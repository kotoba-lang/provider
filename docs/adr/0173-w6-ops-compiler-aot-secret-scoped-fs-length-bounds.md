# ADR 0173: T8.3 compiler-AOT length half of secret/scoped-fs limits

- Status: Accepted
- Date: 2026-07-31
- Depends: ADR 0171–0172 pure numeric compiler-AOT bounds

## Context

ADR 0172 re-emitted process/entropy/git **numeric** pure bounds via
kotoba-compiler. Secret and scoped-fs still only had **hand-written WAT**
that scans guest memory (`secret_name_ok(ptr,len)`, `fs_path_ok(ptr,len)`).

A full char-scan re-emit in `.kotoba` is possible with `:string` +
`string-contains?`, but the wasm32 output imports the **`kotoba:typed`**
host (externref string ABI) — a different packaging class from the pure
`i64`-direct bounds of ADR 0171/0172, and not runnable under bare Node
WebAssembly without a typed host.

## Decision

Ship the **length half** of kit `:limits` as pure compiler-AOT modules
(same class as 0171/0172):

| kit limits | exports | packages |
|---|---|---|
| secret `{:name-bytes 128 :secret-bytes 8192}` | `secret_name_len_ok`, `secret_value_len_ok` | `:secret-name-len{,-component}` |
| scoped-fs `{:path-bytes 1024 :value-bytes 65536}` | `fs_path_len_ok`, `fs_value_len_ok` | `:fs-path-len{,-component}` |

Error codes: name/path empty/`≤0` → `-1`; over max → `-2`; value negative →
`-1`; value over max → `-2`.

Hand WAT char/escape scans remain authoritative for path safety until a
typed-string host packaging path is production-ready.

### Honesty

- Does **not** replace `secret_name_ok` / `fs_path_ok` memory-scan modules
- Does **not** flip `:wasm-aot` to `:implemented`
- Does **not** claim full string-policy AOT without typed host

## Non-claims

- Not secret fetch / scoped-fs host store
- Not multi-file kit project mode
- Not full request/result EDN codec AOT

## Evidence

- digest match module + Component
- Node live vectors for ok / empty / oversize
- `:builder :kotoba-compiler/v1` provenance

## Related

- Reliability WBS T8.3
- ADR 0163/0166/0169, 0171–0172
