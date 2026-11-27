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
- `val` bindings, immutable by default, and `mut val` bindings that can be rebound with `=`.
- `Int`, `String`, `Bool`, and `Unit`.
- Integer arithmetic and comparisons.
- `and` / `or` short-circuiting boolean operators.
- `if` / `else if` / `else` expressions.
- `while`.
- Function calls, `print`, and forward application with `|>`.
- Top-level `class` declarations with immutable fields, named construction, field access,
  and read-only instance methods.
- Top-level `trait` declarations and `class ... has ...` clauses.
- Structural object equality through the built-in `Eq` trait.
- Object printing through the built-in `Display` trait.
- `//` line comments and `///` doc comments.
- String templates with `\{expr}` placeholders.

Not implemented:

- Object mutation (including `mut fn` methods).
- Trait default method bodies.
- Effects, capabilities, services, errors-as-values.
- Java interop.
- `long`, `float`, `double`, numeric conversions.
- Attaching `///` doc comments to declarations (they are currently skipped like `//`).
- Custom entry-point names (only `main` gets a JVM adapter).

See [`roadmap.md`](roadmap.md) for planned work and acceptance criteria.

## Non-goals

- Inheritance. Vera uses classes, traits, and composition instead of inheritance hierarchies.

## Keywords

Reserved words cannot be used as identifiers:

| Category | Keywords |
|----------|----------|
| Declarations | `fn`, `class`, `trait`, `interface`, `has`, `implements`, `requires`, `val`, `mut` |
| Control flow | `if`, `else`, `match`, `while`, `break`, `continue`, `return` |
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
    print(add(a = 1, b = 2))
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

fn double(value: Int): Int = value * 2
fn log(text: String) = print(text)
```

- Parameters require a name and type.
- Parameter, argument, and pattern lists may end with a trailing comma (`add(left = 1, right = 2,)`),
  which keeps multi-line lists uniform.
- Return types are written out, except for `Unit`: a function without a return type returns `Unit`.
  Writing `: Unit` is a compile error, so there is one way to declare a `Unit` function.
- `= expression` is a short form for a function whose body is a single expression; it returns the
  expression's value. A non-`Unit` expression body needs its return type written:
  `fn double(value: Int) = value * 2` is an error. Block bodies keep explicit `return`.
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
- Functions can only be declared at top level, in any order; a function can call one declared after
  it.
- Functions and methods can call themselves and each other recursively.

## Classes and objects

```vera
class Point {
    val x: Int
    val y: Int

    fn sum(): Int {
        return x + y
    }
}

fn main() {
    val point = Point(x = 1, y = 2)
    print(point.x)
    print(point.sum())
}
```

- `class` declarations are top-level.
- Fields are declared one per line as `val name: Type`, the same `val` as for immutable locals. A
  field without `val` is an error with a hint. `mut val` fields are reserved for the planned
  `mut fn` methods and are rejected until those exist.
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
- Field types may be `Int`, `String`, `Bool`, `Unit`, a class type, or a trait type.
- Objects are immutable; fields are read-only from Vera source.
- The compiler generates a constructor with one parameter per field, in field declaration order.
  Construction follows the named-argument rules: `Point(x = 1, y = 2)`.
- A class without fields has exactly one value, so it is a singleton written by its name, with no
  constructor call: `Empty`, `print(Empty)`, `Geometry.square(3)`. Writing `Empty()` is an error with
  a hint. `Display` prints its name (`Empty`). `Unit` is the built-in example.
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
    val side: Int

    fn area(): Int {
        return side * side
    }
}
```

- `trait` declarations are top-level and contain method signatures (`fn name(params): Type`) and
  required fields (`val name: Type`). Each ends at a newline or before the closing brace, so
  `trait Shape { fn area(): Int }` is valid.
- A class opts into traits with `has` after its name: `class Square has Shape`.
- An implementing class must define every trait method with the same parameter types and return
  type and the same parameter names. Parameter names are part of the trait, because callers name
  their arguments.
- A trait is also a type. Parameters, returns, locals, and fields can have a trait type, and an
  object whose class has the trait can be used wherever that trait is expected:
  `fn describe(shape: Shape): Int = shape.area()` accepts `Square(3)`.
- A trait-typed value exposes only the trait's fields and methods. Calling a method runs the
  method of the object's actual class (dynamic dispatch).
