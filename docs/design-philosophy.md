# Vera — Design Philosophy

> **Status: WIP.** Intended direction, not current syntax. See [Language](language.md) for what
> exists today.

## Goals

- Practical JVM language with access to the Java ecosystem.
- Expressive without heavy notation.
- Explicit mutation and side effects.
- Static checks without excessive annotation.
- Small enough to understand as a whole.
- Compiler correctness enforced by architecture.
- Reliability - aim for "if it compiles, it runs": language-level failures are prevented statically
  or represented explicitly in types.

## Non-goals

- A Java replacement with different syntax.
- A purely functional language.
- A research language built around one advanced type-system feature.
- A framework for speculative abstraction.

## Principles

- **Simplicity** — every feature must justify its existence and fit the language model.
- **Explicitness** — prefer clear rules over implicit conversions, hidden dependencies, or magic.
- **Functional foundations** — support immutability, composition, and explicit effects without
  ceremony.
- **Composition over inheritance** — records, traits, and composition instead of class hierarchies.
- **JVM as a first-class target** — keep JVM type distinctions and execution constraints meaningful.
- **Correctness through architecture** — make invalid intermediate states hard to construct; verify
  before emission.
- **Totality over traps** — prefer total operations; when an operation can fail, make the failure
  explicit in the type.
- **Transparency** — make state changes, effects, and failure modes visible from the source.

## Long-term direction

| Area      | Direction                                                                           | Status                                       |
|-----------|-------------------------------------------------------------------------------------|----------------------------------------------|
| Variables | `val` immutable, `var` mutable; rebind with `=`                                     | `var` and rebinding work; `val` not enforced |
| Types     | Keep JVM primitives distinct; no generic numeric abstraction                        | Primitives and record types                  |
| Records   | Data-oriented structured values                                                     | Fields, read access, and read-only methods work; updates do not |
| Traits    | Shared behavior and composition without inheritance                                 | Not implemented                              |
| Functions | Pure by default; side effects declared as capabilities                              | `fn` exists; effects do not                  |
| Effects   | Explicit capabilities such as `Print`                                               | Not implemented                              |
| Services  | Separate abstraction for external interaction                                       | Not implemented                              |
| Errors    | Errors as values instead of exception-driven control flow                           | Not implemented                              |
| Other     | Comments, block scoping, unary operators, Java interop, entry points                | Not implemented                              |

Open design questions include effect syntax, the exact relationship between services and
capabilities, and how much of the type system can stay simple.

## Value-object direction (proposed)

Status: partially implemented. Record declarations, construction, field access, and read-only
instance methods work. Mutation methods and collection semantics are still proposed.

- Objects are value records, not reference-identity classes.
- Values are immutable from the outside; there is no shared mutable state.
- Fields are read-only outside their record.
- A `mut fn` method operates on a private mutable copy of its receiver.
- A method returns a new immutable version, never a shared mutable view.
- The previous version remains valid and unchanged.
- The caller accepts the new state by explicit rebinding, e.g. `value = value.update(...)`.
- Discarding the result of a mutating method is an error or requires explicit discard.
- There is no implicit write-back to the receiver.
- Nested updates produce new nested values and reassign them; direct nested mutation is
  disallowed initially.
- Collections are persistent immutable values. Copy-on-write and structural sharing are
  implementation techniques, not observable semantics.
- No reference identity, inheritance, null, or cyclic construction initially.
- No ownership, borrowing, lifetimes, or move semantics.
- A mutating method must not let its mutable receiver escape. The returned version is frozen or
  rebuilt as immutable.
- If a mutating method fails, the caller's old value remains untouched.
- Value semantics make shallow copies and structural sharing the default; in-place mutation is
  only an optimization when the compiler can prove the old version is unobservable.
- Runtime reliability still requires total operations and explicit failure values. Immutability
  prevents aliasing errors, not division by zero, bounds errors, or external failures.

Open questions:

- How methods return both a new version and a normal result.
- Which collection representations to use first.
- Whether structural equality and hashing are part of the initial object model.
- How Java interop exposes mutable or throwing APIs.

## Compiler pipeline

```text
Source → lexer → parser → AST → structured JVM IR → flat instruction IR → emitter → .class
```

- **Parser** — hand-written recursive descent; produces an AST and handles syntax only.
- **AST** — source-level structure, independent of JVM details.
- **Structured IR** — resolved expressions/statements; no labels, jumps, or local slots.
- **Instruction IR** — owns control flow, local slots, comparison materialization, short-circuit
  flow, and stack verification.
- **Emitter** — translates verified flat instructions using the Java ClassFile API; performs no
  semantic analysis or repair.

Modules follow the pipeline. Dependencies should point forward and avoid leaking implementation
details into lower-level modules.

## Constraints

- Keep the AST independent of the JVM.
- Resolve language constructs before the emitter.
- Keep structured IR and instruction IR focused on their own concerns.
- Verify before emission; invalid IR must not reach the emitter.
- Preserve JVM type distinctions.
- Avoid hidden behavior.
- Reject invalid programs as early as possible.
- Do not prematurely generalize for hypothetical features.

## Contributor guidance

Before adding a feature, ask:

- Does it fit the language philosophy?
- Does it introduce implicit behavior?
- Which compiler stage owns it?
- Can correctness be enforced earlier?
- Can it be expressed with existing concepts?

Do not copy a feature just because another language has it. If a design decision is unclear,
surface the ambiguity and compare alternatives instead of silently choosing one.
