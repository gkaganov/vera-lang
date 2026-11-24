# Vera Lang

Vera is a small, experimental programming language targeting the JVM. It uses a recursive-descent
parser, a 2-phase IR lowering pipeline, and generates JVM bytecode via the Java ClassFile API.

## Documentation

- [`docs/language.md`](docs/language.md) — current language guide
- [`docs/roadmap.md`](docs/roadmap.md) — planned work and acceptance criteria
- [`docs/design-philosophy.md`](docs/design-philosophy.md) — long-term vision and principles
- [`docs/testing.md`](docs/testing.md) — test structure and workflow

## Quick Start

```bash
./gradlew build                    # Compile, test, and assemble
./gradlew :compiler-tests:test     # Run integration tests
./gradlew :compiler-tests:test --tests "vera.compiler.test.VeraCompilerTests.print function prints to system-out"
```

Build output goes to `out/` (compiler) and `build/` (Gradle).

## Architecture

```text
Vera source → lexer/parser → AST → JvmIr (structured IR) → FlatInstructionLowering →
FlatProgram (flat instruction IR) → Bytecode Emitter → .class files
```

### Pipeline

1. **Parse** — `VeraLexer` → `VeraParser` → AST.
2. **Lower to structured IR** — `JvmProgramLowering` / `JvmFunctionLowering`; expressions and
   statements remain nested.
3. **Lower to instruction IR** — `FlatInstructionLowering` flattens IR and owns stack effects,
   labels, jumps, and local slots.
4. **Emit** — `BytecodeEmitter` generates `.class` files via the Java ClassFile API.

### Modules

| Module | Purpose |
|---|---|
| `shared-model` | Shared domain model and exceptions |
| `parser` | Lexer + recursive-descent parser → AST |
| `ast` | AST node definitions |
| `jvm-ir` | Structured JVM IR — classes, methods, expressions, statements, operators |
| `jvm-lowering` | AST → structured IR (`JvmProgramLowering`, `JvmFunctionLowering`) |
| `jvm-instruction-lowering` | Structured IR → flat instruction IR (`FlatInstructionLowering`) |
| `jvm-bytecode-emitter` | Flat instruction IR → JVM `.class` files |
| `compiler` | Orchestration: `VeraCompiler` |
| `cli` | Command-line entry point |
| `compiler-tests` | Integration and compiler-error tests |

### Naming by Stage

| Stage | Module / package | Class naming | Method naming | Purpose |
|---|---|---|---|---|
| Shared model | `shared-model` / `vera.shared.model` | Shared concepts: `Identifier`, `SourceLocation`, `SourceSpan`; base: `VeraException` | n/a | Identifiers, source locations, base exception |
| Lexing | `parser` / `vera.parser` | `Vera*` (`VeraLexer`, `VeraToken`, `VeraLexException`) | `lex*` | Source → tokens |
| Parsing | `parser`, `ast` / `vera.parser`, `vera.ast` | `Vera*` | `parse*` | Tokens → AST |
| Structured IR | `jvm-ir` / `vera.jvm.ir` | `Jvm*` | n/a (data classes) | Resolved, nested JVM IR; no labels or slots |
| AST lowering | `jvm-lowering` / `vera.jvm.lowering` | `Jvm*Lowering`, `Jvm*LoweringState`, `JvmLoweringException` | `lower*`, `validate*`, `require*` | AST → structured IR |
| Instruction lowering | `jvm-instruction-lowering` / `vera.jvm.instruction` | `Flat*` (`FlatInstruction`, `FlatProgram`, `FlatLoadLocal`, `FlatLabel`, `FlatInstructionException`) | `lower*`, `append`, label helpers | Structured IR → flat instruction IR; owns stack, labels, and slots |
| Bytecode emission | `jvm-bytecode-emitter` / `vera.jvm.bytecode.emitter` | `Bytecode*` (`BytecodeEmitter`, `BytecodeEmitterState`, `BytecodeEmitException`) | `emit*` | Flat IR → `.class` |
| Orchestration | `compiler` / `vera.compiler` | `VeraCompiler` | `compile` | Runs the pipeline |

Prefixes identify the data model, not the pipeline stage:

- `Vera*` — source/AST types and source-facing services (`VeraLexer`, `VeraParser`, `VeraToken`,
  `VeraCompiler`).
