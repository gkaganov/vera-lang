# Testing

Compiler tests live in the `compiler-tests` module, organized by language feature so that each
feature's tests, successful and failing, are in one place.

- `language/` — one file per feature, mirroring the sections of [`language.md`](language.md):
  `FunctionTests`, `BindingTests`, `ClassTests`, `TraitTests`, `InterfaceTests`, `MatchTests`, ...
  Each file groups its tests in `@Nested` classes by sub-feature (`TraitTests.Requirements`,
  `InterfaceTests.StructuralFit`), so the IDE outline and the test report read as a tree.
- `DiagnosticsTests.kt` — the rendered form of compiler errors with their source location.
- `InstructionLoweringTests.kt`, `BytecodeEmissionTests.kt` — invariants of the flat instruction
  and emitter stages, checked with hand-built IR because valid Vera source cannot reach them.
- `VeraTestSupport.kt` — shared helpers: compile, run, and compare output or errors.
- `ByteArrayClassLoader.kt` — loads emitted bytecode in-memory.

## Where a test goes

- Put a test in the file of the feature it exercises, in the nested class of its sub-feature, next
  to that feature's other tests. Error tests sit beside the successful ones.
- Name tests as descriptive backticked sentences.
- When a feature gets its own section in `language.md`, give it its own file or nested class.

## How tests work

Tests build Vera source inline, call `VeraCompiler.compile`, load the returned bytecode with
`ByteArrayClassLoader`, and either invoke the compiled method or catch the rendered `VeraException`.
Assertions compare printed stdout, returned values, or an error-message fragment.

Each error test is its own `@Test` calling `assertCompilationFails(source, ExpectedError::class,
"message part")`. Every error reachable from source must carry a source span
(`assertCompilationFails` checks it), and the diagnostics tests compare the full rendered output
of `VeraDiagnostics.render`.

Run the suite with:

```bash
./gradlew :compiler-tests:test
./gradlew :compiler-tests:test --tests "vera.compiler.test.language.InterfaceTests"
./gradlew :compiler-tests:test --tests "vera.compiler.test.language.InterfaceTests\$StructuralFit"
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
