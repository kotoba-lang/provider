# ADR 0244: T8.3 entropy kit fixed-depth EDN request+reply package

- Status: Accepted
- Date: 2026-08-01
- Depends: entropy bounds 0167/0172; scoped-fs EDN package pattern 0243

## Context

Entropy kit request/result is fixed-depth (`{:n n}` request; hex or error
result). Other ops kits gained pure fixed-depth EDN packages through 0243.
Entropy needs the same pure codec surface; CSPRNG stays host-injected.

## Decision

1. Ship **entropy-edn-package** (wasm32, kotoba:typed):
   - Request: `entropy_req_edn` with n ∈ [1, 64]
   - Reply: `entropy_reply_hex_edn` (lowercase hex, even length ≤ 128) /
     `entropy_reply_error_edn`
   - Dual quote/backslash scan on string leaves
   - main → **-2315**
2. CSPRNG remains host-injected — pure EDN codec only.
3. Does not flip wasm-aot to implemented (W4 recursive nested EDN open).

## Evidence

- Package sha `aa2d8c8e…`; KIR main → -2315
- ops kit registry + sha tests

## Related

- T8.3; entropy 0167/0172; ops EDN packages 0236–0243
