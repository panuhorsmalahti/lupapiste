(ns lupapalvelu.application-api
  (:require [taoensso.timbre :as timbre :refer [trace debug debugf info infof warn error errorf]]
            [clj-time.core :refer [year]]
            [clj-time.local :refer [local-now]]
            [clj-time.format :as tf]
            [monger.operators :refer :all]
            [sade.coordinate :as coord]
            [sade.core :refer :all]
            [sade.env :as env]
            [sade.util :as util]
            [sade.strings :as ss]
            [sade.property :as p]
            [lupapalvelu.action :refer [defraw defquery defcommand update-application notify] :as action]
            [lupapalvelu.application :as a]
            [lupapalvelu.application-meta-fields :as meta-fields]
            [lupapalvelu.authorization :as auth]
            [lupapalvelu.attachment :as attachment]
            [lupapalvelu.comment :as comment]
            [lupapalvelu.document.document :as document]
            [lupapalvelu.document.model :as model]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.foreman :as foreman]
            [lupapalvelu.i18n :as i18n]
            [lupapalvelu.wfs :as wfs]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.notifications :as notifications]
            [lupapalvelu.open-inforequest :as open-inforequest]
            [lupapalvelu.operations :as operations]
            [lupapalvelu.organization :as organization]
            [lupapalvelu.permit :as permit]
            [lupapalvelu.states :as states]
            [lupapalvelu.state-machine :as sm]
            [lupapalvelu.user :as user]))

;; Notifications

(notifications/defemail :application-state-change
                        {:subject-key    "state-change"
                         :application-fn (fn [{id :id}] (domain/get-application-no-access-checking id))})

;; Validators

(defn operation-validator [{{operation :operation} :data}]
  (when-not (operations/operations (keyword operation)) (fail :error.unknown-type)))


(defn find-authorities-in-applications-organization [app]
  (mongo/select :users
                {(str "orgAuthz." (:organization app)) "authority", :enabled true}
                user/summary-keys
                (array-map :lastName 1, :firstName 1)))

