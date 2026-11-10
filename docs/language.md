# Vera Language

> **Status: experimental.** Current syntax only. See [Design Philosophy](design-philosophy.md) for
> long-term direction.

## Overview

- Statically typed, JVM-targeting language.
- Compiles through: source → lexer → parser → AST → structured IR → instruction IR → `.class`.
- Top-level functions compile to static JVM methods.
- The compiler receives the generated class name. A zero-parameter top-level function named `main` also gets
  a JVM `main(String[])` entry point.

Implemented:

- Top-level functions with typed parameters and optional return types.
- `var` bindings and rebinding with `=`.
- `Int`, `String`, `Bool`, and `Unit`.
- Integer arithmetic and comparisons.
- `and` / `or` short-circuiting boolean operators.
- `if` / `else if` / `else` expressions.
- `while`.
- Function calls, `print`, and forward application with `|>`.
- Top-level `record` declarations with immutable fields, positional construction, field access,
  and read-only instance methods.

Not implemented:

- `val` immutability enforcement.
- Record mutation (including `mut fn` methods), traits, classes.
- Effects, capabilities, services, errors-as-values.
- Java interop.
- `long`, `float`, `double`, numeric conversions.
- Comments, block scoping.
- Custom entry-point names (only `main` gets a JVM adapter).

See [`roadmap.md`](roadmap.md) for planned work and acceptance criteria.

## Non-goals

- Inheritance. Vera uses records, traits, and composition instead of class hierarchies.

## Keywords

Reserved words cannot be used as identifiers:

| Category | Keywords |
|----------|----------|
| Declarations | `fn`, `record`, `var`, `val` |
| Control flow | `if`, `else`, `while`, `return` |
| Operators | `and`, `or` |
| Types | `Int`, `String`, `Bool`, `Unit` |
| Boolean literals | `True`, `False` |

- `val` is reserved, but immutability is not enforced yet.
- `print` is a built-in function, not a keyword. Top-level functions cannot reuse built-in
  names, so `fn print(...)` is rejected.
- `else if` is not a separate keyword; it is an `else` block containing an `if` expression.

## Program structure

A program is a sequence of top-level function and record declarations.

```vera
fn add(a: Int, b: Int): Int {
    return a + b
}

fn main() {
    print(add(1, 2))
}
```

### JVM entry point

If a zero-parameter top-level function named `main` exists, the compiler also emits:

```text
public static void main(String[] args)
```

This adapter calls the Vera `main` and discards its result, then returns `void`. It lets the generated
class be launched with `java <ClassName>`, while the Vera `main` keeps its declared Vera return type
(including `Unit`). A `main` with parameters does not get an adapter.

## Functions

```vera
fn name(parameter: Type, other: Type): ReturnType {
    return expression
}
```

- Parameters require a name and type.
- Return type is optional; omitted return type means `Unit`.
- Calls check the argument count and types against the callee's parameters.
- A return expression must match the declared return type.
- `return` without an expression returns from a `Unit` function.
- A function without a top-level `return` gets an implicit final `return`; this requires a `Unit`
  return type.
- Returns nested inside `if` or `while` do not suppress that implicit return.
- Functions can only be declared at top level.

## Records

```vera
record Point {
    x: Int
    y: Int

    fn sum(): Int {
        return x + y
    }
}

fn main() {
    var point = Point(1, 2)
    print(point.x)
    print(point.sum())
}
```

- `record` declarations are top-level.
- Fields are declared one per line as `name: Type`.
- Methods are declared with `fn` inside the record body, before or after fields.
- Methods are instance methods. They can read the record's fields and call sibling methods by
  name, without an explicit `this`/`self` expression.
- Shadowing is not allowed: parameters, locals, fields, methods, top-level functions, records, and
  built-ins must have distinct names in their scope.
- Bare names resolve to parameters/locals, fields, sibling methods, then top-level functions and
  built-ins.
- Methods have typed parameters and an optional return type; an omitted return type means `Unit`.
- Methods can call top-level functions, built-ins, and other record methods.
- Method calls check argument count and types; method returns must match the declared return type.
- Methods are called after `.` and must include an argument list: `point.sum()`.
- Method overloading is not supported.
- A record cannot have both a field and a method with the same name.
- Field types may be `Int`, `String`, `Bool`, `Unit`, or another record type.
- Records are immutable; fields are read-only from Vera source.
- The compiler generates a positional constructor in field declaration order.
- Field access uses `.` and can be chained: `line.start.x`.
- Record types can be used in parameter, return, and local type annotations.
- Records compile to their own JVM classes with final fields, a generated constructor, and public
  instance methods.
