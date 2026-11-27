package vera.compiler.test.language

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import vera.compiler.test.assertCompilationFails
import vera.compiler.test.assertPrints
import vera.compiler.test.assertReturns
import vera.compiler.test.classDeclaration
import vera.compiler.test.function
import vera.jvm.lowering.JvmLoweringException
import vera.parser.VeraParseException

/** Functions: calls, named arguments, returns, and expression bodies. */
class FunctionTests {
    @Nested
    inner class Calls {
        @Test
        fun `functions can be called before their declaration`() = assertPrints(
            """
            fn main() {
                greet()
                farewell()
            }
            fn greet() {
                print(1)
            }
            fn farewell() {
                print(2)
            }
            """,
            "1",
            "2",
        )

        @Test
        fun `functions take and return every type`() = assertPrints(
            """
            trait Shape {
                fn area(): Int
            }
            class Square has Shape, Display {
                val side: Int
                fn area(): Int = side * side
            }
            fn sum(first: Int, second: Int, third: Int): Int = first + second + third
            fn shout(text: String): String = "\{text}!"
            fn flip(flag: Bool): Bool = not flag
            fn pass(unit: Unit) = unit
            fn grow(square: Square): Square = Square(square.side + 1)
            fn measure(shape: Shape): Shape = shape
            fn main() {
                print(sum(first = 99, second = 98, third = 2 * 48 + 1))
                print(shout("hi"))
                print(flip(True))
                print(pass(Unit))
                print(grow(Square(2)))
                print(measure(Square(3)).area())
            }
            """,
            "294",
            "hi!",
            "False",
            "Unit",
            "Square(side: 3)",
            "9",
        )

        @Test
        fun `functions can call themselves recursively`() = assertPrints(
            """
            fn factorial(n: Int): Int = if n <= 1 { 1 } else { n * factorial(n - 1) }
            fn main() {
                print(factorial(5))
            }
            """,
            "120",
        )

        @Test
        fun `functions can call each other recursively`() = assertPrints(
            """
            fn isEven(n: Int): Bool = if n == 0 { True } else { isOdd(n - 1) }
            fn isOdd(n: Int): Bool = if n == 0 { False } else { isEven(n - 1) }
            fn main() {
                print(isEven(10))
                print(isOdd(7))
            }
            """,
            "True",
            "True",
        )

        @Test
        fun `rejects functions declared inside functions`() = assertCompilationFails(
            function("fn inner() {", "}"),
            VeraParseException.NestedFunction::class,
            "functions can only be declared at top level",
        )

        @Test
        fun `rejects calls to unknown functions`() = assertCompilationFails(
            function("missing()"),
            JvmLoweringException.UnknownFunction::class,
            "function missing is not defined",
        )

        @Test
        fun `rejects function calls with the wrong arity`() = assertCompilationFails(
            "fn add(left: Int, right: Int): Int {\n    return left + right\n}\n" +
                "fn main(): Int {\n    return add(1)\n}",
            JvmLoweringException.FunctionArityMismatch::class,
            "function add requires 2 arguments, got 1",
        )

        @Test
        fun `rejects function arguments with the wrong type`() = assertCompilationFails(
            "fn add(left: Int, right: Int): Int {\n    return left + right\n}\n" +
                "fn main(): Int {\n    return add(left = 1, right = \"wrong\")\n}",
            JvmLoweringException.FunctionArgumentTypeMismatch::class,
            "function add parameter right requires Int, got String",
        )
    }

