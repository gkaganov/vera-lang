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
- Top-level `class` declarations with immutable fields, positional construction, field access,
  and read-only instance methods.
- Top-level `trait` declarations and `class ... has ...` clauses.
- Structural object equality through the built-in `Eq` trait.
- Object printing through the built-in `Display` trait.

Not implemented:

- `val` immutability enforcement.
- Object mutation (including `mut fn` methods).
- Trait default method bodies and trait-typed values.
- Effects, capabilities, services, errors-as-values.
- Java interop.
- `long`, `float`, `double`, numeric conversions.
- Comments.
- Custom entry-point names (only `main` gets a JVM adapter).

See [`roadmap.md`](roadmap.md) for planned work and acceptance criteria.

## Non-goals

- Inheritance. Vera uses classes, traits, and composition instead of inheritance hierarchies.

## Keywords

Reserved words cannot be used as identifiers:

| Category | Keywords |
|----------|----------|
| Declarations | `fn`, `class`, `trait`, `has`, `var`, `val` |
| Control flow | `if`, `else`, `while`, `break`, `continue`, `return` |
| Operators | `and`, `or`, `not` |
| Types | `Int`, `String`, `Bool`, `Unit` |
| Boolean literals | `True`, `False` |

- `val` is reserved, but immutability is not enforced yet.
- `print` is a built-in function, not a keyword. Top-level functions cannot reuse built-in
  names, so `fn print(...)` is rejected.
- `else if` is not a separate keyword; it is an `else` block containing an `if` expression.

## Program structure

A program is a sequence of top-level function, class, and trait declarations.

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
- A function that does not always return gets an implicit final `return`; this requires a `Unit`
  return type.
- A function always returns when a top-level statement is a `return`, or an `if` with an `else`
  whose branches all always return (including `else if` chains). Returns inside `while` do not
  count.
- A statement after `return`, `break`, `continue`, or an `if` whose branches all return, in the same
  block, is a compile error (`unreachable statement`), as in Java. This includes the value expression
  at the end of an `if` block.
- Functions can only be declared at top level.

## Classes and objects

```vera
class Point {
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

- `class` declarations are top-level.
- Fields are declared one per line as `name: Type`.
- Methods are declared with `fn` inside the class body, before or after fields.
- Methods are instance methods. They can read the class's fields and call sibling methods by
  name, without an explicit `this`/`self` expression.
- Shadowing is not allowed: parameters, locals, fields, methods, top-level functions, classes,
  traits, and built-ins must have distinct names in their scope.
- Bare names resolve to parameters/locals, fields, sibling methods, then top-level functions and
  built-ins.
- Methods have typed parameters and an optional return type; an omitted return type means `Unit`.
- Methods can call top-level functions, built-ins, and other class methods.
- Method calls check argument count and types; method returns must match the declared return type.
- Methods are called after `.` and must include an argument list: `point.sum()`.
- Method overloading is not supported.
- A class cannot have both a field and a method with the same name.
- Field types may be `Int`, `String`, `Bool`, `Unit`, or another class type.
- Objects are immutable; fields are read-only from Vera source.
- The compiler generates a positional constructor in field declaration order.
- Field access uses `.` and can be chained: `line.start.x`.
- Class types can be used in parameter, return, and local type annotations.
- Each class compiles to its own JVM class with final fields, a generated constructor, and public
  instance methods.
- A class can declare traits after `has`, for example `class Point has Eq, Display { ... }`.
- Mutation (including `mut fn`) and default field values are not implemented.

## Traits

```vera
trait Shape {
    fn area(): Int
}

class Square has Shape {
    side: Int

