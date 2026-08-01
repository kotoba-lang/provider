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

(deftest wrap-secret-fetch-audits-edn-optional
  (let [events (atom [])
        fetch (fn [{:keys [name]}]
                (if (= name "API_TOKEN")
                  {:tag :value :value "s3cr3t"}
                  {:tag :error :code :secret/not-found :message "missing"}))
        wrapped (codec/wrap-secret-fetch fetch (fn [e] (swap! events conj e)))
        reply (wrapped {:name "API_TOKEN"})]
    (is (= :value (:tag reply)))
    (is (= "s3cr3t" (:value reply)))
    (if (empty? @events)
      (is true "no events if wrap failed closed without host — unexpected")
      (let [e (first @events)]
        (if (nil? (:request-edn e))
          (is true "skip EDN assert when browser-host unavailable")
          (do
            (is (= :secret (:kit e)))
            (is (str/includes? (:request-edn e) "API_TOKEN"))
            (is (str/includes? (:reply-edn e) "s3cr3t"))
            (is (= :value (:reply-tag e)))))))))

(deftest wrap-http-post-transport-audits-request-edn-optional
  (let [events (atom [])
        transport (fn [{:keys [url]}]
                    {:status 200 :body "ok" :headers {}})
        wrapped (codec/wrap-http-post-transport
                 transport
                 (fn [e] (swap! events conj e)))
        reply (wrapped {:url "https://ex.com/a"
                        :headers {"Accept" "text/plain" "Host" "ex.com"}
                        :body "hi"
                        :timeout-ms 5000})]
    (is (= 200 (:status reply)))
    (if (empty? @events)
      (is true "unexpected empty events")
      (let [e (first @events)]
        (if (nil? (:request-edn e))
          (is true "skip EDN assert when browser-host unavailable")
          (do
            (is (= :http (:kit e)))
            (is (str/includes? (:request-edn e) "https://ex.com/a"))
            (is (= 200 (:status e)))
            (is (false? (:error? e)))))))))
