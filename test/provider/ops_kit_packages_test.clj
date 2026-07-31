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
        ;; Component pilots (secret 0166, entropy 0167): wasm-aot partial + signed ready.
        (let [component-pilot? (contains? #{:secret :entropy} name)]
          (is (contains? (if component-pilot? #{:partial} #{:pending})
                         (:wasm-aot q))
              (str name " wasm-aot honesty"))
          (is (contains? (if component-pilot? #{:ready} #{:pending})
                         (:signed-content-addressed-package q))
              (str name " signed package honesty")))
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
    ;; ADR 0162/0165: Component enables signed package ready; wasm-aot stays partial
    ;; (thin host re-export, not compiler-AOT kit body).
    (is (= :partial (get-in http [:qualification :wasm-aot]))
        "ops Component pilot may mark wasm-aot partial (not full compiler AOT)")
    (is (= :ready (get-in http [:qualification :signed-content-addressed-package]))
        "ADR 0165 content-addressed Component package path ready")))

(deftest secret-kit-still-honest-about-aot
  (let [secret (load-kit "kotoba/lang/capability-kits/secret-v1.edn")]
    (is (= :implemented (get-in secret [:qualification :reference])))
    ;; ADR 0163/0166: Component enables signed package ready; wasm-aot stays partial
    ;; (embedded name policy, not compiler-AOT kit body / host fetch).
    (is (= :partial (get-in secret [:qualification :wasm-aot]))
        "ops Component pilot may mark wasm-aot partial (not full compiler AOT)")
    (is (= :ready (get-in secret [:qualification :signed-content-addressed-package]))
        "ADR 0166 content-addressed Component package path ready")))

(deftest entropy-kit-still-honest-about-aot
  (let [entropy (load-kit "kotoba/lang/capability-kits/entropy-v1.edn")]
    (is (= :implemented (get-in entropy [:qualification :reference])))
    (is (= :partial (get-in entropy [:qualification :wasm-aot]))
        "ADR 0167 draw-size Component: wasm-aot partial (host CSPRNG remains authority)")
    (is (= :ready (get-in entropy [:qualification :signed-content-addressed-package]))
        "ADR 0167 content-addressed Component package path ready")))
