package org.greg

import org.antlr.v4.runtime.BufferedTokenStream
import org.antlr.v4.runtime.CharStreams
import org.greg.antlr.VeraLexer
import org.greg.antlr.VeraParser
import org.greg.antlr.VeraParser.*
import java.io.IOException
import java.lang.classfile.ClassBuilder
import java.lang.classfile.ClassFile
import java.lang.classfile.CodeBuilder
import java.lang.classfile.MethodBuilder
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs
import java.lang.constant.MethodTypeDesc
import java.lang.reflect.AccessFlag
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Consumer

class VeraCompiler(private val mainClassName: String) {
    @JvmRecord
    private data class LocalSlot(val slot: Int)

    @JvmRecord
    private data class FunctionState(val cb: Consumer<CodeBuilder?>?, val localVars: Map<String?, LocalSlot?>?) {
        constructor() : this(Consumer { `_`: CodeBuilder? -> }, emptyMap())

        fun withCodeBuilder(cb: Consumer<CodeBuilder?>?): FunctionState {
            return FunctionState(cb, localVars)
        }

        fun withLocalVars(localVars: Map<String?, LocalSlot?>?): FunctionState {
            return FunctionState(cb, localVars)
        }
    }

    private enum class Builtin(val text: String) {
        PRINT("print");

        companion object {
            fun fromLabel(label: String?): Builtin? {
                return entries.firstOrNull { it.text == label }
            }
        }
    }

    @Throws(IOException::class)
    fun compile(inputFile: Path, outputFile: Path) {
        val code = Files.readString(inputFile)
        val bytecode = compile(code)

        Files.createDirectories(outputFile.parent)
        Files.write(outputFile, bytecode)
    }

    fun compile(code: String): ByteArray {
        val lexer = VeraLexer(CharStreams.fromString(code))
        val parser = VeraParser(BufferedTokenStream(lexer))
        val program = parser.program()

        // create an empty consumer and extend it with compiler logic
        val mainClass = processProgram(program) { `_`: ClassBuilder? -> }
        // ClassFile will create a ClassBuilder instance and apply the compiler logic to it
        return ClassFile.of().build(ClassDesc.of(mainClassName), mainClass)
    }

    private fun processProgram(ctx: ProgramContext, classBuilder: Consumer<ClassBuilder?>): Consumer<ClassBuilder?> {
        var classBuilder = classBuilder
        for (i in ctx.declaration().indices) {
            if (ctx.declaration(i).functionDeclaration() != null) {
                classBuilder = processFunction(ctx.declaration(i).functionDeclaration(), classBuilder)
            }
        }
        return classBuilder.andThen(Consumer { cb: ClassBuilder? -> cb!!.withFlags(AccessFlag.PUBLIC) })
    }

    private fun processFunction(
        ctx: FunctionDeclarationContext,
        classBuilder: Consumer<ClassBuilder?>
    ): Consumer<ClassBuilder?> {
        // CodeBuilder and MethodBuilder work like ClassBuilder - define compiler logic in the consumer, which will later be passed to ClassFile for execution
        var fnState = FunctionState()
        if (ctx.parameterClause().parameters() != null) {
            // TODO number and type of params
            fnState = fnState.withLocalVars(mapOf("args" to LocalSlot(0)))
        }
        for (i in ctx.block().statement().indices) {
            fnState = processStatement(ctx.block().statement(i), fnState)
        }
        fnState = fnState.withCodeBuilder(fnState.cb!!.andThen(Consumer { obj: CodeBuilder? -> obj!!.return_() }))

        val fnName = ctx.IDENTIFIER().text
        val fnType = if (fnName == "main") MethodTypeDesc.of(
            ConstantDescs.CD_void,
            ConstantDescs.CD_String.arrayType()
        ) else MethodTypeDesc.of(
            ConstantDescs.CD_void
        )
        val fnFlags = AccessFlag.PUBLIC.mask() or AccessFlag.STATIC.mask()
        val finalFnState = fnState
        val fnDefinition = Consumer { mb: MethodBuilder? ->
            mb!!
                .withCode(finalFnState.cb)
        }
        return classBuilder.andThen(Consumer { cb: ClassBuilder? ->
            cb!!.withMethod(
                fnName,
                fnType,
                fnFlags,
                fnDefinition
            )
        })
    }

    private fun processStatement(ctx: StatementContext, fnState: FunctionState): FunctionState {
        return if (ctx.bindStatement() != null) {
            processBindStatement(ctx.bindStatement(), fnState)
        } else if (ctx.expression() != null) {
            processExpression(ctx.expression(), fnState)
        } else {
            throw IllegalStateException("Statement type " + ctx.bindStatement().getStart().text + " is invalid.")
        }
    }

