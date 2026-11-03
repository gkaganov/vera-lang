# Suggested Commands

Run from repo root `/home/nixos/projects/vera-lang` (Linux/bash, use `./gradlew`).

- Full build (compile + test + assemble): `./gradlew build`
- Compile only: `./gradlew assemble` / `./gradlew compileKotlin`
- All integration tests: `./gradlew :compiler-tests:test`
- Single test: `./gradlew :compiler-tests:test --tests "vera.compiler.tests.VeraCompilerTests.<test method name>"`
  - test names are backtick names with spaces; quote the whole `--tests` argument.
- Run the CLI compiler (compiles `examples/main.vera` -> `out/Main.class`): `./gradlew :cli:run`
- Dependency updates report: `./gradlew dependencyUpdates --quiet`
- Memory reference check: `serena memories check`

Output dirs: compiler output `out/`, Gradle output `build/` (both gitignored).
Pairs well with `mem:task_completion` for what to run after a change.
