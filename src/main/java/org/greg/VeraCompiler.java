package org.greg;

import io.vavr.collection.Array;
import io.vavr.collection.HashMap;
import io.vavr.collection.List;
import io.vavr.collection.Map;
import io.vavr.control.Option;
import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.CharStreams;
import org.greg.antlr4.VeraLexer;
import org.greg.antlr4.VeraParser;
import org.greg.antlr4.VeraParser.ArgumentListContext;
import org.greg.antlr4.VeraParser.BindStatementContext;
import org.greg.antlr4.VeraParser.ChainedExpressionContext;
import org.greg.antlr4.VeraParser.ExpressionContext;
import org.greg.antlr4.VeraParser.FunctionDeclarationContext;
import org.greg.antlr4.VeraParser.PrimaryExpressionContext;
import org.greg.antlr4.VeraParser.ProgramContext;
import org.greg.antlr4.VeraParser.StatementContext;

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

public class VeraCompiler {

    private final String mainClassName;

    public VeraCompiler(String mainClassName) {
        this.mainClassName = mainClassName;
    }

    private record LocalSlot(int slot) {}

    private record FunctionState(Consumer<CodeBuilder> cb, Map<String, LocalSlot> localVars) {
        FunctionState() {
            this(_ -> {}, HashMap.empty());
        }

        FunctionState withCodeBuilder(Consumer<CodeBuilder> cb) {
            return new FunctionState(cb, localVars);
        }

        FunctionState withLocalVars(Map<String, LocalSlot> localVars) {
            return new FunctionState(cb, localVars);
        }
    }

    private enum Builtin {
        PRINT("print");

        final String text;

        Builtin(String text) {
            this.text = text;
        }

        static List<String> textValues() {
            return Array.of(values()).map(val -> val.text).toList();
        }

        static Option<Builtin> fromLabel(Option<String> label) {
            return label
                    .fold(
                            Option::none,
                            lbl -> Array.of(values()).find(b -> b.text.equals(lbl))
                    );
        }
    }

    public void compile(Path inputFile, Path outputFile) throws IOException {
        var code = Files.readString(inputFile);
        var bytecode = compile(code);

        Files.createDirectories(outputFile.getParent());
        Files.write(outputFile, bytecode);
    }

    public byte[] compile(String code) {
        var lexer = new VeraLexer(CharStreams.fromString(code));
        var parser = new VeraParser(new BufferedTokenStream(lexer));
        var program = parser.program();

        // create an empty consumer and extend it with compiler logic
        Consumer<ClassBuilder> mainClass = processProgram(program, _ -> {});
        // ClassFile will create a ClassBuilder instance and apply the compiler logic to it
        return ClassFile.of().build(ClassDesc.of(mainClassName), mainClass);
    }

    private Consumer<ClassBuilder> processProgram(ProgramContext ctx, Consumer<ClassBuilder> classBuilder) {
        for (int i = 0; i < ctx.declaration().size(); i++) {
            if (ctx.declaration(i).functionDeclaration() != null) {
                classBuilder = processFunction(ctx.declaration(i).functionDeclaration(), classBuilder);
            }
        }
        return classBuilder.andThen(cb -> cb.withFlags(PUBLIC));
    }

    private Consumer<ClassBuilder> processFunction(FunctionDeclarationContext ctx, Consumer<ClassBuilder> classBuilder) {
        // CodeBuilder and MethodBuilder work like ClassBuilder - define compiler logic in the consumer, which will later be passed to ClassFile for execution
        var fnState = new FunctionState();
        if (ctx.parameterClause().parameters() != null) {
            // TODO number and type of params
            fnState = fnState.withLocalVars(HashMap.of("args", new LocalSlot(0)));
        }
        for (int i = 0; i < ctx.block().statement().size(); i++) {
            fnState = processStatement(ctx.block().statement(i), fnState);
        }
        fnState = fnState.withCodeBuilder(fnState.cb.andThen(CodeBuilder::return_));

        var fnName = ctx.IDENTIFIER().getText();
        var fnType = fnName.equals("main") ? MethodTypeDesc.of(CD_void, CD_String.arrayType()) : MethodTypeDesc.of(CD_void);
        var fnFlags = PUBLIC.mask() | STATIC.mask();
        var finalFnState = fnState;
        Consumer<MethodBuilder> fnDefinition = mb -> mb
                .withCode(finalFnState.cb);
        return classBuilder.andThen(cb -> cb.withMethod(
                fnName,
                fnType,
                fnFlags,
                fnDefinition
        ));
    }

