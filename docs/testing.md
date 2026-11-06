# Testing

This document describes how Vera's compiler tests are structured and records the fixture-based
test experiment that was tried and reverted.

## Current setup

Integration tests live in the `compiler-tests` module:

- `CompilerTests.kt` — hand-written integration tests (class `VeraCompilerTests`)
- `ByteArrayClassLoader.kt` — loads emitted bytecode in-memory

Each test follows the same flow:

1. Build a Vera source string inline.
2. Call `VeraCompiler.compile(className, source)`.
3. Load the returned bytecode with `ByteArrayClassLoader`.
4. Invoke the generated method via reflection.
5. Assert one of:
   - printed stdout (`assertProgramPrints`),
   - a returned value (`assertProgramReturns`),
   - a compilation failure and message fragment (`assertCompilationFails`).

Some tests set `compileToOut = true` to dump the generated `.class` file under `out/` for
debugging. `out/` is git-ignored.

Run them with:

```bash
./gradlew :compiler-tests:test
./gradlew :compiler-tests:test --tests "vera.compiler.tests.VeraCompilerTests.print function prints to system-out"
```

### Why this shape

- The program source, expected output/return value, and assertions are all in one place.
- Tests are ordinary `@Test` methods, so the IDE run gutter and Gradle `--tests` can select
  individual tests.
- No synchronization is needed between test methods and external fixture files.
- `print` behavior (including newlines) is checked against a Kotlin literal, not against
  output produced by the same `print` implementation.

The main downside is verbosity: programs are Kotlin strings with `trimIndent()`, which makes
larger Vera programs harder to read and edit.

## Fixture-based experiment (reverted)

We tried moving the integration suite to real `.vera` fixture files so programs could be read and
edited as normal Vera. The work was completed and passing, then reverted because the developer
experience was worse overall.

### Attempted structure

```
modules/compiler-tests/src/test/
  kotlin/vera/compiler/tests/
    VeraCompilerTests.kt
    ByteArrayClassLoader.kt
  resources/fixtures/
    arithmetic.vera
    conditionals.vera
    ...
    errors/
      rebindUnknown.vera
      rebindUnknown.txt
```

- One `.vera` file per larger category, containing many zero-argument case functions.
- Expected output was defined in Vera as a companion function:

  ```vera
  fn intsCanBeAdded() {
      print(2 + 2)
  }

  fn intsCanBeAddedExpected() {
      print(4)
  }
  ```

- The harness compiled each category file once, then compared stdout from the case function with
  stdout from its `Expected` companion.
- Compilation-failure fixtures stayed as `<name>.vera` + `<name>.txt`, where the `.txt` held the
  expected error-message fragment.

Two variants of the harness were tried:

1. **Dynamic tests (`@TestFactory`)** — cases were discovered by reflecting over `*Expected`
   methods and emitted as `DynamicTest`s. Fixtures were the single source of truth, but the tests
   only existed at runtime, so neither the IDE gutter nor Gradle `--tests` could select an
   individual case.
2. **Explicit tests** — one `@Test` method per case, e.g.
   `fun \`arithmetic intsCanBeAdded\`() = assertFixtureCase("arithmetic", "intsCanBeAdded")`.
   Individual tests were selectable again, but every new fixture case required a matching Kotlin
   method, and finding the fixture from a test still meant manually opening/searching
   `arithmetic.vera`.

### Pros of the fixture approach

- Programs are real `.vera` files: syntax-highlighted, editable, and readable without Kotlin
  string indentation.
- Related cases are grouped in one category file.
- Expected output can live next to the case instead of in a separate `.txt` file.
- The harness exercises the same public `VeraCompiler.compile` path with no per-test setup.
- Category files are compiled once and can be cached across cases.

### Cons of the fixture approach

- **No direct navigation from test to fixture.** A string like `"arithmetic"` cannot be
  Ctrl-clicked into `arithmetic.vera`. Every fixture-based variant still requires manually
  opening or searching the resource.
- **Dynamic tests are not individually selectable** by Gradle `--tests` or the IDE run gutter.
- **Explicit tests require synchronization**: adding a case means editing the `.vera` file and
  adding a Kotlin `@Test` method. Forgetting one silently skips the case.
- **Expected output produced by Vera shares the `print` implementation under test**, so bugs in
  `print`'s formatting/newlines can make both sides agree and pass. A separate Kotlin literal
  expectation is still needed for ground truth.
- **Compilation-failure cases still needed text files**, so the "no expected files" benefit was
  partial.
- **Platform-specific concerns remain in Kotlin** (e.g. CRLF handling), since Git may normalize
  committed fixture line endings.
- The harness is more machinery (resource lookup, reflection, fixture caching, companion-function
  convention) than a plain `@Test`.

### Decision

Keep the inline integration tests as they are. The fixture approach improved readability but
made the common workflow — jumping directly from a test to its source and running one case — worse.
If fixtures are revisited, they are likely better suited to a separate golden/smoke suite rather
than replacing the hand-written tests.
