(ns provider.kit-package
  "Capability-kit package identity + optional host-signed package receipts.

  ## Layers (do not collapse)

  1. **Unsigned fingerprint** — SHA-256 of kit EDN text as shipped.
  2. **Signed kit receipt** (T8.3 first slice) — host-injected sign over the
     fingerprint + resource path. Proves publisher intent for the **kit EDN
     package**, not a Wasm Component.
  3. **Signed Wasm provider receipt** (T8.3 remainder, API first slice) —
     host-injected sign over content-address of Wasm **bytes** + kit resource
     binding. Does **not** emit AOT Wasm; does **not** flip readiness
     `:signed-wasm` to ready until a real Component package + full gate exist.

  See ADR 0152–0155."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            #?(:clj [clojure.java.io :as io]))
  #?(:clj (:import (java.security MessageDigest)
                   (javax.crypto Mac)
                   (javax.crypto.spec SecretKeySpec)
                   (java.nio.charset StandardCharsets))))

(def readiness-resource "kotoba/lang/kit-readiness-v1.edn")
(def receipt-format :kotoba.kit-package/v1)
(def signed-receipt-format :kotoba.kit-package.signed/v1)
(def wasm-receipt-format :kotoba.kit-package.wasm/v1)
(def signed-wasm-receipt-format :kotoba.kit-package.wasm-signed/v1)

;; Minimal WebAssembly binary module (magic + version only). Useful as a
;; synthetic fixture for receipt round-trips — **not** a production provider.
(def empty-wasm-module-bytes
  #?(:clj (byte-array [0x00 0x61 0x73 0x6d 0x01 0x00 0x00 0x00])
     :cljs #js [0x00 0x61 0x73 0x6d 0x01 0x00 0x00 0x00]))

(defn- utf8-bytes [s]
  #?(:clj (.getBytes ^String (str s) StandardCharsets/UTF_8)
     :cljs
     (let [enc (js/TextEncoder.)]
       (js/Array.from (.encode enc (str s))))))

(defn- bytes->hex
  [digest]
  #?(:clj (apply str (map #(format "%02x" %) digest))
     :cljs
     (let [arr (js/Uint8Array. digest)]
       (apply str (map (fn [i]
                         (let [b (aget arr i)
                               h (.toString b 16)]
                           (if (< b 16) (str "0" h) h)))
                       (range (.-length arr)))))))

(defn sha256-hex
  "SHA-256 of UTF-8 string → lowercase hex. Dual-runtime (JVM / Node crypto)."
  [s]
  (let [s (str s)]
    #?(:clj
       (let [md (MessageDigest/getInstance "SHA-256")
             digest (.digest md (utf8-bytes s))]
         (bytes->hex digest))
       :cljs
       (try
         (let [crypto (js/require "crypto")
               h (.createHash crypto "sha256")]
           (.update h s)
           (.digest h "hex"))
         (catch :default _
           (throw (ex-info "kit-package/sha256-hex requires Node crypto on cljs"
                           {:phase :kit-package})))))))

(defn- coerce-bytes
  "Normalize binary input to platform bytes for hashing."
  [data]
  #?(:clj
     (cond
       (bytes? data) data
       (string? data) (.getBytes ^String data StandardCharsets/UTF_8)
       (sequential? data) (byte-array (map unchecked-byte data))
       :else (throw (ex-info "sha256-hex-bytes expects bytes|string|seq"
                             {:phase :kit-package :type (type data)})))
     :cljs
     (try
       (let [Buffer (.-Buffer (js/require "buffer"))]
         (cond
           (string? data) (.from Buffer data "utf8")
           (or (instance? js/Uint8Array data)
               (instance? js/Array data)
               (array? data))
           (.from Buffer data)
           :else (throw (ex-info "sha256-hex-bytes expects bytes|string|array"
                                 {:phase :kit-package}))))
       (catch :default e
         (throw (ex-info "sha256-hex-bytes requires Node buffer on cljs"
                         {:phase :kit-package :cause (str e)}))))))

