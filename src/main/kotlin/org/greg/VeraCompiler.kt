package org.greg

import org.antlr.v4.kotlinruntime.BufferedTokenStream
import org.antlr.v4.kotlinruntime.CharStreams
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
import kotlin.io.path.createParentDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeBytes

private data class LocalSlot(val slot: Int)

private data class FnState(
    val typeDesc: MethodTypeDesc = MethodTypeDesc.of(CD_void),
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
        outputFile.createParentDirectories()
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
            classEmitter = processFunction(declaration.functionDeclaration(), classEmitter)
        }
        return {
            classEmitter()
            withFlags(AccessFlag.PUBLIC)
        }
    }

    private fun processFunction(
        ctx: FunctionDeclarationContext,
        classEmitter: ClassBuilder.() -> Unit
    ): ClassBuilder.() -> Unit {
        var fnState = FnState()
        var terminates = false
        for (statement in ctx.block().statement()) {
            val (newState, explicitReturn) = processStatement(statement, fnState)
            fnState = newState
            terminates = explicitReturn
        }
        if (!terminates) {
            fnState = fnState.emit { return_() }
        }

        val fnName = ctx.IDENTIFIER().text
        fnState = fnState.copy(
            typeDesc = if (fnName == "main") {
                MethodTypeDesc.of(CD_void, CD_String.arrayType())
            } else if (terminates) {
                // TODO return type here
                MethodTypeDesc.of(CD_int)
            } else {
                MethodTypeDesc.of(CD_void)
            }
        )
        if (ctx.parameterClause().parameters() != null) {
            // TODO number and type of params
            fnState = fnState.declareLocal("args")
        }

        val fnFlags = AccessFlag.PUBLIC.mask() or AccessFlag.STATIC.mask()
        val fnDefinition: MethodBuilder.() -> Unit = { withCode(fnState.codeEmitter) }
        return {
            classEmitter()
            withMethod(fnName, fnState.typeDesc, fnFlags, fnDefinition)
        }
    }

    /** Emits the statement and returns a Pair of new FnState and Boolean (explicit return on all paths?) */
    private fun processStatement(ctx: StatementContext, fnState: FnState): Pair<FnState, Boolean> {
        ctx.bindStatement()?.let {
            return processBindStatement(it, fnState) to false
        }
        ctx.expression()?.let {
            return processExpression(it, fnState) to false
        }
        ctx.returnStatement()?.let { returnStmt ->
            val returnExpression = returnStmt.expression()
            return if (returnExpression != null) {
                val newState = processExpression(returnExpression, fnState)
                newState.emit { ireturn() } to true
            } else {
                fnState.emit { return_() } to true
            }
        }
        error("Invalid statement: ${ctx.text}")
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
        val firstExpr = ctx.chainedExpression(0) ?: error("first chainedExpr is never null")
        return processChainedExpression(firstExpr, fnState)
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
        if (ctx.argumentList().isNotEmpty()) {
            fnState = processArguments(ctx.argumentList().first(), fnState)
        }
        val fnType = MethodTypeDesc.of(CD_int)
        val builtin = Builtin.from(pendingSymbol)
        fnState = if (builtin != null) {
            processBuiltinCall(fnState, builtin)
        } else {
            processFunctionCall(fnState, pendingSymbol, fnType)
        }
        return fnState
    }

    private fun processArguments(ctx: ArgumentListContext, fnState: FnState): FnState {
        var state = fnState
        for (expr in ctx.arguments()?.expression().orEmpty()) {
            state = processExpression(expr, state)
        }
        return state
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

    private fun processFunctionCall(fnState: FnState, fnName: String, fnType: MethodTypeDesc): FnState {
        val mainClass = ClassDesc.of(mainClassName)
        // TODO function lookup table, search for fnType
        return fnState.emit { invokestatic(mainClass, fnName, fnType) }
    }

    /** Emits a primary expression and returns new FnState and an optional String (pending symbol?) */
    private fun processPrimaryExpression(ctx: PrimaryExpressionContext, fnState: FnState): Pair<FnState, String?> {
        val literal = ctx.literal()
        if (literal != null) {
            return fnState.emit { loadConstant(literal.text.toInt()) } to null
        }

        val identifier = ctx.IDENTIFIER()
        val expression = ctx.expression()
        if (identifier != null) {
            val name = identifier.text
            val localSlot = fnState.getLocalSlot(name)
            return if (localSlot != null) {
                fnState.emit { iload(localSlot.slot) } to null
            } else {
                // not a local, could be builtin/function name
                fnState to name
            }
        } else if (expression != null) {
            return processExpression(expression, fnState) to null
        } else {
            throw UnsupportedOperationException()
        }
    }
}
