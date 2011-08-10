(ns store.core
  (:use plumbing.core
	[plumbing.error :only [assert-keys]]
        [clojure.java.io :only [file]])
  (:require 
   [ring.util.codec :as ring]
   [clojure.string :as str]
   [clj-json.core :as json]
   [clj-time.coerce :as time.coerce])
  (:import [java.util.concurrent ConcurrentHashMap]
           [java.io File]))

(defprotocol IReadBucket
  (bucket-get [this k] "fetch value for key")
  (bucket-batch-get [this ks] "return seq of [k v] pairs")
  (bucket-exists? [this k] "does key-value pair exists")
  (bucket-keys [this] "seq of existing keys")
  (bucket-seq [this] "seq of [k v] elems")
  (bucket-modified [this k] "joda datetime of key modification"))

(defprotocol IWriteBucket
  (bucket-put [this k v]
              "write value for key. return value can be anything")
  (bucket-delete [this k] "remove key-value pair")
  (bucket-update [this k f])
  (bucket-sync [this])
  (bucket-close [this])
  ;; optional merging functins
  (bucket-merge [this k v] "merge v into current value")
  (bucket-merger [this] "return the fn used to merge. should be 3 args of [k cur-val new-val]"))


;;; Default Bucket Operations

(defn default-bucket-exists? [b k]
  (find-first
   (partial = k)
   (bucket-keys b)))

(defn default-bucket-batch-get [b ks]
  (for [k ks] [k (bucket-get b k)]))

(defn default-bucket-update [b k f]
  (->>  k
        (bucket-get b)
        f
        (bucket-put b k)))

(defn default-bucket-seq [b]
  (for [k (bucket-keys b)]
    [k (bucket-get b k)]))

(defn default-bucket-keys [b]
  (map first (bucket-seq b)))

(defn default-bucket-merge [b merge-fn k v]
  (bucket-update b k (fn [v-to] (merge-fn v-to v))))

(defn with-merge [b merge-fn]
  (reify           
    IWriteBucket
    (bucket-put [this k v] (bucket-put b k v))
    (bucket-delete [this k] (bucket-delete b k))
    (bucket-update [this k f] (bucket-update b k f))
    (bucket-sync [this] (bucket-sync b))
    (bucket-close [this] (bucket-close b))
    (bucket-merge [this k v]
                  (default-bucket-merge b (partial merge-fn k) k v))
    (bucket-merger [this] merge-fn)

    IReadBucket
    (bucket-get [this k] (bucket-get b k))
    (bucket-batch-get [this k] (bucket-batch-get b k))
    (bucket-exists? [this k] (bucket-exists? b k))
    (bucket-keys [this] (bucket-keys b))
    (bucket-seq [this] (bucket-seq b))
    (bucket-modified [this k] (bucket-modified b k))))

(defn compose-buckets [read-b write-b]
  (reify           
    IWriteBucket
    (bucket-put [this k v] (bucket-put write-b k v))
    (bucket-delete [this k] (bucket-delete write-b k))
    (bucket-update [this k f] (bucket-update write-b k f))
    (bucket-sync [this] (bucket-sync write-b))
    (bucket-close [this] (bucket-close write-b))
    (bucket-merge [this k v] (bucket-merge write-b k v))
    (bucket-merger [this] (bucket-merger write-b))

    IReadBucket
    (bucket-get [this k] (bucket-get read-b k))
    (bucket-batch-get [this k] (bucket-batch-get read-b k))
    (bucket-exists? [this k] (bucket-exists? read-b k))
    (bucket-keys [this] (bucket-keys read-b))
    (bucket-seq [this] (bucket-seq read-b))
    (bucket-modified [this k] (bucket-modified read-b k))))

(defn hashmap-bucket [^ConcurrentHashMap h]
  (reify IReadBucket

	   (bucket-keys [this]
			(enumeration-seq (.keys h)))
	   (bucket-get [this k]
		       (.get h k))
	   (bucket-batch-get [this ks] (default-bucket-batch-get this ks))
       
	   (bucket-seq [this]
		       (for [^java.util.Map$Entry e
			     (.entrySet h)]
			 [(.getKey e) (.getValue e)]))

	   (bucket-exists? [this k]
			   (.containsKey h k))

	   IWriteBucket
	   (bucket-put [this k v]
		       (.put h k v))
	   (bucket-delete [this k]
			  (.remove h k))
	   (bucket-update [this k f]
			  (loop []
			    (let [v (.get h k) new-v (f v)			
				  replaced? (cond
					     (nil? v) (nil? (.putIfAbsent h k new-v))
					     (nil? new-v) (or (nil? v) (.remove h k v))
					     :else (.replace h k v new-v))]
			      (when (not replaced?)
				(recur)))))
	   (bucket-sync [this] nil)
	   (bucket-close [this] nil)))

(defn copy-bucket [src dst]
  (doseq [k (bucket-keys src)]
    (bucket-put dst k (bucket-get src k))))

(defn bucket-inc [b k]
  (bucket-update
   b k
   (fnil inc 0)))

(defn bucket-merge-to!
  "merge takes (k to-value from-value)"
  [from to]
  {:pre [(or (map? from) (satisfies? IReadBucket from))
         (and (satisfies? IWriteBucket to))]}	 
  (doseq [[k v] (if (map? from) from
                    (bucket-seq from))]
    (bucket-merge to k v))
  to)

(defn bucket-flush-seq! [b]
  (for [k (bucket-keys b)
	:let [v (bucket-delete b k)]
	:when v]
    [k v]))

 (defn bucket-flush-to!
  "merge takes (k to-value from-value)"
  [from tos]
  (doseq [[k v] (bucket-flush-seq! from)
	  to tos]
    (bucket-merge to k v)))

