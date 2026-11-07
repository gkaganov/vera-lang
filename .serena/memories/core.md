# Vera Lang — Core Map

Vera: small experimental language compiling to JVM `.class` via the Java ClassFile API.
Repo `AGENTS.md` is authoritative for conventions and architecture; read it first.

## Source layout
All Gradle modules under `modules/<name>` (mapped in `settings.gradle.kts`).
- `shared-model` — `vera.shared.model` (`Identifier`, `SourceLocation`, `VeraException`)
- `ast` — `vera.ast.VeraAst.kt` (all AST nodes, `Vera*` prefix)
- `parser` — `vera.parser`: `VeraLexer`, `VeraParser`, `VeraToken`
- `jvm-ir` — `vera.jvm.ir`: structured IR (`JvmProgram`, `JvmExpression`, `JvmStatement`, ...)
- `jvm-lowering` — `vera.jvm.lowering`: AST -> structured IR (`JvmProgramLowering`, `JvmFunctionLowering`)
- `jvm-instruction-lowering` — `vera.jvm.instruction` + `.lowering`: structured IR -> flat instruction IR (`FlatInstructionLowering`, `FlatMethodBuilder`)
- `jvm-bytecode-emitter` — `vera.jvm.bytecode.emitter.BytecodeEmitter`: flat instructions -> `.class`
- `compiler` — `vera.compiler.VeraCompiler.compile(mainClassName, code): Map<Identifier, ByteArray>` (throws `VeraException` on failure)
- `cli` — root package `Main.kt`, reads `examples/main.vera`; no Gradle `run` task configured
- `compiler-tests` — integration and compiler-error tests (`VeraCompilerTests`, `CompilerErrorTests`) + `ByteArrayClassLoader`

## Pipeline invariant
`VeraCompiler.compile`: tokenize -> parse -> `JvmProgramLowering.lower` -> `FlatInstructionLowering.lower` -> `BytecodeEmitter.emit`; stages throw named `VeraException` subtypes on failure.
Structured `jvm-ir` contains no labels/jumps/slots. Flat `FlatProgram` owns control-flow labels, stack effects, and local slots. Emitter only maps flat instructions.

## References
- Build/test commands: `mem:suggested_commands`
- Code style, naming, IR layering rules: `mem:conventions`
- Completion checks: `mem:task_completion`
- Toolchain/version pins: `mem:tech_stack`
- Structured/flat IR architecture and change guide: `mem:architecture/structured_jvm_ir`