- `Jvm*` — structured JVM IR and its lowering services/exceptions (`JvmProgram`, `JvmMethod`,
  `JvmProgramLowering`, `JvmLoweringException`).
- `Flat*` — flat instruction IR and its lowering/builder/exceptions (`FlatProgram`, `FlatMethod`,
  `FlatLoadLocal`, `FlatInstructionLowering`, `FlatMethodBuilder`).
- `Bytecode*` — bytecode emission (`BytecodeEmitter`, `BytecodeEmitException`).

Shared concepts such as `Identifier` and `SourceLocation` intentionally have no prefix.

### Lowering

- `JvmProgramLowering.lower(mainClassName, program)` is the public entry point.
- First pass collects class, trait, and function declarations by name; classes lower to their own
  JVM classes (including instance methods), then functions lower to static methods on the program
  class.
- Traits: `JvmProgramLowering` validates `VeraTraitDeclaration` signatures and each class's
  `VeraClassDeclaration.traits` (every trait method must be implemented with matching parameter
  names, parameter types, and return type, and class-typed fields of `Eq`/`Display` classes must
  carry the same trait). Each user trait lowers to an interface `JvmClass` (`isInterface`, with
  `abstractMethods`), and classes list their user traits in `JvmClass.interfaces`.
- Trait types: `JvmTypeLowering` resolves type names through `JvmTypeNames` (classes and user
  traits); a trait name becomes `JvmType.InterfaceType`, and built-in traits are not types.
  `JvmFunctionLoweringState.coerce` accepts a class value where one of its traits is expected,
  wrapping it in `JvmUpcast`; arguments, returns, annotated `val`s, and rebinding all use it. An
  annotated `val` takes its declared type. Calls on trait-typed values lower to `JvmInstanceCall`
  with `ownerIsInterface`.
- `JvmFunctionLowering` is stateless; per-call mutable state lives in a private `*State` helper.
- Locals are block-scoped: `JvmFunctionLoweringState` keeps a scope stack, and `if` branches and
  `while` bodies open a new scope.
- `JvmFunctionLowering` rejects `break`/`continue` outside a loop and inside nested expressions: it
  tracks an expression depth, and a jump may only target a loop entered at the same depth. Only an
  `if` that forms a whole statement keeps statement depth for its branches. The flat layer re-checks
  both as defensive invariants (`BreakOutsideLoop`, `LoopJumpStackMismatch`).
- Return analysis treats an `if` with `else` whose branches all return as returning.
- Trait cases: `VeraParserState.parseTraitCase` turns each case in a trait block into a
  `VeraClassDeclaration` named `Owner$Case` (its JVM binary name; `Identifier.display` shows
  `Owner.Case`), with `owner` set and the owner first in `traits`; `VeraTraitDeclaration.cases`
  holds them. `JvmProgramLowering` lowers them with the top-level classes, rejects other classes
  joining a trait with cases (`BlockTraitJoined`), and marks them with `JvmClass.owner`, which the
  emitter turns into `InnerClasses` attributes. `Owner.Case` in expressions resolves through
  `requireTraitCase`; an unqualified pattern resolves to the scrutinee trait's own case first.
- `match` (`JvmFunctionLoweringState.lowerMatchExpression`) evaluates the scrutinee once into a
  `JvmLet` temporary and lowers to a chain of `JvmIfExpression`s testing `JvmInstanceOf`; the last arm
  is untested (`JvmBlockExpression`) because exhaustiveness rules out every other case. The scrutinee
  must be a trait (cases: the classes that have it, since traits are closed) or a single class. Arms bind fields from a
  `JvmCheckCast` of the temporary in their own scope. `if` and `match` branch types unify through
  `unifyTypes` (equal types, or the single user trait they share) and `coerceBlock`.
- Bindings: `val` is immutable and `mut val` (`VeraBindStatement.mutable`) can be rebound; the lowering
  scope stack records each local's mutability. Rebinding a `val` is `ImmutableBindingRebound` and
  rebinding a parameter is `ParameterRebound`; parameters cannot be marked `mut` in a signature.
  `mut val name` without a value (`VeraMutableParameter`) marks a parameter rebindable from that
  statement on; it is only allowed for parameters, once, and directly in the function body. `var` is not a
  keyword, and `var x = ...` gets a hint.
