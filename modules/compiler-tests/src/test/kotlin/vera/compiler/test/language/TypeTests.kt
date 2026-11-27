package vera.compiler.test.language

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import vera.compiler.test.assertCompilationFails
import vera.compiler.test.assertPrints
import vera.compiler.test.assertReturns
import vera.compiler.test.function
import vera.parser.VeraParseException

/** The built-in types `Int`, `Bool`, and `Unit`. */
class TypeTests {
    @Nested
    inner class Int {
        @Test
        fun `the smallest Int literal can be written`() = assertPrints(
            """
            fn main() {
                print(-2147483648)
                print(-2147483648 + 1)
                print(-(5))
            }
            """,
            "-2147483648",
            "-2147483647",
            "-5",
        )

        @Test
        fun `rejects out-of-range Int literals`() = assertCompilationFails(
            function("print(999999999999999999999)"),
            VeraParseException.InvalidIntLiteral::class,
            "invalid Int literal",
        )
    }

    @Nested
    inner class Bool {
        @Test
        fun `Bools can be declared and bound to True and False`() = assertPrints(
            """
            fn main() {
                val trueValue = True
                val falseValue = False
                print(trueValue)
                print(falseValue)
            }
            """,
            "True",
            "False",
        )
    }

    @Nested
    inner class Unit {
        @Test
        fun `Unit values can be stored in objects and read back`() = assertReturns(
            """
            class Signal {
                val flag: Unit
            }
            fn main(): Int {
                val signal = Signal(Unit)
                val flag: Unit = signal.flag
                return 1
            }
            """,
            1,
        )

        @Test
        fun `Unit values can be passed and returned`() = assertReturns(
            """
            fn identity(unit: Unit) {
                return unit
            }
            fn main(): Int {
                val unit = identity(Unit)
                return 1
            }
            """,
            1,
        )

        @Test
        fun `Unit can be returned explicitly`() = assertReturns(
            """
            fn explicitUnit() {
                return Unit
            }
            fn main(): Int {
                val unit = explicitUnit()
                return 1
            }
            """,
            1,
        )

        @Test
        fun `Unit equality always holds`() = assertPrints(
            """
            fn main() {
                print(Unit == Unit)
                print(Unit != Unit)
            }
            """,
            "True",
            "False",
        )

        @Test
        fun `Unit equality evaluates both operands in order`() = assertPrints(
            """
            fn first() {
                print("first")
            }
            fn second() {
                print("second")
            }
            fn main() {
                print(first() == second())
                print(first() != second())
            }
            """,
            "first",
            "second",
            "True",
            "first",
            "second",
            "False",
        )

        @Test
        fun `Unit can be printed`() = assertPrints(
            """
            fn main() {
                print(Unit)
            }
            """,
            "Unit",
        )
    }
}
