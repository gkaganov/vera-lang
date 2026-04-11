package org.greg;

import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.CharStreams;
import org.greg.antlr4.GLangLexer;
import org.greg.antlr4.GLangParser;

import java.io.IOException;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.MethodBuilder;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static java.lang.constant.ClassDesc.of;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;

public class GLangCompiler {

    public static void compile(Path inputFile) throws IOException {

        var code = Files.readString(inputFile);
        var lexer = new GLangLexer(CharStreams.fromString(code));
        var parser = new GLangParser(new BufferedTokenStream(lexer));
        var program = parser.program();

        System.out.println(program.toStringTree(parser));

        Consumer<CodeBuilder> mainMethodCode = cb -> {
            new GLangVisitorImpl(cb).visitProgram(program);
            cb.return_();
        };

        Consumer<MethodBuilder> mainMethod = mb -> mb
                .withCode(mainMethodCode);

        Consumer<ClassBuilder> mainClass = clb -> clb
                .withFlags(PUBLIC)
                .withMethod(
                        "main",
                        MethodTypeDesc.of(CD_void, CD_String.arrayType()),
                        PUBLIC.mask() | STATIC.mask(),
                        mainMethod
                );

        var className = "Main";
        var classfilePath = Path.of("out", className + ".class");
        Files.createDirectories(classfilePath.getParent());
        ClassFile.of().buildTo(classfilePath, of(className), mainClass);
    }
}
