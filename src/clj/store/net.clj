(ns store.net
  (:use store.api
        [clojure.java.io :only [file copy]]
        [clojure.contrib.shell :only [sh]]
        [store.message :only [write-msg read-msg]]
        [plumbing.core :only [with-timeout]])
  (:import (java.net Socket InetAddress)))

(defn client-socket [^String host ^Integer port f]
  (let [client (Socket. (InetAddress/getByName host) port)
        os (.getOutputStream client)
        ins (.getInputStream client)]
    (f ins os)))

(defn req [cmd]
  (fn [^InputStream ins
       ^OutputStream os]
    (write-msg os cmd)
    (-> (read-msg ins)
        first)))

(defn net-bucket
  "Provides bucket impl for a network interface to a store."
  [& {:keys [^String name
             ^String host
             port
             timeout]
      :or {timeout 10}}]
  ;; Client will later use a pool
  (let [client (with-timeout timeout
                 (partial client-socket host port))]
    (reify
      IReadBucket
      (bucket-get [this k]
                  (-> (client (req ["GET" name (pr-str k)]))
                      read-string))
      (bucket-keys [this]
                   (client (req ["KEYS" name])))
      (bucket-seq [this]
                  (client (req ["SEQ" name])))
      (bucket-exists? [this k]
                      (client (req ["EXISTS" name (pr-str k)])))

      IWriteBucket
      (bucket-put [this k v]
                  (-> (client (req ["PUT" name (pr-str k) (pr-str v)]))
                      read-string))
      (bucket-delete [this k]
                     (-> (client (req ["DELETE" name (pr-str k)]))
                         read-string))
      (bucket-update [this k f]
                     (client (req ["UPDATE" name (pr-str k)])))
      (bucket-sync [this]
                   (client (req ["SYNC" name])))
      (bucket-close [this]
                    (client (req ["CLOSE" name]))))))