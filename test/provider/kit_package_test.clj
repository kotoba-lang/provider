(ns provider.kit-package-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
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
