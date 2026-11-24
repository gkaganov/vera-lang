package vera.compiler.test

import org.junit.jupiter.api.Assertions
import vera.compiler.VeraCompiler
import vera.shared.model.Identifier
import vera.shared.model.VeraException
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.reflect.KClass

// Shared helpers for compiler tests: compile Vera source, run it, and check its output or its error.
//
// Sources are usually indented raw strings:
//
//     assertPrints(
//         """
//         fn main() = print("hi")
//         """,
//         "hi",
//     )
//
// A source that starts with a line break is trimmed with `trimIndent`; any other source is used as is,
// so a test can build exact input such as CRLF line endings.

internal val EOL: String = System.lineSeparator()

/** The program class every test program compiles into; each program gets its own class loader. */
internal const val PROGRAM_CLASS = "VeraTestProgram"

/** A `main` function with [lines] as its body. */
internal fun function(vararg lines: String): String = buildString {
    appendLine("fn main() {")
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

/** Compiles [source], calls its Vera `main`, and expects it to print [lines], each ending with a line break. */
internal fun assertPrints(source: String, vararg lines: String) {
    val (_, output) = runMain(source)
    Assertions.assertEquals(expectedOutput(lines), output)
}

/** Compiles [source], calls its Vera `main`, and expects it to return [expected]. */
internal fun assertReturns(source: String, expected: Any) {
    val (result, _) = runMain(source)
    Assertions.assertEquals(expected, result)
}

/** Compiles [source], runs its JVM entry point `main(String[])`, and expects it to print [lines]. */
internal fun assertJvmMainPrints(source: String, vararg lines: String) {
    val entryPoint = compileProgram(source).loadProgramClass().getMethod("main", Array<String>::class.java)
    val (_, output) = capturingOutput {
        val arguments: Any = emptyArray<String>()
        entryPoint.invoke(null, arguments)
    }
    Assertions.assertEquals(expectedOutput(lines), output)
}

/** Compiles [source] and calls its zero-parameter Vera `main`, returning its result and what it printed. */
internal fun runMain(source: String): Pair<Any?, String> {
    val mainMethod = compileProgram(source).loadProgramClass().getMethod("main")
    return capturingOutput { mainMethod.invoke(null) }
}

/** The classes compiled from one program. */
internal class CompiledProgram(val bytecode: Map<Identifier, ByteArray>) {
    private val classLoader = ByteArrayClassLoader(bytecode.mapKeys { it.key.value })

    fun loadClass(name: String): Class<*> = classLoader.loadClass(name)

    fun loadProgramClass(): Class<*> = loadClass(PROGRAM_CLASS)
}

/** Compiles [source] into [PROGRAM_CLASS], failing the test with the rendered error if it does not compile. */
internal fun compileProgram(source: String): CompiledProgram {
    val bytecode = try {
        VeraCompiler.compile(Identifier(PROGRAM_CLASS), prepared(source))
    } catch (exception: VeraException) {
        throw AssertionError("compilation failed: ${exception.render()}")
    }
    return CompiledProgram(bytecode)
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
        VeraCompiler.compile(Identifier("ErrorTest"), prepared(source))
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

private fun prepared(source: String): String = if (source.startsWith("\n")) source.trimIndent() else source

private fun expectedOutput(lines: Array<out String>): String = lines.joinToString("") { line -> line + EOL }

private fun <T> capturingOutput(block: () -> T): Pair<T, String> {
    val oldOut = System.out
    val output = ByteArrayOutputStream()
    try {
        System.setOut(PrintStream(output))
        return block() to output.toString()
    } finally {
        System.setOut(oldOut)
    }
}
