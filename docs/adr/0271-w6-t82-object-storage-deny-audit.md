# ADR 0271: T8.2 object deny-fixtures + object/storage audit → :ready

- Status: Accepted
- Date: 2026-08-01
- Depends: ADR 0153 kit ready checklist; object dual-runtime; storage
  production-transport on-call

## Context

Ops kits closed audit via EDN wire (0269) and guest host inject (0260–0270).
Residual T8.2 scores on the multi-cap **object** kit and **storage** kit:

| Kit | Was | Gap |
|-----|-----|-----|
| object | deny-fixtures `:partial`, audit `:partial` | deny paths only threw generic messages; no pure validators / on-call |
| storage | audit `:partial` | production-transport had `:on-call`, but provider layer had no kit-level audit + no readiness flip |

`signed-wasm` stays `:pending` — no production-signed object/storage Wasm
claim (host object-store / durable KV remain authority).

## Decision

1. **Object pure deny fixtures** (`validate-get-stream` / `validate-put-block`
   / `validate-cas`) returning stable error keywords; invoke maps to
   `ex-info` with `{:code …}` (fail closed before transport).
2. **Object optional `:on-call`** on all three providers (shared via
   `create-providers`).
3. **Storage optional `:on-call`** on `storage/provider` (additive to
   transport-level HTTP audit).
4. Kit-readiness: object `deny-fixtures`+`audit` → `:ready`; storage
   `audit` → `:ready`; leave `signed-wasm :pending`.

## Evidence

- Unit tests: pure validators; invoke deny + audit events; storage get/put
  on-call; kit-readiness score assertions

## Related

- T8.2 residual after ops audit 0269; does not flip wasm-aot / signed-wasm
