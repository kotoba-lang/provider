# ADR 0254: T8.3 / W4 entropy kit record-typed recursive EDN

- Status: Accepted
- Date: 2026-08-01
- Depends: ADR 0250–0253 record-kv pattern; ADR 0244 fixed-depth entropy EDN

## Context

Entropy kit still used fixed-depth string-concat codecs (0244) with residual
**W4 open**. CSPRNG remains host-injected.

## Decision

1. Ship **entropy-record-kv-edn** (wasm32, kotoba:typed):
   - Same sealed `:edn/kv` + `:edn/node` as 0250+
   - `entropy_req_rec_kv_edn(n)` → `{:n N}` for n ∈ [1,64]
   - `entropy_reply_hex_rec_kv_edn` / `entropy_reply_error_rec_kv_edn`
   - main → **-2504**
2. Does **not** flip `:wasm-aot :implemented` — CSPRNG host-injected.

## Evidence

- Package sha `236703ad…`; browser-host main → -2504

## Related

- T8.3 residual W4; ADR 0244; host CSPRNG
