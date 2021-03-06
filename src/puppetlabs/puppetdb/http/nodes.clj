(ns puppetlabs.puppetdb.http.nodes
  (:require [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng :as eng]
            [puppetlabs.puppetdb.http.facts :as f]
            [puppetlabs.puppetdb.http.resources :as r]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [puppetlabs.puppetdb.http :as pl-http]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options wrap-with-parent-check]]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.http :as http]))

(defn node-status
  "Produce a response body for a single environment."
  [api-version node db url-prefix]
  (let [status (first
                (eng/stream-query-result :nodes
                                         api-version
                                         ["=" "certname" node]
                                         {}
                                         db
                                         url-prefix))]
    (if status 
      (http/json-response status)
      (http/status-not-found-response "node" node))))

(defn routes
  [version]
  (app
   []
   (http-q/query-route :nodes version http-q/restrict-query-to-active-nodes')

   [node]
   {:get
    (-> (fn [{:keys [globals]}]
          (node-status version
                       node
                       (:scf-read-db globals)
                       (:url-prefix globals)))
        ;; Being a singular item, querying and pagination don't really make
        ;; sense here
        (validate-query-params {}))}

   [node "facts" &]
   (-> (comp (f/facts-app version) (partial http-q/restrict-query-to-node node))
       (wrap-with-parent-check version :node node))

   [node "resources" &]
   (-> (comp (r/resources-app version) (partial http-q/restrict-query-to-node node))
       (wrap-with-parent-check version :node node))))

(defn node-app
  [version]
  (-> (routes version)
    verify-accepts-json
    (validate-query-params
     {:optional (cons "query" paging/query-params)})
    wrap-with-paging-options))
