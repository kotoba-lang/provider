(ns provider.edn-codec-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [provider.edn-codec :as codec]))

(deftest codec-aot-complete-flag
  (is (true? (codec/codec-aot-complete?))))

(deftest ops-kits-declare-codec-aot-implemented
  (doseq [res ["kotoba/lang/capability-kits/http-v1.edn"
               "kotoba/lang/capability-kits/secret-v1.edn"
               "kotoba/lang/capability-kits/process-v1.edn"
               "kotoba/lang/capability-kits/git-v1.edn"
               "kotoba/lang/capability-kits/entropy-v1.edn"
               "kotoba/lang/capability-kits/scoped-fs-v1.edn"]]
    (let [kit (clojure.edn/read-string (slurp (io/resource res)))]
      (is (= :implemented (get-in kit [:qualification :codec-aot])) res)
      (is (= :partial (get-in kit [:qualification :wasm-aot])) res))))

(deftest secret-request-edn-host-wire-optional
  (testing "pure W4 secret request via browser-host when available"
    (let [r (codec/secret-request-edn "API_TOKEN")]
      (if (= :browser-host-unavailable (:reason r))
        (is true "skip when browser-host unavailable")
        (do
          (is (:ok r) (pr-str r))
          (is (string? (:value r)))
          (is (str/includes? (:value r) ":name"))
          (is (str/includes? (:value r) "API_TOKEN"))
          (let [empty-r (codec/secret-request-edn "")]
            (is (:ok empty-r))
            (is (= "" (:value empty-r)))))))))

(deftest entropy-request-edn-host-wire-optional
  (let [r (codec/entropy-request-edn 16)]
    (if (= :browser-host-unavailable (:reason r))
      (is true "skip when browser-host unavailable")
      (do
        (is (:ok r) (pr-str r))
        (is (str/includes? (:value r) ":n"))
        (is (str/includes? (:value r) "16"))
        (let [bad (codec/entropy-request-edn 0)]
          (is (:ok bad))
          (is (= "" (:value bad)) "n=0 fail-closed empty EDN"))))))

(deftest process-request-edn-host-wire-optional
  (let [r (codec/process-request-edn "[\"echo\" \"hi\"]" 4096 5000)]
    (if (= :browser-host-unavailable (:reason r))
      (is true "skip when browser-host unavailable")
      (do
        (is (:ok r) (pr-str r))
        (is (str/includes? (:value r) ":argv"))
        (is (str/includes? (:value r) "echo"))))))

(deftest unknown-package-fails-closed
  (let [r (codec/invoke-export :no-such-package :main [])]
    (is (false? (:ok r)))
    (is (contains? #{:unknown-package :browser-host-unavailable} (:reason r)))))
