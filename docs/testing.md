# Testing

Compiler tests live in the `compiler-tests` module, organized by language feature so that each
feature's tests, successful and failing, are in one place.

- `language/` — one file per feature, mirroring the sections of [`language.md`](language.md):
  `FunctionTests`, `BindingTests`, `ClassTests`, `TraitTests`, `InterfaceTests`, `MatchTests`, ...
  Each file groups its tests in `@Nested` classes by sub-feature (`TraitTests.Requirements`,
  `InterfaceTests.StructuralFit`), so the IDE outline and the test report read as a tree.
- `DiagnosticsTests.kt` — the rendered form of compiler errors with their source location.
- `ErrorCoverageTests.kt` — fails when a compiler error has no test; see below.
- `VeraTestSupport.kt` — shared helpers: compile, run, and compare output or errors.
- `ByteArrayClassLoader.kt` — loads emitted bytecode in-memory.

The flat instruction and emitter stages test their own invariants with hand-built IR, because valid
Vera source cannot reach them: `FlatInstructionLoweringTests` in `jvm-instruction-lowering` and
`BytecodeEmitterTests` in `jvm-bytecode-emitter`.

## Where a test goes

- Put a test in the file of the feature it exercises, in the nested class of its sub-feature, next
  to that feature's other tests. Error tests sit beside the successful ones.
- Name tests as descriptive backticked sentences.
- When a feature gets its own section in `language.md`, give it its own file or nested class.

## How tests work

Tests write a Vera program with a `fn main()` as an indented raw string. The helpers in
`VeraTestSupport.kt` compile it, load the bytecode with `ByteArrayClassLoader`, and check the result:

```kotlin
@Test
fun `classes fit interfaces by their members`() = assertPrints(
    """
    interface Named {
        fn name(): String
    }
    class Dog {
        fn name(): String = "Rex"
    }
    fn main() = print(Dog.name())
    """,
    "Rex",
)
```

- `assertPrints(source, lines...)` calls `main` and compares the printed lines.
- `assertReturns(source, value)` calls `main` and compares its return value.
- `assertJvmMainPrints(source, lines...)` runs the JVM entry point `main(String[])`.
- `compileProgram(source)` returns the compiled classes for tests that inspect them by reflection.
- A source that starts with a line break is trimmed with `trimIndent`; any other source is used as
  is, so a test can build exact input such as CRLF line endings.

Each error test is its own `@Test` calling `assertCompilationFails(source, ExpectedError::class,
"message part")`. Every error reachable from source must carry a source span
(`assertCompilationFails` checks it), and the diagnostics tests compare the full rendered output
of `VeraDiagnostics.render`.

Run the suite with:

```bash
./gradlew test
./gradlew :compiler-tests:test
./gradlew :compiler-tests:test --tests "vera.compiler.test.language.InterfaceTests"
./gradlew :compiler-tests:test --tests "vera.compiler.test.language.InterfaceTests\$StructuralFit"
```

## Error coverage

`ErrorCoverageTests` lists every class of each stage's exception hierarchy (`VeraLexException`,
`VeraParseException`, `JvmLoweringException`, `FlatInstructionException`, `BytecodeEmitException`)
and fails unless each is named, as `Stage.Error`, in some test source. An error that no input can
reach goes into its `untestable` map with the reason, so the list of known gaps stays visible.

## Why inline source

Inline tests keep the program, expected result, and assertion together, and let the IDE and Gradle
select individual cases. The trade-off is verbosity: larger Vera programs are harder to read inside
Kotlin strings.

A fixture-based suite using `.vera` files was tried and reverted. It improved program readability,
but made navigation and per-case selection worse, required synchronization between fixtures and test
methods, and still needed separate expected-error files. If fixtures return, they should be a
separate golden/smoke suite rather than a replacement for these tests.