(defquery application
  {:parameters       [:id]
   :states           states/all-states
   :user-roles       #{:applicant :authority :oirAuthority}
   :user-authz-roles auth/all-authz-roles
   :org-authz-roles  auth/reader-org-authz-roles}
  [{:keys [application user]}]
  (if application
    (let [app (assoc application :allowedAttachmentTypes (attachment/get-attachment-types-for-application application))]
      (ok :application (a/post-process-app app user)
          :authorities (if (user/authority? user)
                         (map #(select-keys % [:id :firstName :lastName]) (find-authorities-in-applications-organization app))
                         [])
          :permitSubtypes (a/resolve-valid-subtypes app)))
    (fail :error.not-found)))

(defquery application-authorities
  {:user-roles #{:authority}
   :states     (states/all-states-but :draft)
   :parameters [:id]}
  [{application :application}]
  (let [authorities (find-authorities-in-applications-organization application)]
    (ok :authorities (map #(select-keys % [:id :firstName :lastName]) authorities))))

(defn- autofill-rakennuspaikka [application time]
  (when (and (not (= "Y" (:permitType application))) (not (:infoRequest application)))
    (let [rakennuspaikka-docs (domain/get-documents-by-type application :location)]
      (doseq [rakennuspaikka rakennuspaikka-docs
              :when (seq rakennuspaikka)]
        (let [property-id (or
                            (get-in rakennuspaikka [:data :kiinteisto :kiinteistoTunnus :value])
                            (:propertyId application))]
          (document/fetch-and-persist-ktj-tiedot application rakennuspaikka property-id time))))))

(defquery party-document-names
  {:parameters [:id]
   :user-roles #{:applicant :authority}
   :states     states/all-application-states}
  [{{:keys [documents schema-version] :as application} :application}]
  (let [op-meta (operations/get-primary-operation-metadata application)
        original-schema-names   (->> (select-keys op-meta [:required :optional]) vals (apply concat))
        original-party-schemas  (a/filter-party-docs schema-version original-schema-names false)
        repeating-party-schemas (a/filter-party-docs schema-version original-schema-names true)
        current-schema-name-set (->> documents (filter a/party-document?) (map (comp name :name :schema-info)) set)
        missing-schema-names    (remove current-schema-name-set original-party-schemas)]
    (ok :partyDocumentNames (conj (concat missing-schema-names repeating-party-schemas) (operations/get-applicant-doc-schema-name application)))))

(defcommand mark-seen
  {:parameters       [:id type]
   :input-validators [(fn [{{type :type} :data}] (when-not (a/collections-to-be-seen type) (fail :error.unknown-type)))]
   :user-roles       #{:applicant :authority :oirAuthority}
   :states           states/all-states
   :pre-checks       [a/validate-authority-in-drafts]}
  [{:keys [data user created] :as command}]
  (update-application command {$set (a/mark-collection-seen-update user created type)}))

(defcommand mark-everything-seen
  {:parameters [:id]
   :user-roles #{:authority :oirAuthority}
   :states     (states/all-states-but [:draft])}
  [{:keys [application user created] :as command}]
  (update-application command {$set (a/mark-indicators-seen-updates application user created)}))

;;
;; Assign
;;

(defcommand assign-application
  {:parameters [:id assigneeId]
   :input-validators [(fn [{{assignee :assigneeId} :data}]
                        (when-not (or (ss/blank? assignee) (mongo/valid-key? assignee))
                          (fail "error.user.not.found")))]
   :user-roles #{:authority}
   :states     (states/all-states-but :draft :canceled)}
  [{:keys [user created application] :as command}]
  (let [assignee (util/find-by-id assigneeId (find-authorities-in-applications-organization application))]
    (if (or assignee (ss/blank? assigneeId))
      (update-application command
                          {$set {:modified  created
                                 :authority (if assignee (user/summary assignee) (:authority domain/application-skeleton))}})
      (fail "error.user.not.found"))))

;;
;; Cancel
;;

(defn- remove-app-links [id]
  (mongo/remove-many :app-links {:link {$in [id]}}))

(defn- do-cancel [{:keys [created user data] :as command}]
  {:pre [(seq (:application command))]}
  (update-application command (a/state-transition-update :canceled created user))
  (remove-app-links (:id data))
  (ok))

(defcommand cancel-inforequest
  {:parameters       [id]
   :input-validators [(partial action/non-blank-parameters [:id])]
   :user-roles       #{:applicant :authority :oirAuthority}
   :notified         true
   :on-success       (notify :application-state-change)
   :pre-checks       [(partial sm/validate-state-transition :canceled)]}
  [command]
  (do-cancel command))

(defcommand cancel-application
  {:parameters       [id]
   :input-validators [(partial action/non-blank-parameters [:id])]
   :user-roles       #{:applicant}
   :notified         true
   :on-success       (notify :application-state-change)
   :states           #{:draft :info :open :submitted}
   :pre-checks       [(partial sm/validate-state-transition :canceled)]}
  [command]
  (do-cancel command))

(defcommand cancel-application-authority
  {:parameters       [id text lang]
   :input-validators [(partial action/non-blank-parameters [:id :lang])]
   :user-roles       #{:authority}
   :notified         true
   :on-success       (notify :application-state-change)
   :pre-checks       [a/validate-authority-in-drafts
                      (partial sm/validate-state-transition :canceled)]}
  [{:keys [created application user] :as command}]
  (update-application command
    (util/deep-merge
      (a/state-transition-update :canceled created user)
      (when (seq text)
        (comment/comment-mongo-update
          (:state application)
          (str
            (i18n/localize lang "application.canceled.text") ". "
            (i18n/localize lang "application.canceled.reason") ": "
            text)
          {:type "application"}
          (-> command :user :role)
          false
          (:user command)
          nil
          created))))
  (remove-app-links id)
  (ok))


(defcommand request-for-complement
  {:parameters       [:id]
   :input-validators [(partial action/non-blank-parameters [:id])]
   :user-roles       #{:authority}
   :notified         true
   :on-success       (notify :application-state-change)
   :pre-checks       [(partial sm/validate-state-transition :complementNeeded)]}
  [{:keys [created user] :as command}]
  (update-application command (util/deep-merge (a/state-transition-update :complementNeeded created user))))

(defn- do-submit [command application created]
  (let [history-entries (remove nil?
                          [(when-not (:opened application) (a/history-entry :open created (:user command)))
                           (a/history-entry :submitted created (:user command))])]
    (update-application command
      {$set {:state     :submitted
             :modified  created
             :opened    (or (:opened application) created)
             :submitted (or (:submitted application) created)}
       $push {:history {$each history-entries}}}))
  (try
    (mongo/insert :submitted-applications (-> application
                                            meta-fields/enrich-with-link-permit-data
                                            (dissoc :id)
                                            (assoc :_id (:id application))))
    (catch com.mongodb.DuplicateKeyException e
      ; This is ok. Only the first submit is saved.
      )))

(notifications/defemail :neighbor-hearing-requested
  {:pred-fn       (fn [command] (get-in command [:application :options :municipalityHearsNeighbors]))
   :recipients-fn (fn [{application :application}]
                    (let [organization (organization/get-organization (:organization application))
                          emails (get-in organization [:notifications :neighbor-order-emails])]
                      (map (fn [e] {:email e, :role "authority"}) emails)))
   :tab "statement"})

(defcommand submit-application
  {:parameters       [id]
   :input-validators [(partial action/non-blank-parameters [:id])]
   :user-roles       #{:applicant :authority}
   :states           #{:draft :open}
   :notified         true
   :on-success       [(notify :application-state-change)
                      (notify :neighbor-hearing-requested)]
   :pre-checks       [domain/validate-owner-or-write-access
                      a/validate-authority-in-drafts
                      (partial sm/validate-state-transition :submitted)]}
  [{:keys [application created] :as command}]
  (let [application (meta-fields/enrich-with-link-permit-data application)]
    (or
      (foreman/validate-application application)
      (a/validate-link-permits application)
      (do-submit command application created))))

(defcommand refresh-ktj
  {:parameters [:id]
   :user-roles #{:authority}
   :states     (states/all-application-states-but (conj states/terminal-states :draft))}
  [{:keys [application created]}]
  (autofill-rakennuspaikka application created)
  (ok))

(defcommand save-application-drawings
  {:parameters       [:id drawings]
   :input-validators [(partial action/non-blank-parameters [:id])]
   :user-roles       #{:applicant :authority :oirAuthority}
   :states           #{:draft :info :answered :open :submitted :complementNeeded}
   :pre-checks       [a/validate-authority-in-drafts]}
  [{:keys [created] :as command}]
  (when (sequential? drawings)
    (update-application command
                        {$set {:modified created
                               :drawings drawings}})))

(defn- make-marker-contents [id lang {:keys [location] :as app}]
  (merge
    {:id        (:id app)
     :title     (:title app)
     :location  {:x (first location) :y (second location)}
     :operation (->> (:primaryOperation app) :name (i18n/localize lang "operations"))
     :authName  (-> app
                    (auth/get-auths-by-role :owner)
                    first
                    (#(str (:firstName %) " " (:lastName %))))
     :comments  (->> (:comments app)
                     (filter #(not (= "system" (:type %))))
                     (map #(identity {:name (str (-> % :user :firstName) " " (-> % :user :lastName))
                                      :type (:type %)
                                      :time (:created %)
                                      :text (:text %)})))}
    (when-not (= id (:id app))
      {:link (str (env/value :host) "/app/" (name lang) "/authority#!/inforequest/" (:id app))})))

(defn- remove-irs-by-id [target-irs irs-to-be-removed]
  (remove (fn [ir] (some #(= (:id ir) (:id %)) irs-to-be-removed)) target-irs))

(defquery inforequest-markers
          {:parameters       [id lang x y]
           :user-roles       #{:authority :oirAuthority}
           :states           states/all-inforequest-states
           :input-validators [(partial action/non-blank-parameters [:id :x :y])]}
          [{:keys [application user]}]
          (let [x (util/->double x)
                y (util/->double y)
                inforequests (mongo/select :applications
                                           (merge
                                             (domain/application-query-for user)
                                             {:infoRequest true})
                                           [:title :auth :location :primaryOperation :secondaryOperations :comments])

                same-location-irs (filter
                                    #(and (== x (-> % :location first)) (== y (-> % :location second)))
                                    inforequests)

                inforequests (remove-irs-by-id inforequests same-location-irs)

                application-op-name (-> application :primaryOperation :name)

                same-op-irs (filter
                              (fn [ir]
                                (some #(= application-op-name (:name %)) (a/get-operations ir)))
                              inforequests)

                others (remove-irs-by-id inforequests same-op-irs)

                same-location-irs (map (partial make-marker-contents id lang) same-location-irs)
                same-op-irs (map (partial make-marker-contents id lang) same-op-irs)
                others (map (partial make-marker-contents id lang) others)]

            (ok :sameLocation same-location-irs :sameOperation same-op-irs :others others)
            ))


(defcommand create-application
  {:parameters       [:operation :x :y :address :propertyId]
   :user-roles       #{:applicant :authority}
   :notified         true                                   ; OIR
   :input-validators [(partial action/non-blank-parameters [:operation :address :propertyId])
                      (partial action/property-id-parameters [:propertyId])
                      coord/validate-x coord/validate-y
                      operation-validator]}
  [{{:keys [infoRequest]} :data :keys [created] :as command}]
  (let [created-application (a/do-create-application command)]
    (a/insert-application created-application)
    (when (and (boolean infoRequest) (:openInfoRequest created-application))
      (open-inforequest/new-open-inforequest! created-application))
    (try
      (autofill-rakennuspaikka created-application created)
      (catch java.io.IOException e
        (error "KTJ data was not updated:" (.getMessage e))))
    (ok :id (:id created-application))))

(defn- add-operation-allowed? [_ application]
  (let [op (-> application :primaryOperation :name keyword)
        permit-subtype (keyword (:permitSubtype application))]
    (when-not (and (or (nil? op) (:add-operation-allowed (operations/operations op)))
                   (not= permit-subtype :muutoslupa))
      (fail :error.add-operation-not-allowed))))

(defcommand add-operation
  {:parameters       [id operation]
   :user-roles       #{:applicant :authority}
   :states           states/pre-sent-application-states
   :input-validators [operation-validator]
   :pre-checks       [add-operation-allowed?
                      a/validate-authority-in-drafts]}
  [{:keys [application created] :as command}]
  (let [op (a/make-op operation created)
        new-docs (a/make-documents nil created op application)
        organization (organization/get-organization (:organization application))]
    (update-application command {$push {:secondaryOperations  op
                                        :documents   {$each new-docs}
                                        :attachments {$each (a/make-attachments created op organization (:state application) (:tosFunction application))}}
                                 $set  {:modified created}})))

(defcommand update-op-description
  {:parameters [id op-id desc]
   :input-validators [(partial action/non-blank-parameters [:id :op-id])]
   :user-roles #{:applicant :authority}
   :states     states/pre-sent-application-states
   :pre-checks [a/validate-authority-in-drafts]}
  [{:keys [application] :as command}]
  (if (= (get-in application [:primaryOperation :id]) op-id)
    (update-application command {$set {"primaryOperation.description" desc}})
    (update-application command {"secondaryOperations" {$elemMatch {:id op-id}}} {$set {"secondaryOperations.$.description" desc}})))

(defcommand change-primary-operation
  {:parameters [id secondaryOperationId]
   :input-validators [(partial action/non-blank-parameters [:id :secondaryOperationId])]
   :user-roles #{:applicant :authority}
   :states states/pre-sent-application-states
   :pre-checks [a/validate-authority-in-drafts]}
  [{:keys [application] :as command}]
  (let [old-primary-op (:primaryOperation application)
        old-secondary-ops (:secondaryOperations application)
        new-primary-op (first (filter #(= secondaryOperationId (:id %)) old-secondary-ops))
        secondary-ops-without-old-primary-op (remove #{new-primary-op} old-secondary-ops)
        new-secondary-ops (if old-primary-op ; production data contains applications with nil in primaryOperation
                            (conj secondary-ops-without-old-primary-op old-primary-op)
                            secondary-ops-without-old-primary-op)]
    (when-not (= (:id old-primary-op) secondaryOperationId)
      (when-not new-primary-op
        (fail! :error.unknown-operation))
      (update-application command {$set {:primaryOperation    new-primary-op
                                         :secondaryOperations new-secondary-ops}}))
    (ok)))

(defcommand change-permit-sub-type
  {:parameters [id permitSubtype]
   :user-roles #{:applicant :authority}
   :states     states/pre-sent-application-states
   :input-validators [(partial action/non-blank-parameters [:id :permitSubtype])]
   :pre-checks [a/validate-has-subtypes
                a/pre-check-permit-subtype
                a/validate-authority-in-drafts]}
  [{:keys [application created] :as command}]
  (update-application command {$set {:permitSubtype permitSubtype, :modified created}})
  (ok))

(defn authority-if-post-verdict-state [{user :user} {state :state}]
  (when-not (or (user/authority? user)
                (states/pre-verdict-states (keyword state)))
    (fail :error.unauthorized)))

(defcommand change-location
  {:parameters       [id x y address propertyId]
   :user-roles       #{:applicant :authority :oirAuthority}
   :states           (states/all-states-but (conj states/terminal-states :sent))
   :input-validators [(partial action/non-blank-parameters [:address])
                      (partial action/property-id-parameters [:propertyId])
                      coord/validate-x coord/validate-y]
   :pre-checks       [authority-if-post-verdict-state
                      a/validate-authority-in-drafts]}
  [{:keys [created application] :as command}]
  (if (= (:municipality application) (p/municipality-id-by-property-id propertyId))
    (do
      (update-application command
                          {$set {:location   (a/->location x y)
                                 :address    (ss/trim address)
                                 :propertyId propertyId
                                 :title      (ss/trim address)
                                 :modified   created}})
      (try (autofill-rakennuspaikka (mongo/by-id :applications id) (now))
           (catch Exception e (error e "KTJ data was not updated."))))
    (fail :error.property-in-other-muinicipality)))

;;
;; Link permits
;;

(defquery link-permit-required
          {:description "Dummy command for UI logic: returns falsey if link permit is not required."
           :parameters  [:id]
           :user-roles  #{:applicant :authority}
           :states      states/pre-sent-application-states
           :pre-checks  [(fn [_ application]
                           (when-not (a/validate-link-permits application)
                             (fail :error.link-permit-not-required)))]})

(defquery app-matches-for-link-permits
  {:parameters [id]
   :user-roles #{:applicant :authority}
   :states     (states/all-application-states-but (conj states/terminal-states :sent))}
  [{{:keys [propertyId] :as application} :application user :user :as command}]
  (let [application (meta-fields/enrich-with-link-permit-data application)
        ;; exclude from results the current application itself, and the applications that have a link-permit relation to it
        ignore-ids (-> application
                       (#(concat (:linkPermitData %) (:appsLinkingToUs %)))
                       (#(map :id %))
                       (conj id))
        results (mongo/select :applications
                              (merge (domain/application-query-for user) {:_id             {$nin ignore-ids}
                                                                          :infoRequest     false
                                                                          :permitType      (:permitType application)
                                                                          :secondaryOperations.name {$nin ["ya-jatkoaika"]}
                                                                          :primaryOperation.name {$nin ["ya-jatkoaika"]}})

                              [:permitType :address :propertyId])
        ;; add the text to show in the dropdown for selections
        enriched-results (map
                           (fn [r] (assoc r :text (str (:address r) ", " (:id r))))
                           results)
        ;; sort the results
        same-property-id-fn #(= propertyId (:propertyId %))
        with-same-property-id (vec (filter same-property-id-fn enriched-results))
        without-same-property-id (sort-by :text (vec (remove same-property-id-fn enriched-results)))
        organized-results (flatten (conj with-same-property-id without-same-property-id))
        final-results (map #(select-keys % [:id :text]) organized-results)]
    (ok :app-links final-results)))

(defn- validate-linking [command app]
  (let [link-permit-id (ss/trim (get-in command [:data :linkPermitId]))
        {:keys [appsLinkingToUs linkPermitData]} (meta-fields/enrich-with-link-permit-data app)
        max-outgoing-link-permits (operations/get-primary-operation-metadata app :max-outgoing-link-permits)
        links    (concat appsLinkingToUs linkPermitData)
        illegal-apps (conj links app)]
    (cond
      (and link-permit-id (util/find-by-id link-permit-id illegal-apps))
      (fail :error.link-permit-already-having-us-as-link-permit)

      (and max-outgoing-link-permits (= max-outgoing-link-permits (count linkPermitData)))
      (fail :error.max-outgoing-link-permits))))

(defcommand add-link-permit
  {:parameters       ["id" linkPermitId]
   :user-roles       #{:applicant :authority}
   :states           (states/all-application-states-but (conj states/terminal-states :sent)) ;; Pitaako olla myos 'sent'-tila?
   :pre-checks       [validate-linking
                      a/validate-authority-in-drafts]
   :input-validators [(partial action/non-blank-parameters [:linkPermitId])
                      (fn [{data :data}] (when (= (:id data) (ss/trim (:linkPermitId data))) (fail :error.link-permit-self-reference)))
                      (fn [{data :data}] (when-not (mongo/valid-key? (:linkPermitId data)) (fail :error.invalid-db-key)))]}
  [{application :application}]
  (a/do-add-link-permit application (ss/trim linkPermitId))
  (ok))

(defcommand remove-link-permit-by-app-id
  {:parameters [id linkPermitId]
   :input-validators [(partial action/non-blank-parameters [:id :linkPermitId])]
   :user-roles #{:applicant :authority}
   :states     (states/all-application-states-but (conj states/terminal-states :sent))
   :pre-checks [a/validate-authority-in-drafts]} ;; Pitaako olla myos 'sent'-tila?
  [{application :application}]
  (if (mongo/remove :app-links (a/make-mongo-id-for-link-permit id linkPermitId))
    (ok)
    (fail :error.unknown)))


;;
;; Change permit
;;

(defcommand create-change-permit
  {:parameters ["id"]
   :user-roles #{:applicant :authority}
   :states     #{:verdictGiven :constructionStarted}
   :pre-checks [(permit/validate-permit-type-is permit/R)]}
  [{:keys [created user application] :as command}]
  (let [muutoslupa-app-id (a/make-application-id (:municipality application))
        primary-op (:primaryOperation application)
        secondary-ops (:secondaryOperations application)
        op-id-mapping (into {} (map
                                 #(vector (:id %) (mongo/create-id))
                                 (conj secondary-ops primary-op)))
        state (if (user/authority? user) :open :draft)
        muutoslupa-app (merge domain/application-skeleton
                              (select-keys application
                                [:auth
                                 :propertyId, :location
                                 :schema-version
                                 :address, :title
                                 :foreman, :foremanRole
                                 :applicant, :_applicantIndex
                                 :municipality, :organization
                                 :drawings
                                 :metadata])

                              {:id            muutoslupa-app-id
                               :permitType    permit/R
                               :permitSubtype :muutoslupa
                               :created       created
                               :opened        (when (user/authority? user) created)
                               :modified      created
                               :documents     (into [] (map
                                                         (fn [doc]
                                                           (let [doc (assoc doc :id (mongo/create-id))]
                                                             (if (-> doc :schema-info :op)
                                                               (update-in doc [:schema-info :op :id] op-id-mapping)
                                                               doc)))
                                                         (:documents application)))
                               :state         state

                               :history [(a/history-entry state created user)]
                               :infoRequest false
                               :openInfoRequest false
                               :convertedToApplication nil

                               :primaryOperation (assoc primary-op :id (op-id-mapping (:id primary-op)))
                               :secondaryOperations (mapv #(assoc % :id (op-id-mapping (:id %))) secondary-ops)})]

    (a/do-add-link-permit muutoslupa-app (:id application))
    (a/insert-application muutoslupa-app)
    (ok :id muutoslupa-app-id)))


;;
;; Continuation period permit
;;

(defn- get-tyoaika-alkaa-from-ya-app [app]
  (let [mainostus-viitoitus-tapahtuma-doc (:data (domain/get-document-by-name app "mainosten-tai-viitoitusten-sijoittaminen"))
        tapahtuma-name-key (when mainostus-viitoitus-tapahtuma-doc
                             (-> mainostus-viitoitus-tapahtuma-doc :_selected :value keyword))
        tapahtuma-data (when tapahtuma-name-key
                         (mainostus-viitoitus-tapahtuma-doc tapahtuma-name-key))]
    (if (:started app)
      (util/to-local-date (:started app))
      (or
        (-> app (domain/get-document-by-name "tyoaika") :data :tyoaika-alkaa-pvm :value)
        (-> tapahtuma-data :tapahtuma-aika-alkaa-pvm :value)
        (util/to-local-date (:submitted app))))))

(defn- validate-not-jatkolupa-app [_ application]
  (when (= :ya-jatkoaika (-> application :primaryOperation :name keyword))
    (fail :error.cannot-apply-jatkolupa-for-jatkolupa)))

(defcommand create-continuation-period-permit
  {:parameters ["id"]
   :user-roles #{:applicant :authority}
   :states     #{:verdictGiven :constructionStarted}
   :pre-checks [(permit/validate-permit-type-is permit/YA) validate-not-jatkolupa-app]}
  [{:keys [created user application] :as command}]

  (let [continuation-app (a/do-create-application
                           (assoc command :data {:operation    "ya-jatkoaika"
                                                 :x            (-> application :location first)
                                                 :y            (-> application :location second)
                                                 :address      (:address application)
                                                 :propertyId   (:propertyId application)
                                                 :municipality (:municipality application)
                                                 :infoRequest  false
                                                 :messages     []}))
        continuation-app (merge continuation-app {:authority (:authority application)})
        ;;
        ;; ************
        ;; Lain mukaan hankeen aloituspvm on hakupvm + 21pv, tai kunnan paatospvm jos se on tata aiempi.
        ;; kts.  http://www.finlex.fi/fi/laki/alkup/2005/20050547 ,  14 a pykala
        ;; ************
        ;;
        tyoaika-alkaa-pvm (get-tyoaika-alkaa-from-ya-app application)
        tyo-aika-for-jatkoaika-doc (-> continuation-app
                                       (domain/get-document-by-name "tyo-aika-for-jatkoaika")
                                       (assoc-in [:data :tyoaika-alkaa-pvm :value] tyoaika-alkaa-pvm))
        docs (concat
               [(domain/get-document-by-name continuation-app "hankkeen-kuvaus-jatkoaika") tyo-aika-for-jatkoaika-doc]
               (map #(-> (domain/get-document-by-name application %) model/without-user-id) ["hakija-ya" "yleiset-alueet-maksaja"]))
        continuation-app (assoc continuation-app :documents docs)]

    (a/do-add-link-permit continuation-app (:id application))
    (a/insert-application continuation-app)
    (ok :id (:id continuation-app))))


(defn- validate-new-applications-enabled [command {:keys [permitType municipality] :as application}]
  (when application
    (let [scope (organization/resolve-organization-scope municipality permitType)]
      (when-not (:new-application-enabled scope)
        (fail :error.new-applications-disabled)))))

(defcommand convert-to-application
  {:parameters [id]
   :user-roles #{:applicant :authority}
   :states     states/all-inforequest-states
   :pre-checks [validate-new-applications-enabled]}
  [{:keys [user created application] :as command}]
  (let [op (:primaryOperation application)
        organization (organization/get-organization (:organization application))]
    (update-application command
                        (util/deep-merge
                          (a/state-transition-update :open created user)
                          {$set  {:infoRequest            false
                                  :openInfoRequest        false
                                  :convertedToApplication created
                                  :documents              (a/make-documents user created op application)
                                  :modified               created}
                           $push {:attachments {$each (a/make-attachments created op organization (:state application) (:tosFunction application))}}}))
    (try (autofill-rakennuspaikka application created)
         (catch Exception e (error e "KTJ data was not updated")))))

(defn- validate-organization-backend-urls [_ {org-id :organization}]
  (when org-id
    (let [org (organization/get-organization org-id)]
      (if-let [conf (:vendor-backend-redirect org)]
        (->> (vals conf)
             (remove ss/blank?)
             (some util/validate-url))
        (fail :error.vendor-urls-not-set)))))

(defn get-vendor-backend-id [verdicts]
  (->> verdicts
       (remove :draft)
       (some :kuntalupatunnus)))

(defn- get-backend-and-lp-urls [org-id]
  (-> (organization/get-organization org-id)
      :vendor-backend-redirect
      (util/select-values [:vendor-backend-url-for-backend-id
                           :vendor-backend-url-for-lp-id])))

(defn- correct-urls-configured [_ {:keys [verdicts organization] :as application}]
  (when application
    (let [vendor-backend-id          (get-vendor-backend-id verdicts)
          [backend-id-url lp-id-url] (get-backend-and-lp-urls organization)
          lp-id-url-missing?         (ss/blank? lp-id-url)
          both-urls-missing?         (and lp-id-url-missing?
                                          (ss/blank? backend-id-url))]
      (if vendor-backend-id
        (when both-urls-missing?
          (fail :error.vendor-urls-not-set))
        (when lp-id-url-missing?
          (fail :error.vendor-urls-not-set))))))

(defraw redirect-to-vendor-backend
  {:parameters [id]
   :user-roles #{:authority}
   :states     states/post-submitted-states
   :pre-checks [validate-organization-backend-urls
                correct-urls-configured]}
  [{{:keys [verdicts organization]} :application}]
  (let [vendor-backend-id          (get-vendor-backend-id verdicts)
        [backend-id-url lp-id-url] (get-backend-and-lp-urls organization)
        url-parts                  (if (and vendor-backend-id
                                            (not (ss/blank? backend-id-url)))
                                     [backend-id-url vendor-backend-id]
                                     [lp-id-url id])
        redirect-url               (apply str url-parts)]
    (info "Redirecting from" id "to" redirect-url)
    {:status 303 :headers {"Location" redirect-url}}))

