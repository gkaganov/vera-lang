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

/** The built-in types `Int`, `Bool`, and `Unit`. */
class TypeTests {
    @Nested
    inner class Int {
        @Test
        fun `the smallest Int literal can be written`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(-2147483648)
                            print(-2147483648 + 1)
                            print(-(5))
                        }
                    """.trimIndent()
            assertProgramPrints(source, "-2147483648$EOL-2147483647$EOL-5$EOL", testInfo)
        }

        @Test
        fun `out-of-range Int literals`() = assertCompilationFails(
            function("print(999999999999999999999)"),
            VeraParseException.InvalidIntLiteral::class,
            "invalid Int literal",
        )
    }

    @Nested
    inner class Bool {
        @Test
        fun `Bools can be declared and bound to True and False`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            val trueValue = True
                            val falseValue = False
                            print(trueValue)
                            print(falseValue)
                        }
                    """.trimIndent()
            assertProgramPrints(source, "True${EOL}False${EOL}", testInfo)
        }
    }

    @Nested
    inner class Unit {
        @Test
        fun `Unit values can be stored in objects and read back`(testInfo: TestInfo) {
            val source = """
                        class Signal {
                            val flag: Unit
                        }
                        fn $TEST_MAIN(): Int {
                            val signal = Signal(Unit)
                            val flag: Unit = signal.flag
                            return 1
                        }
                    """.trimIndent()
            assertProgramReturns(source, 1, testInfo)
        }

        @Test
        fun `Unit values can be passed and returned`(testInfo: TestInfo) {
            val source = """
                        fn identity(unit: Unit) {
                            return unit
                        }
                        fn $TEST_MAIN(): Int {
                            val unit = identity(Unit)
                            return 1
                        }
                    """.trimIndent()
            assertProgramReturns(source, 1, testInfo)
        }

        @Test
        fun `Unit can be returned explicitly`(testInfo: TestInfo) {
            val source = """
                        fn explicitUnit() {
                            return Unit
                        }
                        fn $TEST_MAIN(): Int {
                            val unit = explicitUnit()
                            return 1
                        }
                    """.trimIndent()
            assertProgramReturns(source, 1, testInfo)
        }

        @Test
        fun `Unit equality always holds`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(Unit == Unit)
                            print(Unit != Unit)
                        }
                    """.trimIndent()
            assertProgramPrints(source, "True${EOL}False$EOL", testInfo)
        }

        @Test
        fun `Unit equality evaluates both operands in order`(testInfo: TestInfo) {
            val source = """
                        fn first() {
                            print("first")
                        }
                        fn second() {
                            print("second")
                        }
                        fn $TEST_MAIN() {
                            print(first() == second())
                            print(first() != second())
                        }
                    """.trimIndent()
            assertProgramPrints(
                source,
                "first${EOL}second${EOL}True${EOL}first${EOL}second${EOL}False$EOL",
                testInfo,
            )
        }

        @Test
        fun `Unit can be printed`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(Unit)
                        }
                    """.trimIndent()
            assertProgramPrints(source, "Unit$EOL", testInfo)
        }
    }
}
