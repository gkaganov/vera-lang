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
    fun printBuiltinPrintsToSystemOut(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        print(65)
                    }
                
                """.trimIndent()
        assertProgramPrints(source, "65$EOL", testInfo)
    }

    @Test
    fun functionsCanBeCalled(testInfo: TestInfo) {
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
    fun varCanBeBoundAndAccessed(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN() {
                        var myVar1 = 50
                        var myVar2 = 51
                        print(myVar1)
                        print(myVar2)
                        print(52)
                    }
                
                """.trimIndent()
        assertProgramPrints(source, 50.toString() + EOL + 51 + EOL + 52 + EOL, testInfo)
    }

    @Test
    fun intCanBeReturned(testInfo: TestInfo) {
        val source = """
                    fn $TEST_MAIN(): Int {
                        var numberResult = getInt()
                        return numberResult
                    }
                    fn getInt(): Int {
                        return 698
                    }
                
                """.trimIndent()
        assertProgramReturns(source, 698, testInfo)
    }

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

    @Suppress("SameParameterValue")
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