(defn with-multicast
  [buckets]
  (reify
    store.core.IWriteBucket
    (bucket-put [this k v]
                (doseq [b buckets]
                  (bucket-put b k v)))))

(defn add-write-listeners [b listener-buckets]
  (compose-buckets b     
     (with-multicast (cons b listener-buckets))))


;;TODO: remove flush check, moving away from legacy api.
(defmulti bucket #(do (assert (not (contains? % :flush)))
		   (or (:type %) :mem)))

(defn with-flush
  "takes a bucket that has with-merge and returns an in-memory bucket which will use bucket-merge to merge values using the flush-merge-fn and when bucket-sync is called on return bucket
  will flush memory bucket into underlying bucket using underyling bucket merge fn"
  ([b]
     (with-flush b (bucket-merger
                    (if (coll? b) (first b) b))))
  ([b merge-fn]
     (let [buckets (if (coll? b) b [b])
           mem-bucket (with-merge (bucket {:type :mem}) merge-fn)
           do-flush! #(bucket-flush-to! mem-bucket buckets)]
       (->> (reify
	    store.core.IWriteBucket
	    (bucket-merge [this k v]
			  (bucket-merge mem-bucket k v))
	    (bucket-update [this k f]
			   (bucket-update mem-bucket k f))
	    (bucket-sync [this]
			 (do-flush!)
			 (doseq [b buckets]
			    (bucket-sync b)))
	    (bucket-close [this]
			  (do-flush!)
			  (doseq [b buckets]
			    (bucket-close b)))
	    ;;WARNING: This is not a precise delete, it can fail to delete elements from the underlying bucket because we do not hold a lock around the compound operation of deleting from the memory merge bucket and the on disk target buckets.
	    (bucket-delete [this k]
			   (doseq [b (cons mem-bucket
					   buckets)]
			     (bucket-delete b k))))
	   (compose-buckets b)))))

(defn with-merge-and-flush [bucket flush]
  (-> bucket
      (with-merge flush)
      (with-flush flush)))

(defmethod bucket :fs [{:keys [name path merge] :as args}]
	   (assert-keys [:name :path] args)
	   (let [dir-path (str (file path name))
		 f (if (string? dir-path)
		     (file dir-path)
		     dir-path)]
	     (.mkdirs f)
	     (->
	      (reify
	       IReadBucket
	       (bucket-get [this k]
			   (let [f (File. f ^String (ring/url-encode k))]
			     (when (.exists f) (-> f slurp read-string))))
	       (bucket-batch-get [this ks] (default-bucket-batch-get this ks))
	       (bucket-seq [this] (default-bucket-seq this))     
	       (bucket-exists? [this k]		
			       (let [f (File. f ^String (ring/url-encode k))]
				 (.exists f)))
	       (bucket-keys [this]
			    (for [^File c (.listFiles f)
				  :when (and (.isFile c) (not (.isHidden c)))]
			      (ring/url-decode (.getName c))))
	       (bucket-modified [this k]
				(time.coerce/from-long
				 (.lastModified (File. dir-path 
						       ^String (ring/url-encode k)))))
	       
	       IWriteBucket
	       (bucket-put [this k v]
			   (let [f (File. f ^String(ring/url-encode k))]
			     (spit f (pr-str v))))
	       (bucket-delete [this k]
			      (let [f (File. f ^String (ring/url-encode  k))]
				(.delete f)))
	       (bucket-update [this k f]
			      (default-bucket-update this k f))
	       (bucket-sync [this] nil)
	       (bucket-close [this] nil))
	      (?> merge with-merge-and-flush merge))))



(defmethod bucket :mem [{:keys [merge]}]
  (-> (ConcurrentHashMap.)
      hashmap-bucket 	     
      (?> merge with-merge merge)))

;;; Extend Read buckets to clojure maps

(def ^:private read-bucket-map-impls
     {:bucket-get (fn [this k] (this k))
      :bucket-seq (fn [this] (seq this))
      :bucket-batch-get (fn [this ks] (default-bucket-batch-get this ks))
      :bucket-keys (fn [this] (keys this))
      :bucket-exists? (fn [this k] (find this k))
      })

(doseq [c [clojure.lang.PersistentHashMap
	   clojure.lang.PersistentArrayMap
	   clojure.lang.PersistentStructMap]]
  (extend c IReadBucket read-bucket-map-impls))


;;; Generic Buckets

(defn caching-bucket [f merge-fn]
  (let [b (with-merge (bucket {:type :mem}) merge-fn)]
    (compose-buckets
     (reify
      store.core.IReadBucket
      (bucket-get [this k]
		  (or (bucket-get b k)	    
		      (do 
			(bucket-merge b k (f k))
			(bucket-get b k))))
      (bucket-batch-get [this ks] (default-bucket-batch-get this ks)))     
     b)))


(def streaming-ops #{:keys :seq})

(def read-ops
  {:get bucket-get
   :batch-get bucket-batch-get
   :seq bucket-seq
   :keys bucket-keys
   :get-ensure
   (fn [bucket key default-fn]
     (if-let [v (bucket-get bucket key)]
       v
       (let [res (default-fn)]
         (bucket-put bucket key res)
         res)))
   :exists? bucket-exists?
   :modified bucket-modified})

(def write-ops
     {:put bucket-put
      :delete bucket-delete
      :merge bucket-merge
      :update bucket-update
      :sync bucket-sync
      :close bucket-close})