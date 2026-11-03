package vera.compiler.tests

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import vera.compiler.VeraCompiler
import vera.shared.model.Identifier
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeBytes

class VeraCompilerTests {

    companion object {
        private val EOL: String = System.lineSeparator()
        private val CLASS_ID = AtomicLong()
        private const val TEST_MAIN = "testMain"
    }

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
    fun `functions can be called`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print1()
                        print2()
                    }
                    fn print1() {
                        print(1)
                    }
                    fn print2() {
                        print(2)
                    }
                """.trimIndent()
        assertProgramPrints(source, "1${EOL}2${EOL}", testInfo)
    }

    @Test
    fun `var with explicit type declaration can be bound and accessed`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN(): Int {
                        var myVar1: Int = 50
                        var myVar2: Int = 51
                        return myVar1 + myVar2 + 52
                    }
                """.trimIndent()
        assertProgramReturns(source, 153, testInfo)
    }

    @Test
    fun `Int can be returned`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN(): Int {
                        var numberResult: Int = getInt() + 0
                        return numberResult
                    }
                    fn getInt(): Int {
                        return 698
                    }
                """.trimIndent()
        assertProgramReturns(source, 698, testInfo)
    }

    @Test
    fun `one Int parameter can be passed`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN(): Int {
                        var numberResult: Int = receiveAndReturn(369)
                        return numberResult
                    }
                    fn receiveAndReturn(int: Int): Int {
                        return int
                    }
                """.trimIndent()
        assertProgramReturns(source, 369, testInfo)
    }

    @Test
    fun `multiple Int parameters can be passed`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN(): Int {
                        return add3Ints(99, 98, 2 * 48 + 1)
                    }
                    fn add3Ints(int1: Int, int2: Int, int3: Int): Int {
                        return int1 + int2 + int3
                    }
                """.trimIndent()
        assertProgramReturns(source, 294, testInfo)
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
    fun `var types can be inferred when bound directly`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        var inferredString = "I am a String"
                        var inferredInt = 1000
                        print(inferredString)
                        print(inferredInt)
                    }
                """.trimIndent()
        assertProgramPrints(source, "I am a String${EOL}1000${EOL}", testInfo)
    }

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
    fun `Bools can be declared and bound to True and False`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        val trueValue = True
                        val falseValue = False
                        print(trueValue)
                        print(falseValue)
                    }
                """.trimIndent()
        assertProgramPrints(source, "true${EOL}false${EOL}", testInfo)
    }

    @Test
    fun `if statements execute only then-block if True`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        if 1 + 1 < 3 {
                            print("So true")
                        } else {
                            print("Never gonna happen")
                        }
                    }
                """.trimIndent()
        assertProgramPrints(source, "So true$EOL", testInfo)
    }

    @Test
    fun `if statements execute only else-block if False`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        if False {
                            print("So true")
                        } else {
                            print("Absolutely false!")
                        }
                    }
                """.trimIndent()
        assertProgramPrints(source, "Absolutely false!$EOL", testInfo)
    }

    @Test
    fun `blank lines inside if blocks are ignored`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        if True {

                            print("then")

                        } else {

                            print("else")

                        }
                    }
                """.trimIndent()
        assertProgramPrints(source, "then$EOL", testInfo)
    }

    @Test
    fun `blank lines before else are ignored`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        if False {
                            print("then")
                        }


                        else {
                            print("else")
                        }
                    }
                """.trimIndent()
        assertProgramPrints(source, "else$EOL", testInfo)
    }

    @Test
    fun `newline before else is ignored`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        if True {
                            print("then")
                        }
                        else {
                            print("else")
                        }
                    }
                """.trimIndent()
        assertProgramPrints(source, "then$EOL", testInfo)
    }

    @Test
    fun `if without else still terminates before following statement`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        if False {
                            print("then")
                        }

                        print("after")
                    }
                """.trimIndent()
        assertProgramPrints(source, "after$EOL", testInfo)
    }

    @Test
    fun `blank lines between statements are ignored`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print(1)


                        print(2)
                    }
                """.trimIndent()
        assertProgramPrints(source, "1${EOL}2${EOL}", testInfo)
    }

    @Test
    fun `blank lines at function block edges are ignored`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {

                        print(1)

                    }
                """.trimIndent()
        assertProgramPrints(source, "1$EOL", testInfo)
    }

    @Test
    fun `blank lines between function declarations are ignored`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print1()

                        print2()
                    }

                    fn print1() {
                        print(1)
                    }


                    fn print2() {
                        print(2)
                    }
                """.trimIndent()
        assertProgramPrints(source, "1${EOL}2${EOL}", testInfo)
    }

    @Test
    fun `blank lines with crlf line endings are ignored`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {

                        print(1)

                    }
                """.trimIndent().replace("\n", "\r\n")
        assertProgramPrints(source, "1$EOL", testInfo)
    }

    @Test
    fun `while with a false condition skips its body`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        while False {
                            print("body")
                        }
                        print("after")
                    }
                """.trimIndent()
        assertProgramPrints(source, "after$EOL", testInfo)
    }

    @Test
    fun `while executes its body when the condition is true`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        while 1 + 1 < 3 {
                            print("once")
                            return
                        }
                        print("after")
                    }
                """.trimIndent()
        assertProgramPrints(source, "once$EOL", testInfo)
    }

    @Test
    fun `while blocks allow blank lines`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        while True {

                            print("once")

                            return

                        }
                    }
                """.trimIndent()
        assertProgramPrints(source, "once$EOL", testInfo)
    }

    @Test
    fun `equals compares Ints correctly`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print(3 == 3)
                        print(4 == 5)
                    }
                """.trimIndent()
        assertProgramPrints(source, "true${EOL}false${EOL}", testInfo)
    }

    @Test
    fun `not-equals compares Ints correctly`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print(3 != 52)
                        print(4 != 4)
                    }
                """.trimIndent()
        assertProgramPrints(source, "true${EOL}false${EOL}", testInfo)
    }

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
        assertProgramPrints(source, "true${EOL}false${EOL}false${EOL}true${EOL}false${EOL}", testInfo)
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
        assertProgramPrints(source, "true${EOL}false${EOL}false${EOL}", testInfo)
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
        assertProgramPrints(source, "true${EOL}false${EOL}true${EOL}false${EOL}", testInfo)
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
        assertProgramPrints(source, "true${EOL}true${EOL}true$EOL", testInfo)
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
    fun `logical and evaluates booleans`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print(True and True)
                        print(True and False)
                        print(False and True)
                        print(False and False)
                    }
                """.trimIndent()
        assertProgramPrints(source, "true${EOL}false${EOL}false${EOL}false${EOL}", testInfo)
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
        assertProgramPrints(source, "true${EOL}true${EOL}true${EOL}false${EOL}", testInfo)
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
        assertProgramPrints(source, "false${EOL}true${EOL}", testInfo)
    }

    @Test
    fun `forward application passes the left value as the first argument`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print(2 | double)
                        print(1 | add(2))
                    }
                    fn double(value: Int): Int {
                        return value * 2
                    }
                    fn add(left: Int, right: Int): Int {
                        return left + right
                    }
                """.trimIndent()
        assertProgramPrints(source, "4${EOL}3${EOL}", testInfo)
    }

    @Test
    fun `unsupported expressions are rejected`() {
        assertCompilationFails(
            """
                fn $TEST_MAIN() {
                    val x = 1
                    print(x.foo)
                }
            """.trimIndent(),
            expectedMessagePart = "not supported yet",
        )
    }

    @Test
    fun `mismatched operand types are rejected`() {
        listOf(
            "1 + \"x\"" to "same type",
            "\"x\" + 1" to "same type",
            "\"x\" + \"y\"" to "only defined for Int operands",
            "1 < 2 < 3" to "same type",
            "1 and True" to "Bool",
            "True or 1" to "Bool",
        ).forEach { (expression, expectedMessagePart) ->
            assertCompilationFails(
                """
                    fn $TEST_MAIN(): Int {
                        return $expression
                    }
                """.trimIndent(),
                expectedMessagePart = expectedMessagePart,
            )
        }
    }

    private fun assertProgramReturns(
        source: String,
        expected: Any,
        testInfo: TestInfo,
        compileToOut: Boolean = false,
    ) {
        val className = Identifier("CompiledTestClass" + CLASS_ID.getAndIncrement())
        val bytecode = VeraCompiler.compile(className, source)[className]!!
        if (compileToOut) Path("out", testInfo.testMethod.get().name, "${className.value}.class").createParentDirectories().writeBytes(bytecode)
        val classLoader = ByteArrayClassLoader(mapOf(className.value to bytecode))
        val testMethod = classLoader.loadClass(className.value).getMethod(TEST_MAIN)
        val actual = testMethod.invoke(null)
        Assertions.assertEquals(expected, actual)
    }

    private fun assertProgramPrints(
        source: String,
        expected: String,
        testInfo: TestInfo,
        compileToOut: Boolean = false,
    ) {
        val className = Identifier("CompiledTestClass" + CLASS_ID.getAndIncrement())
        val bytecode = VeraCompiler.compile(className, source)[className]!!
        if (compileToOut) Path("out", testInfo.testMethod.get().name, "${className.value}.class").createParentDirectories().writeBytes(bytecode)
        val oldOut = System.out
        val baos = ByteArrayOutputStream()
        val classLoader = ByteArrayClassLoader(mapOf(className.value to bytecode))
        val testMethod = classLoader.loadClass(className.value).getMethod(TEST_MAIN)
        try {
            System.setOut(PrintStream(baos))
            testMethod.invoke(null)
        } finally {
            System.setOut(oldOut)
        }
        Assertions.assertEquals(expected, baos.toString())
    }

    private fun assertCompilationFails(source: String, expectedMessagePart: String) {
        val className = Identifier("CompiledTestClass" + CLASS_ID.getAndIncrement())
        val exception = Assertions.assertThrows(IllegalStateException::class.java) {
            VeraCompiler.compile(className, source)
        }
        Assertions.assertTrue(
            exception.message?.contains(expectedMessagePart) == true,
            "Expected error message to contain '$expectedMessagePart', but was '${exception.message}'",
        )
    }
}
