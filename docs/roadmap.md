# Vera Roadmap

This file tracks planned work. It is the single source of truth for actionable TODOs; the language
guide describes current capability, not upcoming work.

Use one entry per item, with its status, affected area, and acceptance criteria. Remove an item once
it is implemented and covered by tests.

## Important basic features

Reasonable short- and mid-term goals that make ordinary programs correct and complete.

### Closed traits, trait cases, and structural interfaces

- Status: implementation steps 1–6 done. Remaining: the items under *Remaining* below.
- Area: parser, AST, lowering, emitter.
- Model:
  - `trait` is closed: only classes in its module can join it. Traits compile to sealed interfaces
    and can be matched. Open contracts are structural `interface`s (decision 10). There is no
    separate `enum` construct.
  - A trait takes one of two forms, never both. Separate form: the trait declares only
    requirements (method signatures and fields), and classes join it with `has`. Block form: the
    trait lists its own cases, which no other class can join.
  - A class has at most one owner (the block that declares it) and can join any number of traits
    with `has`, including other closed traits.
  - Inside a block, a case is `Name [has Traits] [{ val fields and fn methods }]`, without
    `class`; cases are separated by commas or newlines (`trait Color { Red, Green, Blue }`). A case
    without a body in a trait that requires methods or fields is an error. A class without fields,
    nested or not, is a singleton written by name.
  - Nested cases compile to nested JVM classes (`Color$Red`), seen from Java as `Color.Red`.
- Decisions:
  1. Constructing a case is always qualified: `Color.Red`, `Shape.Circle(2)`. An
     explicit import shorthand (`import Color.{Red, Green}`) may come with modules.
  2. Cases use the body-optional form above; no `class` keyword inside a block.
  3. Sibling cases are qualified even inside their own block (`Tree.Leaf`). The only unqualified
     form is a pattern in a `match` on the owning trait, which can only name that trait's cases.
     In a `match` on any other trait, owned cases are written qualified (`Color.Red`).
  4. `Display` prints nested cases qualified (`Color.Red`, `Shape.Circle(radius: 2)`); separately
     declared classes print their plain name.
  5. "Closed" means the same module: classes in the trait's module may join it, and every `match`
     sees the full case list, including in other modules. Vera has no file scope, so files never
     bound closedness; until modules exist, a program is one module.
  6. A top-level class may share a name with a nested case (`Leaf` and `Tree.Leaf`): nested names
     are always qualified, so they never meet. An import that would bring them together is a
     shadowing error.
  7. Deferred to the generics design: the type of a field-less case of a generic trait
     (`Option.None`). It needs either a bottom type (`Never`) with covariance, or typing from
     context. Owned cases share their owner's type parameters
     (`trait Option<T> { Some { val value: T } None }`).
  8. Trait requirements: `trait Shape requires Display, Eq` means every member of `Shape` has
     those traits, so a `Shape` value can be printed, compared, or used where a required trait is
     expected. This is interface inheritance only: traits carry no storage or code. In the separate
     form, a joining class lists the required traits explicitly
     (`class Circle has Shape, Display, Eq`), and a missing one is an error naming it; block-form
     cases get them automatically. Requirements may not form a cycle: traits that require each
     other would have the same members, so they should be one trait.
  9. `Bool` is a built-in closed trait in block form, `trait Bool { True, False }`, whose cases are
     imported by default so they stay written `True` and `False`; a `match` on a `Bool` covers
     `True` and `False`. It stays a JVM `boolean`. `Unit` is a built-in field-less class, so its
     single value is written `Unit`.
  10. Open contracts are structural `interface`s instead of `open trait`. An interface is open: any
      class with matching members fits it, without declaring anything; parameter names are part of
      a member's signature, so `fn scale(by: Int)` does not fit `fn scale(factor: Int)`. Interfaces
      cannot be matched and have no block form. They compile to plain JVM interfaces: the compiler
      adds `implements` to every fitting class in the program, and Java classes implement them
      explicitly. Classes compiled separately (later modules) will need adapters.
  11. A class may state conformance explicitly with `implements` (`class User has Shape implements
      Named`), which checks the fit at the class declaration. `has` is only for traits and
      `implements` only for interfaces; using the wrong one is an error with a hint.
  12. Traits and interfaces may require fields as well as methods (`val timestamp: Int`). A
      required field is a requirement, not storage: each member class declares the field itself, so
      nothing is inherited. On a trait- or interface-typed value the field can be read
      (`event.timestamp`), compiled to an accessor method on the JVM interface. Block-form cases
      declare required fields in their bodies, so a trait with required fields has no body-less
      cases.
  13. A `match` on an interface-typed value is not possible: an interface is open, so its cases can
      never all be known, and Vera's `match` has no catch-all arm. Code that receives an interface
      uses its members instead.
