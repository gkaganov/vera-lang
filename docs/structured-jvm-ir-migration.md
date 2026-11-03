# Structured JVM IR Migration Plan

**Status:** implemented
**Audience:** AI coding agents  
**Goal:** split semantic lowering from JVM instruction encoding so `jvm-lowering` does not emit labels, jumps, local slots, or bytecode-oriented instructions.

This document was the implementation plan for the structured JVM IR migration. It is kept as an architecture reference; the migration is complete.

## Mission

Replace the current single flat IR with two layers:

```text
Vera source
  -> lexer/parser
  -> AST
  -> JvmProgramLowering          // AST -> structured JVM IR
  -> structured JvmIr            // semantic, typed, backend-facing, no labels/jumps
  -> JvmInstructionLowering      // structured IR -> low-level instruction IR
  -> low-level instruction IR    // slots, labels, jumps, primitive instructions
  -> JvmBytecodeEmitter          // instruction IR -> .class
```

Required outcomes:

- `jvm-lowering` produces structured expressions/statements only.
- `jvm-lowering` never creates labels, jumps, local slots, or method-builder instructions.
- `and` / `or` are represented as structured `JvmLogicalAnd` / `JvmLogicalOr` nodes.
- Comparisons are represented as a structured `JvmComparison` node.
- Short-circuit evaluation is implemented by `JvmInstructionLowering`, not by the bytecode emitter.
- `print` is lowered so the `System.out` receiver is emitted before the argument; the bytecode emitter must no longer use `swap()`.
- `JvmBytecodeEmitter` maps primitive instruction IR to JVM bytecode 1:1 and contains no semantic expansion for comparisons or print.
- Existing language behavior and all existing integration tests remain unchanged.

## Non-goals

Do not implement these as part of this migration:

- New language features.
- `while`, `for`, member access, or `VeraRebindStatement` support.
- A non-JVM backend.
- Optimization passes such as constant folding or CSE.
- Parser or AST changes, except purely mechanical renaming if unavoidable.
- Keeping source/binary compatibility with the old flat `jvm-ir` types.

## Locked design decisions

1. **Two IR layers**
   - Structured IR lives in module `jvm-ir`, package `vera.jvm.ir`.
   - Low-level instruction IR lives in a new module, package `vera.jvm.instruction`.
   - The lowering pass between them lives in package `vera.jvm.instruction.lowering`.

2. **New module**
   - Add `modules/jvm-instruction-lowering`.
   - Include it in `settings.gradle.kts` under `modules/`.
   - It depends on `:jvm-ir`.
   - `:jvm-bytecode-emitter` depends on `:jvm-instruction-lowering`, not directly on `:jvm-ir` for instruction types.
   - `:jvm-lowering` depends only on `:jvm-ir`.
   - `:compiler` depends on `:jvm-lowering`, `:jvm-instruction-lowering`, and `:jvm-bytecode-emitter`.

3. **Structured IR has no control-flow primitives**
   - No `JvmLabel`, `CreateLabel`, `BindLabel`, `JumpTo`, `IfFalseJumpTo`, no `LocalTable`, no slot numbers, no `JvmMethodBuilder`.
   - `if` is represented structurally and control flow is materialized later.
   - `and` / `or` retain lazy semantics in the structured node; they must not be eager boolean operations.

4. **Low-level IR owns encoding decisions**
   - Local slot allocation, label creation/binding, comparison materialization, short-circuit branches, and print receiver ordering happen in `JvmInstructionLowering`.
   - The bytecode emitter only maps primitive instructions to ClassFile API calls.

5. **Keep existing test behavior**
   - All existing `compiler-tests` must pass without changing expected outputs.
   - Add tests only for new structural/encoding guarantees.

## Target structured IR (`jvm-ir`, package `vera.jvm.ir`)

Use a `Jvm` prefix for all structured IR types. Keep shared JVM types (`JvmType`, `JvmMethodSignature`, `JvmParameter`, `IntBinaryOperator`, `ComparisonOperator`) where practical.

