# ADR 0204: T8.3 pure Component re-emit of result packing walk

- Status: Accepted
- Date: 2026-07-31
- Depends: ADR 0192 typed result packing; kotoba-component#99
  (`:http-result-pack-package-with-main` + nested live-main);
  `kotoba compile --target component`

## Context

ADR 0192 shipped a **typed-host** host-sequenced result packing walk over
ok/error arms (`begin` / `status` / `headers` / `body` / `code` / `message` /
`retryable` / `end`). Request packing (ADR 0203) already re-emits as a pure
Component. The result walk needs nine exports, dual arm state machines, and
nested live-main chains with code charset scan.

kotoba-component#99 admits the package and Canonical WAT.

## Decision

| source | module (typed) | Component (pure) | live `main()` |
|---|---|---|---|
| `http_result_pack.kotoba` | `:http-result-pack` | `:http-result-pack-component` | **-12061** |

### Honesty

- Does **not** flip `:wasm-aot` to `:implemented`
- Does **not** pack nested EDN response/error records (W4 open)
- Does **not** claim full kit body AOT complete

### Rebuild recipe

```sh
# requires kotoba-component pin ≥ 180f1302
clojure -Sdeps '{:deps {io.github.kotoba-lang/kotoba-component
  {:git/url "https://github.com/kotoba-lang/kotoba-component.git"
   :git/sha "180f1302e2672887a164021c36f486760777f890"}}}' \
  -M -m kotoba.compiler.cli compile \
  resources/kotoba/lang/wasm-packages/src/http_result_pack.kotoba \
  --target component \
  --output resources/kotoba/lang/wasm-packages/http-result-pack-v1.component.wasm
```

## Evidence

- digest match; wasmtime `main()` → **-12061**; begin(0)→1; begin(3)→-1
- provenance `:builder :kotoba-compiler/v1`, target `:wasm-component-kotoba-v1`
- `:wasm-aot` stays `:partial`

## Related

- T8.3; ADR 0192, 0199–0203; kotoba-component#99
- Frontier residual “result packing walk Component re-emit”
