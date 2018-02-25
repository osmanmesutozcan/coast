(ns coast.alpha.db
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as string]
            [coast.alpha.env :as env]
            [coast.alpha.queries :as queries]
            [coast.alpha.utils :as utils])
  (:refer-clojure :exclude [drop]))

(defn unique-index-error? [error]
  (when (not (nil? error))
    (string/includes? error "duplicate key value violates unique constraint")))

(defn fmt-unique-index-error [s]
  (let [column (->> (re-matches #"(?s)ERROR: duplicate key value violates unique constraint.*Detail: Key \((.*)\)=\((.*)\).*" s)
                    (clojure.core/drop 1)
                    (first))]
    {(keyword column) (str (utils/humanize column) " is already taken")}))

(defn throw-db-exception [e]
  (let [s (.getMessage e)]
    (cond
      (unique-index-error? s) (utils/throw+ (fmt-unique-index-error s))
      :else (throw e))))

(defmacro transact! [f]
  `(try
     ~f
     (catch Exception e#
       (throw-db-exception e#))))

(defn connection []
  (let [db-url (or (env/env :database-url)
                   (env/env :db-spec-or-url))]
    (if (string/blank? db-url)
      (throw (Exception. "Your database connection string is blank. Set the DATABASE_URL or DB_SPEC_OR_URL environment variable"))
      {:connection (jdbc/get-connection db-url)})))

(defn admin-connection []
  (let [db-url (or (env/env :admin-db-spec-or-url)
                   "postgres://localhost:5432")]
    (if (string/blank? db-url)
      (throw (Exception. "Your admin database connection string is blank. Set the ADMIN_DB_SPEC_OR_URL environment variable"))
      {:connection (jdbc/get-connection db-url)})))

(defn sql-vec? [v]
  (and (vector? v)
       (string? (first v))
       (not (string/blank? (first v)))))

(defn query
  ([conn v opts]
   (if (and (sql-vec? v) (map? opts))
     (transact!
       (jdbc/with-db-connection [db-conn conn]
         (jdbc/query db-conn v {:row-fn (partial utils/map-keys utils/kebab)})))
     '()))
  ([conn v]
   (query conn v {})))

(defn query!
  ([conn v opts]
   (let [results (query conn v opts)]
     (if (or (nil? results)
             (empty? results))
       (utils/throw+ {:coast/error "Query results were empty"
                      :coast/error-type :not-found})
       results)))
  ([conn v]
   (query! conn v {})))

(defmacro defq [n filename]
  (let [queries (queries/parts filename)
        {:keys [sql f]} (get queries (str n))]
    (if (nil? sql)
      (throw (Exception. (format "\nQuery %s doesn't exist in %s\nAvailable queries:\n%s\n" n filename (string/join ", " (keys queries)))))
      `(def
         ^{:doc ~sql}
         ~n
         ~(fn [& [m]]
            (let [v (queries/sql-vec sql m)]
              (f (query (connection) v))))))))

(defmacro defq! [n filename]
  (let [queries (queries/parts filename)
        {:keys [sql f]} (get queries (str n))]
    (if (nil? sql)
      (throw (Exception. (format "\nQuery %s doesn't exist in %s\nAvailable queries:\n%s\n" n filename (string/join ", " (keys queries)))))
      `(def
         ^{:doc ~sql}
         ~n
         ~(fn [& [m]]
            (let [v (queries/sql-vec sql m)]
              (f (query! (connection) v))))))))

(defq columns "resources/sql/schema.sql")

(defn create [db-name]
  (let [v [(format "create database %_%" db-name (env/env :coast-env))]]
    (query (admin-connection) v)
    (println "Database" name "created successfully")))

(defn drop [db-name]
  (let [v [(format "drop database %_%" db-name (env/env :coast-env))]]
    (query (admin-connection) v)
    (println "Database" name "dropped successfully")))
