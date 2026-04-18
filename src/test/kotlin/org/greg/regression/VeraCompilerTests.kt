package org.greg.regression

import org.greg.VeraCompiler
import org.greg.lib.classloader.ByteArrayClassLoader
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

class VeraCompilerTests {
    @Test
    @Throws(Exception::class)
    fun printBuiltinPrintsToSystemOut() {
        val source = """
                    fn testMethod() {
                        print(65)
                    }
                
                """.trimIndent()
        assertProgramPrints(source, "65$EOL")
    }

    @Test
    @Throws(Exception::class)
    fun functionsCanBeCalled() {
        val source = """
                    fn testMethod() {
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
        assertProgramPrints(source, "1${EOL}2${EOL}")
    }

    @Test
    @Throws(Exception::class)
    fun varCanBeBoundAndAccessed() {
        val source = """
                    fn testMethod() {
                        var myVar1 = 50
                        var myVar2 = 51
                        print(myVar1)
                        print(myVar2)
                        print(52)
                    }
                
                """.trimIndent()
        assertProgramPrints(source, 50.toString() + EOL + 51 + EOL + 52 + EOL)
    }

    @Throws(ReflectiveOperationException::class)
    private fun assertProgramPrints(source: String, expectedOutput: String?) {
        val mainMethodName = "testMethod"
        val className = "CompiledTestClass" + CLASS_ID.getAndIncrement()
        val bytecode = VeraCompiler(className).compile(source)
        val oldOut = System.out
        val baos = ByteArrayOutputStream()
        val classLoader = ByteArrayClassLoader(mapOf(className to bytecode))
        val testMethod = classLoader.loadClass(className).getMethod(mainMethodName)
        try {
            System.setOut(PrintStream(baos))
            testMethod.invoke(null)
        } finally {
            System.setOut(oldOut)
        }
        Assertions.assertEquals(expectedOutput, baos.toString(StandardCharsets.UTF_8))
    }

    @Suppress("unused")
    @Throws(ReflectiveOperationException::class)
    private fun assertProgramReturns(source: String, expected: Any?) {
        val methodName = "testMethod"
        val className = "CompiledTestClass" + System.nanoTime()
        val bytecode = VeraCompiler(className).compile(source)
        val classLoader = ByteArrayClassLoader(mapOf(className to bytecode))
        val testMethod = classLoader.loadClass(className).getMethod(methodName)
        val actual = testMethod.invoke(null)
        Assertions.assertEquals(expected, actual)
    }

    companion object {
        private val EOL: String? = System.lineSeparator()
        private val CLASS_ID = AtomicLong()
    }
}