- A required field is a requirement, not storage: every class with the trait declares the field
  itself, with the same type, and nothing is inherited. Trait values can read it:

  ```vera
  trait Event {
      val timestamp: Int
  }

  class Login has Event {
      val user: String
      val timestamp: Int
  }

  fn age(event: Event, now: Int): Int = now - event.timestamp
  ```

  Required fields are read-only, like every field, and a trait cannot have a field and a method of
  the same name. On the JVM, the interface declares an accessor method `timestamp()` and each class
  implements it.
- Traits compile to JVM interfaces that their classes implement, so Java code sees ordinary
  interfaces.
- A trait can require other traits: `trait Shape requires Display, Eq { ... }` means every member of
  `Shape` has them. This is interface inheritance only; traits carry no storage or code.
  - A class joining the trait lists the required traits itself (`class Square has Shape, Display, Eq`),
    so its declaration shows every trait it has; a missing one is an error naming it. Cases in a
    trait's block get the requirements automatically.
  - A `Shape` value can be printed and used in string templates when `Shape` requires `Display`, and
    compared with `==` when it requires `Eq`. Objects of different classes compare as the one trait
    they share: equal only if they have the same class and equal fields.
  - A `Shape` value is also a value of every trait `Shape` requires, and their fields and methods
    can be used on it.
  - A class with `Eq` or `Display` can have a trait-typed field when that trait requires the same
    built-in trait.
  - Requirements must not form a cycle. The built-in traits `Eq` and `Display` are not types.
- Traits cannot declare default method bodies or overloads.
- Trait method parameter types may be `Int`, `String`, `Bool`, `Unit`, class types, or trait types; return types
  follow the function rules, so a `Unit` method leaves its return type out.
