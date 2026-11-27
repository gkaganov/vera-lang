package vera.compiler.test.language

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import vera.compiler.test.assertCompilationFails
import vera.compiler.test.assertPrints
import vera.compiler.test.function
import vera.jvm.lowering.JvmLoweringException
import vera.parser.VeraParseException

/** The built-in types `Int`, `Bool`, and `None`. */
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
    inner class None {
        @Test
        fun `None values can be stored in objects and read back`() = assertPrints(
            """
            class Signal {
                val flag: None
            }
            fn main() {
                val signal = Signal(None)
                val flag: None = signal.flag
                print(flag)
            }
            """,
            "None",
        )

        @Test
        fun `None values can be passed and returned`() = assertPrints(
            """
            fn identity(nothing: None) {
                return nothing
            }
            fn explicitNone() {
                return None
            }
            fn main() {
                print(identity(None))
                print(explicitNone())
            }
            """,
            "None",
            "None",
        )

        @Test
        fun `None equality always holds`() = assertPrints(
            """
            fn main() {
                print(None == None)
                print(None != None)
            }
            """,
            "True",
            "False",
        )

        @Test
        fun `None equality evaluates both operands in order`() = assertPrints(
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
        fun `None can be printed`() = assertPrints(
            """
            fn main() {
                print(None)
            }
            """,
            "None",
        )

        @Test
        fun `rejects constructing None`() = assertCompilationFails(
            function("val nothing = None()"),
            JvmLoweringException.SingletonConstructed::class,
            "None has no fields, so it has a single value; write `None` instead of `None()`",
        )
    }
}
