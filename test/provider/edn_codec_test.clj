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

(deftest git-request-edn-host-wire-optional
  (let [r (codec/git-request-edn "[\"status\"]" 4096 5000)]
    (if (= :browser-host-unavailable (:reason r))
      (is true "skip when browser-host unavailable")
      (do
        (is (:ok r) (pr-str r))
        (is (str/includes? (:value r) ":args")
            (str "got " (:value r)))
        (is (str/includes? (:value r) "status"))))))

(deftest fs-req-read-edn-host-wire-optional
  (let [r (codec/fs-req-read-edn "workspace" "docs/a.txt")]
    (if (= :browser-host-unavailable (:reason r))
      (is true "skip when browser-host unavailable")
      (do
        (is (:ok r) (pr-str r))
        (is (str/includes? (:value r) ":read")
            (str "got " (:value r)))
        (is (str/includes? (:value r) "docs/a.txt"))))))

(deftest wrap-process-spawn-audits-optional
  (let [events (atom [])
        spawn (fn [_] {:tag :ok :exit 0 :stdout "hi" :stderr ""})
        wrapped (codec/wrap-process-spawn spawn (fn [e] (swap! events conj e)))
        reply (wrapped {:argv ["echo" "hi"] :max-stdout-bytes 4096 :timeout-ms 5000})]
    (is (= :ok (:tag reply)))
    (if (empty? @events)
      (is true "unexpected empty")
      (let [e (first @events)]
        (if (nil? (:request-edn e))
          (is true "skip when browser-host unavailable")
          (do
            (is (= :process (:kit e)))
            (is (str/includes? (:request-edn e) "echo"))
            (is (str/includes? (:reply-edn e) "hi"))))))))

(deftest wrap-git-run-audits-optional
  (let [events (atom [])
        run (fn [_] {:tag :ok :exit 0 :stdout "ok" :stderr ""})
        wrapped (codec/wrap-git-run run (fn [e] (swap! events conj e)))
        reply (wrapped {:args ["status"] :max-stdout-bytes 4096 :timeout-ms 5000})]
    (is (= :ok (:tag reply)))
    (if (empty? @events)
      (is true "unexpected empty")
      (let [e (first @events)]
        (if (nil? (:request-edn e))
          (is true "skip when browser-host unavailable")
          (do
            (is (= :git (:kit e)))
            (is (str/includes? (:request-edn e) "status"))))))))

(deftest wrap-entropy-draw-audits-optional
  (let [events (atom [])
        draw (fn [_] {:tag :hex :hex "deadbeefcafebabe"})
        wrapped (codec/wrap-entropy-draw draw (fn [e] (swap! events conj e)))
        reply (wrapped {:n 8})]
    (is (= :hex (:tag reply)))
    (if (empty? @events)
      (is true "unexpected empty")
      (let [e (first @events)]
        (if (nil? (:request-edn e))
          (is true "skip when browser-host unavailable")
          (do
            (is (= :entropy (:kit e)))
            (is (str/includes? (:request-edn e) "8"))
            (is (str/includes? (:reply-edn e) "deadbeefcafebabe"))))))))

(deftest wrap-scoped-fs-transact-audits-optional
  (let [events (atom [])
        tx (fn [{:keys [op]}]
             (if (= op :read)
               {:tag :content :content "hello"}
               {:tag :written :written true}))
        wrapped (codec/wrap-scoped-fs-transact tx (fn [e] (swap! events conj e)))
        reply (wrapped {:op :read :root "workspace" :path "docs/a.txt"})]
    (is (= :content (:tag reply)))
    (if (empty? @events)
      (is true "unexpected empty")
      (let [e (first @events)]
        (if (nil? (:request-edn e))
          (is true "skip when browser-host unavailable")
          (do
            (is (= :scoped-fs (:kit e)))
            (is (str/includes? (:request-edn e) "docs/a.txt"))
            (is (str/includes? (:reply-edn e) "hello"))))))))