    private fun processBindStatement(ctx: BindStatementContext, fnState: FunctionState): FunctionState {
        var fnState = fnState
        val newVarName = ctx.IDENTIFIER().text
        val newVarPosition = LocalSlot(fnState.localVars!!.size)
        // process expression tree
        fnState = processExpression(ctx.expression(), fnState)
        // store result in local variable table
        val codeWithIStore = fnState.cb!!.andThen(Consumer { cb: CodeBuilder? -> cb!!.istore(newVarPosition.slot) })
        val newVarMap = fnState.localVars!! + (newVarName to newVarPosition)
        return FunctionState(codeWithIStore, newVarMap)
    }

    private fun processExpression(ctx: ExpressionContext, fnState: FunctionState): FunctionState {
        // TODO more chain links
        return processChainedExpression(ctx.chainedExpression(0), fnState)
    }

    private fun processChainedExpression(ctx: ChainedExpressionContext, fnState: FunctionState): FunctionState {
        var fnState = fnState
        val result = processPrimaryExpression(ctx.primaryExpression(), fnState)
        val pendingSymbol = result.pendingSymbol
        fnState = result.fnState

        // no pending symbol -> done processing
        if (pendingSymbol == null) {
            return fnState
        }
        // pending symbol -> function/builtin call
        if (ctx.argumentList() != null) {
            if (!ctx.argumentList().isEmpty()) {
                fnState = processArguments(ctx.argumentList().first(), fnState)
            }
            val builtin = Builtin.fromLabel(pendingSymbol)
            fnState = if (builtin != null) {
                processBuiltinCall(fnState, builtin)
            } else {
                processFunctionCall(fnState, pendingSymbol)
            }
        }
        return fnState
    }

    private fun processArguments(ctx: ArgumentListContext, fnState: FunctionState): FunctionState {
        var fnState = fnState
        if (ctx.arguments() == null) {
            return fnState
        }
        for (expr in ctx.arguments().expression()) {
            fnState = processExpression(expr, fnState)
        }
        return fnState
    }

    private fun processBuiltinCall(fnState: FunctionState, builtin: Builtin): FunctionState {
        return when (builtin) {
            Builtin.PRINT -> {
                val system = ClassDesc.of("java.lang", "System")
                val printStream = ClassDesc.of("java.io", "PrintStream")
                fnState.withCodeBuilder(fnState.cb!!.andThen(Consumer { cb: CodeBuilder? ->
                    cb!!.getstatic(system, "out", printStream)
                    cb.swap()
                    cb.invokevirtual(
                        printStream,
                        "println",
                        MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int)
                    )
                }))
            }
        }
    }

    private fun processFunctionCall(fnState: FunctionState, fnName: String?): FunctionState {
        val mainClass = ClassDesc.of(mainClassName)
        val fnType = MethodTypeDesc.of(ConstantDescs.CD_void)
        return fnState.withCodeBuilder(fnState.cb!!.andThen(Consumer { cb: CodeBuilder? ->
            cb!!.invokestatic(
                mainClass,
                fnName,
                fnType
            )
        }))
    }

    @JvmRecord
    private data class PrimaryExpressionResult(val fnState: FunctionState, val pendingSymbol: String?) {
        constructor(fnState: FunctionState) : this(fnState, null)
    }

    private fun processPrimaryExpression(
        ctx: PrimaryExpressionContext,
        fnState: FunctionState
    ): PrimaryExpressionResult {
        if (ctx.literal() != null) {
            return PrimaryExpressionResult(
                fnState.withCodeBuilder(
                    fnState.cb!!.andThen(Consumer { cb: CodeBuilder? ->
                        cb?.loadConstant(
                            ctx.literal().text.toInt()
                        )
                    })
                )
            )
        } else if (ctx.IDENTIFIER() != null) {
            val name = ctx.IDENTIFIER().text
            val local = fnState.localVars!![name]

            // the identifier is not a local variable so it must be a function
            if (local != null) {
                return PrimaryExpressionResult(
                    fnState.withCodeBuilder(
                        fnState.cb!!.andThen(Consumer { cb: CodeBuilder? -> cb!!.iload(local.slot) })
                    )
                )
            }
            // not a local; could be builtin/function name
            return PrimaryExpressionResult(fnState, name)
        } else if (ctx.expression() != null) {
            return PrimaryExpressionResult(processExpression(ctx.expression(), fnState))
        } else {
            throw UnsupportedOperationException()
        }
    }
}
