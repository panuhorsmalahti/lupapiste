(ns lupapalvelu.application
  (:require [taoensso.timbre :as timbre :refer [trace debug debugf info infof warn error fatal]]
            [clj-time.core :refer [year]]
            [clj-time.local :refer [local-now]]
            [clojure.string :as s]
            [clojure.walk :refer [keywordize-keys]]
            [clojure.zip :as zip]
            [lupapalvelu.action :as action]
            [lupapalvelu.application-meta-fields :as meta-fields]
            [lupapalvelu.attachment :as attachment]
            [lupapalvelu.company :as c]
            [lupapalvelu.document.model :as model]
            [lupapalvelu.document.schemas :as schemas]
            [lupapalvelu.document.tools :as tools]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.mongo :refer [$each] :as mongo]
            [lupapalvelu.organization :as organization]
            [lupapalvelu.operations :as operations]
            [lupapalvelu.permit :as permit]
            [lupapalvelu.tiedonohjaus :as tos]
            [lupapalvelu.user :as user]
            [sade.core :refer :all]
            [sade.property :as p]
            [sade.util :as util]
            [swiss.arrows :refer [-<>>]]))


(defn get-operations [application]
  (remove nil? (conj (seq (:secondaryOperations application)) (:primaryOperation application))))


;;
;; Validators
;;

