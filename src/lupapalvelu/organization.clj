(ns lupapalvelu.organization
  (:require [taoensso.timbre :as timbre :refer [trace debug debugf info warn error errorf fatal]]
            [clojure.string :as s]
            [clojure.walk :as walk]
            [monger.operators :refer :all]
            [cheshire.core :as json]
            [schema.core :as sc]
            [sade.core :refer [fail fail!]]
            [sade.env :as env]
            [sade.strings :as ss]
            [sade.util :as util]
            [sade.crypt :as crypt]
            [sade.http :as http]
            [sade.xml :as sxml]
            [sade.schemas :as ssc]
            [lupapalvelu.i18n :as i18n]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.permit :as permit]
            [lupapalvelu.wfs :as wfs]))

(def scope-skeleton
  {:permitType nil
   :municipality nil
   :inforequest-enabled false
   :new-application-enabled false
   :open-inforequest false
   :open-inforequest-email ""
   :opening nil})

(sc/defschema Tag
  {:id ssc/ObjectIdStr
   :label sc/Str})

(sc/defschema Layer
  {:id sc/Str
   :base sc/Bool
   :name sc/Str})

(def permanent-archive-authority-roles [:tos-editor :tos-publisher :archivist])
(def authority-roles (concat [:authority :approver :commenter :reader] permanent-archive-authority-roles))

