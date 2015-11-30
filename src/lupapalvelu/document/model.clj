(ns lupapalvelu.document.model
  (:require [taoensso.timbre :as timbre :refer [trace debug info warn error fatal]]
            [clojure.walk :refer [keywordize-keys]]
            [clojure.string :refer [join]]
            [clojure.set :refer [union difference]]
            [clojure.string :as s]
            [clj-time.format :as timeformat]
            [sade.env :as env]
            [sade.util :as util]
            [sade.strings :as ss]
            [sade.core :refer :all]
            [sade.validators :as v]
            [lupapalvelu.authorization :as auth]
            [lupapalvelu.document.vrk :refer :all]
            [lupapalvelu.document.document-field-validators :refer :all]
            [lupapalvelu.document.schemas :as schemas]
            [lupapalvelu.document.tools :as tools]
            [lupapalvelu.domain :as domain]
            [lupapalvelu.document.validator :as validator]
            [lupapalvelu.document.subtype :as subtype]
            [lupapalvelu.mongo :as mongo]))

;;
;; Validation:
;;

;; if you changes these values, change it in docgen.js, too
(def default-max-len 255)
(def dd-mm-yyyy (timeformat/formatter "dd.MM.YYYY"))

(def- latin1 (java.nio.charset.Charset/forName "ISO-8859-1"))

(defn- latin1-encoder
  "Creates a new ISO-8859-1 CharsetEncoder instance, which is not thread safe."
  [] (.newEncoder latin1))

;;
;; Field validation
;;

(defmulti validate-field (fn [application elem value] (keyword (:type elem))))

(defmethod validate-field :group [_ _ v]
  (if (not (map? v)) [:err "illegal-value:not-a-map"]))

(defmethod validate-field :table [_ _ v]
  (if (not (map? v)) [:err "illegal-value:not-a-map"]))

(defmethod validate-field :string [_ {:keys [max-len min-len] :as elem} v]
  (when-not (nil? v)
    (cond
      (not (string? v)) [:err "illegal-value:not-a-string"]
      (not (.canEncode (latin1-encoder) v)) [:warn "illegal-value:not-latin1-string"]
      (> (.length v) (or max-len default-max-len)) [:err "illegal-value:too-long"]
      (and
        (> (.length v) 0)
        (< (.length v) (or min-len 0))) [:warn "illegal-value:too-short"]
      :else (subtype/subtype-validation elem v))))

(defmethod validate-field :text [_ elem v]
  (cond
    (not (string? v)) [:err "illegal-value:not-a-string"]
    (> (.length v) (or (:max-len elem) default-max-len)) [:err "illegal-value:too-long"]
    (and
(> (.length v) 0)
(< (.length v) (or (:min-len elem) 0))) [:warn "illegal-value:too-short"]))

(defmethod validate-field :hetu [_ _ v]
  (cond
    (ss/blank? v) nil
    (re-matches v/finnish-hetu-regex v) (when-not (v/valid-hetu? v) [:err "illegal-hetu"])
    :else [:err "illegal-hetu"]))

(defmethod validate-field :checkbox [_ _ v]
  (if (not= (type v) Boolean) [:err "illegal-value:not-a-boolean"]))

(defmethod validate-field :date [_ _ v]
  (try
    (or (ss/blank? v) (timeformat/parse dd-mm-yyyy v))
    nil
    (catch Exception e [:warn "illegal-value:date"])))

(defmethod validate-field :time [_ _ v]
  (when-not (ss/blank? v)
    (if-let [matches (seq (rest (re-matches util/time-pattern v)))]
      (let [h (util/->int (first matches))
            m (util/->int (second matches))]
        (when-not (and (<= 0 h 23) (<= 0 m 59)) [:warn "illegal-value:time"]))
      [:warn "illegal-value:time"])))

(defmethod validate-field :select [_ {:keys [body other-key]} v]
  (let [accepted-values (set (map :name body))
        accepted-values (if other-key (conj accepted-values "other") accepted-values)]
    (when-not (or (ss/blank? v) (accepted-values v))
      [:warn "illegal-value:select"])))

(defmethod validate-field :radioGroup [_ {body :body} v]
  (let [accepted-values (set (map :name body))]
    (when-not (or (ss/blank? v) (accepted-values v))
      [:warn "illegal-value:select"])))

(defmethod validate-field :buildingSelector [_ elem v]
  (cond
    (ss/blank? v) nil
    (= "other" v) nil
    (v/rakennusnumero? v) nil
    (v/rakennustunnus? v) nil
    :else [:warn "illegal-rakennusnumero"]))

