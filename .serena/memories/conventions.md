# Conventions

Authoritative, detailed rules live in repo `AGENTS.md`; keep it updated when changing conventions.

## Kotlin style
- 4-space indent. Sealed interfaces for IR hierarchies.
- Public pipeline services are stateless `object`s; per-call mutable state in private `*State` classes.
- Do not add shared/global mutable state across compilations.
- Local variable names mirror their layer: AST-derived `vera*` (`veraFunction`, `veraPrimaryExpression`, `veraType`); IR/JVM-derived and lowering-time values `jvm*` (`jvmMethodBuilder`, `jvmParameters`, `jvmEndLabel`, `jvmLocalBindings`, `JvmLocalBinding`). Prefer precise nouns over prefix-only names (`JvmLocalBinding`, not `JvmLocal`, which collides conceptually with `LocalTable.Local`).
- Lowering state classes keep the full pipeline prefix (`JvmFunctionLoweringState`).
- Lowering verb families: `lower*` (all AST->IR, including helpers that append IR instructions), `validate*`/`require*` (operand checks), `tryLower*` (returns `Either`). Reserve `emit*` for actual JVM bytecode generation in `JvmBytecodeEmitter`.

## Naming
- AST types: `Vera` prefix (`VeraProgram`, `VeraExpression`, `VeraStatement`, `VeraBindStatement`, `VeraReturnStatement`, `VeraChainedExpression`, `VeraPrimaryExpression`, `VeraBinaryOperation`, `VeraOperator`, `VeraLiteral`, `VeraIntLiteral`, `VeraStringLiteral`, `VeraBoolLiteral`, `VeraIdentifierAsExpression`, `VeraIfExpression`, `VeraParameter`, `VeraMemberAccess`, `VeraArguments`, `VeraFunctionDeclaration`, `VeraDeclaration`).
- IR types: `JvmIr.` namespace prefix (`JvmIr.Program`, `JvmIr.ReturnStatement`, `JvmIr.Expression`, ...).
- JVM-level names: standard (`VeraMain`, `VeraApp`).
- Tests: `test_<scenario>_<detail>`; compiler-tests use backtick prose names. `fun name(testInfo: TestInfo)`.

## IR design
- `JvmIr.Expression` is a sealed interface (literals, variables, binary ops, calls, ...).
- `IntBinaryOperator` and `ComparisonOperator` are independent enums, no shared operator interface.
- `JvmIr.Statement` sealed interface: `ReturnStatement`, `VarDeclaration`, `AssignStatement`, `RebindStatement`, `PrintStatement`.
- `JvmIr.Program` holds `JvmIr.Class` entries.

## Parser / language
- Recursive-descent parser; `VeraLexer` emits significant `EOL` tokens; newlines terminate statements.
- One parser method per precedence level.
- Precedence low->high: `|`, `or`, `and`, `==`/`!=`, `<`/`>`/`<=`/`>=`, `+`/`-`, `*`/`/`/`%`.
- `and`/`or` are short-circuiting Bool keywords; `|` is forward application `value | f` -> `f(value)`; `print` is built-in.

## Lowering
- `JvmProgramLowering.lower(mainClassName, program)` 2-phase: collect declarations by name, then lower each function.
- Key `JvmFunctionLowering` methods: `lower`, `lowerExpression` (returns type), `lowerStatement`.

## Git
- Commit messages describe what was done in past tense (e.g. `added operator precedence`), not imperative.

## Known stubs / TODOs
- `VeraRebindStatement` (`x := v`) lowering is `TODO()` in `JvmFunctionLowering.lowerStatement`.
- `VeraMemberAccess` lowering is `TODO()` in `JvmFunctionLowering.lowerChainedExpression`.
