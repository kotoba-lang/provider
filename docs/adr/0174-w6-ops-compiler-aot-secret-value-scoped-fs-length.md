# ADR 0174: T8.3 compiler-AOT secret value-len + scoped-fs length bounds

- Status: Accepted
- Date: 2026-07-31
- Depends: ADR 0173 secret name-len; ADR 0169 scoped-fs hand path policy

## Context

ADR 0173 landed pure `secret_name_len_ok`. Secret still lacked the
`:secret-bytes 8192` length checker, and scoped-fs had no compiler-AOT length
half for `:path-bytes 1024` / `:value-bytes 65536`. Char/escape scans stay
hand WAT (`secret-get-v1`, `scoped-fs-path-v1`) because typed-string emit
needs `kotoba:typed` host.

## Decision

| export | kit limit | packages |
|---|---|---|
| `secret_value_len_ok` | secret-bytes 8192 | `:secret-value-len{,-component}` |
| `fs_path_len_ok` | path-bytes 1024 | `:fs-path-len{,-component}` |
| `fs_value_len_ok` | value-bytes 65536 | (same packages) |

Error codes: negative / empty path → `-1`; over max → `-2`.

### Honesty

- Does not replace hand path/name **char** scanners
- Does not flip `:wasm-aot` to `:implemented`
- Pure `i64` direct ABI (ADR 0171 class), not typed-string host

## Evidence

- digest match; Node live vectors; `:builder :kotoba-compiler/v1`

## Related

- T8.3; ADR 0171–0173
