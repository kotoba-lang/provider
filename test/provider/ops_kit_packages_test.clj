(ns provider.ops-kit-packages-test
  "W6 ops kit packages: EDN surface + honest qualification claims."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(def ops-kits
  [{:name :secret :resource "kotoba/lang/capability-kits/secret-v1.edn" :id 21}
   {:name :process :resource "kotoba/lang/capability-kits/process-v1.edn" :id 20}
   {:name :scoped-fs :resource "kotoba/lang/capability-kits/scoped-fs-v1.edn" :id 19}
   {:name :git :resource "kotoba/lang/capability-kits/git-v1.edn" :id 22}
   {:name :entropy :resource "kotoba/lang/capability-kits/entropy-v1.edn" :id 23}])

(defn- load-kit [resource]
  (edn/read-string (slurp (io/resource resource))))

(deftest ops-kit-packages-load
  (doseq [{:keys [name resource id]} ops-kits]
    (testing (str name)
      (let [kit (load-kit resource)
            q (:qualification kit)
            cap (:capability kit)]
        (is (= name (:kotoba.capability-kit/name kit)))
        (is (= 1 (:kotoba.capability-kit/version kit)))
        (is (= id (:id cap)))
        (is (= :implemented (:reference q)))
        (is (= :pending (:wasm-aot q)))
        (is (= :pending (:signed-content-addressed-package q)))
        (is (some? (:request kit)))
        (is (some? (:result kit)))))))

(deftest provider-conformance-lists-ops-kits
  (let [conf (edn/read-string
              (slurp (io/resource "kotoba/lang/provider-conformance-v1.edn")))
        names (set (map :name (:kits conf)))]
    (is (contains? names :http) "pre-existing network kit")
    (doseq [n [:secret :process :scoped-fs :git :entropy]]
      (is (contains? names n) (str "ops kit registered: " n)))))

(deftest http-kit-still-honest-about-aot
  (let [http (load-kit "kotoba/lang/capability-kits/http-v1.edn")]
    (is (= :implemented (get-in http [:qualification :reference])))
    ;; ADR 0162: real non-fixture wasm bytes → :wasm-aot :partial, but signed
    ;; content-addressed package + readiness :signed-wasm stay pending.
    (is (= :partial (get-in http [:qualification :wasm-aot]))
        "ops real-bytes pilot may mark wasm-aot partial (not full AOT Component)")
    (is (= :pending (get-in http [:qualification :signed-content-addressed-package]))
        "must not claim signed package until publisher policy covers ops")))
