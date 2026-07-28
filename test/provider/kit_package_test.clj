(ns provider.kit-package-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [ed25519.core :as ed]
            [provider.kit-package :as kit]))

(deftest sha256-hex-stable
  (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
         (kit/sha256-hex "")))
  (is (= (kit/sha256-hex "abc") (kit/sha256-hex "abc")))
  (is (not= (kit/sha256-hex "abc") (kit/sha256-hex "abd"))))

(deftest package-digest-unsigned
  (let [d (kit/package-digest "{:a 1}\n")]
    (is (= :sha256 (:alg d)))
    (is (false? (:signed? d)))
    (is (string? (:digest d)))
    (is (= 64 (count (:digest d))))))

(deftest secret-kit-receipt-not-production-signed
  (let [path "kotoba/lang/capability-kits/secret-v1.edn"
        text (slurp (io/resource path))
        rec (kit/kit-package-receipt :secret path text)]
    (is (= :secret (:kit-name rec)))
    (is (false? (:signed? (:package rec))))
    (is (false? (:production-signed-claim? rec)))
    (is (= :pending (get-in rec [:qualification :signed-content-addressed-package])))))

(deftest readiness-table-http-secret-process
  (let [table (kit/readiness-table
               (slurp (io/resource "kotoba/lang/kit-readiness-v1.edn")))
        http (kit/readiness-for table :http)
        secret (kit/readiness-for table :secret)
        process (kit/readiness-for table :process)]
    (is (= 1 (:kotoba.kit-readiness/version table)))
    (is (= :ready (get-in http [:scores :package])))
    (is (= :pending (get-in http [:scores :signed-wasm])))
    (is (false? (kit/production-signed-allowed? http)))
    (is (false? (kit/production-signed-allowed? secret)))
    (is (false? (kit/production-signed-allowed? process)))
    (let [path "kotoba/lang/capability-kits/http-v1.edn"
          text (slurp (io/resource path))
          pkg (kit/kit-package-receipt :http path text)
          rr (kit/readiness-receipt http pkg)]
      (is (= (:digest (:package pkg)) (:package-digest rr)))
      (is (false? (:production-signed-claim-allowed? rr))))))

(deftest readiness-covers-t8-critical-kits
  (let [table (kit/readiness-table
               (slurp (io/resource "kotoba/lang/kit-readiness-v1.edn")))
        names (set (map :name (:kits table)))]
    (doseq [n [:http :secret :process :scoped-fs :object :storage]]
      (is (contains? names n) (str n)))))

(deftest signing-input-canonical
  (is (= "kotoba.kit-package.signed/v1\nsha256\nabc\npath/x.edn\n"
         (kit/signing-input {:digest "abc" :resource "path/x.edn"}))))

(deftest signed-kit-receipt-round-trip
  (let [path "kotoba/lang/capability-kits/secret-v1.edn"
        text (slurp (io/resource path))
        unsigned (kit/kit-package-receipt :secret path text)
        {:keys [sign verify]} (kit/test-hmac-signer "test-secret-key")
        signed (kit/sign-kit-package-receipt unsigned sign)
        v (kit/verify-kit-package-receipt signed verify)]
    (is (true? (:signed-kit-receipt? signed)))
    (is (true? (get-in signed [:package :signed?])))
    (is (false? (:signed-wasm-provider? signed)))
    (is (false? (:production-signed-claim? signed)))
    (is (true? (:ok? v)))
    (is (= :signed-kit-edn-receipt (:layer v)))
    (is (false? (:signed-wasm-provider? v)))
    (is (= (get-in unsigned [:package :digest]) (:digest v)))))

(deftest signed-kit-receipt-rejects-forgery
  (let [path "kotoba/lang/capability-kits/http-v1.edn"
        text (slurp (io/resource path))
        unsigned (kit/kit-package-receipt :http path text)
        a (kit/test-hmac-signer "key-a")
        b (kit/test-hmac-signer "key-b")
        signed (kit/sign-kit-package-receipt unsigned (:sign a))
        bad (kit/verify-kit-package-receipt signed (:verify b))
        tampered (assoc-in signed [:package :digest] (kit/sha256-hex "nope"))
        bad2 (kit/verify-kit-package-receipt tampered (:verify a))]
    (is (false? (:ok? bad)))
    (is (= :bad-signature (:reason bad)))
    (is (false? (:ok? bad2)))
    (is (= :signing-input-mismatch (:reason bad2)))))