(defn- property-id? [^String s]
  (and s (re-matches #"^[0-9]{14}$" s)))

(defn property-id-parameters [params command]
  (when-let [invalid (seq (filter #(not (property-id? (get-in command [:data %]))) params))]
    (info "invalid property id parameters:" (s/join ", " invalid))
    (fail :error.invalid-property-id :parameters (vec invalid))))

(defn- is-link-permit-required [application]
  (or (= :muutoslupa (keyword (:permitSubtype application)))
      (some #(operations/link-permit-required-operations (keyword (:name %))) (get-operations application))))

(defn validate-link-permits [application]
  (let [application (meta-fields/enrich-with-link-permit-data application)
        linkPermits (-> application :linkPermitData count)]
    (when (and (is-link-permit-required application) (zero? linkPermits))
      (fail :error.permit-must-have-link-permit))))

(defn validate-authority-in-drafts
  "Validator: Restric authority access in draft application.
   To be used in commands' :pre-checks vector."
  [{user :user} {state :state}]
  (when (and (= :draft (keyword state)) (user/authority? user))
    unauthorized))

;;
;; Helpers
;;

(defn insert-application [application]
  (mongo/insert :applications (merge application (meta-fields/applicant-index application))))

(defn filter-repeating-party-docs [schema-version schema-names]
  (let [schemas (schemas/get-schemas schema-version)]
    (filter
      (fn [schema-name]
        (let [schema-info (get-in schemas [schema-name :info])]
          (and (:repeating schema-info) (= (:type schema-info) :party))))
      schema-names)))

; Seen updates
(def collections-to-be-seen #{"comments" "statements" "verdicts"})

(defn mark-collection-seen-update [{id :id} timestamp collection]
  {:pre [(collections-to-be-seen collection) id timestamp]}
  {(str "_" collection "-seen-by." id) timestamp})

(defn mark-indicators-seen-updates [application user timestamp]
  (merge
    (apply merge (map (partial mark-collection-seen-update user timestamp) collections-to-be-seen))
    (when (user/authority? user) (model/mark-approval-indicators-seen-update application timestamp))
    (when (user/authority? user) {:_attachment_indicator_reset timestamp})))

; Masking
(defn- person-id-masker-for-user [user {authority :authority :as application}]
  (cond
    (user/same-user? user authority) identity
    (user/authority? user) model/mask-person-id-ending
    :else (comp model/mask-person-id-birthday model/mask-person-id-ending)))

(defn with-masked-person-ids [application user]
  (let [mask-person-ids (person-id-masker-for-user user application)]
    (update-in application [:documents] (partial map mask-person-ids))))

; Process
(defn- process-documents [user {authority :authority :as application}]
  (let [validate (fn [doc] (assoc doc :validationErrors (model/validate application doc)))
        mask-person-ids (person-id-masker-for-user user application)
        doc-mapper (comp mask-person-ids validate)]
    (update-in application [:documents] (partial map doc-mapper))))

(defn ->location [x y]
  {:x (util/->double x) :y (util/->double y)})

(defn get-link-permit-app [{:keys [linkPermitData]}]
  "Return associated (first lupapistetunnus) link-permit application."
  (when-let [link (some #(when (= (:type %) "lupapistetunnus") %) linkPermitData)]
    (domain/get-application-no-access-checking (:id link))))

;;
;; Application query post process
;;

(defn- link-permit-submitted? [link-id]
  (-> (mongo/by-id "applications" link-id [:state])
    :state keyword #{:submitted :sent :complement-needed :verdictGiven :constructionStarted :closed :canceled} nil? not))

; Foreman
(defn- foreman-submittable? [application]
  (let [result (when (-> application :state keyword #{:draft :open :submitted :complement-needed})
                 (when-let [lupapiste-link (filter #(= (:type %) "lupapistetunnus") (:linkPermitData application))]
                   (when (seq lupapiste-link) (link-permit-submitted? (-> lupapiste-link first :id)))))]
    (if (nil? result)
      true
      result)))

(defn- process-foreman-v2 [application]
  (if (= (-> application :primaryOperation :name) "tyonjohtajan-nimeaminen-v2")
    (assoc application :submittable (foreman-submittable? application))
    application))


(defn- process-tasks [application]
  (update-in application [:tasks] (partial map #(assoc % :validationErrors (model/validate application %)))))

;; For enrich-docs-disabled-flag --> ; TODO should these be moved to own namespace, or to document related namespace?

(defn- schema-branch? [node]
  (or
    (seq? node)
    (and
      (map? node)
      (contains? node :body))))

(def- schema-leaf?
      (complement schema-branch?))

(defn- schema-zipper [doc-schema]
  (let [branch? (fn [node]
                  (and (map? node)
                       (contains? node :body)))
        children (fn [{body :body :as branch-node}]
                   (assert (map? branch-node) (str "Assertion failed in schema-zipper/children, expected node to be a map:" branch-node))
                   (assert (not (empty? body)) (str "Assertion failed in schema-zipper/children, branch node to have children:" branch-node))
                   body)
        make-node (fn [node, children]
                    (assert (map? node) (str "Assertion failed in schema-zipper/make-node, expected node to be a map:" node))
                    (assoc node :body children))]
    (zip/zipper branch? children make-node doc-schema)))

(defn- iterate-siblings-to-right [loc f]
  (if (nil? (zip/right loc))
    (-> (f loc)
        zip/up)
    (-> (f loc)
        zip/right
        (recur f))))

(defn- get-root-path [loc]
  (let [keyword-name (comp keyword :name)
        root-path (->> (zip/path loc)
                       (mapv keyword-name)
                       (filterv identity))
        node-name (-> (zip/node loc)
                      keyword-name)]
    (seq (conj root-path node-name))))

(defn- add-whitelist-property [node new-whitelist]
  (if-not (and (seq? node) (:whitelist node))
    (assoc node :whitelist new-whitelist)
    node))

(defn- walk-schema
  ([loc] (walk-schema loc nil))
  ([loc disabled-paths]
   (if (zip/end? loc)
     disabled-paths
     (let [current-node (zip/node loc)
           current-whitelist (:whitelist current-node)
           propagate-wl? (and (schema-branch? current-node) current-whitelist)
           loc (if propagate-wl?
                 (iterate-siblings-to-right
                   (zip/down loc)                           ;leftmost-child, starting point
                   #(zip/edit % add-whitelist-property current-whitelist))
                 loc)
           whitelisted-leaf? (and
                               (schema-leaf? current-node)
                               current-whitelist)
           disabled-paths (if whitelisted-leaf?
                            (conj disabled-paths [(get-root-path loc) current-whitelist])
                            disabled-paths)]
       (recur (zip/next loc) disabled-paths)))))

(defn- prefix-with [prefix coll]
  (conj (seq coll) prefix))

(defn- enrich-single-doc-disabled-flag [user-role doc]
  (let [doc-schema (model/get-document-schema doc)
        zip-root (schema-zipper doc-schema)
        whitelisted-paths (walk-schema zip-root)]
    (reduce (fn [new-doc [path whitelist]]
              (if-not ((set (:roles whitelist)) (keyword user-role))
                (util/update-in-repeating new-doc (prefix-with :data path) merge {:whitelist-action (:otherwise whitelist)})
                new-doc))
            doc
            whitelisted-paths)))

;; <-- For enrich-docs-disabled-flag

(defn- enrich-docs-disabled-flag [{user-role :role} app]
  (update-in app [:documents] (partial map (partial enrich-single-doc-disabled-flag user-role))))

(defn post-process-app [app user]
  (->> app
       meta-fields/enrich-with-link-permit-data
       (meta-fields/with-meta-fields user)
       action/without-system-keys
       process-foreman-v2
       (process-documents user)
       process-tasks
       (enrich-docs-disabled-flag user)))

;;
;; Application creation
;;

(defn make-attachments [created operation organization applicationState tos-function & {:keys [target]}]
  (for [[type-group type-id] (organization/get-organization-attachments-for-operation organization operation)]
    (let [metadata (tos/metadata-for-document (:id organization) tos-function {:type-group type-group :type-id type-id})]
      (attachment/make-attachment created target true false false applicationState operation {:type-group type-group :type-id type-id} metadata))))

(defn- schema-data-to-body [schema-data application]
  (keywordize-keys
    (reduce
      (fn [body [data-path data-value]]
        (let [path (if (= :value (last data-path)) data-path (conj (vec data-path) :value))
              val (if (fn? data-value) (data-value application) data-value)]
          (assoc-in body path val)))
      {} schema-data)))

(defn make-documents [user created op application & [manual-schema-datas]]
  {:pre [(or (nil? manual-schema-datas) (map? manual-schema-datas))]}
  (let [op-info (operations/operations (keyword (:name op)))
        op-schema-name (:schema op-info)
        schema-version (:schema-version application)
        default-schema-datas (util/assoc-when {}
                                              op-schema-name (:schema-data op-info)
                                              "yleiset-alueet-maksaja" operations/schema-data-yritys-selected
                                              "tyomaastaVastaava" operations/schema-data-yritys-selected)
        merged-schema-datas (merge-with conj default-schema-datas manual-schema-datas)
        make (fn [schema-name]
               (let [schema (schemas/get-schema schema-version schema-name)]
                 {:id          (mongo/create-id)
                  :schema-info (:info schema)
                  :created     created
                  :data        (util/deep-merge
                                 (tools/create-document-data schema tools/default-values)
                                 (tools/timestamped
                                   (if-let [schema-data (get-in merged-schema-datas [schema-name])]
                                     (schema-data-to-body schema-data application)
                                     {})
                                   created))}))
        ;;The merge below: If :removable is set manually in schema's info, do not override it to true.
        op-doc (update-in (make op-schema-name) [:schema-info] #(merge {:op op :removable true} %))
        new-docs (-<>> (:documents application)
                       (map (comp :name :schema-info))      ;; existing schema names
                       set
                       (remove <> (:required op-info))      ;; required schema names
                       (map make)                           ;; required docs
                       (cons op-doc))]                      ;; new docs
    (if-not user
      new-docs
      (conj new-docs (make (permit/get-applicant-doc-schema (permit/permit-type application)))))))


(defn make-op [op-name created]
  {:id          (mongo/create-id)
   :name        (keyword op-name)
   :description nil
   :created     created})

(defn make-application-id [municipality]
  (let [year (str (year (local-now)))
        sequence-name (str "applications-" municipality "-" year)
        counter (format "%05d" (mongo/get-next-sequence-value sequence-name))]
    (str "LP-" municipality "-" year "-" counter)))

(defn make-application [id operation x y address property-id municipality organization info-request? open-inforequest? messages user created manual-schema-datas]
  {:pre [id operation address property-id (not (nil? info-request?)) (not (nil? open-inforequest?)) user created]}
  (let [permit-type (operations/permit-type-of-operation operation)
        owner (user/user-in-role user :owner :type :owner)
        op (make-op operation created)
        state (cond
                info-request? :info
                (or (user/authority? user) (user/rest-user? user)) :open
                :else :draft)
        comment-target (if open-inforequest? [:applicant :authority :oirAuthority] [:applicant :authority])
        tos-function (get-in organization [:operations-tos-functions (keyword operation)])
        application (merge domain/application-skeleton
                      {:id                  id
                       :created             created
                       :opened              (when (#{:open :info} state) created)
                       :modified            created
                       :permitType          permit-type
                       :permitSubtype       (first (permit/permit-subtypes permit-type))
                       :infoRequest         info-request?
                       :openInfoRequest     open-inforequest?
                       :primaryOperation    op
                       :secondaryOperations []
                       :state               state
                       :municipality        municipality
                       :location            (->location x y)
                       :organization        (:id organization)
                       :address             address
                       :propertyId          property-id
                       :title               address
                       :auth                (if-let [company (some-> user :company :id c/find-company-by-id c/company->auth)]
                                              [owner company]
                                              [owner])
                       :comments            (map #(domain/->comment % {:type "application"} (:role user) user nil created comment-target) messages)
                       :schema-version      (schemas/get-latest-schema-version)
                       :tosFunction         tos-function
                       :metadata            (tos/metadata-for-document (:id organization) tos-function "hakemus")})]
    (merge application (when-not info-request?
                         {:attachments (make-attachments created op organization state tos-function)
                          :documents   (make-documents user created op application manual-schema-datas)}))))

(defn do-create-application
  [{{:keys [operation x y address propertyId infoRequest messages]} :data :keys [user created] :as command} & [manual-schema-datas]]
  (let [municipality      (p/municipality-id-by-property-id propertyId)
        permit-type       (operations/permit-type-of-operation operation)
        organization      (organization/resolve-organization municipality permit-type)
        scope             (organization/resolve-organization-scope municipality permit-type organization)
        organization-id   (:id organization)
        info-request?     (boolean infoRequest)
        open-inforequest? (and info-request? (:open-inforequest scope))]

    (when-not (or (user/applicant? user) (user/user-is-authority-in-organization? user organization-id))
      (unauthorized!))
    (when-not organization-id
      (fail! :error.missing-organization :municipality municipality :permit-type permit-type :operation operation))
    (if info-request?
      (when-not (:inforequest-enabled scope)
        (fail! :error.inforequests-disabled))
      (when-not (:new-application-enabled scope)
        (fail! :error.new-applications-disabled)))

    (let [id (make-application-id municipality)]
      (make-application id operation x y address propertyId municipality organization info-request? open-inforequest? messages user created manual-schema-datas))))

;;
;; Link permit
;;

(defn make-mongo-id-for-link-permit [app-id link-permit-id]
  (if (<= (compare app-id link-permit-id) 0)
    (str app-id "|" link-permit-id)
    (str link-permit-id "|" app-id)))

(defn do-add-link-permit [{:keys [id propertyId primaryOperation]} link-permit-id]
  {:pre [(mongo/valid-key? link-permit-id)
         (not= id link-permit-id)]}
  (let [db-id (make-mongo-id-for-link-permit id link-permit-id)
        is-lupapiste-app (.startsWith link-permit-id "LP-")
        linked-app (when is-lupapiste-app
                     (domain/get-application-no-access-checking link-permit-id))]
    (mongo/update-by-id :app-links db-id
                        {:_id           db-id
                         :link          [id link-permit-id]
                         id             {:type       "application"
                                         :apptype    (:name primaryOperation)
                                         :propertyId propertyId}
                         link-permit-id {:type           "linkpermit"
                                         :linkpermittype (if is-lupapiste-app
                                                           "lupapistetunnus"
                                                           "kuntalupatunnus")
                                         :apptype        (->> linked-app
                                                              (:primaryOperation)
                                                              (:name))}}
                        :upsert true)))


