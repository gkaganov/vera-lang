package vera.compiler.test.language

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import vera.compiler.test.assertCompilationFails
import vera.compiler.test.assertPrints
import vera.compiler.test.function
import vera.compiler.test.runMain
import vera.jvm.lowering.JvmLoweringException

/** The built-in `print`. */
class PrintTests {
    @Nested
    inner class Values {
        @Test
        fun `print ends every line with a line feed on every platform`() {
            val (_, output) = runMain(
                """
                fn main() {
                    print(1)
                    print("a\nb")
                }
                """,
            )
            Assertions.assertEquals("1\na\nb\n", output)
        }

        @Test
        fun `print writes each value on its own line`() = assertPrints(
            """
            class Point has Display {
                val x: Int
            }
            fn doNothing() {
            }
            fn main() {
                print(65)
                print(1 + 2 * 3)
                print("Hello World!")
                print(True)
                print(Unit)
                print(doNothing())
                print(Point(1))
            }
            """,
            "65",
            "7",
            "Hello World!",
            "True",
            "Unit",
            "Unit",
            "Point(x: 1)",
        )

        @Test
        fun `print returns Unit`() = assertPrints(
            """
            fn main() {
                print(print(1))
            }
            """,
            "1",
            "Unit",
        )

        @Test
        fun `rejects builtin calls with the wrong arity`() = assertCompilationFails(
            function("print(1, 2)"),
            JvmLoweringException.BuiltinArityMismatch::class,
            "print requires exactly 1 argument, got 2",
        )

        @Test
        fun `rejects print without an argument`() = assertCompilationFails(
            function("print()"),
            JvmLoweringException.BuiltinArityMismatch::class,
            "print requires exactly 1 argument, got 0",
        )
    }
}