```kotlin
data class JvmProgram(val classes: List<JvmClass>)
data class JvmClass(val name: Identifier, val methods: List<JvmMethod>)
data class JvmMethod(
    val name: Identifier,
    val parameters: List<JvmParameter>,
    val returnType: JvmType,
    val body: List<JvmStatement>,
)
data class JvmParameter(val name: Identifier, val type: JvmType)
data class JvmMethodSignature(val parameters: List<JvmParameter>, val returnType: JvmType)
data class JvmLocal(val name: Identifier, val type: JvmType)

sealed interface JvmExpression {
    val type: JvmType
}

data class JvmIntLiteral(val value: Int) : JvmExpression
data class JvmStringLiteral(val value: String) : JvmExpression
data class JvmBoolLiteral(val value: Boolean) : JvmExpression
data class JvmLocalRead(val local: JvmLocal) : JvmExpression

data class JvmIntBinary(
    val operator: IntBinaryOperator,
    val left: JvmExpression,
    val right: JvmExpression,
) : JvmExpression

data class JvmComparison(
    val operator: ComparisonOperator,
    val left: JvmExpression,
    val right: JvmExpression,
) : JvmExpression

data class JvmLogicalAnd(
    val left: JvmExpression,
    val right: JvmExpression,
) : JvmExpression

data class JvmLogicalOr(
    val left: JvmExpression,
    val right: JvmExpression,
) : JvmExpression

data class JvmStaticCall(
    val ownerClass: Identifier,
    val methodName: Identifier,
    val arguments: List<JvmExpression>,
    val signature: JvmMethodSignature,
) : JvmExpression

enum class JvmBuiltin { PRINT }

data class JvmBuiltinCall(
    val builtin: JvmBuiltin,
    val arguments: List<JvmExpression>,
) : JvmExpression

data class JvmIfExpression(
    val condition: JvmExpression,
    val thenBlock: List<JvmStatement>,
    val elseBlock: List<JvmStatement>,
) : JvmExpression

sealed interface JvmStatement
data class JvmReturn(val expression: JvmExpression?) : JvmStatement
data class JvmVarDeclaration(val local: JvmLocal, val expression: JvmExpression) : JvmStatement
data class JvmAssign(val local: JvmLocal, val expression: JvmExpression) : JvmStatement
data class JvmRebind(val local: JvmLocal, val expression: JvmExpression) : JvmStatement
data class JvmExpressionStatement(val expression: JvmExpression) : JvmStatement
```

Notes:

- `JvmExpression.type` is authoritative. `JvmIfExpression.type` is `JvmType.VOID` for current Vera semantics. `JvmBuiltinCall(PRINT)` is `JvmType.VOID`.
- `JvmLocal` identity is its `name` + `type`; current Vera has no shadowing that would break this. If shadowing is added later, introduce a unique local id then.
- `JvmRebind` is a placeholder for the existing `VeraRebindStatement` TODO; keep the `TODO()` behavior in AST lowering for now.

## Target low-level instruction IR (`jvm-instruction-lowering`, package `vera.jvm.instruction`)

Copy and adapt the existing flat model from `jvm-ir`. Prefix the program/class/method containers with `JvmInstruction` to avoid collisions with structured IR:

```kotlin
data class JvmInstructionProgram(val classes: List<JvmInstructionClass>)
data class JvmInstructionClass(val name: Identifier, val methods: List<JvmInstructionMethod>)
data class JvmInstructionMethod(
    val name: Identifier,
    val instructions: List<InstructionWithDebugInfo>,
    val signature: JvmMethodSignature,
)
```

Keep the existing instruction names for instruction nodes:

- `LoadLocal`, `StoreLocal`, `LoadConstant`
- `IntBinaryOperation`
- `Invokestatic`
- `GetStatic` (new)
- `InvokeVirtual` (new)
- `IntComparisonJumpTo` (new; maps 1:1 to `if_icmp*`)
- `CreateLabel`, `BindLabel`, `JumpTo`, `IfFalseJumpTo`
- `Return`

Remove these from the low-level layer:

- `ComparisonOperation` — replaced by `IntComparisonJumpTo` plus boolean materialization in instruction lowering.
- `Print` — replaced by `GetStatic` + argument + `InvokeVirtual`.

