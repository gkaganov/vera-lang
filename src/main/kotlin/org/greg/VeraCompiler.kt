package org.greg

import org.antlr.v4.kotlinruntime.BufferedTokenStream
import org.antlr.v4.kotlinruntime.CharStreams
import org.greg.antlr.VeraLexer
import org.greg.antlr.VeraParser
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

// TODO this should prob be extracted
private data class FnState(
    val codeEmitter: CodeBuilder.() -> Unit = {},
    val nextFreeLocalSlot: LocalSlot = LocalSlot(0),
    val visibleFunctions: Map<String, FunctionDeclaration> = emptyMap(),
    private val locals: Map<String, LocalSlot> = emptyMap()
) {

    data class LocalSlot(val slot: Int)

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

// TODO add mapping from antlr ast types to custom domain ast
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

        val customAstProgram = AstMapper().mapProgram(program)

        val mainClassEmitter = processProgram(customAstProgram)
        return ClassFile.of().build(ClassDesc.of(mainClassName), mainClassEmitter)
    }

    private fun processProgram(program: Program): ClassBuilder.() -> Unit {
        // first pass - collect function declarations by name
        val fnDeclarations = mutableMapOf<String, FunctionDeclaration>()
        for (declaration in program.declarations) {
            if (declaration is FunctionDeclaration) {
                fnDeclarations[declaration.name] = declaration
            }
        }

        // second pass - compile program
        var classEmitter: ClassBuilder.() -> Unit = {}
        for (declaration in program.declarations) {
            if (declaration is FunctionDeclaration) {
                classEmitter = processFunction(declaration, classEmitter, fnDeclarations)
            }
        }

        return {
            classEmitter()
            withFlags(AccessFlag.PUBLIC)
        }
    }

    private fun getClassDescFrom(type: Type): ClassDesc {
        return when (type) {
            Type.INT -> CD_int
            Type.STRING -> CD_String
            Type.VOID -> CD_void
        }
    }

    // TODO merge with FnState into a new class
    private fun processFunction(
        declaration: FunctionDeclaration,
        classEmitter: ClassBuilder.() -> Unit,
        visibleFunctions: Map<String, FunctionDeclaration>
    ): ClassBuilder.() -> Unit {
        var fnState = FnState().copy(visibleFunctions = visibleFunctions)
        var terminates = false
        for (statement in declaration.statements) {
            val (newState, explicitReturn) = processStatement(statement, fnState)
            fnState = newState
            terminates = explicitReturn
        }
        if (!terminates) {
            fnState = fnState.emit { return_() }
        }

        if (declaration.params.isNotEmpty()) {
            // TODO number and type of params
            fnState = fnState.declareLocal("args")
        }

        val fnName = declaration.name
        val fnFlags = AccessFlag.PUBLIC.mask() or AccessFlag.STATIC.mask()
        val fnDefinition: MethodBuilder.() -> Unit = { withCode(fnState.codeEmitter) }
        val fnDesc = getMethodTypeDescFrom(declaration)
        return {
            classEmitter()
            withMethod(fnName, fnDesc, fnFlags, fnDefinition)
        }
    }

    private fun getMethodTypeDescFrom(functionDeclaration: FunctionDeclaration): MethodTypeDesc {
        val paramClassDescs = functionDeclaration.params.map { param -> getClassDescFrom(param.type) }
        return MethodTypeDesc.of(getClassDescFrom(functionDeclaration.returnType), paramClassDescs)
    }

    /** Emits the statement and returns a Pair of new FnState and Boolean (explicit return on all paths?) */
    private fun processStatement(statement: Statement, fnState: FnState): Pair<FnState, Boolean> {
        return when (statement) {
            is BindStatement -> {
                processBindStatement(statement, fnState) to false
            }

            is Expression -> {
                processExpression(statement, fnState) to false
            }

            is ReturnStatement -> {
                if (statement.expression != null) {
                    val newState = processExpression(statement.expression, fnState)
                    newState.emit { ireturn() } to true
                } else {
                    fnState.emit { return_() } to true
                }
            }

            is RebindStatement -> TODO()
        }
    }

    private fun processBindStatement(bindStatement: BindStatement, fnState: FnState): FnState {
        var fnState = fnState
        // process expression tree, put result on the stack
        fnState = processExpression(bindStatement.expression, fnState)
        // get result from stack and store it in local variable table
        return storeLocalVar(fnState, bindStatement.name)
    }

    private fun storeLocalVar(fnState: FnState, name: String): FnState {
        return fnState
            .emit { istore(fnState.nextFreeLocalSlot.slot) }
            .declareLocal(name)
    }

    private fun processExpression(expression: Expression, fnState: FnState): FnState {
        // TODO more chain links
        val firstExpr = expression.chainedExpressions.first()
        return processChainedExpression(firstExpr, fnState)
    }

    private fun processChainedExpression(chainedExpression: ChainedExpression, fnState: FnState): FnState {
        var fnState = fnState
        val result = processPrimaryExpression(chainedExpression.primaryExpression, fnState)
        val pendingSymbol = result.second
        fnState = result.first

        // no pending symbol -> done processing
        if (pendingSymbol == null) {
            return fnState
        }

        // pending symbol -> function/builtin call
        val fnName = pendingSymbol
        for (exprData in chainedExpression.data) {
            when (exprData) {
                is Arguments -> fnState = processArguments(exprData, fnState)
                is MemberAccess -> TODO()
            }
        }
        val builtin = Builtin.from(fnName)
        fnState = if (builtin != null) {
            processBuiltinCall(fnState, builtin)
        } else {
            val fnDeclaration = fnState.visibleFunctions[fnName] ?: error("function $fnName not found.")
            val fnDesc = getMethodTypeDescFrom(fnDeclaration)
            processFunctionCall(fnState, fnName, fnDesc)
        }
        return fnState
    }

    private fun processArguments(arguments: Arguments, fnState: FnState): FnState {
        var state = fnState
        for (expression in arguments.expressions) {
            state = processExpression(expression, state)
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
    private fun processPrimaryExpression(primaryExpression: PrimaryExpression, fnState: FnState): Pair<FnState, String?> {
        return when (primaryExpression) {
            is Literal -> {
                fnState.emit { loadConstant(primaryExpression.symbol.toInt()) } to null
            }

            is ExpressionIdentifier -> {
                val name = primaryExpression.identifier
                val localSlot = fnState.getLocalSlot(name)
                if (localSlot != null) {
                    fnState.emit { iload(localSlot.slot) } to null
                } else {
                    // not a local, could be builtin/function name
                    fnState to name
                }
            }

            is Expression -> {
                processExpression(primaryExpression, fnState) to null
            }
        }
    }
}
