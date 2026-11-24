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

/** Expressions: arithmetic, comparisons, equality, Boolean logic, and precedence. */
class ExpressionTests {
    @Nested
    inner class Arithmetic {
        @Test
        fun `Ints can be added`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN(): Int {
                            val sum = 2 + 2
                            return sum
                        }
                    """.trimIndent()
            assertProgramReturns(source, 4, testInfo)
        }

        @Test
        fun `Ints can be subtracted`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN(): Int {
                            val difference = 100 - 37
                            return difference
                        }
                    """.trimIndent()
            assertProgramReturns(source, 63, testInfo)
        }

        @Test
        fun `unary minus negates Ints`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(-1)
                            val value = 5
                            print(-value)
                            print(-2 * 3)
                            print(1 - -2)
                            print(--4)
                        }
                    """.trimIndent()
            assertProgramPrints(source, "-1${EOL}-5${EOL}-6${EOL}3${EOL}4$EOL", testInfo)
        }

        @Test
        fun `Ints can be multiplied`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN(): Int {
                            val product = 6 * 8
                            return product
                        }
                    """.trimIndent()
            assertProgramReturns(source, 48, testInfo)
        }

        @Test
        fun `Ints can be divided if the result is an Int`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN(): Int {
                            val quotient = 16 / 4
                            return quotient
                        }
                    """.trimIndent()
            assertProgramReturns(source, 4, testInfo)
        }

        @Test
        fun `Ints support modulo`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN(): Int {
                            return 17 % 5
                        }
                    """.trimIndent()
            assertProgramReturns(source, 2, testInfo)
        }

        @Test
        fun `binary operands with different types`() = assertCompilationFails(
            function("val value = 1 * \"x\""),
            JvmLoweringException.OperandTypesDiffer::class,
            "operator '*' requires both operands to have the same type",
        )

        @Test
        fun `non-Int binary operands`() = assertCompilationFails(
            function("val value = \"x\" * \"y\""),
            JvmLoweringException.IntOperandsRequired::class,
            "operator '*' is only defined for Int operands",
        )

        @Test
        fun `non-Int unary operands`() = assertCompilationFails(
            function("print(-\"text\")"),
            JvmLoweringException.IntOperandsRequired::class,
            "operator '-' is only defined for Int operands",
        )
    }

    @Nested
    inner class Comparisons {
        @Test
        fun `Ints can be compared with less-than`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(3 < 5)
                            print(5 < 3)
                            print(3 < 3)
                            print(1 + 1 < 3)
                            print(3 < 1 + 1)
                        }
                    """.trimIndent()
            assertProgramPrints(source, "True${EOL}False${EOL}False${EOL}True${EOL}False${EOL}", testInfo)
        }

        @Test
        fun `Ints can be compared with greater-than`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(5 > 3)
                            print(3 > 5)
                            print(3 > 3)
                        }
                    """.trimIndent()
            assertProgramPrints(source, "True${EOL}False${EOL}False${EOL}", testInfo)
        }

        @Test
        fun `Ints can be compared with less-or-equal and greater-or-equal`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(3 <= 3)
                            print(4 <= 3)
                            print(3 >= 3)
                            print(3 >= 4)
                        }
                    """.trimIndent()
            assertProgramPrints(source, "True${EOL}False${EOL}True${EOL}False${EOL}", testInfo)
        }

        @Test
        fun `ordering Bool operands`() = assertCompilationFails(
            function("print(True < False)"),
            JvmLoweringException.IntOperandsRequired::class,
            "operator '<' is only defined for Int operands, got Bool",
        )
    }

    @Nested
    inner class Equality {
        @Test
        fun `equals compares Ints correctly`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(3 == 3)
                            print(4 == 5)
                        }
                    """.trimIndent()
            assertProgramPrints(source, "True${EOL}False${EOL}", testInfo)
        }

        @Test
        fun `not-equals compares Ints correctly`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(3 != 52)
                            print(4 != 4)
                        }
                    """.trimIndent()
            assertProgramPrints(source, "True${EOL}False${EOL}", testInfo)
        }

        @Test
        fun `Bool equality compares values`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(True == True)
                            print(True == False)
                            print(True != False)
                            print(False != False)
                        }
                    """.trimIndent()
            assertProgramPrints(source, "True${EOL}False${EOL}True${EOL}False$EOL", testInfo)
        }

        @Test
        fun `String equality compares contents`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print("hello" == "hello")
                            print("hello" == "world")
                            print("hello" != "world")
                            print("hello" != "hello")
                        }
                    """.trimIndent()
            assertProgramPrints(source, "True${EOL}False${EOL}True${EOL}False$EOL", testInfo)
        }

        @Test
        fun `equality operands with different types`() = assertCompilationFails(
            function("print(1 == True)"),
            JvmLoweringException.OperandTypesDiffer::class,
            "operator '==' requires both operands to have the same type",
        )
    }

    @Nested
    inner class BooleanLogic {
        @Test
        fun `logical and evaluates booleans`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(True and True)
                            print(True and False)
                            print(False and True)
                            print(False and False)
                        }
                    """.trimIndent()
            assertProgramPrints(source, "True${EOL}False${EOL}False${EOL}False${EOL}", testInfo)
        }

        @Test
        fun `logical or evaluates booleans`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(True or True)
                            print(True or False)
                            print(False or True)
                            print(False or False)
                        }
                    """.trimIndent()
            assertProgramPrints(source, "True${EOL}True${EOL}True${EOL}False${EOL}", testInfo)
        }

        @Test
        fun `not negates booleans`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            val done = False
                            print(not True)
                            print(not done)
                            print(not not True)
                            print(not (1 < 2))
                        }
                    """.trimIndent()
            assertProgramPrints(source, "False${EOL}True${EOL}True${EOL}False${EOL}", testInfo)
        }

        @Test
        fun `not applies to whole member call chains`(testInfo: TestInfo) {
            val source = """
                        class Task {
                            val finished: Bool
                            fn isFinished(): Bool {
                                return finished
                            }
                        }
                        fn $TEST_MAIN() {
                            val task = Task(False)
                            if not task.isFinished() and not task.finished {
                                print("pending")
                            }
                        }
                    """.trimIndent()
            assertProgramPrints(source, "pending$EOL", testInfo)
        }

        @Test
        fun `logical operators short-circuit`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(False and fail())
                            print(True or fail())
                        }
                        fn fail(): Bool {
                            print("should not run")
                            return True
                        }
                    """.trimIndent()
            assertProgramPrints(source, "False${EOL}True${EOL}", testInfo)
        }

        @Test
        fun `non-Bool logical operands`() = assertCompilationFails(
            function("val value = 1 and True"),
            JvmLoweringException.BoolOperandsRequired::class,
            "operator 'and' is only defined for Bool operands",
        )

        @Test
        fun `non-Bool not operands`() = assertCompilationFails(
            function("val value = not 1"),
            JvmLoweringException.BoolOperandsRequired::class,
            "operator 'not' is only defined for Bool operands",
        )
    }

    @Nested
    inner class Precedence {
        @Test
        fun `multiplication has precedence over addition`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN(): Int {
                            return 2 + 3 * 4
                        }
                    """.trimIndent()
            assertProgramReturns(source, 14, testInfo)
        }

        @Test
        fun `mixed addition and subtraction are left-associative`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN(): Int {
                            return 8 - 2 + 1
                        }
                    """.trimIndent()
            assertProgramReturns(source, 7, testInfo)
        }

        @Test
        fun `mixed multiplication, division, and modulo are left-associative`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN(): Int {
                            return 8 / 2 * 3 % 5
                        }
                    """.trimIndent()
            assertProgramReturns(source, 2, testInfo)
        }

        @Test
        fun `additive expressions have precedence over equality`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(1 + 1 == 2)
                            print(2 == 1 + 1)
                            print(1 + 1 == 3 - 1)
                        }
                    """.trimIndent()
            assertProgramPrints(source, "True${EOL}True${EOL}True$EOL", testInfo)
        }

        @Test
        fun `parentheses override arithmetic precedence`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN(): Int {
                            return (1 + 2) * 3
                        }
                    """.trimIndent()
            assertProgramReturns(source, 9, testInfo)
        }

        @Test
        fun `not binds tighter than comparison and logical operators`(testInfo: TestInfo) {
            val source = """
                        fn $TEST_MAIN() {
                            print(not True == False)
                            print(not False and False)
                            print(not True or True)
                            print(True == not False)
                        }
                    """.trimIndent()
            assertProgramPrints(source, "True${EOL}False${EOL}True${EOL}True${EOL}", testInfo)
        }

        @Test
        fun `not applied to an Int comparison operand`() = assertCompilationFails(
            function("val value = not 1 == 2"),
            JvmLoweringException.BoolOperandsRequired::class,
            "operator 'not' is only defined for Bool operands",
        )
    }
}