(defmethod validate-field :newBuildingSelector [_ elem v]
  (when (not= v "ei tiedossa")
    (subtype/subtype-validation {:subtype :number} v)))

(defmethod validate-field :personSelector [application elem v]
  (when-not (ss/blank? v)
    (when-not (and
                (auth/has-auth? application v)
                (domain/no-pending-invites? application v))
      [:err "application-does-not-have-given-auth"])))

(defmethod validate-field :companySelector [application elem v]
  (when-not (or (string? v) (nil? v))
    [:err "unknown-type"]))

(defmethod validate-field :fillMyInfoButton [_ _ _] nil)
(defmethod validate-field :foremanHistory [_ _ _] nil)
(defmethod validate-field :foremanOtherApplications [_ _ _] nil)

(defmethod validate-field :maaraalaTunnus [_ _ v]
  (cond
    (ss/blank? v) nil
    (re-matches v/maara-alatunnus-pattern v) nil
    :else [:warn "illegal-maaraala-tunnus"]))

(defmethod validate-field nil [_ _ _]
  [:err "illegal-key"])

(defmethod validate-field :default [_ elem _]
  (warn "Unknown schema type: elem=[%s]" elem)
  [:err "unknown-type"])

;;
;; Element validation (:validator key in schema)
;;

(declare find-by-name)

(defn good-postal-code?
    "Empty postal code is always valid. The idea here is to avoid
  false negatives and this should be a safe assumption since the
  required fields are enforced on the schema level."
  [postal-code country]
  (if (= country "FIN")
    (or (ss/blank? postal-code) (v/finnish-zip? postal-code))
    true))

(defmulti validate-element (fn [_ _ _ element]
                           (:validator element)))

(defmethod validate-element :address
  [info data path element]
  (let [{:keys [postinumero maa]} (tools/unwrapped data)]
    (when-not (good-postal-code? postinumero maa)
      {:path     (-> (map keyword path) (concat [:postinumero]))
       :element  (assoc (find-by-name (:body element) [:postinumero]) :locKey "postinumero")
       :document (:document info)
       :result   [:warn "bad-postal-code"]})))


;;
;; Neue api:
;;

