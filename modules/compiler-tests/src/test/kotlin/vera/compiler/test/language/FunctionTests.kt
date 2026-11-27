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
        fun `functions can be called`() = assertPrints(
            """
            fn main() {
                print1()
                print2()
            }
            fn print1() {
                print(1)
            }
            fn print2() {
                print(2)
            }
            """,
            "1",
            "2",
        )

        @Test
        fun `one Int parameter can be passed`() = assertReturns(
            """
            fn main(): Int {
                val numberResult: Int = receiveAndReturn(369)
                return numberResult
            }
            fn receiveAndReturn(int: Int): Int {
                return int
            }
            """,
            369,
        )

        @Test
        fun `multiple Int parameters can be passed`() = assertReturns(
            """
            fn main(): Int {
                return add3Ints(int1 = 99, int2 = 98, int3 = 2 * 48 + 1)
            }
            fn add3Ints(int1: Int, int2: Int, int3: Int): Int {
                return int1 + int2 + int3
            }
            """,
            294,
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
                1 |> add(2) |> print
                42 |> clamp(low = 0, high = 10) |> print
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
        fun `Int can be returned`() = assertReturns(
            """
            fn main(): Int {
                val numberResult: Int = getInt() + 0
                return numberResult
            }
            fn getInt(): Int {
                return 698
            }
            """,
            698,
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
            fn total(first: Int, second: Int): Int =
                first + second
            fn log(text: String) = print(text)
            fn main() {
                print(Square(3).area())
                print(4 |> double)
                print(greet("Vera"))
                print(sign(-7))
                print(total(first = 2, second = 3))
                log("done")
            }
            """,
            "9",
            "8",
            "Hello, Vera!",
            "-1",
            "5",
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
