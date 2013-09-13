;; ## SQL/query-related functions for events

(ns com.puppetlabs.puppetdb.query.events
  (:require [com.puppetlabs.utils :as utils]
            [clojure.string :as string]
            [cheshire.core :as json])
  (:use [com.puppetlabs.jdbc :only [underscores->dashes dashes->underscores valid-jdbc-query? add-limit-clause]]
        [com.puppetlabs.puppetdb.scf.storage :only [db-serialize sql-regexp-match]]
        [com.puppetlabs.puppetdb.query :only [compile-term compile-and compile-or compile-not-v2 execute-query]]
        [clojure.core.match :only [match]]
        [clj-time.coerce :only [to-timestamp]]
        [com.puppetlabs.puppetdb.query.paging :only [validate-order-by!]]))

(defn compile-resource-event-inequality
  "Compile a timestamp inequality for a resource event query (> < >= <=).
  The `value` for comparison must be coercible to a timestamp via
  `clj-time.coerce/to-timestamp` (e.g., an ISO-8601 compatible date-time string)."
  [& [op path value :as args]]
  {:post [(map? %)
          (string? (:where %))]}
  (when-not (= (count args) 3)
    (throw (IllegalArgumentException. (format "%s requires exactly two arguments, but %d were supplied" op (dec (count args))))))
  (match [path]
    ["timestamp"]
    (if-let [timestamp (to-timestamp value)]
      {:where (format "resource_events.timestamp %s ?" op)
       :params [(to-timestamp value)]}
      (throw (IllegalArgumentException. (format "'%s' is not a valid timestamp value" value))))

    :else (throw (IllegalArgumentException.
                   (str op " operator does not support object '" path "' for resource events")))))

