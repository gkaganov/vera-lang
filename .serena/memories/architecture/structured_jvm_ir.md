# Structured JVM IR Architecture

Status: implemented.

## Pipeline
`AST -> JvmIr (structured) -> JvmInstructionProgram (flat) -> bytecode`

- `jvm-ir` (`vera.jvm.ir`): structured expressions/statements only. No labels, jumps, local slots, or method builder.
- `jvm-lowering` (`vera.jvm.lowering`): AST -> structured `JvmIr` via `JvmProgramLowering` / `JvmFunctionLowering`.
- `jvm-instruction-lowering` (`vera.jvm.instruction.lowering`): `JvmInstructionLowering.lower` flattens structured IR into `JvmInstructionProgram` in `vera.jvm.instruction`. Owns local slot allocation, label/jump emission, stack verification (`JvmInstructionMethodBuilder`), short-circuit CFG, comparison materialization, and print receiver ordering.
- `jvm-bytecode-emitter`: consumes only `JvmInstructionProgram`; maps flat instructions to ClassFile API calls. No semantic expansion, no `swap()`. The verb `emit` means producing JVM bytecode; instruction-lowering helpers use `append(...)`.

## Key invariants
- `and`/`or` short-circuit semantics live in structured `JvmLogicalAnd` / `JvmLogicalOr`; instruction lowering expands to CFG.
- Comparisons are structured `JvmComparison`; instruction lowering appends `IntComparisonJumpTo` + boolean materialization.
- `while` is structured `JvmWhile`; instruction lowering appends a condition label, false-jump to end, body, jump back, and end label.
- Rebinding is structured `JvmRebind`; instruction lowering lowers the expression and appends `StoreLocal` for the existing local.
- Print is `JvmBuiltinCall(PRINT, ...)`; instruction lowering appends `GetStatic` (receiver) before the argument, then `InvokeVirtual`. No swap.
- `GetStatic` carries both `fieldType` (verification stack type) and `fieldDescriptor` (real JVM field descriptor). Needed because reference-typed fields such as `System.out: PrintStream` cannot be represented by the limited `JvmType` enum.
- `JvmType.OBJECT` is the generic reference stack type; `fieldDescriptor` supplies the concrete JVM class descriptor for getstatic.
- `jvm-lowering` must not import `vera.jvm.instruction.*` or use labels/jumps/slots.

## Change guide
- Add a new Vera construct: add structured node(s) in `jvm-ir`, lower in `JvmFunctionLowering`, expand in `JvmInstructionLowering`, and add a primitive bytecode mapping if a new instruction is needed.
- Do not add backend concepts to `jvm-ir`; do not add semantic constructs to the emitter.
