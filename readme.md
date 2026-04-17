# Vera - an Experimental JVM Language
## core features

- names may be rebound
- structures may not be mutated
- methods on structures return new values instead of changing old ones
- assignment is the only way for a scope’s state to move forward
- explicit impurity -> `impure` block/definition
- no inheritance
- traits for polymorphism (can define fields and functions)
- aliases can group traits -> pure sugar
- global immutability, local mutability
  - no setters, no mutable collections
  - `var` and parameter reassignment
- first-class functions
- new structure called `unit` - "immutable class"

```
var newOrder = order { customer.address.street = "X" }
var newAddress = customer.address { street.name = "some street" }
```
```
customer.address { street.name = "some street" }
```
it should be obvious that street means address.street,
not some local variable named street -> no shadowing!!

```
order {
  customer.name = "Bob"
  customer.address.street = "X"
}
```
this is an atomic declarative operation and any conflicts are a compile time error

no nulls

# vera feature examples

Small, realistic examples showing how vera models data and state changes.

---

## Explicit state changes with `:=`

```vera
fn main() {
  var customer = Customer(
    "c-100",
    "Ada Lovelace",
    "ada@example.com",
    Address("Old Street 12", "London", "EC1", "UK"),
    false
  )

  customer := {
    address.city = "Berlin"
    address.postalCode = "10115"
  }

  print(customer.address.city)
  # > "Berlin"
}
```

- values do not change by accident
- `:=` makes the state change visible
- the reassignment block returns a new `Customer`

---

## Updating deeply nested data

```vera
fn markOrderForGermanShipping(order: Order): Order {
  return order {
    customer.address.country = "Germany"
    customer.address.city = "Berlin"
    status = "address-updated"
  }
}
```

- nested updates stay concise
- the original `order` stays unchanged
- vera makes immutable updates practical for real object graphs

---

## Updating only the part you care about

```vera
fn normalizeAddress(customer: Customer): Customer {
  val address = customer.address {
    city = "Berlin"
    country = "Germany"
  }

  return customer {
    address = address
  }
}
```

- the update root is the expression before the braces
- you can copy only a nested value instead of rebuilding everything
- useful when you want precise control over what gets replaced

---

## Methods are just functions associated with a type

```vera
public unit Customer(
  id: String,
  name: String,
  email: String,
  address: Address,
  isVip: Bool
) {

  fn shippingLabel(this): String {
    return this.name + ", " +
           this.address.street + ", " +
           this.address.postalCode + " " +
           this.address.city + ", " +
           this.address.country
  }

  fn promoteToVip(this): Customer {
    return this {
      isVip = true
    }
  }
}
```

- `this` is a normal parameter
- methods still return new values instead of mutating state
- domain logic can live next to the data it works on

---

## Dot syntax is just call sugar

```vera
val label1 = customer.shippingLabel()
val label2 = shippingLabel(customer)
```

- both forms mean the same thing
- the real model is still plain functions
- dot syntax just makes method-style code nicer to read

---

## The instance parameter can be unused

```vera
public unit Order(
  id: String,
  customer: Customer,
  lines: Array<OrderLine>,
  status: String,
  internalNote: String
) {

  fn defaultStatus(_): String {
    return "draft"
  }
}
```

- `_` means the parameter exists but is not used
- useful for keeping method form without fake names

---

## Parameters are local values too

```vera
fn ensureVip(customer: Customer): Customer {
  var customer = customer

  if !customer.isVip {
    customer := {
      isVip = true
    }
  }

  return customer
}
```

- parameters can be rebound like other locals
- this works well with immutable workflows
- local rebinding stays explicit

---

## `val` and `var`

```vera
fn main() {
  val orderId = "o-200"
  var status = "draft"

  status := "submitted"

  print(orderId)
  print(status)
}
```

- `val` cannot be rebound
- `var` can be rebound
- rebinding a local is not object mutation

---

## Pure by default

```vera
fn lineTotal(line: OrderLine): Int {
  return line.quantity * line.unitPriceCents
}

fn isLargeOrder(order: Order): Bool {
  return order.lines.size > 10
}
```

- ordinary business logic is pure by default
- pure functions are easier to test and reason about
- this fits calculations, validation, formatting, and mapping

---

## Explicit impurity at the edges

```vera
impure fn main() {
  val order = loadOrder("o-200")
  print(order.status)
}
```

- side effects must be marked with `impure`
- I/O stays visible in the signature
- pure code cannot accidentally become effectful

---

## Pure code cannot call impure code

```vera
impure fn loadCustomer(id: String): Customer

fn welcomeMessage(id: String): String {
  val customer = loadCustomer(id)
  # ^ compile error

  return "welcome"
}
```

- impure functions can only be called from impure code
- side effects stay contained
- this keeps the safe default actually safe

---

## Compiler-known facts can remove impossible error handling

```vera
fn secondLine(order: Order) {
  if order.lines.size > 1 {
    val line = order.lines.get(1)

    # the compiler can see that index 1 is valid here
    # so required error handling can be reduced or removed
  }
}
```

- vera can track facts about values
- proven facts can simplify safe APIs
- goal: less boilerplate without giving up correctness

---

## Pipeline style for business flows

```vera
val finalOrder =
  order
  |> validateOrder()
  |> applyVipDiscount()
  |> addAuditNote()
```

- pipeline style keeps the data flow left to right
- useful for service logic and transformations
- works especially well with immutable values

---

## Pipeline and dot syntax can be combined

```vera
val label =
  order
  |> applyVipDiscount()
  |> it.customer.shippingLabel()
```

- free functions and dot calls can work together
- this keeps APIs flexible
- still reads as a straight data flow

---

## A realistic workflow

```vera
impure fn main() {
  var order = loadOrder("o-200")

  order := {
    customer.address.city = "Berlin"
    customer.address.country = "Germany"
    internalNote = "address corrected by support"
  }

  order = order
    |> applyVipDiscount()
    |> markAsReviewed()

  print(order.customer.shippingLabel())
}
```

- load data at the impure edge
- transform it with explicit immutable updates
- keep the final workflow readable