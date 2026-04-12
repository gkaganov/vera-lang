import org.greg.GLangCompiler;

void main() throws IOException {
    new GLangCompiler().compile(Path.of("main.gl"));
}