- Function bodies are `VeraBlockBody` (explicit `return`s) or `VeraExpressionBody` (`= expression`,
  lowered to a single `JvmReturn`). An omitted return type means `Unit`; a non-`Unit` expression body
  without one is `ExpressionBodyReturnTypeRequired`. The parser rejects an explicit `: Unit` return
  type (`VeraParseException.ExplicitUnitReturnType`).
- Calls carry `VeraArgument(name, expression)`. `JvmFunctionLowering.requireArgumentNames` checks every
  function, method, constructor, and built-in call after its arity check. Only written arguments
  count; the value forwarded by `|>` fills the first parameter unnamed. One written argument is
  passed unnamed; two or more are named with `name = value`, in parameter order, or as a bare
  identifier equal to the parameter name.
- A statement after `return`, `break`, `continue`, or an always-returning `if` in the same block is
  rejected as `UnreachableStatement`, including an `if` block's final value expression.
- `value |> chain.call()` evaluates `value` into a compiler-generated local (`JvmLet`) before the
  receiver chain, keeping left-to-right evaluation order.
- The flat instruction layer verifies stack discipline and resolves labels/jumps.
- The emitter must not interpret structured expressions or control flow.

### IR Design

- **Structured IR** (`vera.jvm.ir`): `JvmProgram`, `JvmClass`, `JvmField`, `JvmConstructor`,
  `JvmMethod`, `JvmExpression`, `JvmStatement`, and block kinds `JvmExplicitReturnBlock`,
  `JvmImplicitReturnBlock`, `JvmUnitReturnBlock`.
- `JvmLet(local, value, body)` evaluates `value` into a compiler-generated local, then `body`.
- Object construction is `JvmObjectConstruction`; field reads are `JvmGetField`; object method
  calls are `JvmInstanceCall` with an explicit receiver expression. `JvmMethod.receiver` is the
  optional instance receiver local; `null` means a static method.
- `JvmBuiltinTraits` defines the built-in `Eq` and `Display` traits. `JvmObjectMethods`
  generates a structural `equals` for classes with `Eq` and a `toString` for classes with
  `Display`. Object `==`/`!=` lower to the generated `equals` and require `Eq`; `print` of an
  object lowers to `toString` and requires `Display`. Missing traits are lowering errors. `!=`
  negates the call with `JvmLogicalNot`, and generated methods are never visible to Vera
  name resolution.
- Field-less classes are singletons: `JvmProgramLowering.singletonMembers` gives each one a static
  `INSTANCE` field, a `<clinit>` that creates it, and a private constructor (`JvmConstructor.isPrivate`,
  `FlatMethod.isPrivate`). A bare class name lowers to `JvmSingletonValue` (`FlatGetStatic`), and
  `Empty()` is `SingletonConstructed`.
- `Unit` is a real zero-field value, not `VOID`. `JvmUnitType` is the generated `Unit` class, a
  field-less class built with the same singleton members. The unit value is
  `JvmUnitLiteral`, lowered to `FlatGetStatic`. Omitted return types and `print` produce `Unit`.
  `Unit` implicitly has `Eq` and `Display`; its generated `equals` always returns `true`
  and its generated `toString` returns `Unit`, so `Unit` equality and printing use the object path.
- A zero-parameter top-level `main` gets an additional JVM entry point `main(String[])V` that calls
  the Vera `main` and discards its result. `JvmType.Array` represents the `String[]` parameter.
- **Operators**: `JvmIntUnaryOperator`, `JvmIntBinaryOperator`, and `JvmComparisonOperator` are
  independent enums.
- **Flat instruction IR** (`vera.jvm.instruction`): flat instruction lists with explicit
  `FlatInstructionEffect`, `FlatLabel` control flow, and `FlatInstructionWithDebugInfo`.
- Object construction uses `FlatNew`, `FlatDup`, and `FlatInvokeSpecial`; generated constructors
  initialize fields with `FlatPutField`.
- Object method calls use `FlatInvokeVirtual`; its `receiverType` participates in stack
  verification.
- `JvmInstanceOf`/`FlatInstanceOf` emit `instanceof` and `JvmCheckCast`/`FlatCheckCast` emit `checkcast`.
  Every trait's interface is sealed through `permittedSubclasses` (`PermittedSubclasses` attribute). The
  emitter gives the ClassFile API a class hierarchy resolver for the program's own classes, which
  stack maps need when branch types merge.
- Trait method calls use `FlatInvokeInterface` (`invokeinterface`). `FlatUpcast` retypes a class
  value to an interface on the simulated stack and emits no bytecode.
