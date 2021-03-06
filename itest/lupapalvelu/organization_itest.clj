(ns lupapalvelu.organization-itest
  (:require [midje.sweet :refer :all]
            [clojure.java.io :as io]
            [lupapalvelu.organization :as local-org-api]
            [lupapalvelu.operations :as operations]
            [lupapalvelu.proxy-services :as proxy]
            [lupapalvelu.factlet :refer [fact* facts*]]
            [lupapalvelu.itest-util :refer :all]
            [lupapalvelu.mongo :as mongo]
            [lupapalvelu.fixture.core :as fixture]
            [monger.operators :refer :all]
            [sade.core :as sade]))

(apply-remote-minimal)

(facts
  (let [uri "http://127.0.0.1:8000/dev/krysp"]
    (fact "pena can't set krysp-url"
      (command pena :set-krysp-endpoint :url uri :username "" :password "" :permitType "R" :version "1") => unauthorized?)

    (fact "sipoo can set working krysp-url"
      (command sipoo :set-krysp-endpoint :url uri :username "" :password "" :permitType "YA" :version "2") => ok?)

   (fact "sipoo cant set incorrect krysp-url"
      (command sipoo :set-krysp-endpoint :url "BROKEN_URL" :username "" :password "" :permitType "R"  :version "1") => fail?)))

(facts
  (let [uri "http://127.0.0.1:8000/dev/private-krysp"]

    (fact "sipoo can not set working krysp-url without credentials"
      (command sipoo :set-krysp-endpoint :url uri :username "" :password "" :permitType "R" :version "2") => fail?)

    (fact "sipoo can not set working krysp-url with incorrect credentials"
      (command sipoo :set-krysp-endpoint :url uri :username "foo" :password "bar" :permitType "R" :version "2") => fail?)

    (fact "sipoo can set working krysp-url with correct credentials"
      (command sipoo :set-krysp-endpoint :url uri :username "pena" :password "pena" :permitType "R" :version "2") => ok?)

    (fact "sipoo can not set working krysp-url with incorrect username and saved password"
      (command sipoo :set-krysp-endpoint :url uri :username "foo" :password "" :permitType "R" :version "2") => fail?)

    (fact "sipoo can set working krysp-url with only username set"
      (command sipoo :set-krysp-endpoint :url uri :username "pena" :password "" :permitType "R" :version "2") => ok?)

    (fact "query krysp config - no url or credentials set for P endpoint"
      (-> (query sipoo :krysp-config) :krysp :P (select-keys [:url :username :password])) => {})

    (fact "query krysp config - credentials not set for YA endpoint"
      (-> (query sipoo :krysp-config) :krysp :YA (select-keys [:url :username :password])) => (just {:url anything}))

    (fact "query krysp config - credentials set for R endpoint - password is not returned"
      (-> (query sipoo :krysp-config) :krysp :R (select-keys [:url :username :password])) => (just {:url uri :username "pena"}))))


(facts* "users-in-same-organizations"
  (let [naantali (apikey-for "rakennustarkastaja@naantali.fi")
        jarvenpaa (apikey-for "rakennustarkastaja@jarvenpaa.fi")
        oulu (apikey-for "olli")

        naantali-user (query naantali :user) => ok?
        jarvenpaa-user (query jarvenpaa :user) => ok?
        oulu-user (query oulu :user) => ok?]

    ; Meta
    (fact "naantali user in naantali & jarvenpaa orgs"
      (->> naantali-user :user :orgAuthz keys) => (just [:529-R :186-R] :in-any-order))
    (fact "jarvenpaa just jarvenpaa"
      (->> jarvenpaa-user :user :orgAuthz keys) => [:186-R])
    (fact "oulu user in oulu & naantali orgs"
      (->> oulu-user :user :orgAuthz keys) => (just [:564-R :529-R :564-YMP] :in-any-order))


    (let [naantali-sees (:users (query naantali :users-in-same-organizations))
          jarvenpaa-sees (:users (query jarvenpaa :users-in-same-organizations))
          oulu-sees (:users (query oulu :users-in-same-organizations))]

      (fact "naantali user sees other users in naantali & jarvenpaa (but not admin)"
        (map :username naantali-sees) =>
        (contains ["rakennustarkastaja@naantali.fi" "lupasihteeri@naantali.fi" "rakennustarkastaja@jarvenpaa.fi" "lupasihteeri@jarvenpaa.fi" "olli"] :in-any-order))

      (fact "jarvenpaa just jarvenpaa users (incl. Mr. Naantali but not admin)"
        (map :username jarvenpaa-sees) =>
        (contains ["rakennustarkastaja@jarvenpaa.fi" "lupasihteeri@jarvenpaa.fi" "rakennustarkastaja@naantali.fi"] :in-any-order))

      (fact "oulu user sees other users in oulu & naantali"
        (map :username oulu-sees) =>
        (contains ["olli" "rakennustarkastaja@naantali.fi" "lupasihteeri@naantali.fi"] :in-any-order)))))

