package org.greg;

import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.CharStreams;
import org.greg.antlr4.GLangLexer;
import org.greg.antlr4.GLangParser;

import java.io.IOException;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static java.lang.constant.ClassDesc.of;

public class GLangCompiler {

    public static void compile(Path inputFile) throws IOException {

        var code = Files.readString(inputFile);
        var lexer = new GLangLexer(CharStreams.fromString(code));
        var parser = new GLangParser(new BufferedTokenStream(lexer));
        var program = parser.program();

        System.out.println(program.toStringTree(parser));

        Consumer<ClassBuilder> mainClass = classBuilder -> new GLangVisitorImpl(classBuilder).visitProgram(program);

        var className = "Main";
        var classfilePath = Path.of("out", className + ".class");
        Files.createDirectories(classfilePath.getParent());
        ClassFile.of().buildTo(classfilePath, of(className), mainClass);
    }
}
