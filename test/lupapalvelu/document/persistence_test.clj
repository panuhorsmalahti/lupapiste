(ns lupapalvelu.document.persistence-test
  (:require [lupapalvelu.document.persistence :refer :all]
            [midje.sweet :refer :all]
            [midje.util :refer [testable-privates]]
            [lupapalvelu.document.model :as model]
            [lupapalvelu.document.schemas :as schemas]))

(testable-privates lupapalvelu.document.persistence empty-op-attachments-ids)

(def some-time 123456789)

(model/with-timestamp some-time
  (facts "Facts about ->update"
    (fact (->mongo-updates "foo" (->model-updates []) nil) => {})
    (fact (->mongo-updates "foo" (->model-updates [["a" "A"]]) nil) => {"foo.a.value" "A"
                                                                    "foo.a.modified" some-time})
    (fact (->mongo-updates "foo" (->model-updates [["a" "A"]]) {:source "krysp"}) => {"foo.a.value" "A"
                                                                                      "foo.a.sourceValue" "A"
                                                                                      "foo.a.modified" some-time
                                                                                      "foo.a.source" "krysp"})
    (fact (->mongo-updates "foo" (->model-updates [["a" "A"]]) {:source "krysp" :modifier "Me"}) => {"foo.a.value" "A"
                                                                                                     "foo.a.sourceValue" "A"
                                                                                                     "foo.a.modified" some-time
                                                                                                     "foo.a.source" "krysp"
                                                                                                     "foo.a.modifier" "Me"})
    (fact (->mongo-updates "foo" (->model-updates [["a" "A"] ["b.b" "B"]]) nil) => {"foo.a.value"      "A"
                                                                                    "foo.a.modified"   some-time
                                                                                    "foo.b.b.value"    "B"
                                                                                    "foo.b.b.modified" some-time}))

  (fact "create model updates from ui-protocol"
    (->model-updates [["mitat.kerrosluku" "44"]]) => [[[:mitat :kerrosluku] "44"]])

  (fact "create mongo updates from model-updates"
    (->mongo-updates "documents.$.data"
      [[[:mitat :kerrosluku] ..kerrosluku..]
       [[:henkilo :nimi] ..nimi..]] nil)
    => {"documents.$.data.mitat.kerrosluku.value"    ..kerrosluku..
        "documents.$.data.mitat.kerrosluku.modified" some-time
        "documents.$.data.henkilo.nimi.value"        ..nimi..
        "documents.$.data.henkilo.nimi.modified"     some-time}))


(def attachments [{:id "1" :op {:id "123"}}
                  {:id "2" :op nil}
                  {:id "3" :op {:id "123"} :versions [{:v 1}]}
                  {:id "4" :op {:id "123"} :versions [{:v 1}]}
                  {:id "5" :op {:id "321"} :versions [{:v 1}]}
                  {:id "6" :op {:id "111"}}
                  {:id "7" :op {:id "112"} :versions []}
                  {:id "9" :op {:id "112"} :versions nil}
                  {:id "8" :op {:id "123"}}])

(fact "removable attachments with operation"
  (empty-op-attachments-ids nil nil) => nil
  (empty-op-attachments-ids [] nil) => nil
  (empty-op-attachments-ids attachments nil) => nil
  (empty-op-attachments-ids attachments "") => nil
  (empty-op-attachments-ids attachments "012") => nil
  (empty-op-attachments-ids attachments "123") => ["1" "8"]
  (empty-op-attachments-ids attachments "321") => nil
  (empty-op-attachments-ids attachments "111") => ["6"]
  (empty-op-attachments-ids attachments "112") => ["7" "9"])

(fact "removing-updates-by-path - two paths"
  (removing-updates-by-path :someCollection "123" [[:path :to :removed :item] [:path "to" :another "removed" :item]])
  =>  {:mongo-query   {:someCollection {"$elemMatch" {:id "123"}}}, 
       :mongo-updates {"$unset" {"someCollection.$.data.path.to.removed.item" "", 
                                 "someCollection.$.meta.path.to.removed.item" "", 
                                 "someCollection.$.data.path.to.another.removed.item" "", 
                                 "someCollection.$.meta.path.to.another.removed.item" ""}}})

(fact "new-doc - without updates"
  (-> (new-doc {} (schemas/get-schema 1 "rakennusjatesuunnitelma") "22")
      (get :data))
  => truthy)

(fact "new-doc - with updates"
  (-> [[[:rakennusJaPurkujate :0 :suunniteltuMaara] "123"] 
       [[:rakennusJaPurkujate :1 :yksikko] "tonni"]]
      ((partial new-doc {} (schemas/get-schema 1 "rakennusjatesuunnitelma") "22"))
      (get-in [:data :rakennusJaPurkujate :1 :yksikko :value])) 
  => "tonni")

(fact "new-doc - with failing updates"
  (-> [[[:rakennusJaPurkujate :0 :illegalKey] "123"] ]
      ((partial new-doc {} (schemas/get-schema 1 "rakennusjatesuunnitelma") "22"))
      (get-in [:data :rakennusJaPurkujate :1])) 
  => (throws Exception))

(fact "removing-updates-by-path - no paths"
  (removing-updates-by-path :someCollection "123" [])
  => {})

(fact "validate-readonly-updates! - valid update"
  (validate-readonly-updates! {:schema-info {:name "rakennusjatesuunnitelma"}}
                              [[:rakennusJaPurkujate :0 :suunniteltuMaara]])
  => nil)

(fact "validate-readonly-updates! - contains readonly"
  (validate-readonly-updates! {:schema-info {:name "rakennusjateselvitys"}}
                              [[:rakennusJaPurkujate :suunniteltuJate :0 :suunniteltuMaara]])
  => (throws Exception))

(fact "validate-readonly-updates! - another valid update"
  (validate-readonly-updates! {:schema-info {:name "rakennusjateselvitys"}}
                              [[:rakennusJaPurkujate :suunniteltuJate :0 :arvioituMaara]])
  => nil)

(fact "validate-readonly-removes! - valid"
  (validate-readonly-updates! {:schema-info {:name "rakennusjatesuunnitelma"}}
                              [[:rakennusJaPurkujate :0]])
  => nil)

(fact "validate-readonly-removes! - contains readonly"
  (validate-readonly-removes! {:schema-info {:name "rakennusjateselvitys"}}
                              [[:rakennusJaPurkujate :suunniteltuJate :0]])
  => (throws Exception))
