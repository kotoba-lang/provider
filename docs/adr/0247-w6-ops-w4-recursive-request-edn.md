# ADR 0247: T8.3 / W4 second ops slice — recursive headers into kit request map

- Status: Accepted
- Date: 2026-08-01
- Depends: ADR 0246 recursive-headers-edn

## Context

ADR 0246 landed recursive `edn/node` atom|pair print for header lists.
Kit `:request` map identity still used fixed-depth string-concat only.
Next residual is composing the recursive headers spine into the request map.

## Decision

1. Ship **recursive-request-edn** (wasm32, kotoba:typed):
   - Reuse recursive headers list print (0246 surface via `headers_list_edn`)
   - `request_edn(url, body, timeout, headers-edn)` wraps recursive headers
     EDN into `{:url … :headers … :body … :timeout-ms …}`
   - Dual scan on url/body; reject empty headers / negative timeout
   - main → **-2402**
2. Honesty: headers interior is recursive ADT-printed; outer map keys remain
   fixed-depth skeleton. Not full result recursive identity. Does **not** alone
   flip `:wasm-aot :implemented`.

## Evidence

- Package sha `ef37c6f0…`; browser-host main → -2402
- ops kit registry + sha tests

## Related

- T8.3 / W4; ADR 0246; HTTP 0234/0242 fixed-depth request
