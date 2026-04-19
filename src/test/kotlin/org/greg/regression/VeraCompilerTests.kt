package org.greg.regression

import org.greg.VeraCompiler
import org.greg.lib.classloader.ByteArrayClassLoader
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
        assertProgramPrints(source, "1${EOL}2${EOL}", testInfo, true)
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
                    fn $TEST_MAIN() {
                        var numberResult = getInt()
                        print(numberResult)
                    }
                    fn getInt() {
                        return 698
                    }
                
                """.trimIndent()
        assertProgramPrints(source, "698$EOL", testInfo, true)
    }

    private fun assertProgramPrints(
        source: String,
        expectedOutput: String,
        testInfo: TestInfo,
        compileToOut: Boolean = false,
    ) {
        val className = "CompiledTestClass" + CLASS_ID.getAndIncrement()
        val bytecode = VeraCompiler(className).compile(source)
        if (compileToOut) {
            val path = Path("out", testInfo.testMethod.get().name, "$className.class")
            path.createParentDirectories()
            path.writeBytes(bytecode)
        }
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
        Assertions.assertEquals(expectedOutput, baos.toString())
    }

    @Suppress("unused")
    private fun assertProgramReturns(source: String, expected: Any) {
        val className = "CompiledTestClass" + System.nanoTime()
        val bytecode = VeraCompiler(className).compile(source)
        val classLoader = ByteArrayClassLoader(mapOf(className to bytecode))
        val testMethod = classLoader.loadClass(className).getMethod(TEST_MAIN)
        val actual = testMethod.invoke(null)
        Assertions.assertEquals(expected, actual)
    }
}
