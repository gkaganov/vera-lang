# Vera Language

> **Status: experimental.** This document describes the syntax that is implemented today.  
> For the long-term vision and planned features, see [Design Philosophy](design-philosophy.md).

## What is Vera?

Vera is a small, statically typed programming language targeting the JVM. It aims to combine
practical functional programming with straightforward data-oriented design, while keeping the
compiler pipeline explicit and predictable.

Vera is not a Java replacement and not a purely functional language. It is a pragmatic experiment
in a small language with strong compile-time checks and a direct JVM target.

## Design goals

- Prefer a small, coherent language over a large feature set.
- Make mutation and side effects explicit.
- Provide useful compile-time guarantees without excessive annotation.
- Favor composition over inheritance.
- Treat the JVM as a first-class target.
- Keep the compiler understandable: lexer → parser → AST → structured IR → instruction IR → emitter.

Some of these goals are not implemented yet; see [Current limitations](#current-limitations).

## Current status

Implemented:

- Top-level functions with typed parameters and optional return types
- `var` bindings and rebinding with `=`
- `Int`, `String`, `Bool`, and `Unit` types
- Integer arithmetic and comparisons
- `and` / `or` short-circuiting boolean operators
- `if` / `else`
- `while`
- Function calls and `print`
- Forward application with `|`

Not implemented yet:

- `val` immutability enforcement
- Records, traits, classes, and inheritance
- Effects/capabilities, services, and errors-as-values
- `for`, member/field access, Java interop
- `long`, `float`, `double`, and numeric conversions
- Comments, block scoping, and unary operators
- User-defined `main` entry-point conventions

## Program structure

A Vera program is a sequence of top-level function declarations.

```vera
fn add(a: Int, b: Int): Int {
    return a + b
}

fn main() {
    print(add(1, 2))
}
```

Functions are compiled as static JVM methods. The compiler is given the generated class name; the
language itself does not currently assign special meaning to a function named `main`.

## Functions

```vera
fn name(parameter: Type, other: Type): ReturnType {
    // body
    return expression
}
```

- Parameters require an explicit name and type.
- The return type is optional. A function without a return type is treated as returning `Unit`.
- `return` with no expression returns from a `Unit` function.
- A function without a top-level `return` statement gets an implicit `return` at the end. Returns nested inside `if` or `while` blocks do not currently suppress this implicit return.
- Functions can only be declared at the top level.

## Variables and rebinding

```vera
var count = 0
var name: String = "Vera"

count = count + 1
```

- `var` introduces a binding. The type can be inferred from the initializer or declared explicitly.
- `name = expression` rebinds an existing variable.
- The new value must have the same type as the existing variable.
- Rebinding an unknown variable is a compile-time error.
- `val` is currently parsed but is not yet enforced as immutable.

Bindings are currently tracked per function. Block scoping is planned but not implemented.

## Types

| Type | Meaning | Examples |
|---|---|---|
| `Int` | 32-bit signed integer | `0`, `42`, `100` |
| `String` | Text | `"hello"` |
| `Bool` | Boolean | `True`, `False` |
| `Unit` | No useful value | Return type of `print` |

String literals currently accept a limited character set: letters, digits, spaces, and `!`.

`long`, `float`, `double`, records, traits, and user-defined types are not implemented yet.

## Expressions

### Arithmetic

```vera
1 + 2
5 - 3
4 * 6
8 / 2
7 % 3
```

Arithmetic operators are defined for `Int` only. There is no unary minus yet.

### Comparisons

```vera
a == b
a != b
a < b
a > b
a <= b
a >= b
```

Comparisons are defined for `Int` only and produce a `Bool`.

### Boolean logic

```vera
left and right
left or right
```

`and` and `or` are short-circuiting and require `Bool` operands.

### Function calls

```vera
add(1, 2)
print("hello")
```

### Forward application

```vera
2 | add(1)
```

`value | f(args)` is lowered to `f(value, args)`. `value | f` is lowered to `f(value)`.

## Operator precedence

Lowest to highest:

1. `|`
2. `or`
3. `and`
4. `==`, `!=`
5. `<`, `>`, `<=`, `>=`
6. `+`, `-`
7. `*`, `/`, `%`

## Control flow

### `if` / `else`

```vera
if condition {
    print("yes")
} else if otherCondition {
    print("maybe")
} else {
    print("no")
}
```

- The condition must be a `Bool`.
- `else` is optional.
- `else if` chains are supported and select the first matching branch.
- `else` may appear on the next line, including after blank lines.
- `if` is currently parsed as an expression, but its value type is `Unit`.

### `while`

```vera
var counter = 0

while counter < 3 {
    print(counter)
    counter = counter + 1
}
```

- The condition must be a `Bool`.
- The body is a block and may contain blank lines.
- `while` is a statement and produces no value.

`for` is not implemented yet.

## Built-in `print`

```vera
print(42)
print("hello")
print(True)
```

`print` writes one value followed by a newline to standard output and returns `Unit`.

## Newlines and blocks

- Newlines terminate statements.
- Semicolons are not used.
- Blank lines are ignored.
- Blank lines are allowed at block boundaries and before `else`.
- CRLF line endings are supported.
- Comments are not implemented yet.

## Example

```vera
fn add(a: Int, b: Int): Int {
    return a + b
}

fn main() {
    var counter = 0

    while counter < 3 {
        print(counter)
        counter = counter + 1
    }

    if add(1, 2) == 3 {
        print("ok")
    }

    print(2 | add(1))
}
```

## Compiler architecture

The current pipeline is:

```text
Source
  → lexer
  → recursive-descent parser
  → AST
  → structured JVM IR (JvmIr)
  → flat instruction IR (JvmInstructionProgram)
  → JVM bytecode emitter
  → .class files
```

The structured IR represents expressions and statements without labels or local slots. The
instruction-lowering pass owns control flow, local slot allocation, and stack verification. The
bytecode emitter only translates verified flat instructions.

See [AGENTS.md](../AGENTS.md) for the current architecture and contributor conventions.

## Current limitations

These are intentional gaps in the current implementation, not necessarily permanent language
decisions:

- No comments.
- No block scoping.
- No unary operators.
- No `for` loops.
- No member access, field access, or Java interop.
- No records, traits, classes, or inheritance.
- No effects, capabilities, services, or errors-as-values.
- No `val` immutability enforcement.
- No `long`, `float`, `double`, or numeric conversions.
- String literals only support letters, digits, spaces, and `!`.

## Further reading

- [Design Philosophy](design-philosophy.md) — long-term vision, planned language features, and compiler principles
- [Structured JVM IR Migration](structured-jvm-ir-migration.md) — how the current two-layer IR was designed
- [AGENTS.md](../AGENTS.md) — contributor and coding-agent conventions
