(ns lupapalvelu.fixture.minimal
  (:use lupapalvelu.fixture)
  (:require [lupapalvelu.mongo :as mongo]))

(def users
  [{:id "777777777777777777000099" ;; admin
    :email "admin@solita.fi"
    :enabled true
    :role :admin
    :personId "solita123"
    :firstName "Admin"
    :lastName "Admin"
    :phone "03030303"
    :username "admin"
    :private {:password "$2a$10$WHPur/hjvaOTlm41VFjtjuPI5hBoIMm8Y1p2vL4KqRi7QUvHMS1Ie"
              :salt "$2a$10$WHPur/hjvaOTlm41VFjtju"
              :apikey "5087ba34c2e667024fbd5992"}}
   {:id "777777777777777777000016" ;; Veikko Viranomainen - tamperelainen Lupa-arkkitehti
    :email "veikko.viranomainen@tampere.fi"
    :enabled true
    :role :authority
    :municipality "837"
    :personId "kunta 122"
    :firstName "Veikko"
    :lastName "Viranomainen"
    :phone "03121991"
    :username "veikko"
    :private {:password "$2a$10$s4OOPduvZeH5yQzsCFSKIuLF5AQqkSO5S1DJOgziMep.xJLYm3.xG"
              :salt "$2a$10$s4OOPduvZeH5yQzsCFSKIu"
              :apikey "5051ba0caa2480f374dcfeff"}}
   ;; Sonja Sibbo - Sipoon lupa-arkkitehti
   ;; sonja / sonja
   {:id "777777777777777777000023"
    :email "sonja.sibbo@sipoo.fi"
    :enabled true
    :role :authority
    :municipality "753"
    :personId "kunta123"
    :firstName "Sonja"
    :lastName "Sibbo"
    :phone "03121991"
    :username "sonja"
    :private {:password "$2a$10$s4OOPduvZeH5yQzsCFSKIuVKiwbKvNs90f80zc57FDiPnGjuMbuf2"
              :salt "$2a$10$s4OOPduvZeH5yQzsCFSKIu"
              :apikey "5056e6d3aa24a1c901e6b9dd"}}
   ;; Sonja Sibbo - Sipoon lupa-arkkitehti
   ;; ronja / sonja
   {:id "777777777777777777000024"
    :email "ronja.sibbo@sipoo.fi"
    :enabled true
    :role :authority
    :municipality "753"
    :personId "kunta123"
    :firstName "Ronja"
    :lastName "Sibbo"
    :phone "03121991"
    :username "ronja"
    :private {:password "$2a$10$s4OOPduvZeH5yQzsCFSKIuVKiwbKvNs90f80zc57FDiPnGjuMbuf2"
              :salt "$2a$10$s4OOPduvZeH5yQzsCFSKIu"
              :apikey "5056e6d3aa24a1c901e6b9dd"}}
   ;; sipoo / sipoo
   {:id "50ac77ecc2e6c2ea6e73f83e" ;; Simo Sippo
    :email "admin@sipoo.fi"
    :enabled true
    :role :authorityAdmin
    :municipality "753"
    :firstName "Simo"
    :lastName "Suurvisiiri"
    :username "sipoo"
    :private {:salt "$2a$10$VFcksPILCd9ykyl.1FIhwO",
              :password "$2a$10$VFcksPILCd9ykyl.1FIhwO/tEYby9SsqZL7GsIAdpJ1XGvAG2KskG"
              :apikey "50ac788ec2e6c2ea6e73f83f"}}
   {:id "505718b0aa24a1c901e6ba24" ;; Admin
    :enabled true
    :firstName "Judge"
    :lastName "Dread"
    :email "judge.dread@example.com"
    :role :admin
    :private {:apikey "505718b0aa24a1c901e6ba24"}}
   {:lastName "Nieminen" ;; Mikkos neighbour
    :firstName "Teppo"
    :enabled true
    :postalCode "33200"
    :username "teppo@example.com"
    :private {:salt "$2a$10$KKBZSYTFTEFlRrQPa.PYPe"
              :password "$2a$10$KKBZSYTFTEFlRrQPa.PYPe9wz4q1sRvjgEUCG7gt8YBXoYwCihIgG"}
    :phone "0505503171"
    :email "teppo@example.com"
    :personId "210281-0001"
    :role "applicant"
    :id "5073c0a1c2e6c470aef589a5"
    :street "Mutakatu 7"
    :zip "33560"
    :city "Tampere"}
   {:id "777777777777777777000010" ;; Mikko Intonen
    :username "mikko@example.com"
    :enabled true
    :role "applicant"
    :personId "210281-0002"
    :firstName "Mikko"
    :lastName "Intonen"
    :email "mikko@example.com"
    :street "Rambokuja 6"
    :zip "55550"
    :city "Sipoo"
    :phone "0505503171"
    :private {:password "$2a$10$zwb/nvYQu4b1oZGpxz8.QOqHEBx3vXw9brc3NqDexgMbDuU2pwL9q"
              :salt "$2a$10$zwb/nvYQu4b1oZGpxz8.QO"
              :apikey "502cb9e58426c613c8b85abc"}}
   {:id "777777777777777777000020" ;; pena
    :username "pena"
    :enabled true
    :role "applicant"
    :personId "010203-0405"
    :firstName "Pena"
    :lastName "Panaani"
    :email "pena"
    :street "Paapankuja 12"
    :zip "010203"
    :city "Piippola"
    :phone "0102030405"
    :private {:password "$2a$10$hLCt8BvzrJScTOGQcXJ34ea5ovSfS5b/4X0OAmPbfcs/x3hAqEDxy"
              :salt "$2a$10$hLCt8BvzrJScTOGQcXJ34e"
              :apikey "602cb9e58426c613c8b85abc"}}])