(deftest readiness-still-blocks-production-claim-after-signed-kit
  (let [table (kit/readiness-table
               (slurp (io/resource "kotoba/lang/kit-readiness-v1.edn")))
        secret (kit/readiness-for table :secret)
        path "kotoba/lang/capability-kits/secret-v1.edn"
        text (slurp (io/resource path))
        {:keys [sign]} (kit/test-hmac-signer "k")
        signed (kit/sign-kit-package-receipt
                (kit/kit-package-receipt :secret path text) sign)
        rr (kit/readiness-receipt secret signed)]
    (is (true? (:signed-kit-receipt? rr)))
    (is (false? (:production-signed-claim-allowed? rr))
        "signed kit EDN receipt must not imply production signed Wasm")))

(deftest empty-wasm-module-digest-stable
  (let [d (kit/wasm-artifact-digest kit/empty-wasm-module-bytes :fixture-synthetic)]
    (is (= :sha256 (:alg d)))
    (is (= 64 (count (:digest d))))
    (is (= :fixture-synthetic (:artifact-kind d)))
    (is (false? (:signed? d)))
    ;; magic\0asm + version is fixed → stable digest
    (is (= (kit/sha256-hex-bytes kit/empty-wasm-module-bytes) (:digest d)))))

(deftest wasm-signing-input-canonical
  (is (= (str "kotoba.kit-package.wasm-signed/v1\n"
              "sha256\n"
              "deadbeef\n"
              "kotoba/lang/capability-kits/secret-v1.edn\n"
              "application/wasm\n"
              "fixture-synthetic\n"
              "kitdig\n")
         (kit/wasm-signing-input
          {:digest "deadbeef"
           :resource "kotoba/lang/capability-kits/secret-v1.edn"
           :media-type "application/wasm"
           :artifact-kind :fixture-synthetic
           :kit-edn-digest "kitdig"}))))

(deftest signed-wasm-provider-receipt-round-trip
  (let [path "kotoba/lang/capability-kits/secret-v1.edn"
        text (slurp (io/resource path))
        kit-rec (kit/kit-package-receipt :secret path text)
        unsigned (-> (kit/wasm-provider-receipt
                      :secret path kit/empty-wasm-module-bytes
                      {:artifact-kind :fixture-synthetic})
                     (kit/chain-kit-and-wasm-receipts kit-rec))
        {:keys [sign verify]} (kit/test-hmac-signer "wasm-test-key")
        signed (kit/sign-wasm-provider-receipt unsigned sign)
        v (kit/verify-wasm-provider-receipt signed verify)]
    (is (true? (:signed-wasm-provider? signed)))
    (is (true? (:fixture? signed)))
    (is (false? (:production-signed-claim? signed)))
    (is (= (get-in kit-rec [:package :digest]) (:kit-edn-digest signed)))
    (is (true? (:ok? v)))
    (is (= :signed-wasm-provider-receipt (:layer v)))
    (is (true? (:fixture? v)))
    (is (false? (:production-signed-claim? v)))))

(deftest signed-wasm-receipt-rejects-forgery
  (let [path "kotoba/lang/capability-kits/http-v1.edn"
        unsigned (kit/wasm-provider-receipt
                  :http path kit/empty-wasm-module-bytes
                  {:artifact-kind :fixture-synthetic})
        a (kit/test-hmac-signer "key-a")
        b (kit/test-hmac-signer "key-b")
        signed (kit/sign-wasm-provider-receipt unsigned (:sign a))
        bad (kit/verify-wasm-provider-receipt signed (:verify b))
        tampered (assoc-in signed [:artifact :digest] (kit/sha256-hex "nope"))
        bad2 (kit/verify-wasm-provider-receipt tampered (:verify a))]
    (is (false? (:ok? bad)))
    (is (= :bad-signature (:reason bad)))
    (is (false? (:ok? bad2)))
    (is (= :signing-input-mismatch (:reason bad2)))))

(deftest readiness-still-blocks-after-signed-wasm-fixture
  (let [table (kit/readiness-table
               (slurp (io/resource "kotoba/lang/kit-readiness-v1.edn")))
        secret (kit/readiness-for table :secret)
        path "kotoba/lang/capability-kits/secret-v1.edn"
        text (slurp (io/resource path))
        {:keys [sign]} (kit/test-hmac-signer "k")
        kit-signed (kit/sign-kit-package-receipt
                    (kit/kit-package-receipt :secret path text) sign)
        wasm-signed (kit/sign-wasm-provider-receipt
                     (kit/chain-kit-and-wasm-receipts
                      (kit/wasm-provider-receipt
                       :secret path kit/empty-wasm-module-bytes
                       {:artifact-kind :fixture-synthetic})
                      kit-signed)
                     sign)
        rr (kit/readiness-receipt secret kit-signed wasm-signed)]
    (is (true? (:signed-kit-receipt? rr)))
    (is (true? (:signed-wasm-provider? rr)))
    (is (true? (:wasm-fixture? rr)))
    (is (string? (:wasm-artifact-digest rr)))
    (is (= :pending (get-in secret [:scores :signed-wasm])))
    (is (false? (:production-signed-claim-allowed? rr))
        "fixture signed Wasm must not open production-signed claim")))

