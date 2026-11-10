# Vera Roadmap

This file tracks planned work. It is the single source of truth for actionable TODOs; the language
guide describes current capability, not upcoming work.

Use one entry per item, with its status, affected area, and acceptance criteria. Remove an item once
it is implemented and covered by tests.

## Near term

### Forward application to member calls and sibling methods

- Status: decided, not implemented.
- Area: lowering.
- Rule: the right side of `|>` may be a bare callable (function, built-in, record constructor,
  sibling record method) or a member call chain; the forwarded value becomes the first argument.
- Acceptance: `data |> processor.runtime.process() |> print()` works and lowers like
  `print(processor.runtime.process(data))`; sibling methods work as RHS targets; integration tests
  cover both.
- Notes: shared named-call resolution should be used by both chained calls and forward application.

## Feature work

### Record mutation

- Status: not started.
- Area: parser, AST, structured IR, lowering, flat IR, emitter.
- Includes: `mut fn` methods, field assignment, and value/reference semantics for mutations.
- Acceptance: mutation works through record methods and is rejected for immutable records and
  fields where appropriate.

### Persistent collections

- Status: proposed.
- Area: runtime and compiler.
- Depends on: record and value semantics.
- Acceptance: immutable collection values support construction and the operations agreed in the
  design discussion.

### `val` immutability enforcement

- Status: not started.
- Area: parser and lowering.
- Acceptance: rebinding a `val` is rejected; `var` and rebinding continue to work.

## Later

- Traits.
- Classes.
- Effects, capabilities, services, and errors-as-values.
- Java interop.
- `long`, `float`, `double`, and numeric conversions.
- Comments, block scoping, and unary operators.
- Custom entry-point names (only `main` gets a JVM adapter).

## Non-goals

- Inheritance. Vera uses records, traits, and composition instead of class hierarchies. This is
  not planned for any future version.