    fn area(): Int {
        return side * side
    }
}
```

- `trait` declarations are top-level and contain method signatures only: `fn name(params): Type`.
  Signatures end at a newline or before the closing brace, so `trait Shape { fn area(): Int }` is
  valid.
- A class opts into traits with `has` after its name: `class Square has Shape`.
- An implementing class must define every trait method with the same parameter types and return
  type; parameter names may differ.
- Traits are compile-time contracts: they produce no bytecode, are not types, and cannot be used
  in annotations. Trait methods are called on the class, with static dispatch.
- Traits cannot declare fields, default method bodies, or overloads.
- Trait method parameter and return types may be `Int`, `String`, `Bool`, `Unit`, or class types.
- Trait names share the top-level namespace with functions and classes.
- Two built-in traits are always available and are implemented by the compiler:
  - `Eq` generates a structural `equals`, enabling `==` and `!=` on the class's objects.
  - `Display` generates a `toString`, enabling `print` of the class's objects.
- Built-in trait names are reserved: no function, class, or trait can be named `Eq` or `Display`.
- A class with `Eq` or `Display` must have the same trait on every class-typed field, so nested
  objects stay comparable and printable.
- `Unit` implicitly has `Eq` and `Display`, so `Unit == Unit` holds and `print(Unit)` works.
- A class that declares `Eq` or `Display` cannot define the corresponding `equals` or `toString`
  method itself.

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
- Bindings are block-scoped: a `var` declared inside an `if` branch or `while` body is not
  visible after that block.
- Names cannot be redeclared or shadow any name already in scope, including parameters, local
  bindings, fields, methods, top-level declarations, and built-ins. Sibling blocks may reuse a name,
  because neither binding is in scope in the other.

## Types

| Type     | Meaning                       | Examples               |
|----------|-------------------------------|------------------------|
| `Int`    | 32-bit signed integer         | `0`, `42`, `-2147483648` |
| `String` | Text                          | `"hello"`              |
| `Bool`   | Boolean                       | `True`, `False`        |
| `Unit`   | Exactly one value             | `Unit`                 |
| Class    | User-defined immutable object | `Point`                |

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

- `==` and `!=` produce `Bool`.
- `Int`, `Bool`, and `String` values compare by value; `String` equality compares contents, not
  reference identity.
- `Unit` values are always equal.
- Objects of the same class compare structurally, field by field, when the class has the `Eq`
  trait; nested objects compare recursively. Comparing objects of a class without `Eq` is
  rejected, and objects of different classes cannot be compared.
- `<`, `>`, `<=`, and `>=` are `Int`-only and produce `Bool`.

### Boolean logic

```vera
left and right
left or right
not done
```

- `and` and `or` are short-circuiting.
- `not` negates a `Bool`. Like unary `-`, it binds tighter than every binary operator, so
  `not a == b` means `(not a) == b` and `not a and b` means `(not a) and b`. Write `not (a == b)`
  or `a != b` to negate a comparison.
- `not` applies to a whole member chain: `not task.isFinished()` negates the call result.
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
- The right side may be a bare callable (function, built-in, class constructor, sibling object
  method) or a member call chain; the forwarded value becomes the first argument.
- Chained `|>` feeds each result into the next call.
- Evaluation is left to right: the forwarded value is evaluated before the receiver chain and the
  explicit arguments of the call it is passed to.

## Operator precedence

Lowest to highest:

1. `|>`
2. `or`
3. `and`
4. `==`, `!=`
5. `<`, `>`, `<=`, `>=`
6. `+`, `-`
7. `*`, `/`, `%`

Unary `-` and `not` bind tighter than multiplicative operators and are right-associative; member
access and calls bind tighter still.

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
- `break` exits the innermost `while`.
- `continue` jumps to the next condition check of the innermost `while`.
- `break` and `continue` are only valid inside a loop.
- They may appear in statements, including inside the branches of an `if` whose value forms a whole
  statement, but not inside a nested expression such as a call argument or operator operand, where
  the jump would abandon a partially evaluated expression:

Allowed, because the outer `if` forms the whole `var` statement:

```vera
while True {
    var next = if ready {
        if done { break }
        step()
    } else {
        0
    }
}
```

Rejected, because the `break` is nested in a call argument:

```vera
while True {
    print(add(1, if done { break } else { 2 }))
}
```
- `while` is a statement and produces no value.

## Built-in `print`

```vera
print(42)
print("hello")
print(True)
print(Unit)
print(Point(1, 2))
```

- Writes one value followed by a newline to standard output.
- Prints `Int`, `String`, and `Bool` values directly and `Unit` as `Unit`.
- Prints an object as `ClassName(field: value, ...)` only when its class has the `Display`
  trait; nested objects print recursively through their own `Display` implementations.
- Returns `Unit`.

## Newlines and blocks

- A newline ends a statement, a class member, or a trait signature. A closing `}` also ends the
  last one, so blocks fit on one line: `if c { 1 } else { 2 }`, `class Point { x: Int }`.
- Semicolons are not used; writing one is a compile error that says so.
- A statement continues on the next line only when that is visible on the line itself:
  - inside an open `(`, newlines are ignored, so argument lists and grouped expressions can span
    lines (a `{` block inside the parentheses still uses newline-terminated statements);
  - a line that ends with a binary operator, `not`, `=`, `,`, or `.` continues;
  - a line that starts with `|>` or `.` continues the previous line, for top-to-bottom pipelines and
    member chains.
- A line starting with `-` or `not` does not continue the previous line: it starts a new statement.
  Put the operator at the end of the line instead.
- Blank lines are ignored, including at block boundaries, before `else`, and inside continuations.
- CRLF line endings are supported.
- Comments are not implemented.

```vera
var report = orders
    |> paidOnly()
    |> summarize()

var total = subtotal +
    shipping
```

## Grammar

The parser is hand-written recursive descent (`VeraParser.kt`): each rule below is one `parse*`
function, `{ … }` is a loop, and `[ … ]` is optional. Keep this section in sync with the parser.

Notation: `"text"` is a keyword or symbol, `UPPER` is a token class, and `EOL` is a newline token
(one or more line breaks; CRLF counts as one).

### Declarations

```text
program        = { EOL } { declaration { EOL } } EOF
declaration    = function | class | trait

function       = "fn" IDENT signature block
signature      = "(" [ parameter { "," parameter } ] ")" [ ":" type ]
parameter      = IDENT ":" type

class          = "class" IDENT [ "has" IDENT { "," IDENT } ] "{" { EOL } { member statementEnd } "}"
member         = IDENT ":" type
               | function

trait          = "trait" IDENT "{" { EOL } { traitMethod statementEnd } "}"
traitMethod    = "fn" IDENT signature

type           = "Int" | "String" | "Bool" | "Unit" | IDENT
```

### Statements

```text
block          = "{" { EOL } { statement statementEnd } "}"
statementEnd   = EOL | EOF | (* nothing, before the closing "}" *)

statement      = ( "var" | "val" ) IDENT [ ":" type ] "=" expression
               | IDENT "=" expression             (* rebinding; chosen when IDENT is followed by "=" *)
               | "return" [ expression ]          (* no expression before EOL, EOF, or "}" *)
               | "while" expression block
               | "break"
               | "continue"
               | expression
```

### Expressions

Each level binds tighter than the one above it; binary operators are left-associative.

```text
expression     = forward
forward        = or       { "|>" or }
or             = and      { "or" and }
and            = equality { "and" equality }
equality       = compare  { ( "==" | "!=" ) compare }
compare        = additive { ( "<" | ">" | "<=" | ">=" ) additive }
additive       = multiply { ( "+" | "-" ) multiply }
multiply       = unary    { ( "*" | "/" | "%" ) unary }
unary          = "-" INT                          (* folded into one literal, e.g. -2147483648 *)
               | ( "-" | "not" ) unary
               | chained
chained        = primary { "." IDENT | "(" [ expression { "," expression } ] ")" }
primary        = INT | STRING | "True" | "False" | "Unit" | IDENT
               | "(" expression ")"
               | if

if             = "if" expression block [ { EOL } "else" ( if | block ) ]
```

- In an `if` block, a final expression statement becomes the block's value.
- `{ EOL }` before `else` is only consumed when `else` follows; otherwise the newlines end the
  enclosing statement.

### Tokens

```text
IDENT          = ( letter | "_" ) { letter | digit | "_" }   (* ASCII; keywords excluded *)
INT            = digit { digit }                              (* must fit in a 32-bit Int *)
STRING         = '"' { character | escape } '"'               (* no line breaks inside *)
escape         = "\n" | "\t" | "\r" | '\"' | "\\"
```

Spaces and tabs separate tokens and are otherwise ignored. The lexer drops a newline (emits no
`EOL`) inside an open `(`, after a trailing operator, `not`, `=`, `,`, or `.`, and before a line
starting with `|>` or `.`. A `;` is rejected with a dedicated error.

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
