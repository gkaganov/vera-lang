# Suggested Commands

Run from repo root `/home/nixos/projects/vera-lang` (Linux/bash, use `./gradlew`).

- Full build (compile + test + assemble): `./gradlew build`
- All integration tests: `./gradlew :compiler-tests:test`
- Single test: `./gradlew :compiler-tests:test --tests "vera.compiler.test.VeraCompilerTests.<test name>"`
  - test names are backtick prose names; quote the whole `--tests` argument.
- Compile only: `./gradlew assemble`
- Memory reference check: `serena memories check`

There is currently no `:cli:run` Gradle task; run the CLI only after adding an application plugin or invoke `MainKt` manually with a built classpath.
Output dirs: `out/` (compiler), `build/` (Gradle); both gitignored.
