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

## Long-term direction

| Area | Direction | Status |
|---|---|---|
| Variables | `val` immutable, `var` mutable; rebind with `=` | `var` and rebinding work; `val` not enforced |
| Types | Keep JVM primitives distinct; no generic numeric abstraction | `Int`, `String`, `Bool`, `Unit` only |
| Records | Data-oriented structured values | Not implemented |
| Traits | Shared behavior and composition without inheritance | Not implemented |
| Functions | Pure by default; side effects declared as capabilities | `fn` exists; effects do not |
| Effects | Explicit capabilities such as `Print` | Not implemented |
| Services | Separate abstraction for external interaction | Not implemented |
| Errors | Errors as values instead of exception-driven control flow | Not implemented |
| Other | `for`, member access, comments, block scoping, unary operators, Java interop, entry points | Not implemented |

Open design questions include effect syntax, the exact relationship between services and
capabilities, and how much of the type system can stay simple.

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
