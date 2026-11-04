# Vera — Design Philosophy and Long-Term Direction

> **Status: WIP.** This document describes the intended direction of Vera. It mixes implemented
> ideas with planned features that do not exist yet. For current syntax, see
> [Language](language.md).

## 1. What Vera is trying to be

Vera is a statically typed, JVM-targeting programming language exploring a pragmatic middle ground
between functional and object-oriented programming.

The goal is a language that is:

- practical on the JVM and able to reach the Java ecosystem
- expressive without becoming notation-heavy
- explicit about mutation and side effects
- statically checked without excessive annotation
- small enough to understand as a coherent whole
- backed by a compiler that makes correctness an architectural property

Vera takes inspiration from Kotlin, Scala, and Elm, but does not aim to reproduce their feature sets
or design compromises.

It is not intended to be:

- a Java replacement with different syntax
- a purely functional language
- a research language built around one advanced type-system feature
- a framework for speculative abstraction

## 2. Core principles

### Simplicity over feature accumulation

Every language feature should justify its existence. Features must fit the language model rather
than adding special cases that interact unpredictably with the rest of the language.

### Explicitness over magic

Important behavior should be visible in the source. Prefer clear rules over implicit conversions,
hidden dependencies, surprising inference, or runtime conventions.

### Functional foundations without ceremony

Vera should support immutability, pure functions, composition, and explicit effects without
requiring mathematical notation or heavily abstracted functional programming patterns.

### Object-oriented composition without inheritance complexity

Vera intends to support object-oriented programming without classical class inheritance. Records,
traits, and composition are preferred over inheritance hierarchies.

### JVM as a first-class target

Vera targets the JVM directly. The JVM is not merely a deployment detail; its execution model,
type distinctions, and class-file constraints are important design inputs.

The language should integrate naturally with the Java ecosystem while retaining its own semantics
and type system.

### Correctness through compiler architecture

The compiler should make invalid intermediate states difficult to construct. Verification and
structural checks belong before bytecode emission, not inside the emitter.

## 3. Long-term language direction

The following sections describe intended direction, not necessarily implemented behavior.

### 3.1 Variables and mutability

Vera distinguishes immutable bindings from mutable bindings.

- `val` is intended for immutable bindings.
- `var` is intended for mutable bindings.
- Rebinding an existing mutable binding uses `=`.

Current status: `var` and rebinding work. `val` parses but immutability is not enforced yet.

Immutability should become the natural default where practical.

### 3.2 Types

Vera is statically typed. The compiler should reject invalid programs before bytecode generation.

The type system should provide useful guarantees without excessive annotations or complicated
type-level abstractions.

JVM primitive distinctions are expected to remain meaningful. In particular, `Int`, `Long`,
`Float`, and `Double` should not be collapsed into a generic numeric abstraction.

Current status: only `Int`, `String`, `Bool`, and `Unit` exist. No other primitive numeric types
are implemented yet.

### 3.3 Records

Vera intends to use **records** for structured data.

Records are data-oriented constructs rather than traditional classes with inheritance. They should
represent structured values without requiring boilerplate.

The terminology is deliberately `record`, not `unit`.

Current status: records are not implemented.

### 3.4 Traits

Vera intends to use traits as an abstraction and composition mechanism.

Because classical inheritance is intentionally absent, traits should provide shared behavior and
composition across types. The model favors composition over inheritance.

Current status: traits are not implemented.

### 3.5 Functions

Functions are a central building block. Vera aims to support pure functions, composition, and
explicit handling of effects.

Functions should be pure by default. A function that performs side effects should declare the
capabilities it requires. Conceptually:

```vera
fun greet() [Print] {
    // ...
}
```

The exact syntax and semantics of effect declarations remain open design questions. `fun` is also
a placeholder; the current keyword is `fn`.

Current status: `fn` exists. Effects/capabilities do not.

### 3.6 Effects and capabilities

Vera intends to use explicit capabilities to represent permitted side effects. A pure calculation
requires no capabilities; printing requires a `Print` capability; other external interactions
require their corresponding capabilities.

The objective is to make side effects visible and statically checkable without turning Vera into a
language dominated by effect-system syntax.

Current status: not implemented. `print` is currently an unconstrained built-in.

### 3.7 Services

Services are intended to provide a separate abstraction for functionality that interacts with the
outside world, complementing the pure functional core.

Services and capabilities are related but should not automatically be treated as interchangeable.
The relationship between service declarations, capability requirements, and runtime representation
is still an open design area.

Current status: not implemented.

### 3.8 Error handling

Vera favors errors-as-values over exception-driven control flow. Failure paths should be explicit
and composable, integrating naturally with the type system and functional style.

Current status: not implemented.

### 3.9 Other planned language work

- `for` loops
- member and field access
- comments
- block scoping
- unary operators
- Java interop
- a larger string-literal character set
- entry-point conventions

## 4. Compiler architecture

Vera is implemented in Kotlin and compiles to JVM bytecode using Java's Class-File API.

The pipeline is intentionally staged:

```text
Source Code
    → lexer
    → recursive-descent parser
    → AST
    → structured JVM IR (JvmIr)
    → flat instruction IR (JvmInstructionProgram)
    → JVM bytecode emitter
    → JVM class files
```

Each stage has a specific responsibility.

### 4.1 Parsing

The parser is a hand-written recursive-descent parser. It produces an AST directly; there is no
parser generator or separate parse-tree mapper.

The parser is concerned with syntax, not semantic analysis or bytecode generation.

### 4.2 AST

The AST represents the source program in a structured, data-oriented form.

