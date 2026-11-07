# Testing

Compiler tests live in the `compiler-tests` module:

- `CompilerTests.kt` — successful compilation and execution tests
- `CompilerErrorTests.kt` — compilation-failure tests grouped by compiler stage
- `ByteArrayClassLoader.kt` — loads emitted bytecode in-memory

## How tests work

Tests build Vera source inline, call `VeraCompiler.compile`, load the returned bytecode with
`ByteArrayClassLoader`, and either invoke the compiled method or catch the rendered `VeraException`.
Assertions compare printed stdout, returned values, or an error-message fragment.

Error tests use parameterized cases per stage. Instruction-lowering and emitter diagnostics use
hand-built IR because they cannot be reached through valid Vera source.

Run the suite with:

```bash
./gradlew :compiler-tests:test
./gradlew :compiler-tests:test --tests "vera.compiler.test.VeraCompilerTests.print function prints to system-out"
```

Some tests set `compileToOut = true` to dump generated `.class` files under `out/` for debugging.
`out/` is git-ignored.

## Why inline source

Inline tests keep the program, expected result, and assertion together, and let the IDE and Gradle
select individual cases. The trade-off is verbosity: larger Vera programs are harder to read inside
Kotlin strings.

A fixture-based suite using `.vera` files was tried and reverted. It improved program readability,
but made navigation and per-case selection worse, required synchronization between fixtures and test
methods, and still needed separate expected-error files. If fixtures return, they should be a
separate golden/smoke suite rather than a replacement for these tests.