- `FlatPop` discards non-`VOID` expression results, including `Unit` and `if` values used as
  statements.
- `FlatGetStatic` carries both stack type (`fieldType`) and JVM descriptor (`fieldDescriptor`).
- `FlatPutStatic` stores static fields; static fields carry `isStatic` from `JvmField`/`FlatField`
  through to the emitter.
- No structured expressions/statements remain at the flat layer.

## Conventions

### Kotlin

- Kotlin 2.4.0; Java 25 source and bytecode.
- 4-space indentation.
- Sealed interfaces for IR hierarchies.
- `Jvm*` for structured IR, `Flat*` for flat instruction IR, and `Bytecode*` for emission.

### API Style

- Public pipeline services are stateless objects: `VeraLexer`, `VeraParser`,
  `JvmProgramLowering`, `FlatInstructionLowering`, `BytecodeEmitter`, `VeraCompiler`.
- Inputs are method arguments; mutable state is scoped to a single call.
- Stateful helpers are private `*State` classes created per operation.

### Error Handling

- Expected failures throw named exceptions extending `vera.shared.model.VeraException`.
- Each stage has a sealed hierarchy: `VeraLexException`, `VeraParseException`,
  `JvmLoweringException`, `FlatInstructionException`, `BytecodeEmitException`.
- Exceptions carry structured fields and build a user-facing `render()` message.
- User-facing messages name types with `VeraType.render()` / `JvmType.render()`, which use Vera's
  spelling (`Int`, `Bool`, `() -> String`), and never mention compiler internals.
- `VeraCompiler.compile` propagates exceptions; CLI and tests catch `VeraException`.
- Errors carry a `SourceSpan` (`VeraException.span`). Lexer and parser errors attach their character
  or token span. Every AST declaration, statement, and expression has a `span`; lowering attaches the
  innermost one with `located(span) { ... }` (first span wins) or `throw error.at(span)` for a more
  precise spot such as a single argument. Declaration spans cover the header (`fn add`).
- `render()` returns the message plus `at line L, column C`. `VeraDiagnostics.render(exception,
  source, fileName)` (compiler module) renders the message, `file:line:column`, and the source line
  with the span underlined; the CLI uses it. Messages themselves never embed positions.
- Hints: the lexer rejects Java operators with `VeraLexException.JavaOperator`, the parser rejects
  Java syntax with `VeraParseException.JavaSyntax` only where parsing would fail anyway (so `new`,
  `final`, and `for` stay ordinary identifiers), and lowering adds "did you mean" suggestions and
  Java name advice to unknown-name errors through `JvmNameHints`.
- Do not use `error()`, `require`, `check`, `TODO()`, `!!`, or unsafe casts in compiler modules.
- Unexpected JDK ClassFile API exceptions are compiler bugs and propagate unwrapped.
- Arrow and `VeraResult` are not used.

### Testing

- JUnit 5 (JUnit 6 API) with JUnit Platform.
- `compiler-tests` are integration tests: compile Vera code, load bytecode, execute, compare output.
- Test names are descriptive backticked Kotlin names.
- Error tests live in `CompilerErrorTests`; see [`docs/testing.md`](docs/testing.md).

### Workflow Keywords

- `review` — walk through all uncommitted changes, step by step.
- `push` — commit and push everything; ask only if a question or decision is needed.
- `note` — record the accompanying instruction or decision in `AGENTS.md` or a file referenced from it.
- `next` — pick the next feature to build from [`docs/roadmap.md`](docs/roadmap.md).

`review`, `push`, and `next` only apply when sent on their own; the same words inside a sentence are
ordinary text. `note` may be followed by the content to record. Reviewing changes belongs to `review`, not
`push`.

### Choosing the Next Feature

When the user sends **`next` on its own**:

1. Read [`docs/roadmap.md`](docs/roadmap.md) and choose the next feature to build: prefer items under
   *Important basic features*, in listed order, unless the user asks for something else or an item is
   explicitly blocked or undecided.
2. Report the chosen item with its area and acceptance criteria, and say why it is next.
3. Ask whether to start implementing; wait for confirmation.

### Git

- Commit messages describe **what was done** in past tense (e.g. `added operator precedence`).
- Review happens in the `review` step. `push` commits and pushes all changes and asks only when a
  question or decision is needed.

### Pushing Changes

When the user sends **`push` on its own**:

