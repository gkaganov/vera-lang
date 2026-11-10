# Vera Roadmap

This file tracks planned work. It is the single source of truth for actionable TODOs; the language
guide describes current capability, not upcoming work.

Use one entry per item, with its status, affected area, and acceptance criteria. Remove an item once
it is implemented and covered by tests.

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
