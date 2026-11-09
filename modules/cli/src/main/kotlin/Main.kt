import vera.compiler.VeraCompiler
import vera.shared.model.Identifier
import vera.shared.model.VeraException
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.system.exitProcess

fun main() {
    val className = Identifier("Main")
    val inputPath = Path.of("examples", "main.vera")

    val sourcecode = inputPath.readText()

    try {
        val bytecode = VeraCompiler.compile(className, sourcecode)
        if (bytecode.isEmpty()) {
            System.err.println("internal error: no bytecode emitted for ${className.value}")
            exitProcess(1)
        }
        bytecode.forEach { (emittedClassName, bytes) ->
            val outputPath = Path.of("out", "${emittedClassName.value}.class")
            outputPath.createParentDirectories()
            outputPath.writeBytes(bytes)
        }
    } catch (exception: VeraException) {
        System.err.println(exception.render())
        exitProcess(1)
    }
}