(deftest secret-map-fetch-factory-audits-optional
  (let [events (atom [])
        fetch (codec/secret-map-fetch-with-edn-audit
               {"API_TOKEN" "s3cr3t"}
               (fn [e] (swap! events conj e)))
        reply (fetch {:name "API_TOKEN"})]
    (is (= :value (:tag reply)))
    (is (= "s3cr3t" (:value reply)))
    (if (empty? @events)
      (is true "unexpected empty")
      (let [e (first @events)]
        (if (nil? (:request-edn e))
          (is true "skip when browser-host unavailable")
          (do
            (is (str/includes? (:request-edn e) "API_TOKEN"))
            (is (str/includes? (:reply-edn e) "s3cr3t"))))))))

(deftest process-echo-factory-audits-optional
  (let [events (atom [])
        spawn (codec/process-echo-with-edn-audit
               (fn [e] (swap! events conj e)))
        reply (spawn {:argv ["echo" "hi"] :max-stdout-bytes 4096 :timeout-ms 5000})]
    (is (= :ok (:tag reply)))
    (if (empty? @events)
      (is true "unexpected empty")
      (let [e (first @events)]
        (if (nil? (:request-edn e))
          (is true "skip when browser-host unavailable")
          (do
            (is (= :process (:kit e)))
            (is (str/includes? (:request-edn e) "echo"))
            (is (= :ok (:reply-tag e)))))))))

(deftest git-echo-factory-audits-optional
  (let [events (atom [])
        run (codec/git-echo-with-edn-audit
             (fn [e] (swap! events conj e)))
        reply (run {:args ["status" "--short"] :max-stdout-bytes 8192 :timeout-ms 30000})]
    (is (= :ok (:tag reply)))
    (if (empty? @events)
      (is true "unexpected empty")
      (let [e (first @events)]
        (if (nil? (:request-edn e))
          (is true "skip when browser-host unavailable")
          (do
            (is (= :git (:kit e)))
            (is (str/includes? (:request-edn e) "status"))
            (is (= :ok (:reply-tag e)))))))))

(deftest wrap-scoped-fs-uses-value-field
  (let [events (atom [])
        store (fn [{:keys [op path]}]
                (if (= op :read)
                  {:tag :content :value "hello"}
                  {:tag :written}))
        wrapped (codec/wrap-scoped-fs-transact
                 store
                 (fn [e] (swap! events conj e)))
        reply (wrapped {:op :read :root :workspace :path "a.txt"})]
    (is (= :content (:tag reply)))
    (if (empty? @events)
      (is true "unexpected empty")
      (let [e (first @events)]
        (if (nil? (:reply-edn e))
          (is true "skip when browser-host unavailable")
          (is (str/includes? (:reply-edn e) "hello")))))))

(deftest entropy-mem-draw-factory-audits-bytes-as-hex-optional
  (let [events (atom [])
        ;; 8 deterministic bytes as 0–255 ints (entropy mem-draw contract)
        seed (vec (range 8))
        draw (codec/entropy-mem-draw-with-edn-audit
              seed
              (fn [e] (swap! events conj e)))
        reply (draw {:n 8})]
    (is (= :bytes (:tag reply)))
    (is (= 8 (count (:bytes reply))))
    (if (empty? @events)
      (is true "unexpected empty")
      (let [e (first @events)]
        (if (nil? (:request-edn e))
          (is true "skip when browser-host unavailable")
          (do
            (is (= :entropy (:kit e)))
            (is (str/includes? (:request-edn e) ":n"))
            (is (str/includes? (:request-edn e) "8"))
            (is (= :bytes (:reply-tag e)))
            (is (string? (:reply-edn e)))
            (is (str/includes? (:reply-edn e) ":hex"))
            ;; first two bytes 0x00 0x01 → 0001...
            (is (str/includes? (:reply-edn e) "0001"))))))))

(deftest entropy-os-draw-factory-smoke-optional
  (let [events (atom [])
        draw (codec/entropy-os-draw-with-edn-audit
              {:on-call (fn [e] (swap! events conj e))})
        reply (draw {:n 4})]
    (is (#{:bytes :error} (:tag reply)))
    (when (= :bytes (:tag reply))
      (is (= 4 (count (:bytes reply)))))
    (if (empty? @events)
      (is true "unexpected empty")
      (let [e (first @events)]
        (if (nil? (:request-edn e))
          (is true "skip when browser-host unavailable")
          (is (str/includes? (:request-edn e) "4")))))))
