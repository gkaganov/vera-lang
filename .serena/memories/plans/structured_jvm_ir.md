# Planned: Structured JVM IR Migration

Status: planned, not started.
Full implementable plan: `docs/structured-jvm-ir-migration.md` in the repo root. Read it before changing `jvm-ir`, `jvm-lowering`, or `jvm-bytecode-emitter`.

## Summary
Replace the current single flat IR with two layers:
1. `jvm-ir`: structured semantic IR (`JvmExpression`, `JvmStatement`, `JvmLogicalAnd`, `JvmLogicalOr`, `JvmComparison`, `JvmBuiltinCall`, `JvmIfExpression`, etc.). No labels, jumps, slots, or `JvmMethodBuilder`.
2. New module `jvm-instruction-lowering`: low-level instruction IR + `JvmInstructionLowering`, which expands structured IR to slots/labels/jumps/primitives. Owns short-circuit CFG, comparison materialization, and print receiver ordering.
3. `jvm-bytecode-emitter`: maps low-level instructions to bytecode 1:1. No comparison expansion, no `swap`.

## Locked outcomes
- `and`/`or` stay lazy in structured IR; short-circuit is materialized by instruction lowering.
- `print` emits `GetStatic` before its argument; emitter `swap()` is removed.
- `ComparisonOperation` and `Print` disappear from low-level IR.
- Existing `compiler-tests` behavior is preserved.
- Implement the phased plan in the doc; do not ask the user for design clarification.
