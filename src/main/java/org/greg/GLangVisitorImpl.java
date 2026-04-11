package org.greg;

import org.greg.antlr4.GLangBaseVisitor;
import org.greg.antlr4.GLangParser;

import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;

import static java.lang.constant.ClassDesc.of;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_void;

class GLangVisitorImpl extends GLangBaseVisitor<Void> {

    private final CodeBuilder cb;

    GLangVisitorImpl(CodeBuilder cb) {
        this.cb = cb;
    }

    @Override
    public Void visitProgram(GLangParser.ProgramContext ctx) {
        visitChildren(ctx);
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
                cb.getstatic(system, "out", printStream);
                cb.swap();
                cb.invokevirtual(printStream, "println", MethodTypeDesc.of(CD_void, CD_int));
                break;
        }
        return null;
    }

    @Override
    public Void visitInfixOperation(GLangParser.InfixOperationContext ctx) {
        switch (ctx.getStart().getType()) {
            case GLangParser.PLUS:
                cb.iadd();
                break;
            case GLangParser.MINUS:
                cb.isub();
                break;
        }
        return null;
    }

    @Override
    public Void visitTerm(GLangParser.TermContext ctx) {
        int value = Integer.parseInt(ctx.INT().getText());
        cb.ldc(value);
        return null;
    }
}
