;; nbb socket test: HTTP ingress node:http transport
;;   npm run test-nbb-http-ingress-socket
;;   nbb --classpath "src:$(clojure -Spath)" test/cljs_http_ingress_socket_test.cljs
;;
;; Every case binds port 0 and reads the port the kernel assigned back off
;; the listener -- a hardcoded port is a flake waiting for a busy machine.
;;
;; `promesa.core` is bundled inside the nbb binary itself (like `cljs.core`);
;; it is not a dependency of this repository and adds nothing to a consumer.
(ns cljs-http-ingress-socket-test
  (:require [promesa.core :as p]
            [kotoba.kir.cljs-i64 :as i64]
            [provider.http-ingress :as ingress]
            [provider.http-ingress-transport :as transport]))

(def results (atom []))

(defn- check! [name ok? detail]
  (swap! results conj {:name name :ok? (boolean ok?) :detail (when-not ok? detail)})
  (boolean ok?))

;; --- a minimal node:http client, so the assertions are about a real socket --

(defn- http-req
  "Issue one real request at 127.0.0.1:port. Resolves {:status :headers :body}."
  [{:keys [port method path headers body]}]
  (js/Promise.
   (fn [resolve reject]
     (let [http (js/require "node:http")
           req (.request http
                         #js {:host "127.0.0.1"
                              :port port
                              :path (or path "/")
                              :method (or method "GET")
                              :headers (clj->js (or headers {}))}
                         (fn [res]
                           (let [chunks (atom [])]
                             (.on res "data" (fn [c] (swap! chunks conj c)))
                             (.on res "end"
                                  (fn []
                                    (resolve
                                     {:status (.-statusCode res)
                                      :headers (js->clj (.-headers res))
                                      :body (.toString
                                             (.concat js/Buffer (into-array @chunks))
                                             "utf8")}))))))]
       (.on req "error" reject)
       (when body (.write req body))
       (.end req)))))

(defn- wait-for
  "Poll `pred` every 5ms until true, or reject after `ms`."
  ([pred] (wait-for pred 3000))
  ([pred ms]
   (let [deadline (+ (js/Date.now) ms)]
     (js/Promise.
      (fn [resolve reject]
        (letfn [(tick []
                  (cond
                    (pred) (resolve true)
                    (> (js/Date.now) deadline)
                    (reject (js/Error. "wait-for timed out"))
                    :else (js/setTimeout tick 5)))]
          (tick)))))))

;; --- the guest side: exactly the two typed calls the capability defines ----

(defn- guest-accept [listener]
  ((:invoke (get (:providers listener) ingress/accept-capability-id))
   [ingress/accept-request-type i64/zero]))

(defn- guest-reply
  ([listener status body] (guest-reply listener status body []))
  ([listener status body headers]
   ((:invoke (get (:providers listener) ingress/reply-capability-id))
    [ingress/reply-request-type (js/BigInt status)
     [ingress/header-set-type headers] body])))

;; ---------------------------------------------------------------------------
;; 1. a request arrives, the guest accepts it, replies, and the HTTP client
;;    sees that status and that body
;; ---------------------------------------------------------------------------