(fact* "Organization details query works"
 (let [resp  (query pena "organization-details" :municipality "753" :operation "kerrostalo-rivitalo" :lang "fi") => ok?]
   (count (:attachmentsForOp resp )) => pos?
   (count (:links resp)) => pos?))

(fact* "The query /organizations"
  (let [resp (query admin :organizations) => ok?]
    (count (:organizations resp)) => pos?))

(fact "Update organization"
  (let [organization         (first (:organizations (query admin :organizations)))
        orig-scope           (first (:scope organization))
        organization-id      (:id organization)
        resp                 (command admin :update-organization
                               :permitType (:permitType orig-scope)
                               :municipality (:municipality orig-scope)
                               :inforequestEnabled (not (:inforequest-enabled orig-scope))
                               :applicationEnabled (not (:new-application-enabled orig-scope))
                               :openInforequestEnabled (not (:open-inforequest orig-scope))
                               :openInforequestEmail "someone@localhost"
                               :opening nil)
        updated-organization (query admin :organization-by-id :organizationId organization-id)
        updated-scope        (local-org-api/resolve-organization-scope (:municipality orig-scope) (:permitType orig-scope) updated-organization)]

    resp => ok?

    (fact "inforequest-enabled" (:inforequest-enabled updated-scope) => (not (:inforequest-enabled orig-scope)))
    (fact "new-application-enabled" (:new-application-enabled updated-scope) => (not (:new-application-enabled orig-scope)))
    (fact "open-inforequest" (:open-inforequest updated-scope) => (not (:open-inforequest orig-scope)))
    (fact "open-inforequest-email" (:open-inforequest-email updated-scope) => "someone@localhost")))

(fact* "Tampere-ya sees (only) YA operations and attachments (LUPA-917, LUPA-1006)"
  (let [resp (query tampere-ya :organization-by-user) => ok?
        tre  (:organization resp)]
    (keys (:operationsAttachments tre)) => [:YA]
    (-> tre :operationsAttachments :YA) => truthy
    (keys (:attachmentTypes resp)) => [:YA]
    (-> resp :attachmentTypes :YA) => truthy))


