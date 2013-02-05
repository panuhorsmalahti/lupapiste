(ns lupapalvelu.document.schemas)

(defn group [name boby] {:name name :type :group :body body})

(defn to-map-by-name
  "Take list of schema maps, return a map of schemas keyed by :name under :info"
  [docs]
  (reduce (fn [docs doc] (assoc docs (get-in doc [:info :name]) doc)) {} docs))

(def simple-osoite {:name "osoite"
                    :type :group
                    :body [{:name "katu" :type :string}
                           {:name "postinumero" :type :string :size "s"}
                           {:name "postitoimipaikannimi" :type :string :size "m"}]})

(def full-osoite [{:name "osoite"
                   :type :group
                   :body [{:name "kunta" :type :string}
                          {:name "lahiosoite" :type :string}
                          {:name "osoitenumero" :type :string}
                          {:name "osoitenumero2" :type :string}
                          {:name "jakokirjain" :type :string :size "s"}
                          {:name "jakokirjain2" :type :string :size "s"}
                          {:name "porras" :type :string :size "s"}
                          {:name "huoneisto" :type :string :size "s"}
                          {:name "postinumero" :type :string :size "s"}
                          {:name "postitoimipaikannimi" :type :string :size "m"}
                          {:name "pistesijanti" :type :string}]}])

(def yhteystiedot-body [{:name "puhelin" :type :string :subtype :tel}
                        {:name "email" :type :string :subtype :email}
                        {:name "fax" :type :string :subtype :tel}])

(def henkilotiedot-minimal-body [{:name "etunimi" :type :string}
                                 {:name "sukunimi" :type :string}])

(def henkilotiedot-body
  (conj henkilotiedot-minimal-body {:name "hetu" :type :string}))

(def henkilo-body [{:name "henkilotiedot" :type :group :body henkilotiedot-body}
                   simple-osoite
                   {:name "yhteystiedot" :type :group :body yhteystiedot-body}])

(def yritys-minimal-body [{:name "yritysnimi" :type :string}
                   {:name "liikeJaYhteisoTunnus" :type :string}])

(def yritys-body (conj yritys-minimal-body
                       simple-osoite
                       {:name "yhteyshenkilo" :type :group
                        :body [{:name "henkilotiedot" :type :group :body henkilotiedot-minimal-body}
                               {:name "yhteystiedot" :type :group :body yhteystiedot-body}]}))

(def party-body [{:name "_selected" :type :radioGroup :body [{:name "henkilo"} {:name "yritys"}]}
                 {:name "henkilo" :type :group :body henkilo-body}
                 {:name "yritys" :type :group :body yritys-body}])

(def patevyys [{:name "koulutus" :type :string}
               {:name "patevyysluokka" :type :select
                :body [{:name "AA"}
                       {:name "A"}
                       {:name "B"}
                       {:name "C"}
                       {:name "ei tiedossa"}]}])

(def designer-basic [{:name "henkilotiedot" :type :group :body henkilotiedot-minimal-body}
                     {:name "yritys" :type :group :body yritys-minimal-body}
                     simple-osoite
                     {:name "yhteystiedot" :type :group :body yhteystiedot-body}])

(def paasuunnittelija-body (conj
                         designer-basic
                         {:name "patevyys" :type :group :body patevyys}))

(def suunnittelija-body (conj
                         designer-basic
                         {:name "patevyys" :type :group
                          :body
                          (cons {:name "kuntaRoolikoodi" :type :select
                                  :body [{:name "GEO-suunnittelija"}
                                         {:name "LVI-suunnittelija"}
                                         {:name "IV-suunnittelija"}
                                         {:name "KVV-suunnittelija"}
                                         {:name "RAK-rakennesuunnittelija"}
                                         {:name "ARK-rakennussuunnittelija"}
                                         {:name "ei tiedossa"}
                                         {:name "Vaikeiden t\u00F6iden suunnittelija"}]
                                  } patevyys)
                            })) ; TODO miten liitteet hanskataan

