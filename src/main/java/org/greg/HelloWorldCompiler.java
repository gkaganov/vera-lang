package org.greg;

import java.io.IOException;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.MethodBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static java.lang.constant.ClassDesc.of;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;

public class HelloWorldCompiler {

    public static void generate() throws IOException {

        var system = ClassDesc.of("java.lang", "System");
        var printStream = of("java.io", "PrintStream");
        Consumer<CodeBuilder> mainMethodCode = cb -> cb
                // arg0 - "this" context
                .getstatic(system, "out", printStream)
                // arg1 - string to print
                .ldc("Hello World!")
                // call method with arg0 and arg1
                .invokevirtual(printStream, "println", MethodTypeDesc.of(CD_void, CD_String))
                .return_();
        Consumer<MethodBuilder> mainMethod = mb -> mb
                .withCode(mainMethodCode);

        var mainMethodName = "main";
        Consumer<ClassBuilder> dummyClass = clb -> clb
                .withFlags(PUBLIC)
                .withMethod(
                        mainMethodName,
                        MethodTypeDesc.of(CD_void, CD_String.arrayType()),
                        PUBLIC.mask() | STATIC.mask(),
                        mainMethod
                );

        var className = "DummyClass";
        var classfilePath = Path.of("out", className + ".class");
        Files.createDirectories(classfilePath.getParent());
        ClassFile.of().buildTo(classfilePath, of(className), dummyClass);
    }
}
