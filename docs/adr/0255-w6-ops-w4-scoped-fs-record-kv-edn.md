# ADR 0255: T8.3 / W4 scoped-fs kit record-typed recursive EDN

- Status: Accepted
- Date: 2026-08-01
- Depends: ADR 0250–0254 record-kv pattern; ADR 0243 fixed-depth scoped-fs EDN

## Context

Scoped-fs kit still used fixed-depth string-concat codecs (0243) with residual
**W4 open**. Host store remains host-injected. Path policy (no `..`, no absolute
paths, root keyword body) matches 0243.

## Decision

1. Ship **scoped-fs-record-kv-edn** (wasm32, kotoba:typed):
   - Same sealed `:edn/kv` + `:edn/node` as 0250+
   - `fs_req_read_rec_kv_edn` / `fs_req_write_rec_kv_edn`
   - `fs_reply_content_rec_kv_edn` / `fs_reply_written_rec_kv_edn` /
     `fs_reply_error_rec_kv_edn`
   - main → **-2505**
2. Closes last ops-kit **W4 open** residual for entropy+fs cohort.
3. Does **not** flip `:wasm-aot :implemented` — host store open.

## Evidence

- Package sha `9121592e…`; browser-host main → -2505

## Related

- T8.3 residual W4; ADR 0243; host store authority
