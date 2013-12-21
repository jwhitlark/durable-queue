(ns durable-queue
  (:require
    [clojure.java.io :as io]
    [byte-streams :as bs]
    [clojure.string :as str]
    [primitive-math :as p]
    [taoensso.nippy :as nippy])
  (:import
    [java.util.concurrent
     LinkedBlockingQueue
     TimeoutException
     TimeUnit]
    [java.util.zip
     CRC32]
    [java.util.concurrent.locks
     ReentrantReadWriteLock]
    [java.io
     Writer
     File
     RandomAccessFile
     IOException]
    [java.nio.channels
     FileChannel
     FileChannel$MapMode]
    [java.nio
     ByteBuffer
     MappedByteBuffer]))

;;;

(defmacro ^:private with-lock [lock & body]
  `(let [^ReentrantReadWriteLock lock# ~lock
         read-lock# (.readLock lock#)]
     (do
       (.lock read-lock#)
       (try
         ~@body
         (finally
           (.unlock read-lock#))))))

(defmacro ^:private with-exclusive-lock [lock & body]
  `(let [^ReentrantReadWriteLock lock# ~lock
         write-lock# (.writeLock lock#)]
     (do
       (.lock write-lock#)
       (try
         ~@body
         (finally
           (.unlock write-lock#))))))

;;;

(defn- checksum ^long [^long length ^bytes ary]
  (let [crc (CRC32.)]
    (dotimes [i 4]
      (.update crc (p/>> length i)))
    (.update crc ary)
    (.getValue crc)))

;;;

(def ^:private ^:const header-size 14)

(defprotocol ITask
  (^:private status [_] "Returns the task status")
  (^:private status! [_ status] "Sets the task status"))

;; a single task within a slab, assumes that the buffer is sliced around
;; the task's boundaries
(defrecord Task [buf-fn task-ref]
  clojure.lang.IDeref
  (deref [_]
    @task-ref)
  ITask
  (status [_]
    (case (.get ^ByteBuffer (buf-fn) 1)
      0 :incomplete
      1 :in-progress
      2 :complete))
  (status! [_ status]
    (.put ^ByteBuffer (buf-fn) 1
      (case status
        :incomplete 0
        :in-progress 1
        :complete 2))
    nil))

(defn- task [buf-fn lock]
  (with-lock lock
    (Task. buf-fn
      (delay
        (let [^ByteBuffer buf (buf-fn)
              checksum' (.getLong buf 2)
              ary (bs/to-byte-array (.position buf header-size))]
          (when-not (== (checksum (.getInt buf 10) ary) checksum')
            (prn (.get buf 0) (.get buf 1) (.getLong buf 2) (.getInt buf 10))
            (throw (IOException. "checksum mismatch")))
          (nippy/thaw-from-bytes ary))))))

(defmethod print-method Task [t ^Writer w]
  (.write w
    (str "< " (status t) " | " (pr-str @t) " >")))

;;;

(defprotocol ITaskSlab
  (^:private unmap [_] "Temporarily releases mapped byte buffer until it's needed again.")
  (^:private ^ByteBuffer buffer [_])
  (^:private append-to-slab! [_ descriptor])
  (^:private read-write-lock [_]))

;; the byte layout is
;; [ exists?  : int8
;;   state    : int8
;;   checksum : int64
;;   size     : int32 
;;   payload  : array ]
;; valid values for 'exists' is 0 (no), 1 (yes)
;; valid values for 'state' is 0 (unclaimed), 1 (in progress), 2 (complete)
(defn- slab->task-seq
  "Takes a slab, and returns a sequence of the tasks it contains."
  ([slab]
     (slab->task-seq slab 0))
  ([slab pos]
     (lazy-seq
       (let [^ByteBuffer
             buf' (-> (buffer slab)
                    .duplicate
                    (.position pos))]

         ;; is there a next task, and is there space left in the buffer?
         (when (and
                 (pos? (.remaining buf'))
                 (== 1 (.get buf')))
           
           (let [status (.get buf')
                 checksum (.getLong buf')
                 size (.getInt buf')]
             (cons

               (vary-meta
                 (task
                   #(-> (buffer slab)
                      .duplicate
                      (.position pos)
                      ^ByteBuffer
                      (.limit (+ pos header-size size))
                      .slice)
                   (read-write-lock slab))
                 assoc ::slab slab)

               (slab->task-seq
                 slab
                 (+ pos header-size size)))))))))

(deftype TaskSlab
  [filename
   buf+fc+raf ;; a clearable atom-thunk holding the resources associated with the buffer
   position   ;; an atom storing the write position of the slab
   lock]
  ITaskSlab

  (read-write-lock [_]
    lock)

  (buffer [_]
    (locking buf+fc+raf
      (if-let [x @buf+fc+raf]
        (first x)
        (first
          (swap! buf+fc+raf
            (fn [x]
              (or x
                (let [_ (assert (.exists (io/file filename)))
                      raf (RandomAccessFile. (io/file filename) "rw")
                      fc (.getChannel raf)
                      buf (.map fc FileChannel$MapMode/READ_WRITE 0 (.length raf))]
                  [buf fc raf]))))))))
  
  (unmap [_]
    (with-exclusive-lock lock
      (when-let [x @buf+fc+raf]
        (let [[^MappedByteBuffer buf ^FileChannel fc ^RandomAccessFile raf] x]
          (.force buf)
          (.close raf)
          (.close fc)
          (reset! buf+fc+raf nil)))))

  (append-to-slab! [this descriptor]
    (locking this
      (let [ary (nippy/freeze descriptor)
            cnt (count ary)
            pos @position

            ^ByteBuffer
            buf (-> (buffer this)
                  .duplicate
                  (.position pos))]

        (when (> (.remaining buf) (+ (count ary) header-size))
          ;; write to the buffer
          (doto buf
            (.position pos)
            (.put (byte 1))   ;; exists
            (.put (byte 0))   ;; incomplete
            (.putLong (checksum cnt ary))
            (.putInt cnt)
            (.put ary)
            (.put (byte 0))) ;; next doesn't exist
              
          (swap! position + header-size cnt)
            
          ;; return a task to enqueue in-memory
          (with-meta
            (task
              #(-> (buffer this)
                 .duplicate
                 (.position pos)
                 ^ByteBuffer
                 (.limit (+ pos header-size cnt))
                 .slice)
              lock)
            {::slab this})))))
  
  clojure.lang.Seqable
  (seq [this]
    (slab->task-seq this))

  Comparable
  (compareTo [_ x]
    (assert (instance? TaskSlab x))
    (compare filename (.filename ^TaskSlab x))))

(def ^:private fs-monitor (Object.))

(defn- delete-slab
  [^TaskSlab slab]
  (locking fs-monitor
    (unmap slab)
    (.delete (io/file (.filename slab)))))

(defn- sync-slab
  [^TaskSlab slab]
  (when (.exists (io/file (.filename slab)))
    (.force ^MappedByteBuffer (buffer slab))))

(defn- create-slab
  "Creates a new slab file, ensuring a new file name that is lexicographically greater than
   any existing files for that queue name."
  ([directory q-name size]
     (locking fs-monitor
       (let [pattern (re-pattern (str "^" q-name "_(\\d+)"))
             last-number (->> directory
                           io/file
                           .listFiles
                           (map #(.getName ^File %))
                           (map #(second (re-find pattern %)))
                           (remove nil?)
                           (map #(Long/parseLong %))
                           sort
                           last)
             n (if last-number (inc last-number) 0)
             f (io/file (str directory "/" q-name "_" (format "%06d" n)))]

         (when-not (.createNewFile f)
           (throw (IOException. (str "Could not create new slab file at " (.getAbsolutePath f)))))

         (let [raf (doto (RandomAccessFile. f "rw")
                     (.setLength size))
               fc (.getChannel raf)
               buf (.map fc FileChannel$MapMode/READ_WRITE 0 size)]
           (doto buf
             (.put 0 (byte 0))
             .force)
           (TaskSlab. (.getAbsolutePath f) (atom [buf fc raf]) (atom 0) (ReentrantReadWriteLock.)))))))

(defn- file->slab
  "Transforms a file into a slab representing that file's contents."
  [filename]
  (let [pos (atom 0)
        slab (TaskSlab. filename (atom nil) pos (ReentrantReadWriteLock.))
        len (->> slab
              seq
              (map #((:buf-fn %)))
              (map #(.remaining ^ByteBuffer %))
              (reduce +))]
    (reset! pos len)
    (unmap slab)
    slab))

(defn- directory->queue->slab-files
  "Returns a map of queue names onto slab files for that queue."
  [directory]
  (let [queue->file (->> directory
                      io/file
                      .listFiles
                      (filter #(re-find #"\w+_\d+" (.getName ^File %)))
                      (group-by #(second (re-find #"(\w+)_\d+" (.getName ^File %)))))]
    (zipmap
      (keys queue->file)
      (map
        (fn [files]
          (->> files
            (map #(.getAbsolutePath ^File %))
            sort))
        (vals queue->file)))))

;;;

(defprotocol IQueues
  (take!
    [_ q-name]
    [_ q-name timeout timeout-val]
    "A blocking dequeue from `name`.  If `timeout` is specified, returns `timeout-val` if
     no task is available within `timeout` milliseconds.")
  (put!
    [_ q-name task-descriptor]
    [_ q-name task-descriptor timeout]
    "A blocking enqueue to `name`.  If `timeout` is specified, returns `false` if unable to
     enqueue within `timeout` milliseconds."))

(defn queues
  "Creates a point of interaction for queues, backed by disk storage in `directory`.

   The following options can be specified:

       max-queue-size - the maximum number of elements that can be in the queue before `put!`
                        blocks.  Defaults to `Integer/MAX_VALUE`.

       complete? - a predicate that is run on pre-existing tasks to check if they were already
                   completed.  If the tasks in the queue are non-idempotent, this must be
                   specified for correct behavior.  Defaults to always returning false.

       slab-size - The size, in bytes, of the backing files for the queue.  Defaults to 16mb.

       fsync-put? - if true, each `put!` will force an fsync.  Defaults to true.

       fsync-take? - if true, each `take!` will force an fsync.  Defaults to false."
  ([directory]
     (queues directory nil))
  ([directory
    {:keys [max-queue-size
            complete?
            slab-size
            fsync-put?
            fsync-take?]
     :or {max-queue-size Integer/MAX_VALUE
          complete? nil
          slab-size (* 64 1024 1024)
          fsync-put? true
          fsync-take? false}}]

     (let [queue (memoize (fn [_] (LinkedBlockingQueue. (int max-queue-size))))
           queue->files (directory->queue->slab-files directory)
           queue->slabs (atom
                          (zipmap
                            (keys queue->files)
                            (->> queue->files
                              vals
                              (map #(map file->slab %))
                              vec)))
           slabs (->> @queue->slabs vals (apply concat))
           slab->count (zipmap
                         slabs
                         (map #(atom (count (seq %))) slabs))
           create-new-slab (fn [q-name]
                             (let [slab (create-slab directory q-name slab-size)
                                   empty-slabs (->> (@queue->slabs q-name)
                                                 (filter (fn [slab]
                                                           (->> slab
                                                             seq
                                                             (remove #(= :complete (status %)))
                                                             empty?)))
                                                 set)]

                               ;; delete empty slabs
                               (doseq [s empty-slabs]
                                 (delete-slab s))

                               ;; update list of active slabs
                               (swap! queue->slabs update-in [q-name]
                                 #(conj (vec (remove empty-slabs %)) slab))

                               ;; unmap all slabs but the first (which is being consumed)
                               ;; and the last (which is being written to)
                               (doseq [s (-> (@queue->slabs q-name) rest butlast)]
                                 (unmap s))
                               slab))]

       ;; populate queues with pre-existing tasks
       (let [empty-slabs (atom #{})]
         (doseq [[q slabs] @queue->slabs]
           (let [^LinkedBlockingQueue q' (queue q)]
             (doseq [slab slabs]
               (let [tasks (->> slab
                             seq
                             (map #(vary-meta % assoc ::queue q' ::fsync? fsync-take?))
                             (remove #(or (= :complete (status %))
                                        (when complete? (complete? @%)))))]
                 
                 (if (empty? tasks)
                   
                   ;; if there aren't any active tasks, just delete the slab
                   (do
                     (delete-slab slab)
                     (swap! empty-slabs conj slab))
                   
                   (doseq [task tasks]
                     (status! task :incomplete)
                     (when-not (.offer q' task)
                       (throw
                         (IllegalArgumentException.
                           "'max-queue-size' insufficient to hold existing tasks.")))))

                 (sync-slab slab)))))

         (swap! queue->slabs
           (fn [m]
             (->> m
               (map
                 (fn [[q slabs]]
                   [q (remove @empty-slabs slabs)]))
               (into {})))))

       (reify IQueues

         (take! [this q-name timeout timeout-val]
           (let [q-name (munge (name q-name))
                 ^LinkedBlockingQueue q (queue q-name)]
             (try
               (if-let [t (if (zero? timeout)
                            (.poll q)
                            (.poll q timeout TimeUnit/MILLISECONDS))]
                 (do
                   (status! t :in-progress)
                   ;; we don't need to fsync here, because in-progress and incomplete
                   ;; are effectively equivalent on restart
                   t)
                 timeout-val)
               (catch TimeoutException _
                 timeout-val))))
         (take! [this q-name]
           (take! this q-name Long/MAX_VALUE nil))

         (put! [_ q-name task-descriptor timeout]
           (let [q-name (munge (name q-name))
                 ^LinkedBlockingQueue q (queue q-name)
                 slab! (fn []
                         (let [slabs (@queue->slabs q-name)
                               slab  (last slabs)
                               task  (when slab
                                       (append-to-slab! slab task-descriptor))

                               ;; if no task was created, we need to create a new slab file
                               ;; and try again
                               slab  (if task
                                       slab
                                       (create-new-slab q-name))
                               task  (or task (append-to-slab! slab task-descriptor))]

                           (when-not task
                             (throw
                               (IllegalArgumentException.
                                 (str "Can't enqueue task whose serialized representation is larger than :slab-size, which is currently " slab-size))))
                           
                           (when fsync-put?
                             (sync-slab slab))
                           task))
                 
                 queue! (fn [task]
                          (if (zero? timeout)
                            (.offer q task)
                            (.offer q task timeout TimeUnit/MILLISECONDS)))]
             (locking q
               (queue!
                 (vary-meta (slab!) assoc
                   ::queue q
                   ::fsync? fsync-take?)))))
         (put! [this q-name task-descriptor]
           (put! this q-name task-descriptor Long/MAX_VALUE))))))

;;;

(defn task-seq
  "Returns an infinite lazy sequence of tasks for `q-name`."
  [qs q-name]
  (lazy-seq
    (cons
      (take! qs q-name)
      (task-seq qs q-name))))

(defn immediate-task-seq
  "Returns a finite lazy sequence of tasks for `q-name` which terminates once there are
   no more tasks immediately available."
  [qs q-name]
  (lazy-seq
    (let [task (take! qs q-name 0 ::none)]
      (when-not (= ::none task)
        (cons
          task
          (immediate-task-seq qs q-name))))))

(defn interval-task-seq
  "Returns a lazy sequence of tasks that can be consumed in `interval` milliseconds.  This will
   terminate after that time has elapsed, even if there are still tasks immediately available."
  [qs q-name interval]
  (let [now (System/currentTimeMillis)]
    (lazy-seq
      (let [now' (System/currentTimeMillis)
            remaining (- interval (- now' now))]
        (when (pos? remaining)
          (let [task (take! qs q-name remaining ::none)]
            (when-not (= ::none task)
              (cons
                task
                (interval-task-seq q-name (- interval (- (System/currentTimeMillis) now)))))))))))

(defn complete!
  "Marks a task as complete."
  [task]
  (status! task :complete)
  (when (-> task meta ::fsync?)
    (sync-slab (-> task meta ::slab)))
  true)

(defn retry!
  "Marks a task as available for retry."
  [task]
  (status! task :incomplete)
  (when (-> task meta ::fsync?)
    (sync-slab (-> task meta ::slab)))
  (let [^LinkedBlockingQueue q (-> task meta ::queue)]
    (.offer q
      task
      Long/MAX_VALUE
      TimeUnit/MILLISECONDS)))
