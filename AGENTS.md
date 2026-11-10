# Vera Lang

Vera is a small, experimental programming language targeting the JVM. It uses a recursive-descent
parser, a 2-phase IR lowering pipeline, and generates JVM bytecode via the Java ClassFile API.

## Documentation

- [`docs/language.md`](docs/language.md) — current language guide
- [`docs/design-philosophy.md`](docs/design-philosophy.md) — long-term vision and principles
- [`docs/testing.md`](docs/testing.md) — test structure and workflow

## Quick Start

```bash
./gradlew build                    # Compile, test, and assemble
./gradlew :compiler-tests:test     # Run integration tests
./gradlew :compiler-tests:test --tests "vera.compiler.test.VeraCompilerTests.print function prints to system-out"
```

Build output goes to `out/` (compiler) and `build/` (Gradle).

## Architecture

```text
Vera source → lexer/parser → AST → JvmIr (structured IR) → FlatInstructionLowering →
FlatProgram (flat instruction IR) → Bytecode Emitter → .class files
```

### Pipeline

1. **Parse** — `VeraLexer` → `VeraParser` → AST.
2. **Lower to structured IR** — `JvmProgramLowering` / `JvmFunctionLowering`; expressions and
   statements remain nested.
3. **Lower to instruction IR** — `FlatInstructionLowering` flattens IR and owns stack effects,
   labels, jumps, and local slots.
4. **Emit** — `BytecodeEmitter` generates `.class` files via the Java ClassFile API.

### Modules

| Module | Purpose |
|---|---|
| `shared-model` | Shared domain model and exceptions |
| `parser` | Lexer + recursive-descent parser → AST |
| `ast` | AST node definitions |
| `jvm-ir` | Structured JVM IR — classes, methods, expressions, statements, operators |
| `jvm-lowering` | AST → structured IR (`JvmProgramLowering`, `JvmFunctionLowering`) |
| `jvm-instruction-lowering` | Structured IR → flat instruction IR (`FlatInstructionLowering`) |
| `jvm-bytecode-emitter` | Flat instruction IR → JVM `.class` files |
| `compiler` | Orchestration: `VeraCompiler` |
| `cli` | Command-line entry point |
| `compiler-tests` | Integration and compiler-error tests |

### Naming by Stage

| Stage | Module / package | Class naming | Method naming | Purpose |
|---|---|---|---|---|
| Shared model | `shared-model` / `vera.shared.model` | Shared concepts: `Identifier`, `SourceLocation`; base: `VeraException` | n/a | Identifiers, source locations, base exception |
| Lexing | `parser` / `vera.parser` | `Vera*` (`VeraLexer`, `VeraToken`, `VeraLexException`) | `lex*` | Source → tokens |
| Parsing | `parser`, `ast` / `vera.parser`, `vera.ast` | `Vera*` | `parse*` | Tokens → AST |
| Structured IR | `jvm-ir` / `vera.jvm.ir` | `Jvm*` | n/a (data classes) | Resolved, nested JVM IR; no labels or slots |
| AST lowering | `jvm-lowering` / `vera.jvm.lowering` | `Jvm*Lowering`, `Jvm*LoweringState`, `JvmLoweringException` | `lower*`, `validate*`, `require*` | AST → structured IR |
| Instruction lowering | `jvm-instruction-lowering` / `vera.jvm.instruction` | `Flat*` (`FlatInstruction`, `FlatProgram`, `FlatLoadLocal`, `FlatLabel`, `FlatInstructionException`) | `lower*`, `append`, label helpers | Structured IR → flat instruction IR; owns stack, labels, and slots |
| Bytecode emission | `jvm-bytecode-emitter` / `vera.jvm.bytecode.emitter` | `Bytecode*` (`BytecodeEmitter`, `BytecodeEmitterState`, `BytecodeEmitException`) | `emit*` | Flat IR → `.class` |
| Orchestration | `compiler` / `vera.compiler` | `VeraCompiler` | `compile` | Runs the pipeline |

Prefixes identify the data model, not the pipeline stage:

- `Vera*` — source/AST types and source-facing services (`VeraLexer`, `VeraParser`, `VeraToken`,
  `VeraCompiler`).
- `Jvm*` — structured JVM IR and its lowering services/exceptions (`JvmProgram`, `JvmMethod`,
  `JvmProgramLowering`, `JvmLoweringException`).
- `Flat*` — flat instruction IR and its lowering/builder/exceptions (`FlatProgram`, `FlatMethod`,
  `FlatLoadLocal`, `FlatInstructionLowering`, `FlatMethodBuilder`).
- `Bytecode*` — bytecode emission (`BytecodeEmitter`, `BytecodeEmitException`).

Shared concepts such as `Identifier` and `SourceLocation` intentionally have no prefix.

### Lowering

- `JvmProgramLowering.lower(mainClassName, program)` is the public entry point.
- First pass collects record and function declarations by name; records lower to their own
  classes (including instance methods), then functions lower to static methods on the program
  class.
