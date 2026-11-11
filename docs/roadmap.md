# Vera Roadmap

This file tracks planned work. It is the single source of truth for actionable TODOs; the language
guide describes current capability, not upcoming work.

Use one entry per item, with its status, affected area, and acceptance criteria. Remove an item once
it is implemented and covered by tests.

## Important basic features

Reasonable short- and mid-term goals that make ordinary programs correct and complete.

### Record equality and printing

- Status: not implemented.
- Area: lowering, emitter.
- Acceptance: decide structural equality semantics; records can be compared and printed; tests
  cover nested records.

### `val` immutability enforcement

- Status: not started.
- Area: parser and lowering.
- Acceptance: rebinding a `val` is rejected; `var` and rebinding continue to work.

### Record mutation

- Status: not started.
- Area: parser, AST, structured IR, lowering, flat IR, emitter.
- Includes: `mut fn` methods, field assignment, and value/reference semantics.
- Acceptance: mutation works through record methods and is rejected for immutable records and
  fields where appropriate.

### Comments and block scoping

- Status: not implemented.
- Area: lexer, parser, lowering.
- Acceptance: comments are skipped; block-scoped bindings no longer leak; the no-shadowing rule
  still applies.

### Numeric edge-case semantics

- Status: undecided.
- Area: lowering and emitter.
- Acceptance: division/modulo by zero and integer overflow behavior are decided, documented, and
  tested.

### Open language decisions

- Overloading: decide whether top-level functions and record methods support overloading, then
  document and test the result.
- Update blocks: `x = { ... }` is currently rejected by the parser; either implement it as part of
  record mutation or remove the reserved syntax.
- Recursion: recursive top-level functions and methods appear to work; add integration tests and
  document them explicitly.
- Default record field values: decide whether fields may omit constructor arguments by declaring a
  default, or keep positional construction mandatory.

## Long-term ideas and advanced features

Larger design work and features that are not required for a usable basic language.

- Persistent collections.
- String concatenation and other string operations.
- Traits.
- Classes.
- Effects, capabilities, services, and errors-as-values.
- Java interop.
- `long`, `float`, `double`, and numeric conversions.
- Custom entry-point names (only `main` gets a JVM adapter).

## Non-goals

- Inheritance. Vera uses records, traits, and composition instead of class hierarchies. This is
  not planned for any future version.
