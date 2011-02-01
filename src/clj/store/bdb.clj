(ns store.bdb
  (:import (com.sleepycat.je Database 
                             DatabaseEntry
                             LockMode
                             Environment
                             EnvironmentConfig
                             DatabaseConfig
                             OperationStatus
                             CheckpointConfig
                             CacheMode))
  (:use store.api))

;;http://download.oracle.com/docs/cd/E17277_02/html/GettingStartedGuide

(def cache-modes {:default CacheMode/DEFAULT
                  :evict-bin CacheMode/EVICT_BIN
                  :evict-ln CacheMode/EVICT_LN
                  :keep-hot CacheMode/KEEP_HOT
                  :make-cold CacheMode/MAKE_COLD
                  :unchanged CacheMode/UNCHANGED})

(defn from-entry [^DatabaseEntry e]
  (read-string (String. (.getData e) "UTF-8")))

(defn to-entry [clj]
  (DatabaseEntry. (.getBytes (pr-str clj) "UTF-8")))

(defn bdb-put [^Database db k v]
  (let [entry-key (to-entry k)
	entry-val (to-entry v)]
    (.put db nil entry-key entry-val)))

(defn bdb-get [^Database db k]
  (let [entry-key (to-entry k)
	entry-val (DatabaseEntry.)]
    (if (= (.get db nil entry-key entry-val LockMode/DEFAULT)
	   OperationStatus/SUCCESS)
      (from-entry entry-val))))

(defn entries-seq
 [^Database db]
 (let [cursor (.openCursor db nil nil)]
   (take-while identity
               (repeatedly
                #(let [k (DatabaseEntry.)
                       v (DatabaseEntry.)]
                   (if (not (= (.getNext cursor k v LockMode/DEFAULT)
                               OperationStatus/SUCCESS))
                     ;; return nil
                     (do (.close cursor)
                         nil)
                     [(from-entry k)
                      (from-entry v)]))))))

(defn bdb-delete [#^Database db k]
  (let [entry-key (to-entry k)]
    (.delete db nil entry-key)))

;;http://download.oracle.com/docs/cd/E17076_02/html/java/com/sleepycat/db/EnvironmentConfig.html
;;http://download.oracle.com/docs/cd/E17076_02/html/java/com/sleepycat/db/EnvironmentConfig.html

(defn bdb-env [env-path read-only-env
               checkpoint-kb checkpoint-mins
               locking cache-percent]
  (let [env-config (doto (EnvironmentConfig.)
                     (.setReadOnly read-only-env)
                     (.setAllowCreate (not read-only-env))
                     (.setLocking locking)
                     (.setCachePercent cache-percent))]
    (doto CheckpointConfig/DEFAULT
      (.setKBytes checkpoint-kb)
      (.setMinutes checkpoint-mins))
    (-> env-path java.io.File. (Environment. env-config))))

(defn bdb-conf [read-only-db deferred-write cache-mode]
  (let []
    (doto (DatabaseConfig.)
      (.setReadOnly read-only-db)
      (.setAllowCreate (not read-only-db))
      (.setDeferredWrite deferred-write)
      (.setCacheMode (cache-modes cache-mode)))))

(defn open-db [db-env db-conf bucket]
  (.openDatabase db-env nil bucket db-conf))

(defn bdb-open
  "Parameters:
   :env-path - bdb environment path
   :bucket - bucket name
   :read-only-env - set bdb environment to be read-only
   :read-only-db - set db to read-only, overrides environment config
   :deferred-write - toggle deferred writing to filesystem
   :checkpoint - toggle checkpointing
   :locking - toggle locking, if turned off then the cleaner is also
   :evict - toggle leaf node eviction in cache
   :cache-percent - percent of heap to use for BDB cache
   :cache-mode - eviction policy for cache"
  [& {:keys [env-path bucket read-only-env
             read-only-db deferred-write
             checkpoint-kb checkpoint-mins
             locking evict cache-percent cache-mode]
      :or {read-only-env false
           read-only-db false
           env-path "/var/bdb/"
           deferred-write false
           checkpoint-kb 0 
           checkpoint-mins 0
           locking true
           cache-percent 60
           cache-mode :default}
      :as opts}]
  (let [db-env (bdb-env env-path read-only-env checkpoint-kb checkpoint-mins
                        locking cache-percent)
        db-conf (bdb-conf read-only-db deferred-write cache-mode)]
    (open-db db-env db-conf bucket)))

(defn bdb-bucket
  "returns callback fn for a Berkeley DB backed bucket."
  [& env]
  (let [^Database db (apply bdb-open env)]
    (reify IBucket
	   (bucket-get [this k]
		       (bdb-get db k))
	   (bucket-put [this k v]
		       (bdb-put db k v))
	   (bucket-keys [this] (default-bucket-keys this))
	   (bucket-seq [this]
		       (entries-seq db))
	   (bucket-delete [this k]
			  (bdb-delete db k))
	   (bucket-update [this k f]
			  (default-bucket-update this k f))
	   (bucket-exists? [this k] (default-bucket-exists? this k))
     (bucket-sync [this] (.sync db))
     (bucket-close [this] (.close db)))))