(facts "Selected operations"

  (fact "For an organization which has no selected operations, all operations are returned"
    (let [resp (query sipoo "all-operations-for-organization" :organizationId "753-YA")
          operations (:operations resp)]
      ;; All the YA operations (and only those) are received here.
      (count operations) => 1
      (-> operations first first) => "yleisten-alueiden-luvat"))

  (fact "Set selected operations"
    (command pena "set-organization-selected-operations" :operations ["pientalo" "aita"]) => unauthorized?
    (command sipoo "set-organization-selected-operations" :operations ["pientalo" "aita"]) => ok?)

  (fact* "Query selected operations"
    (query pena "selected-operations-for-municipality" :municipality "753") => ok?
    (let [resp (query sipoo "selected-operations-for-municipality" :municipality "753")]
      resp => ok?

      ;; Received the two selected R operations plus 4 YA operations.
      (:operations resp) =>  [["Rakentaminen ja purkaminen"
                               [["Uuden rakennuksen rakentaminen"
                                 [["pientalo" "pientalo"]]]
                                ["Rakennelman rakentaminen"
                                 [["Aita" "aita"]]]]]
                              ["yleisten-alueiden-luvat"
                               [["sijoituslupa"
                                 [["pysyvien-maanalaisten-rakenteiden-sijoittaminen"
                                   [["vesi-ja-viemarijohtojen-sijoittaminen" "ya-sijoituslupa-vesi-ja-viemarijohtojen-sijoittaminen"]]]]]
                                ["katulupa" [["kaivaminen-yleisilla-alueilla"
                                              [["vesi-ja-viemarityot" "ya-katulupa-vesi-ja-viemarityot"]]]]]
                                ["kayttolupa" [["mainokset" "ya-kayttolupa-mainostus-ja-viitoitus"]
                                               ["terassit" "ya-kayttolupa-terassit"]]]]]]))

  (fact* "Query selected operations"
    (let [id   (create-app-id pena :operation "kerrostalo-rivitalo" :propertyId sipoo-property-id)
          resp (query pena "addable-operations" :id id) => ok?]
      (:operations resp) => [["Rakentaminen ja purkaminen" [["Uuden rakennuksen rakentaminen" [["pientalo" "pientalo"]]] ["Rakennelman rakentaminen" [["Aita" "aita"]]]]]]))

  (fact* "The query 'organization-by-user' correctly returns the selected operations of the organization"
    (let [resp (query pena "organization-by-user") => unauthorized?
          resp (query sipoo "organization-by-user") => ok?]
      (get-in resp [:organization :selectedOperations]) => {:R ["aita" "pientalo"]}))

  (fact "An application query correctly returns the 'required fields filling obligatory' and 'kopiolaitos-email' info in the organization meta data"
    (let [app-id (create-app-id pena :operation "kerrostalo-rivitalo" :propertyId sipoo-property-id)
          app    (query-application pena app-id)
          org    (query admin "organization-by-id" :organizationId  (:organization app))
          kopiolaitos-email "kopiolaitos@example.com"
          kopiolaitos-orderer-address "Testikatu 1"
          kopiolaitos-orderer-phone "123"
          kopiolaitos-orderer-email "orderer@example.com"]

      (fact "the 'app-required-fields-filling-obligatory' and 'kopiolaitos-email' flags have not yet been set for organization in db"
        (:app-required-fields-filling-obligatory org) => falsey
        (-> app :organizationMeta :requiredFieldsFillingObligatory) => falsey)

      (command sipoo "set-organization-app-required-fields-filling-obligatory" :enabled false) => ok?

      (let [app    (query-application pena app-id)
            org    (query admin "organization-by-id" :organizationId  (:organization app))
            organizationMeta (:organizationMeta app)]
        (fact "the 'app-required-fields-filling-obligatory' is set to False"
          (:app-required-fields-filling-obligatory org) => false
          (:requiredFieldsFillingObligatory organizationMeta) => false)
        (fact "the 'kopiolaitos-email' is set (from minimal)"
          (:kopiolaitos-email org) => "sipoo@example.com"
          (get-in organizationMeta [:kopiolaitos :kopiolaitosEmail]) => "sipoo@example.com")
        (fact "the 'kopiolaitos-orderer-address' is set (from minimal)"
          (:kopiolaitos-orderer-address org) => "Testikatu 2, 12345 Sipoo"
          (get-in organizationMeta [:kopiolaitos :kopiolaitosOrdererAddress]) => "Testikatu 2, 12345 Sipoo")
        (fact "the 'kopiolaitos-orderer-email' is set (from minimal)"
          (:kopiolaitos-orderer-email org) => "tilaaja@example.com"
          (get-in organizationMeta [:kopiolaitos :kopiolaitosOrdererEmail]) => "tilaaja@example.com")
        (fact "the 'kopiolaitos-orderer-phone' is set (from minimal)"
          (:kopiolaitos-orderer-phone org) => "0501231234"
          (get-in organizationMeta [:kopiolaitos :kopiolaitosOrdererPhone]) => "0501231234"))

      (command sipoo "set-organization-app-required-fields-filling-obligatory" :enabled true) => ok?
      (command sipoo "set-kopiolaitos-info"
        :kopiolaitosEmail kopiolaitos-email
        :kopiolaitosOrdererAddress kopiolaitos-orderer-address
        :kopiolaitosOrdererPhone kopiolaitos-orderer-phone
        :kopiolaitosOrdererEmail kopiolaitos-orderer-email) => ok?

      (let [app    (query-application pena app-id)
            org    (query admin "organization-by-id" :organizationId  (:organization app))
            organizationMeta (:organizationMeta app)]
        (fact "the 'app-required-fields-filling-obligatory' flag is set to true value"
          (:app-required-fields-filling-obligatory org) => true
          (:requiredFieldsFillingObligatory organizationMeta) => true)
        (fact "the 'kopiolaitos-email' flag is set to given email address"
          (:kopiolaitos-email org) => kopiolaitos-email
          (get-in organizationMeta [:kopiolaitos :kopiolaitosEmail]) => kopiolaitos-email)
        (fact "the 'kopiolaitos-orderer-address' flag is set to given address"
          (:kopiolaitos-orderer-address org) => kopiolaitos-orderer-address
          (get-in organizationMeta [:kopiolaitos :kopiolaitosOrdererAddress]) => kopiolaitos-orderer-address)
        (fact "the 'kopiolaitos-orderer-phone' flag is set to given phone address"
          (:kopiolaitos-orderer-phone org) => kopiolaitos-orderer-phone
          (get-in organizationMeta [:kopiolaitos :kopiolaitosOrdererPhone]) => kopiolaitos-orderer-phone)
        (fact "the 'kopiolaitos-orderer-email' flag is set to given email address"
          (:kopiolaitos-orderer-email org) => kopiolaitos-orderer-email
          (get-in organizationMeta [:kopiolaitos :kopiolaitosOrdererEmail]) => kopiolaitos-orderer-email)))))

