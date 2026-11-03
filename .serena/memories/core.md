# Vera Lang — Core Map

Vera: small experimental language compiling to JVM `.class` via the Java ClassFile API.
Repo `AGENTS.md` is authoritative for conventions and architecture; read it first.

## Source layout
All Gradle modules under `modules/<name>` (mapped in `settings.gradle.kts`).
- `shared-model` — `vera.shared.model` (`Identifier`, shared types)
- `ast` — `vera.ast.VeraAst.kt` (all AST nodes, `Vera*` prefix)
- `parser` — `vera.parser`: `VeraLexer`, `VeraParser`, `Token`
- `jvm-ir` — `vera.jvm.ir`: structured IR (`JvmProgram`, `JvmExpression`, `JvmStatement`, ...)
- `jvm-lowering` — `vera.jvm.lowering`: AST -> structured IR (`JvmProgramLowering`, `JvmFunctionLowering`)
- `jvm-instruction-lowering` — `vera.jvm.instruction` + `.lowering`: structured IR -> flat instruction IR (`JvmInstructionLowering`, `JvmInstructionMethodBuilder`)
- `jvm-bytecode-emitter` — `vera.jvm.bytecode.emitter.JvmBytecodeEmitter`: flat instructions -> `.class`
- `compiler` — `vera.compiler.VeraCompiler.compile(mainClassName, code): Map<Identifier, ByteArray>`
- `cli` — root package `Main.kt`, reads `examples/main.vera`; no Gradle `run` task configured
- `compiler-tests` — integration tests `vera.compiler.tests.VeraCompilerTests` + `ByteArrayClassLoader`

## Pipeline invariant
`VeraCompiler.compile`: tokenize -> parse -> `JvmProgramLowering.lower` -> `JvmInstructionLowering.lower` -> `JvmBytecodeEmitter.emit`.
Structured `jvm-ir` contains no labels/jumps/slots. Flat `JvmInstructionProgram` owns control-flow labels, stack effects, and local slots. Emitter only maps flat instructions.

## References
- Build/test commands: `mem:suggested_commands`
- Code style, naming, IR layering rules: `mem:conventions`
- Completion checks: `mem:task_completion`
- Toolchain/version pins: `mem:tech_stack`
- Structured/flat IR architecture and change guide: `mem:architecture/structured_jvm_ir`