(deftest hex-encode-decode-round-trip
  (let [raw (byte-array (map unchecked-byte [0 1 255 16]))
        h (kit/hex-encode raw)]
    (is (= "0001ff10" h))
    (is (= (seq raw) (seq (kit/hex-decode h))))))

(deftest fixture-wasm-matches-empty-module
  (let [fix (kit/load-fixture-wasm-bytes)]
    (is (= (seq kit/empty-wasm-module-bytes) (seq fix)))
    (is (= (kit/sha256-hex-bytes kit/empty-wasm-module-bytes)
           (kit/sha256-hex-bytes fix)))))

(deftest identity-signer-kit-receipt-round-trip
  "Production-shaped inject: host supplies byte sign/verify; kit-package
   normalises to string receipt API."
  (let [pub (byte-array (map unchecked-byte (range 32)))
        secret "host-identity-seed"
        sign-bytes (fn [msg-bytes]
                     (let [msg (String. ^bytes msg-bytes java.nio.charset.StandardCharsets/UTF_8)]
                       (kit/hex-decode (kit/hmac-sha256-hex secret msg))))
        verify-bytes (fn [msg-bytes _pub sig-bytes]
                       (let [msg (String. ^bytes msg-bytes java.nio.charset.StandardCharsets/UTF_8)
                             expected (kit/hex-decode (kit/hmac-sha256-hex secret msg))]
                         (= (seq expected) (seq sig-bytes))))
        id (kit/identity-signer {:sign-bytes sign-bytes
                                 :verify-bytes verify-bytes
                                 :public-key-bytes pub
                                 :alg :ed25519
                                 :key-id "test-identity"})
        path "kotoba/lang/capability-kits/secret-v1.edn"
        text (slurp (io/resource path))
        signed (kit/sign-kit-package-receipt
                (kit/kit-package-receipt :secret path text)
                (:sign id))
        v (kit/verify-kit-package-receipt signed (:verify id))
        wasm (kit/sign-wasm-provider-receipt
              (kit/wasm-provider-receipt
               :secret path (kit/load-fixture-wasm-bytes)
               {:artifact-kind :fixture-synthetic
                :kit-edn-digest (get-in signed [:package :digest])})
              (:sign id))
        vw (kit/verify-wasm-provider-receipt wasm (:verify id))]
    (is (= :ed25519 (get-in signed [:signature :alg])))
    (is (= "test-identity" (get-in signed [:signature :key-id])))
    (is (true? (:ok? v)))
    (is (true? (:ok? vw)))
    (is (true? (:fixture? vw)))
    (is (false? (:production-signed-claim? signed)))))

(deftest ed25519-identity-signer-kit-and-wasm
  "Real Ed25519 (org-ietf-ed25519, test dep) injected via identity-signer."
  (let [seed (byte-array 32)
        _ (dotimes [i 32] (aset-byte seed i (unchecked-byte i)))
        pub (ed/pubkey-from-seed seed)
        id (kit/identity-signer
            {:sign-bytes (fn [msg] (ed/sign seed msg))
             :verify-bytes (fn [msg pub-b sig] (ed/verify pub-b msg sig))
             :public-key-bytes pub
             :alg :ed25519
             :key-id "ed25519-test"})
        path "kotoba/lang/capability-kits/http-v1.edn"
        text (slurp (io/resource path))
        kit-signed (kit/sign-kit-package-receipt
                    (kit/kit-package-receipt :http path text)
                    (:sign id))
        v (kit/verify-kit-package-receipt kit-signed (:verify id))
        wasm (kit/sign-wasm-provider-receipt
              (kit/chain-kit-and-wasm-receipts
               (kit/wasm-provider-receipt
                :http path (kit/load-fixture-wasm-bytes)
                {:artifact-kind :fixture-synthetic})
               kit-signed)
              (:sign id))
        vw (kit/verify-wasm-provider-receipt wasm (:verify id))]
    (is (true? (:ok? v)))
    (is (= :ed25519 (get-in kit-signed [:signature :alg])))
    (is (true? (:ok? vw)))
    (is (false? (:production-signed-claim? kit-signed)))
    (is (= :pending
           (get-in (kit/readiness-for
                    (kit/readiness-table
                     (slurp (io/resource "kotoba/lang/kit-readiness-v1.edn")))
                    :http)
                   [:scores :signed-wasm])))))
