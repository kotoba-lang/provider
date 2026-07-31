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

(deftest scoped-fs-compiler-aot-path-len-package-registered
  (let [table (edn/read-string
               (slurp (io/resource "kotoba/lang/wasm-packages/wasm-packages-v1.edn")))
        by-name (into {} (map (juxt :name identity) (:packages table)))
        mod (get by-name :fs-path-len)
        comp (get by-name :fs-path-len-component)
        mod-bytes (-> (io/resource (:resource mod)) io/input-stream .readAllBytes)
        comp-bytes (-> (io/resource (:resource comp)) io/input-stream .readAllBytes)
        sha (fn [^bytes b]
              (let [md (java.security.MessageDigest/getInstance "SHA-256")]
                (.update md b)
                (apply str (map #(format "%02x" %) (.digest md)))))]
    (is (= :wasm-module (:artifact-kind mod)))
    (is (= :wasm-component (:artifact-kind comp)))
    (is (false? (:fixture? mod)))
    (is (= :ops (:class mod)))
    (is (= :kotoba-compiler/v1 (get-in mod [:source :builder])))
    (is (= (:sha256 mod) (sha mod-bytes)))
    (is (= (:sha256 comp) (sha comp-bytes)))
    (is (contains? (:exports mod) "fs_path_len_ok"))
    (is (some? (io/resource "kotoba/lang/wasm-packages/src/fs_path_len.kotoba")))))

(deftest scoped-fs-compiler-aot-path-len-live-behavior
  (let [bytes (-> (io/resource "kotoba/lang/wasm-packages/fs-path-len-v1.wasm")
                  io/input-stream .readAllBytes)
        tmp (java.io.File/createTempFile "fs-path-len" ".wasm")
        _ (java.nio.file.Files/write (.toPath tmp) bytes
                                     (into-array java.nio.file.OpenOption []))
        script (str "const b=require('fs').readFileSync(" (pr-str (.getAbsolutePath tmp)) ");"
                    "WebAssembly.instantiate(b).then(({instance})=>{"
                    "const f=instance.exports.fs_path_len_ok;"
                    "const out=[Number(f(0n)),Number(f(1n)),Number(f(1024n)),Number(f(1025n))];"
                    "console.log(JSON.stringify(out));"
                    "}).catch(e=>{console.error(e); process.exit(1);});")
        p (.start (ProcessBuilder. ["node" "-e" script]))
        out (slurp (.getInputStream p))
        err (slurp (.getErrorStream p))
        code (.waitFor p)]
    (.delete tmp)
    (is (zero? code) (str "node failed: " err out))
    (is (= [-1 0 0 -2] (edn/read-string out)))))

(deftest compiler-aot-value-length-packages
  "ADR 0175: secret/fs value-length pure bounds."
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
                  (is (some? mod) (str mod-name))
                  (is (= :kotoba-compiler/v1 (get-in mod [:source :builder])))
                  (is (= (:sha256 mod) (sha mod-bytes)))
                  (is (= (:sha256 comp) (sha comp-bytes)))
                  (is (contains? (:exports mod) export))))]
    (check :secret-value-len :secret-value-len-component "secret_value_len_ok")
    (check :fs-value-len :fs-value-len-component "fs_value_len_ok")))

(deftest compiler-aot-value-length-live
  (let [run (fn [resource export cases]
              (let [bytes (-> (io/resource resource) io/input-stream .readAllBytes)
                    tmp (java.io.File/createTempFile "vlen" ".wasm")
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
                (is (zero? code) (str err out))
                (edn/read-string out)))]
    (is (= [0 0 -2 -1]
           (run "kotoba/lang/wasm-packages/secret-value-len-v1.wasm"
                "secret_value_len_ok" [[0] [8192] [8193] [-1]])))
    (is (= [0 0 -2 -1]
           (run "kotoba/lang/wasm-packages/fs-value-len-v1.wasm"
                "fs_value_len_ok" [[0] [65536] [65537] [-1]])))))

(deftest secret-compiler-aot-char-class-package-registered
  (let [table (edn/read-string
               (slurp (io/resource "kotoba/lang/wasm-packages/wasm-packages-v1.edn")))
        by-name (into {} (map (juxt :name identity) (:packages table)))
        mod (get by-name :secret-name-char)
        comp (get by-name :secret-name-char-component)
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
    (is (contains? (:exports mod) "secret_name_char_ok"))
    (is (some? (io/resource "kotoba/lang/wasm-packages/src/secret_name_char.kotoba")))))

(deftest secret-compiler-aot-char-class-live-behavior
  (let [bytes (-> (io/resource "kotoba/lang/wasm-packages/secret-name-char-v1.wasm")
                  io/input-stream .readAllBytes)
        tmp (java.io.File/createTempFile "secret-char" ".wasm")
        _ (java.nio.file.Files/write (.toPath tmp) bytes
                                     (into-array java.nio.file.OpenOption []))
        script (str "const b=require('fs').readFileSync(" (pr-str (.getAbsolutePath tmp)) ");"
                    "WebAssembly.instantiate(b).then(({instance})=>{"
                    "const f=instance.exports.secret_name_char_ok;"
                    "const codes=[65,0,9,10,32,42,47,63,92,97];"
                    "const out=codes.map(c=>Number(f(BigInt(c))));"
                    "console.log(JSON.stringify(out));"
                    "}).catch(e=>{console.error(e); process.exit(1);});")
        p (.start (ProcessBuilder. ["node" "-e" script]))
        out (slurp (.getInputStream p))
        err (slurp (.getErrorStream p))
        code (.waitFor p)]
    (.delete tmp)
    (is (zero? code) (str "node failed: " err out))
    (is (= [0 -3 -3 -3 -3 -3 -3 -3 -3 0] (edn/read-string out)))))

(deftest compiler-aot-fs-path-gate-packages
  "ADR 0177: pure scoped-fs path state-machine gates."
  (let [table (edn/read-string
               (slurp (io/resource "kotoba/lang/wasm-packages/wasm-packages-v1.edn")))
        by-name (into {} (map (juxt :name identity) (:packages table)))
        sha (fn [^bytes b]
              (let [md (java.security.MessageDigest/getInstance "SHA-256")]
                (.update md b)
                (apply str (map #(format "%02x" %) (.digest md)))))
        mod (get by-name :fs-path-gate)
        comp (get by-name :fs-path-gate-component)
        mod-bytes (-> (io/resource (:resource mod)) io/input-stream .readAllBytes)
        comp-bytes (-> (io/resource (:resource comp)) io/input-stream .readAllBytes)]
    (is (some? mod))
    (is (= :kotoba-compiler/v1 (get-in mod [:source :builder])))
    (is (= (:sha256 mod) (sha mod-bytes)))
    (is (= (:sha256 comp) (sha comp-bytes)))
    (doseq [e ["fs_path_first_ok" "fs_path_step" "fs_path_finish"]]
      (is (contains? (:exports mod) e)))
    (is (some? (io/resource "kotoba/lang/wasm-packages/src/fs_path_gate.kotoba")))))

(deftest compiler-aot-fs-path-gate-live
  (let [bytes (-> (io/resource "kotoba/lang/wasm-packages/fs-path-gate-v1.wasm")
                  io/input-stream .readAllBytes)
        tmp (java.io.File/createTempFile "fsgate" ".wasm")
        _ (java.nio.file.Files/write (.toPath tmp) bytes
                                     (into-array java.nio.file.OpenOption []))
        script (str
                "const b=require('fs').readFileSync(" (pr-str (.getAbsolutePath tmp)) ");"
                "WebAssembly.instantiate(b).then(({instance})=>{"
                "const first=instance.exports.fs_path_first_ok;"
                "const step=instance.exports.fs_path_step;"
                "const finish=instance.exports.fs_path_finish;"
                "const N=x=>Number(x);"
                "const walk=s=>{const bytes=[...Buffer.from(s)];"
                "const f=N(first(BigInt(bytes[0]))); if(f!==0) return f;"
                "let st=4n; for(const c of bytes){st=step(st,BigInt(c)); if(N(st)<0) return N(st);} return N(finish(st));};"
                "console.log(JSON.stringify(["
                "N(first(47n)),N(first(126n)),N(first(97n)),"
                "walk('a'),walk('a/b'),walk('a\\0b'),walk('a\\\\b'),"
                "walk('.'),walk('..'),walk('a/../b'),walk('a/b/c')"
                "]));"
                "}).catch(e=>{console.error(e); process.exit(1);});")
        pb (ProcessBuilder. ["node" "-e" script])
        p (.start pb)
        out (slurp (.getInputStream p))
        err (slurp (.getErrorStream p))
        code (.waitFor p)]
    (.delete tmp)
    (is (zero? code) (str err out))
    (is (= [-5 -6 0 0 0 -3 -4 -7 -7 -7 0] (edn/read-string out)))))

(deftest secret-typed-string-name-ok-package-registered
  (let [table (edn/read-string
               (slurp (io/resource "kotoba/lang/wasm-packages/wasm-packages-v1.edn")))
        by-name (into {} (map (juxt :name identity) (:packages table)))
        mod (get by-name :secret-name-ok)
        mod-bytes (-> (io/resource (:resource mod)) io/input-stream .readAllBytes)
        sha (fn [^bytes b]
              (let [md (java.security.MessageDigest/getInstance "SHA-256")]
                (.update md b)
                (apply str (map #(format "%02x" %) (.digest md)))))]
    (is (= :wasm-module (:artifact-kind mod)))
    (is (false? (:fixture? mod)))
    (is (= :ops-network (:class mod)))
    (is (= :kotoba-compiler/v1 (get-in mod [:source :builder])))
    (is (= :kotoba.typed (get-in mod [:source :typed-host])))
    (is (= (:sha256 mod) (sha mod-bytes)))
    (is (contains? (:exports mod) "secret_name_ok"))
    (is (contains? (:exports mod) "main"))
    (is (contains? (:imports mod) "kotoba:typed"))
    (is (some? (io/resource "kotoba/lang/wasm-packages/src/secret_name_ok.kotoba")))))

(deftest secret-typed-string-name-ok-live-browser-host-optional
  (let [host (or (System/getenv "KOTOBA_BROWSER_HOST")
                 ;; sibling monorepo layout /tmp worktree may not have it
                 (let [cand (io/file ".." "compiler" "runtime" "browser-host.mjs")]
                   (when (.exists cand) (.getAbsolutePath cand))))
        wasm (.getAbsolutePath
              (io/file "resources/kotoba/lang/wasm-packages/secret-name-ok-v1.wasm"))]
    (if-not (and host (.exists (io/file host)) (.exists (io/file wasm)))
      (is true "skip typed live when browser-host unavailable")
      (let [script (str "import { readFileSync } from 'fs';"
                        "import { instantiateKotoba } from "
                        (pr-str (str "file://" host))
                        ";"
                        "const h=await instantiateKotoba(readFileSync(" (pr-str wasm) "));"
                        "const v=h.instance.exports.main();"
                        "if(v!==-130n){console.error('got',v); process.exit(2);}"
                        "console.log(JSON.stringify([-130]));")
            pb (doto (ProcessBuilder. ["node" "--input-type=module" "-e" script])
                 (.redirectErrorStream true))
            p (.start pb)
            out (slurp (.getInputStream p))
            code (.waitFor p)]
        (is (zero? code) (str "browser-host live failed: " out))
        (is (= [-130] (edn/read-string out)))))))

(deftest compiler-aot-secret-name-walk-packages
  "ADR 0179: pure multi-step secret name walk."
  (let [table (edn/read-string
               (slurp (io/resource "kotoba/lang/wasm-packages/wasm-packages-v1.edn")))
        by-name (into {} (map (juxt :name identity) (:packages table)))
        sha (fn [^bytes b]
              (let [md (java.security.MessageDigest/getInstance "SHA-256")]
                (.update md b)
                (apply str (map #(format "%02x" %) (.digest md)))))
        mod (get by-name :secret-name-walk)
        comp (get by-name :secret-name-walk-component)
        mod-bytes (-> (io/resource (:resource mod)) io/input-stream .readAllBytes)
        comp-bytes (-> (io/resource (:resource comp)) io/input-stream .readAllBytes)]
    (is (some? mod))
    (is (= :kotoba-compiler/v1 (get-in mod [:source :builder])))
    (is (= (:sha256 mod) (sha mod-bytes)))
    (is (= (:sha256 comp) (sha comp-bytes)))
    (doseq [e ["secret_name_begin" "secret_name_next" "secret_name_end"]]
      (is (contains? (:exports mod) e)))
    (is (some? (io/resource "kotoba/lang/wasm-packages/src/secret_name_walk.kotoba")))))

(deftest compiler-aot-secret-name-walk-live
  (let [bytes (-> (io/resource "kotoba/lang/wasm-packages/secret-name-walk-v1.wasm")
                  io/input-stream .readAllBytes)
        tmp (java.io.File/createTempFile "snwalk" ".wasm")
        _ (java.nio.file.Files/write (.toPath tmp) bytes
                                     (into-array java.nio.file.OpenOption []))
        script (str
                "const b=require('fs').readFileSync(" (pr-str (.getAbsolutePath tmp)) ");"
                "WebAssembly.instantiate(b).then(({instance})=>{"
                "const begin=instance.exports.secret_name_begin;"
                "const next=instance.exports.secret_name_next;"
                "const end=instance.exports.secret_name_end;"
                "const N=x=>Number(x);"
                "const walk=s=>{let st=begin(BigInt(Buffer.byteLength(s))); if(N(st)<0) return N(st);"
                "for(const c of Buffer.from(s)){st=next(st,BigInt(c)); if(N(st)<0) return N(st);} return N(end(st));};"
                "console.log(JSON.stringify([walk(''),walk('api-key'),walk('x'.repeat(129)),walk('a/b'),walk('a*b')]));"
                "}).catch(e=>{console.error(e); process.exit(1);});")
        pb (ProcessBuilder. ["node" "-e" script])
        p (.start pb)
        out (slurp (.getInputStream p))
        err (slurp (.getErrorStream p))
        code (.waitFor p)]
    (.delete tmp)
    (is (zero? code) (str err out))
    (is (= [-1 0 -2 -3 -3] (edn/read-string out)))))

(deftest fs-typed-string-path-ok-package-registered
  (let [table (edn/read-string
               (slurp (io/resource "kotoba/lang/wasm-packages/wasm-packages-v1.edn")))
        by-name (into {} (map (juxt :name identity) (:packages table)))
        mod (get by-name :fs-path-ok)
        mod-bytes (-> (io/resource (:resource mod)) io/input-stream .readAllBytes)
        sha (fn [^bytes b]
              (let [md (java.security.MessageDigest/getInstance "SHA-256")]
                (.update md b)
                (apply str (map #(format "%02x" %) (.digest md)))))]
    (is (some? mod))
    (is (= :wasm-module (:artifact-kind mod)))
    (is (false? (:fixture? mod)))
    (is (= :ops-network (:class mod)))
    (is (= :kotoba-compiler/v1 (get-in mod [:source :builder])))
    (is (= :kotoba.typed (get-in mod [:source :typed-host])))
    (is (= (:sha256 mod) (sha mod-bytes)))
    (is (contains? (:exports mod) "fs_path_ok"))
    (is (contains? (:exports mod) "main"))
    (is (contains? (:imports mod) "kotoba:typed"))
    (is (some? (io/resource "kotoba/lang/wasm-packages/src/fs_path_ok.kotoba")))))

(deftest fs-typed-string-path-ok-live-browser-host-optional
  (let [host (or (System/getenv "KOTOBA_BROWSER_HOST")
                 (let [cand (io/file ".." "compiler" "runtime" "browser-host.mjs")]
                   (when (.exists cand) (.getAbsolutePath cand)))
                 (let [cand (io/file "/Users/junkawasaki/github/com-junkawasaki/orgs/kotoba-lang/compiler/runtime/browser-host.mjs")]
                   (when (.exists cand) (.getAbsolutePath cand))))
        wasm (.getAbsolutePath
              (io/file "resources/kotoba/lang/wasm-packages/fs-path-ok-v1.wasm"))]
    (if-not (and host (.exists (io/file host)) (.exists (io/file wasm)))
      (is true "skip typed live when browser-host unavailable")
      (let [script (str "import { readFileSync } from 'fs';"
                        "import { instantiateKotoba } from "
                        (pr-str (str "file://" host))
                        ";"
                        "const h=await instantiateKotoba(readFileSync(" (pr-str wasm) "));"
                        "const v=h.instance.exports.main();"
                        "if(v!==-15470n){console.error('got',v); process.exit(2);}"
                        "console.log(JSON.stringify([-15470]));")
            pb (doto (ProcessBuilder. ["node" "--input-type=module" "-e" script])
                 (.redirectErrorStream true))
            p (.start pb)
            out (slurp (.getInputStream p))
            code (.waitFor p)]
        (is (zero? code) (str "browser-host live failed: " out))
        (is (= [-15470] (edn/read-string out)))))))

(deftest scoped-fs-multistep-walk-package-registered
  (let [table (edn/read-string
               (slurp (io/resource "kotoba/lang/wasm-packages/wasm-packages-v1.edn")))
        by-name (into {} (map (juxt :name identity) (:packages table)))
        mod (get by-name :fs-path-walk)
        comp (get by-name :fs-path-walk-component)
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
    (is (contains? (:exports mod) "fs_path_begin"))
    (is (contains? (:exports mod) "fs_path_next"))
    (is (contains? (:exports mod) "fs_path_end"))
    (is (some? (io/resource "kotoba/lang/wasm-packages/src/fs_path_walk.kotoba")))))

(deftest scoped-fs-multistep-walk-live-behavior
  (let [bytes (-> (io/resource "kotoba/lang/wasm-packages/fs-path-walk-v1.wasm")
                  io/input-stream .readAllBytes)
        tmp (java.io.File/createTempFile "fs-walk" ".wasm")
        _ (java.nio.file.Files/write (.toPath tmp) bytes
                                     (into-array java.nio.file.OpenOption []))
        script (str
                "const b=require('fs').readFileSync(" (pr-str (.getAbsolutePath tmp)) ");"
                "WebAssembly.instantiate(b).then(({instance})=>{"
                "const {fs_path_begin,fs_path_next,fs_path_end}=instance.exports;"
                "const N=x=>Number(x);"
                "function walk(s){"
                "  let st=N(fs_path_begin(BigInt(s.length)));"
                "  if(st<0) return st;"
                "  for(const ch of s){"
                "    st=N(fs_path_next(BigInt(st), BigInt(ch.charCodeAt(0))));"
                "    if(st<0) return st;"
                "  }"
                "  return N(fs_path_end(BigInt(st)));"
                "}"
                "const cases=['','a','a/b','../x','/a','~a','a\\\\b','ok/path','a/..'];"
                "const out=cases.map(walk);"
                "console.log(JSON.stringify(out));"
                "}).catch(e=>{console.error(e); process.exit(1);});")
        p (.start (ProcessBuilder. ["node" "-e" script]))
        out (slurp (.getInputStream p))
        err (slurp (.getErrorStream p))
        code (.waitFor p)]
    (.delete tmp)
    (is (zero? code) (str "node failed: " err out))
    (is (= [-1 0 0 -7 -5 -6 -4 0 -7] (edn/read-string out)))))

(deftest http-typed-string-url-ok-package-registered
  (let [table (edn/read-string
               (slurp (io/resource "kotoba/lang/wasm-packages/wasm-packages-v1.edn")))
        by-name (into {} (map (juxt :name identity) (:packages table)))
        mod (get by-name :http-url-ok)
        mod-bytes (-> (io/resource (:resource mod)) io/input-stream .readAllBytes)
        sha (fn [^bytes b]
              (let [md (java.security.MessageDigest/getInstance "SHA-256")]
                (.update md b)
                (apply str (map #(format "%02x" %) (.digest md)))))]
    (is (some? mod))
    (is (= :wasm-module (:artifact-kind mod)))
    (is (= :kotoba-compiler/v1 (get-in mod [:source :builder])))
    (is (= :kotoba.typed (get-in mod [:source :typed-host])))
    (is (= (:sha256 mod) (sha mod-bytes)))
    (is (contains? (:exports mod) "http_url_ok"))
    (is (contains? (:exports mod) "main"))
    (is (contains? (:imports mod) "kotoba:typed"))
    (is (some? (io/resource "kotoba/lang/wasm-packages/src/http_url_ok.kotoba")))))

(deftest http-typed-string-url-ok-live-browser-host-optional
  (let [host (or (System/getenv "KOTOBA_BROWSER_HOST")
                 (let [cand (io/file ".." "compiler" "runtime" "browser-host.mjs")]
                   (when (.exists cand) (.getAbsolutePath cand)))
                 (let [cand (io/file "/Users/junkawasaki/github/com-junkawasaki/orgs/kotoba-lang/compiler/runtime/browser-host.mjs")]
                   (when (.exists cand) (.getAbsolutePath cand))))
        wasm (.getAbsolutePath
              (io/file "resources/kotoba/lang/wasm-packages/http-url-ok-v1.wasm"))]
    (if-not (and host (.exists (io/file host)) (.exists (io/file wasm)))
      (is true "skip typed live when browser-host unavailable")
      (let [script (str "import { readFileSync } from 'fs';"
                        "import { instantiateKotoba } from "
                        (pr-str (str "file://" host))
                        ";"
                        "const h=await instantiateKotoba(readFileSync(" (pr-str wasm) "));"
                        "const v=h.instance.exports.main();"
                        "if(v!==-130n){console.error('got',v); process.exit(2);}"
                        "console.log(JSON.stringify([-130]));")
            pb (doto (ProcessBuilder. ["node" "--input-type=module" "-e" script])
                 (.redirectErrorStream true))
            p (.start pb)
            out (slurp (.getInputStream p))
            code (.waitFor p)]
        (is (zero? code) (str "browser-host live failed: " out))
        (is (= [-130] (edn/read-string out)))))))

(deftest process-multistep-walk-package-registered
  (let [table (edn/read-string
               (slurp (io/resource "kotoba/lang/wasm-packages/wasm-packages-v1.edn")))
        by-name (into {} (map (juxt :name identity) (:packages table)))
        mod (get by-name :process-spawn-walk)
        comp (get by-name :process-spawn-walk-component)
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
    (is (contains? (:exports mod) "process_spawn_begin"))
    (is (contains? (:exports mod) "process_spawn_arg"))
    (is (contains? (:exports mod) "process_spawn_end"))
    (is (some? (io/resource "kotoba/lang/wasm-packages/src/process_spawn_walk.kotoba")))))

(deftest process-multistep-walk-live-behavior
  (let [bytes (-> (io/resource "kotoba/lang/wasm-packages/process-spawn-walk-v1.wasm")
                  io/input-stream .readAllBytes)
        tmp (java.io.File/createTempFile "proc-walk" ".wasm")
        _ (java.nio.file.Files/write (.toPath tmp) bytes
                                     (into-array java.nio.file.OpenOption []))
        script (str
                "const b=require('fs').readFileSync(" (pr-str (.getAbsolutePath tmp)) ");"
                "WebAssembly.instantiate(b).then(({instance})=>{"
                "const {process_spawn_begin,process_spawn_arg,process_spawn_end}=instance.exports;"
                "const N=x=>Number(x);"
                "function walk(argc,lens,mo,to){"
                "  let st=N(process_spawn_begin(BigInt(argc)));"
                "  if(st<0) return st;"
                "  for(const L of lens){"
                "    st=N(process_spawn_arg(BigInt(st), BigInt(L)));"
                "    if(st<0) return st;"
                "  }"
                "  return N(process_spawn_end(BigInt(st), BigInt(mo), BigInt(to)));"
                "}"
                "const out=["
                "  walk(3,[1,2,3],100,1000),"
                "  walk(0,[],100,1000),"
                "  walk(33,Array(33).fill(1),100,1000),"
                "  walk(1,[5000],100,1000),"
                "  walk(2,[1],100,1000),"
                "  walk(1,[1,1],100,1000),"
                "  walk(1,[1],100,0),"
                "  walk(1,[1],0,1000)"
                "];"
                "console.log(JSON.stringify(out));"
                "}).catch(e=>{console.error(e); process.exit(1);});")
        p (.start (ProcessBuilder. ["node" "-e" script]))
        out (slurp (.getInputStream p))
        err (slurp (.getErrorStream p))
        code (.waitFor p)]
    (.delete tmp)
    (is (zero? code) (str "node failed: " err out))
    (is (= [0 -1 -2 -6 -7 -5 -4 -3] (edn/read-string out)))))

(deftest compiler-aot-git-run-walk-packages
  "ADR 0184: pure multi-step git run walk."
  (let [table (edn/read-string
               (slurp (io/resource "kotoba/lang/wasm-packages/wasm-packages-v1.edn")))
        by-name (into {} (map (juxt :name identity) (:packages table)))
        sha (fn [^bytes b]
              (let [md (java.security.MessageDigest/getInstance "SHA-256")]
                (.update md b)
                (apply str (map #(format "%02x" %) (.digest md)))))
        mod (get by-name :git-run-walk)
        comp (get by-name :git-run-walk-component)
        mod-bytes (-> (io/resource (:resource mod)) io/input-stream .readAllBytes)
        comp-bytes (-> (io/resource (:resource comp)) io/input-stream .readAllBytes)]
    (is (some? mod))
    (is (= :kotoba-compiler/v1 (get-in mod [:source :builder])))
    (is (= (:sha256 mod) (sha mod-bytes)))
    (is (= (:sha256 comp) (sha comp-bytes)))
    (doseq [e ["git_run_begin" "git_run_arg" "git_run_end"]]
      (is (contains? (:exports mod) e)))
    (is (some? (io/resource "kotoba/lang/wasm-packages/src/git_run_walk.kotoba")))))

(deftest compiler-aot-git-run-walk-live
  (let [bytes (-> (io/resource "kotoba/lang/wasm-packages/git-run-walk-v1.wasm")
                  io/input-stream .readAllBytes)
        tmp (java.io.File/createTempFile "gwalk" ".wasm")
        _ (java.nio.file.Files/write (.toPath tmp) bytes
                                     (into-array java.nio.file.OpenOption []))
        script (str
                "const b=require('fs').readFileSync(" (pr-str (.getAbsolutePath tmp)) ");"
                "WebAssembly.instantiate(b).then(({instance})=>{"
                "const begin=instance.exports.git_run_begin;"
                "const arg=instance.exports.git_run_arg;"
                "const end=instance.exports.git_run_end;"
                "const N=x=>Number(x);"
                "const walk=(argc,lens,mo,to)=>{let st=begin(BigInt(argc)); if(N(st)<0) return N(st);"
                "for(const L of lens){st=arg(st,BigInt(L)); if(N(st)<0) return N(st);} return N(end(st,BigInt(mo),BigInt(to)));};"
                "let st=begin(1n); st=arg(st,3n); const extra=N(arg(st,3n));"
                "console.log(JSON.stringify([walk(2,[3,4],100,1000),walk(0,[],100,1000),walk(65,[1],100,1000),"
                "walk(1,[5000],100,1000),walk(2,[3],100,1000),extra,walk(1,[1],0,1000),walk(1,[1],100,0)]));"
                "}).catch(e=>{console.error(e); process.exit(1);});")
        pb (ProcessBuilder. ["node" "-e" script])
        p (.start pb)
        out (slurp (.getInputStream p))
        err (slurp (.getErrorStream p))
        code (.waitFor p)]
    (.delete tmp)
    (is (zero? code) (str err out))
    (is (= [0 -1 -2 -6 -7 -5 -3 -4] (edn/read-string out)))))

(deftest http-multistep-walk-package-registered
  (let [table (edn/read-string
               (slurp (io/resource "kotoba/lang/wasm-packages/wasm-packages-v1.edn")))
        by-name (into {} (map (juxt :name identity) (:packages table)))
        mod (get by-name :http-post-walk)
        comp (get by-name :http-post-walk-component)
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
    (doseq [x ["http_post_begin" "http_post_url" "http_post_headers"
                "http_post_body" "http_post_end"]]
      (is (contains? (:exports mod) x)))
    (is (some? (io/resource "kotoba/lang/wasm-packages/src/http_post_walk.kotoba")))))

(deftest http-multistep-walk-live-behavior
  (let [bytes (-> (io/resource "kotoba/lang/wasm-packages/http-post-walk-v1.wasm")
                  io/input-stream .readAllBytes)
        tmp (java.io.File/createTempFile "http-walk" ".wasm")
        _ (java.nio.file.Files/write (.toPath tmp) bytes
                                     (into-array java.nio.file.OpenOption []))
        script (str
                "const b=require('fs').readFileSync(" (pr-str (.getAbsolutePath tmp)) ");"
                "WebAssembly.instantiate(b).then(({instance})=>{"
                "const e=instance.exports; const N=x=>Number(x);"
                "function walk(u,h,b,t){"
                "  let s=N(e.http_post_begin());"
                "  s=N(e.http_post_url(BigInt(s),BigInt(u))); if(s<0) return s;"
                "  s=N(e.http_post_headers(BigInt(s),BigInt(h))); if(s<0) return s;"
                "  s=N(e.http_post_body(BigInt(s),BigInt(b))); if(s<0) return s;"
                "  return N(e.http_post_end(BigInt(s),BigInt(t)));"
                "}"
                "const out=[walk(10,2,100,1000),walk(0,0,0,1000),walk(10,40,0,1000),"
                "walk(10,0,70000,1000),walk(10,0,0,0),N(e.http_post_end(0n,1000n))];"
                "console.log(JSON.stringify(out));"
                "}).catch(e=>{console.error(e); process.exit(1);});")
        p (.start (ProcessBuilder. ["node" "-e" script]))
        out (slurp (.getInputStream p))
        err (slurp (.getErrorStream p))
        code (.waitFor p)]
    (.delete tmp)
    (is (zero? code) (str "node failed: " err out))
    (is (= [0 -1 -2 -3 -4 -5] (edn/read-string out)))))

(deftest http-typed-string-request-ok-package-registered
  (let [table (edn/read-string
               (slurp (io/resource "kotoba/lang/wasm-packages/wasm-packages-v1.edn")))
        by-name (into {} (map (juxt :name identity) (:packages table)))
        mod (get by-name :http-post-request-ok)
        mod-bytes (-> (io/resource (:resource mod)) io/input-stream .readAllBytes)
        sha (fn [^bytes b]
              (let [md (java.security.MessageDigest/getInstance "SHA-256")]
                (.update md b)
                (apply str (map #(format "%02x" %) (.digest md)))))]
    (is (some? mod))
    (is (= :wasm-module (:artifact-kind mod)))
    (is (= :kotoba-compiler/v1 (get-in mod [:source :builder])))
    (is (= :kotoba.typed (get-in mod [:source :typed-host])))
    (is (= (:sha256 mod) (sha mod-bytes)))
    (is (contains? (:exports mod) "http_post_request_ok"))
    (is (contains? (:exports mod) "main"))
    (is (contains? (:imports mod) "kotoba:typed"))
    (is (some? (io/resource "kotoba/lang/wasm-packages/src/http_post_request_ok.kotoba")))))

(deftest http-typed-string-request-ok-live-browser-host-optional
  (let [host (or (System/getenv "KOTOBA_BROWSER_HOST")
                 (let [cand (io/file ".." "compiler" "runtime" "browser-host.mjs")]
                   (when (.exists cand) (.getAbsolutePath cand)))
                 (let [cand (io/file "/Users/junkawasaki/github/com-junkawasaki/orgs/kotoba-lang/compiler/runtime/browser-host.mjs")]
                   (when (.exists cand) (.getAbsolutePath cand))))
        wasm (.getAbsolutePath
              (io/file "resources/kotoba/lang/wasm-packages/http-post-request-ok-v1.wasm"))]
    (if-not (and host (.exists (io/file host)) (.exists (io/file wasm)))
      (is true "skip typed live when browser-host unavailable")
      (let [script (str "import { readFileSync } from 'fs';"
                        "import { instantiateKotoba } from "
                        (pr-str (str "file://" host))
                        ";"
                        "const h=await instantiateKotoba(readFileSync(" (pr-str wasm) "));"
                        "const v=h.instance.exports.main();"
                        "if(v!==-13406n){console.error('got',v); process.exit(2);}"
                        "console.log(JSON.stringify([-13406]));")
            pb (doto (ProcessBuilder. ["node" "--input-type=module" "-e" script])
                 (.redirectErrorStream true))
            p (.start pb)
            out (slurp (.getInputStream p))
            code (.waitFor p)]
        (is (zero? code) (str "browser-host live failed: " out))
        (is (= [-13406] (edn/read-string out)))))))

(deftest http-typed-string-header-name-ok-package-registered
  (let [table (edn/read-string
               (slurp (io/resource "kotoba/lang/wasm-packages/wasm-packages-v1.edn")))
        by-name (into {} (map (juxt :name identity) (:packages table)))
        mod (get by-name :http-header-name-ok)
        mod-bytes (-> (io/resource (:resource mod)) io/input-stream .readAllBytes)
        sha (fn [^bytes b]
              (let [md (java.security.MessageDigest/getInstance "SHA-256")]
                (.update md b)
                (apply str (map #(format "%02x" %) (.digest md)))))]
    (is (some? mod))
    (is (= :wasm-module (:artifact-kind mod)))
    (is (= :kotoba-compiler/v1 (get-in mod [:source :builder])))
    (is (= :kotoba.typed (get-in mod [:source :typed-host])))
    (is (= (:sha256 mod) (sha mod-bytes)))
    (is (contains? (:exports mod) "http_header_name_ok"))
    (is (contains? (:exports mod) "main"))
    (is (contains? (:imports mod) "kotoba:typed"))
    (is (some? (io/resource "kotoba/lang/wasm-packages/src/http_header_name_ok.kotoba")))))

(deftest http-typed-string-header-name-ok-live-browser-host-optional
  (let [host (or (System/getenv "KOTOBA_BROWSER_HOST")
                 (let [cand (io/file ".." "compiler" "runtime" "browser-host.mjs")]
                   (when (.exists cand) (.getAbsolutePath cand)))
                 (let [cand (io/file "/Users/junkawasaki/github/com-junkawasaki/orgs/kotoba-lang/compiler/runtime/browser-host.mjs")]
                   (when (.exists cand) (.getAbsolutePath cand))))
        wasm (.getAbsolutePath
              (io/file "resources/kotoba/lang/wasm-packages/http-header-name-ok-v1.wasm"))]
    (if-not (and host (.exists (io/file host)) (.exists (io/file wasm)))
      (is true "skip typed live when browser-host unavailable")
      (let [script (str "import { readFileSync } from 'fs';"
                        "import { instantiateKotoba } from "
                        (pr-str (str "file://" host))
                        ";"
                        "const h=await instantiateKotoba(readFileSync(" (pr-str wasm) "));"
                        "const v=h.instance.exports.main();"
                        "if(v!==-130n){console.error('got',v); process.exit(2);}"
                        "console.log(JSON.stringify([-130]));")
            pb (doto (ProcessBuilder. ["node" "--input-type=module" "-e" script])
                 (.redirectErrorStream true))
            p (.start pb)
            out (slurp (.getInputStream p))
            code (.waitFor p)]
        (is (zero? code) (str "browser-host live failed: " out))
        (is (= [-130] (edn/read-string out)))))))

(deftest http-typed-string-header-value-ok-package-registered
  (let [table (edn/read-string
               (slurp (io/resource "kotoba/lang/wasm-packages/wasm-packages-v1.edn")))
        by-name (into {} (map (juxt :name identity) (:packages table)))
        mod (get by-name :http-header-value-ok)
        mod-bytes (-> (io/resource (:resource mod)) io/input-stream .readAllBytes)
        sha (fn [^bytes b]
              (let [md (java.security.MessageDigest/getInstance "SHA-256")]
                (.update md b)
                (apply str (map #(format "%02x" %) (.digest md)))))]
    (is (some? mod))
    (is (= :wasm-module (:artifact-kind mod)))
    (is (= :kotoba-compiler/v1 (get-in mod [:source :builder])))
    (is (= :kotoba.typed (get-in mod [:source :typed-host])))
    (is (= (:sha256 mod) (sha mod-bytes)))
    (doseq [e ["http_header_value_ok" "http_header_pair_ok" "main"]]
      (is (contains? (:exports mod) e)))
    (is (contains? (:imports mod) "kotoba:typed"))
    (is (some? (io/resource "kotoba/lang/wasm-packages/src/http_header_value_ok.kotoba")))))

(deftest http-typed-string-header-value-ok-live-browser-host-optional
  (let [host (or (System/getenv "KOTOBA_BROWSER_HOST")
                 (let [cand (io/file ".." "compiler" "runtime" "browser-host.mjs")]
                   (when (.exists cand) (.getAbsolutePath cand)))
                 (let [cand (io/file "/Users/junkawasaki/github/com-junkawasaki/orgs/kotoba-lang/compiler/runtime/browser-host.mjs")]
                   (when (.exists cand) (.getAbsolutePath cand))))
        wasm (.getAbsolutePath
              (io/file "resources/kotoba/lang/wasm-packages/http-header-value-ok-v1.wasm"))]
    (if-not (and host (.exists (io/file host)) (.exists (io/file wasm)))
      (is true "skip typed live when browser-host unavailable")
      (let [script (str "import { readFileSync } from 'fs';"
                        "import { instantiateKotoba } from "
                        (pr-str (str "file://" host))
                        ";"
                        "const h=await instantiateKotoba(readFileSync(" (pr-str wasm) "));"
                        "const v=h.instance.exports.main();"
                        "if(v!==-3036n){console.error('got',v); process.exit(2);}"
                        "console.log(JSON.stringify([-3036]));")
            pb (doto (ProcessBuilder. ["node" "--input-type=module" "-e" script])
                 (.redirectErrorStream true))
            p (.start pb)
            out (slurp (.getInputStream p))
            code (.waitFor p)]
        (is (zero? code) (str "browser-host live failed: " out))
        (is (= [-3036] (edn/read-string out)))))))

(deftest http-typed-string-headers-set-ok-package-registered
  (let [table (edn/read-string
               (slurp (io/resource "kotoba/lang/wasm-packages/wasm-packages-v1.edn")))
        by-name (into {} (map (juxt :name identity) (:packages table)))
        mod (get by-name :http-headers-set-ok)
        mod-bytes (-> (io/resource (:resource mod)) io/input-stream .readAllBytes)
        sha (fn [^bytes b]
              (let [md (java.security.MessageDigest/getInstance "SHA-256")]
                (.update md b)
                (apply str (map #(format "%02x" %) (.digest md)))))]
    (is (some? mod))
    (is (= :wasm-module (:artifact-kind mod)))
    (is (= :kotoba-compiler/v1 (get-in mod [:source :builder])))
    (is (= :kotoba.typed (get-in mod [:source :typed-host])))
    (is (= (:sha256 mod) (sha mod-bytes)))
    (doseq [e ["http_headers_begin" "http_headers_pair" "http_headers_end" "main"]]
      (is (contains? (:exports mod) e)))
    (is (contains? (:imports mod) "kotoba:typed"))
    (is (some? (io/resource "kotoba/lang/wasm-packages/src/http_headers_set_ok.kotoba")))))

(deftest http-typed-string-headers-set-ok-live-browser-host-optional
  (let [host (or (System/getenv "KOTOBA_BROWSER_HOST")
                 (let [cand (io/file ".." "compiler" "runtime" "browser-host.mjs")]
                   (when (.exists cand) (.getAbsolutePath cand)))
                 (let [cand (io/file "/Users/junkawasaki/github/com-junkawasaki/orgs/kotoba-lang/compiler/runtime/browser-host.mjs")]
                   (when (.exists cand) (.getAbsolutePath cand))))
        wasm (.getAbsolutePath
              (io/file "resources/kotoba/lang/wasm-packages/http-headers-set-ok-v1.wasm"))]
    (if-not (and host (.exists (io/file host)) (.exists (io/file wasm)))
      (is true "skip typed live when browser-host unavailable")
      (let [script (str "import { readFileSync } from 'fs';"
                        "import { instantiateKotoba } from "
                        (pr-str (str "file://" host))
                        ";"
                        "const h=await instantiateKotoba(readFileSync(" (pr-str wasm) "));"
                        "const v=h.instance.exports.main();"
                        "if(v!==-3647n){console.error('got',v); process.exit(2);}"
                        "console.log(JSON.stringify([-3647]));")
            pb (doto (ProcessBuilder. ["node" "--input-type=module" "-e" script])
                 (.redirectErrorStream true))
            p (.start pb)
            out (slurp (.getInputStream p))
            code (.waitFor p)]
        (is (zero? code) (str "browser-host live failed: " out))
        (is (= [-3647] (edn/read-string out)))))))

(deftest http-typed-string-response-ok-package-registered
  (let [table (edn/read-string
               (slurp (io/resource "kotoba/lang/wasm-packages/wasm-packages-v1.edn")))
        by-name (into {} (map (juxt :name identity) (:packages table)))
        mod (get by-name :http-response-ok)
        mod-bytes (-> (io/resource (:resource mod)) io/input-stream .readAllBytes)
        sha (fn [^bytes b]
              (let [md (java.security.MessageDigest/getInstance "SHA-256")]
                (.update md b)
                (apply str (map #(format "%02x" %) (.digest md)))))]
    (is (some? mod))
    (is (= :wasm-module (:artifact-kind mod)))
    (is (= :kotoba-compiler/v1 (get-in mod [:source :builder])))
    (is (= :kotoba.typed (get-in mod [:source :typed-host])))
    (is (= (:sha256 mod) (sha mod-bytes)))
    (doseq [e ["http_status_ok" "http_response_ok" "main"]]
      (is (contains? (:exports mod) e)))
    (is (contains? (:imports mod) "kotoba:typed"))
    (is (some? (io/resource "kotoba/lang/wasm-packages/src/http_response_ok.kotoba")))))

(deftest http-typed-string-response-ok-live-browser-host-optional
  (let [host (or (System/getenv "KOTOBA_BROWSER_HOST")
                 (let [cand (io/file ".." "compiler" "runtime" "browser-host.mjs")]
                   (when (.exists cand) (.getAbsolutePath cand)))
                 (let [cand (io/file "/Users/junkawasaki/github/com-junkawasaki/orgs/kotoba-lang/compiler/runtime/browser-host.mjs")]
                   (when (.exists cand) (.getAbsolutePath cand))))
        wasm (.getAbsolutePath
              (io/file "resources/kotoba/lang/wasm-packages/http-response-ok-v1.wasm"))]
    (if-not (and host (.exists (io/file host)) (.exists (io/file wasm)))
      (is true "skip typed live when browser-host unavailable")
      (let [script (str "import { readFileSync } from 'fs';"
                        "import { instantiateKotoba } from "
                        (pr-str (str "file://" host))
                        ";"
                        "const h=await instantiateKotoba(readFileSync(" (pr-str wasm) "));"
                        "const v=h.instance.exports.main();"
                        "if(v!==-1012n){console.error('got',v); process.exit(2);}"
                        "console.log(JSON.stringify([-1012]));")
            pb (doto (ProcessBuilder. ["node" "--input-type=module" "-e" script])
                 (.redirectErrorStream true))
            p (.start pb)
            out (slurp (.getInputStream p))
            code (.waitFor p)]
        (is (zero? code) (str "browser-host live failed: " out))
        (is (= [-1012] (edn/read-string out)))))))
