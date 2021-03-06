(ns lupapalvelu.document.transformations
  (:require [sade.util :as util]
            [monger.operators :refer :all]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.document.persistence :as persistence]
            [lupapalvelu.document.schemas :as schemas]))

;;
;; Application state change updates
;;

(defn- transform-rakennusjate-path [[path val]]
  [(->> (rest path) (cons :suunniteltuJate) (cons (first path)) vec) val])

(defn rakennusjateselvitys-updates [suunnitelma-doc]
  (->> (:data suunnitelma-doc)
       (persistence/data-model->model-updates [])
       (filter (comp #{:rakennusJaPurkujate :vaarallisetAineet} ffirst))
       (filter (comp not-empty second))
       (mapv transform-rakennusjate-path)))

(defn- rakennusjate-removed-rows [suunnitelma-doc selvitys-doc group-name]
  (->> (clojure.set/difference (-> (get-in selvitys-doc [:data group-name :suunniteltuJate]) keys set)
                               (-> (get-in suunnitelma-doc [:data group-name]) keys set))
       (map (partial conj [group-name :suunniteltuJate]))))

(defn rakennusjateselvitys-removed-paths [suunnitelma-doc selvitys-doc]
  (concat (rakennusjate-removed-rows suunnitelma-doc selvitys-doc :rakennusJaPurkujate)
          (rakennusjate-removed-rows suunnitelma-doc selvitys-doc :vaarallisetAineet)))

(defn create-rakennusjateselvitys-from-rakennusjatesuunnitelma [{{schema-version :schema-version :as application} :application created :created :as command}]
  (when-let [suunnitelma-doc (domain/get-document-by-name application "rakennusjatesuunnitelma")]
    (let [updates (rakennusjateselvitys-updates suunnitelma-doc)
          schema  (schemas/get-schema schema-version "rakennusjateselvitys")]
      (if-let [selvitys-doc (domain/get-document-by-name application "rakennusjateselvitys")]
        (let [removed-paths (rakennusjateselvitys-removed-paths suunnitelma-doc selvitys-doc)]
          (util/deep-merge
           (persistence/removing-updates-by-path :documents (:id selvitys-doc) removed-paths)
           (persistence/validated-model-updates application :documents selvitys-doc updates created)))
        {:mongo-updates {$push {:documents (persistence/new-doc application schema created updates)}}}))))

(defn get-state-transition-updates [command next-state]
  (case next-state
    :verdictGiven (create-rakennusjateselvitys-from-rakennusjatesuunnitelma command)
    {}))
