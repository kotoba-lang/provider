# ADR 0251: T8.3 / W4 secret kit — record-typed recursive EDN request+reply

- Status: Accepted
- Date: 2026-08-01
- Depends: ADR 0250 HTTP record-kv recursive EDN; ADR 0232/0236/0237 fixed-depth secret EDN

## Context

HTTP ops closed W4 recursive nested EDN identity through ADR 0246–0250
(record-typed `:edn/kv` entries). Secret kit still claimed “W4 recursive /
host-fetch kit body open” while only fixed-depth string-concat codecs
(ADR 0232–0237, 0245) existed. Host-fetch remains a separate residual;
recursive EDN identity can advance without it.

## Decision

1. Ship **secret-record-kv-edn** (wasm32, kotoba:typed), same sealed schemas
   as ADR 0250:
   - `:edn/kv` record `{k v}` + `:edn/node` =
     `:atom | :entry kv | :pair [node node]`
   - `secret_request_rec_kv_edn` → `{:name "…"}` (name ≤128; dual reject)
   - `secret_reply_value_rec_kv_edn` → `{:tag :value :value "…"}`
   - `secret_reply_error_rec_kv_edn` → `{:tag :error :code "…" :message "…"}`
   - main → **-2501**
2. Closes secret kit **W4 recursive ADT** residual for request/reply identity.
3. Does **not** flip `:wasm-aot :implemented` — **host-fetch** still open
   (same honesty as HTTP residual “host I/O”).

## Evidence

- Package sha `443e2a90…`; browser-host main → -2501
- ops kit registry + sha tests

## Related

- T8.3 residual W4; ADR 0250; secret fixed-depth 0232–0237/0245
