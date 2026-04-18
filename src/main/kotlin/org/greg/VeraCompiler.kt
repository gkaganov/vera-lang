package org.greg

import org.antlr.v4.runtime.BufferedTokenStream
import org.antlr.v4.runtime.CharStreams
import org.greg.antlr.VeraLexer
import org.greg.antlr.VeraParser
import org.greg.antlr.VeraParser.ArgumentListContext
import org.greg.antlr.VeraParser.BindStatementContext
import org.greg.antlr.VeraParser.ChainedExpressionContext
import org.greg.antlr.VeraParser.ExpressionContext
import org.greg.antlr.VeraParser.FunctionDeclarationContext
import org.greg.antlr.VeraParser.PrimaryExpressionContext
import org.greg.antlr.VeraParser.ProgramContext
import org.greg.antlr.VeraParser.StatementContext
import java.lang.classfile.ClassBuilder
import java.lang.classfile.ClassFile
import java.lang.classfile.CodeBuilder
import java.lang.classfile.MethodBuilder
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.CD_String
import java.lang.constant.ConstantDescs.CD_int
import java.lang.constant.ConstantDescs.CD_void
import java.lang.constant.MethodTypeDesc
import java.lang.reflect.AccessFlag
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeBytes

private data class LocalSlot(val slot: Int)

private data class FnState(
    val codeEmitter: CodeBuilder.() -> Unit = {},
    val nextFreeLocalSlot: LocalSlot = LocalSlot(0),
    private val locals: Map<String, LocalSlot> = emptyMap()
) {
    fun emit(block: CodeBuilder.() -> Unit): FnState =
        copy(codeEmitter = {
            codeEmitter()
            block()
        })

    /** Bind a local name to the current free slot, then advance the slot counter. */
    fun declareLocal(name: String): FnState =
        copy(locals = locals + (name to nextFreeLocalSlot), nextFreeLocalSlot = LocalSlot(nextFreeLocalSlot.slot + 1))

    fun getLocalSlot(name: String): LocalSlot? = locals[name]
}

private enum class Builtin(val keyword: String) {
    PRINT("print");

    companion object {
        private val byKeyword = entries.associateBy { it.keyword }
        fun from(keyword: String): Builtin? = byKeyword[keyword]
    }
}

class VeraCompiler(private val mainClassName: String) {

    fun compile(inputFile: Path, outputFile: Path) {
        val bytecode = compile(inputFile.readText())
        outputFile.parent?.createDirectories()
        outputFile.writeBytes(bytecode)
    }

    fun compile(code: String): ByteArray {
        val lexer = VeraLexer(CharStreams.fromString(code))
        val parser = VeraParser(BufferedTokenStream(lexer))
        val program = parser.program()

        val mainClassEmitter = processProgram(program)
        return ClassFile.of().build(ClassDesc.of(mainClassName), mainClassEmitter)
    }

    private fun processProgram(ctx: ProgramContext): ClassBuilder.() -> Unit {
        var classEmitter: ClassBuilder.() -> Unit = {}
        for (declaration in ctx.declaration()) {
            val fn = declaration.functionDeclaration() ?: continue
            classEmitter = processFunction(fn, classEmitter)
        }
        return {
            classEmitter()
            withFlags(AccessFlag.PUBLIC)
        }
    }

    private fun processFunction(ctx: FunctionDeclarationContext, classEmitter: ClassBuilder.() -> Unit): ClassBuilder.() -> Unit {
        var fnState = FnState()
        if (ctx.parameterClause().parameters() != null) {
            // TODO number and type of params
            fnState = fnState.declareLocal("args")
        }
        for (statement in ctx.block().statement()) {
            fnState = processStatement(statement, fnState)
        }
        fnState = fnState.emit { return_() }

        val fnName = ctx.IDENTIFIER().text
        val fnType = if (fnName == "main") {
            MethodTypeDesc.of(CD_void, CD_String.arrayType())
        } else {
            MethodTypeDesc.of(CD_void)
        }
        val fnFlags = AccessFlag.PUBLIC.mask() or AccessFlag.STATIC.mask()
        val fnDefinition: MethodBuilder.() -> Unit = { withCode(fnState.codeEmitter) }
        return {
            classEmitter()
            withMethod(fnName, fnType, fnFlags, fnDefinition)
        }
    }

