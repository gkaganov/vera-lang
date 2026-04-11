package org.greg;

import org.greg.antlr4.GLangBaseVisitor;
import org.greg.antlr4.GLangParser;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.MethodBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.function.Consumer;

import static java.lang.constant.ClassDesc.of;
import static java.lang.constant.ConstantDescs.*;
import static java.lang.reflect.AccessFlag.PUBLIC;
import static java.lang.reflect.AccessFlag.STATIC;

class GLangVisitorImpl extends GLangBaseVisitor<Void> {

    private final ClassBuilder classBuilder;
    private CodeBuilder currentCodeBuilder;

    GLangVisitorImpl(ClassBuilder classBuilder) {
        this.classBuilder = classBuilder;
    }

    @Override
    public Void visitProgram(GLangParser.ProgramContext ctx) {
        visitChildren(ctx);
        classBuilder.withFlags(PUBLIC);
        return null;
    }

    @Override
    public Void visitFn(GLangParser.FnContext ctx) {
        // we define a consumer which will receive a prebuilt CodeBuilder-Object by the ClassFile-library
        // then we feed our jvm instructions to the CodeBuilder which stores them internally
        // this internal state is later read by the ClassFile-library to create our class
        Consumer<CodeBuilder> fnCode = cb -> {
            // the rest of the logic in this class needs a reference to the CodeBuilder so it can write to it
            this.currentCodeBuilder = cb;
            for (int i = 0; i < ctx.expr().size(); i++) {
                visitExpr(ctx.expr(i));
            }
            cb.return_();
        };

        // the MethodBuilder works like the CodeBuilder - we pass a consumer with our logic to the ClassFile-library for later execution
        Consumer<MethodBuilder> fn = mb -> mb
                .withCode(fnCode);

        var fnName = ctx.fnName().LETTERS().getText();
        // we register our method with the ClassBuilder
        classBuilder.withMethod(
                fnName,
                MethodTypeDesc.of(CD_void, CD_String.arrayType()),
                PUBLIC.mask() | STATIC.mask(),
                fn
        );
        return null;
    }

    @Override
    public Void visitExpr(GLangParser.ExprContext ctx) {
        visitTerm(ctx.term(0));
        for (int i = 0; i < ctx.infixOperation().size(); i++) {
            visitTerm(ctx.term(i + 1));
            visitInfixOperation(ctx.infixOperation(i));
        }

        // after calculating the terms pass the result to the prefixOperation (e.g. print)
        if (ctx.prefixOperation(0) != null) {
            visitPrefixOperation(ctx.prefixOperation(0));
        }
        return null;
    }

    @Override
    public Void visitPrefixOperation(GLangParser.PrefixOperationContext ctx) {
        //noinspection SwitchStatementWithTooFewBranches
        switch (ctx.getStart().getType()) {
            case GLangParser.PRINT:
                var system = ClassDesc.of("java.lang", "System");
                var printStream = of("java.io", "PrintStream");
                currentCodeBuilder.getstatic(system, "out", printStream);
                currentCodeBuilder.swap();
                currentCodeBuilder.invokevirtual(printStream, "println", MethodTypeDesc.of(CD_void, CD_int));
                break;
        }
        return null;
    }

    @Override
    public Void visitInfixOperation(GLangParser.InfixOperationContext ctx) {
        switch (ctx.getStart().getType()) {
            case GLangParser.PLUS:
                currentCodeBuilder.iadd();
                break;
            case GLangParser.MINUS:
                currentCodeBuilder.isub();
                break;
        }
        return null;
    }

    @Override
    public Void visitTerm(GLangParser.TermContext ctx) {
        int value = Integer.parseInt(ctx.INT().getText());
        currentCodeBuilder.ldc(value);
        return null;
    }
}
