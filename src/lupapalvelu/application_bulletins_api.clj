(ns lupapalvelu.application-bulletins-api
  (:require [monger.operators :refer :all]
            [monger.query :as query]
            [sade.core :refer :all]
            [sade.strings :as ss]
            [sade.property :as p]
            [lupapalvelu.action :refer [defquery defcommand] :as action]
            [lupapalvelu.application-bulletins :as bulletins]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.document.schemas :as schemas]
            [monger.operators :refer :all]
            [lupapalvelu.states :as states]
            [lupapalvelu.application-search :refer [make-text-query dir]]))

(def bulletins-fields
  {:versions {$slice -1} :versions.bulletinState 1
   :versions.state 1 :versions.municipality 1
   :versions.address 1 :versions.location 1
   :versions.primaryOperation 1 :versions.propertyId 1
   :versions.applicant 1 :versions.modified 1
   :modified 1})

(def bulletin-page-size 10)

(defn- make-query [search-text municipality state]
  (let [text-query         (when-not (ss/blank? search-text)
                             (make-text-query (ss/trim search-text)))
        municipality-query (when-not (ss/blank? municipality)
                             {:versions.municipality municipality})
        state-query        (when-not (ss/blank? state)
                             {:versions.bulletinState state})
        queries            (filter seq [text-query municipality-query state-query])]
    (when-let [and-query (seq queries)]
      {$and and-query})))

(defn- get-application-bulletins-left [page searchText municipality state _]
  (let [query (make-query searchText municipality state)]
    (- (mongo/count :application-bulletins query)
       (* page bulletin-page-size))))

(def- sort-field-mapping {"bulletinState" :bulletinState
                          "municipality" :municipality
                          "address" :address
                          "applicant" :applicant
                          "modified" :modified})

(defn- make-sort [{:keys [field asc]}]
  (let [sort-field (sort-field-mapping field)]
    (cond
      (nil? sort-field) {}
      (sequential? sort-field) (apply array-map (interleave sort-field (repeat (dir asc))))
      :else (array-map sort-field (dir asc)))))

(defn- get-application-bulletins [page searchText municipality state sort]
  (let [query (or (make-query searchText municipality state) {})
        apps (mongo/with-collection "application-bulletins"
               (query/find query)
               (query/fields bulletins-fields)
               (query/sort (make-sort sort))
               (query/paginate :page page :per-page bulletin-page-size))]
    (map
      #(assoc (first (:versions %)) :id (:_id %))
      apps)))

(defquery application-bulletins
  {:description "Query for Julkipano"
   :feature :publish-bulletin
   :parameters [page searchText municipality state sort]
   :input-validators [(partial action/number-parameters [:page])]
   :user-roles #{:anonymous}}
  [_]
  (let [parameters [page searchText municipality state sort]]
    (ok :data (apply get-application-bulletins parameters)
        :left (apply get-application-bulletins-left parameters))))

(defquery application-bulletin-municipalities
  {:description "List of distinct municipalities of application bulletins"
   :feature :publish-bulletin
   :parameters []
   :user-roles #{:anonymous}}
  [_]
  (let [municipalities (mongo/distinct :application-bulletins :versions.municipality)]
    (ok :municipalities municipalities)))

(defquery application-bulletin-states
  {:description "List of distinct municipalities of application bulletins"
   :feature :publish-bulletin
   :parameters []
   :user-roles #{:anonymous}}
  [_]
  (let [states (mongo/distinct :application-bulletins :versions.bulletinState)]
    (ok :states states)))


(defn- get-search-fields [fields app]
  (into {} (map #(hash-map % (% app)) fields)))

(defcommand publish-bulletin
  {:parameters [id]
   :feature :publish-bulletin
   :user-roles #{:authority}
   :states     (states/all-application-states-but :draft)}
  [{:keys [application created] :as command}]
  (let [app-snapshot (bulletins/create-bulletin-snapshot application)
        search-fields [:municipality :address :verdicts :_applicantIndex :bulletinState :applicant]
        search-updates (get-search-fields search-fields app-snapshot)
        updates (bulletins/snapshot-updates app-snapshot search-updates created)]
    (mongo/update-by-id :application-bulletins id updates :upsert true)
    (ok)))

(def bulletin-fields
  (merge bulletins-fields
    {:versions._applicantIndex 1 :versions.documents 1
     :versions.attachments 1}))

(defquery bulletin
  {:parameters [bulletinId]
   :feature :publish-bulletin
   :user-roles #{:anonymous}}
  (if-let [bulletin (mongo/with-id (mongo/by-id :application-bulletins bulletinId bulletin-fields))]
    (let [bulletin-version (assoc (-> bulletin :versions first) :id (:id bulletin))
          append-schema-fn (fn [{schema-info :schema-info :as doc}]
                             (assoc doc :schema (schemas/get-schema schema-info)))
          bulletin (update-in bulletin-version [:documents] (partial map append-schema-fn))]
      (ok :bulletin bulletin))
    (fail :error.bulletin.not-found)))