(def municipalities [{:id "753"
                      :municipalityCode "753"
                      :name {:fi "Sipoo" :sv "Sibbo"}
                      :links [{:name {:fi "Sipoo", :sv "Sibbo"}
                               :url "http://sipoo.fi"}
                              {:name {:fi "Rakennusvalvonta", :sv "Rakennusvalvonta"}
                               :url "http://sipoo.fi/fi/palvelut/asuminen_ja_rakentaminen/rakennusvalvonta"}]
                      ;;:legacy "http://212.213.116.162/geoserver/wfs"}
                      :legacy "http://localhost:8000/krysp/building.xml"}
                     {:id "837"
                      :name {:fi "Tampere" :sv "Tammerfors"}
                      :municipalityCode "837"
                      :links [{:name {:fi "Tampere" :sv "Tammerfors"}
                               :url "http://tampere.fi"}
                              {:name {:fi "Rakennusvalvonta", :sv "Rakennusvalvonta"}
                               :url "http://www.tampere.fi/asuminenjarakentaminen/rakennusvalvonta.html"}
                              {:name {:fi "Lomakkeet" :sv "Lomakkeet"}
                               :url "http://www.tampere.fi/asuminenjarakentaminen/rakennusvalvonta/lomakkeet.html"}]}
                     {:id "186"
                      :name {:fi "J\u00E4rvenp\u00E4\u00E4" :sv "Tr\u00E4skenda"}
                      :municipalityCode "186"
                      :links [{:name {:fi "J\u00E4rvenp\u00E4\u00E4" :sv "Tr\u00E4skenda"}
                               :url "http://www.jarvenpaa.fi"}
                              {:name {:fi "Rakennusvalvonta", :sv "Rakennusvalvonta"}
                               :url "http://www.jarvenpaa.fi/sivu/index.tmpl?sivu_id=182"}]}])

(deffixture "minimal" {}
  (mongo/clear!)
  (dorun (map (partial mongo/insert :users) users))
  (dorun (map (partial mongo/insert :municipalities) municipalities)))
