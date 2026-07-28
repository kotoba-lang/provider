(ns provider-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
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
            [provider.object-transport :as object-transport]
            [provider.state :as state]
            [provider.storage]
            [provider.storage-transport]
            [provider.ui]
            [provider.scoped-fs :as scoped-fs]
            [provider.scoped-fs-transport :as scoped-fs-transport]
            [provider.process :as process]
            [provider.process-transport :as process-transport]))

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
  (is (some? (find-ns 'provider.object-transport)) "provider.object-transport must load")
  (is (some? (find-ns 'provider.state)) "provider.state must load")
  (is (some? (find-ns 'provider.storage)) "provider.storage must load")
  (is (some? (find-ns 'provider.storage-transport)) "provider.storage-transport must load")
  (is (some? (find-ns 'provider.ui)) "provider.ui must load")
  (is (some? (find-ns 'provider.scoped-fs)) "provider.scoped-fs must load")
  (is (some? (find-ns 'provider.scoped-fs-transport)) "provider.scoped-fs-transport must load")
  (is (some? (find-ns 'provider.process)) "provider.process must load")
  (is (some? (find-ns 'provider.process-transport)) "provider.process-transport must load"))

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

(deftest object-get-stream-open-stream-progressive-push
  "ADR 0126: {:open-stream true}; host enqueues then closes."
  (let [ps (:providers (object/create-providers
                        {:allowed-bindings #{:example/blocks}
                         :transport (fn [_] {:open-stream true})}))
        p (get ps object/get-stream-capability-id)
        task ((:invoke p) [object/get-stream-request-type :example/blocks "k"])
        stream (:stream (value/task-poll task))
        p0 (value/stream-read! stream 100)
        a (byte-array [10 11])
        _ (value/stream-enqueue! stream a)
        c1 (value/stream-read! stream 100)
        _ (value/stream-close! stream)
        done (value/stream-read! stream 100)]
    (is (true? (:pending? p0)))
    (is (= [10 11] (vec (:bytes c1))))
    (is (false? (:done? c1)))
    (is (true? (:done? done)))))

(deftest object-transport-resolve-endpoint-requires-host-config
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":endpoint"
                        (object-transport/resolve-endpoint {})))
  (is (= "https://obj.example.test"
         (object-transport/resolve-endpoint {:endpoint "https://obj.example.test"}))))

(deftest object-transport-request-body-shapes
  (is (= {"operation" "get-stream" "binding" "example/blocks" "key" "k1"}
         (#'object-transport/request-body
          {:operation :get-stream :binding :example/blocks :key "k1"})))
  (let [body (#'object-transport/request-body
              {:operation :put-block
               :binding :example/blocks
               :digest "sha256:abc"
               :bytes (byte-array [1 2 3])})]
    (is (= "put-block" (get body "operation")))
    (is (string? (get body "bytes_base64")))
    (is (seq (get body "bytes_base64")))))


(deftest scoped-fs-resolve-path-rejects-escapes
  (is (= {:ok "a/b"} (scoped-fs/resolve-path "a/b")))
  (is (= {:ok "a/b"} (scoped-fs/resolve-path "a//b/")))
  (is (= :fs/absolute (:error (scoped-fs/resolve-path "/etc/passwd"))))
  (is (= :fs/home-escape (:error (scoped-fs/resolve-path "~/secret"))))
  (is (= :fs/escape (:error (scoped-fs/resolve-path "a/../b"))))
  (is (= :fs/escape (:error (scoped-fs/resolve-path "a/./b"))))
  (is (= :fs/empty-path (:error (scoped-fs/resolve-path ""))))
  (is (= :fs/backslash (:error (scoped-fs/resolve-path "a\\b")))))

(deftest scoped-fs-mem-store-read-write-roundtrip
  (let [store (scoped-fs/mem-store {:cache/tmp {"hello.txt" "hi"}})
        ps (:providers (scoped-fs/create-providers
                        {:allowed-roots #{:cache/tmp}
                         :store store}))
        p (get ps scoped-fs/capability-id)
        read-req [scoped-fs/request-type :read
                  [scoped-fs/read-request-type :cache/tmp "hello.txt"]]
        write-req [scoped-fs/request-type :write
                   [scoped-fs/write-request-type :cache/tmp "hello.txt" "bye"]]
        found ((:invoke p) read-req)
        written ((:invoke p) write-req)
        found2 ((:invoke p) read-req)
        escape [scoped-fs/request-type :read
                [scoped-fs/read-request-type :cache/tmp "../x"]]]
    (is (= :content (second found)))
    (is (= "hi" (nth found 2)))
    (is (= :written (second written)))
    (is (= "bye" (nth found2 2)))
    (is (= :error (second ((:invoke p) escape))))))

(deftest process-validate-spawn-policy
  (is (nil? (process/validate-spawn ["echo" "hi"] 100 1000 #{"echo"})))
  (is (= :process/empty-argv (process/validate-spawn [] 100 1000 #{"echo"})))
  (is (= :process/not-allowed (process/validate-spawn ["rm" "-rf"] 100 1000 #{"echo"})))
  (is (= :process/path-command (process/validate-spawn ["/bin/echo" "x"] 100 1000 #{"echo"})))
  (is (= :process/bad-max-stdout (process/validate-spawn ["echo"] 0 1000 #{"echo"}))))

(deftest process-echo-transport-roundtrip
  (let [ps (:providers (process/create-providers
                        {:allowed-commands #{"echo"}
                         :spawn (process/echo-transport)}))
        p (get ps process/capability-id)
        reply ((:invoke p) [process/spawn-request-type ["echo" "a" "b"] 1024 5000])]
    (is (= :ok (second reply)))
    (let [[_ _exit stdout _stderr] (nth reply 2)]
      (is (= 0 _exit))
      (is (= "a b" stdout)))))


(deftest process-transport-resolve-binary-no-path-scan
  (is (= "/bin/echo" (process-transport/resolve-binary {"echo" "/bin/echo"} "echo")))
  (is (nil? (process-transport/resolve-binary {"echo" "/bin/echo"} "rm")))
  (is (nil? (process-transport/resolve-binary {} "echo"))))

(deftest process-os-spawn-runs-host-mapped-echo
  (let [echo-bin (let [f (java.io.File. "/bin/echo")]
                   (if (.canExecute f) "/bin/echo" "/usr/bin/echo"))
        spawn (process-transport/os-spawn {:binaries {"echo" echo-bin}})
        ps (:providers (process/create-providers
                        {:allowed-commands #{"echo"}
                         :spawn spawn}))
        p (get ps process/capability-id)
        reply ((:invoke p) [process/spawn-request-type ["echo" "hello-os"] 4096 5000])]
    (is (= :ok (second reply)))
    (let [[_ exit stdout _] (nth reply 2)]
      (is (zero? exit))
      (is (str/includes? stdout "hello-os")))))

(deftest process-os-spawn-rejects-unmapped-binary
  (let [spawn (process-transport/os-spawn {:binaries {"echo" "/bin/echo"}})
        reply (spawn {:argv ["rm" "-rf" "/"] :max-stdout-bytes 100 :timeout-ms 1000})]
    (is (= :error (:tag reply)))
    (is (= :process/no-binary (:code reply)))))

(deftest scoped-fs-under-root-prefix-check
  (is (true? (scoped-fs-transport/under-root? "/var/cache" "/var/cache")))
  (is (true? (scoped-fs-transport/under-root? "/var/cache" "/var/cache/a")))
  (is (false? (scoped-fs-transport/under-root? "/var/cache" "/var/cache2/x")))
  (is (false? (scoped-fs-transport/under-root? "/var/cache" "/etc/passwd"))))

(deftest scoped-fs-os-store-roundtrip-under-temp-root
  (let [dir (doto (java.io.File. (str (System/getProperty "java.io.tmpdir")
                                      "/kotoba-fs-test-"
                                      (System/currentTimeMillis)))
              (.mkdirs))
        store (scoped-fs-transport/os-store {:roots {:cache/tmp dir}})
        ps (:providers (scoped-fs/create-providers
                        {:allowed-roots #{:cache/tmp}
                         :store store}))
        p (get ps scoped-fs/capability-id)
        write-req [scoped-fs/request-type :write
                   [scoped-fs/write-request-type :cache/tmp "nested/hello.txt" "os-hi"]]
        read-req [scoped-fs/request-type :read
                  [scoped-fs/read-request-type :cache/tmp "nested/hello.txt"]]
        escape [scoped-fs/request-type :read
                [scoped-fs/read-request-type :cache/tmp "../outside"]]]
    (is (= :written (second ((:invoke p) write-req))))
    (let [found ((:invoke p) read-req)]
      (is (= :content (second found)))
      (is (= "os-hi" (nth found 2))))
    (is (= :error (second ((:invoke p) escape))))
    ;; cleanup
    (doseq [f (reverse (file-seq dir))]
      (.delete f))))
