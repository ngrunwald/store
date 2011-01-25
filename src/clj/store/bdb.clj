(ns store.bdb
  (:import com.sleepycat.je.DatabaseEntry
	   com.sleepycat.je.LockMode
	   com.sleepycat.je.Environment
	   com.sleepycat.je.EnvironmentConfig
	   com.sleepycat.je.DatabaseConfig
	   com.sleepycat.je.OperationStatus)
  (:use store.api))

;;http://download.oracle.com/docs/cd/E17277_02/html/GettingStartedGuide

(defn from-entry [e]
  (read-string (String. (.getData e) "UTF-8")))

(defn to-entry [clj]
  (DatabaseEntry. (.getBytes (pr-str clj) "UTF-8")))

(defn bdb-put [db k v]
  (let [entry-key (to-entry k)
	entry-val (to-entry v)]
    (.put db nil entry-key entry-val)))

(defn bdb-get [db k]
  (let [entry-key (to-entry k)
	entry-val (DatabaseEntry.)]
    (if (= (.get db nil entry-key entry-val LockMode/DEFAULT)
	   OperationStatus/SUCCESS)
      (from-entry entry-val))))

(defn entries-seq [db]
  (let [cursor (.openCursor db nil nil)]
    (lazy-seq ((fn [acc]
		 (let [k (DatabaseEntry.)
		       v (DatabaseEntry.)]
		   (if (not (= (.getNext cursor k v LockMode/DEFAULT)
			       OperationStatus/SUCCESS))
		     (do (.close cursor)
			 acc)
		     (recur
		      (cons [(from-entry k)
			     (from-entry v)]
			    acc)))))
		 []))))

(defn bdb-delete [db k]
  (let [entry-key (to-entry k)]
    (.delete db nil entry-key)))

(defn bdb-open [env-path db-name]
  (let [env-config (doto (EnvironmentConfig.)
		     (.setAllowCreate true))
	db-env (-> env-path java.io.File. (Environment. env-config))
	db-config (doto (DatabaseConfig.)
		    (.setAllowCreate true))]
    (.openDatabase db-env nil db-name db-config)))

(defn bdb-bucket
  "returns callback fn for a Berkeley DB backed bucket."
  ([^String bucket  &
    {:keys [env-path]
     :or {env-path "/var/bdb/"}}]
     (let [db (bdb-open env-path bucket)]
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
	      (bucket-exists? [this k] (default-bucket-exists? this k))))))