Move/adapt these support types from `jvm-ir` into the new module:

- `InstructionEffect`
- `InstructionWithDebugInfo`
- `DebugFrameSnapshot`
- `LocalTable` / `LocalTable.Slot` / `LocalTable.Local`
- `OperandStack`
- `JvmMethodBuilder` -> rename to `JvmInstructionMethodBuilder`

`JvmInstructionLowering` is a stateless public object:

```kotlin
object JvmInstructionLowering {
    fun lower(program: JvmProgram): JvmInstructionProgram
}
```

Use a private per-lower `JvmInstructionLoweringState` class for mutable state (local slots, method builder), consistent with project conventions.

## Required instruction-lowering semantics

### Locals

- Parameters are allocated first, in order.
- `JvmVarDeclaration` allocates a slot for its `JvmLocal`, lowers the initializer, then emits `StoreLocal`.
- `JvmLocalRead` emits `LoadLocal`.

### Expressions

- Literals -> `LoadConstant`.
- `JvmLocalRead` -> `LoadLocal`.
- `JvmIntBinary` -> lower left, lower right, emit `IntBinaryOperation`.
- `JvmComparison` -> lower left, lower right, then materialize a boolean using `IntComparisonJumpTo`, labels, and `LoadConstant(BOOL, ...)`. The emitter must not expand comparisons.
- `JvmLogicalAnd` / `JvmLogicalOr` -> generate short-circuit CFG with a result local, matching current behavior.
- `JvmStaticCall` -> lower arguments left-to-right, emit `Invokestatic`.
- `JvmBuiltinCall(PRINT)` -> emit `GetStatic(System.out)`, lower the single argument, emit `InvokeVirtual(PrintStream.println)` for the argument type. Do not emit a swap.
- `JvmIfExpression` -> generate branches/labels matching current `VeraIfExpression` behavior, including the empty-else optimization.

### Statements

- `JvmReturn` -> lower optional expression, emit `Return(type)` or `Return()`.
- `JvmVarDeclaration` -> as described above.
- `JvmExpressionStatement` -> lower expression.
- `JvmAssign` / `JvmRebind` -> not required beyond keeping current TODO/unsupported behavior.

## Required emitter changes

`JvmBytecodeEmitter` consumes `JvmInstructionProgram` and must:

- Map `IntComparisonJumpTo` directly to `if_icmp*`.
- Map `GetStatic` to `codeBuilder.getstatic(...)`.
- Map `InvokeVirtual` to `codeBuilder.invokevirtual(...)`.
- Keep `Invokestatic`, loads/stores, labels, jumps, `LoadConstant`, `IntBinaryOperation`, `Return`.
- Delete `ComparisonOperation` expansion.
- Delete `Print` handling and the `swap()` call.
- Contain no semantic lowering for `and`, `or`, comparison, or print.

## Implementation phases

Follow these phases in order. Phase 1 and Phase 2 should leave `./gradlew build` green.

### Phase 1 — add the low-level module without changing the pipeline

1. Add `modules/jvm-instruction-lowering/build.gradle.kts`:
   - `api(project(":jvm-ir"))`
2. Add the module to `settings.gradle.kts`.
3. Copy the current flat IR model and `JvmMethodBuilder` from `jvm-ir` into the new module under `vera.jvm.instruction`, using the names specified above.
4. Add `GetStatic`, `InvokeVirtual`, and `IntComparisonJumpTo` instruction definitions with appropriate `InstructionEffect`.
5. Keep the old `jvm-ir` flat types and existing pipeline untouched.
6. Run `./gradlew build`. It must pass with the new module unused.

### Phase 2 — add structured IR types

1. Add structured IR definitions to `jvm-ir` (new file recommended: `JvmStructuredIr.kt`).
2. Remove nothing yet and do not change the pipeline.
3. Run `./gradlew build`. It must pass.

### Phase 3 — implement `JvmInstructionLowering`