(def huoneisto-body [{:name "huoneistoTunnus" :type :group
                      :body [{:name "porras" :type :string :subtype :letter :max-len 1 :size "s"}
                             {:name "huoneistonumero" :type :string :subtype :number :min-len 1 :max-len 3 :size "s"}
                             {:name "jakokirjain" :type :string :subtype :letter :max-len 1 :size "s"}]}
                     {:name "huoneistonTyyppi"
                      :type :group
                      :body [{:name "huoneistoTyyppi" :type :select
                              :body [{:name "asuinhuoneisto"}
                                     {:name "toimitila"}
                                     {:name "ei tiedossa"}]}
                             {:name "huoneistoala" :type :string :unit "m2" :subtype :number :size "s"}
                             {:name "huoneluku" :type :string :size "m"}]}
                     {:name "keittionTyyppi" :type :select
                      :body [{:name "keittio"}
                             {:name "keittokomero"}
                             {:name "keittotila"}
                             {:name "tupakeittio"}
                             {:name "ei tiedossa"}]}
                     {:name "varusteet" :type :choice
                      :body [{:name "WCKytkin" :type :checkbox}
                             {:name "ammeTaiSuihkuKytkin" :type :checkbox}
                             {:name "saunaKytkin" :type :checkbox}
                             {:name "parvekeTaiTerassiKytkin" :type :checkbox}
                             {:name "lamminvesiKytkin" :type :checkbox}]}])

