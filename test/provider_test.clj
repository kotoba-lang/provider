(ns provider-test
  (:require [clojure.test :refer [deftest is]]
            [kotoba.kir.value :as value]
            [provider.conformance :as conformance]
            [provider.clock]
            [provider.clock-transport]
            [provider.http]
            [provider.http-ingress]
            [provider.http-transport]
            [provider.llm]
            [provider.llm-transport]
            [provider.log]
            [provider.object :as object]
            [provider.state :as state]
            [provider.storage]
            [provider.storage-transport]
            [provider.ui]))

;; Load gate: the split must not break namespace resolution. Each extracted
;; namespace must load standalone from this repo's own dependency closure.
(deftest every-extracted-namespace-loads
  (is (some? (find-ns 'provider.conformance)) "provider.conformance must load")
  (is (some? (find-ns 'provider.clock)) "provider.clock must load")
  (is (some? (find-ns 'provider.clock-transport)) "provider.clock-transport must load")
  (is (some? (find-ns 'provider.http)) "provider.http must load")
  (is (some? (find-ns 'provider.http-ingress)) "provider.http-ingress must load")
  (is (some? (find-ns 'provider.http-transport)) "provider.http-transport must load")
  (is (some? (find-ns 'provider.llm)) "provider.llm must load")
  (is (some? (find-ns 'provider.llm-transport)) "provider.llm-transport must load")
  (is (some? (find-ns 'provider.log)) "provider.log must load")
  (is (some? (find-ns 'provider.object)) "provider.object must load")
  (is (some? (find-ns 'provider.state)) "provider.state must load")
  (is (some? (find-ns 'provider.storage)) "provider.storage must load")
  (is (some? (find-ns 'provider.storage-transport)) "provider.storage-transport must load")
  (is (some? (find-ns 'provider.ui)) "provider.ui must load"))

(deftest state-provider-and-conformance-are-owned-here
  (let [provider (state/provider {:message "one"})
        put [state/request-type :put [state/put-type :message "two"]]
        get [state/request-type :get [state/get-type :message]]
        written ((:invoke provider) put)
        found ((:invoke provider) get)
        receipt (conformance/validate-suite!
                 {:state/transact state/capability-id}
                 [{:name :state/transact :id state/capability-id
                   :provider provider}])]
    (is (= :written (second written)))
    (is (= :found (second found)))
    (is (= "two" (get-in found [2 2])))
    (is (= [{:name :state/transact :id state/capability-id}]
           (:capabilities receipt)))))

(deftest object-get-stream-chunk-queue-yields-discrete-chunks
  "ADR 0125: {:chunk-queue [...]} is not pre-joined (unlike :chunks)."
  (let [a (byte-array [1 2])
        b (byte-array [3])
        ps (:providers (object/create-providers
                        {:allowed-bindings #{:example/blocks}
                         :transport (fn [_] {:chunk-queue [a b]})}))
        p (get ps object/get-stream-capability-id)
        task ((:invoke p) [object/get-stream-request-type :example/blocks "k"])
        stream (:stream (value/task-poll task))
        c1 (value/stream-read! stream 100)
        c2 (value/stream-read! stream 100)]
    (is (false? (:done? c1)))
    (is (= [1 2] (vec (:bytes c1))))
    (is (true? (:done? c2)))
    (is (= [3] (vec (:bytes c2))))))
