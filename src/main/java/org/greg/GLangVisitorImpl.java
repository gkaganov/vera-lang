package org.greg;

import org.greg.antlr4.GLangBaseVisitor;
import org.greg.antlr4.GLangParser;

import java.lang.classfile.CodeBuilder;

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
        for (int i = 0; i < ctx.operation().size(); i++) {
            visitTerm(ctx.term(i + 1));
            visitOperation(ctx.operation(i));
        }
        return null;
    }

    @Override
    public Void visitOperation(GLangParser.OperationContext ctx) {
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
