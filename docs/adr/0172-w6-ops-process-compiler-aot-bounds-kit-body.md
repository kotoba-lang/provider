# ADR 0172: T8.3 compiler-AOT process spawn bounds kit body

- Status: Accepted
- Date: 2026-07-31
- Depends: ADR 0168 process Component; ADR 0171 http compiler-AOT pilot

## Context

ADR 0171 landed the first **compiler-emitted** ops kit body slice (http-post
`:limits`). Process already had a hand-written pure WAT bounds checker
(`process-spawn-v1`, ADR 0168). T8.3 residual asks to re-emit such pure
policies via `kotoba-compiler` rather than keep expanding hand WAT.

## Decision

### Compiler-AOT re-emit (alongside hand WAT)

Source `process_spawn_bounds.kotoba` exports:

```
process_spawn_bounds_ok(argc, max-out, timeout-ms) → i64
```

Semantics match ADR 0168 / process-v1 `:limits` (`:argv 32`,
`:stdout-bytes 65536`, `:timeout-ms [1 600000]`):

| code | condition |
|---|---|
| `-1` | argc ≤ 0 |
| `-2` | argc > 32 |
| `-3` | max-out ∉ [1, 65536] |
| `-4` | timeout-ms ∉ [1, 600000] |
| `0` | ok |

| artifact | role |
|---|---|
| `src/process_spawn_bounds.kotoba` | source of truth |
| `process-spawn-bounds-v1.wasm` | kotoba-compiler wasm32 + provenance |
| `process-spawn-bounds-v1.component.wasm` | Component embed |

Registry: `:process-spawn-bounds{,-component}` with
`:builder :kotoba-compiler/v1`, `:class :ops`.

### ABI honesty

- Compiler path is **i64/i64/i64 → i64** (kotoba-compiler native).
- Hand WAT ADR 0168 remains **i32/i64/i64 → i32** for continuity; not deleted
  in this ADR.
- Host OS spawn remains the authority boundary.

### Honesty

- `:wasm-aot` stays `:partial` (no full request/result EDN codec AOT)
- Does not claim production AOT of spawn execution

## Non-claims

- Not secret/scoped-fs/git/entropy compiler re-emit (follow-ups)
- Not multi-file project with `:capabilities #{:process/spawn}`
- Not live OS spawn success proof

## Evidence

- digest match module + Component
- Node WebAssembly vectors: ok/argc/max-out/timeout error codes
- provenance `:builder :kotoba-compiler/v1`
- process kit notes + readiness evidence list ADR 0172

## Related

- Reliability WBS T8.3
- ADR 0168, 0171
- Frontier ADR-2607299400