(defn compile-resource-event-equality
  "Compile an = predicate for resource event query. `path` represents the field to
  query against, and `value` is the value."
  [& [path value :as args]]
  {:post [(map? %)
          (string? (:where %))]}
  (when-not (= (count args) 2)
    (throw (IllegalArgumentException. (format "= requires exactly two arguments, but %d were supplied" (count args)))))
  (let [path (dashes->underscores path)]
    (match [path]
      ["certname"]
      {:where (format "reports.certname = ?")
       :params [value]}

      ["latest_report"]
      {:where (format "resource_events.report %s (SELECT latest_reports.report FROM latest_reports)"
                      (if value "IN" "NOT IN"))}

      [(field :when #{"report" "resource_type" "resource_title" "status"})]
      {:where (format "resource_events.%s = ?" field)
       :params [value] }

      ;; these fields allow NULL, which causes a change in semantics when
      ;; wrapped in a NOT(...) clause, so we have to be very explicit
      ;; about the NULL case.
      [(field :when #{"property" "message" "file" "line" "containing_class"})]
      (if-not (nil? value)
        {:where (format "resource_events.%s = ? AND resource_events.%s IS NOT NULL" field field)
         :params [value] }
        {:where (format "resource_events.%s IS NULL" field)
         :params nil })

      ;; these fields require special treatment for NULL (as described above),
      ;; plus a serialization step since the values can be complex data types
      [(field :when #{"old_value" "new_value"})]
      {:where (format "resource_events.%s = ? AND resource_events.%s IS NOT NULL" field field)
       :params [(db-serialize value)] }

      :else (throw (IllegalArgumentException.
                     (str path " is not a queryable object for resource events"))))))

(defn compile-resource-event-regexp
  "Compile an ~ predicate for resource event query. `path` represents the field
   to query against, and `pattern` is the regular expression to match."
    [& [path pattern :as args]]
    {:post [(map? %)
            (string? (:where %))]}
    (when-not (= (count args) 2)
      (throw (IllegalArgumentException. (format "~ requires exactly two arguments, but %d were supplied" (count args)))))
    (let [path (dashes->underscores path)]
      (match [path]
        ["certname"]
        {:where (sql-regexp-match "reports.certname")
         :params [pattern]}

        [(field :when #{"report" "resource_type" "resource_title" "status"})]
        {:where  (sql-regexp-match (format "resource_events.%s" field))
         :params [pattern] }

        ;; these fields allow NULL, which causes a change in semantics when
        ;; wrapped in a NOT(...) clause, so we have to be very explicit
        ;; about the NULL case.
        [(field :when #{"property" "message" "file" "line" "containing_class"})]
        {:where (format "%s AND resource_events.%s IS NOT NULL"
                    (sql-regexp-match (format "resource_events.%s" field))
                    field)
         :params [pattern] }

        :else (throw (IllegalArgumentException.
                       (str path " is not a queryable object for resource events"))))))



(defn resource-event-ops
  "Maps resource event query operators to the functions implementing them. Returns nil
  if the operator isn't known."
  [op]
  (let [op (string/lower-case op)]
    (cond
      (= op "=") compile-resource-event-equality
      (= op "and") (partial compile-and resource-event-ops)
      (= op "or") (partial compile-or resource-event-ops)
      (= op "not") (partial compile-not-v2 resource-event-ops)
      (#{">" "<" ">=" "<="} op) (partial compile-resource-event-inequality op)
      (= op "~") compile-resource-event-regexp)))

(def event-columns
  {"certname"               "reports"
   "configuration_version"  "reports"
   "report"                 "resource_events"
   "status"                 "resource_events"
   "timestamp"              "resource_events"
   "resource_type"          "resource_events"
   "resource_title"         "resource_events"
   "property"               "resource_events"
   "new_value"              "resource_events"
   "old_value"              "resource_events"
   "message"                "resource_events"
   "file"                   "resource_events"
   "line"                   "resource_events"
   "containment_path"       "resource_events"
   "containing_class"       "resource_events"})

(defn query->sql
  "Compile a resource event `query` into an SQL expression."
  [query]
  {:pre  [(sequential? query)]
   :post [(valid-jdbc-query? %)]}
  (let [{:keys [where params]} (compile-term resource-event-ops query)
        sql (format "SELECT %s
                        FROM resource_events
                        JOIN reports ON resource_events.report = reports.hash
                        WHERE %s"
              (string/join ", "
                (map (fn [[column table]] (str table "." column))
                  event-columns))
              where)]
    (apply vector sql params)))

(defn limited-query-resource-events
  "Take a limit, paging-options map, a query, and its parameters,
  and return a map containing the results and metadata.

  The returned map will contain a key `:result`, whose value is vector of
  resource events which match the query.  If the paging-options indicate
  that a total result count should also be returned, then the map will
  contain an additional key `:count`, whose value is an integer.

  Throws an exception if the query would return more than `limit` results.
  (A value of `0` for `limit` means that the query should not be limited.)"
  [limit paging-options [query & params]]
  {:pre  [(and (integer? limit) (>= limit 0))]
   :post [(or (zero? limit) (<= (count %) limit))
          (map? %)
          (contains? % :result)
          (sequential? (:result %))]}

  (validate-order-by! (keys event-columns) paging-options)
  (let [limited-query   (add-limit-clause limit query)
        results         (execute-query
                          limit
                          (apply vector limited-query params)
                          paging-options)]
    (assoc results :result
      (map
        #(-> (utils/mapkeys underscores->dashes %)
           (update-in [:old-value] json/parse-string)
           (update-in [:new-value] json/parse-string))
        (:result results)))))

(defn query-resource-events
  "Take a paging-options map, a query, and its parameters, and return a map
  containing matching resource events and metadata.  For more information about
  the return value, see `limited-query-resource-events`."
  [paging-options [sql & params]]
  {:pre [(string? sql)]}
  (limited-query-resource-events 0 paging-options (apply vector sql params)))

(defn events-for-report-hash
  "Given a particular report hash, this function returns all events for that
   given hash."
  [report-hash]
  {:pre [(string? report-hash)]
   :post [(vector? %)]}
  (let [query          ["=" "report" report-hash]
        ;; we aren't actually supporting paging through this code path for now
        paging-options {}]
    (vec
      (->> query
        (query->sql)
        (query-resource-events paging-options)
        (:result)))))
