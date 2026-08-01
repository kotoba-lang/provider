# ADR 0238: T8.3 multi-export HTTP kit EDN (request + result set-record)

- Status: Accepted
- Date: 2026-08-01
- Depends: ADR 0234 request set-record; ADR 0235 result set-record; compiler ADR 0195 (browser-host ref compare)

## Context

Request (0234) and result-variant (0235) fixed-depth kit-shaped EDN were
separate packages. Hosts need one shared pure multi-export surface for the
HTTP kit EDN encode path — same pattern as secret ADR 0237.

## Decision

1. Ship **http-kit-edn-package** (wasm32, kotoba:typed): exports
   `http_req_*` (begin/add/code/count/edn) + `http_res_ok_*` +
   `http_res_err_edn` + `main`.
2. `main` → **-9238**. Package sha `ac184503…`.
3. Live browser-host `main()` requires compiler ADR 0195 (compareValue
   resolves schema refs for set-of-record conj).

Honesty: fixed-depth / bounded sets only; not W4 recursive ADT; not
host-fetch AOT. Does not flip HTTP wasm-aot to implemented.

## Evidence

- KIR/browser-host main → -9238
- ops kit registry + sha tests

## Related

- T8.3; ADR 0234, 0235, 0237 (secret twin pattern); compiler ADR 0195
