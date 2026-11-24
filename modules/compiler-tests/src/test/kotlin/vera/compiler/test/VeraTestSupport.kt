package vera.compiler.test

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.TestInfo
import vera.compiler.VeraCompiler
import vera.shared.model.Identifier
import vera.shared.model.VeraException
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeBytes
import kotlin.reflect.KClass

// Shared helpers for compiler tests: compile Vera source, run it, and check its output or its error.

internal val EOL: String = System.lineSeparator()
internal const val TEST_MAIN = "testMain"
private val CLASS_ID = AtomicLong()

/** A `testMain` function with [lines] as its body. */
internal fun function(vararg lines: String): String = buildString {
    appendLine("fn $TEST_MAIN() {")
    lines.forEach { appendLine("    $it") }
    appendLine("}")
}

internal fun classDeclaration(name: String, vararg lines: String): String = buildString {
    appendLine("class $name {")
    lines.forEach { appendLine("    $it") }
    appendLine("}")
}

internal fun traitDeclaration(name: String, vararg lines: String): String = buildString {
    appendLine("trait $name {")
    lines.forEach { appendLine("    $it") }
    appendLine("}")
}

/** Compiles [source], runs its JVM entry point `main(String[])`, and compares what it printed. */
internal fun assertJvmMainPrints(source: String, expected: String) {
    val className = Identifier("CompiledTestClass" + CLASS_ID.getAndIncrement())
    val bytecode = compileOrFail(className, source)
    val classLoader = ByteArrayClassLoader(bytecode.mapKeys { it.key.value })
    val entryPoint = classLoader.loadClass(className.value)
        .getMethod("main", Array<String>::class.java)

    val oldOut = System.out
    val baos = ByteArrayOutputStream()
    try {
        System.setOut(PrintStream(baos))
        val arguments: Any = emptyArray<String>()
        entryPoint.invoke(null, arguments)
    } finally {
        System.setOut(oldOut)
    }

    Assertions.assertEquals(expected, baos.toString())
}

/** Compiles [source], calls `testMain`, and compares its return value. */
internal fun assertProgramReturns(
    source: String,
    expected: Any,
    testInfo: TestInfo,
    compileToOut: Boolean = false,
) {
    val className = Identifier("CompiledTestClass" + CLASS_ID.getAndIncrement())
    val bytecode = compileOrFail(className, source)
    val mainBytecode = bytecode[className] ?: throw AssertionError("no bytecode emitted for ${className.value}")
    if (compileToOut) Path("out", testInfo.testMethod.get().name, "${className.value}.class").createParentDirectories().writeBytes(mainBytecode)
    val classLoader = ByteArrayClassLoader(bytecode.mapKeys { it.key.value })
    val testMethod = classLoader.loadClass(className.value).getMethod(TEST_MAIN)
    val actual = testMethod.invoke(null)
    Assertions.assertEquals(expected, actual)
}

/** Compiles [source], calls `testMain`, and compares what it printed. */
internal fun assertProgramPrints(
    source: String,
    expected: String,
    testInfo: TestInfo,
    compileToOut: Boolean = false,
) {
    val className = Identifier("CompiledTestClass" + CLASS_ID.getAndIncrement())
    val bytecode = compileOrFail(className, source)
    val mainBytecode = bytecode[className] ?: throw AssertionError("no bytecode emitted for ${className.value}")
    if (compileToOut) Path("out", testInfo.testMethod.get().name, "${className.value}.class").createParentDirectories().writeBytes(mainBytecode)
    val oldOut = System.out
    val baos = ByteArrayOutputStream()
    val classLoader = ByteArrayClassLoader(bytecode.mapKeys { it.key.value })
    val testMethod = classLoader.loadClass(className.value).getMethod(TEST_MAIN)
    try {
        System.setOut(PrintStream(baos))
        testMethod.invoke(null)
    } finally {
        System.setOut(oldOut)
    }
    Assertions.assertEquals(expected, baos.toString())
}

internal fun compileOrFail(className: Identifier, source: String): Map<Identifier, ByteArray> {
    return try {
        VeraCompiler.compile(className, source)
    } catch (exception: VeraException) {
        throw AssertionError("compilation failed: ${exception.render()}")
    }
}

/**
 * Compiles [source] and expects an error of [expectedType] whose message contains [messagePart]. Every
 * error reachable from source must carry a source location.
 */
internal fun assertCompilationFails(
    source: String,
    expectedType: KClass<out VeraException>,
    messagePart: String,
) {
    val error = try {
        VeraCompiler.compile(Identifier("ErrorTest"), source)
        throw AssertionError("Expected compilation to fail, but it succeeded")
    } catch (exception: VeraException) {
        exception
    }
    assertMatches(error, expectedType, messagePart)
    Assertions.assertNotNull(error.span, "Expected a source location for: ${error.render()}")
}

/** Runs [block] and expects an error of type [T] whose message contains [messagePart]. */
internal inline fun <reified T : VeraException> assertCompilerError(
    messagePart: String,
    crossinline block: () -> Unit,
) {
    val error = Assertions.assertThrows(T::class.java) { block() }
    assertMatches(error, T::class, messagePart)
}

internal fun assertMatches(
    error: VeraException,
    expectedType: KClass<out VeraException>,
    messagePart: String,
) {
    Assertions.assertTrue(
        expectedType.isInstance(error),
        "Expected ${expectedType.simpleName}, but got ${error::class.simpleName}: ${error.render()}",
    )
    Assertions.assertTrue(
        error.render().contains(messagePart),
        "Expected error message to contain '$messagePart', but was '${error.render()}'",
    )
}