It should remain independent of JVM-specific implementation details. The AST represents what the
programmer wrote, not how the JVM will execute it.

This separation allows the source language to evolve without coupling every feature directly to
bytecode generation.

### 4.3 Lowering to structured IR

The first lowering pass transforms the AST into a structured JVM-oriented IR.

This is where source-language constructs are resolved and translated into higher-level JVM
operations. Expressions and statements remain nested constructs at this layer.

Lowering also enforces semantic constraints before control-flow and local-slot details are
materialized.

### 4.4 Structured IR

The structured IR (`JvmIr`) represents classes, methods, expressions, and statements.

It deliberately contains no labels, jumps, local slot numbers, or instruction builder. Those belong
to the next stage.

Important concepts include:

- `JvmProgram`
- `JvmClass`
- `JvmMethod`
- `JvmExpression`
- `JvmStatement`
- `JvmType`

The structured IR is not the complete Vera source-language type system. It is an
implementation-level representation for the JVM backend.

### 4.5 Instruction lowering and verification

The instruction-lowering pass flattens structured IR into a linear instruction program.

It owns:

- local slot allocation
- labels and jumps
- comparison materialization
- short-circuit control flow
- print receiver ordering
- stack-effect verification

Flat instructions carry execution-state information through `InstructionWithDebugInfo` and
`DebugFrameSnapshot`. The method builder verifies stack discipline as instructions are appended.

Invalid instruction sequences should be rejected before bytecode generation.

### 4.6 JVM IR

The flat instruction IR is a deliberately thin abstraction over the JVM class-file model.

It should represent what is necessary to generate valid JVM bytecode without becoming a second,
unnecessarily complicated programming language.

Current JVM type categories:

- `INT`
- `STRING`
- `BOOL`
- `OBJECT`
- `VOID`

This is an implementation model, not the source-language type system.

### 4.7 Bytecode emitter

The bytecode emitter translates verified flat instructions into class files using the Java
Class-File API.

The emitter should be deliberately dumb. It should not perform semantic analysis, make language
design decisions, repair invalid IR, or implement complicated type-checking logic.

Its responsibility is to translate an already verified representation into the corresponding
class-file structures.

## 5. Technology choices

| Component | Technology |
|---|---|
| Implementation language | Kotlin |
| Build system | Gradle |
| Build scripts | Kotlin DSL |
| Parser | Hand-written recursive descent |
| Compilation target | JVM |
| Bytecode generation | Java Class-File API |
| Required JDK | JDK 25 |

Kotlin was chosen for the compiler implementation primarily for null-safety and an expressive type
system. The Java Class-File API requires JDK 25.

## 6. Project structure

The compiler is organized into Gradle modules:

- `cli` — command-line interface
- `compiler` — compiler orchestration
- `shared-model` — shared models and definitions
- `parser` — lexer and recursive-descent parser
- `ast` — source-level abstract syntax tree
- `jvm-ir` — structured JVM IR
- `jvm-lowering` — AST to structured IR lowering
- `jvm-instruction-lowering` — structured IR to flat instruction IR
- `jvm-bytecode-emitter` — JVM class-file generation
- `compiler-tests` — integration tests

The modular structure is intentional. Each module should have a clear responsibility, and
dependencies should follow the compilation pipeline. Avoid unnecessary cross-module dependencies or
leaking implementation details into lower-level modules.

## 7. Architectural constraints

1. **Keep the AST independent of the JVM.** Source-language concepts should not be polluted with
   bytecode implementation details.
2. **Keep lowering responsible for translation.** Higher-level constructs should be resolved before
   reaching the emitter.
3. **Keep both IR layers focused.** Structured IR owns language-level structure; flat instruction IR
   owns JVM control flow, slots, and stack effects.
4. **Verify before emission.** Invalid IR should never reach the emitter.
5. **Keep the emitter simple.** It translates verified instructions; it does not repair or reinterpret
   them.
6. **Preserve JVM type distinctions.** Primitive types such as `long`, `double`, and `float` matter
   and should not be casually abstracted away.
7. **Avoid hidden behavior.** Language semantics should be explicit and predictable.
8. **Keep modules focused.** Do not move responsibilities between modules merely for convenience.
9. **Prefer compile-time guarantees.** Invalid programs and invalid intermediate representations
   should be rejected as early as possible.
10. **Do not prematurely generalize.** Implement the language features Vera actually needs instead of
    designing speculative frameworks for hypothetical future features.

## 8. What Vera is trying to achieve

Vera aims for a programming experience where:

- writing pure code is straightforward
- side effects are explicit
- data structures are simple and expressive
- abstraction is possible without inheritance hierarchies
- the type system catches mistakes early
- the JVM remains accessible without dictating the entire language design
- compilation is understandable, modular, and verifiable

The language should be expressive without being complicated, safe without being restrictive, and
functional without becoming impractical.

The compiler should reflect the same values: a clear pipeline, explicit intermediate
representations, strong invariants, and minimal magic.

## 9. Guidance for contributors and coding agents

Before implementing a feature, consider:

- Does it fit the existing language philosophy?
- Does it introduce implicit behavior?
- Can it be implemented without compromising the separation between compiler stages?
- Does it belong in the AST, structured IR, instruction lowering, or emitter?
- Can correctness be enforced at an earlier stage?
- Does it require a new language concept, or can it be expressed using existing ones?

Do not assume a feature is desirable simply because Kotlin, Scala, Java, or another language
implements it.

Do not invent language semantics based on what is convenient for the current implementation.

When a design decision is unclear, identify the ambiguity and propose alternatives rather than
silently committing to one.