    private FunctionState processStatement(StatementContext ctx, FunctionState fnState) {
        if (ctx.bindStatement() != null) {
            return processBindStatement(ctx.bindStatement(), fnState);
        } else if (ctx.expression() != null) {
            return processExpression(ctx.expression(), fnState);
        } else {
            throw new IllegalStateException("Statement type " + ctx.bindStatement().getStart().getText() + " is invalid.");
        }
    }

    private FunctionState processBindStatement(BindStatementContext ctx, FunctionState fnState) {
        var newVarName = ctx.IDENTIFIER().getText();
        var newVarPosition = new LocalSlot(fnState.localVars.size());
        // process expression tree
        fnState = processExpression(ctx.expression(), fnState);
        // store result in local variable table
        var codeWithIStore = fnState.cb.andThen(cb -> cb.istore(newVarPosition.slot));
        var newVarMap = fnState.localVars.put(newVarName, newVarPosition);
        return new FunctionState(codeWithIStore, newVarMap);
    }

    private FunctionState processExpression(ExpressionContext ctx, FunctionState fnState) {
        // TODO more chain links
        return processChainedExpression(ctx.chainedExpression(0), fnState);
    }

    @SuppressWarnings("StatementWithEmptyBody")
    private FunctionState processChainedExpression(ChainedExpressionContext ctx, FunctionState fnState) {
        var result = processPrimaryExpression(ctx.primaryExpression(), fnState);
        var pendingSymbol = result.pendingSymbol;
        fnState = result.fnState;

        // no pending symbol -> done processing
        if (pendingSymbol.isEmpty()) {
            return fnState;
        }
        // pending symbol -> function/builtin call
        if (ctx.argumentList() != null) {
            if (!ctx.argumentList().isEmpty()) {
                fnState = processArguments(ctx.argumentList().getFirst(), fnState);
            }
            Option<Builtin> builtin = Builtin.fromLabel(pendingSymbol);
            if (builtin.isDefined()) {
                fnState = processBuiltinCall(fnState, builtin.get());
            } else {
                fnState = processFunctionCall(fnState, pendingSymbol.get());
            }
        } else if (ctx.memberAccess() != null) {
        }
        return fnState;
    }

    private FunctionState processArguments(ArgumentListContext ctx, FunctionState fnState) {
        if (ctx.arguments() == null) {
            return fnState;
        }
        for (var expr : ctx.arguments().expression()) {
            fnState = processExpression(expr, fnState);
        }
        return fnState;
    }

    private FunctionState processBuiltinCall(FunctionState fnState, Builtin builtin) {
        return switch (builtin) {
            case Builtin.PRINT -> {
                var system = ClassDesc.of("java.lang", "System");
                var printStream = ClassDesc.of("java.io", "PrintStream");
                yield fnState.withCodeBuilder(fnState.cb.andThen(cb -> {
                    cb.getstatic(system, "out", printStream);
                    cb.swap();
                    cb.invokevirtual(printStream, "println", MethodTypeDesc.of(CD_void, CD_int));
                }));
            }
        };
    }

    private FunctionState processFunctionCall(FunctionState fnState, String fnName) {
        var mainClass = ClassDesc.of(mainClassName);
        var fnType = MethodTypeDesc.of(CD_void);
        return fnState.withCodeBuilder(fnState.cb.andThen(cb -> cb.invokestatic(mainClass, fnName, fnType)));
    }

    private record PrimaryExpressionResult(FunctionState fnState, Option<String> pendingSymbol) {
        PrimaryExpressionResult(FunctionState fnState) {
            this(fnState, Option.none());
        }

        PrimaryExpressionResult(FunctionState fnState, String pendingSymbol) {
            this(fnState, Option.of(pendingSymbol));
        }
    }

    private PrimaryExpressionResult processPrimaryExpression(PrimaryExpressionContext ctx, FunctionState fnState) {
        if (ctx.literal() != null) {
            return new PrimaryExpressionResult(
                    fnState.withCodeBuilder(
                            fnState.cb.andThen(cb -> cb.ldc(Integer.parseInt(ctx.literal().getText())))
                    )
            );
        } else if (ctx.IDENTIFIER() != null) {
            var name = ctx.IDENTIFIER().getText();
            var local = fnState.localVars.get(name);

            // the identifier is not a local variable so it must be a function
            if (!local.isEmpty()) {
                return new PrimaryExpressionResult(
                        fnState.withCodeBuilder(
                                fnState.cb.andThen(cb -> cb.iload(local.get().slot))
                        )
                );
            }
            // not a local; could be builtin/function name
            return new PrimaryExpressionResult(fnState, name);
        } else if (ctx.expression() != null) {
            return new PrimaryExpressionResult(processExpression(ctx.expression(), fnState));
        } else {
            throw new UnsupportedOperationException();
        }
    }
}
