# ADR 0225: T8.3 Component twin true-set header name list

- Status: Accepted
- Date: 2026-08-01
- Depends: kotoba-component `:headers-names-add` (`dfba940`); ADR 0223/0224
  pure typed-set surfaces

## Context

ADR 0223/0224 own true header-name uniqueness on the **pure/compiler** path
(`[:set :string]` guest records). Reject-path Canonical Components still used
substring `:name "…"` markers (0216) for multi-header EDN. Hosts that only
consume Components lacked a true-set twin for the **name list** plane.

kotoba-component admits Canonical role `:headers-names-add`:
`string × string → string` over an EDN vector of quoted names, with
**element-bound exact equality** membership (not marker substring scan).

## Decision

1. Ship **`http-headers-names-add`** (wasm32 admission skeleton + Component).
2. Component lowering `:headers-names-add` (kotoba-component ≥ `dfba940`).
3. Live contract: `[]`→`["Host"]`; append Accept; dup Host → `""`; prefix
   `"Ho"` does not collide with `"Host"`.

Honesty:

- Name-list plane only (not full headers EDN map append).
- Does **not** replace 0216 multi-header EDN append Component yet.
- Does **not** flip `:wasm-aot :implemented` (W4 recursive nested EDN +
  multi-export fold still open).

## Evidence

- kotoba-component ≥ `dfba940`
- Component digest `939b4bfd…`; wasmtime Host/Accept/dup/prefix
- ops kit registry + sha tests

## Related

- T8.3; ADR 0216, 0222–0224; kotoba-component `:headers-names-add`
- Follow-up: fold into multi-export reject package; W4; wasm-aot