1. Implement `JvmInstructionLowering` in `jvm-instruction-lowering`.
2. Implement all expression/statement lowering rules above.
3. Add focused unit tests in the new module if practical. If adding tests, add `testImplementation(libs.junit.jupiter)` and `tasks.test { useJUnitPlatform() }`.
   - At minimum, test that `JvmBuiltinCall(PRINT, ...)` lowers to `GetStatic` before the argument evaluation and contains no swap-like instruction.
   - Test `JvmLogicalAnd` / `JvmLogicalOr` produce short-circuit labels/jumps.
4. Run `./gradlew build`. It must pass with the new lowering unused by the compiler.

### Phase 4 — cut over the pipeline

1. Rewrite `JvmProgramLowering` / `JvmFunctionLowering` to produce structured `JvmProgram`, `JvmClass`, `JvmMethod`, `JvmStatement`, and `JvmExpression`.
   - Keep the existing 2-phase function collection behavior.
   - Keep all current type checks and error messages where possible.
   - Remove all label/jump/local-slot/method-builder usage from `jvm-lowering`.
2. Update `VeraCompiler.compile` to:
   - parse -> `JvmProgramLowering.lower` -> `JvmInstructionLowering.lower` -> `JvmBytecodeEmitter.emit`.
3. Update `jvm-bytecode-emitter` to consume `JvmInstructionProgram` from the new module.
4. Update Gradle dependencies:
   - `:compiler` -> add `:jvm-instruction-lowering`
   - `:jvm-bytecode-emitter` -> replace `:jvm-ir` with `:jvm-instruction-lowering`
   - `:jvm-lowering` -> keep `:jvm-ir`
5. Run `./gradlew build` and fix all tests.

### Phase 5 — remove obsolete flat IR and update docs

1. Delete the old flat IR types and `JvmMethodBuilder` from `jvm-ir`:
   - `JvmInstruction`, `InstructionEffect`, `InstructionWithDebugInfo`, `DebugFrameSnapshot`
   - `LoadLocal`, `StoreLocal`, `LoadConstant`, `IntBinaryOperation`, `ComparisonOperation`, `Invokestatic`, `CreateLabel`, `BindLabel`, `JumpTo`, `IfFalseJumpTo`, `Print`, `Return`
   - `LocalTable`, `OperandStack`, `JvmMethodBuilder`
2. Keep only structured IR and shared types in `jvm-ir`.
3. Update `AGENTS.md`:
   - Pipeline diagram and pipeline overview.
   - Module table (add `jvm-instruction-lowering`).
   - Lowering details.
   - IR design section: structured `jvm-ir`, low-level instruction IR, and the rule that lowering owns semantics while instruction lowering owns encoding.
   - Known TODOs (remove obsolete references to `JvmFunctionLowering` builder behavior if changed).
4. Update Serena memory `mem:core` and add/update a memory for this architecture.
5. Run `./gradlew build` and `./gradlew :cli:run`.

## Verification checklist

Run all of these before declaring the migration complete:

```bash
./gradlew build
```

`./gradlew :cli:run` is not currently configured in this build; do not use it as a completion check.

Then verify by static search:

```bash
# jvm-lowering must not contain backend/instruction concepts
rg -n "JvmMethodBuilder|CreateLabel|BindLabel|JumpTo|IfFalseJumpTo|LocalTable|Compare|ComparisonOperation" modules/jvm-lowering

# emitter must not contain swapped print or comparison expansion
rg -n "swap\(|ComparisonOperation|is Print" modules/jvm-bytecode-emitter

# structured IR must not contain low-level instruction types
rg -n "CreateLabel|BindLabel|JumpTo|IfFalseJumpTo|LocalTable|JvmMethodBuilder" modules/jvm-ir
```

Expected: no matches, except intentional references in documentation/comments.

All existing `compiler-tests` must pass. Add tests for:

- `print(1 + 2 * 3)` printing `7`.
- `print` of a function call / forward application.
- Existing short-circuit tests still proving RHS is not evaluated.
- Comparison results printed as booleans.

## Commit guidance

Use past-tense commit messages consistent with project convention, for example:

- `added structured jvm ir and instruction lowering module`
- `moved comparison and short-circuit lowering out of the bytecode emitter`
- `removed swap from print bytecode emission`
