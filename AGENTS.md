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
Vera source → lexer/parser → AST → JvmIr (IR) → JvmProgramLowering (2-phase) → Bytecode Emitter → .class files
```

### Pipeline Overview (`VeraCompiler`)

1. **Parse**: source → `VeraLexer` token stream → recursive-descent `VeraParser` → AST
2. **Lower**: `JvmProgramLowering` transforms AST → JVM IR (`JvmIr`) using `JvmFunctionLowering` per function
3. **Emit**: `JvmBytecodeEmitter` generates `.class` files using Java ClassFile API

### Modules

| Module                 | Purpose                                                                                        |
|------------------------|------------------------------------------------------------------------------------------------|
| `shared-model`         | Shared domain model (types, expressions, etc.)                                                 |
| `parser`               | Lexer + recursive-descent parser → AST                                                         |
| `ast`                  | AST node definitions                                                                           |
| `jvm-ir`               | JVM intermediate representation — classes, methods, fields, expressions, statements, operators |
| `jvm-lowering`         | AST → IR lowering (`JvmProgramLowering`, `JvmFunctionLowering`)                                 |
| `jvm-bytecode-emitter` | IR → JVM `.class` file generation                                                              |
| `compiler`             | Orchestration: `VeraCompiler` ties parse → lower → emit                                        |
| `cli`                  | Command-line entry point                                                                       |
| `compiler-tests`       | Integration tests — compiles Vera code, runs via custom classloader, asserts stdout            |

### Lowering Details

`JvmProgramLowering` is the module's public entry point. `JvmFunctionLowering` is an
internal stateless object; the per-function mutable state (locals and the method builder)
lives in a private helper created for each call.

`JvmProgramLowering.lower(mainClassName, program)` uses a **2-phase approach**:
1. **Collect**: First pass collects all `VeraFunctionDeclaration` AST nodes into a map by name (for function calls)
2. **Lower**: Second pass lowers each function individually with `JvmFunctionLowering`

Key methods in `JvmFunctionLowering`:
- `lower` — lowers a whole function to a `JvmMethod`
- `lowerExpression` — lowers AST expressions and returns their type
- `lowerStatement` — lowers AST statements to IR instructions

### IR Design (`JvmIr`)

- **Expressions**: `JvmIr.Expression` sealed interface with subtypes for literals, variables, binary operations, function calls, etc.
- **Operators**: `IntBinaryOperator` and `ComparisonOperator` are independent enums (no shared sealed interface)
- **Statements**: `JvmIr.Statement` sealed interface (`JvmIr.ReturnStatement`, `JvmIr.VarDeclaration`, `JvmIr.AssignStatement`, `JvmIr.RebindStatement`, `JvmIr.PrintStatement`)
- **Program**: `JvmIr.Program` — collection of `JvmIr.Class` entries

## Conventions

### Kotlin

- Kotlin 2.4.0
- Target: Java 25 (source + bytecode compatibility)
- 4-space indentation
- Sealed interfaces for IR hierarchies
- `JvmIr.` namespace prefix on all IR types

### API Style

Public pipeline services (`VeraLexer`, `VeraParser`, `JvmProgramLowering`,
`JvmBytecodeEmitter`, `VeraCompiler`) are stateless objects. Inputs are passed as method
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
- **All IR types** use the `JvmIr.` namespace prefix (e.g., `JvmIr.ReturnStatement`, `JvmIr.Program`, `JvmIr.Expression`)
- JVM types: standard JVM naming (e.g., `VeraMain`, `VeraApp`)

### AGENTS.md

- This file documents project conventions, architecture, and important decisions
- When making changes that affect conventions (naming, structure, design decisions), update this file immediately
- New conventions and decisions should be recorded here automatically as they are made

## Parsing

The parser is recursive-descent. `VeraCompiler` invokes `VeraLexer` to produce tokens,
including significant `EOL` tokens, then passes them to `VeraParser`, which implements one
method per precedence level. Newlines terminate statements.

Key language constructs:
- Functions: `fn name(params) { body }`
- Variables: `var name = expr`
- Assignment: `name = expr`
- Control flow: `if`, `while`, `for`
- Operators (lowest to highest precedence): `|`, `or`, `and`, `==`/`!=`, `<`/`>`/`<=`/`>=`, `+`/`-`, `*`/`/`/`%`
- `and` and `or` are reserved keywords and cannot be used as identifiers
- Built-in: `print`

## Known TODOs

- **VeraRebindStatement** (`x := newValue`): `JvmFunctionLowering.lowerStatement` — `TODO()` stub
- **VeraMemberAccess**: `JvmFunctionLowering.lowerChainedExpression` — `TODO()` stub for member access / field access
- **Logical and forward-application operators** (`and`, `or`, `|`): parser/AST support exists, but `JvmFunctionLowering.lowerBinaryOperation` rejects them

## Run / Debug

The `cli` module provides the entry point. Run via:
```bash
./gradlew :cli:run
```

Or build the fat JAR and run directly (check `cli/build/libs/` after build).
