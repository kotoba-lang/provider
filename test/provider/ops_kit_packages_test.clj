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

(deftest compiler-aot-numeric-bounds-packages-registered
  "ADR 0172: process/entropy/git compiler-AOT pure bounds packages."
  (let [table (edn/read-string
               (slurp (io/resource "kotoba/lang/wasm-packages/wasm-packages-v1.edn")))
        by-name (into {} (map (juxt :name identity) (:packages table)))
        sha (fn [^bytes b]
              (let [md (java.security.MessageDigest/getInstance "SHA-256")]
                (.update md b)
                (apply str (map #(format "%02x" %) (.digest md)))))
        check (fn [mod-name comp-name export]
                (let [mod (get by-name mod-name)
                      comp (get by-name comp-name)
                      mod-bytes (-> (io/resource (:resource mod)) io/input-stream .readAllBytes)
                      comp-bytes (-> (io/resource (:resource comp)) io/input-stream .readAllBytes)]
                  (is (= :wasm-module (:artifact-kind mod)))
                  (is (= :wasm-component (:artifact-kind comp)))
                  (is (false? (:fixture? mod)))
                  (is (= :kotoba-compiler/v1 (get-in mod [:source :builder])))
                  (is (= (:sha256 mod) (sha mod-bytes)))
                  (is (= (:sha256 comp) (sha comp-bytes)))
                  (is (contains? (:exports mod) export))))]
    (check :process-spawn-bounds :process-spawn-bounds-component "process_spawn_bounds_ok")
    (check :entropy-draw-bounds :entropy-draw-bounds-component "entropy_draw_ok")
    (check :git-run-bounds :git-run-bounds-component "git_run_bounds_ok")
    (is (some? (io/resource "kotoba/lang/wasm-packages/src/process_spawn_bounds.kotoba")))
    (is (some? (io/resource "kotoba/lang/wasm-packages/src/entropy_draw_bounds.kotoba")))
    (is (some? (io/resource "kotoba/lang/wasm-packages/src/git_run_bounds.kotoba")))))

(deftest compiler-aot-numeric-bounds-live-behavior
  (let [run (fn [resource export cases]
              (let [bytes (-> (io/resource resource) io/input-stream .readAllBytes)
                    tmp (java.io.File/createTempFile "bounds" ".wasm")
                    _ (java.nio.file.Files/write (.toPath tmp) bytes
                                                 (into-array java.nio.file.OpenOption []))
                    args-js (clojure.string/join ","
                              (map (fn [args]
                                     (str "Number(f("
                                          (clojure.string/join "," (map #(str % "n") args))
                                          "))"))
                                   cases))
                    script (str "const b=require('fs').readFileSync(" (pr-str (.getAbsolutePath tmp)) ");"
                                "WebAssembly.instantiate(b).then(({instance})=>{"
                                "const f=instance.exports." export ";"
                                "console.log(JSON.stringify([" args-js "]));"
                                "}).catch(e=>{console.error(e); process.exit(1);});")
                    pb (ProcessBuilder. ["node" "-e" script])
                    p (.start pb)
                    out (slurp (.getInputStream p))
                    err (slurp (.getErrorStream p))
                    code (.waitFor p)]
                (.delete tmp)
                (is (zero? code) (str resource " node failed: " err out))
                (edn/read-string out)))]
    (is (= [0 -1 -2 -3 -3 -4 -4]
           (run "kotoba/lang/wasm-packages/process-spawn-bounds-v1.wasm"
                "process_spawn_bounds_ok"
                [[1 100 1000] [0 100 1000] [40 100 1000]
                 [1 0 1000] [1 70000 1000] [1 100 0] [1 100 700000]])))
    (is (= [0 0 0 -1 -1 -1]
           (run "kotoba/lang/wasm-packages/entropy-draw-bounds-v1.wasm"
                "entropy_draw_ok"
                [[1] [32] [64] [0] [65] [-1]])))
    (is (= [0 -1 -2 -3 -3 -4 -4]
           (run "kotoba/lang/wasm-packages/git-run-bounds-v1.wasm"
                "git_run_bounds_ok"
                [[1 100 1000] [0 100 1000] [80 100 1000]
                 [1 0 1000] [1 70000 1000] [1 100 0] [1 100 700000]])))))

(deftest secret-compiler-aot-name-len-package-registered
  (let [table (edn/read-string
               (slurp (io/resource "kotoba/lang/wasm-packages/wasm-packages-v1.edn")))
        by-name (into {} (map (juxt :name identity) (:packages table)))
        mod (get by-name :secret-name-len)
        comp (get by-name :secret-name-len-component)
        mod-bytes (-> (io/resource (:resource mod)) io/input-stream .readAllBytes)
        comp-bytes (-> (io/resource (:resource comp)) io/input-stream .readAllBytes)
        sha (fn [^bytes b]
              (let [md (java.security.MessageDigest/getInstance "SHA-256")]
                (.update md b)
                (apply str (map #(format "%02x" %) (.digest md)))))]
    (is (= :wasm-module (:artifact-kind mod)))
    (is (= :wasm-component (:artifact-kind comp)))
    (is (false? (:fixture? mod)))
    (is (= :ops-network (:class mod)))
    (is (= :kotoba-compiler/v1 (get-in mod [:source :builder])))
    (is (= (:sha256 mod) (sha mod-bytes)))
    (is (= (:sha256 comp) (sha comp-bytes)))
    (is (contains? (:exports mod) "secret_name_len_ok"))
    (is (some? (io/resource "kotoba/lang/wasm-packages/src/secret_name_len.kotoba")))))

(deftest secret-compiler-aot-name-len-live-behavior
  (let [bytes (-> (io/resource "kotoba/lang/wasm-packages/secret-name-len-v1.wasm")
                  io/input-stream .readAllBytes)
        tmp (java.io.File/createTempFile "secret-len" ".wasm")
        _ (java.nio.file.Files/write (.toPath tmp) bytes
                                     (into-array java.nio.file.OpenOption []))
        script (str "const b=require('fs').readFileSync(" (pr-str (.getAbsolutePath tmp)) ");"
                    "WebAssembly.instantiate(b).then(({instance})=>{"
                    "const f=instance.exports.secret_name_len_ok;"
                    "const out=[Number(f(0n)),Number(f(1n)),Number(f(128n)),Number(f(129n))];"
                    "console.log(JSON.stringify(out));"
                    "}).catch(e=>{console.error(e); process.exit(1);});")
        p (.start (ProcessBuilder. ["node" "-e" script]))
        out (slurp (.getInputStream p))
        err (slurp (.getErrorStream p))
        code (.waitFor p)]
    (.delete tmp)
    (is (zero? code) (str "node failed: " err out))
    (is (= [-1 0 0 -2] (edn/read-string out)))))

(deftest compiler-aot-secret-value-scoped-fs-length-packages
  "ADR 0174: secret value-len + scoped-fs path/value length."
  (let [table (edn/read-string
               (slurp (io/resource "kotoba/lang/wasm-packages/wasm-packages-v1.edn")))
        by-name (into {} (map (juxt :name identity) (:packages table)))
        sha (fn [^bytes b]
              (let [md (java.security.MessageDigest/getInstance "SHA-256")]
                (.update md b)
                (apply str (map #(format "%02x" %) (.digest md)))))
        check (fn [mod-name comp-name exports]
                (let [mod (get by-name mod-name)
                      comp (get by-name comp-name)
                      mod-bytes (-> (io/resource (:resource mod)) io/input-stream .readAllBytes)
                      comp-bytes (-> (io/resource (:resource comp)) io/input-stream .readAllBytes)]
                  (is (some? mod) (str mod-name))
                  (is (= :wasm-module (:artifact-kind mod)))
                  (is (= :wasm-component (:artifact-kind comp)))
                  (is (false? (:fixture? mod)))
                  (is (= :kotoba-compiler/v1 (get-in mod [:source :builder])))
                  (is (= (:sha256 mod) (sha mod-bytes)))
                  (is (= (:sha256 comp) (sha comp-bytes)))
                  (doseq [e exports] (is (contains? (:exports mod) e)))))]
    (check :secret-value-len :secret-value-len-component ["secret_value_len_ok"])
    (check :fs-path-len :fs-path-len-component ["fs_path_len_ok" "fs_value_len_ok"])
    (is (some? (io/resource "kotoba/lang/wasm-packages/src/secret_value_len.kotoba")))
    (is (some? (io/resource "kotoba/lang/wasm-packages/src/fs_path_len.kotoba")))))

(deftest compiler-aot-secret-value-scoped-fs-length-live
  (let [run (fn [resource export cases]
              (let [bytes (-> (io/resource resource) io/input-stream .readAllBytes)
                    tmp (java.io.File/createTempFile "len" ".wasm")
                    _ (java.nio.file.Files/write (.toPath tmp) bytes
                                                 (into-array java.nio.file.OpenOption []))
                    args-js (clojure.string/join ","
                              (map (fn [args]
                                     (str "Number(f("
                                          (clojure.string/join "," (map #(str % "n") args))
                                          "))"))
                                   cases))
                    script (str "const b=require('fs').readFileSync(" (pr-str (.getAbsolutePath tmp)) ");"
                                "WebAssembly.instantiate(b).then(({instance})=>{"
                                "const f=instance.exports." export ";"
                                "console.log(JSON.stringify([" args-js "]));"
                                "}).catch(e=>{console.error(e); process.exit(1);});")
                    pb (ProcessBuilder. ["node" "-e" script])
                    p (.start pb)
                    out (slurp (.getInputStream p))
                    err (slurp (.getErrorStream p))
                    code (.waitFor p)]
                (.delete tmp)
                (is (zero? code) (str resource " " export " failed: " err out))
                (edn/read-string out)))]
    (is (= [0 0 -2 -1]
           (run "kotoba/lang/wasm-packages/secret-value-len-v1.wasm"
                "secret_value_len_ok" [[0] [8192] [8193] [-1]])))
    (is (= [0 -1 0 -2]
           (run "kotoba/lang/wasm-packages/fs-path-len-v1.wasm"
                "fs_path_len_ok" [[1] [0] [1024] [1025]])))
    (is (= [0 0 -2 -1]
           (run "kotoba/lang/wasm-packages/fs-path-len-v1.wasm"
                "fs_value_len_ok" [[0] [65536] [65537] [-1]])))))