    private fun processStatement(ctx: StatementContext, fnState: FnState): FnState {
        return if (ctx.bindStatement() != null) {
            processBindStatement(ctx.bindStatement(), fnState)
        } else if (ctx.expression() != null) {
            processExpression(ctx.expression(), fnState)
        } else {
            error("Invalid statement: ${ctx.text}")
        }
    }

    private fun processBindStatement(ctx: BindStatementContext, fnState: FnState): FnState {
        var fnState = fnState
        // process expression tree, put result on the stack
        fnState = processExpression(ctx.expression(), fnState)
        // get result from stack and store it in local variable table
        return storeLocalVar(fnState, ctx.IDENTIFIER().text)
    }

    private fun storeLocalVar(fnState: FnState, name: String): FnState {
        return fnState
            .emit { istore(fnState.nextFreeLocalSlot.slot) }
            .declareLocal(name)
    }

    private fun processExpression(ctx: ExpressionContext, fnState: FnState): FnState {
        // TODO more chain links
        return processChainedExpression(ctx.chainedExpression(0), fnState)
    }

    private fun processChainedExpression(ctx: ChainedExpressionContext, fnState: FnState): FnState {
        var fnState = fnState
        val result = processPrimaryExpression(ctx.primaryExpression(), fnState)
        val pendingSymbol = result.second
        fnState = result.first

        // no pending symbol -> done processing
        if (pendingSymbol == null) {
            return fnState
        }
        // pending symbol -> function/builtin call
        if (ctx.argumentList() != null) {
            if (!ctx.argumentList().isEmpty()) {
                fnState = processArguments(ctx.argumentList().first(), fnState)
            }
            val builtin = Builtin.from(pendingSymbol)
            fnState = if (builtin != null) {
                processBuiltinCall(fnState, builtin)
            } else {
                processFunctionCall(fnState, pendingSymbol)
            }
        }
        return fnState
    }

    private fun processArguments(ctx: ArgumentListContext, fnState: FnState): FnState {
        var fnState = fnState
        if (ctx.arguments() == null) {
            return fnState
        }
        for (expr in ctx.arguments().expression()) {
            fnState = processExpression(expr, fnState)
        }
        return fnState
    }

    private fun processBuiltinCall(fnState: FnState, builtin: Builtin): FnState {
        return when (builtin) {
            Builtin.PRINT -> {
                val system = ClassDesc.of("java.lang", "System")
                val printStream = ClassDesc.of("java.io", "PrintStream")
                fnState.emit {
                    getstatic(system, "out", printStream)
                    swap()
                    invokevirtual(printStream, "println", MethodTypeDesc.of(CD_void, CD_int))
                }
            }
        }
    }

    private fun processFunctionCall(fnState: FnState, fnName: String): FnState {
        val mainClass = ClassDesc.of(mainClassName)
        val fnType = MethodTypeDesc.of(CD_void)
        return fnState.emit { invokestatic(mainClass, fnName, fnType) }
    }

    private fun processPrimaryExpression(ctx: PrimaryExpressionContext, fnState: FnState): Pair<FnState, String?> {
        if (ctx.literal() != null) {
            return fnState.emit { loadConstant(ctx.literal().text.toInt()) } to null
        }
        if (ctx.IDENTIFIER() != null) {
            val name = ctx.IDENTIFIER().text
            val localSlot = fnState.getLocalSlot(name)
            return if (localSlot != null) {
                fnState.emit { iload(localSlot.slot) } to null
            } else {
                // not a local, could be builtin/function name
                fnState to name
            }
        } else if (ctx.expression() != null) {
            return processExpression(ctx.expression(), fnState) to null
        } else {
            throw UnsupportedOperationException()
        }
    }
}
