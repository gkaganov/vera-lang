# Task Completion Checks

Run from repo root.

- Primary gate: `./gradlew build` (compiles all modules, runs `:compiler-tests:test`, assembles).
- Faster inner loop: `./gradlew :compiler-tests:test`; targeted: `./gradlew :compiler-tests:test --tests "vera.compiler.tests.VeraCompilerTests.<name>"`.
- No separate linter/formatter/type-checker task is configured; Kotlin compilation is the type check, and `:compiler-tests:test` is the behavior check.
- If behavior/CLI paths changed, smoke-test end-to-end: `./gradlew :cli:run` (compiles `examples/main.vera` -> `out/Main.class`).
- If you changed conventions/architecture, update repo `AGENTS.md` in the same change (project rule).
