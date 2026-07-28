# ADR 0148: git kit contract first slice (id 22)

- Status: Accepted
- Date: 2026-07-28

## Context

W6 kbb ability gap lists **git** (read-only status/log under grant) as
medium / missing. Ops tooling scripts stay on nbb/bb until a kit exists.

## Decision

| piece | role |
|---|---|
| `provider.git` (id **22**) | typed status + log; `:allowed-worktrees` |
| pure `validate-worktree` / `validate-log-n` | fail-closed keys and bounds |
| `mem-run` | test double |
| `git-transport/os-run` | JVM: absolute `:git-bin` + `:worktrees` dirs |

### Explicit non-goals

- No commit / push / reset / checkout mutations
- No ambient PATH `git`, no ambient CWD
- cljs os-run deferred (compose process-transport later)

## Consequences

- Gap `:git` → contract first slice + JVM os-run available.
- Scripts can inject mem-run in tests and os-run under declared roots.

## Related

- ADR 0143/0144 process + scoped-fs (inject, no ambient)
- `lang/w6-kbb-ability-gap.edn` `:git`