- `JvmFunctionLowering` is stateless; per-call mutable state lives in a private `*State` helper.
- The flat instruction layer verifies stack discipline and resolves labels/jumps.
- The emitter must not interpret structured expressions or control flow.

### IR Design

- **Structured IR** (`vera.jvm.ir`): `JvmProgram`, `JvmClass`, `JvmField`, `JvmConstructor`,
  `JvmMethod`, `JvmExpression`, `JvmStatement`, and block kinds `JvmExplicitReturnBlock`,
  `JvmImplicitReturnBlock`, `JvmUnitReturnBlock`.
- Record construction is `JvmRecordConstruction`; field reads are `JvmGetField`; record method
  calls are `JvmInstanceCall` with an explicit receiver expression. `JvmMethod.receiver` is the
  optional instance receiver local; `null` means a static method.
- `Unit` is a real zero-field value, not `VOID`. `JvmUnitType` is the generated `Unit` class;
  it stores a static `INSTANCE` singleton initialized by a generated `<clinit>`. The unit value is
  `JvmUnitLiteral`, lowered to `FlatGetStatic`. Omitted return types and `print` produce `Unit`.
- A zero-parameter top-level `main` gets an additional JVM entry point `main(String[])V` that calls
  the Vera `main` and discards its result. `JvmType.Array` represents the `String[]` parameter.
- **Operators**: `JvmIntBinaryOperator` and `JvmComparisonOperator` are independent enums.
- **Flat instruction IR** (`vera.jvm.instruction`): flat instruction lists with explicit
  `FlatInstructionEffect`, `FlatLabel` control flow, and `FlatInstructionWithDebugInfo`.
- Record construction uses `FlatNew`, `FlatDup`, and `FlatInvokeSpecial`; generated constructors
  initialize fields with `FlatPutField`.
- Record method calls use `FlatInvokeVirtual`; its `receiverType` participates in stack
  verification.
- `FlatPop` discards non-`VOID` expression results, including `Unit` and `if` values used as
  statements.
- `FlatGetStatic` carries both stack type (`fieldType`) and JVM descriptor (`fieldDescriptor`).
- `FlatPutStatic` stores static fields; static fields carry `isStatic` from `JvmField`/`FlatField`
  through to the emitter.
- No structured expressions/statements remain at the flat layer.

## Conventions

### Kotlin

- Kotlin 2.4.0; Java 25 source and bytecode.
- 4-space indentation.
- Sealed interfaces for IR hierarchies.
- `Jvm*` for structured IR, `Flat*` for flat instruction IR, and `Bytecode*` for emission.

### API Style

- Public pipeline services are stateless objects: `VeraLexer`, `VeraParser`,
  `JvmProgramLowering`, `FlatInstructionLowering`, `BytecodeEmitter`, `VeraCompiler`.
- Inputs are method arguments; mutable state is scoped to a single call.
- Stateful helpers are private `*State` classes created per operation.

### Error Handling

- Expected failures throw named exceptions extending `vera.shared.model.VeraException`.
- Each stage has a sealed hierarchy: `VeraLexException`, `VeraParseException`,
  `JvmLoweringException`, `FlatInstructionException`, `BytecodeEmitException`.
- Exceptions carry structured fields and build a user-facing `render()` message.
- `VeraCompiler.compile` propagates exceptions; CLI and tests catch `VeraException`.
- Do not use `error()`, `require`, `check`, `TODO()`, `!!`, or unsafe casts in compiler modules.
- Unexpected JDK ClassFile API exceptions are compiler bugs and propagate unwrapped.
- Arrow and `VeraResult` are not used.

### Testing

- JUnit 5 (JUnit 6 API) with JUnit Platform.
- `compiler-tests` are integration tests: compile Vera code, load bytecode, execute, compare output.
- Test names are descriptive backticked Kotlin names.
- Error tests live in `CompilerErrorTests`; see [`docs/testing.md`](docs/testing.md).

### Workflow Keywords

- `review` — walk through all uncommitted changes, step by step.
- `push` — review the work, commit, and push after approval.
- `note` — record the accompanying instruction or decision in `AGENTS.md` or a file referenced from it.

`review` and `push` only apply when sent on their own; the same words inside a sentence are ordinary
text. `note` may be followed by the content to record.

### Git

- Commit messages describe **what was done** in past tense (e.g. `added operator precedence`).
- Ask the user to review changes before pushing. Do not push until the user approves.

### Pushing Changes

When the user sends **`push` on its own**:

1. Ask about anything needing clarification or a decision.
2. Once resolved, commit the changes.
3. Ask the user to review the committed changes.
4. Push only after the user approves.

### Reviewing Changes

When the user sends **`review` on its own**, perform a guided, step-by-step review of all
uncommitted changes:

1. Inspect `git status` and the diffs, including staged, unstaged, untracked, and deleted files.
   Include every change, no matter how small.
