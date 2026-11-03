# Vera Lang

Vera is a small, experimental programming language with a JVM target. It uses a recursive-descent parser, a 2-phase IR lowering pipeline, and generates JVM bytecode via the Java ClassFile API.

## Quick Start

```bash
./gradlew build                    # Compile, test, and assemble
./gradlew :compiler-tests:test     # Run integration tests
./gradlew :compiler-tests:test --tests "compiler.tests.CompilerTests.test_fibonacci"  # Single test
```

Build output goes to `out/` (compiler) and `build/` (Gradle).

## Architecture

```
Vera source → lexer/parser → AST → JvmIr (structured IR) → JvmInstructionLowering →
JvmInstructionProgram (flat instruction IR) → Bytecode Emitter → .class files
```

### Pipeline Overview (`VeraCompiler`)

1. **Parse**: source → `VeraLexer` token stream → recursive-descent `VeraParser` → AST
2. **Lower to structured IR**: `JvmProgramLowering` transforms AST → structured JVM IR (`JvmIr`)
   using `JvmFunctionLowering` per function. Expressions/statements remain nested constructs here.
3. **Lower to instruction IR**: `JvmInstructionLowering` flattens the structured `JvmIr` into a
   linear `JvmInstructionProgram` with explicit stack effects and control-flow labels.
4. **Emit**: `JvmBytecodeEmitter` generates `.class` files from the flat instruction IR using the
   Java ClassFile API.

### Modules

| Module                 | Purpose                                                                                        |
|------------------------|------------------------------------------------------------------------------------------------|
| `shared-model`         | Shared domain model (types, expressions, etc.)                                                 |
| `parser`               | Lexer + recursive-descent parser → AST                                                         |
| `ast`                  | AST node definitions                                                                           |
| `jvm-ir`               | Structured JVM intermediate representation — classes, methods, expressions, statements, operators |
| `jvm-lowering`         | AST → structured IR lowering (`JvmProgramLowering`, `JvmFunctionLowering`)                     |
| `jvm-instruction-lowering` | Structured IR → flat instruction IR lowering (`JvmInstructionLowering`)                    |
| `jvm-bytecode-emitter` | Flat instruction IR → JVM `.class` file generation                                             |
| `compiler`             | Orchestration: `VeraCompiler` ties parse → lower → emit                                        |
| `cli`                  | Command-line entry point                                                                       |
| `compiler-tests`       | Integration tests — compiles Vera code, runs via custom classloader, asserts stdout            |

### Lowering Details

`JvmProgramLowering` is the module's public entry point. `JvmFunctionLowering` is an
internal stateless object; the per-function mutable state (local bindings) lives in a private
helper created for each call and produces structured IR, not flat instructions.

`JvmProgramLowering.lower(mainClassName, program)` uses a **2-phase approach**:
1. **Collect**: First pass collects all `VeraFunctionDeclaration` AST nodes into a map by name (for function calls)
2. **Lower**: Second pass lowers each function individually with `JvmFunctionLowering`

`JvmInstructionLowering.lower(jvmProgram)` then flattens the structured IR into
`JvmInstructionProgram`. The flat layer owns stack-effect verification and label/jump resolution;
the emitter must not interpret structured expressions or control flow.

Key methods in `JvmFunctionLowering`:
- `lower` — lowers a whole function to a structured `JvmMethod`
- `lowerExpression` — lowers an AST expression to a structured `JvmExpression`
- `lowerStatement` — lowers an AST statement to a structured `JvmStatement`

### IR Design

**Structured IR (`JvmIr`, module `jvm-ir`)**

- **Expressions**: `JvmIr.Expression` sealed interface with subtypes for literals, variables, binary operations, function calls, etc.
- **Operators**: `IntBinaryOperator` and `ComparisonOperator` are independent enums (no shared sealed interface)
- **Statements**: `JvmIr.Statement` sealed interface (`JvmIr.ReturnStatement`, `JvmIr.VarDeclaration`, `JvmIr.AssignStatement`, `JvmIr.RebindStatement`, `JvmIr.PrintStatement`)
- **Program**: `JvmIr.Program` — collection of `JvmIr.Class` entries

**Flat instruction IR (`JvmInstruction`, module `jvm-instruction-lowering`)**

- `JvmInstructionProgram` contains classes/methods whose bodies are flat lists of `InstructionWithDebugInfo`
- Instructions expose `InstructionEffect` (stack pushes/pops) so the builder verifies stack discipline
- Labels are `JvmLabel`; control flow is explicit via `CreateLabel`, `BindLabel`, `JumpTo`, `IfFalseJumpTo`, `IntComparisonJumpTo`
- `GetStatic` carries both the stack type (`fieldType`) and the real JVM field descriptor (`fieldDescriptor`) so reference-typed fields verify correctly
- No structured expressions/statements remain at this layer; emitter consumes instructions only

## Conventions

### Kotlin