(defn find-by-name [schema-body [k & ks]]
  (when-let [elem (some #(when (= (:name %) (name k)) %) schema-body)]
    (if (nil? ks)
      elem
      (if (:repeating elem)
        (when (ss/numeric? (name (first ks)))
          (if (seq (rest ks))
            (find-by-name (:body elem) (rest ks))
            elem))
        (find-by-name (:body elem) ks)))))

(defn- resolve-element-loc-key [info element path]
  (if (:i18nkey element)
    (:i18nkey element)
    (->
      (str
        (join "." (concat [(-> info :document :locKey)] (map name path)))
        (when (= :select (:type element)) "._group_label"))
      (s/replace #"\.+\d+\." ".")  ;; removes numbers in the middle:  "a.1.b" => "a.b"
      (s/replace #"\.+" "."))))    ;; removes multiple dots: "a..b" => "a.b"

(defn- ->validation-result [info data path element result]
  (when result
    (let [result {:data        data
                  :path        (vec (map keyword path))
                  :element     (merge element {:locKey (resolve-element-loc-key info element path)})
                  :document    (:document info)
                  :result      result}]
      ; Return results without :data.
      ; Data is handy when hacking in REPL, though.
      ; See also mongo_scripts/prod/hetu-cleanup.js.
      (dissoc result :data))))

(defn- validate-fields [application info k data path]
  (let [current-path (if k (conj path (name k)) path)
        element (if (not-empty current-path)
                  (keywordize-keys (find-by-name (:schema-body info) current-path))
                  {})]
    (if (contains? data :value)
      (let [result  (validate-field application element (:value data))]
        (->validation-result info data current-path element result))
      (let [result (when (:validator element)
                     (validate-element info data current-path element))]
        (filter
         (comp not nil?)
         (concat (flatten [result])
                 (map (fn [[k2 v2]]
                        (validate-fields application info k2 v2 current-path)) data)))))))

(defn- sub-schema-by-name [sub-schemas name]
  (some (fn [schema] (when (= (:name schema) name) schema)) sub-schemas))

(defn- one-of-many-options [sub-schemas]
  (map :name (:body (sub-schema-by-name sub-schemas schemas/select-one-of-key))))

(defn- one-of-many-selection [sub-schemas path data]
  (when-let [one-of (seq (one-of-many-options sub-schemas))]
    (or (get-in data (conj path :_selected :value)) (first one-of))))

(defn- validate-required-fields [info path data validation-errors]
  (let [check (fn [{:keys [name required body repeating] :as element}]
                (let [kw (keyword name)
                      current-path (conj path kw)
                      validation-error (when (and required (ss/blank? (get-in data (conj current-path :value))))
                                         (->validation-result info nil current-path element [:tip "illegal-value:required"]))
                      current-validation-errors (if validation-error (conj validation-errors validation-error) validation-errors)]
                  (concat current-validation-errors
                    (if body
                      (let [newInfo (assoc info :schema-body body)]
                        (if repeating
                          (map (fn [k] (validate-required-fields newInfo (conj current-path k) data [])) (keys (get-in data current-path)))
                          (validate-required-fields newInfo current-path data [])))
                      []))))

        schema-body (:schema-body info)
        selected (one-of-many-selection schema-body path data)
        sub-schemas-to-validate (-> (set (map :name schema-body))
                                  (difference (set (one-of-many-options schema-body)) #{schemas/select-one-of-key})
                                  (union (when selected #{selected})))]

      (map #(check (sub-schema-by-name schema-body %)) sub-schemas-to-validate)))

(defn get-document-schema [{schema-info :schema-info}]
  {:pre [schema-info]
   :post [%]}
  (schemas/get-schema schema-info))

(defn- validate-document [schema document info data]
  (let [doc-validation-results (validator/validate document)]
    (map
      #(let [element (find-by-name (:schema-body info) (:path %))]
         (->validation-result info data (:path %) element (:result %)))
      doc-validation-results)))

(defn validate
  "Validates document against schema and document level rules. Returns list of validation errors.
   If schema is not given, uses schema defined in document."
  ([application document]
    (validate application document nil))
  ([application document schema]
    {:pre [(map? application) (map? document)]}
    (let [data (:data document)
          schema (or schema (get-document-schema document))
          info {:document {:id (:id document)
                           :name (-> schema :info :name)
                           :locKey (or (-> schema :info :i18name) (-> schema :info :name))
                           :type (-> schema :info :type)}
                :schema-body (:body schema)}]
      (when data
        (flatten
          (concat
            (validate-fields application info nil data [])
            (validate-required-fields info [] data [])
            (validate-document schema document info data)))))))

(defn has-errors?
  [results]
  (->>
    results
    (map :result)
    (map first)
    (some (partial = :err))
    true?))

;;
;; Updates
;;

(def ^:dynamic *timestamp* nil)
(defn current-timestamp
  "Returns the current timestamp to be used in document modifications."
  [] *timestamp*)

(defmacro with-timestamp [timestamp & body]
  `(binding [*timestamp* ~timestamp]
     ~@body))

(declare apply-updates)

(defn map2updates
  "Creates model-updates from map into path."
  [path m]
  (map (fn [[p v]] [(into path p) v]) (tools/path-vals m)))

(defn apply-update
  "Updates a document returning the modified document.
   Value defaults to \"\", e.g. unsetting the value.
   To be used within with-timestamp.
   Example: (apply-update document [:mitat :koko] 12)"
  ([document path]
    (apply-update document path ""))
  ([document path value]
    (if (map? value)
      (apply-updates document (map2updates path value))
      (let [data-path (vec (flatten [:data path]))]
        (-> document
          (assoc-in (conj data-path :value) value)
          (assoc-in (conj data-path :modified) (current-timestamp)))))))

(defn apply-updates
  "Updates a document returning the modified document.
   To be used within with-timestamp.
   Example: (apply-updates document [[:mitat :koko] 12])"
  [document updates]
  (reduce (fn [document [path value]] (apply-update document path value)) document updates))

;;
;; Approvals
;;

(defn ->approved
  "Approval meta data model. To be used within with-timestamp."
  [status user]
  {:value status
   :user (select-keys user [:id :firstName :lastName])
   :timestamp (current-timestamp)})


(defn apply-approval
  "Merges approval meta data into a map.
   To be used within with-timestamp or with a given timestamp."
  ([document path status user]
    (assoc-in document (filter (comp not nil?) (flatten [:meta path :_approved])) (->approved status user)))
  ([document path status user timestamp]
    (with-timestamp timestamp (apply-approval document path status user))))

(defn approvable?
  ([document] (approvable? document nil nil))
  ([document path] (approvable? document nil path))
  ([document schema path]
    (if (seq path)
      (let [schema      (or schema (get-document-schema document))
            schema-body (:body schema)
            str-path    (map #(if (keyword? %) (name %) %) path)
            element     (keywordize-keys (find-by-name schema-body str-path))]
        (true? (:approvable element)))
      (true? (get-in document [:schema-info :approvable])))))

(defn modifications-since-approvals
  ([{:keys [schema-info data meta] :as document}]
    (let [schema (and schema-info (schemas/get-schema (:version schema-info) (:name schema-info)))
          timestamp (max (get-in meta [:_approved :timestamp] 0) (get-in meta [:_indicator_reset :timestamp] 0))]
      (modifications-since-approvals (:body schema) [] data meta (get-in schema [:info :approvable]) timestamp)))
  ([schema-body path data meta approvable-parent timestamp]
    (letfn [(max-timestamp [p] (max timestamp (get-in meta (concat p [:_approved :timestamp]) 0)))
            (count-mods
              [{:keys [name approvable repeating body type] :as element}]
              (let [current-path (conj path (keyword name))
                    current-approvable (or approvable-parent approvable)]
                (if (or (= :group type) (= :table type))
                  (if repeating
                    (reduce + 0 (map (fn [k]
                                       (modifications-since-approvals body (conj current-path k) data meta current-approvable (max-timestamp (conj current-path k))))
                                     (keys (get-in data current-path))))
                    (modifications-since-approvals body current-path data meta current-approvable (max-timestamp current-path)))
                  (if (and
                        current-approvable
                        (>
                          (or
                            (get-in data (conj current-path :modified))
                            0)
                          (max-timestamp current-path)))
                    1
                    0))))]
      (reduce + 0 (map count-mods schema-body)))))

(defn mark-approval-indicators-seen-update
  "Generates update map for marking document approval indicators seen. Merge into $set statement."
  [{documents :documents} timestamp]
  (mongo/generate-array-updates :documents documents (constantly true) "meta._indicator_reset.timestamp" timestamp))

;;
;; Create
;;

(defn new-document
  "Creates an empty document out of schema"
  [schema created]
  {:id          (mongo/create-id)
   :created     created
   :schema-info (:info schema)
   :data        (tools/create-document-data schema tools/default-values)})

;;
;; Convert data
;;
(defn convert-document-data
  "Walks document data starting from initial-path.
   If predicate matches, value is outputted using emitter function.
   Both predicate and emitter take two parameters: element schema definition and the value map."
  [pred emitter {data :data :as document} initial-path]
  (if-not data
    document
    (letfn [(doc-walk [schema-body path]
              (into {}
                (map
                  (fn [{:keys [name type body repeating] :as element}]
                    (let [k (keyword name)
                          current-path (conj path k)
                          v (get-in data current-path)]
                      (if (pred element v)
                        [k (emitter element v)]
                        (when v
                          (if (or (= (keyword type) :group) (= (keyword type) :table))
                            [k (if repeating
                                 (into {} (map (fn [k2] [k2 (doc-walk body (conj current-path k2))]) (keys v)))
                                 (doc-walk body current-path))]
                            [k v])))))
                  schema-body)))]
      (let [path (vec initial-path)
            schema (get-document-schema document)
            schema-body (:body (if (seq path) (find-by-name (:body schema) path) schema))]
        (assoc-in document (concat [:data] path) (doc-walk schema-body path))))))

(defn strip-blacklisted-data
  "Strips values from document data if blacklist in schema includes given blacklist-item."
  [document blacklist-item & [initial-path]]
  (let [bl-kw (keyword blacklist-item)
        strip-if (fn [{bl :blacklist} _] ((set (map keyword bl)) bl-kw))]
    (convert-document-data strip-if (constantly nil) document initial-path)))

(defn strip-turvakielto-data [{data :data :as document}]
  (reduce
    (fn [doc [path v]]
      (let [turvakielto-value (:value v)
            ; Strip data starting from one level up.
            ; Fragile, but currently schemas are modeled this way!
            strip-from (butlast path)]
        (if turvakielto-value
          (strip-blacklisted-data doc schemas/turvakielto strip-from)
          doc)))
    document
    (tools/deep-find data (keyword schemas/turvakielto))))

(defn mask-person-id-ending
  "Replaces last characters of person IDs with asterisks (e.g., 010188-123A -> 010188-****)"
  [document & [initial-path]]
  (let [mask-if (fn [{type :type} {hetu :value}] (and (= (keyword type) :hetu) hetu (> (count hetu) 7)))
        do-mask (fn [_ {hetu :value :as v}] (assoc v :value (str (subs hetu 0 7) "****")))]
    (convert-document-data mask-if do-mask document initial-path)))

(defn mask-person-id-birthday
  "Replaces first characters of person IDs with asterisks (e.g., 010188-123A -> ******-123A)"
  [document & [initial-path]]
  (let [mask-if (fn [{type :type} {hetu :value}] (and (= (keyword type) :hetu) hetu (pos? (count hetu))))
        do-mask (fn [_ {hetu :value :as v}] (assoc v :value (str "******" (ss/substring hetu 6 11))))]
    (convert-document-data mask-if do-mask document initial-path)))

(defn without-user-id
  "Removes userIds from the document."
  [doc]
  (util/postwalk-map (fn [m] (dissoc m :userId)) doc))

(defn has-hetu?
  ([schema]
    (has-hetu? schema [:henkilo]))
  ([schema-body base-path]
    (let [full-path (apply conj base-path [:henkilotiedot :hetu])]
      (boolean (find-by-name schema-body full-path)))))

(defn good-flag? [flag]
  (or (nil? flag) (util/boolean? flag)))

(defn ->henkilo [{:keys [id firstName lastName email phone street zip city personId turvakieltokytkin
                         companyName companyId allowDirectMarketing
                         fise fiseKelpoisuus degree graduatingYear]} & {:keys [with-hetu with-empty-defaults?]}]
  {:pre [(good-flag? turvakieltokytkin) (good-flag? allowDirectMarketing)]}
  (letfn [(wrap [v] (if (and with-empty-defaults? (nil? v)) "" v))]
    (->
      {:userId                                  (wrap id)
       :henkilotiedot {:etunimi                 (wrap firstName)
                       :sukunimi                (wrap lastName)
                       :hetu                    (wrap (when with-hetu personId))
                       :turvakieltoKytkin       (when (or turvakieltokytkin with-empty-defaults?) (boolean turvakieltokytkin))}
       :yhteystiedot {:email                    (wrap email)
                      :puhelin                  (wrap phone)}
       :kytkimet {:suoramarkkinointilupa        (when (or allowDirectMarketing with-empty-defaults?) (boolean allowDirectMarketing))}
       :osoite {:katu                           (wrap street)
                :postinumero                    (wrap zip)
                :postitoimipaikannimi           (wrap city)}
       :yritys {:yritysnimi                     (wrap companyName)
                :liikeJaYhteisoTunnus           (wrap companyId)}
       :patevyys {:koulutusvalinta              (wrap degree)
                  :koulutus                     nil
                  :valmistumisvuosi             (wrap graduatingYear)
                  :fise                         (wrap fise)
                  :fiseKelpoisuus               (wrap fiseKelpoisuus)}
       :patevyys-tyonjohtaja {:koulutusvalinta  (wrap degree)
                              :koulutus         nil
                              :valmistumisvuosi (wrap graduatingYear)}}
      util/strip-nils
      util/strip-empty-maps
      tools/wrapped)))

(defn ->yritys [{:keys [firstName lastName email phone address1 zip po turvakieltokytkin name y ovt pop allowDirectMarketing]}
                & {:keys [with-empty-defaults?]}]
  {:pre [(good-flag? turvakieltokytkin) (good-flag? allowDirectMarketing)]}
  (letfn [(wrap [v] (if (and with-empty-defaults? (nil? v)) "" v))]
    (->
      {:yritysnimi                                    (wrap name)
       :liikeJaYhteisoTunnus                          (wrap y)
       :osoite {:katu                                 (wrap address1)
                :postinumero                          (wrap zip)
                :postitoimipaikannimi                 (wrap po)}
       :yhteyshenkilo {:henkilotiedot {:etunimi       (wrap firstName)
                                       :sukunimi      (wrap lastName)
                                       :turvakieltoKytkin (when (or turvakieltokytkin with-empty-defaults?) (boolean turvakieltokytkin))}
                       :yhteystiedot {:email          (wrap email)
                                      :puhelin        (wrap phone)}
                       :kytkimet {:suoramarkkinointilupa (when (or allowDirectMarketing with-empty-defaults?) (boolean allowDirectMarketing))}}
       :verkkolaskutustieto {:ovtTunnus               (wrap ovt)
                             :verkkolaskuTunnus       ""
                             :valittajaTunnus         (wrap pop)}}
      util/strip-nils
      util/strip-empty-maps
      tools/wrapped)))

