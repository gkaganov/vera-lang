# Task Completion Checks

Run from repo root.

- Primary gate: `./gradlew build` (all modules compile, `:compiler-tests:test` runs, artifacts assemble).
- Faster loop: `./gradlew :compiler-tests:test`; targeted: `./gradlew :compiler-tests:test --tests "vera.compiler.test.VeraCompilerTests.<name>"`.
- No separate linter/formatter/type-check task; Kotlin compilation is the type check.
- For IR-boundary changes, verify:
  ```bash
  rg -n "Flat[A-Z]|FlatLocalTable" modules/jvm-lowering modules/jvm-ir
  rg -n "swap\(" modules/jvm-bytecode-emitter
  ```
  Expected: no matches.
- `./gradlew :cli:run` is not currently configured; do not cite it as a passing check.
- If conventions/architecture changed, update `AGENTS.md` and relevant Serena memories in the same change.
