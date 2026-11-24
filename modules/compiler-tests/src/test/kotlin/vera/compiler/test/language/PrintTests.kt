package vera.compiler.test.language

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import vera.compiler.VeraCompiler
import vera.compiler.test.ByteArrayClassLoader
import vera.compiler.test.EOL
import vera.compiler.test.TEST_MAIN
import vera.compiler.test.assertCompilationFails
import vera.compiler.test.assertCompilerError
import vera.compiler.test.assertJvmMainPrints
import vera.compiler.test.assertProgramPrints
import vera.compiler.test.assertProgramReturns
import vera.compiler.test.classDeclaration
import vera.compiler.test.function
import vera.compiler.test.traitDeclaration
import vera.jvm.lowering.JvmLoweringException
import vera.parser.VeraLexException
import vera.parser.VeraParseException
import vera.shared.model.Identifier

/** The built-in `print`. */
class PrintTests {
    @Nested
    inner class Values {
        @Test
        fun `print function prints to system-out`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(65)
                        }
                    """.trimIndent()
            assertProgramPrints(source, "65$EOL", testInfo)
        }

        @Test
        fun `print evaluates arithmetic arguments`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(1 + 2 * 3)
                        }
                    """.trimIndent()
            assertProgramPrints(source, "7$EOL", testInfo)
        }

        @Test
        fun `a String can be printed`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print("Hello World!")
                        }
                    """.trimIndent()
            assertProgramPrints(source, "Hello World!$EOL", testInfo)
        }

        @Test
        fun `Unit-returning calls can be printed`(testInfo: TestInfo) {
            val source = """
                        fn doNothing() {
                        }
                        fn $TEST_MAIN() {
                            print(doNothing())
                        }
                    """.trimIndent()
            assertProgramPrints(source, "Unit$EOL", testInfo)
        }

        @Test
        fun `builtin calls with the wrong arity`() = assertCompilationFails(
            function("print(1, 2)"),
            JvmLoweringException.BuiltinArityMismatch::class,
            "print requires exactly 1 argument",
        )
    }
}
