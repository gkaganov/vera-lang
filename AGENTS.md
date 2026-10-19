# Vera Lang

Vera is a small, experimental programming language with a JVM target. It uses ANTLR for parsing, a 2-phase IR lowering pipeline, and generates JVM bytecode via the Java ClassFile API.

## Quick Start

```bash
./gradlew build                    # Compile, test, and assemble
./gradlew :compiler-tests:test     # Run integration tests
./gradlew :compiler-tests:test --tests "compiler.tests.CompilerTests.test_fibonacci"  # Single test
```

Build output goes to `out/` (compiler) and `build/` (Gradle).

## Architecture

```
Vera source → ANTLR parser → AST → JvmIr (IR) → JvmLowering (2-phase) → Bytecode Emitter → .class files
```

### Pipeline Overview (`VeraCompiler`)

1. **Parse**: `Vera.g4` grammar → ANTLR parse tree → AST (via `VeraParserAntlrVisitor`)
2. **Lower**: `JvmLowering` transforms AST → JVM IR (`JvmIr`)
3. **Emit**: `JvmBytecodeEmitter` generates `.class` files using Java ClassFile API

### Modules

| Module | Purpose |
|--------|---------|
| `shared-model` | Shared domain model (types, expressions, etc.) |
| `antlr-parser` | ANTLR grammar (`Vera.g4`) + parse tree visitor → AST |
| `ast` | AST node definitions |
| `jvm-ir` | JVM intermediate representation — classes, methods, fields, expressions, statements, operators |
| `jvm-lowering` | AST → IR lowering (`JvmLowering`) |
| `jvm-bytecode-emitter` | IR → JVM `.class` file generation |
| `compiler` | Orchestration: `VeraCompiler` ties parse → lower → emit |
| `cli` | Command-line entry point |
| `compiler-tests` | Integration tests — compiles Vera code, runs via custom classloader, asserts stdout |

### Lowering Details

`JvmLowering` uses a **2-phase approach**:
1. **Collect**: First pass collects all `VeraFunctionDeclaration` AST nodes into a map by name (for function calls)
2. **Lower**: Second pass lowers each function individually

Key methods in `JvmLowering`:
- `processExpression` — converts AST expressions to IR
- `processBlock` — converts AST block statements to IR
- `processStatement` — converts AST statements to IR

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

### Testing

- JUnit 5 (JUnit 6 API) with JUnit Platform
- Tests in `compiler-tests` module are **integration tests** — they compile Vera code, load it via a custom classloader, execute, and compare stdout
- Test naming: `test_<scenario>_<detail>`

### Naming

- **All AST types** use the `Vera` prefix (e.g., `VeraProgram`, `VeraExpression`, `VeraStatement`, `VeraBindStatement`, `VeraReturnStatement`, `VeraChainedExpression`, `VeraPrimaryExpression`, `VeraLiteral`, `VeraIntLiteral`, `VeraStringLiteral`, `VeraBoolLiteral`, `VeraIdentifierAsExpression`, `VeraIfExpression`, `VeraParameter`, `VeraMemberAccess`, `VeraArguments`, `VeraFunctionDeclaration`, `VeraDeclaration`)
- **All IR types** use the `JvmIr.` namespace prefix (e.g., `JvmIr.ReturnStatement`, `JvmIr.Program`, `JvmIr.Expression`)
- JVM types: standard JVM naming (e.g., `VeraMain`, `VeraApp`)

### AGENTS.md

- This file documents project conventions, architecture, and important decisions
- When making changes that affect conventions (naming, structure, design decisions), update this file immediately
- New conventions and decisions should be recorded here automatically as they are made

## Grammar (`Vera.g4`)

Layout-sensitive, EOL-based statement termination. Key constructs:
- Functions: `fn name(params) { body }`
- Variables: `var name = expr`
- Assignment: `name = expr`
- Control flow: `if`, `while`, `for`
- Operators: `+`, `-`, `*`, `/`, `%`, `==`, `!=`, `<`, `>`, `<=`, `>=`, `&&`, `||`
- Built-in: `print`

## Known TODOs

- **VeraRebindStatement** (`x := newValue`): `JvmLowering.kt:109` — `TODO()` stub
- **VeraMemberAccess**: `JvmLowering.kt:195` — `TODO()` stub for member access / field access

## Run / Debug

The `cli` module provides the entry point. Run via:
```bash
./gradlew :cli:run
```

Or build the fat JAR and run directly (check `cli/build/libs/` after build).
