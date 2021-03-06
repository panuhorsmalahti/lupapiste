(ns lupapalvelu.document.document-api
  (:require [taoensso.timbre :as timbre :refer [trace debug debugf info infof warn error]]
            [monger.operators :refer :all]
            [sade.core :refer [ok fail fail! unauthorized! now]]
            [sade.strings :as ss]
            [lupapalvelu.action :refer [defquery defcommand update-application] :as action]
            [lupapalvelu.application :as application]
            [lupapalvelu.authorization :as auth]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.states :as states]
            [lupapalvelu.user :as user]
            [lupapalvelu.document.document :refer :all]
            [lupapalvelu.document.persistence :as doc-persistence]
            [lupapalvelu.document.model :as model]
            [lupapalvelu.document.tools :as tools]))


(def update-doc-states (states/all-application-states-but (conj states/terminal-states :sent :verdictGiven :constructionStarted)))

(def approve-doc-states (states/all-application-states-but (conj states/terminal-states :draft :sent :verdictGiven :constructionStarted)))

(defn validate-is-construction-time-doc
  [{{doc-id :doc} :data} {state :state documents :documents}]
  (when doc-id
    (when-not (some-> (domain/get-document-by-id documents doc-id)
                      (model/get-document-schema)
                      (get-in [:info :construction-time]))
      (fail :error.document-not-construction-time-doc))))

;;
;; CRUD
;;

(defcommand create-doc
  {:parameters [:id :schemaName]
   :optional-parameters [updates fetchRakennuspaikka]
   :input-validators [(partial action/non-blank-parameters [:id :schemaName])]
   :user-roles #{:applicant :authority}
   :states     #{:draft :answered :open :submitted :complementNeeded}
   :pre-checks [create-doc-validator
                application/validate-authority-in-drafts]}
  [{{schema-name :schemaName} :data :as command}]
  (let [document (doc-persistence/do-create-doc! command schema-name updates)]
    (when fetchRakennuspaikka
      (let [property-id (or
                          (tools/get-update-item-value updates "kiinteisto.kiinteistoTunnus")
                          (get-in command [:application :propertyId]))]
        (fetch-and-persist-ktj-tiedot (:application command) document property-id (now))))
    (ok :doc (:id document))))

(defcommand remove-doc
  {:parameters  [id docId]
   :input-validators [(partial action/non-blank-parameters [:id :docId])]
    :user-roles #{:applicant :authority}
    :states     #{:draft :answered :open :submitted :complementNeeded}
    :pre-checks [application/validate-authority-in-drafts
                 remove-doc-validator]}
  [{:keys [application created] :as command}]
  (if-let [document (domain/get-document-by-id application docId)]
    (do
      (doc-persistence/remove! command docId "documents")
      (ok))
    (fail :error.document-not-found)))

(defcommand update-doc
  {:parameters [id doc updates]
   :input-validators [(partial action/non-blank-parameters [:id :doc])
                      (partial action/vector-parameters [:updates])]
   :user-roles #{:applicant :authority}
   :states     update-doc-states
   :pre-checks [application/validate-authority-in-drafts]}
  [command]
  (doc-persistence/update! command doc updates "documents"))

(defcommand update-construction-time-doc
  {:parameters [id doc updates]
   :input-validators [(partial action/non-blank-parameters [:id :doc])
                      (partial action/vector-parameters [:updates])]
   :user-roles #{:applicant :authority}
   :states     states/post-verdict-states
   :pre-checks [application/validate-authority-in-drafts validate-is-construction-time-doc]}
  [command]
  (doc-persistence/update! command doc updates "documents"))

(defcommand update-task
  {:parameters [id doc updates]
   :input-validators [(partial action/non-blank-parameters [:id :doc])
                      (partial action/vector-parameters [:updates])]
   :user-roles #{:applicant :authority}
   :states     (states/all-application-states-but (conj states/terminal-states :sent))
   :pre-checks [application/validate-authority-in-drafts]}
  [command]
  (doc-persistence/update! command doc updates "tasks"))

(defcommand remove-document-data
  {:parameters       [id doc path collection]
   :user-roles       #{:applicant :authority}
   :states           #{:draft :answered :open :submitted :complementNeeded}
   :input-validators [doc-persistence/validate-collection]
   :pre-checks       [application/validate-authority-in-drafts]}
  [command]
  (doc-persistence/remove-document-data command doc [path] collection))

