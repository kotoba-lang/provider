(ns provider.secret-transport
  "Production-ish secret fetch transports for `provider.secret` (ADR 0145).

  Does NOT define a new capability. Builds `(fn [{:keys [name]}] -> reply)`
  for injection as `:fetch`.

  ## No ambient secret authority

  - **No keychain dump / list-all**
  - **No full process env scan**
  - Host supplies an explicit map of secret-name → source

  ## Sources

  ### `map-fetch`
  Static `{name secret}` — tests and sealed host config.

  ### `env-fetch`
  `{secret-name env-var-name}` — reads only those env vars via
  `System/getenv` / `js/process.env` for the single requested name.
  Never enumerates all environment keys.

  ### kagi / OS keychain
  Out of scope for this slice (interactive unlock / compartment policy).
  Hosts that already have a one-shot getter can wrap it as `:fetch`."
  (:require [clojure.string :as str]
            [provider.secret :as secret]))

(defn map-fetch
  "Host-sealed map of secret name → value."
  [m]
  (secret/mem-fetch m))

(defn- read-env
  "Read a single env var by exact name. Never lists the environment."
  [env-name]
  #?(:clj (System/getenv ^String env-name)
     :cljs (when (exists? js/process)
             (let [v (aget js/process.env env-name)]
               (when (some? v) (str v))))))

(defn env-fetch
  "Build a fetch transport from `{secret-name env-var-name}`.

  Only env vars named in the map are ever read. Unknown secret names
  return not-found (provider allowlist should already reject them)."
  [name->env]
  (when-not (and (map? name->env) (seq name->env)
                 (every? string? (keys name->env))
                 (every? string? (vals name->env)))
    (throw (ex-info "secret-transport/env-fetch requires non-empty string map"
                    {:phase :secret-transport})))
  (doseq [[n e] name->env]
    (when-let [err (secret/validate-name n)]
      (throw (ex-info "secret-transport secret name fails policy"
                      {:phase :secret-transport :name n :error err})))
    (when (or (str/blank? e) (str/includes? e "*"))
      (throw (ex-info "secret-transport env var name invalid"
                      {:phase :secret-transport :env e}))))
  (fn [{:keys [name]}]
    (if-let [env-name (get name->env name)]
      (if-let [v (read-env env-name)]
        (if (str/blank? v)
          {:tag :error :code :secret/empty :message "env secret empty"}
          {:tag :value :value v})
        {:tag :error :code :secret/not-found :message "env not set"})
      {:tag :error :code :secret/not-found :message "no env mapping"})))
