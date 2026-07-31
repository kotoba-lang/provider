# ADR 0202: T8.3 pure Component re-emit of headers-set packing walk

- Status: Accepted
- Date: 2026-07-31
- Depends: ADR 0189 typed headers-set packing; kotoba-component#97
  (`:http-headers-set-package-with-main` + nested live-main);
  `kotoba compile --target component`

## Context

ADR 0189 shipped a **typed-host** wasm module for host-sequenced header set
packing (`begin` / `pair` / `end`). ADR 0199–0201 re-emitted url/request/
response/error and header name/value packages as pure Components. The set
packing walk remained typed-only and needs nested live-main recognition
(bindings call nested begin/pair/end, not flat policy lits).

kotoba-component#97 admits the four-export package with nested live-main
expressions and Canonical WAT that owns tchar name + CTL value policy.

## Decision

Re-emit one pure Component and register a twin:

| source | module (typed) | Component (pure) | live `main()` |
|---|---|---|---|
| `http_headers_set_ok.kotoba` | `:http-headers-set-ok` | `:http-headers-set-ok-component` | **-3647** |

### Honesty

- Does **not** flip `:wasm-aot` to `:implemented`
- Does **not** check header name uniqueness (W4 / set storage open)
- Does **not** re-emit request/result packing walks (0192–0193)

### Rebuild recipe

```sh
# requires kotoba-component pin ≥ f4e0fa6d
clojure -Sdeps '{:deps {io.github.kotoba-lang/kotoba-component
  {:git/url "https://github.com/kotoba-lang/kotoba-component.git"
   :git/sha "f4e0fa6d2d1bae7c214e38fa0403d01e198e3401"}}}' \
  -M -m kotoba.compiler.cli compile \
  resources/kotoba/lang/wasm-packages/src/http_headers_set_ok.kotoba \
  --target component \
  --output resources/kotoba/lang/wasm-packages/http-headers-set-ok-v1.component.wasm
```

## Evidence

- digest match; wasmtime `main()` → **-3647**; begin(0)→0; begin(40)→-4
- provenance `:builder :kotoba-compiler/v1`, target `:wasm-component-kotoba-v1`
- `:wasm-aot` stays `:partial`

## Related

- T8.3; ADR 0189, 0200–0201; kotoba-component#97
- Frontier residual “headers-set packing Component re-emit”
