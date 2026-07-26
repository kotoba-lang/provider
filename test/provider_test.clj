(ns provider-test
  (:require [clojure.test :refer [deftest is]]
            [provider.conformance]
            [provider.clock]
            [provider.clock-transport]
            [provider.http]
            [provider.http-transport]
            [provider.llm]
            [provider.llm-transport]
            [provider.log]
            [provider.state]
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
  (is (some? (find-ns 'provider.http-transport)) "provider.http-transport must load")
  (is (some? (find-ns 'provider.llm)) "provider.llm must load")
  (is (some? (find-ns 'provider.llm-transport)) "provider.llm-transport must load")
  (is (some? (find-ns 'provider.log)) "provider.log must load")
  (is (some? (find-ns 'provider.state)) "provider.state must load")
  (is (some? (find-ns 'provider.storage)) "provider.storage must load")
  (is (some? (find-ns 'provider.storage-transport)) "provider.storage-transport must load")
  (is (some? (find-ns 'provider.ui)) "provider.ui must load"))
