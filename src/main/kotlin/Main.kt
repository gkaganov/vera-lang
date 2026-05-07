import org.vera-lang.VeraCompiler
import java.nio.file.Path

fun main() {
    val inputPath = Path.of("examples", "main.vera")
    val outputPath = Path.of("out", "Main.class")
    VeraCompiler("Main").compile(inputPath, outputPath)
}