    @Nested
    inner class NamedArguments {
        @Test
        fun `named arguments work for functions, constructors, and methods`() = assertPrints(
            """
            class Rect {
                val width: Int
                val height: Int
                fn scaled(factor: Int, extra: Int): Int {
                    return width * height * factor + extra
                }
            }
            fn area(width: Int, height: Int): Int {
                return width * height
            }
            fn main() {
                print(area(width = 3, height = 4))
                val rect = Rect(width = 2, height = 5)
                print(rect.scaled(factor = 2, extra = 1))
                print(Rect(
                    width = 1,
                    height = 1
                ).width)
            }
            """,
            "12",
            "21",
            "1",
        )

        @Test
        fun `parameter, argument, and pattern lists allow a trailing comma`() = assertPrints(
            """
            trait Shape {
                Rect {
                    val width: Int
                    val height: Int
                }
            }
            fn area(
                width: Int,
                height: Int,
            ): Int = width * height
            fn describe(shape: Shape): Int = match shape {
                Rect(width, height,) -> width + height
            }
            fn main() {
                print(area(width = 2, height = 3,))
                val rect = Shape.Rect(
                    width = 4,
                    height = 5,
                )
                print(describe(rect))
                print(1 |> area(7,))
            }
            """,
            "6",
            "9",
            "7",
        )

        @Test
        fun `bare locals with matching names count as named arguments`() = assertReturns(
            """
            class Point {
                val x: Int
                val y: Int
            }
            fn main(): Int {
                val x = 3
                val y = 4
                val point = Point(x, y)
                val mixed = Point(x, y = 10)
                return point.x + point.y + mixed.y
            }
            """,
            17,
        )

        @Test
        fun `only written arguments count when naming forwarded calls`() = assertPrints(
            """
            fn add(left: Int, right: Int): Int {
                return left + right
            }
            fn clamp(value: Int, low: Int, high: Int): Int {
                return if value < low { low } else if value > high { high } else { value }
            }
            fn main() {
                1 |> add(2) |> print()
                42 |> clamp(low = 0, high = 10) |> print()
            }
            """,
            "3",
            "10",
        )

        @Test
        fun `rejects unnamed arguments in multi-argument calls`() = assertCompilationFails(
            "fn add(left: Int, right: Int): Int {\n    return left + right\n}\n" +
                function("print(add(1, 2))"),
            JvmLoweringException.ArgumentNameRequired::class,
            "argument 1 of function add must be named: write `left = ...`",
        )

        @Test
        fun `rejects named arguments out of parameter order`() = assertCompilationFails(
            "fn add(left: Int, right: Int): Int {\n    return left + right\n}\n" +
                function("print(add(right = 2, left = 1))"),
            JvmLoweringException.ArgumentNameMismatch::class,
            "argument 1 of function add is named right, but parameter 1 is left; " +
                "arguments must follow the parameter order",
        )

        @Test
        fun `rejects misspelled argument names`() = assertCompilationFails(
            "fn add(left: Int, right: Int): Int = left + right\n" + function("print(add(left = 1, rigth = 2))"),
            JvmLoweringException.UnknownArgumentName::class,
            "function add has no parameter named rigth; did you mean `right`?",
        )

        @Test
        fun `rejects argument names that match no parameter`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int", "val y: Int") + function("print(Point(x = 1, depth = 2).x)"),
            JvmLoweringException.UnknownArgumentName::class,
            "class Point has no parameter named depth",
        )

        @Test
        fun `rejects shorthand arguments out of parameter order`() = assertCompilationFails(
            "fn add(left: Int, right: Int): Int {\n    return left + right\n}\n" +
                function("val left = 1", "val right = 2", "print(add(right, left))"),
            JvmLoweringException.ArgumentNameMismatch::class,
            "argument 1 of function add is named right, but parameter 1 is left",
        )

        @Test
        fun `rejects shorthand arguments that match no parameter`() = assertCompilationFails(
            "fn add(left: Int, right: Int): Int {\n    return left + right\n}\n" +
                function("val first = 1", "print(add(first, right = 2))"),
            JvmLoweringException.ArgumentNameRequired::class,
            "argument 1 of function add must be named: write `left = ...`",
        )

        @Test
        fun `rejects unnamed constructor arguments`() = assertCompilationFails(
            classDeclaration("Point", "val x: Int", "val y: Int") + function("print(Point(1, 2).x)"),
            JvmLoweringException.ArgumentNameRequired::class,
            "argument 1 of class Point must be named: write `x = ...`",
        )

        @Test
        fun `rejects unnamed method arguments`() = assertCompilationFails(
            classDeclaration("Box", "val base: Int", "fn plus(a: Int, b: Int): Int {", "    return a + b", "}") +
                function("print(Box(1).plus(a = 1, 2))"),
            JvmLoweringException.ArgumentNameRequired::class,
            "argument 2 of method Box.plus must be named: write `b = ...`",
        )

        @Test
        fun `rejects a named single written argument after a forwarded value`() = assertCompilationFails(
            "fn add(left: Int, right: Int): Int {\n    return left + right\n}\n" +
                function("print(1 |> add(right = 2))"),
            JvmLoweringException.SingleArgumentNamed::class,
            "a call to function add with one written argument passes it without a name; remove `right =`",
        )

        @Test
        fun `rejects unnamed written arguments after a forwarded value`() = assertCompilationFails(
            "fn clamp(value: Int, low: Int, high: Int): Int {\n    return value\n}\n" +
                function("print(42 |> clamp(0, high = 10))"),
            JvmLoweringException.ArgumentNameRequired::class,
            "argument 2 of function clamp must be named: write `low = ...`",
        )

        @Test
        fun `rejects named single arguments`() = assertCompilationFails(
            "fn double(value: Int): Int {\n    return value * 2\n}\n" + function("print(double(value = 2))"),
            JvmLoweringException.SingleArgumentNamed::class,
            "a call to function double with one written argument passes it without a name; remove `value =`",
        )