- Trait and interface names share the top-level namespace with functions and classes.
- Traits are closed: only classes in the same module can have them, and a program is one module
  for now. A trait's cases are therefore always known, so a `match` on a `Shape` can check that it
  covers every one. Each trait compiles to a JVM sealed interface that permits exactly its classes.
  For open contracts, see [Interfaces](#interfaces).
  Writing `sealed trait` is an error with a hint, since every trait is already closed.
- When `if` or `match` branches produce objects of different classes, the result has the one user
  trait they all share: `if big { Circle(5) } else { Empty }` is a `Shape`.
- Two built-in traits are always available and are implemented by the compiler:
  - `Eq` generates a structural `equals`, enabling `==` and `!=` on the class's objects.
  - `Display` generates a `toString`, enabling `print` of the class's objects.
- Built-in trait names are reserved: no function, class, or trait can be named `Eq` or `Display`.
- A class with `Eq` or `Display` must have the same trait on every class-typed field, so nested
  objects stay comparable and printable.
- Classes without fields, including `Unit` and field-less trait cases, implicitly have `Eq` and
  `Display`: they have a single value, so `Empty == Empty` holds and `print(Color.Red)` prints
  `Color.Red`. Writing `has Eq` on them is allowed but redundant. A trait value still needs its trait
  to require `Eq` or `Display`.
- `toString`, `equals`, and `hashCode` are reserved member names in every class, trait, and
  interface: they are the JVM's `Object` methods, which `Display` and `Eq` generate. Customizing
  them by hand, as in Java or Kotlin, is an error that points to the trait.

### Trait cases

A trait can list its own cases in a block instead of letting classes join it:

```vera
trait Color { Red, Green, Blue }

trait Shape {
    fn area(): Int
    Circle has Display {
        val radius: Int
        fn area(): Int = 3 * radius * radius
    }
    Empty has Display {
        fn area(): Int = 0
    }
}

val color = Color.Red
val shape: Shape = Shape.Circle(2)
print(shape)                              // Shape.Circle(radius: 2)

fn name(color: Color): String = match color {
    Red -> "red"
    Green -> "green"
    Blue -> "blue"
}
```

- A case is `Name`, optionally followed by `has Traits` and a body with `val` fields and `fn` methods,
  without the `class` keyword. Cases are separated by commas or newlines, and a trailing comma is
  allowed. A case with `has` needs a body, which ends its trait list.
- A trait takes one form: either it lists its cases, or classes join it with `has`. A class cannot
  join a trait that lists its cases.
- Cases are classes owned by their trait and always written qualified: `Color.Red`,
  `Shape.Circle(2)`, `val circle: Shape.Circle`, even inside the trait. The only exception is a
  pattern in a `match` on the owning trait, which can only name that trait's cases: `Red ->`.
- A case can also join other traits (`Red has Printable { ... }`); in a `match` on such a trait it is
  written qualified (`Color.Red ->`).
- A case without fields is a singleton (`Color.Red`). Every case must implement the trait's methods
  and declare its required fields in its body, so a trait with required fields has no body-less cases.
- `Display` prints a case qualified: `Color.Red`, `Shape.Circle(radius: 2)`.
- A case name may match a top-level class name: `Tree.Leaf` and `Leaf` never meet.
- Cases compile to static nested JVM classes, which Java sees as `Shape.Circle`.

## Interfaces

A trait is closed: its classes are all known, so it can be matched. An interface is open and
structural: every class with its members fits it, without declaring anything. Use interfaces for
contracts that any class may meet, such as the parameters of an API.

```vera
interface Named {
    fn name(): String
}

class Dog {
    fn name(): String = "Rex"
}

class User implements Named {
    val login: String
    fn name(): String = login
}

fn greet(named: Named) = print("hello \{named.name()}")

greet(Dog)                     // hello Rex
greet(User("ada"))             // hello ada
```

- An interface declares methods and required fields, like a trait, but has no cases.
- A class fits an interface when it has every field with the same type and every method with the
  same parameter types, parameter names, and return type. Fields fit fields and methods fit
  methods. A fitting object can be used wherever the interface is expected, and an interface-typed
  value exposes only the interface's fields and methods.
- `implements Named` after a class's traits (`class User has Shape implements Named`) states that
  the class fits, and the compiler reports what is missing where the class is declared. It is
  optional and changes nothing else. Trait cases can state interfaces too
  (`Square implements Named { ... }`).
- `has` is only for traits and `implements` only for interfaces; using the wrong one is an error
  with a hint.
- An interface can require other interfaces and the built-in traits: `interface Pet requires Named,
  Display`. A class fits `Pet` when it fits `Named`, has `Display`, and has `Pet`'s own members. A
  `Pet` value is also a `Named` value, and it can be printed.
- A trait value fits an interface when its trait, with the traits it requires, has the interface's
  members: a `Shape` value can be passed as `Named` when `Shape` declares `fn name(): String`.
  Traits cannot require interfaces, and interfaces cannot require traits.
- An interface value cannot be matched: any class may fit the interface, so its cases are never all
  known. Use its members, or match on a trait.
- `if` and `match` branches of different classes are not inferred to be an interface, because a
  class fits one by its members alone. They unify only to a shared trait, or to a branch's type when
  every other branch converts to it.
- On the JVM, an interface is an ordinary, non-sealed interface. The compiler adds it to every
  fitting class in the program, and Java classes can implement it explicitly. A trait whose values
  fit an interface extends it.

## Variables and rebinding

```vera
val name: String = "Vera"
mut val count = 0

count = count + 1
```

- `val` introduces an immutable binding; the type is inferred or declared explicitly.
- `mut val` introduces a binding that can be rebound. Every declaration reads as adjective and
  noun, like the planned `mut fn`: a `mut val` is a mutable value.
- `name = expression` rebinds a `mut val`. Rebinding a `val` is a compile error that suggests
  `mut val`.
- Parameters are immutable, and the signature never marks them `mut`. To rebind one, write
  `mut val name` without a value directly in the function body; from that line on, the parameter
  `name` can be rebound:

```vera
fn countdown(from: Int) {
    mut val from
    while from > 0 {
        print(from)
        from = from - 1
    }
}
```

- `mut val name` without a value only applies to parameters, only once per parameter, and not inside
  a nested block.
- Rebinding requires the same type as the existing binding.
- Rebinding an unknown variable is a compile-time error.
- Vera has no `var`; writing it is a compile error that suggests `val` or `mut val`.
- Bindings are block-scoped: a `val` declared inside an `if` branch or `while` body is not
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

- `Unit` is a real value: it can be stored in fields and variables, passed, and returned. It is the
  built-in class without fields, so like any such class it is a singleton written by its name.
  The unit value is written `Unit`. Omitted return types and `print` produce `Unit`.
- `True` and `False` are the two variants of `Bool`, just as `Unit` is the single variant of `Unit`.
  They print as written: `print(1 < 2)` outputs `True`.
- Casing convention: types and variants are uppercase (`Int`, `Point`, `True`, `Unit`); variables,
  parameters, fields, functions, and methods are lowercase. Vera has no primitive types to treat
  differently.
- String literals may contain any character except an unescaped `"` and raw newlines.
  Supported escapes: `\"`, `\\`, `\n`, `\t`, `\r`, and `\uXXXX` with exactly four hex digits, as in
  Java and Kotlin (`"caf\u00E9"`).
- `\{` starts a string template placeholder; see [String templates](#string-templates).
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

### String templates

```vera
print("Hello, \{name}!")
print("\{a} + \{b} = \{a + b}")
print("\{user.name} has \{user.count()} items")
```

- `\{expr}` inside a string literal inserts the value of `expr`. Any expression is allowed,
  including calls, `if` expressions, and nested strings, but a placeholder must fit on one line.
- Values render exactly as `print` renders them: `Int` and `String` directly, `Bool` as
  `True`/`False`, `Unit` as `Unit`, and objects through their `Display` trait. Using an object whose
  class lacks `Display` is a compile error.
- Placeholders are evaluated left to right.
- A plain `{` is ordinary text, so `"{\"key\": \{value}}"` works. Write `\\{` for a literal
  backslash followed by `{`.
- Templates are the way to build strings; `+` is only defined for `Int`.

### Function calls

```vera
add(a = 1, b = 2)
Point(x, y)
print("hello")
```

- A call with two or more written arguments names every argument with `name = value`, in the
  declared parameter order. This applies to functions, methods, and class constructors.
- A bare local whose name equals the parameter at that position counts as named: `Point(x, y)`
  passes the locals `x` and `y`. The compiler checks the names, so `Point(y, x)` is an error.
- A call with one written argument passes it without a name: `print(x)`, `double(2)`, `Box(5)`,
  and `value |> add(2)`. Naming it is an error. The value forwarded by `|>` is not written in the
  argument list, so it does not count.
- Arguments out of parameter order, missing names, and unknown names are compile errors.

### Forward application

```vera
2 |> add(1)
42 |> clamp(low = 0, high = 10)
data |> processor.runtime.process() |> print()
```

- `value |> f(x)` lowers to `f(value, x)`: the forwarded value fills the first parameter without a
  name.
- Only written arguments count for the naming rules, so `2 |> add(1)` is a call with one written
  argument and stays unnamed, while `42 |> clamp(low = 0, high = 10)` names both written arguments.
- `value |> f()` lowers to `f(value)`.
- The right side must be a call: of a function, built-in, class constructor, or sibling object
  method, or a member call chain. A bare name is an error, so `2 |> print` is written
  `2 |> print()`; this keeps every call visibly a call.
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
- `if` without `else` produces `Unit`: a missing `else` is `else { Unit }`. As a statement its branch
  value is discarded, but where its value is used the branch must be `Unit` too, so
  `val x = if c { 1 }` is an error that asks for an `else`.

```vera
val label = if count > 0 {
    "positive"
} else {
    "not positive"
}

val result = if count > 10 {
    count * 2
} else if count > 5 {
    count + 1
} else {
    0
}
```

### `match`

```vera
trait Shape {}
class Circle has Shape {
    val radius: Int
}
class Rect has Shape {
    val width: Int
    val height: Int
}
class Empty has Shape {}

fn area(shape: Shape): Int = match shape {
    Circle(radius) -> 3 * radius * radius
    Rect(width = w, height) -> w * height
    Empty -> 0
}
```

- `match` selects the arm whose class matches the object. The value matched must be a trait
  or a single class (which destructures it).
- Every case must be covered exactly once. There is no catch-all arm, so adding a class to a
  trait makes every `match` that misses it a compile error that names the missing cases.
- A pattern names a class and, optionally, fields to bind: `Rect(width, height)` binds the fields to
  locals of the same names, and `Rect(width = w)` binds `width` to `w`. Fields may be left out. A
  pattern that binds nothing has no parentheses: `Empty`.
- Bindings are local to their arm and follow the no-shadowing rule.
- An arm is `Pattern -> expression`, `Pattern -> { block }`, or `Pattern -> return / break /
  continue`, one arm per line.
- `match` is an expression. Arms must have the same type or share one user trait. A `match` used as a
  statement may have `Unit` arms, and one whose arms all return ends the function like an `if`.
- The matched expression is evaluated once.

### `while`

```vera
mut val counter = 0

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

Allowed, because the outer `if` forms the whole `val` statement:

```vera
while True {
    val next = if ready {
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
    print(add(a = 1, b = if done { break } else { 2 }))
}
```
- `while` is a statement and produces no value.

## Built-in `print`

```vera
print(42)
print("hello")
print(True)
print(Unit)
print(Point(x = 1, y = 2))
```

- Writes one value followed by a line feed (`\n`) to standard output. The line ending is `\n` on
  every platform, so output is the same on Windows and Linux.
- Prints `Int` and `String` values directly, `Bool` values as `True` or `False`, and `Unit` as
  `Unit`.
- Prints an object as `ClassName(field: value, ...)` only when its class has the `Display`
  trait; nested objects print recursively through their own `Display` implementations.
- Returns `Unit`.

## Comments

```vera
/// Returns the total of all paid orders.
fn total(orders: Orders): Int {
    // Refunds are already excluded upstream.
    return orders |> paidOnly() |> sum()
}
```

- `//` starts a comment that runs to the end of the line.
- `///` starts a Markdown doc comment, the same form as Java 23 (JEP 467). Doc comments are
  currently skipped like `//`; attaching them to the following declaration is planned.
- There are no block comments (`/* */`). Comment out several lines with `//` on each line.
- `//` inside a string literal is part of the string.
- A comment never affects line continuation: `a +  // note` still continues, and a trailing
  comment still ends the statement.

## Newlines and blocks

- A newline ends a statement, a class member, or a trait signature. A closing `}` also ends the
  last one, so blocks fit on one line: `if c { 1 } else { 2 }`, `class Point { val x: Int }`.
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
- A comment-only line behaves like a blank line, including between the lines of a continued
  statement.

```vera
val report = orders
    |> paidOnly()
    |> summarize()

val total = subtotal +
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
declaration    = function | class | trait | interface

function       = "fn" IDENT signature ( block | "=" expression )
signature      = "(" [ parameter { "," parameter } [ "," ] ] ")" [ ":" type ]   (* type is not "Unit" *)
parameter      = IDENT ":" type

class          = "class" IDENT [ hasList ] [ implementsList ] "{" { EOL } { member statementEnd } "}"
hasList        = "has" IDENT { "," IDENT }
implementsList = "implements" IDENT { "," IDENT }
member         = "val" IDENT ":" type
               | function

trait          = "trait" IDENT [ requiresList ] "{" { EOL } { traitItem } "}"
requiresList   = "requires" IDENT { "," IDENT }
traitItem      = traitMethod statementEnd | traitField statementEnd | case ( "," | statementEnd )
traitMethod    = "fn" IDENT signature
traitField     = "val" IDENT ":" type
case           = IDENT [ [ hasList ] [ implementsList ] classBody ]   (* a list needs a body *)
classBody      = "{" { EOL } { member statementEnd } "}"

interface      = "interface" IDENT [ requiresList ] "{" { EOL } { interfaceItem } "}"
interfaceItem  = ( traitMethod | traitField ) statementEnd

type           = "Int" | "String" | "Bool" | "Unit" | typeName
```

### Statements

```text
block          = "{" { EOL } { statement statementEnd } "}"
statementEnd   = EOL | EOF | (* nothing, before the closing "}" *)

statement      = [ "mut" ] "val" IDENT [ ":" type ] "=" expression
               | "mut" "val" IDENT                (* no value: makes a parameter rebindable *)
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
chained        = primary { "." IDENT | "(" [ argument { "," argument } [ "," ] ] ")" }
argument       = [ IDENT "=" ] expression      (* names required for 2+ written arguments *)
primary        = INT | STRING | template | "True" | "False" | "Unit" | IDENT
               | "(" expression ")"
               | if
               | match
template       = TEMPLATE_START expression { TEMPLATE_MIDDLE expression } TEMPLATE_END

if             = "if" expression block [ { EOL } "else" ( if | block ) ]
match          = "match" expression "{" { EOL } arm statementEnd { arm statementEnd } "}"
arm            = pattern "->" ( block | expression | "return" [ expression ] | "break" | "continue" )
pattern        = typeName [ "(" fieldPattern { "," fieldPattern } [ "," ] ")" ]
typeName       = IDENT [ "." IDENT ]                (* Owner.Case names a trait case *)
fieldPattern   = IDENT [ "=" IDENT ]
```

- In an `if` block, a final expression statement becomes the block's value.
- `{ EOL }` before `else` is only consumed when `else` follows; otherwise the newlines end the
  enclosing statement.

### Tokens

```text
IDENT           = ( letter | "_" ) { letter | digit | "_" }  (* ASCII; keywords excluded *)
INT             = digit { digit }                             (* must fit in a 32-bit Int *)
STRING          = '"' { character | escape } '"'              (* no line breaks inside *)
escape          = "\n" | "\t" | "\r" | '\"' | "\\" | "\u" HEX HEX HEX HEX   (* HEX: 0-9, a-f, A-F *)
TEMPLATE_START  = '"' { character | escape } "\{"             (* text before the first placeholder *)
TEMPLATE_MIDDLE = "}" { character | escape } "\{"             (* text between placeholders *)
TEMPLATE_END    = "}" { character | escape } '"'              (* text after the last placeholder *)
COMMENT         = "//" { character }                          (* to end of line; includes "///" *)
```

Spaces, tabs, and comments separate tokens and are otherwise ignored. The lexer drops a newline
(emits no `EOL`) inside an open `(`, after a trailing operator, `not`, `=`, `,`, or `.`, and before a
line starting with `|>` or `.`, looking past blank and comment-only lines. A `;` is rejected with a
dedicated error.

## Example

```vera
fn add(a: Int, b: Int): Int {
    return a + b
}

fn main() {
    mut val counter = 0

    while counter < 3 {
        print(counter)
        counter = counter + 1
    }

    if add(a = 1, b = 2) == 3 {
        print("ok")
    }

    print(2 |> add(1))
}
```

## Compiler errors

Errors name the problem in source terms and point at the code that caused it:

```text
error: argument 1 of function add must be named: write `left = ...`
 --> main.vera:5:15
  |
5 |     print(add(1, right = 2))
  |               ^
```

- The underline covers the innermost construct responsible: an expression, an argument, a
  statement, or a declaration header such as `fn add`.
- A span that continues onto later lines is underlined to the end of its first line.
- Unknown names suggest a close match: ``variable cuont is not defined; did you mean `count`?``.
- Common Java and Kotlin habits get a hint saying what to write in Vera instead:

| Java / Kotlin | Hint |
|---------------|------|
| `!done`, `&&`, `\|\|` | use `not`, `and`, `or` |
| `x++`, `x--`, `x += 1` | write `x = x + 1` |
| `a = b = 3` | rebind each variable on its own line |
| `;` | end the statement with a newline |
| `/* ... */` | start each line with `//` |
| `===`, `!==` | use `==` and `!=`, which compare values |
| `!!`, `?.`, `?:`, `null` | Vera has no `null` |
| `c ? a : b` | write `if c { a } else { b }` |
| `&`, `\|`, `^`, `~`, `<<`, `>>` | no bitwise operators |
| `0x10`, `1_000`, `10L`, `1.5` | only decimal `Int` literals |
| `'c'`, `"""..."""` | strings use double quotes and stay on one line |
| `true`, `false` | write `True`, `False` |
| `int`, `boolean`, `void`, `Long`, `Any` as types | use `Int`, `Bool`, `Unit`; there is no universal type |
| `var count = 0`, `int count = 0`, `lateinit var` | declare with `val name: Type = ...` (or `mut val`) |
| `val x: Int` assigned later | give the `val` its value, or choose it with `if` |
| top-level `val`, `const val` | use a function: `fn limit(): Int = 1` |
| `public`, `private`, `static`, `final` | no modifiers; bindings are immutable by default |
| `fun`, `int add(int a)`, `a: Int = 1` | declare `fn add(a: Int): Int`; no default parameter values |
| `if (c) x`, `do`, `when`, `switch`, `break outer` | use braces, `while`, `match`, and plain `break` |
| `for (...)` | use `while` |
| `else ->`, `_ ->`, `is Circle`, `case Circle c` | list every case as `Circle ->` |
| `new Point()` | call the class directly: `Point(...)` |
| `class Point(val x: Int)`, `record`, `data class` | declare fields in the body; every class is a data class |
| `object Config` | a class without fields is a singleton |
| `enum`, `sealed class` | a trait with cases: `trait Color { Red, Green }` |
| `extends`, `class A : B` | no inheritance; list traits with `has` and interfaces with `implements` |
| `sealed trait` | traits are closed by default |
| `override`, `@Override`, `default` | no annotations; a matching method implements the trait |
| `constructor`, `init`, `val x = 0` field | the constructor is generated; fields have no defaults |
| `toString`, `equals`, `hashCode` methods | use `has Display` and `has Eq` |
| `point.x = 3` | objects are immutable; create a new object |
| `this`, `super` | refer to fields and methods by name; no inheritance |
| `"a" + name`, `"a".equals(b)` | use a string template and `==` |
| `println`, `System.out.println` | use `print`, which ends the line |

- Words such as `var`, `new`, `final`, `for`, `fun`, or `when` are not reserved; the hints only appear
  where the code would not compile anyway.

## Further reading

- [Design Philosophy](design-philosophy.md) — long-term direction and compiler principles
- [AGENTS.md](../AGENTS.md) — contributor and coding-agent conventions
