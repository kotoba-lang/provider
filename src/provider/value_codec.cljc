(ns provider.value-codec
  "Bounded canonical value wire for provider ops-kit audit events.

  The old W4 guest packages emit EDN text. `legacy-edn->audit-bytes` is the
  bounded compatibility bridge: it parses that closed EDN value on the host,
  then immediately moves it onto `kotoba.value.v1` bytes. New provider paths
  should call `encode-audit-value` with domain values directly."
  (:require #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [kotoba.value.codec :as value]))

(def audit-format :provider.ops-audit/v1)
(def max-audit-value-bytes (* 1024 1024))

(defn- utf8-byte-count [s]
  #?(:clj (alength (.getBytes ^String s "UTF-8"))
     :cljs (.-length (.encode (js/TextEncoder.) s))))

(defn- envelope [kit direction v]
  {:format audit-format
   :kit kit
   :direction direction
   :value v})

(defn encode-audit-value
  "Encode one typed provider request/reply with the provider-owned bound."
  [kit direction v]
  (when-not (keyword? kit)
    (throw (ex-info "provider audit kit must be a keyword"
                    {:type :provider.value-codec/invalid-kit :kit kit})))
  (when-not (#{:request :reply} direction)
    (throw (ex-info "provider audit direction must be :request or :reply"
                    {:type :provider.value-codec/invalid-direction
                     :direction direction})))
  (value/encode-bounded (envelope kit direction v)
                        max-audit-value-bytes))

(defn decode-audit-value
  "Decode and validate one provider audit envelope."
  [bytes]
  (let [decoded (value/decode-bounded bytes max-audit-value-bytes)]
    (when-not (and (map? decoded)
                   (= #{:format :kit :direction :value} (set (keys decoded)))
                   (= audit-format (:format decoded))
                   (keyword? (:kit decoded))
                   (#{:request :reply} (:direction decoded)))
      (throw (ex-info "invalid provider audit envelope"
                      {:type :provider.value-codec/invalid-envelope})))
    decoded))

(defn legacy-edn->audit-bytes
  "Bounded bridge from a W4 guest EDN string to canonical typed bytes.

  The byte bound is checked before parsing and again after canonical encoding.
  Tagged literals are rejected closed."
  [kit direction text]
  (when-not (string? text)
    (throw (ex-info "legacy provider audit value must be EDN text"
                    {:type :provider.value-codec/invalid-edn-text})))
  (when (> (utf8-byte-count text) max-audit-value-bytes)
    (throw (ex-info "legacy provider audit EDN exceeds byte limit"
                    {:type :provider.value-codec/limit-exceeded
                     :limit max-audit-value-bytes})))
  (let [parsed (edn/read-string
                {:readers {}
                 :default (fn [tag _]
                            (throw (ex-info "tagged literals are not admitted"
                                            {:type :provider.value-codec/tagged-literal
                                             :tag tag})))}
                text)]
    (encode-audit-value kit direction parsed)))