(facts "municipality-active"
  (fact "only info requests enabled"
    (let [m (query pena :municipality-active :municipality "997")]
      (:applications m) => empty?
      (:infoRequests m) => ["R"]
      (:opening m) => empty?))
  (fact "only applications enabled"
    (let [m (query pena :municipality-active :municipality "998")]
      (:applications m) => ["R"]
      (:infoRequests m) => empty?
      (:opening m) => empty?))
  (fact "nothing enabled, but coming"
    (command admin :update-organization
      :permitType "R"
      :municipality "999"
      :inforequestEnabled false
      :applicationEnabled false
      :openInforequestEnabled false
      :openInforequestEmail "someone@localhost"

      :opening 123)
    (let [m (query pena :municipality-active :municipality "999")]
      (:applications m) => empty?
      (:infoRequests m) => empty?
      (:opening m) => [{:permitType "R", :opening 123}])))


(facts "organization-operations-attachments"
  (fact "Invalid operation is rejected"
    (command sipoo :organization-operations-attachments :operation "foo" :attachments []) => (partial expected-failure? "error.unknown-operation"))

  (fact "Empty attachments array is ok"
    (command sipoo :organization-operations-attachments :operation "pientalo" :attachments []) => ok?)

  (fact "scalar value as attachments parameter is not ok"
    (command sipoo :organization-operations-attachments :operation "pientalo" :attachments "") => (partial expected-failure? "error.non-vector-parameters"))

  (fact "Invalid attachment is rejected"
    (command sipoo :organization-operations-attachments :operation "pientalo" :attachments [["foo" "muu"]]) => (partial expected-failure? "error.unknown-attachment-type"))

  (fact "Valid attachment is ok"
    (command sipoo :organization-operations-attachments :operation "pientalo" :attachments [["muut" "muu"]]) => ok?))

(facts "permanent-archive-can-be-set"
  (let [organization  (first (:organizations (query admin :organizations)))
        id (:id organization)]

    (fact "Permanent archive can be enabled"
      (command admin "set-organization-permanent-archive-enabled" :enabled true :organizationId id) => ok?
      (let [updated-org (query admin "organization-by-id" :organizationId id)]
        (:permanent-archive-enabled updated-org) => true))

    (fact "Permanent archive can be disabled"
      (command admin "set-organization-permanent-archive-enabled" :enabled false :organizationId id) => ok?
      (let [updated-org (query admin "organization-by-id" :organizationId id)]
        (:permanent-archive-enabled updated-org) => false))))

(facts "Organization names"
  (let [{names :names :as resp} (query pena :get-organization-names)]
    resp => ok?
    (count names) => pos?
    (-> names :753-R :fi) => "Sipoon rakennusvalvonta"))

