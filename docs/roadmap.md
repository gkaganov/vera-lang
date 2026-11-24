# Vera Roadmap

This file tracks planned work. It is the single source of truth for actionable TODOs; the language
guide describes current capability, not upcoming work.

Use one entry per item, with its status, affected area, and acceptance criteria. Remove an item once
it is implemented and covered by tests.

## Important basic features

Reasonable short- and mid-term goals that make ordinary programs correct and complete.

### Sealed traits, `match`, and `enum`

- Status: in progress; trait-typed values and dynamic dispatch (phase A) are implemented.
- Area: parser, AST, lowering, structured IR, flat IR, emitter.
- Phase B: `sealed trait` (implementing classes in the same file), `match` with `->` arms and
  named-field patterns (`Rect(width, height)`, `Rect(width = w)`), exhaustiveness without `else`,
  and `if`/`match` branches of different classes unifying to their sealed trait.
- Phase C: `enum Shape has Display { Circle(radius: Int) Empty }` as shorthand for a sealed trait plus
  classes, so a variant can later become a full class without changing callers; classes without
  fields become singletons written by name (`Empty`), with `Unit` as one of them.
- Acceptance: sealed traits compile to JVM sealed interfaces; a non-exhaustive `match` is rejected;
  converting an enum variant to a hand-written class needs no changes at call sites or patterns.

### Object mutation

- Status: not started.
- Area: parser, AST, structured IR, lowering, flat IR, emitter.
- Includes: `mut fn` methods, field assignment, and value/reference semantics.
- Acceptance: mutation works through object methods and is rejected for immutable objects and
  fields where appropriate.

### Numeric edge-case semantics

- Status: undecided.
- Area: lowering and emitter.
- Acceptance: division/modulo by zero and integer overflow behavior are decided, documented, and
  tested.

### Open language decisions

- Overloading: decide whether top-level functions and object methods support overloading, then
  document and test the result.
- Update blocks: `x = { ... }` is currently rejected by the parser; either implement it as part of
  object mutation or remove the reserved syntax.
- Recursion: recursive top-level functions and methods appear to work; add integration tests and
  document them explicitly.
- Default class field values: decide whether fields may omit constructor arguments by declaring a
  default, or keep every field required at construction.

## Long-term ideas and advanced features

Larger design work and features that are not required for a usable basic language.

- Persistent collections.
- String operations such as length and substring; strings are built with templates, not `+`.
- Trait default method bodies.
- Doc comments: attach `///` comments to the following declaration and render them as documentation.
- A universal `Any` top type with `java.lang.Object`-like behavior. Deferred: it requires boxing
  for primitives, `instanceof`/`checkcast`, `invokeinterface`, and a heterogeneous `equals`.
  `Any` is deliberately not a root trait yet, so `Eq` and `Display` stay opt-in.
- Effects, capabilities, services, and errors-as-values.
- Java interop.
- `long`, `float`, `double`, and numeric conversions.
- Custom entry-point names (only `main` gets a JVM adapter).

## Non-goals

- Inheritance. Vera uses classes, traits, and composition instead of inheritance hierarchies. This is
  not planned for any future version.
