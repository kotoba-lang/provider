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
        (let [component-pilot? (contains? #{:secret :entropy :process :scoped-fs :git} name)]
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

(deftest process-kit-still-honest-about-aot
  (let [process (load-kit "kotoba/lang/capability-kits/process-v1.edn")]
    (is (= :implemented (get-in process [:qualification :reference])))
    (is (= :partial (get-in process [:qualification :wasm-aot]))
        "ops Component pilot may mark wasm-aot partial (not full compiler AOT)")
    (is (= :ready (get-in process [:qualification :signed-content-addressed-package]))
        "ADR 0168 content-addressed Component package path ready")))

(deftest scoped-fs-kit-still-honest-about-aot
  (let [fs (load-kit "kotoba/lang/capability-kits/scoped-fs-v1.edn")]
    (is (= :implemented (get-in fs [:qualification :reference])))
    (is (= :partial (get-in fs [:qualification :wasm-aot])))
    (is (= :ready (get-in fs [:qualification :signed-content-addressed-package])))))

(deftest git-kit-still-honest-about-aot
  (let [git (load-kit "kotoba/lang/capability-kits/git-v1.edn")]
    (is (= :implemented (get-in git [:qualification :reference])))
    (is (= :partial (get-in git [:qualification :wasm-aot]))
        "ops Component pilot may mark wasm-aot partial (not full compiler AOT)")
    (is (= :ready (get-in git [:qualification :signed-content-addressed-package]))
        "ADR 0170 content-addressed Component package path ready")))

(deftest http-compiler-aot-bounds-package-registered
  (let [table (edn/read-string
               (slurp (io/resource "kotoba/lang/wasm-packages/wasm-packages-v1.edn")))
        by-name (into {} (map (juxt :name identity) (:packages table)))
        mod (get by-name :http-post-bounds)
        comp (get by-name :http-post-bounds-component)
        mod-bytes (-> (io/resource (:resource mod))
                      io/input-stream
                      .readAllBytes)
        comp-bytes (-> (io/resource (:resource comp))
                       io/input-stream
                       .readAllBytes)
        sha (fn [^bytes b]
              (let [md (java.security.MessageDigest/getInstance "SHA-256")]
                (.update md b)
                (apply str (map #(format "%02x" %) (.digest md)))))]
    (is (= :wasm-module (:artifact-kind mod)))
    (is (= :wasm-component (:artifact-kind comp)))
    (is (false? (:fixture? mod)))
    (is (false? (:fixture? comp)))
    (is (= :ops-network (:class mod)))
    (is (= :kotoba-compiler/v1 (get-in mod [:source :builder])))
    (is (= :kotoba-compiler/v1 (get-in comp [:source :builder])))
    (is (= (:sha256 mod) (sha mod-bytes)))
    (is (= (:sha256 comp) (sha comp-bytes)))
    (is (contains? (:exports mod) "http_post_bounds_ok"))
    ;; source.kotoba present on classpath
    (is (some? (io/resource "kotoba/lang/wasm-packages/src/http_post_bounds.kotoba")))))

(deftest http-compiler-aot-bounds-live-behavior
  (let [bytes (-> (io/resource "kotoba/lang/wasm-packages/http-post-bounds-v1.wasm")
                  io/input-stream
                  .readAllBytes)
        ;; Chicory if available, else skip with note — use java wasm via process node
        ]
    ;; Prefer Node WebAssembly for zero extra deps (matches pure i64 ABI).
    (let [tmp (java.io.File/createTempFile "http-bounds" ".wasm")
          _ (java.nio.file.Files/write (.toPath tmp) bytes
                                       (into-array java.nio.file.OpenOption []))
          script (str "const b=require('fs').readFileSync(" (pr-str (.getAbsolutePath tmp)) ");"
                      "WebAssembly.instantiate(b).then(({instance})=>{"
                      "const f=instance.exports.http_post_bounds_ok;"
                      "const out=["
                      "Number(f(100n,1n,10n,1000n)),"
                      "Number(f(0n,1n,10n,1000n)),"
                      "Number(f(5000n,1n,10n,1000n)),"
                      "Number(f(100n,40n,10n,1000n)),"
                      "Number(f(100n,1n,70000n,1000n)),"
                      "Number(f(100n,1n,10n,0n)),"
                      "Number(f(100n,1n,10n,40000n))"
                      "];"
                      "console.log(JSON.stringify(out));"
                      "}).catch(e=>{console.error(e); process.exit(1);});")
          pb (ProcessBuilder. ["node" "-e" script])
          p (.start pb)
          out (slurp (.getInputStream p))
          err (slurp (.getErrorStream p))
          code (.waitFor p)]
      (.delete tmp)
      (is (zero? code) (str "node failed: " err out))
      (is (= [0 -1 -1 -2 -3 -4 -4] (edn/read-string out))))))

(deftest process-compiler-aot-bounds-package-registered
  (let [table (edn/read-string
               (slurp (io/resource "kotoba/lang/wasm-packages/wasm-packages-v1.edn")))
        by-name (into {} (map (juxt :name identity) (:packages table)))
        mod (get by-name :process-spawn-bounds)
        comp (get by-name :process-spawn-bounds-component)
        mod-bytes (-> (io/resource (:resource mod))
                      io/input-stream
                      .readAllBytes)
        comp-bytes (-> (io/resource (:resource comp))
                       io/input-stream
                       .readAllBytes)
        sha (fn [^bytes b]
              (let [md (java.security.MessageDigest/getInstance "SHA-256")]
                (.update md b)
                (apply str (map #(format "%02x" %) (.digest md)))))]
    (is (= :wasm-module (:artifact-kind mod)))
    (is (= :wasm-component (:artifact-kind comp)))
    (is (false? (:fixture? mod)))
    (is (false? (:fixture? comp)))
    (is (= :ops (:class mod)))
    (is (= :kotoba-compiler/v1 (get-in mod [:source :builder])))
    (is (= :kotoba-compiler/v1 (get-in comp [:source :builder])))
    (is (= (:sha256 mod) (sha mod-bytes)))
    (is (= (:sha256 comp) (sha comp-bytes)))
    (is (contains? (:exports mod) "process_spawn_bounds_ok"))
    (is (some? (io/resource "kotoba/lang/wasm-packages/src/process_spawn_bounds.kotoba")))))

(deftest process-compiler-aot-bounds-live-behavior
  (let [bytes (-> (io/resource "kotoba/lang/wasm-packages/process-spawn-bounds-v1.wasm")
                  io/input-stream
                  .readAllBytes)
        tmp (java.io.File/createTempFile "process-bounds" ".wasm")
        _ (java.nio.file.Files/write (.toPath tmp) bytes
                                     (into-array java.nio.file.OpenOption []))
        script (str "const b=require('fs').readFileSync(" (pr-str (.getAbsolutePath tmp)) ");"
                    "WebAssembly.instantiate(b).then(({instance})=>{"
                    "const f=instance.exports.process_spawn_bounds_ok;"
                    "const out=["
                    "Number(f(1n,1n,1n)),"
                    "Number(f(0n,1n,1n)),"
                    "Number(f(33n,1n,1n)),"
                    "Number(f(1n,0n,1n)),"
                    "Number(f(1n,70000n,1n)),"
                    "Number(f(1n,1n,0n)),"
                    "Number(f(1n,1n,700000n)),"
                    "Number(f(32n,65536n,600000n))"
                    "];"
                    "console.log(JSON.stringify(out));"
                    "}).catch(e=>{console.error(e); process.exit(1);});")
        pb (ProcessBuilder. ["node" "-e" script])
        p (.start pb)
        out (slurp (.getInputStream p))
        err (slurp (.getErrorStream p))
        code (.waitFor p)]
    (.delete tmp)
    (is (zero? code) (str "node failed: " err out))
    (is (= [0 -1 -2 -3 -3 -4 -4 0] (edn/read-string out)))))
