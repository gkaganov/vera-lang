# Conventions

Authoritative details live in repo `AGENTS.md`; update it when changing conventions.

## Kotlin style
- 4-space indent. Sealed interfaces for IR hierarchies.
- Public pipeline services are stateless `object`s; per-call mutable state in private `*State` classes.
- No shared/global mutable state across compilations.
- Local variable names mirror their layer: AST-derived `vera*` (`veraFunction`, `veraPrimaryExpression`, `veraType`); IR/JVM-derived and lowering-time values `jvm*` (`jvmBuilder`, `jvmParameters`, `jvmEndLabel`, `jvmLocalBindings`, `JvmLocalBinding`).
- Prefer precise nouns over prefix-only names (`JvmLocalBinding`, not `JvmLocal`, which collides with `LocalTable.Local`).
- Lowering state classes keep the full pipeline prefix (`JvmFunctionLoweringState`).
- Verb families: `lower*` for AST->IR and structured->instruction lowering, `validate*`/`require*` for operand checks, `tryLower*` when returning `Either`. Use the `append(...)` helper (or the method builder) for one flat IR instruction.
- `emit*` means produce JVM bytecode, not append IR. Reserve `emit*` for `JvmBytecodeEmitter` and actual bytecode generation.

## Naming
- AST types: `Vera` prefix (`VeraProgram`, `VeraExpression`, ...).
- Structured IR: `JvmIr.` namespace prefix (`JvmProgram`, `JvmExpression`, `JvmComparison`, ...).
- Flat instruction IR: package `vera.jvm.instruction`, unprefixed instruction types (`GetStatic`, `InvokeVirtual`, `JvmInstructionProgram`).
- Tests: `test_<scenario>_<detail>`; compiler-tests use backtick prose names and `TestInfo`.

## IR layering
- `jvm-ir` structured IR: expressions/statements, no labels, jumps, slots, or builder.
- `jvm-instruction-lowering` flat IR: `InstructionWithDebugInfo`, `InstructionEffect`, labels, stack verification.
- `and`/`or` stay lazy in structured IR; short-circuit CFG is materialized in instruction lowering.
- Comparisons are structured; instruction lowering appends `IntComparisonJumpTo` + boolean materialization.
- `print` receiver is appended before the argument; the bytecode emitter must not use `swap()`.
- `GetStatic` carries `fieldType` for stack verification and `fieldDescriptor` for the real JVM field type.
- Emitter must not interpret structured expressions/control flow.

## Parser / language
- Recursive-descent parser; significant `EOL` tokens terminate statements.
- Blank lines/extra EOLs are ignored at block boundaries and between `}` and `else`; CRLF is normalized to one EOL.
- Precedence low->high: `|`, `or`, `and`, `==`/`!=`, `<`/`>`/`<=`/`>=`, `+`/`-`, `*`/`/`/`%`.
- `and`/`or` are short-circuiting Bool keywords; `|` is forward application; `print` is built-in.
- `while` is a statement (`VeraWhileStatement`): `while <Bool condition> { body }`; it lowers to `JvmWhile` and then a condition-label/body/jump-back/end-label CFG. `for` is not implemented.
- Rebinding is `name = expr` (`VeraRebindStatement` -> `JvmRebind` -> `StoreLocal`). The old `:=` operator was removed.

## Git
- Commit messages describe what was done in past tense (e.g. `added operator precedence`).

## Known TODOs
- `VeraMemberAccess` lowering is `TODO()`.