        @Test
        fun `rejects named print arguments`() = assertCompilationFails(
            function("print(value = 1)"),
            JvmLoweringException.SingleArgumentNamed::class,
            "a call to print with one written argument passes it without a name",
        )
    }

    @Nested
    inner class Returns {
        @Test
        fun `return without a value ends a Unit function`() = assertPrints(
            """
            fn report(ready: Bool) {
                if not ready {
                    return
                }
                print("ready")
            }
            fn main() {
                report(False)
                report(True)
            }
            """,
            "ready",
        )

        @Test
        fun `rejects an if without else as the only return`() = assertCompilationFails(
            "fn sign(value: Int): Int {\n    if value > 0 {\n        return 1\n    }\n}",
            JvmLoweringException.MissingReturnStatement::class,
            "missing return statement for type Int",
        )

        @Test
        fun `returning from both if branches ends a function`() = assertPrints(
            """
            fn sign(value: Int): Int {
                if value > 0 {
                    return 1
                } else if value < 0 {
                    return -1
                } else {
                    return 0
                }
            }
            fn main() {
                print(sign(5))
                print(sign(-5))
                print(sign(0))
            }
            """,
            "1",
            "-1",
            "0",
        )

        @Test
        fun `rejects explicit Unit return types`() = assertCompilationFails(
            "fn log(text: String): Unit {\n}",
            VeraParseException.ExplicitUnitReturnType::class,
            "leave out the return type for a function that returns `Unit`; remove `: Unit`",
        )

        @Test
        fun `rejects returns without a value in non-Unit functions`() = assertCompilationFails(
            "fn main(): Int {\n    return\n}",
            JvmLoweringException.MissingReturnValue::class,
            "return without an expression requires a Unit function",
        )

        @Test
        fun `rejects function return values with the wrong type`() = assertCompilationFails(
            "fn main(): Int {\n    return \"wrong\"\n}",
            JvmLoweringException.ReturnTypeMismatch::class,
            "return requires Int, got String",
        )

        @Test
        fun `rejects Unit functions returning a value`() = assertCompilationFails(
            "fn main() {\n    return 1\n}",
            JvmLoweringException.ReturnTypeMismatch::class,
            "return requires Unit, got Int",
        )

        @Test
        fun `rejects non-Unit functions without a return statement`() = assertCompilationFails(
            "fn main(): Int {\n}",
            JvmLoweringException.MissingReturnStatement::class,
            "missing return statement for type Int",
        )

        @Test
        fun `rejects block bodies that end in a value without return`() = assertCompilationFails(
            "fn add(a: Int): Int {\n    a\n}",
            JvmLoweringException.MissingReturnStatement::class,
            "missing return statement for type Int; a block body needs `return`: write `return ...`, or use an " +
                "expression body `= ...`",
        )

        @Test
        fun `rejects functions that return only inside while`() = assertCompilationFails(
            "fn first(): Int {\n    while True {\n        return 1\n    }\n}",
            JvmLoweringException.MissingReturnStatement::class,
            "a `return` inside `while` does not count; add a `return` after the loop",
        )
    }

    @Nested
    inner class ExpressionBodies {
        @Test
        fun `functions and methods can have expression bodies`() = assertPrints(
            """
            trait Shape {
                fn area(): Int
            }
            class Square has Shape {
                val side: Int
                fn area(): Int = side * side
            }
            fn double(value: Int): Int = value * 2
            fn greet(name: String): String = "Hello, \{name}!"
            fn sign(value: Int): Int = if value > 0 { 1 } else if value < 0 { -1 } else { 0 }
            fn main() {
                print(Square(3).area())
                print(4 |> double())
                print(greet("Vera"))
                print(sign(-7))
            }
            """,
            "9",
            "8",
            "Hello, Vera!",
            "-1",
        )

        @Test
        fun `an expression body can start on the next line`() = assertPrints(
            """
            fn total(first: Int, second: Int): Int =
                first + second
            fn main() = print(total(first = 2, second = 3))
            """,
            "5",
        )

        @Test
        fun `Unit functions can have expression bodies`() = assertPrints(
            """
            fn log(text: String) = print(text)
            fn main() = log("done")
            """,
            "done",
        )

        @Test
        fun `rejects expression bodies without a return type`() = assertCompilationFails(
            "fn double(value: Int) = value * 2",
            JvmLoweringException.ExpressionBodyReturnTypeRequired::class,
            "function double returns Int, so its return type must be written: `fn double(...): Int = ...`",
        )

        @Test
        fun `rejects expression bodies of the wrong type`() = assertCompilationFails(
            "fn double(value: Int): String = value * 2",
            JvmLoweringException.ReturnTypeMismatch::class,
            "return requires String, got Int",
        )
    }
}