(defcommand remove-construction-time-document-data
  {:parameters       [id doc path collection]
   :user-roles       #{:applicant :authority}
   :states           states/post-verdict-states
   :input-validators [doc-persistence/validate-collection]
   :pre-checks       [application/validate-authority-in-drafts validate-is-construction-time-doc]}
  [command]
  (doc-persistence/remove-document-data command doc [path] collection))

;;
;; Document validation
;;

(defquery validate-doc
  {:parameters       [:id doc collection]
   :user-roles       #{:applicant :authority}
   :states           states/all-states
   :input-validators [doc-persistence/validate-collection]
   :user-authz-roles auth/all-authz-roles
   :org-authz-roles  auth/reader-org-authz-roles}
  [{:keys [application]}]
  (debug doc collection)
  (let [document (doc-persistence/by-id application collection doc)]
    (when-not document (fail! :error.document-not-found))
    (ok :results (model/validate application document))))

(defquery fetch-validation-errors
  {:parameters       [:id]
   :user-roles       #{:applicant :authority}
   :user-authz-roles auth/all-authz-roles
   :org-authz-roles  auth/reader-org-authz-roles
   :states           states/all-states}
  [{app :application}]
  (let [results (for [doc (:documents app)] (model/validate app doc))]
    (ok :results results)))

;;
;; Document approvals
;;

(defcommand approve-doc
  {:parameters [:id :doc :path :collection]
   :input-validators [(partial action/non-blank-parameters [:id :doc :collection])
                      doc-persistence/validate-collection]
   :user-roles #{:authority}
   :states     approve-doc-states}
  [command]
  (ok :approval (approve command "approved")))

(defcommand approve-construction-time-doc
  {:parameters [:id :doc :path :collection]
   :input-validators [(partial action/non-blank-parameters [:id :doc :collection])
                      doc-persistence/validate-collection]
   :user-roles #{:authority}
   :states     states/post-verdict-states
   :pre-checks [validate-is-construction-time-doc]}
  [command]
  (ok :approval (approve command "approved")))

(defcommand reject-doc
  {:parameters [:id :doc :path :collection]
   :input-validators [(partial action/non-blank-parameters [:id :doc :collection])
                      doc-persistence/validate-collection]
   :user-roles #{:authority}
   :states     approve-doc-states}
  [command]
  (ok :approval (approve command "rejected")))

(defcommand reject-construction-time-doc
  {:parameters [:id :doc :path :collection]
   :input-validators [(partial action/non-blank-parameters [:id :doc :collection])
                      doc-persistence/validate-collection]
   :user-roles #{:authority}
   :states     states/post-verdict-states
   :pre-checks [validate-is-construction-time-doc]}
  [command]
  (ok :approval (approve command "rejected")))

;;
;; Set party to document
;;

(defcommand set-user-to-document
  {:parameters [id documentId userId path]
   :input-validators [(partial action/non-blank-parameters [:id :documentId])]
   :user-roles #{:applicant :authority}
   :pre-checks [user-can-be-set-validator
                application/validate-authority-in-drafts]
   :states     update-doc-states}
  [{:keys [created application] :as command}]
  (doc-persistence/do-set-user-to-document application documentId userId path created))

(defcommand set-current-user-to-document
  {:parameters [id documentId path]
   :input-validators [(partial action/non-blank-parameters [:id :documentId])]
   :user-roles #{:applicant :authority}
   :pre-checks [domain/validate-owner-or-write-access
                application/validate-authority-in-drafts]
   :states     update-doc-states}
  [{:keys [created application user] :as command}]
  (doc-persistence/do-set-user-to-document application documentId (:id user) path created))

(defcommand set-company-to-document
  {:parameters [id documentId companyId path]
   :input-validators [(partial action/non-blank-parameters [:id :documentId])]
   :user-roles #{:applicant :authority}
   :states     update-doc-states
   :pre-checks [application/validate-authority-in-drafts]}
  [{:keys [user created application] :as command}]
  (if-let [document (domain/get-document-by-id application documentId)]
    (doc-persistence/do-set-company-to-document application document companyId path (user/get-user-by-id (:id user)) created)
    (fail :error.document-not-found)))
