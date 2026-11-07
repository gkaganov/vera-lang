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
Vera source → lexer/parser → AST → JvmIr (structured IR) → JvmInstructionLowering →
JvmInstructionProgram (flat instruction IR) → Bytecode Emitter → .class files
```

### Pipeline

1. **Parse** — `VeraLexer` → `VeraParser` → AST.
2. **Lower to structured IR** — `JvmProgramLowering` / `JvmFunctionLowering`; expressions and
   statements remain nested.
3. **Lower to instruction IR** — `JvmInstructionLowering` flattens IR and owns stack effects,
   labels, jumps, and local slots.
4. **Emit** — `JvmBytecodeEmitter` generates `.class` files via the Java ClassFile API.

### Modules

| Module | Purpose |
|---|---|
| `shared-model` | Shared domain model and exceptions |
| `parser` | Lexer + recursive-descent parser → AST |
| `ast` | AST node definitions |
| `jvm-ir` | Structured JVM IR — classes, methods, expressions, statements, operators |
| `jvm-lowering` | AST → structured IR (`JvmProgramLowering`, `JvmFunctionLowering`) |
| `jvm-instruction-lowering` | Structured IR → flat instruction IR (`JvmInstructionLowering`) |
| `jvm-bytecode-emitter` | Flat instruction IR → JVM `.class` files |
| `compiler` | Orchestration: `VeraCompiler` |
| `cli` | Command-line entry point |
| `compiler-tests` | Integration and compiler-error tests |

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
- **Operators**: `IntBinaryOperator` and `ComparisonOperator` are independent enums.
- **Flat instruction IR** (`vera.jvm.instruction`): flat instruction lists with explicit
  `InstructionEffect`, `JvmLabel` control flow, and `InstructionWithDebugInfo`.
- `Pop` discards non-`Unit` expression results, including `if` values used as statements.
- `GetStatic` carries both stack type (`fieldType`) and JVM descriptor (`fieldDescriptor`).
- No structured expressions/statements remain at the flat layer.

## Conventions

### Kotlin

- Kotlin 2.4.0; Java 25 source and bytecode.
- 4-space indentation.
- Sealed interfaces for IR hierarchies.
- `Jvm` prefix for structured IR; flat instruction types are unprefixed.

### API Style

- Public pipeline services are stateless objects: `VeraLexer`, `VeraParser`,
  `JvmProgramLowering`, `JvmInstructionLowering`, `JvmBytecodeEmitter`, `VeraCompiler`.
- Inputs are method arguments; mutable state is scoped to a single call.
- Stateful helpers are private `*State` classes created per operation.

### Error Handling

- Expected failures throw named exceptions extending `vera.shared.model.VeraException`.
- Each stage has a sealed hierarchy: `VeraLexException`, `VeraParseException`,
  `JvmLoweringException`, `JvmInstructionException`, `JvmEmitException`.
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

- AST types use the `Vera` prefix (`VeraProgram`, `VeraExpression`, `VeraStatement`, ...).
- Structured IR types use the `Jvm` prefix in `vera.jvm.ir` (`JvmProgram`, `JvmReturn`, ...).
- Flat instruction IR types live in `vera.jvm.instruction` and are unprefixed (`GetStatic`,
  `JvmInstructionProgram`, ...).
- Mirror the layer in local names: `vera*` for AST values, `jvm*` for IR/JVM/lowering values.
- Lowering state classes keep the full prefix: `JvmFunctionLoweringState`.
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
- `for` is not implemented.
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
