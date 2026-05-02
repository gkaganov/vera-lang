package org.greg

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
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
                    fn $TEST_MAIN() {
                        var myVar1: Int = 50
                        var myVar2: Int = 51
                        print(myVar1)
                        print(myVar2)
                        print(52)
                    }
                """.trimIndent()
        assertProgramPrints(source, 50.toString() + EOL + 51 + EOL + 52 + EOL, testInfo)
    }

    @Test
    fun `Int can be returned`(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN(): Int {
                        var numberResult: Int = getInt()
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
                    fn $TEST_MAIN() {
                        print3Ints(99, 98, 97)
                    }
                    fn print3Ints(int1: Int, int2: Int, int3: Int) {
                        print(int1)
                        print(int2)
                        print(int3)
                    }
                """.trimIndent()
        assertProgramPrints(source, "99${EOL}98${EOL}97${EOL}", testInfo)
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

    // TODO explicit thread sync
    private fun assertProgramPrints(
        source: String,
        expected: String,
        testInfo: TestInfo,
        compileToOut: Boolean = false,
    ) {
        val className = "CompiledTestClass" + CLASS_ID.getAndIncrement()
        val bytecode = VeraCompiler(className).compile(source)
        if (compileToOut) Path("out", testInfo.testMethod.get().name, "$className.class").createParentDirectories().writeBytes(bytecode)
        val oldOut = System.out
        val baos = ByteArrayOutputStream()
        val classLoader = ByteArrayClassLoader(mapOf(className to bytecode))
        val testMethod = classLoader.loadClass(className).getMethod(TEST_MAIN)
        try {
            System.setOut(PrintStream(baos))
            testMethod.invoke(null)
        } finally {
            System.setOut(oldOut)
        }
        Assertions.assertEquals(expected, baos.toString())
    }

    private fun assertProgramReturns(
        source: String,
        expected: Any,
        testInfo: TestInfo,
        compileToOut: Boolean = false,
    ) {
        val className = "CompiledTestClass" + CLASS_ID.getAndIncrement()
        val bytecode = VeraCompiler(className).compile(source)
        if (compileToOut) Path("out", testInfo.testMethod.get().name, "$className.class").createParentDirectories().writeBytes(bytecode)
        val classLoader = ByteArrayClassLoader(mapOf(className to bytecode))
        val testMethod = classLoader.loadClass(className).getMethod(TEST_MAIN)
        val actual = testMethod.invoke(null)
        Assertions.assertEquals(expected, actual)
    }
}
