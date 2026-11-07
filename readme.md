# Vera

Vera is a small, experimental programming language targeting the JVM.

## Documentation

- [Language guide](docs/language.md) — current syntax and language status
- [Design philosophy](docs/design-philosophy.md) — long-term vision and compiler principles
- [AGENTS.md](AGENTS.md) — contributor and coding-agent conventions

## Compiler stages

| Stage | Prefix | Key types | Purpose |
|---|---|---|---|
| Lexing | `Vera*` | `VeraLexer`, `VeraToken` | Source text → tokens |
| Parsing | `Vera*` | `VeraParser`, AST types | Tokens → AST |
| AST lowering | `Jvm*` | `JvmProgramLowering`, `JvmFunctionLowering` | AST → structured JVM IR |
| Instruction lowering | `Flat*` | `FlatInstructionLowering`, `FlatMethodBuilder` | Structured IR → flat instruction IR |
| Bytecode emission | `Bytecode*` | `BytecodeEmitter` | Flat IR → JVM `.class` files |
| Orchestration | `Vera*` | `VeraCompiler` | Runs the full pipeline |

See [`AGENTS.md`](AGENTS.md) for module details and naming conventions.

## Development

```bash
./gradlew build
./gradlew :compiler-tests:test
```