- Mutation (including `mut fn`), equality, default values, and printing whole records are not
  implemented.

## Variables and rebinding

```vera
var count = 0
var name: String = "Vera"

count = count + 1
```

- `var` introduces a binding; the type is inferred or declared explicitly.
- `name = expression` rebinds an existing variable.
- Rebinding requires the same type as the existing variable.
- Rebinding an unknown variable is a compile-time error.
- `val` parses but is not yet enforced as immutable.
- Bindings are function-scoped; block scoping is not implemented.
- Names cannot be redeclared or shadow any name already in scope, including parameters, local
  bindings, fields, methods, top-level declarations, and built-ins.

## Types

| Type     | Meaning                      | Examples               |
|----------|------------------------------|------------------------|
| `Int`    | 32-bit signed integer        | `0`, `42`              |
| `String` | Text                         | `"hello"`              |
| `Bool`   | Boolean                      | `True`, `False`        |
| `Unit`   | Exactly one value            | `Unit`                 |
| Record   | User-defined immutable value | `Point`                |

- `Unit` is a real value: it can be stored in fields and variables, passed, and returned.
  The unit value is written `Unit`. Omitted return types and `print` produce `Unit`.
- String literals may contain any character except an unescaped `"` and raw newlines.
  Supported escapes: `\"`, `\\`, `\n`, `\t`, `\r`.
- Other numeric types are not implemented.

## Expressions

### Arithmetic

```vera
1 + 2
5 - 3
4 * 6
8 / 2
7 % 3
-5
-value
```

- `+`, `-`, `*`, `/`, `%` are `Int`-only.
- Unary `-` negates an `Int`.

### Comparisons

```vera
a == b
a != b
a < b
a > b
a <= b
a >= b
"hello" == "hello"
True != False
```

- `==` and `!=` compare `Int`, `Bool`, and `String` values and produce `Bool`.
- `String` equality compares contents, not reference identity.
- `<`, `>`, `<=`, and `>=` are `Int`-only and produce `Bool`.

### Boolean logic

```vera
left and right
left or right
```

- `and` and `or` are short-circuiting.
- Both operands must be `Bool`.

### Function calls

```vera
add(1, 2)
print("hello")
```

### Forward application

```vera
2 |> add(1)
data |> processor.runtime.process() |> print()
```

- `value |> f(args)` lowers to `f(value, args)`.
- `value |> f` lowers to `f(value)`.
- The right side may be a bare callable (function, built-in, record constructor, sibling record
  method) or a member call chain; the forwarded value becomes the first argument.
- Chained `|>` feeds each result into the next call.

## Operator precedence

Lowest to highest:

1. `|>`
2. `or`
3. `and`
4. `==`, `!=`
5. `<`, `>`, `<=`, `>=`
6. `+`, `-`
7. `*`, `/`, `%`

Unary `-` binds tighter than multiplicative operators and is right-associative.

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

- Condition must be `Bool`.
- Parentheses around the condition are optional; `( ... )` is ordinary expression grouping.
- `else` is optional.
- `else if` chains select the first matching branch.
- `else` may appear on the next line, including after blank lines.
- `if` is an expression when both branches are present.
- A branch result is its final expression; earlier expressions are discarded.
- Both branches must have the same type.
- A branch without a final expression produces `Unit`.
- `if` without `else` produces `Unit`.

```vera
var label = if count > 0 {
    "positive"
} else {
    "not positive"
}

var result = if count > 10 {
    count * 2
} else if count > 5 {
    count + 1
} else {
    0
}
```

### `while`

```vera
var counter = 0

while counter < 3 {
    print(counter)
    counter = counter + 1
}
```

- Condition must be `Bool`.
- Parentheses around the condition are optional; `( ... )` is ordinary expression grouping.
- Body is a block and may contain blank lines.
- `while` is a statement and produces no value.

## Built-in `print`

```vera
print(42)
print("hello")
print(True)
```

- Writes one value followed by a newline to standard output.
- Returns `Unit`.

## Newlines and blocks

- Newlines terminate statements.
- Semicolons are not used.
- Blank lines are ignored.
- Blank lines are allowed at block boundaries and before `else`.
- Record fields are also newline-terminated.
- CRLF line endings are supported.
- Comments are not implemented.

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

    print(2 |> add(1))
}
```

## Further reading

- [Design Philosophy](design-philosophy.md) — long-term direction and compiler principles
- [AGENTS.md](../AGENTS.md) — contributor and coding-agent conventions
