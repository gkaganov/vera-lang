package vera.compiler.test.language

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import vera.compiler.test.assertCompilationFails
import vera.compiler.test.assertPrints
import vera.compiler.test.assertReturns
import vera.compiler.test.function
import vera.jvm.lowering.JvmLoweringException

/** Forward application with `|>`. */
class ForwardApplicationTests {
    @Nested
    inner class Basics {
        @Test
        fun `forward application passes the left value as the first argument`() = assertPrints(
            """
            fn main() {
                print(2 |> double)
                print(1 |> add(2))
            }
            fn double(value: Int): Int {
                return value * 2
            }
            fn add(left: Int, right: Int): Int {
                return left + right
            }
            """,
            "4",
            "3",
        )

        @Test
        fun `forward application works without surrounding spaces`() = assertPrints(
            """
            fn main() {
                print(2|>double)
            }
            fn double(value: Int): Int {
                return value * 2
            }
            """,
            "4",
        )

        @Test
        fun `forward application chains left to right`() = assertPrints(
            """
            fn main() {
                print(3 |> add(4) |> double)
            }
            fn double(value: Int): Int {
                return value * 2
            }
            fn add(left: Int, right: Int): Int {
                return left + right
            }
            """,
            "14",
        )

        @Test
        fun `forward application accepts object values`() = assertReturns(
            """
            class Point {
                val x: Int
                val y: Int
            }
            fn sum(point: Point): Int {
                return point.x + point.y
            }
            fn main(): Int {
                return Point(x = 1, y = 2) |> sum
            }
            """,
            3,
        )

        @Test
        fun `forward application works inside arguments`() = assertReturns(
            """
            fn main(): Int {
                return 1 |> add(2 |> double)
            }
            fn double(value: Int): Int {
                return value * 2
            }
            fn add(left: Int, right: Int): Int {
                return left + right
            }
            """,
            5,
        )
    }

    @Nested
    inner class Precedence {
        @Test
        fun `forward application binds looser than arithmetic`() = assertPrints(
            """
            fn main() {
                print(1 + 2 |> double)
            }
            fn double(value: Int): Int {
                return value * 2
            }
            """,
            "6",
        )

        @Test
        fun `forward application binds looser than or`() = assertPrints(
            """
            fn main() {
                print(True or False |> negate)
            }
            fn negate(value: Bool): Bool {
                return if value {
                    False
                } else {
                    True
                }
            }
            """,
            "False",
        )

        @Test
        fun `forward application binds looser than and`() = assertPrints(
            """
            fn main() {
                print(False and True |> negate)
            }
            fn negate(value: Bool): Bool {
                return if value {
                    False
                } else {
                    True
                }
            }
            """,
            "True",
        )

        @Test
        fun `forward application binds looser than equality`() = assertPrints(
            """
            fn main() {
                print(1 == 1 |> identityBool)
            }
            fn identityBool(value: Bool): Bool {
                return value
            }
            """,
            "True",
        )

        @Test
        fun `forward application groups an or-expression on the right`() = assertCompilationFails(
            function("print(1 |> f or True)"),
            JvmLoweringException.InvalidForwardApplication::class,
            "right-hand side of '|>' must be a callable name or member call",
        )
    }

    @Nested
    inner class Targets {
        @Test
        fun `forward application works with builtins`() = assertPrints(
            """
            fn main() {
                2 |> print
            }
            """,
            "2",
        )

        @Test
        fun `forward application calls sibling class methods`() = assertReturns(
            """
            class Counter {
                val value: Int

                fn add(amount: Int): Int {
                    return value + amount
                }

                fn viaForward(amount: Int): Int {
                    return amount |> add
                }
            }
            fn main(): Int {
                return Counter(10).viaForward(5)
            }
            """,
            15,
        )

        @Test
        fun `forward application accepts class constructors on the right`() = assertReturns(
            """
            class Point {
                val x: Int
                val y: Int
            }
            fn main(): Int {
                val point = 2 |> Point(1)
                return point.x * 10 + point.y
            }
            """,
            21,
        )

        @Test
        fun `forward application to a non-callable value`() = assertCompilationFails(
            function("print(1 |> 2)"),
            JvmLoweringException.InvalidForwardApplication::class,
            "right-hand side of '|>' must be a callable name or member call",
        )

        @Test
        fun `forward application to a member access without a call`() = assertCompilationFails(
            function("print(1 |> value.name)"),
            JvmLoweringException.InvalidForwardApplication::class,
            "right-hand side of '|>' must be a callable name or member call",
        )

        @Test
        fun `forward application calling a call result`() = assertCompilationFails(
            function("print(1 |> f()())"),
            JvmLoweringException.InvalidForwardApplication::class,
            "right-hand side of '|>' cannot call the result of another call",
        )
    }

    @Nested
    inner class MemberChains {
        @Test
        fun `forward application accepts field access on the left`() = assertReturns(
            """
            class Point {
                val x: Int
                val y: Int
            }
            fn double(value: Int): Int {
                return value * 2
            }
            fn main(): Int {
                return Point(x = 3, y = 4).x |> double
            }
            """,
            6,
        )

        @Test
        fun `forward application calls methods on member chains`() = assertReturns(
            """
            class Runtime {
                val base: Int

                fn process(input: Int): Int {
                    return input + base
                }
            }
            class Processor {
                val runtime: Runtime
            }
            fn main(): Int {
                val processor = Processor(Runtime(10))
                return 5 |> processor.runtime.process()
            }
            """,
            15,
        )

        @Test
        fun `forward application passes the left value before explicit method arguments`() = assertReturns(
            """
            class Calculator {
                val offset: Int

                fn add(value: Int, extra: Int): Int {
                    return value + extra + offset
                }
            }
            fn main(): Int {
                val calculator = Calculator(100)
                return 1 |> calculator.add(20)
            }
            """,
            121,
        )

        @Test
        fun `forward application chains member calls`() = assertPrints(
            """
            class Runtime {
                val base: Int

                fn process(input: Int): Int {
                    return input + base
                }
            }
            class Processor {
                val runtime: Runtime
            }
            fn main() {
                val processor = Processor(Runtime(10))
                1 |> processor.runtime.process() |> print()
            }
            """,
            "11",
        )

        @Test
        fun `forward application evaluates the left value before a member chain`() = assertPrints(
            """
            class Box {
                val base: Int
                fn plus(a: Int, b: Int): Int {
                    return a + b + base
                }
            }
            fn left(): Int {
                print("left")
                return 1
            }
            fn box(): Box {
                print("box")
                return Box(10)
            }
            fn two(): Int {
                print("two")
                return 2
            }
            fn main() {
                print(left() |> box().plus(two()))
            }
            """,
            "left",
            "box",
            "two",
            "13",
        )
    }
}