1. Ask only if something needs clarification or a decision.
2. Commit all changes.
3. Push everything.

Do not run a review or wait for review approval; that belongs to `review`.

### Reviewing Changes

When the user sends **`review` on its own**, perform a guided, step-by-step review of all
uncommitted changes:

1. Inspect `git status` and the diffs, including staged, unstaged, untracked, and deleted files.
   Include every change, no matter how small.
2. Run the tests if they have not run recently; report the result.
3. Before the first step, show an overview of all planned steps with a total count. Order them for
   comprehension, for example: data model → parser → structured IR → lowering → flat IR → emitter →
   tests → docs. Group tiny related changes; split large files into focused hunks. Each step must be
   small enough to follow easily.
4. For each step, show the full relevant diff or hunk explicitly in the response, alongside an
   explanation of what changed and why. The diff and the explanation are equally important; do not
   omit any change, no matter how small.
5. After each step, state what the next step is explicitly (for example `Next: step 5/11`), then ask
   whether the user has any questions.
6. Pause and wait for the user's reply before continuing. Answer questions, but do not resume until
   the user explicitly says to continue (for example `go on` or `next`).
7. End with a concise summary of the whole change set and stop. Do not commit or push; those happen
   only after the user says **`push`**.

### Naming

- Vera calls its user-defined types **classes** and their values **objects**. Use `class` in
  source and `Class`/`Object` in type names (`VeraClassDeclaration`, `JvmObjectConstruction`).
- Source/AST types use `Vera*` (`VeraProgram`, `VeraExpression`, `VeraToken`, ...).
- Structured IR types use `Jvm*` in `vera.jvm.ir` (`JvmProgram`, `JvmReturn`, ...).
- Flat instruction IR types use `Flat*` in `vera.jvm.instruction` (`FlatProgram`, `FlatLoadLocal`,
  ...).
- Lowering services are named by their target: `JvmProgramLowering` / `JvmFunctionLowering` produce
  structured IR; `FlatInstructionLowering` produces flat instruction IR.
- Mirror the data model in local names: `vera*` for AST values, `jvm*` for structured IR values,
  `flat*` for flat instruction values.
- Stateful helpers keep the full pipeline prefix: `JvmFunctionLoweringState`,
  `FlatInstructionLoweringState`, `VeraParserState`, `BytecodeEmitterState`.
- Use `lower*` for AST-to-IR and structured-to-instruction lowering; use `validate*`/`require*` for
  operand checks; use `append(...)` for one flat instruction.
- Reserve `emit*` for actual bytecode generation.
- Class and object types are `VeraClassDeclaration`/`VeraField`, `JvmField`/`JvmConstructor`/
  `JvmObjectConstruction`/`JvmGetField`/`JvmInstanceCall`, and `FlatNew`/`FlatGetField`/
  `FlatPutField`/`FlatInvokeSpecial`/`FlatInvokeVirtual`.
- Trait types are `VeraTraitDeclaration`/`VeraTraitMethod` and `VeraClassDeclaration.traits`;
  built-in traits live in `JvmBuiltinTraits` (`eq`, `display`, `names`), and generated trait
  implementations live in `JvmObjectMethods`.
- `Unit` uses `VeraUnitLiteral`, `JvmUnitLiteral`, `JvmUnitType`/`JvmPutStatic`, and
  `FlatGetStatic`/`FlatPutStatic`.
- The JVM entry-point adapter is built in `JvmProgramLowering` as a `JvmStaticCall` to `main`
  followed by `JvmReturn(null)`.
- JVM types use standard naming (e.g. `VeraMain`, `VeraApp`).

### AGENTS.md

- This file records project conventions, architecture, and important decisions.
- Update it when conventions change.

## Parsing

- Recursive descent: `VeraCompiler` → `VeraLexer` (including significant `EOL` tokens) →
  `VeraParser`.
- The grammar is written out in EBNF in the Grammar section of [`docs/language.md`](docs/language.md).
  Update it whenever `VeraParser` changes.
- Newlines terminate statements; a closing `}` also ends the last statement or member in a block
  (`VeraParserState.parseStatementTerminator`).
- `VeraLexer` decides line continuation and emits no `EOL` inside an open `(` (tracked with a
  bracket stack, so `{` blocks inside parentheses keep their `EOL`s), after a trailing operator,
  `not`, `=`, `,`, or `.`, and before a line starting with `|>` or `.`. A leading `-` or `not` never
  continues a line. `;` throws `VeraLexException.UnexpectedSemicolon`.
