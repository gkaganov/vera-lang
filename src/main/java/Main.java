import org.greg.VeraCompiler;

void main() throws IOException {
    new VeraCompiler().compile(Path.of("main.gl"));
}