(def rakennuksen-tiedot [
             {:name "kaytto"
              :type :group
              :body [{:name "rakentajaTyyppi" :type "select"
                      :body [{:name "liiketaloudellinen"}
                             {:name "muu"}
                             {:name "ei tiedossa"}]}
                     {:name "kayttotarkoitus" :type :select
                      :body [{:name "999 muualla luokittelemattomat rakennukset"}
                             {:name "941 talousrakennukset"}
                             {:name "931 saunarakennukset"}
                             {:name "899 muut maa-, mets\u00e4- ja kalatalouden rakennukset"}
                             {:name "893 turkistarhat"}
                             {:name "892 kasvihuoneet"}
                             {:name "891 viljankuivaamot ja viljan s\u00e4ilytysrakennukset"}
                             {:name "819 el\u00e4insuojat, ravihevostallit, maneesit yms"}
                             {:name "811 navetat, sikalat, kanalat yms"}
                             {:name "729 muut palo- ja pelastustoimen rakennukset"}
                             {:name "722 v\u00e4est\u00f6nsuojat"}
                             {:name "721 paloasemat"}
                             {:name "719 muut varastorakennukset"}
                             {:name "712 kauppavarastot"}
                             {:name "711 teollisuusvarastot"}
                             {:name "699 muut teollisuuden tuotantorakennukset"}
                             {:name "692 teollisuus- ja pienteollisuustalot"}
                             {:name "691 teollisuushallit"}
                             {:name "613 yhdyskuntatekniikan rakennukset"}
                             {:name "611 voimalaitosrakennukset"}
                             {:name "549 muualla luokittelemattomat opetusrakennukset"}
                             {:name "541 j\u00e4rjest\u00f6jen, liittojen, ty\u00f6nantajien yms opetusrakennukset"}
                             {:name "532 tutkimuslaitosrakennukset"}
                             {:name "531 korkeakoulurakennukset"}
                             {:name "521 ammatillisten oppilaitosten rakennukset"}
                             {:name "511 yleissivist\u00e4vien oppilaitosten rakennukset"}
                             {:name "369 muut kokoontumisrakennukset"}
                             {:name "359 muut urheilu- ja kuntoilurakennukset"}
                             {:name "354 monitoimihallit ja muut urheiluhallit"}
                             {:name "353 tennis-, squash- ja sulkapallohallit"}
                             {:name "352 uimahallit"}
                             {:name "351 j\u00e4\u00e4hallit"}
                             {:name "349 muut uskonnollisten yhteis\u00f6jen rakennukset"}
                             {:name "342 seurakuntatalot"}
                             {:name "341 kirkot, kappelit, luostarit ja rukoushuoneet"}
                             {:name "331 seura- ja kerhorakennukset yms"}
                             {:name "324 n\u00e4yttelyhallit"}
                             {:name "323 museot ja taidegalleriat"}
                             {:name "322 kirjastot ja arkistot"}
                             {:name "312 elokuvateatterit"}
                             {:name "311 teatterit, ooppera-, konsertti- ja kongressitalot"}
                             {:name "241 vankilat"}
                             {:name "239 muualla luokittelemattomat sosiaalitoimen rakennukset"}
                             {:name "231 lasten p\u00e4iv\u00e4kodit"}
                             {:name "229 muut huoltolaitosrakennukset"}
                             {:name "223 kehitysvammaisten hoitolaitokset"}
                             {:name "222 lasten- ja koulukodit"}
                             {:name "221 vanhainkodit"}
                             {:name "219 muut terveydenhuoltorakennukset"}
                             {:name "215 terveydenhuollon erityislaitokset"}
                             {:name "214 terveyskeskukset"}
                             {:name "213 muut sairaalat"}
                             {:name "211 keskussairaalat"}
                             {:name "169 muut liikenteen rakennukset"}
                             {:name "164 tietoliikenteen rakennukset"}
                             {:name "163 pys\u00e4k\u00f6intitalot"}
                             {:name "162 kulkuneuvojen suoja- ja huoltorakennukset"}
                             {:name "161 rautatie- ja linja-autoasemat, lento- ja satamaterminaalit"}
                             {:name "151 toimistorakennukset"}
                             {:name "141 ravintolat yms"}
                             {:name "139 muut asuntolarakennukset"}
                             {:name "131 asuntolat yms"}
                             {:name "129 muut majoitusliikerakennukset"}
                             {:name "124 vuokrattavat lomam\u00f6kit ja -osakkeet"}
                             {:name "123 loma-, lepo- ja virkistyskodit"}
                             {:name "121 hotellit yms"}
                             {:name "119 muut myym\u00e4l\u00e4rakennukset"}
                             {:name "112 liike- ja tavaratalot, kauppakeskukset"}
                             {:name "111 myym\u00e4l\u00e4hallit"}
                             {:name "041 vapaa-ajan asuinrakennukset"}
                             {:name "039 muut asuinkerrostalot"}
                             {:name "032 luhtitalot"}
                             {:name "022 ketjutalot"}
                             {:name "021 rivitalot"}
                             {:name "013 muut erilliset talot"}
                             {:name "012 kahden asunnon talot"}
                             {:name "011 yhden asunnon talot"}
                             {:name "ei tiedossa"}]}]}
             {:name "mitat"
              :type :group
              :body [{:name "tilavuus" :type :string :size "s" :unit "m3" :subtype :number}
                     {:name "kokonaisala" :type :string :size "s" :unit "m2" :subtype :number}
                     {:name "kellarinpinta-ala" :type :string :size "s" :unit "m2" :subtype :number}
                     {:name "kerrosluku" :type :string :size "s"}
                     {:name "kerrosala" :type :string :size "s" :unit "m2" :subtype :number}]}
             {:name "rakenne"
              :type :group
              :body [{:name "rakentamistapa" :type :select
                      :body [{:name "elementti"}
                             {:name "paikalla"}
                             {:name "ei tiedossa"}]}
                     {:name "kantavaRakennusaine" :type :select
                      :body [{:name "betoni"}
                             {:name "tiili"}
                             {:name "teras"}
                             {:name "puu"}
                             {:name "muurakennusaine" :type :string :size "s"}
                             {:name "ei tiedossa"}]}
                     {:name "julkisivu" :type :select
                      :body [{:name "betoni"}
                             {:name "tiili"}
                             {:name "metallilevy"}
                             {:name "kivi"}
                             {:name "puu"}
                             {:name "lasi"}
                             {:name "muumateriaali" :type :string :size "s"}
                             {:name "ei tiedossa"}]}]}
             {:name "lammitys"
              :type :group
              :body [{:name "lammitystapa" :type :select
                      :body [{:name "vesikeskus"}
                             {:name "ilmakeskus"}
                             {:name "suorasahko"}
                             {:name "uuni"}
                             {:name "eiLammitysta"}
                             {:name "ei tiedossa"}]}
                     {:name "lammonlahde" :type :select
                      :body [{:name "kauko tai aluel\u00e4mp\u00f6"}
                             {:name "kevyt poltto\u00f6ljy"}
                             {:name "raskas poltto\u00f6ljy"}
                             {:name "s\u00e4hk\u00f6"}
                             {:name "kaasu"}
                             {:name "kiviihiili koksi tms"}
                             {:name "turve"}
                             {:name "maal\u00e4mp\u00f6"}
                             {:name "puu"}
                             {:name "muu" :type :string :size "s"} ;TODO tukii tekstille
                             {:name "ei tiedossa"}]}]}
             {:name "verkostoliittymat" :type :choice
              :body [{:name "viemariKytkin" :type :checkbox}
                     {:name "vesijohtoKytkin" :type :checkbox}
                     {:name "sahkoKytkin" :type :checkbox}
                     {:name "maakaasuKytkin" :type :checkbox}
                     {:name "kaapeliKytkin" :type :checkbox}]}
             {:name "varusteet" :type :choice
              :body [{:name "sahkoKytkin" :type :checkbox}
                     {:name "kaasuKytkin" :type :checkbox}
                     {:name "viemariKytkin" :type :checkbox}
                     {:name "vesijohtoKytkin" :type :checkbox}
                     {:name "hissiKytkin" :type :checkbox}
                     {:name "koneellinenilmastointiKytkin" :type :checkbox}
                     {:name "lamminvesiKytkin" :type :checkbox}
                     {:name "aurinkopaneeliKytkin" :type :checkbox}
                     {:name "saunoja" :type :string :subtype :number}
                     {:name "vaestonsuoja" :type :string :subtype :number}]}
             {:name "luokitus"
              :type :group
              :body [{:name "energialuokka" :type :string :size "s"}
                     {:name "paloluokka" :type :string :size "s"}]}
             {:name "huoneistot"
              :type :group
              :repeating true
              :body huoneisto-body}])