(facts "Organization tags"
  (fact "only auth admin can add new tags"
    (command sipoo :save-organization-tags :tags []) => ok?
    (command sipoo :save-organization-tags :tags [{:id nil :label "makeja"} {:id nil :label "nigireja"}]) => ok?
    (command sonja :save-organization-tags :tags [{:id nil :label "illegal"}] =not=> ok?)
    (command pena :save-organization-tags :tags [{:id nil :label "makeja"}] =not=> ok?))
  (fact "tags get ids when saved"
    (:tags (query sipoo :get-organization-tags)) => (just {:753-R (just {:name (just {:fi string? :sv string?})
                                                                         :tags (just [(just {:id string? :label "makeja"})
                                                                                      (just {:id string? :label "nigireja"})])})}))

  (fact "only authority can fetch available tags"
    (query pena :get-organization-tags) =not=> ok?
    (map :label (:tags (:753-R (:tags (query sonja :get-organization-tags))))) => ["makeja" "nigireja"])

  (fact "invalid data is rejected"
    (command sipoo :save-organization-tags :tagz []) => fail?
    (command sipoo :save-organization-tags :tags {}) => fail?
    (command sipoo :save-organization-tags :tags nil) => fail?
    (command sipoo :save-organization-tags :tags "tag") => fail?
    (command sipoo :save-organization-tags :tags [{}]) => fail?
    (command sipoo :save-organization-tags :tags [{:id "id"}]) => fail?
    (command sipoo :save-organization-tags :tags [{:label false}]) => fail?
    (command sipoo :save-organization-tags :tags [{:id nil :label {:injection true}}]) => fail?)

  (fact "Check tag deletion query"
    (let [id (create-app-id sonja)
          tag-id (-> (query sonja :get-organization-tags)
                   :tags :753-R :tags first :id)]
      (command sonja :add-application-tags :id id :tags [tag-id]) => ok?

      (fact "when tag is used, application id is returned"
        (let [res (query sipoo :remove-tag-ok :tagId tag-id)]
          res =not=> ok?
          (-> res :applications first :id) => id))

      (fact "when tag is not used in applications, ok is returned"
        (command sonja :add-application-tags :id id :tags []) => ok?
        (query sipoo :remove-tag-ok :tagId tag-id) => ok?))))

(defn- upload-area [apikey & [filename]]
  (let [filename    (or filename "dev-resources/sipoon_alueet.zip")
        uploadfile  (io/file filename)
        uri         (str (server-address) "/api/raw/organization-area")]
    (http-post uri
      {:headers {"authorization" (str "apikey=" apikey)}
       :multipart [{:name "files[]" :content uploadfile}]
       :throw-exceptions false})))

(facts "Organization areas zip file upload"
  (fact "only authorityAdmin can upload"
    (:body (upload-area pena)) => "unauthorized"
    (:body (upload-area sonja)) => "unauthorized")

  (fact "text file is not ok (zip required)"
    (->
      (upload-area sipoo "dev-resources/test-attachment.txt")
      :body) => "error.illegal-shapefile")

  (let [resp (upload-area sipoo)
        body (:body (decode-response resp))]

    (fact "zip file with correct shape file can be uploaded by auth admin"
      resp => http200?
      body => ok?)))

(def local-db-name (str "test_organization_itest_" (sade/now)))

(mongo/connect!)
(mongo/with-db local-db-name (fixture/apply-fixture "minimal"))