- Implementation steps:
  1. Every trait closed (drop `sealed`), and `match` on any trait. Done.
  2. Field-less classes as singletons, with `Unit` as one of them. Done.
  3. Block-form cases: qualified names, nested JVM classes, unqualified patterns, qualified
     `Display`. Done.
  4. Trait requirements, including `equals(Object)` so `==` works on trait-typed values. Done; a
     matching `hashCode` for Java collections is still missing.
  5. Required fields in traits. Done.
  6. Structural `interface`s with `implements`. Done. Traits whose values fit an interface extend
     it; traits and interfaces cannot require each other.
- Remaining:
  - `Bool` as a block-form trait with default-imported cases, so `match` covers `True` and `False`
    (decision 9).
  - `hashCode` alongside the generated `equals(Object)`, for Java collections.
  - Branch types from the expected type: `val named: Named = if c { Dog } else { User(...) }` is
    rejected today, because branches of different classes unify only to a shared trait.
  - Generic singletons (decision 7) and sugar against long trait lists.
  - Adapters for classes compiled separately, once there are modules (decision 10).
- Acceptance: a non-exhaustive `match` is rejected and names the missing cases; interfaces cannot
  be matched; a block-form trait cannot be joined with `has`; field-less classes are singletons; a
  class with an interface's members is accepted where the interface is expected.

### Object mutation

- Status: not started.
- Area: parser, AST, structured IR, lowering, flat IR, emitter.
- Includes: `mut fn` methods, field assignment, and value/reference semantics.
- Acceptance: mutation works through object methods and is rejected for immutable objects and
  fields where appropriate.

### Numeric edge-case semantics

- Status: direction decided for overflow; design not started.
- Area: type system, lowering, and emitter.
- Overflow: `Int` values may only hold legal values. Types will carry the range of values they can
  hold, and every arithmetic result must be proven, or explicitly asserted, to stay in range at
  compile time. Overflow does not wrap silently; until this exists, overflow is left untested so no
  test pins the current wrapping behavior.
- Division/modulo by zero: undecided; possibly ruled out by the same mechanism.
- Acceptance: range-carrying `Int` types, the proof and assertion rules, and division/modulo by zero
  are designed, documented, and tested.

### Open language decisions

- Overloading: decide whether top-level functions and object methods support overloading, then
  document and test the result.
- Update blocks: `x = { ... }` is currently rejected by the parser; either implement it as part of
  object mutation or remove the reserved syntax.
- Union types (`Int | Unit`): an `if` without `else` could produce "the branch type or `Unit`", Vera's
  null-free optional value. Decide whether unions are inferred (then `if c { 1 } else { "a" }` is
  `Int | String` and branch mismatches move to the use site) or only declared; how a union is consumed
  (`match` on built-in types such as `Int(n) -> ...`); what `==`, `print`, and templates do on one; the
  JVM representation (boxed values in an `Object` slot with `instanceof` checks); and how anonymous
  unions relate to closed traits. Until then, an `if` without `else` whose value is used must have a
  `Unit` branch, so adding unions later changes no existing program.
- Built-in types fitting interfaces: decided that `Int`, `String`, `Bool`, and `Unit` fit an interface
  through their built-in traits (`Eq`, `Display`), so they fit interfaces without members of their
  own, such as `interface Anything {}`. Such interfaces need an `Object` representation on the JVM
  with boxed values and runtime-dispatched `==` and `print` (a boxed `Boolean` must print `True`), the
  same machinery as union types, so both are built together. Until then a built-in type passed as an
  interface is rejected with "cannot fit an interface yet"; `Unit` does not fit early.
- Several patterns per `match` arm: `Circle, Rect -> 1`, as in Java (`case A, B ->`) and Kotlin. Each
  listed case counts as covered; decide whether such an arm binds no fields or only fields every
  listed case shares with the same type. Until then the parser rejects it with a hint.
- Default values: decide whether fields and function parameters may declare a default so callers can
  omit them, or keep every field and parameter required. Until then both are rejected with a hint.

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