2. Run the tests if they have not run recently; report the result.
3. Before the first step, show an overview of all planned steps with a total count. Order them for
   comprehension, for example: data model → parser → structured IR → lowering → flat IR → emitter →
   tests → docs. Group tiny related changes; split large files into focused hunks. Each step must be
   small enough to follow easily.
4. For each step, show the full relevant diff or hunk explicitly in the response, alongside an
   explanation of what changed and why. The diff and the explanation are equally important; do not
   omit any change, no matter how small.
5. After each step, state what the next step is explicitly (for example `Next: step 5/11`), then ask
   whether the user has any questions.
6. Pause and wait for the user's reply before continuing. Answer questions, but do not resume until
   the user explicitly says to continue (for example `go on` or `next`).
7. End with a concise summary of the whole change set and stop. Do not commit or push; those happen
   only after the user says **`push`**.

### Naming

- Source/AST types use `Vera*` (`VeraProgram`, `VeraExpression`, `VeraToken`, ...).
- Structured IR types use `Jvm*` in `vera.jvm.ir` (`JvmProgram`, `JvmReturn`, ...).
- Flat instruction IR types use `Flat*` in `vera.jvm.instruction` (`FlatProgram`, `FlatLoadLocal`,
  ...).
- Lowering services are named by their target: `JvmProgramLowering` / `JvmFunctionLowering` produce
  structured IR; `FlatInstructionLowering` produces flat instruction IR.
- Mirror the data model in local names: `vera*` for AST values, `jvm*` for structured IR values,
  `flat*` for flat instruction values.
- Stateful helpers keep the full pipeline prefix: `JvmFunctionLoweringState`,
  `FlatInstructionLoweringState`, `VeraParserState`, `BytecodeEmitterState`.
- Use `lower*` for AST-to-IR and structured-to-instruction lowering; use `validate*`/`require*` for
  operand checks; use `append(...)` for one flat instruction.
- Reserve `emit*` for actual bytecode generation.
- Record types are `VeraRecordDeclaration`/`VeraRecordField`, `JvmField`/`JvmConstructor`/
  `JvmRecordConstruction`/`JvmGetField`/`JvmInstanceCall`, and `FlatNew`/`FlatGetField`/
  `FlatPutField`/`FlatInvokeSpecial`/`FlatInvokeVirtual`.
- `Unit` uses `VeraUnitLiteral`, `JvmUnitLiteral`, `JvmUnitType`/`JvmPutStatic`, and
  `FlatGetStatic`/`FlatPutStatic`.
- The JVM entry-point adapter is built in `JvmProgramLowering` as a `JvmStaticCall` to `main`
  followed by `JvmReturn(null)`.
- JVM types use standard naming (e.g. `VeraMain`, `VeraApp`).

### AGENTS.md

- This file records project conventions, architecture, and important decisions.
- Update it when conventions change.

## Parsing

- Recursive descent: `VeraCompiler` → `VeraLexer` (including significant `EOL` tokens) →
  `VeraParser`.
- Newlines terminate statements.
- Blank lines and extra `EOL` tokens are ignored at block boundaries and before `else`.
- CRLF is normalized to a single `EOL` token.
- String literals allow arbitrary non-newline characters and decode `\"`, `\\`, `\n`, `\t`,
  and `\r` escapes.
- Constructs: `fn`, `record`, `var`, rebinding with `=`, `if` / `else if` / `else`, `while`,
  `print`.
- Operators, lowest to highest: `|>`, `or`, `and`, `==`/`!=`, `<`/`>`/`<=`/`>=`, `+`/`-`, `*`/`/`/`%`.
- `and` and `or` are short-circuiting reserved keywords.
- `|>` is forward application: `value |> f` lowers to `f(value)`. The forwarded value becomes the
  first argument.
- The right side of `|>` may be a bare callable (function, built-in, record constructor, sibling
  record method) or a member call chain, e.g. `data |> processor.runtime.process() |> print()`.
- Forward application to member calls and sibling methods is not implemented yet.
- `print` is a built-in, not a keyword; top-level functions cannot reuse built-in names.

## Name Resolution

- Shadowing is never allowed in Vera. Every name in a scope must be unique, including parameters,
  locals, fields, methods, top-level functions, records, and built-ins. A nested binding may not
  reuse an outer name; declarations must be renamed instead.
- Enforcement is not implemented yet; current lookups still let parameters/locals take precedence
  over fields.

## Known TODOs

- Record mutation and persistent collections are not implemented.
- Name shadowing is disallowed by design but not rejected yet.
- Forward application to member calls and sibling record methods is not implemented yet.

## Run / Debug

- The `cli` module provides `Main.kt` in the default package.
- No Gradle application/run task is configured; `./gradlew :cli:run` is unavailable.
- Add the `application` plugin or run `MainKt` manually with the classpath from `cli/build/libs/`.