(def rakennuksen-omistajat [{:name "rakennuksenOmistajat"
                             :type :group :repeating true
                             :body party-body}])

(def muutostyonlaji [{:name :muutostyolaji :type :select
                      :body
                      [{:name "perustusten ja kantavien rakenteiden muutos- ja korjausty\u00f6t"}
                       {:name "rakennukse p\u00e4\u00e4asiallinen k\u00e4ytt\u00f6tarkoitusmuutos"}
                       {:name "muut muutosty\u00f6t"}]}];Kirjotus virhe kryspin 2.02 versiossa. korjaus arvattu tarkista m
  )

(def rakennuksen-valitsin
  [{:name :rakennusnro :type :buildingSelector}])

(def olemassaoleva-rakennus (concat rakennuksen-valitsin rakennuksen-omistajat full-osoite rakennuksen-tiedot))

(def rakennuksen-muuttaminen (concat muutostyonlaji olemassaoleva-rakennus))

(def purku (concat [{:name "poistumanSyy" :type :select
                     :body [{:name "purettu uudisrakentamisen vuoksi"}
                            {:name "purettu muusta syyst\u00e4"}
                            {:name "tuhoutunut"}
                            {:name "r\u00e4nsitymisen vuoksi hyl\u00e4tty"}
                            {:name "poistaminen"}]}
                    {:name "poistumanAjankohta" :type :string}]
                   olemassaoleva-rakennus))

