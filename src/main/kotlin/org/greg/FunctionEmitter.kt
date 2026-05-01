package org.greg

import java.lang.classfile.ClassBuilder
import java.lang.classfile.CodeBuilder
import java.lang.classfile.MethodBuilder
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.CD_String
import java.lang.constant.ConstantDescs.CD_int
import java.lang.constant.ConstantDescs.CD_void
import java.lang.constant.MethodTypeDesc
import java.lang.reflect.AccessFlag

private data class LocalSlot(val slot: Int)

enum class Builtin(val keyword: String) {
    PRINT("print");

    companion object {
        private val byKeyword = entries.associateBy { it.keyword }
        fun from(keyword: String): Builtin? = byKeyword[keyword]
    }
}

class FunctionEmitter(
    private val className: String,
    private val visibleFunctions: Map<String, FunctionDeclaration>,
) {
    private var codeEmitter: CodeBuilder.() -> Unit = {}
    private var nextFreeLocalSlot: LocalSlot = LocalSlot(0)
    private val locals: MutableMap<String, LocalSlot> = mutableMapOf()

    fun processFunction(
        declaration: FunctionDeclaration,
        classEmitter: ClassBuilder.() -> Unit,
    ): ClassBuilder.() -> Unit {
        for (param in declaration.params) {
            declareLocal(param.name)
        }

        var terminates = false
        for (statement in declaration.statements) {
            terminates = processStatement(statement)
        }
        if (!terminates) {
            emit { return_() }
        }

        val fnName = declaration.name
        val fnFlags = AccessFlag.PUBLIC.mask() or AccessFlag.STATIC.mask()
        val fnDefinition: MethodBuilder.() -> Unit = { withCode(codeEmitter) }
        val fnDesc = getMethodTypeDescFrom(declaration)
        return {
            classEmitter()
            withMethod(fnName, fnDesc, fnFlags, fnDefinition)
        }
    }

    private fun getLocalSlot(name: String): LocalSlot? = locals[name]

    private fun emit(block: CodeBuilder.() -> Unit) {
        val previous = codeEmitter
        codeEmitter = { previous(); block() }
    }

    private fun getMethodTypeDescFrom(functionDeclaration: FunctionDeclaration): MethodTypeDesc {
        val paramClassDescs = functionDeclaration.params.map { param -> getClassDescFrom(param.type) }
        return MethodTypeDesc.of(getClassDescFrom(functionDeclaration.returnType), paramClassDescs)
    }

    private fun getClassDescFrom(type: Type): ClassDesc {
        return when (type) {
            Type.INT -> CD_int
            Type.STRING -> CD_String
            Type.VOID -> CD_void
        }
    }

    /** @return whether there is an explicit return statement. */
    private fun processStatement(statement: Statement): Boolean {
        return when (statement) {
            is BindStatement -> {
                processBindStatement(statement)
                false
            }

            is Expression -> {
                processExpression(statement)
                false
            }

            is ReturnStatement -> {
                if (statement.expression != null) {
                    processExpression(statement.expression)
                    emit { ireturn() }
                } else {
                    emit { return_() }
                }
                true
            }

            is RebindStatement -> TODO()
        }
    }

    private fun processBindStatement(bindStatement: BindStatement) {
        // process expression tree, put result on the stack
        processExpression(bindStatement.expression)
        // get result from stack and store it in local variable table
        return storeLocalVar(bindStatement.name)
    }

    private fun storeLocalVar(name: String) {
        val slot = declareLocal(name)
        emit { istore(slot.slot) }
    }

    /** Reserves a new slot for the name and returns it. */
    private fun declareLocal(name: String): LocalSlot {
        val slot = nextFreeLocalSlot
        locals += name to slot
        nextFreeLocalSlot = LocalSlot(slot.slot + 1)
        return slot
    }

    private fun processExpression(expression: Expression) {
        // TODO more chain links
        val firstExpr = expression.chainedExpressions.first()
        return processChainedExpression(firstExpr)
    }

    private fun processChainedExpression(chainedExpression: ChainedExpression) {
        // no pending symbol -> done processing
        val pendingSymbol = processPrimaryExpression(chainedExpression.primaryExpression) ?: return

        // pending symbol -> function/builtin call
        val fnName = pendingSymbol
        for (exprData in chainedExpression.data) {
            when (exprData) {
                is Arguments -> processArguments(exprData)
                is MemberAccess -> TODO()
            }
        }
        val builtin = Builtin.from(fnName)
        if (builtin != null) {
            processBuiltinCall(builtin)
        } else {
            val fnDeclaration = visibleFunctions[fnName] ?: error("function $fnName not found.")
            val fnDesc = getMethodTypeDescFrom(fnDeclaration)
            processFunctionCall(fnName, fnDesc)
        }
    }

    private fun processArguments(arguments: Arguments) {
        for (expression in arguments.expressions) {
            processExpression(expression)
        }
    }

    private fun processBuiltinCall(builtin: Builtin) {
        return when (builtin) {
            Builtin.PRINT -> {
                val system = ClassDesc.of("java.lang", "System")
                val printStream = ClassDesc.of("java.io", "PrintStream")
                emit {
                    getstatic(system, "out", printStream)
                    swap()
                    invokevirtual(printStream, "println", MethodTypeDesc.of(CD_void, CD_int))
                }
            }
        }
    }

    private fun processFunctionCall(fnName: String, fnType: MethodTypeDesc) {
        val fnClass = ClassDesc.of(className)
        return emit { invokestatic(fnClass, fnName, fnType) }
    }

    /** @return pending symbol if there is one. */
    private fun processPrimaryExpression(primaryExpression: PrimaryExpression): String? {
        return when (primaryExpression) {
            is Literal -> {
                emit { loadConstant(primaryExpression.symbol.toInt()) }
                null
            }

            is ExpressionIdentifier -> {
                val name = primaryExpression.identifier
                val localSlot = getLocalSlot(name)
                if (localSlot != null) {
                    emit { iload(localSlot.slot) }
                    null
                } else {
                    // not a local, could be builtin/function name
                    name
                }
            }

            is Expression -> {
                processExpression(primaryExpression)
                null
            }
        }
    }
}