(defn- case-round-trip []
  (p/let [listener (transport/start-listener! {})
          port (:port listener)
          ;; p/let awaits every binding, and this request cannot resolve
          ;; until the guest replies -- park it in a vector, which is not a
          ;; thenable, and unwrap it below once the reply has been made.
          inflight [(http-req {:port port :method "POST" :path "/v1/echo?x=1"
                               :headers {"x-trace" "abc"}
                               :body "ping"})]
          _ (wait-for #(pos? (:queued ((:snapshot listener)))))
          accepted (guest-accept listener)
          ;; The value handed to the guest must be the capability's typed
          ;; value and nothing else -- deep equality against a literal is
          ;; what proves no socket, stream or host object rode along.
          [_ some? req] accepted
          [_ method path [_ headers] body] req
          header-map (into {} (map (fn [[_ n v]] [n v])) headers)
          _ (guest-reply listener 201 "pong"
                         [[ingress/header-type :content-type "text/plain"]
                          [ingress/header-type :x-from "guest"]])
          response (first inflight)
          snap ((:snapshot listener))]
    (check! "round-trip/guest-sees-typed-request"
            (and (true? some?)
                 (= :http/post method)
                 (= "/v1/echo?x=1" path)
                 (= "ping" body)
                 (= "abc" (get header-map :x-trace)))
            (pr-str {:accepted accepted}))
    (check! "round-trip/client-sees-guest-status-and-body"
            (and (= 201 (:status response))
                 (= "pong" (:body response))
                 (= "guest" (get (:headers response) "x-from"))
                 (= "text/plain" (get (:headers response) "content-type"))
                 ;; host owns framing: content-length is computed here, not
                 ;; taken from the guest
                 (= "4" (get (:headers response) "content-length")))
            (pr-str response))
    (check! "round-trip/queue-drained-and-unpaired"
            (and (zero? (:queued snap))
                 (false? (:pending? snap))
                 (= 1 (get-in snap [:transport :replied])))
            (pr-str snap))
    ((:stop! listener))))

;; ---------------------------------------------------------------------------
;; 2. queue-full behaviour: 503, and the over-depth request never enqueues
;; ---------------------------------------------------------------------------

(defn- case-queue-full []
  (p/let [listener (transport/start-listener! {:max-queue-depth 1})
          port (:port listener)
          inflight [(http-req {:port port :path "/first"})]
          _ (wait-for #(= 1 (:queued ((:snapshot listener)))))
          rejected (http-req {:port port :path "/second"})
          snap-during ((:snapshot listener))
          accepted (guest-accept listener)
          _ (guest-reply listener 200 "first-ok")
          first-response (first inflight)
          snap ((:snapshot listener))]
    (check! "queue-full/over-depth-request-gets-503"
            (and (= 503 (:status rejected))
                 (= "0" (get (:headers rejected) "retry-after"))
                 (= "queue-full" (get (:headers rejected) "x-kotoba-ingress-reject")))
            (pr-str rejected))
    (check! "queue-full/over-depth-request-never-enqueued"
            (and (= 1 (:queued snap-during))
                 (= 1 (:max-queue-depth snap-during))
                 (= 1 (get-in snap [:transport :queue-full]))
                 (= 1 (get-in snap [:transport :enqueued])))
            (pr-str {:during snap-during :after snap}))
    (check! "queue-full/queued-request-still-served"
            (and (= "/first" (nth (nth accepted 2) 2))
                 (= 200 (:status first-response))
                 (= "first-ok" (:body first-response)))
            (pr-str {:accepted accepted :response first-response}))
    ((:stop! listener))))

;; ---------------------------------------------------------------------------
;; 3. an over-limit body is rejected at the host and never reaches the queue
;;    (both the declared content-length path and the chunked/streamed path)
;; ---------------------------------------------------------------------------

(defn- try-accept [listener]
  (try (guest-accept listener)
       (catch :default e {:threw (.-message e)})))

(defn- case-over-limit-body []
  ;; a short reply timeout so that a REGRESSION here (an over-limit body that
  ;; does reach the queue and is never replied to) fails fast instead of
  ;; parking the run on the 30s default
  (p/let [listener (transport/start-listener! {:max-body-bytes 64
                                               :reply-timeout-ms 2000})
          port (:port listener)
          big (apply str (repeat 200 "x"))
          declared (http-req {:port port :method "POST" :path "/big"
                              :headers {"content-length" (str (count big))}
                              :body big})
          snap-declared ((:snapshot listener))
          none-after-declared (try-accept listener)
          ;; no content-length => node sends it chunked, so the host cannot
          ;; know the size up front and must bound the stream as it arrives
          chunked (http-req {:port port :method "POST" :path "/big-chunked"
                             :body big})
          snap ((:snapshot listener))
          none-after-chunked (try-accept listener)
          inflight [(http-req {:port port :method "POST" :path "/small" :body "ok"})]
          _ (wait-for #(pos? (:queued ((:snapshot listener)))))
          accepted (guest-accept listener)
          _ (guest-reply listener 200 "accepted")
          under-response (first inflight)]
    (check! "over-limit/declared-body-rejected-413"
            (and (= 413 (:status declared))
                 (= "body-too-large" (get (:headers declared) "x-kotoba-ingress-reject")))
            (pr-str declared))
    (check! "over-limit/streamed-body-rejected-413"
            (and (= 413 (:status chunked))
                 (= "body-too-large" (get (:headers chunked) "x-kotoba-ingress-reject")))
            (pr-str chunked))
    (check! "over-limit/never-reaches-the-queue"
            (and (zero? (:queued snap-declared))
                 (zero? (:queued snap))
                 (zero? (get-in snap [:transport :enqueued]))
                 (= [ingress/accept-result-type false] none-after-declared)
                 (= [ingress/accept-result-type false] none-after-chunked))
            (pr-str {:declared snap-declared :chunked snap
                     :accepts [none-after-declared none-after-chunked]}))
    (check! "over-limit/under-limit-body-still-served"
            (and (= "ok" (nth (nth accepted 2) 4))
                 (= 200 (:status under-response)))
            (pr-str {:accepted accepted :response under-response}))
    ((:stop! listener))))

;; ---------------------------------------------------------------------------
;; 4. accept with nothing pending returns the option's none arm -- not a
;;    block, not an error
;; ---------------------------------------------------------------------------

(defn- case-empty-accept []
  (p/let [listener (transport/start-listener! {})
          started (js/Date.now)
          result (try (guest-accept listener)
                      (catch :default e {:threw (.-message e)}))
          elapsed (- (js/Date.now) started)]
    (check! "empty-accept/returns-none-arm"
            (= [ingress/accept-result-type false] result)
            (pr-str result))
    (check! "empty-accept/does-not-block"
            (< elapsed 250)
            (str "took " elapsed "ms"))
    ((:stop! listener))))

;; ---------------------------------------------------------------------------
;; 5. reply without a pending accept is refused
;; ---------------------------------------------------------------------------

(defn- case-unpaired-reply []
  (p/let [listener (transport/start-listener! {})
          port (:port listener)
          outcome (try {:returned (guest-reply listener 200 "nope")}
                       (catch :default e {:threw (.-message e)}))
          ;; and it stays refused while a request is merely queued: the
          ;; pairing rule is accept-then-reply, not enqueue-then-reply
          inflight [(http-req {:port port :path "/queued"})]
          _ (wait-for #(pos? (:queued ((:snapshot listener)))))
          queued-outcome (try {:returned (guest-reply listener 200 "still nope")}
                              (catch :default e {:threw (.-message e)}))
          accepted (guest-accept listener)
          _ (guest-reply listener 202 "now ok")
          response (first inflight)
          snap ((:snapshot listener))]
    (check! "unpaired-reply/refused-with-no-accept"
            (= "HTTP reply requires a prior accept" (:threw outcome))
            (pr-str outcome))
    (check! "unpaired-reply/refused-while-only-queued"
            (= "HTTP reply requires a prior accept" (:threw queued-outcome))
            (pr-str queued-outcome))
    (check! "unpaired-reply/paired-reply-still-works"
            (and (true? (second accepted))
                 (= 202 (:status response))
                 (= "now ok" (:body response))
                 (= 1 (get-in snap [:transport :replied])))
            (pr-str {:response response :snap snap}))
    ((:stop! listener))))

;; ---------------------------------------------------------------------------
;; 6. a guest that never replies does not hold the socket forever
;; ---------------------------------------------------------------------------

(defn- case-reply-timeout []
  (p/let [listener (transport/start-listener! {:reply-timeout-ms 60})
          port (:port listener)
          response (http-req {:port port :path "/never-answered"})
          snap ((:snapshot listener))
          ;; the entry is a tombstone, not a hole: the guest may still accept
          ;; it, and its late reply is dropped rather than written
          accepted (guest-accept listener)
          late (guest-reply listener 200 "too late")
          after ((:snapshot listener))]
    (check! "reply-timeout/host-answers-504"
            (and (= 504 (:status response))
                 (= "reply-timeout" (get (:headers response) "x-kotoba-ingress-reject"))
                 (= 1 (get-in snap [:transport :timed-out])))
            (pr-str {:response response :snap snap}))
    (check! "reply-timeout/late-guest-reply-is-dropped-not-written"
            (and (true? (second accepted))
                 (true? late)
                 (= 1 (get-in after [:transport :dropped-replies]))
                 (zero? (get-in after [:transport :replied])))
            (pr-str after))
    ((:stop! listener))))

;; ---------------------------------------------------------------------------
;; 7. stopping the listener answers whatever is still outstanding
;; ---------------------------------------------------------------------------

(defn- case-stop-answers-outstanding []
  (p/let [listener (transport/start-listener! {})
          port (:port listener)
          inflight [(http-req {:port port :path "/outstanding"})]
          _ (wait-for #(pos? (:queued ((:snapshot listener)))))
          _ ((:stop! listener))
          response (first inflight)
          refused (-> (http-req {:port port :path "/after-stop"})
                      (p/then (fn [r] {:status (:status r)}))
                      (p/catch (fn [e] {:connect-error (.-code e)})))]
    (check! "stop/outstanding-request-answered-503"
            (and (= 503 (:status response))
                 (= "listener-stopped"
                    (get (:headers response) "x-kotoba-ingress-reject")))
            (pr-str response))
    (check! "stop/port-is-closed"
            (some? (:connect-error refused))
            (pr-str refused))))

;; ---------------------------------------------------------------------------

(defn -main []
  (-> (p/do (case-round-trip)
            (case-queue-full)
            (case-over-limit-body)
            (case-empty-accept)
            (case-unpaired-reply)
            (case-reply-timeout)
            (case-stop-answers-outstanding))
      (p/then
       (fn [_]
         (doseq [{:keys [name ok? detail]} @results]
           (println (if ok? "PASS" "FAIL") name (if ok? "" (str "-- " detail))))
         (let [failed (remove :ok? @results)]
           (println (str (count @results) " checks, " (count failed) " failed"))
           (js/process.exit (if (seq failed) 1 0)))))
      (p/catch
       (fn [e]
         (doseq [{:keys [name ok? detail]} @results]
           (println (if ok? "PASS" "FAIL") name (if ok? "" (str "-- " detail))))
         (println "ERROR" (.-message e))
         (println (.-stack e))
         (js/process.exit 1)))))

(-main)
