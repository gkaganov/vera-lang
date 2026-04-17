import org.greg.VeraCompiler;

void main() throws IOException {
    var inputPath = Path.of("examples", "main.vera");
    var outputPath = Path.of("out", "Main.class");
    new VeraCompiler().compile(inputPath, outputPath);
}