(defn sha256-hex-bytes
  "SHA-256 of binary payload → lowercase hex. Dual-runtime (JVM / Node crypto)."
  [data]
  (let [ba (coerce-bytes data)]
    #?(:clj
       (let [md (MessageDigest/getInstance "SHA-256")]
         (bytes->hex (.digest md ba)))
       :cljs
       (try
         (let [crypto (js/require "crypto")
               h (.createHash crypto "sha256")]
           (.update h ba)
           (.digest h "hex"))
         (catch :default _
           (throw (ex-info "kit-package/sha256-hex-bytes requires Node crypto"
                           {:phase :kit-package})))))))

(defn package-digest
  "Content-address fingerprint of kit EDN text (unsigned)."
  [edn-text]
  (when (str/blank? (str edn-text))
    (throw (ex-info "kit package text empty" {:phase :kit-package})))
  {:alg :sha256
   :digest (sha256-hex edn-text)
   :signed? false
   :note "unsigned content-address of kit EDN; not a signed Wasm provider"})

(defn wasm-artifact-digest
  "Content-address fingerprint of Wasm provider bytes (unsigned).
  `artifact-kind` is documentation only: e.g. `:wasm-module`, `:wasm-component`,
  `:fixture-synthetic` (tests / placeholders — never production)."
  ([wasm-bytes] (wasm-artifact-digest wasm-bytes :wasm-module))
  ([wasm-bytes artifact-kind]
   (when (nil? wasm-bytes)
     (throw (ex-info "wasm bytes required" {:phase :kit-package})))
   {:alg :sha256
    :digest (sha256-hex-bytes wasm-bytes)
    :media-type "application/wasm"
    :artifact-kind artifact-kind
    :signed? false
    :note "unsigned content-address of Wasm bytes; not a production signed claim"}))

(defn load-kit-text
  "Load kit EDN resource text from classpath (JVM) or injected loader."
  ([resource-path]
   #?(:clj
      (if-let [url (io/resource resource-path)]
        (slurp url)
        (throw (ex-info "kit resource missing" {:path resource-path})))
      :cljs
      (throw (ex-info "load-kit-text requires inject on cljs"
                      {:path resource-path :hint "pass text or loader"}))))
  ([resource-path loader]
   (or (loader resource-path)
       (throw (ex-info "kit resource missing" {:path resource-path})))))

(defn signing-input
  "Canonical message signed for a kit package receipt (UTF-8 string).
  Format: lines — format, digest-alg, digest, resource-path."
  [{:keys [digest resource]}]
  (str (namespace signed-receipt-format) "/" (name signed-receipt-format) "\n"
       "sha256\n"
       digest "\n"
       resource "\n"))

(defn kit-package-receipt
  "Build an unsigned package receipt for a kit resource.
  `edn-text` is the exact shipped file body."
  [kit-name resource-path edn-text]
  (let [kit (edn/read-string edn-text)
        dig (package-digest edn-text)
        q (:qualification kit)]
    {:format receipt-format
     :name kit-name
     :resource resource-path
     :kit-name (:kotoba.capability-kit/name kit)
     :kit-version (:kotoba.capability-kit/version kit)
     :package dig
     :qualification (select-keys q [:reference :wasm-aot :signed-content-addressed-package
                                    :dual-runtime-os-transport :host-transports])
     :production-signed-claim?
     false
     :signed-kit-receipt? false
     :signed-wasm-provider? false}))

