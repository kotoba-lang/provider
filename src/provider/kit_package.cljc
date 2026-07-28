(ns provider.kit-package
  "Capability-kit package identity (content-address fingerprint).

  Computes a deterministic SHA-256 hex digest of kit EDN **text as shipped**.
  This is an unsigned package fingerprint for inventory and readiness receipts.

  It is **not** a production signed Wasm provider claim. See ADR 0152 / 0153:
  `:signed-wasm` stays pending until a signed content-addressed Component
  artifact exists."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            #?(:clj [clojure.java.io :as io]))
  #?(:clj (:import (java.security MessageDigest)
                   (java.nio.charset StandardCharsets))))

(def readiness-resource "kotoba/lang/kit-readiness-v1.edn")

(defn- utf8-bytes [s]
  #?(:clj (.getBytes ^String s StandardCharsets/UTF_8)
     :cljs
     (let [enc (js/TextEncoder.)]
       (js/Array.from (.encode enc s)))))

(defn sha256-hex
  "SHA-256 of UTF-8 string → lowercase hex. Dual-runtime (JVM MessageDigest /
  WebCrypto sync not available on all cljs — Node crypto on nbb)."
  [s]
  (let [s (str s)]
    #?(:clj
       (let [md (MessageDigest/getInstance "SHA-256")
             digest (.digest md (utf8-bytes s))]
         (apply str (map #(format "%02x" %) digest)))
       :cljs
       (try
         (let [crypto (js/require "crypto")
               h (.createHash crypto "sha256")]
           (.update h s)
           (.digest h "hex"))
         (catch :default _
           (throw (ex-info "kit-package/sha256-hex requires Node crypto on cljs"
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

(defn kit-package-receipt
  "Build an unsigned package receipt for a kit resource.
  `edn-text` is the exact shipped file body."
  [kit-name resource-path edn-text]
  (let [kit (edn/read-string edn-text)
        dig (package-digest edn-text)
        q (:qualification kit)]
    {:format :kotoba.kit-package/v1
     :name kit-name
     :resource resource-path
     :kit-name (:kotoba.capability-kit/name kit)
     :kit-version (:kotoba.capability-kit/version kit)
     :package dig
     :qualification (select-keys q [:reference :wasm-aot :signed-content-addressed-package
                                    :dual-runtime-os-transport :host-transports])
     :production-signed-claim?
     (boolean (and (= :ready (:signed-content-addressed-package q))
                   (:signed? dig)))}))

(defn readiness-table
  "Parse kit-readiness-v1 EDN text → table map."
  [edn-text]
  (edn/read-string edn-text))

(defn readiness-for
  "Lookup one kit readiness row by name keyword."
  [table kit-name]
  (first (filter #(= kit-name (:name %)) (:kits table))))

(defn production-signed-allowed?
  "True only when readiness scores clear the ADR 0153 signed gate.
  Never true solely because reference dual-runtime is implemented."
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
  "Compact receipt for inventory: kit name, scores, package digest (optional),
  and whether production signed claim is allowed (always false until signed-wasm ready)."
  ([row] (readiness-receipt row nil))
  ([row package-receipt]
   {:name (:name row)
    :id (:id row)
    :scores (:scores row)
    :package-digest (get-in package-receipt [:package :digest])
    :production-signed-claim-allowed? (production-signed-allowed? row)
    :evidence (:evidence row)}))