(facts "Municipality (753) maps"
       (mongo/with-db local-db-name
         (let [url "http://mapserver"]
           (local-org-api/update-organization
            "753-R"
            {$set {:map-layers {:server {:url url}
                                :layers [{:name "asemakaava"
                                          :id "asemakaava-id"
                                          :base true}
                                         {:name "kantakartta"
                                          :id "kantakartta-id"
                                          :base true}
                                         {:name "foo"
                                          :id "foo-id"
                                          :base false}
                                         {:name "bar"
                                          :id "bar-id"
                                          :base false}]}}})
           (local-org-api/update-organization
            "753-YA"
            {$set {:map-layers {:server {:url url}
                                :layers [{:name "asemakaava"
                                          :id "other-asemakaava-id"
                                          :base true}
                                         {:name "kantakartta"
                                          :id "other-kantakartta-id"
                                          :base true}
                                         {:name "Other foo"
                                          :id "foo-id"
                                          :base false}
                                         {:name "kantakartta" ;; not base layer
                                          :id "hii-id"
                                          :base false}]}}})
           (let [layers (proxy/municipality-layers "753")
                 objects (proxy/municipality-layer-objects "753")]
             (fact "Five layers"
                   (count layers) => 5)
             (fact "Only one asemakaava"
                   (->> layers (filter #(= (:name %) "asemakaava")) count) => 1)
             (fact "Only one with foo-id"
                   (->> layers (filter #(= (:id %) "foo-id")) count) => 1)
             (facts "Two layers named kantakartta"
                    (let [kantas (filter #(= "kantakartta" (:name %)) layers)
                          [k1 k2] kantas]
                      (fact "Two layers" (count kantas) => 2)
                      (fact "One of which is not a base layer"
                            (not= (:base k1) (:base k2)) => true)))
             (facts "Layer objects"
                    (fact "Every layer object has an unique id"
                          (->> objects (map :id) set count) => 5)
                    (fact "Ids are correctly formatted"
                          (every? #(let [{:keys [id base]} %]
                                     (or (and base (= (name {"asemakaava"  101
                                                             "kantakartta" 102})
                                                      id))
                                         (and (not base) (not (number? id))))) objects))
                    (fact "Bar layer is correct "
                          (let [subtitles {:fi "" :sv "" :en ""}
                                bar-index (->> layers (map-indexed #(assoc %2 :index %1)) (some #(if (= (:id %) "bar-id") (:index %))))]

                            (nth objects bar-index) => {:name        {:fi "bar" :sv "bar" :en "bar"}
                                                        :subtitle    subtitles
                                                        :id          (str "Lupapiste-" bar-index)
                                                        :baseLayerId (str "Lupapiste-" bar-index)
                                                        :isBaseLayer false
                                                        :minScale    400000
                                                        :wmsName     "Lupapiste-753-R:bar-id"
                                                        :wmsUrl      "/proxy/kuntawms"})))
             (facts "New map data with different server to 753-YA"
                    (local-org-api/update-organization
                     "753-YA"
                     {$set {:map-layers {:server {:url "http://different"}
                                         :layers [{:name "asemakaava"
                                                   :id   "other-asemakaava-id"
                                                   :base true}
                                                  {:name "kantakartta"
                                                   :id   "other-kantakartta-id"
                                                   :base true}
                                                  {:name "Other foo"
                                                   :id   "foo-id"
                                                   :base false}]}}})
                    (let [layers (proxy/municipality-layers "753")]
                      (fact "Two layers with same ids are allowed if the servers differ"
                            (->> layers (filter #(= (:id %) "foo-id")) count) => 2)
                      (fact "Still only two base layers"
                            (->> layers (filter :base) count) => 2)))
             (facts "Storing passwords"
                    (local-org-api/update-organization-map-server "753-YA"
                                                                  "http://doesnotmatter"
                                                                  "username"
                                                                  "plaintext")
                    (let [org (local-org-api/get-organization "753-YA")
                          {:keys [username password crypto-iv]} (-> org :map-layers :server)]
                      (fact "Password is encrypted"
                            (not= password "plaintext") => true)
                      (fact "Password decryption"
                            (local-org-api/decode-credentials password crypto-iv) => "plaintext")
                      (fact "No extra credentials are stored (nil)"
                            (local-org-api/update-organization-map-server "753-YA"
                                                                          "http://stilldoesnotmatter"
                                                                          nil
                                                                          nil)
                            (-> "753-YA" local-org-api/get-organization :map-layers :server
                                (select-keys [:password :crypto-iv])) => {:password nil})
                      (fact "No extra credentials are stored (\"\")"
                            (local-org-api/update-organization-map-server "753-YA"
                                                                          "http://stilldoesnotmatter"
                                                                          ""
                                                                          "")
                            (-> "753-YA" local-org-api/get-organization :map-layers :server
                                (select-keys [:password :crypto-iv])) => {:password ""})))))))

(facts "set-organization-neighbor-order-email"
       (fact "Emails are not set in fixture"
             (let [resp (query sipoo :organization-by-user)]
               resp => ok?
               (:organization resp) => seq
               (get-in resp [:organization :notifications :neighbor-order-emails]) => empty?))

       (fact "One email is set"
             (command sipoo :set-organization-neighbor-order-email :emails "kirjaamo@sipoo.example.com") => ok?
             (-> (query sipoo :organization-by-user)
                 (get-in [:organization :notifications :neighbor-order-emails])) => ["kirjaamo@sipoo.example.com"])

       (fact "Three emails are set"
             (command sipoo :set-organization-neighbor-order-email :emails "kirjaamo@sipoo.example.com,  sijainen1@sipoo.example.com;sijainen2@sipoo.example.com") => ok?
             (-> (query sipoo :organization-by-user)
                 (get-in [:organization :notifications :neighbor-order-emails])) => ["kirjaamo@sipoo.example.com", "sijainen1@sipoo.example.com", "sijainen2@sipoo.example.com"])

       (fact "Reset email addresses"
             (command sipoo :set-organization-neighbor-order-email :emails "") => ok?
             (-> (query sipoo :organization-by-user)
                 (get-in [:organization :notifications :neighbor-order-emails])) => empty?))
