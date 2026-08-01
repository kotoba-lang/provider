# ADR 0266: T8.3 remaining ops guest host surfaces (process/git/entropy/fs)

- Status: Accepted
- Date: 2026-08-01
- Depends: ADR 0260 HTTP host_post; 0265 secret host_get; W4 packages 0252–0255

## Context

HTTP (0260) and secret (0265) gained guest host surfaces that pure-encode W4
request EDN then `typed-cap-call` for host inject. Process/git/entropy/scoped-fs
had pure packages and JVM round-trips (0264) but no guest capability-forward
exports.

## Decision

1. Ship guest packages (wasm32, kotoba:typed):
   | Package | Cap wire | Export | main |
   |---------|----------|--------|------|
   | process-w4-host-edn | 20 | host_spawn_edn | -2508 |
   | git-w4-host-edn | 22 | host_run_edn | -2509 |
   | entropy-w4-host-edn | 23 | host_draw_edn | -2510 |
   | scoped-fs-w4-host-edn | 19 | host_read_edn | -2511 |
2. edn-codec inject helpers with primary-cap-id (echo inject for cap path).
3. Numeric wire ids match provider kit ids; named catalog registration remains
   follow-up (kotoba-lang authority).
4. Does **not** flip `:wasm-aot :implemented`.

## Evidence

- Package digests in registry; main codes via browser-host
- edn-codec optional inject tests; ops package sha tests

## Related

- T8.3 host authority residual; ADR 0260/0265; kit ids 19–23