(defn- with-scope-defaults [org]
  (if (:scope org)
    (update-in org [:scope] #(map (fn [s] (util/deep-merge scope-skeleton s)) %))
    org))

(defn- remove-sensitive-data
  [org]
  (if (:krysp org)
    (update org :krysp #(into {} (map (fn [[permit-type config]] [permit-type (dissoc config :password :crypto-iv)]) %)))
    org))

(defn get-organizations
  ([]
    (get-organizations {}))
  ([query]
   (->> (mongo/select :organizations query)
        (map remove-sensitive-data)
        (map with-scope-defaults)))
  ([query projection]
   (->> (mongo/select :organizations query projection)
        (map remove-sensitive-data)
        (map with-scope-defaults))))

(defn get-organization [id]
  {:pre [(not (s/blank? id))]}
  (->> (mongo/by-id :organizations id)
       remove-sensitive-data
       with-scope-defaults))

(defn update-organization [id changes]
  {:pre [(not (s/blank? id))]}
  (mongo/update-by-id :organizations id changes))

(defn get-organization-attachments-for-operation [organization operation]
  (-> organization :operations-attachments ((-> operation :name keyword))))

(defn encode-credentials
  [username password]
  (when-not (s/blank? username)
    (let [crypto-key       (-> (env/value :backing-system :crypto-key) (crypt/str->bytes) (crypt/base64-decode))
          crypto-iv        (crypt/make-iv-128)
          crypted-password (->> password
                                (crypt/str->bytes)
                                (crypt/encrypt crypto-key crypto-iv :aes)
                                (crypt/base64-encode)
                                (crypt/bytes->str))
          crypto-iv        (-> crypto-iv (crypt/base64-encode) (crypt/bytes->str))]
      {:username username :password crypted-password :crypto-iv crypto-iv})))

(defn decode-credentials
  "Decode password that was originally generated (together with the init-vector )by encode-credentials.
   Arguments are base64 encoded."
  [password crypto-iv]
  (let [crypto-key   (-> (env/value :backing-system :crypto-key) (crypt/str->bytes) (crypt/base64-decode))
        crypto-iv (-> crypto-iv crypt/str->bytes crypt/base64-decode)]
    (->> password
                          (crypt/str->bytes)
                          (crypt/base64-decode)
                          (crypt/decrypt crypto-key crypto-iv :aes)
                          (crypt/bytes->str))))

(defn get-krysp-wfs
  "Returns a map containing :url and :version information for municipality's KRYSP WFS"
  ([{:keys [organization permitType] :as application}]
    (get-krysp-wfs {:_id organization} permitType))
  ([query permit-type]
   (let [organization (mongo/select-one :organizations query [:krysp])
         krysp-config (get-in organization [:krysp (keyword permit-type)])
         crypto-key   (-> (env/value :backing-system :crypto-key) (crypt/str->bytes) (crypt/base64-decode))
         crypto-iv    (:crypto-iv krysp-config)
         password     (when-let [password (and crypto-iv (:password krysp-config))]
                        (decode-credentials password crypto-iv))
         username     (:username krysp-config)]
     (when-not (s/blank? (:url krysp-config))
       (->> (when username {:credentials [username password]})
            (merge (select-keys krysp-config [:url :version])))))))

(defn municipality-address-endpoint [municipality]
  (when (and (not (ss/blank? municipality)) (re-matches #"\d{3}" municipality) )
    (get-krysp-wfs {:scope.municipality municipality, :krysp.osoitteet.url {"$regex" ".+"}} :osoitteet)))


(defn set-krysp-endpoint
  [id url username password endpoint-type version]
  {:pre [(mongo/valid-key? endpoint-type)]}
  (let [url (ss/trim url)
        updates (->> (encode-credentials username password)
                  (merge {:url url :version version})
                  (map (fn [[k v]] [(str "krysp." endpoint-type "." (name k)) v]))
                  (into {})
                  (hash-map $set))]
    (if (and (not (ss/blank? url)) (= "osoitteet" endpoint-type))
      (let [capabilities-xml (wfs/get-capabilities-xml url username password)
            osoite-feature-type (some->> (wfs/feature-types capabilities-xml)
                                         (map (comp :FeatureType sxml/xml->edn))
                                         (filter #(re-matches #"[a-z]*:?Osoite$" (:Name %))) first)
            address-updates (assoc-in updates [$set (str "krysp." endpoint-type "." "defaultSRS")] (:DefaultSRS osoite-feature-type))]
        (if-not osoite-feature-type
          (fail! :error.no-address-feature-type)
          (update-organization id address-updates)))
      (update-organization id updates))))

(defn get-organization-name [organization]
  (let [default (get-in organization [:name :fi] (str "???ORG:" (:id organization) "???"))]
    (get-in organization [:name i18n/*lang*] default)))

(defn resolve-organizations
  ([municipality]
    (resolve-organizations municipality nil))
  ([municipality permit-type]
    (get-organizations {:scope {$elemMatch (merge {:municipality municipality} (when permit-type {:permitType permit-type}))}})))

(defn resolve-organization [municipality permit-type]
  {:pre  [municipality (permit/valid-permit-type? permit-type)]}
  (when-let [organizations (resolve-organizations municipality permit-type)]
    (when (> (count organizations) 1)
      (errorf "*** multiple organizations in scope of - municipality=%s, permit-type=%s -> %s" municipality permit-type (count organizations)))
    (first organizations)))

(defn resolve-organization-scope
  ([municipality permit-type]
    {:pre  [municipality (permit/valid-permit-type? permit-type)]}
    (let [organization (resolve-organization municipality permit-type)]
      (resolve-organization-scope municipality permit-type organization)))
  ([municipality permit-type organization]
    {:pre  [municipality organization (permit/valid-permit-type? permit-type)]}
   (first (filter #(and (= municipality (:municipality %)) (= permit-type (:permitType %))) (:scope organization)))))

(defn with-organization [id function]
  (if-let [organization (get-organization id)]
    (function organization)
    (do
      (debugf "organization '%s' not found with id." id)
      (fail :error.organization-not-found))))

(defn has-ftp-user? [organization permit-type]
  (not (ss/blank? (get-in organization [:krysp (keyword permit-type) :ftpUser]))))

(defn allowed-roles-in-organization [organization]
  {:pre [(map? organization)]}
  (if-not (:permanent-archive-enabled organization)
    (remove #(% (set permanent-archive-authority-roles)) authority-roles)
    authority-roles))

(defn filter-valid-user-roles-in-organization [organization roles]
  (let [organization  (if (map? organization) organization (get-organization organization))
        allowed-roles (set (allowed-roles-in-organization organization))]
    (filter (comp allowed-roles keyword) roles)))

(defn create-tag-ids
  "Creates mongo id for tag if id is not present"
  [tags]
  (map
    #(if (:id %)
       %
       (assoc % :id (mongo/create-id)))
    tags))

(defn some-organization-has-archive-enabled? [organization-ids]
  (pos? (mongo/count :organizations {:_id {$in organization-ids} :permanent-archive-enabled true})))


;;
;; Organization/municipality provided map support.

(defn query-organization-map-server
  [org-id params headers]
  (when-let [m (-> org-id get-organization :map-layers :server)]
    (let [{:keys [url username password crypto-iv]} m]
      (http/get url
                (merge {:query-params params}
                       (when-not (ss/blank? crypto-iv)
                         {:basic-auth [username (decode-credentials password crypto-iv)]})
                       {:headers (select-keys headers [:accept :accept-encoding])
                        :as :stream})))))

(defn organization-map-layers-data [org-id]
  (when-let [{:keys [server layers]} (-> org-id get-organization :map-layers)]
    (let [{:keys [url username password crypto-iv]} server]
      {:server {:url url
                :username username
                :password (if (ss/blank? crypto-iv)
                            password
                            (decode-credentials password crypto-iv))}
       :layers layers})))

(defn update-organization-map-server [org-id url username password]
  (let [credentials (if (ss/blank? password)
                      {:username username
                       :password password}
                      (encode-credentials username password))
        server      (assoc credentials :url url)]
   (update-organization org-id {$set {:map-layers.server server}})))