(defn hmac-sha256-hex
  "Test/reference MAC (not production identity/sign). Dual-runtime."
  [key-str msg]
  #?(:clj
     (let [mac (Mac/getInstance "HmacSHA256")
           key (SecretKeySpec. (utf8-bytes key-str) "HmacSHA256")]
       (.init mac key)
       (apply str (map #(format "%02x" %) (.doFinal mac (utf8-bytes msg)))))
     :cljs
     (try
       (let [crypto (js/require "crypto")]
         (-> (.createHmac crypto "sha256" key-str)
             (.update msg)
             (.digest "hex")))
       (catch :default _
         (throw (ex-info "hmac-sha256-hex requires Node crypto on cljs"
                         {:phase :kit-package}))))))

(defn test-hmac-signer
  "Host-injectable signer for tests: HMAC-SHA256 with a shared secret.
  Returns sign-fn / verify-fn pair. Production hosts inject identity.sign."
  [secret-key]
  (let [key-id (str "hmac-test:" (subs (sha256-hex secret-key) 0 16))
        sign (fn [message]
               {:alg :hmac-sha256
                :key-id key-id
                :public-key key-id
                :signature (hmac-sha256-hex secret-key message)})
        verify (fn [message public-key signature]
                 (and (= public-key key-id)
                      (= signature (hmac-sha256-hex secret-key message))))]
    {:sign sign :verify verify :key-id key-id}))

(defn sign-kit-package-receipt
  "Attach a host signature to an unsigned kit package receipt.

  `sign-fn` is host-injected: (fn [message-str] -> {:alg :key-id :public-key :signature}).
  Signs kit EDN content-address only — **not** a Wasm Component artifact.

  Returns receipt with `:signed-kit-receipt? true` and `:package :signed? true`
  for the **kit EDN** layer. `:signed-wasm-provider?` stays false;
  `:production-signed-claim?` stays false until readiness signed-wasm is ready."
  [unsigned-receipt sign-fn]
  (when-not (and (map? unsigned-receipt)
                 (= receipt-format (:format unsigned-receipt))
                 (string? (get-in unsigned-receipt [:package :digest])))
    (throw (ex-info "sign-kit-package-receipt requires unsigned v1 receipt"
                    {:phase :kit-package})))
  (when-not (fn? sign-fn)
    (throw (ex-info "sign-fn required" {:phase :kit-package})))
  (let [digest (get-in unsigned-receipt [:package :digest])
        resource (:resource unsigned-receipt)
        msg (signing-input {:digest digest :resource resource})
        sig (sign-fn msg)]
    (when-not (and (map? sig) (:signature sig) (:public-key sig) (:alg sig))
      (throw (ex-info "sign-fn must return {:alg :key-id? :public-key :signature}"
                      {:phase :kit-package :got sig})))
    (-> unsigned-receipt
        (assoc :format signed-receipt-format
               :signed-kit-receipt? true
               :signed-wasm-provider? false
               :production-signed-claim? false
               :signing-input msg
               :signature {:alg (:alg sig)
                           :key-id (:key-id sig)
                           :public-key (:public-key sig)
                           :signature (:signature sig)})
        (assoc-in [:package :signed?] true)
        (assoc-in [:package :note]
                  "signed kit EDN package receipt; Wasm provider artifact not included"))))

(defn verify-kit-package-receipt
  "Verify a signed kit package receipt with host-injected verify-fn:
  (fn [message public-key signature] -> boolean).

  Also re-checks signing-input matches digest+resource."
  [receipt verify-fn]
  (when-not (fn? verify-fn)
    (throw (ex-info "verify-fn required" {:phase :kit-package})))
  (cond
    (not= signed-receipt-format (:format receipt))
    {:ok? false :reason :not-signed-receipt}

    (not (string? (get-in receipt [:package :digest])))
    {:ok? false :reason :missing-digest}

    :else
    (let [digest (get-in receipt [:package :digest])
          resource (:resource receipt)
          expected (signing-input {:digest digest :resource resource})
          actual (or (:signing-input receipt) expected)
          sig (:signature receipt)]
      (cond
        (not= expected actual)
        {:ok? false :reason :signing-input-mismatch}

        (not (and (map? sig) (:signature sig) (:public-key sig)))
        {:ok? false :reason :missing-signature}

        (not (verify-fn actual (:public-key sig) (:signature sig)))
        {:ok? false :reason :bad-signature}

        :else
        {:ok? true
         :layer :signed-kit-edn-receipt
         :signed-wasm-provider? false
         :digest digest
         :resource resource}))))

(defn wasm-signing-input
  "Canonical message signed for a Wasm provider receipt (UTF-8 string).
  Lines: format, digest-alg, digest, kit-resource, media-type, artifact-kind,
  kit-edn-digest (empty if unbound)."
  [{:keys [digest resource media-type artifact-kind kit-edn-digest]}]
  (str (namespace signed-wasm-receipt-format) "/" (name signed-wasm-receipt-format) "\n"
       "sha256\n"
       digest "\n"
       resource "\n"
       (or media-type "application/wasm") "\n"
       (name (or artifact-kind :wasm-module)) "\n"
       (or kit-edn-digest "") "\n"))

(defn wasm-provider-receipt
  "Build an unsigned Wasm provider receipt bound to a kit resource path.

  `wasm-bytes` is the exact binary to content-address.
  `opts`:
    :artifact-kind — :wasm-module | :wasm-component | :fixture-synthetic
    :kit-edn-digest — optional SHA-256 of the kit EDN text (chain to layer 2)
    :media-type — default application/wasm

  Honesty: `:production-signed-claim?` is always false here; readiness
  `:signed-wasm` stays pending until real AOT packages + full gate."
  ([kit-name resource-path wasm-bytes]
   (wasm-provider-receipt kit-name resource-path wasm-bytes nil))
  ([kit-name resource-path wasm-bytes opts]
   (let [opts (or opts {})
         kind (or (:artifact-kind opts) :wasm-module)
         media (or (:media-type opts) "application/wasm")
         kit-dig (:kit-edn-digest opts)
         dig (wasm-artifact-digest wasm-bytes kind)]
     {:format wasm-receipt-format
      :name kit-name
      :resource resource-path
      :artifact dig
      :media-type media
      :artifact-kind kind
      :kit-edn-digest kit-dig
      :production-signed-claim? false
      :signed-kit-receipt? false
      :signed-wasm-provider? false
      :fixture? (= kind :fixture-synthetic)})))

(defn sign-wasm-provider-receipt
  "Attach a host signature to an unsigned Wasm provider receipt.

  `sign-fn` same contract as sign-kit-package-receipt.
  Signs Wasm **bytes digest** + kit binding — still not a production claim
  unless readiness `:signed-wasm` is ready and artifact is not a fixture."
  [unsigned-receipt sign-fn]
  (when-not (and (map? unsigned-receipt)
                 (= wasm-receipt-format (:format unsigned-receipt))
                 (string? (get-in unsigned-receipt [:artifact :digest])))
    (throw (ex-info "sign-wasm-provider-receipt requires unsigned wasm v1 receipt"
                    {:phase :kit-package})))
  (when-not (fn? sign-fn)
    (throw (ex-info "sign-fn required" {:phase :kit-package})))
  (let [digest (get-in unsigned-receipt [:artifact :digest])
        resource (:resource unsigned-receipt)
        media (:media-type unsigned-receipt)
        kind (:artifact-kind unsigned-receipt)
        kit-dig (:kit-edn-digest unsigned-receipt)
        msg (wasm-signing-input {:digest digest
                                 :resource resource
                                 :media-type media
                                 :artifact-kind kind
                                 :kit-edn-digest kit-dig})
        sig (sign-fn msg)]
    (when-not (and (map? sig) (:signature sig) (:public-key sig) (:alg sig))
      (throw (ex-info "sign-fn must return {:alg :key-id? :public-key :signature}"
                      {:phase :kit-package :got sig})))
    (-> unsigned-receipt
        (assoc :format signed-wasm-receipt-format
               :signed-wasm-provider? true
               :production-signed-claim? false
               :signing-input msg
               :signature {:alg (:alg sig)
                           :key-id (:key-id sig)
                           :public-key (:public-key sig)
                           :signature (:signature sig)})
        (assoc-in [:artifact :signed?] true)
        (assoc-in [:artifact :note]
                  (if (:fixture? unsigned-receipt)
                    "signed fixture Wasm digest; not a production provider"
                    "signed Wasm provider digest; readiness signed-wasm still gated")))))

(defn verify-wasm-provider-receipt
  "Verify a signed Wasm provider receipt with host-injected verify-fn:
  (fn [message public-key signature] -> boolean)."
  [receipt verify-fn]
  (when-not (fn? verify-fn)
    (throw (ex-info "verify-fn required" {:phase :kit-package})))
  (cond
    (not= signed-wasm-receipt-format (:format receipt))
    {:ok? false :reason :not-signed-wasm-receipt}

    (not (string? (get-in receipt [:artifact :digest])))
    {:ok? false :reason :missing-digest}

    :else
    (let [digest (get-in receipt [:artifact :digest])
          resource (:resource receipt)
          expected (wasm-signing-input
                    {:digest digest
                     :resource resource
                     :media-type (:media-type receipt)
                     :artifact-kind (:artifact-kind receipt)
                     :kit-edn-digest (:kit-edn-digest receipt)})
          actual (or (:signing-input receipt) expected)
          sig (:signature receipt)]
      (cond
        (not= expected actual)
        {:ok? false :reason :signing-input-mismatch}

        (not (and (map? sig) (:signature sig) (:public-key sig)))
        {:ok? false :reason :missing-signature}

        (not (verify-fn actual (:public-key sig) (:signature sig)))
        {:ok? false :reason :bad-signature}

        :else
        {:ok? true
         :layer :signed-wasm-provider-receipt
         :signed-wasm-provider? true
         :fixture? (boolean (:fixture? receipt))
         :digest digest
         :resource resource
         :kit-edn-digest (:kit-edn-digest receipt)
         :production-signed-claim? false}))))

(defn chain-kit-and-wasm-receipts
  "Honesty helper: attach kit EDN digest onto a Wasm receipt for inventory.
  Does not re-sign; call before sign-wasm-provider-receipt."
  [wasm-unsigned kit-receipt]
  (when-not (and (map? wasm-unsigned) (= wasm-receipt-format (:format wasm-unsigned)))
    (throw (ex-info "chain requires unsigned wasm receipt" {:phase :kit-package})))
  (let [kit-dig (or (get-in kit-receipt [:package :digest])
                    (:kit-edn-digest kit-receipt))]
    (when-not (string? kit-dig)
      (throw (ex-info "kit receipt missing package digest" {:phase :kit-package})))
    (assoc wasm-unsigned :kit-edn-digest kit-dig)))

(defn readiness-table
  "Parse kit-readiness-v1 EDN text → table map."
  [edn-text]
  (edn/read-string edn-text))

(defn readiness-for
  "Lookup one kit readiness row by name keyword."
  [table kit-name]
  (first (filter #(= kit-name (:name %)) (:kits table))))

(defn production-signed-allowed?
  "True only when readiness scores clear the ADR 0153 signed **Wasm** gate.
  A verified signed kit EDN receipt and/or signed Wasm fixture receipt alone
  are insufficient."
  [row]
  (let [s (:scores row)]
    (boolean
     (and (= :ready (:schema s))
          (= :ready (:dual-runtime s))
          (= :ready (:deny-fixtures s))
          (= :ready (:quota s))
          (= :ready (:package s))
          (= :ready (:signed-wasm s))))))

(defn readiness-receipt
  "Compact receipt for inventory.
  Optional second arg: kit package receipt.
  Optional third arg: wasm provider receipt."
  ([row] (readiness-receipt row nil nil))
  ([row package-receipt] (readiness-receipt row package-receipt nil))
  ([row package-receipt wasm-receipt]
   {:name (:name row)
    :id (:id row)
    :scores (:scores row)
    :package-digest (get-in package-receipt [:package :digest])
    :signed-kit-receipt? (boolean (:signed-kit-receipt? package-receipt))
    :wasm-artifact-digest (get-in wasm-receipt [:artifact :digest])
    :signed-wasm-provider? (boolean (:signed-wasm-provider? wasm-receipt))
    :wasm-fixture? (boolean (:fixture? wasm-receipt))
    :production-signed-claim-allowed? (production-signed-allowed? row)
    :evidence (:evidence row)}))