- Kotlin 2.4.0
- Target: Java 25 (source + bytecode compatibility)
- 4-space indentation
- Sealed interfaces for IR hierarchies
- `Jvm` prefix for structured IR types in `vera.jvm.ir`; flat instruction IR types are unprefixed in `vera.jvm.instruction`

### API Style

Public pipeline services (`VeraLexer`, `VeraParser`, `JvmProgramLowering`,
`JvmInstructionLowering`, `JvmBytecodeEmitter`, `VeraCompiler`) are stateless objects. Inputs are passed as method
arguments, and mutable state is scoped to a single call. Stateful helpers are private classes
named `*State` and are created per operation, so instances cannot accidentally retain state
between compilations.

### Testing

- JUnit 5 (JUnit 6 API) with JUnit Platform
- Tests in `compiler-tests` module are **integration tests** — they compile Vera code, load it via a custom classloader, execute, and compare stdout
- Test naming: `test_<scenario>_<detail>`

### Git

- Commit messages describe **what was done** in past tense (e.g. `added operator precedence`), not imperative instructions (e.g. `add operator precedence`)

### Naming

- **All AST types** use the `Vera` prefix (e.g., `VeraProgram`, `VeraExpression`, `VeraStatement`, `VeraBindStatement`, `VeraReturnStatement`, `VeraChainedExpression`, `VeraPrimaryExpression`, `VeraBinaryOperation`, `VeraOperator`, `VeraLiteral`, `VeraIntLiteral`, `VeraStringLiteral`, `VeraBoolLiteral`, `VeraIdentifierAsExpression`, `VeraIfExpression`, `VeraParameter`, `VeraMemberAccess`, `VeraArguments`, `VeraFunctionDeclaration`, `VeraDeclaration`)
- **All structured IR types** use the `Jvm` prefix and live in `vera.jvm.ir` (e.g., `JvmProgram`, `JvmReturn`, `JvmExpression`)
- **Flat instruction IR types** live in `vera.jvm.instruction` and are unprefixed (e.g., `GetStatic`, `InvokeVirtual`, `JvmInstructionProgram`)
- **Local variable names mirror their layer**: AST-derived values use `vera*` (e.g., `veraFunction`, `veraPrimaryExpression`, `veraType`), while IR/JVM-derived and lowering-time values use `jvm*` (e.g., `jvmMethodBuilder`, `jvmParameters`, `jvmEndLabel`, `jvmLocalBindings`, `JvmLocalBinding`). Short scalar names (`index`, `builtin`) may omit the prefix when the layer is unambiguous. Prefer precise nouns over prefix-only names: `JvmLocalBinding`, not `JvmLocal`, because `JvmLocal` would be confused with `LocalTable.Local`.
- **Lowering state classes** keep the full pipeline prefix: `JvmFunctionLoweringState`, not `FunctionLoweringState`.
- **Lowering methods use consistent verb families**: `lower*` for all AST-to-IR lowering (including helpers that append IR instructions), `validate*`/`require*` for operand checks, and `tryLower*` when returning `Either`. Reserve `emit*` for actual JVM bytecode generation in `JvmBytecodeEmitter`.
- JVM types: standard JVM naming (e.g., `VeraMain`, `VeraApp`)

### AGENTS.md

- This file documents project conventions, architecture, and important decisions
- When making changes that affect conventions (naming, structure, design decisions), update this file immediately
- New conventions and decisions should be recorded here automatically as they are made

## Parsing

The parser is recursive-descent. `VeraCompiler` invokes `VeraLexer` to produce tokens,
including significant `EOL` tokens, then passes them to `VeraParser`, which implements one
method per precedence level. Newlines terminate statements.

Blank lines and extra `EOL` tokens are ignored at block boundaries and between a closing
`}` and `else`, so empty lines inside blocks and before `else` are valid. CRLF is normalized
to a single `EOL` token by the lexer.

Key language constructs:
- Functions: `fn name(params) { body }`
- Variables: `var name = expr`
- Assignment: `name = expr`
- Control flow: `if`, `while`, `for`
- Operators (lowest to highest precedence): `|`, `or`, `and`, `==`/`!=`, `<`/`>`/`<=`/`>=`, `+`/`-`, `*`/`/`/`%`
- `and` and `or` are short-circuiting Bool operators and are reserved keywords
- `|` is forward application: `value | f` lowers to `f(value)`
- Built-in: `print`

## Known TODOs

- **VeraRebindStatement** (`x := newValue`): `JvmFunctionLowering.lowerStatement` — `TODO()` stub
- **VeraMemberAccess**: `JvmFunctionLowering.lowerChainedExpression` — `TODO()` stub for member access / field access

## Run / Debug

The `cli` module provides the entry point (`Main.kt`, default package). No Gradle application/run task is currently configured, so `./gradlew :cli:run` is unavailable. Add the `application` plugin to `cli/build.gradle.kts` to expose it; otherwise run `MainKt` manually with the built classpath from `cli/build/libs/`.
