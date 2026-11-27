package vera.compiler.test

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import vera.jvm.bytecode.emitter.BytecodeEmitException
import vera.jvm.instruction.FlatInstructionException
import vera.jvm.lowering.JvmLoweringException
import vera.parser.VeraLexException
import vera.parser.VeraParseException
import vera.shared.model.VeraException
import kotlin.io.path.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.walk

/**
 * Every compiler error has a test: each class in a stage's exception hierarchy must be referenced by some
 * test as `Stage.Error::class` (as `assertCompilationFails` takes it) or `<Stage.Error>` (as
 * `assertCompilerError` takes it); a mention in a comment or string does not count. Errors that no test can reach are listed in [untestable] with
 * the reason.
 */
class ErrorCoverageTests {

    private val hierarchies: List<Class<out VeraException>> = listOf(
        VeraLexException::class.java,
        VeraParseException::class.java,
        JvmLoweringException::class.java,
        FlatInstructionException::class.java,
        BytecodeEmitException::class.java,
    )

    /** Errors that cannot be triggered, by `Stage.Error`, with the reason. */
    private val untestable: Map<String, String> = mapOf(
        "JvmLoweringException.InternalInvariant" to "a compiler bug by definition; no valid input reaches it",
    )

    /** The test sources of every module, relative to this module's directory, where Gradle runs tests. */
    private val testRoots = listOf(
        "src/test/kotlin",
        "../jvm-instruction-lowering/src/test/kotlin",
        "../jvm-bytecode-emitter/src/test/kotlin",
    )

    @Test
    fun `every compiler error is named by a test`() {
        val testSources = testRoots.flatMap { root ->
            Path(root).walk().filter { path -> path.extension == "kt" && !path.endsWith("ErrorCoverageTests.kt") }.toList()
        }.joinToString("\n") { path -> path.readText() }
        Assertions.assertTrue(testSources.isNotEmpty(), "no test sources found under $testRoots")

        val errors = hierarchies.flatMap { hierarchy ->
            hierarchy.declaredClasses
                .filter { error -> hierarchy.isAssignableFrom(error) }
                .map { error -> "${hierarchy.simpleName}.${error.simpleName}" }
        }
        val untested = errors.filter { error -> error !in untestable && !testSources.contains(reference(error)) }
        Assertions.assertEquals(emptyList<String>(), untested, "compiler errors without a test")

        val stale = untestable.keys - errors.toSet()
        Assertions.assertEquals(emptySet<String>(), stale, "untestable entries that are no longer errors")
    }

    /** Matches `Stage.Error::class` or `<Stage.Error>`, the two forms the test helpers take. */
    private fun reference(error: String): Regex {
        val name = Regex.escape(error)
        return Regex("\\b$name::class\\b|<$name>")
    }
}
