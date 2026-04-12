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
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;

public class GLangCompiler {

    public void compile(Path inputFile) throws IOException {
        var code = Files.readString(inputFile);
        var lexer = new GLangLexer(CharStreams.fromString(code));
        var parser = new GLangParser(new BufferedTokenStream(lexer));
        var program = parser.program();

        System.out.println(program.toStringTree(parser));

        var outPath = Path.of("out");
        Files.createDirectories(outPath);
        var className = "Main";
        var classfilePath = outPath.resolve(className + ".class");
        Files.deleteIfExists(classfilePath);

        // create an empty consumer and extend it with compiler logic
        Consumer<ClassBuilder> mainClass = processProgram(program, _ -> {});
        // ClassFile will create a ClassBuilder instance and apply the compiler logic to it
        ClassFile.of().buildTo(classfilePath, ClassDesc.of(className), mainClass);
    }

    private Consumer<ClassBuilder> processProgram(GLangParser.ProgramContext ctx, Consumer<ClassBuilder> classBuilder) {
        for (int i = 0; i < ctx.fn().size(); i++) {
            classBuilder = processFn(ctx.fn(i), classBuilder);
        }
        return classBuilder.andThen(cb -> cb.withFlags(PUBLIC));
    }

    private Consumer<ClassBuilder> processFn(GLangParser.FnContext ctx, Consumer<ClassBuilder> classBuilder) {
        // CodeBuilder and MethodBuilder work like ClassBuilder - define compiler logic in the consumer, which will later be passed to ClassFile for execution
        Consumer<CodeBuilder> fnCode = _ -> {};
        for (int i = 0; i < ctx.expr().size(); i++) {
            fnCode = processExpr(ctx.expr(i), fnCode);
        }
        fnCode = fnCode.andThen(CodeBuilder::return_);
        final var finalFnCode = fnCode;

        var fnName = ctx.FN_NAME().getText();
        var fnType = fnName.equals("main") ? MethodTypeDesc.of(CD_void, CD_String.arrayType()) : MethodTypeDesc.of(CD_void);
        var fnFlags = PUBLIC.mask() | STATIC.mask();
        Consumer<MethodBuilder> fnDefinition = mb -> mb
                .withCode(finalFnCode);
        return classBuilder.andThen(cb -> cb.withMethod(
                fnName,
                fnType,
                fnFlags,
                fnDefinition
        ));
    }

    private Consumer<CodeBuilder> processExpr(GLangParser.ExprContext ctx, Consumer<CodeBuilder> codeBuilder) {
        if (!ctx.term().isEmpty()) {
            codeBuilder = processTerm(ctx.term(0), codeBuilder);
            for (int i = 0; i < ctx.infixOperation().size(); i++) {
                codeBuilder = processTerm(ctx.term(i + 1), codeBuilder);
                codeBuilder = processInfixOperation(ctx.infixOperation(i), codeBuilder);
            }
        }

        // after calculating the terms pass the result to the prefixOperation (e.g. print)
        if (ctx.prefixOperation() != null) {
            codeBuilder = processPrefixOperation(ctx.prefixOperation(), codeBuilder);
        }
        return codeBuilder;
    }

    private Consumer<CodeBuilder> processPrefixOperation(GLangParser.PrefixOperationContext ctx, Consumer<CodeBuilder> codeBuilder) {
        if (ctx.fnBuiltinCall() != null) {
            return processFnBuiltinCall(ctx.fnBuiltinCall(), codeBuilder);
        } else if (ctx.fnCall() != null) {
            return processFnCall(ctx.fnCall(), codeBuilder);
        } else {
            throw new RuntimeException(ctx.getStart().getText() + " is an invalid PrefixOperation.");
        }
    }

    private Consumer<CodeBuilder> processFnBuiltinCall(GLangParser.FnBuiltinCallContext ctx, Consumer<CodeBuilder> codeBuilder) {
        int operationType = ctx.getStart().getType();
        //noinspection SwitchStatementWithTooFewBranches
        return switch (operationType) {
            case GLangParser.PRINT -> {
                var system = ClassDesc.of("java.lang", "System");
                var printStream = ClassDesc.of("java.io", "PrintStream");
                yield codeBuilder.andThen(cb -> {
                    cb.getstatic(system, "out", printStream);
                    cb.swap();
                    cb.invokevirtual(printStream, "println", MethodTypeDesc.of(CD_void, CD_int));
                });
            }
            default -> throw new RuntimeException("OperationType " + operationType + " is not a valid FnBuiltinCall.");
        };
    }

    private Consumer<CodeBuilder> processFnCall(GLangParser.FnCallContext ctx, Consumer<CodeBuilder> codeBuilder) {
        var main = ClassDesc.of("Main");
        var fnName = ctx.FN_NAME().getText();
        var fnType = MethodTypeDesc.of(CD_void);
        return codeBuilder.andThen(cb -> cb.invokestatic(main, fnName, fnType));
    }

    private Consumer<CodeBuilder> processInfixOperation(GLangParser.InfixOperationContext ctx, Consumer<CodeBuilder> codeBuilder) {
        int operationType = ctx.getStart().getType();
        return switch (operationType) {
            case GLangParser.PLUS -> codeBuilder.andThen(CodeBuilder::iadd);
            case GLangParser.MINUS -> codeBuilder.andThen(CodeBuilder::isub);
            default -> throw new RuntimeException("OperationType " + operationType + " is not a valid InfixOperation.");
        };
    }

    private Consumer<CodeBuilder> processTerm(GLangParser.TermContext ctx, Consumer<CodeBuilder> codeBuilder) {
        int value = Integer.parseInt(ctx.INT().getText());
        return codeBuilder.andThen(cb -> cb.ldc(value));
    }
}