- Blank lines and extra `EOL` tokens are ignored at block boundaries and before `else`.
- CRLF is normalized to a single `EOL` token.
- `VeraLexer` skips `//` comments and `///` doc comments up to the line break, so comments never
  change line continuation; the leading-`|>`/`.` lookahead skips comment-only lines. There are no
  block comments.
- String literals allow arbitrary non-newline characters and decode `\"`, `\\`, `\n`, `\t`,
  and `\r` escapes.
- String templates: `VeraLexer` splits `"a \{x} b"` into `TEMPLATE_START`, the placeholder's tokens,
  `TEMPLATE_MIDDLE`, and `TEMPLATE_END`, tracking open placeholders by bracket depth so `{ }` blocks
  inside a placeholder work; a line break inside a placeholder is an unterminated string. The
  parser builds `VeraStringTemplate`, and lowering joins the parts with a `StringBuilder`
  (`JvmObjectMethods.concatenate`), rendering values as `print` does.
- Class fields are `val name: Type` (`VeraParserState.parseField`); a bare `name: Type` is
  `VeraParseException.FieldWithoutVal`, and `mut val` fields are `MutableFieldNotSupported` until
  `mut fn` exists.
- Constructs: `fn`, `class`, `trait` (closed; `sealed trait` gets a hint), `has`, `val`, `mut val`, rebinding with `=`,
  `if` / `else if` / `else`, `match` with `Pattern -> body` arms, `while`, `break`, `continue`, `print`.
- `if` and `while` conditions do not require parentheses; `( ... )` remains available as ordinary
  expression grouping.
- `break` and `continue` target the innermost `while`; using either outside a loop or inside a
  nested expression is rejected.
- Operators, lowest to highest: `|>`, `or`, `and`, `==`/`!=`, `<`/`>`/`<=`/`>=`, `+`/`-`, `*`/`/`/`%`.
  Unary `-` and `not` bind tighter than multiplicative operators; `not a == b` is `(not a) == b`,
  which reads like Java's `!a == b`.
- `==` and `!=` support `Int`, `Bool`, `String`, and `Unit`; `String` equality compares contents.
  Objects compare structurally only when their class has the built-in `Eq` trait. Ordering
  operators are `Int`-only.
- `print` accepts `Int`, `String`, `Bool`, `Unit`, and objects whose class has the built-in
  `Display` trait. `Bool` prints as `True`/`False` (matching the literals) through
  `JvmObjectMethods.boolToString`, used by both `print` and generated `toString`.
- `and` and `or` are short-circuiting reserved keywords; `not` is the `Bool`-only negation keyword.
  It lowers to `JvmLogicalNot` and `FlatBoolNot` (`iconst_1; ixor`).
- `|>` is forward application: `value |> f` lowers to `f(value)`. The forwarded value becomes the
  first argument, unnamed, and does not count toward the named-argument rules: `1 |> add(2)`.
- Call arguments are `name = value` or a bare expression (`VeraParserState.parseArgument`).
  Calls with two or more arguments must name them; see Lowering.
- The right side of `|>` may be a bare callable (function, built-in, class constructor, sibling
  object method) or a member call chain, e.g. `data |> processor.runtime.process() |> print()`.
- `print` is a built-in, not a keyword; top-level functions cannot reuse built-in names.
- Trait names share the top-level namespace with functions and classes; the built-in trait names
  `Eq` and `Display` are reserved.

## Name Resolution

- Shadowing is never allowed in Vera. Every name in a scope must be unique, including parameters,
  locals, fields, methods, top-level functions, classes, traits, and built-ins. A nested binding
  may not reuse an outer name; declarations must be renamed instead. Bindings are block-scoped, so
  sibling blocks may reuse a name.
- This rule is enforced during lowering.

## Non-goals

- Inheritance is not supported and will not be added. Vera uses classes, traits, and composition
  instead of inheritance hierarchies.

## Known TODOs

See [`docs/roadmap.md`](docs/roadmap.md) for planned work and acceptance criteria.

## Run / Debug

- The `cli` module provides `Main.kt` in the default package.
- No Gradle application/run task is configured; `./gradlew :cli:run` is unavailable.
- Add the `application` plugin or run `MainKt` manually with the classpath from `cli/build/libs/`.
