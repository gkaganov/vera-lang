package vera.compiler.test.language

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import vera.compiler.test.assertCompilationFails
import vera.compiler.test.assertPrints
import vera.compiler.test.function
import vera.jvm.lowering.JvmLoweringException

/** The built-in `print`. */
class PrintTests {
    @Nested
    inner class Values {
        @Test
        fun `print function prints to system-out`() = assertPrints(
            """
            fn main() {
                print(65)
            }
            """,
            "65",
        )

        @Test
        fun `print evaluates arithmetic arguments`() = assertPrints(
            """
            fn main() {
                print(1 + 2 * 3)
            }
            """,
            "7",
        )

        @Test
        fun `a String can be printed`() = assertPrints(
            """
            fn main() {
                print("Hello World!")
            }
            """,
            "Hello World!",
        )

        @Test
        fun `Unit-returning calls can be printed`() = assertPrints(
            """
            fn doNothing() {
            }
            fn main() {
                print(doNothing())
            }
            """,
            "Unit",
        )

        @Test
        fun `builtin calls with the wrong arity`() = assertCompilationFails(
            function("print(1, 2)"),
            JvmLoweringException.BuiltinArityMismatch::class,
            "print requires exactly 1 argument",
        )
    }
}
