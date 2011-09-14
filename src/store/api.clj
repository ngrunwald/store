(ns store.api
  (:use [plumbing.core :only [?> ?>> map-from-vals map-map]]
	[clojure.java.io :only [file]]
	[plumbing.error :only [with-ex logger]]
	store.core
	store.net
	store.bdb
        store.s3)
  (:require [plumbing.observer :as obs])
  (:import [java.util.concurrent Executors TimeUnit
	    ConcurrentHashMap]))

(defn create-buckets [{:keys [read write] :as spec}]
  (let [r (bucket (if read (merge spec read) spec))
	w (if write
	    (bucket (merge spec write))
	    r)]
    (assoc spec
      :read r :write w      
      :write-spec (or write spec))))

(defn buckets [specs & [context]]
  (->> specs
       (map #(->> %
		  (?>> (string? %) hash-map :name)
		  (merge context)
		  create-buckets
		  ((fn [m] [(:name m) m]))))
       (into {})
       (ConcurrentHashMap.)
       hashmap-bucket))

(declare bucket-ops store-op)

(deftype Store [bucket-map dispatch context]
  clojure.lang.IFn
  (invoke [this op]
	  (dispatch this op nil))
  (invoke [this op bucket-name]
	  (dispatch this op bucket-name))
  (invoke [this op bucket-name key]
	  (dispatch this op bucket-name key))
  (invoke [this op bucket-name key val]
	  (dispatch this op bucket-name key val))
  (applyTo [this args]
	   (apply dispatch this args)))

(def bucket-ops
     {:buckets (fn [^store.api.Store store name]  ;;HACK, don't need name.  just puinting until we do api overahul.
		 (bucket-keys (.bucket-map store)))
      :bucket (fn [^store.api.Store store bucket-name]
		(bucket-get (.bucket-map store) bucket-name))
      :add (fn [^store.api.Store store bucket-name]
	     (let [bucket (create-buckets (assoc (.context store)
					    :name bucket-name))]
	       (bucket-put
		(.bucket-map store)
		bucket-name
		bucket)))
      :remove (fn [^store.api.Store store bucket-name]
		(bucket-delete
		 (.bucket-map store) bucket-name))})

(defn store-op [^store.api.Store store op & args]
  (let [{:keys [type]} (.context store)
        name (first args)
	args (rest args)]
    (cond
     ;;should just send fn.  over rest, it gets quoted and then evaled on server.
     (= op :eval) ((eval name) store)
     (find bucket-ops op)
     (let [local ((op bucket-ops) store name)]
       (if-not (= type :rest) local
               ((op rest-bucket-ops) store name)))
     :else
     (let [read (read-ops op)
	   spec (->> name (bucket-get (.bucket-map store)))
	   b (if read (:read spec)
		 (:write spec))
	   f (or read (write-ops op))]
       (when-not b
	 (when-not spec
	   (throw (Exception. (format "No bucket %s" name))))
	 (let [read-or-write (if read "read" "write")]
	   (throw (Exception. (format "No %s operation for bucket %s" read-or-write name)))))
       (apply f b args)))))

;;TODO: fucked, create a coherent model for store flush and shutdown
(defn flush! [^Store store]
  (doseq [[_ spec] (bucket-seq (.bucket-map store))
	  :when (-> spec :write-spec)]
    (bucket-sync (:write spec))))

(defn shutdown [^Store store]
  (doseq [[name spec] (bucket-seq (.bucket-map store))
	  f (:shutdown spec)]
    (with-ex (logger) f))
  (doseq [[name spec] (bucket-seq (.bucket-map store))]
    (with-ex (logger)  bucket-close (:read spec))
    (with-ex (logger)  bucket-close (:write spec))))

(defn start-flush-pools [bucket-map]
  (->> bucket-map
       bucket-seq
       (map-map
	(fn [{:keys [write,write-spec] :as bucket-spec}]
	  (let [{:keys [flush-freq,num-flush-threads]} write-spec]
	    (if-not flush-freq
	      bucket-spec
	      (let [pool (doto (Executors/newSingleThreadScheduledExecutor)
			   (.scheduleAtFixedRate			  
			    #(with-ex (logger) bucket-sync write)
			    (long 0) (long flush-freq)
			    TimeUnit/SECONDS))]
		(-> bucket-spec
		    (assoc :flush-pool pool)
		    (update-in [:shutdown]
			       conj
			       (fn []
				 (bucket-sync write)
				 (.shutdownNow pool)))))))))
       doall
       ^java.util.Map (into {})
       (ConcurrentHashMap.)
       hashmap-bucket))

(defn observe-merge [ks old v]
  (if old (merge-with + old v) v))

(defn observe-report [m duration]
  (map-map
   (fn [{:keys [queue-size] :as b}]
     (let [{:keys [count size]} queue-size]
       (if (and count (> count 0))
         (assoc b :queue-size (/ size 1.0 count))
         b)))
   m))

(defn store [bucket-specs & [context]]
  (let [context (update-in (or context {}) [:observer]
                           obs/sub-observer (obs/gen-key "store"))]
    (-> (buckets bucket-specs context)
	start-flush-pools
	(Store.
	 (obs/observed-fn
	    (:observer context) :counts
	    {:type :counts :group (obs/observed-fn
				   (:observer context) :counts
				   {:type :counts :group (fn [[_ op b]] [b op])}
				   store-op)}
	    store-op)

	 context))))

(defn clone [^store.api.Store s & [context]]
  (store (bucket-keys (.bucket-map s)) context))

(defn mirror-remote [spec]
  (let [s (store [] spec)
	ks (s :buckets)]
    (store ks spec)))

(defn copy [in out bucket & {:keys [select skip]  ;;skip to skip keys
			     :or {select identity ;;select to select values
}}]
  (doseq [[k v] (->> (in :seq bucket)
		     (?>> skip filter (comp skip first)))
	  :let [vs (select v)]]
    (out :put bucket k vs))
  (out :sync bucket))

(defn sync-stores [in out bucket & args]
  (apply copy in out bucket
	 (concat args [:skip
		       (complement (partial out :get bucket))])))

(defn merge-stores [host other]
  (doseq [[k v] (bucket-seq (.bucket-map other))]
    (bucket-put (.bucket-map host) k v))
  host)