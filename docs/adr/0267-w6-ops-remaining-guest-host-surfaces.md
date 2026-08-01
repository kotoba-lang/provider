# ADR 0267: T8.3 remaining guest host surfaces — git / entropy / scoped-fs

- Status: Accepted
- Date: 2026-08-01
- Depends: ADR 0253–0255 W4 packages; 0260–0266 guest host surfaces

## Context

HTTP (0260/0262), secret (0265), and process (0266) have guest host surfaces
that encode pure W4 request EDN and forward via `typed-cap-call`. Remaining
ops kits with W4 codecs but no guest host export: **git** (22), **entropy**
(23), **scoped-fs** (19).

## Decision

1. Ship guest packages (wasm32, kotoba:typed, policy per cap id):
   - **git-w4-host-edn** — `host_run_edn` wire **22**; main pure → **-2509**
   - **entropy-w4-host-edn** — `host_draw_edn` wire **23**; main pure → **-2510**
   - **scoped-fs-w4-host-edn** — `host_read_edn` wire **19** (read first slice);
     main pure → **-2511**
2. edn-codec inject modes `:git-ok` / `:entropy-hex` / `:fs-content` +
   host-*-edn / deny helpers.
3. Does **not** flip `:wasm-aot :implemented` — OS git / CSPRNG / store remain
   host-injected. scoped-fs write guest host export deferred.

## Evidence

- Package shas registered; browser-host mains -2509/-2510/-2511; inject tests

## Related

- Closes guest host-surface residual for six ops kits; T8.3 residual is host
  I/O authority only (by design)
