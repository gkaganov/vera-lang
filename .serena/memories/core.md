# Vera Lang — Core Map

Vera: small experimental language, JVM target. Compiles to `.class` via Java ClassFile API.
Full architecture/conventions are maintained in repo `AGENTS.md` (read it first).

## Source layout
All Gradle modules live under `modules/<name>` (mapped in `settings.gradle.kts`).
- `shared-model` — `vera.shared.model` (`Identifier`, shared types)
- `ast` — `vera.ast.VeraAst.kt` (all AST node defs)
- `parser` — `vera.parser`: `VeraLexer`, `VeraParser`, `Token`
- `jvm-ir` — `vera.jvm.ir`: `JvmIr`, `JvmMethodBuilder`
- `jvm-lowering` — `vera.jvm.lowering`: `JvmProgramLowering` (public entry), `JvmFunctionLowering` (internal)
- `jvm-bytecode-emitter` — `vera.jvm.bytecode.emitter.JvmBytecodeEmitter`
- `compiler` — `vera.compiler.VeraCompiler.compile(mainClassName, code): Map<Identifier, ByteArray>`
- `cli` — root package `Main.kt`, reads `examples/main.vera`, writes `out/<Class>.class`
- `compiler-tests` — integration tests `vera.compiler.tests.VeraCompilerTests` + `ByteArrayClassLoader`

## Pipeline invariant
`VeraCompiler.compile`: tokenize -> parse -> `JvmProgramLowering.lower` -> `JvmBytecodeEmitter.emit`.
Public pipeline services are stateless `object`s, inputs as args; mutable state in private per-call `*State` helper classes.
`JvmProgramLowering.lower` is 2-phase: collect function declarations by name, then lower each with `JvmFunctionLowering`.

## References
- Build/test commands and running the compiler: `mem:suggested_commands`
- Code style, naming, IR/parser design rules: `mem:conventions`
- Completion checks before declaring work done: `mem:task_completion`
- Toolchain and version pins: `mem:tech_stack`
- Planned two-layer structured JVM IR refactor and migration checklist: `mem:plans/structured_jvm_ir`
