package org.greg

import arrow.core.Either
import org.greg.VeraAst.Arguments
import org.greg.VeraAst.BindStatement
import org.greg.VeraAst.ChainedExpression
import org.greg.VeraAst.Expression
import org.greg.VeraAst.ExpressionIdentifier
import org.greg.VeraAst.FunctionDeclaration
import org.greg.VeraAst.IntLiteral
import org.greg.VeraAst.MemberAccess
import org.greg.VeraAst.PrimaryExpression
import org.greg.VeraAst.RebindStatement
import org.greg.VeraAst.ReturnStatement
import org.greg.VeraAst.StringLiteral
import org.greg.VeraAst.Type
import java.lang.classfile.ClassBuilder
import java.lang.classfile.CodeBuilder
import java.lang.classfile.MethodBuilder
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.CD_String
import java.lang.constant.ConstantDescs.CD_int
import java.lang.constant.ConstantDescs.CD_void
import java.lang.constant.MethodTypeDesc
import java.lang.reflect.AccessFlag

@JvmInline private value class PendingSymbol(val text: String)

private enum class Builtin(val keyword: String) {

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
    private val locals = Locals()
    private val operandStack = OperandStack()

    private var codeEmitter: CodeBuilder.() -> Unit = {}

    fun emitFunction(
        declaration: FunctionDeclaration,
        classEmitter: ClassBuilder.() -> Unit,
    ): ClassBuilder.() -> Unit {
        for (param in declaration.params) {
            locals.declare(
                Locals.Name(param.name),
                param.type,
            )
        }

        var terminates = false
        for (statement in declaration.statements) {
            terminates = processStatement(statement)
        }
        if (!terminates) {
            emit { return_() }
        }

        val fnFlags = AccessFlag.PUBLIC.mask() or AccessFlag.STATIC.mask()
        val fnDefinition: MethodBuilder.() -> Unit = { withCode(codeEmitter) }
        val fnDesc = getMethodTypeDescFrom(declaration)
        return {
            classEmitter()
            withMethod(declaration.name, fnDesc, fnFlags, fnDefinition)
        }
    }

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
            Type.UNIT -> CD_void
        }
    }

    // TODO this should not exist, always pass around Types, not ClassDesc
    private fun getTypeFrom(classDesc: ClassDesc): Type {
        return when (classDesc) {
            CD_int -> Type.INT
            CD_String -> Type.STRING
            CD_void -> Type.UNIT
            else -> {
                error("No known Type for ClassDesc $classDesc")
            }
        }
    }

    /** @return `true` if this statement explicitly returns from the current function. */
    private fun processStatement(statement: VeraAst.Statement): Boolean {
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
        val type = processExpression(bindStatement.expression)
        // get result from stack and store it in local variable table
        return storeLocalVar(bindStatement.name, type)
    }

    private fun storeLocalVar(name: String, type: Type) {
        val newLocal = locals.declare(Locals.Name(name), (type))
        // store operations pop one from the operand stack
        operandStack.pop()
        emitStore(newLocal)
    }

    private fun processExpression(expression: Expression): Type {
        val mainExpression = expression.chainedExpressions.first()
        val chainType = processChainedExpression(mainExpression)
        val chainedExpressions = expression.chainedExpressions.tail

        if (expression.chainOperators.size != chainedExpressions.size) {
            error("the number of infix operators and chained expressions must be equal")
        }

        for (operatorExpr in expression.chainOperators.zip(chainedExpressions)) {
            val chainedtype = processChainedExpression(operatorExpr.second)
            if (chainedtype != chainType) error("all result types in a chained expression need to be the same")

            // infix operations always pop 2 and push 1
            val operandtypes = operandStack.pop(2)
            if (operandtypes.any { type -> type != chainType }) error("the consumed values on the operand stack must be of the same type as the chain type")
            operandStack.push(chainType)

            emitOperator(operatorExpr.first)
        }
        return chainType
    }

    private fun emitOperator(operator: VeraAst.InfixOperator) {
        when (operator) {
            VeraAst.InfixOperator.PLUS -> emit { iadd() }
            VeraAst.InfixOperator.MINUS -> emit { isub() }
            VeraAst.InfixOperator.MUL -> emit { imul() }
            VeraAst.InfixOperator.DIV -> emit { idiv() }
        }
    }

    private fun processChainedExpression(chainedExpression: ChainedExpression): Type {
        // no pending symbol -> done processing
        val expressionResult = processPrimaryExpression(chainedExpression.primaryExpression)

        return expressionResult.fold(ifLeft = {
            return it
        }, ifRight = {
            // pending symbol -> function/builtin call
            val fnName = it.text
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
                getTypeFrom(fnDesc.returnType())
            }

        })

    }

    private fun processArguments(arguments: Arguments): List<Type> {
        val types = mutableListOf<Type>()
        for (expression in arguments.expressions) {
            types.add(processExpression(expression))
        }
        return types
    }

    // TODO remove this and add builtins as normal predefined functions
    private fun processBuiltinCall(builtin: Builtin): Type {
        return when (builtin) {
            Builtin.PRINT -> {
                val system = ClassDesc.of("java.lang", "System")
                val printStream = ClassDesc.of("java.io", "PrintStream")
                val stackValueType = operandStack.pop()
                val stackValueTypeDesc = getClassDescFrom(stackValueType)
                emit {
                    getstatic(system, "out", printStream)
                    swap()
                    invokevirtual(
                        printStream,
                        "println",
                        MethodTypeDesc.of(CD_void, stackValueTypeDesc)
                    )
                }
                Type.UNIT
            }
        }
    }

    private fun processFunctionCall(fnName: String, fnType: MethodTypeDesc) {
        repeat(fnType.parameterCount()) { operandStack.pop() }
        operandStack.push(getTypeFrom(fnType.returnType()))
        return emit { invokestatic(ClassDesc.of(className), fnName, fnType) }
    }

    private fun processPrimaryExpression(primaryExpression: PrimaryExpression): Either<Type, PendingSymbol> {
        return when (primaryExpression) {
            is IntLiteral -> {
                operandStack.push(Type.INT)
                emit { loadConstant(primaryExpression.value) }
                Either.Left(Type.INT)
            }

            is StringLiteral -> {
                operandStack.push(Type.STRING)
                emit { ldc(constantPool().stringEntry(primaryExpression.value)) }
                Either.Left(Type.STRING)
            }

            is ExpressionIdentifier -> {
                val name = primaryExpression.identifier
                // is it a local?
                val local = locals.getLocal(name)
                if (local != null) {
                    operandStack.push(local.type)
                    emitLoad(local)
                    Either.Left(local.type)
                } else {
                    // not a local, could be builtin/function name
                    Either.Right(PendingSymbol(name))
                }
            }

            is Expression -> {
                val type = processExpression(primaryExpression)
                Either.Left(type)
            }
        }
    }

    private fun emitStore(local: Locals.Local) {
        when (local.type) {
            Type.INT -> emit { istore(local.slot.id) }
            else -> emit { astore(local.slot.id) }
        }
    }

    private fun emitLoad(local: Locals.Local) {
        when (local.type) {
            Type.INT -> emit { iload(local.slot.id) }
            else -> emit { aload(local.slot.id) }
        }
    }
}
