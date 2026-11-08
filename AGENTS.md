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
- First pass collects function declarations by name; second pass lowers each function.
- `JvmFunctionLowering` is stateless; per-call mutable state lives in a private `*State` helper.
- The flat instruction layer verifies stack discipline and resolves labels/jumps.
- The emitter must not interpret structured expressions or control flow.

### IR Design

- **Structured IR** (`vera.jvm.ir`): `JvmProgram`, `JvmClass`, `JvmMethod`, `JvmExpression`,
  `JvmStatement`, and block kinds `JvmExplicitReturnBlock`, `JvmImplicitReturnBlock`,
  `JvmUnitReturnBlock`.
- **Operators**: `JvmIntBinaryOperator` and `JvmComparisonOperator` are independent enums.
- **Flat instruction IR** (`vera.jvm.instruction`): flat instruction lists with explicit
  `FlatInstructionEffect`, `FlatLabel` control flow, and `FlatInstructionWithDebugInfo`.
- `FlatPop` discards non-`Unit` expression results, including `if` values used as statements.
- `FlatGetStatic` carries both stack type (`fieldType`) and JVM descriptor (`fieldDescriptor`).
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

### Git

- Commit messages describe **what was done** in past tense (e.g. `added operator precedence`).

### Finishing a Task

When the user says **"finish"**:

1. Review the code and changes for problems, inconsistencies, and missing updates.
2. Ask about anything needing clarification or a decision.
3. Once resolved, commit and push.

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
- Constructs: `fn`, `var`, rebinding with `=`, `if` / `else if` / `else`, `while`, `print`.
- Operators, lowest to highest: `|`, `or`, `and`, `==`/`!=`, `<`/`>`/`<=`/`>=`, `+`/`-`, `*`/`/`/`%`.
- `and` and `or` are short-circuiting reserved keywords.
- `|` is forward application: `value | f` lowers to `f(value)`.

## Known TODOs

- **VeraMemberAccess**: `JvmFunctionLowering.lowerChainedExpression` — member/field access is not
  implemented and throws `JvmLoweringException.MemberAccessNotSupported`.

## Run / Debug

- The `cli` module provides `Main.kt` in the default package.
- No Gradle application/run task is configured; `./gradlew :cli:run` is unavailable.
- Add the `application` plugin or run `MainKt` manually with the classpath from `cli/build/libs/`.