(def schemas
  (to-map-by-name
    [{:info {:name "uusiRakennus"}
      :body rakennuksen-tiedot}

     {:info {:name "hankkeen-kuvaus"}
      :body [{:name "kuvaus" :type :text}
             {:name "poikkeamat" :type :text}]}


     {:info {:name "rakennuksen-muuttaminen"}
      :body rakennuksen-muuttaminen}

     {:info {:name "purku"}
      :body purku}

     {:info {:name "hakija" :repeating true}
      :body party-body}

     {:info {:name "paasuunnittelija"}
      :body paasuunnittelija-body}

     {:info {:name "suunnittelija" :repeating true}
      :body suunnittelija-body}

     {:info {:name "maksaja" :repeating true}
      :body party-body}

     {:info {:name "rakennuspaikka"} ; TODO sijainti(kios?/ jo kartalta osoitettu)
      :body [{:name "kiinteisto"
              :type :group
              :body [{:name "maaraalaTunnus" :type :string}
                     {:name "kokotilaKytkin" :type :checkbox}
                     {:name "kylaNimi" :type :string}
                     {:name "tilanNimi" :type :string}]}
             {:name "hallintaperuste" :type :select
              :body [{:name "oma"}
                     {:name "vuokra"}
                     {:name "ei tiedossa"}]}
             {:name "kaavanaste" :type "select"
              :body [{:name "asema"}
                     {:name "ranta"}
                     {:name "rakennus"}
                     {:name "yleis"}
                     {:name "eiKaavaa"}
                     {:name "ei tiedossa"}]}]}

     ;; not used...
     {:info {:name "osoite"}
      :body full-osoite}

     {:info {:name "lisatiedot"}
      :body [{:name "suoramarkkinointikielto" :type :checkbox}]}

     ; Rest are templates for future. Just guessing...

     {:info {:name "asuinrakennus"}
      :body [{:name "foo" :type :string}]}
     {:info {:name "vapaa-ajan-asuinrakennus"}
      :body [{:name "foo" :type :string}]}
     {:info {:name "varasto-tms"}
      :body [{:name "foo" :type :string}]}
     {:info {:name "julkinen-rakennus"}
      :body [{:name "foo" :type :string}]}
     {:info {:name "muu-uusi-rakentaminen"}
      :body [{:name "foo" :type :string}]}
     {:info {:name "laajentaminen"}
      :body [{:name "foo" :type :string}]}
     {:info {:name "kayttotark-muutos"}
      :body [{:name "foo" :type :string}]}
     {:info {:name "julkisivu-muutos"}
      :body [{:name "foo" :type :string}]}
     {:info {:name "jakaminen-tai-yhdistaminen"}
      :body [{:name "foo" :type :string}]}
     {:info {:name "markatilan-laajentaminen"}
      :body [{:name "foo" :type :string}]}
     {:info {:name "takka-tai-hormi"}
      :body [{:name "foo" :type :string}]}
     {:info {:name "parveke-tai-terassi"}
      :body [{:name "foo" :type :string}]}
     {:info {:name "muu-laajentaminen"}
      :body [{:name "foo" :type :string}]}
     {:info {:name "auto-katos"}
      :body [{:name "foo" :type :string}]}
     {:info {:name "masto-tms"}
      :body [{:name "foo" :type :string}]}
     {:info {:name "mainoslaite"}
      :body [{:name "foo" :type :string}]}
     {:info {:name "aita"}
      :body [{:name "foo" :type :string}]}
     {:info {:name "maalampo"}
      :body [{:name "foo" :type :string}]}
     {:info {:name "jatevesi"}
      :body [{:name "foo" :type :string}]}
     {:info {:name "muu-rakentaminen"}
      :body [{:name "foo" :type :string}]}
     {:info {:name "purkaminen"}
      :body [{:name "foo" :type :string}]}
     {:info {:name "kaivuu"}
      :body [{:name "foo" :type :string}]}
     {:info {:name "puun-kaataminen"}
      :body [{:name "foo" :type :string}]}
     {:info {:name "muu-maisema-toimenpide"}
      :body [{:name "foo" :type :string}]}]))
