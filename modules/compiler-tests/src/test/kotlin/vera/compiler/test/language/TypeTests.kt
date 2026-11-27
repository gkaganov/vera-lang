package vera.compiler.test.language

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import vera.compiler.test.assertCompilationFails
import vera.compiler.test.assertPrints
import vera.compiler.test.function
import vera.jvm.lowering.JvmLoweringException
import vera.parser.VeraParseException

/** The built-in types `Int`, `Bool`, and `Unit`. */
class TypeTests {
    @Nested
    inner class Int {
        @Test
        fun `the smallest and largest Int literals can be written`() = assertPrints(
            """
            fn main() {
                print(-2147483648)
                print(-2147483648 + 1)
                print(2147483647)
            }
            """,
            "-2147483648",
            "-2147483647",
            "2147483647",
        )

        @Test
        fun `rejects the first Int literal above the range`() = assertCompilationFails(
            function("print(2147483648)"),
            VeraParseException.InvalidIntLiteral::class,
            "Int literal 2147483648 is out of range; `Int` is 32-bit (-2147483648 to 2147483647)",
        )

        @Test
        fun `rejects the first Int literal below the range`() = assertCompilationFails(
            function("print(-2147483649)"),
            VeraParseException.InvalidIntLiteral::class,
            "Int literal -2147483649 is out of range",
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
        fun `Unit values can be stored in objects and read back`() = assertPrints(
            """
            class Signal {
                val flag: Unit
            }
            fn main() {
                val signal = Signal(Unit)
                val flag: Unit = signal.flag
                print(flag)
            }
            """,
            "Unit",
        )

        @Test
        fun `Unit values can be passed and returned`() = assertPrints(
            """
            fn identity(unit: Unit) {
                return unit
            }
            fn explicitUnit() {
                return Unit
            }
            fn main() {
                print(identity(Unit))
                print(explicitUnit())
            }
            """,
            "Unit",
            "Unit",
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

        @Test
        fun `rejects constructing Unit`() = assertCompilationFails(
            function("val unit = Unit()"),
            JvmLoweringException.SingletonConstructed::class,
            "Unit has no fields, so it has a single value; write `Unit` instead of `Unit()`",
        )
    }
}
