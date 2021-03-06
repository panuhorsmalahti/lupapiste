(ns lupapalvelu.tiedonohjaus-api
  (:require [lupapalvelu.action :refer [defquery defcommand non-blank-parameters] :as action]
            [sade.core :refer [ok fail fail!]]
            [lupapalvelu.tiedonohjaus :as t]
            [lupapalvelu.organization :as o]
            [lupapalvelu.states :as states]
            [lupapalvelu.user :as user]
            [monger.operators :refer :all]
            [lupapalvelu.action :as action]
            [lupapiste-commons.tos-metadata-schema :as tms]
            [schema.core :as s]
            [taoensso.timbre :as timbre]))

(defquery available-tos-functions
  {:user-roles #{:anonymous}
   :parameters [organizationId]
   :input-validators [(partial non-blank-parameters [:organizationId])]
   :feature :tiedonohjaus}
  (let [functions (t/available-tos-functions organizationId)]
    (ok :functions functions)))

(defn- store-function-code [operation function-code user]
  (let [orgId (user/authority-admins-organization-id user)
        organization (o/get-organization orgId)
        operation-valid? (some #{operation} (:selected-operations organization))
        code-valid? (some #{function-code} (map :code (t/available-tos-functions orgId)))]
    (if (and operation-valid? code-valid?)
      (do (o/update-organization orgId {$set {(str "operations-tos-functions." operation) function-code}})
          (ok))
      (fail "Invalid organization or operation"))))

(defcommand set-tos-function-for-operation
  {:parameters [operation functionCode]
   :user-roles #{:authorityAdmin}
   :input-validators [(partial non-blank-parameters [:functionCode :operation])]
   :feature :tiedonohjaus}
  [{user :user}]
  (store-function-code operation functionCode user))

(defcommand set-tos-function-for-application
  {:parameters [:id functionCode]
   :input-validators [(partial non-blank-parameters [:id :functionCode])]
   :user-roles #{:authority}
   :states states/all-but-draft-or-terminal
   :feature :tiedonohjaus}
  [{:keys [application created user] :as command}]
  (let [orgId (:organization application)
        code-valid? (some #{functionCode} (map :code (t/available-tos-functions orgId)))]
    (if code-valid?
      (let [updated-attachments (map #(t/document-with-updated-metadata % orgId functionCode) (:attachments application))
            {updated-metadata :metadata} (t/document-with-updated-metadata application orgId functionCode "hakemus")]
        (action/update-application command
                                   {$set {:modified created
                                          :tosFunction functionCode
                                          :metadata updated-metadata
                                          :attachments updated-attachments}}))
      (fail "Invalid TOS function code"))))

(def schema-to-input-type-map
  {s/Str "text"
   tms/NonEmptyStr "text"
   tms/Vuodet "number"
   s/Bool "checkbox"})

(def editable-metadata-fields
  (->> (remove #(= tms/Tila %) tms/asiakirja-metadata-fields)
       (concat tms/common-metadata-fields)))

(defn- metadata-schema-for-ui [field]
  (cond-> field
          (:dependencies field) (->> (:dependencies)
                                     (map (fn [[k v]] {k (map metadata-schema-for-ui v)}))
                                     (into {})
                                     (assoc field :dependencies))
          (:subfields field) (->> (:subfields)
                                  (map metadata-schema-for-ui)
                                  (assoc field :subfields))
          (:schema field) (-> (assoc :inputType (get schema-to-input-type-map (:schema field)))
                              (dissoc :schema))))

(defquery tos-metadata-schema
  {:user-roles #{:anonymous}
   :feature :tiedonohjaus}
  (ok :schema (map metadata-schema-for-ui editable-metadata-fields)))

(defn- revert-unauthorized-modifications [roles old-metadata new-metadata]
  (let [disallowed-metadata (filter (fn [field] (when-let [role (:require-role field)]
                                                  (not (contains? roles role))))
                              editable-metadata-fields)
        disallowed-keys (map :type disallowed-metadata)
        replacement-metadata (select-keys old-metadata disallowed-keys)]
    (merge new-metadata replacement-metadata)))

(defn- sanitize-metadata [m]
  (try
    (tms/sanitize-metadata m)
    (catch Throwable t
      (fail! "error.invalid.metadata"))))

(defn- update-document-metadata [document metadata user-roles]
  (let [old-metadata (tms/coerce-metadata-to-schema (:metadata document) [])
        metadata (->> (tms/coerce-metadata-to-schema metadata [])
                      (revert-unauthorized-modifications user-roles old-metadata)
                      (#(assoc % :tila (or (:tila old-metadata) :luonnos)))
                      sanitize-metadata)]
    (assoc document :metadata metadata)))

(defn- update-application-child-metadata! [{:keys [application created user] :as command} type id metadata]
  (if-let [child (first (filter #(= (:id %) id) (type application)))]
    (let [user-roles (get-in user [:orgAuthz (keyword (:organization application))])
          updated-child (update-document-metadata child metadata user-roles)
          updated-children (-> (remove #(= % child) (type application)) (conj updated-child))]
      (action/update-application command {$set {:modified created type updated-children}})
      (ok))
    (fail "error.child.id")))

(defcommand store-tos-metadata-for-attachment
  {:parameters [:id attachmentId metadata]
   :input-validators [(partial non-blank-parameters [:id :attachmentId])
                      (partial action/map-parameters [:metadata])]
   :user-roles #{:authority}
   :states states/all-but-draft-or-terminal
   :feature :tiedonohjaus}
  [command]
  (update-application-child-metadata! command :attachments attachmentId metadata))

(defcommand store-tos-metadata-for-application
  {:parameters [:id metadata]
   :input-validators [(partial non-blank-parameters [:id])
                      (partial action/map-parameters [:metadata])]
   :user-roles #{:authority}
   :states states/all-but-draft-or-terminal
   :feature :tiedonohjaus}
  [{:keys [application created user] :as command}]
  (let [user-roles (get-in user [:orgAuthz (keyword (:organization application))])
        processed-metadata (->> (update-document-metadata application metadata user-roles)
                                (:metadata))]
    (action/update-application command {$set {:modified created
                                              :metadata processed-metadata}})
    (ok)))

(defquery case-file-data
  {:parameters [:id]
   :user-roles #{:authority}
   :states states/all-application-states
   :feature :tiedonohjaus}
  [{:keys [application]}]
  (ok :process (t/generate-case-file-data application)))
