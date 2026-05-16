import vera.compiler.VeraCompiler
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeBytes

fun main() {
    val className = "Main"
    val inputPath = Path.of("examples", "main.vera")
    val outputPath = Path.of("out", "$className.class")

    val sourcecode = inputPath.readText()
    val bytecode = VeraCompiler(className).compile(sourcecode)

    outputPath.createParentDirectories()
    outputPath.writeBytes(bytecode)
}
