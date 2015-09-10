(ns lupapalvelu.xml.krysp.maankayton-muutos-mapping
  (:require [clojure.data.xml :as xml]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [lupapalvelu.document.maankayton-muutos-canonical :as maankayton-muutos-canonical]
            [lupapalvelu.permit :as permit]
            [lupapalvelu.xml.disk-writer :as writer]
            [lupapalvelu.xml.krysp.mapping-common :as mapping-common]
            [lupapalvelu.xml.emit :as emit]))



(defn- make-seq [a]
  (if (sequential? a)
    a
    [a]))

(defn taggy
  "Returns list [map ns] where the map contains :tag and :ns (if given).
  The tag name is defined by argument k. The format is
  :tagname/ns where the namespace part is optional.  Note: namespace
  is returned but not used on this element.  The namespace for this
  element is the ns argument or nothing."
  [k & [ns]]
  (let [[tag new-ns] (-> k str rest str/join (str/split #"/"))]
    [(merge (when ns {:ns ns})
            {:tag (keyword tag)}) (or new-ns ns)]))

(defmulti mapper
  "Recursively generates a 'traditional' mapping (with :tag, :ns
  and :child properties) from the shorthand form. As the shorthand
  uses lists, maps and keywords, each type is handled byt its
  corresponding method.
  Note: The root element must be defined separately. See the
  ->mapping function for details."
  (fn [& args]
    (let [arg (first args)]
      (if (map? arg)
        :map
        (if (keyword? arg)
          :keyword
          (if (sequential? arg)
            :sequential))))))

(defmethod mapper :map [m & [ns]]
  (let [k (-> m keys first)
        [tag ns] (taggy k ns)
        v (k m)]
    ;; Mapping sanity check
    (assert (= (count m) 1) k)
    (assoc tag :child (make-seq (mapper v ns)))))

(defmethod mapper :keyword [kw & [ns]]
  (first (taggy kw ns)))

(defmethod mapper :sequential [xs & [ns]]
  (map #(mapper % ns) xs))

(defn ->mapping [muutos]
  (let [osoite [{:osoitenimi :teksti} :postinumero :postitoimipaikannimi]]
    {:tag :Maankaytonmuutos :ns "mkmu"
     :attr (merge {:xsi:schemaLocation (mapping-common/schemalocation :MM "1.0.1")
                   :xmlns:mkmu "http://www.paikkatietopalvelu.fi/gml/maankaytonmuutos"}
                  mapping-common/common-namespaces)
     :child [(mapper {:maankayttomuutosTieto
                      {muutos
                       [{:toimituksenTiedottieto
                         {:ToimituksenTiedot/yht [:aineistonnimi :aineistotoimittaja :tila :toimitusPvm :kuntakoodi
                                                  :kielitieto]}}
                        {:hakemustieto
                         [{:Hakemus
                           [{:osapuolitieto
                             {:Osapuoli
                              [:roolikoodi :turvakieltokytkin :asioimiskieli
                               {:henkilotieto
                                {:Henkilo/yht [{:nimi [:etunimi :sukunimi]}
                                               {:osoite osoite}
                                               :sahkopostiosoite
                                               :faksinumero
                                               :puhelin
                                               :henkilotunnus]}}
                               {:yritystieto
                                {:Yritys/yht [:nimi :liikeJaYhteisotunnus
                                              {:postiosoitetieto {:postiosoite osoite}}
                                              :puhelin
                                              :sahkopostiosoite
                                              {:verkkolaskutustieto
                                               {:Verkkolaskutus [:ovtTunnus :verkkolaskuTunnus :valittajaTunnus]}}]}}
                               :vainsahkoinenAsiointiKytkin]}}
                            {:sijaintitieto {:Sijainti/yht [{:osoite [:yksilointitieto :alkuHetki {:osoitenimi :teksti}]}
                                                            {:piste/gml {:Point :pos}}]}}
                            :kohdekiinteisto
                            :maaraAla
                            {:tilatieto {:Tila [:pvm :kasittelija :hakemuksenTila]}}]}]}
                        :toimituksenTila
                        {:liitetieto {:Liite/yht [:kuvaus :linkkiliitteeseen :muokkausHetki :versionumero]}}
                        :uusiKytkin
                        :kuvaus]}})]}))


(defn save-application-as-krysp
  "Sends application to municipality backend. Returns a sequence of
  attachment file IDs that ware sent."
  [application lang submitted-application krysp-version output-dir begin-of-link]
  (let [canonical-without-attachments  (maankayton-muutos-canonical/maankayton-muutos-canonical application lang)
        attachments-canonical (mapping-common/get-attachments-as-canonical application begin-of-link)
        muutos (-> canonical-without-attachments :Maankaytonmuutos :maankayttomuutosTieto first key)
        canonical (assoc-in
                    canonical-without-attachments
                    [:Maankaytonmuutos :maankayttomuutosTieto muutos :liitetieto ]
                    attachments-canonical)
        mapping (->mapping muutos)
        xml (emit/element-to-xml canonical mapping nil)
        attachments-for-write (mapping-common/attachment-details-from-canonical attachments-canonical)]
    (writer/write-to-disk
      application
      attachments-for-write
      xml
      krysp-version
      output-dir
      submitted-application
      lang)))

(permit/register-function permit/MM :app-krysp-mapper save-application-as-krysp)
