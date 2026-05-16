package vera.jvm.lowering

import org.greg.Locals
import vera.ast.Arguments
import vera.ast.BindStatement
import vera.ast.ChainedExpression
import vera.ast.Expression
import vera.ast.ExpressionIdentifier
import vera.ast.FunctionDeclaration
import vera.ast.IntLiteral
import vera.ast.MemberAccess
import vera.ast.PrimaryExpression
import vera.ast.RebindStatement
import vera.ast.ReturnStatement
import vera.ast.StringLiteral
import vera.jvm.ir.JvmMethod

@JvmInline private value class PendingSymbol(val text: String)

private enum class Builtin(val keyword: String) {

    PRINT("print");

    companion object {
        private val byKeyword = entries.associateBy { it.keyword }
        fun from(keyword: String): Builtin? = byKeyword[keyword]
    }
}

class JvmLowering(
    private val className: String,
    private val visibleFunctions: Map<String, FunctionDeclaration>,
) {
    fun lowerFunction(function: FunctionDeclaration): JvmMethod {
        val jvmMethod = JvmMethod(function.params.map { p -> p.type }, function.returnType)

        val terminates = function.statements
            .map { processStatement(it) }
            .any()
        if (!terminates) jvmMethod

        val fnFlags = AccessFlag.PUBLIC.mask() or AccessFlag.STATIC.mask()
        val fnDefinition: MethodBuilder.() -> Unit = { withCode { codeBuilder } }
        val fnDesc = getMethodTypeDescFrom(function)
        // TODO create internal representation of method desc and return it here
        return classBuilder.withMethod(function.name, fnDesc, fnFlags, fnDefinition)
    }

    private fun getMethodTypeDescFrom(functionDeclaration: FunctionDeclaration): MethodTypeDesc {
        val paramClassDescs = functionDeclaration.params.map { param -> getClassDescFrom(param.type) }
        return MethodTypeDesc.of(getClassDescFrom(functionDeclaration.returnType), paramClassDescs)
    }

    private fun getClassDescFrom(type: Type): ClassDesc {
        return when (type) {
            Type.INT -> CD_int
            Type.STRING -> CD_String
            Type.BOOL -> CD_boolean
            Type.UNIT -> CD_void
        }
    }

    // TODO this should not exist, always pass around Types, not ClassDesc
    private fun getTypeFrom(classDesc: ClassDesc): Type {
        return when (classDesc) {
            CD_int -> Type.INT
            CD_String -> Type.STRING
            CD_boolean -> Type.STRING
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

            is VeraAst.BoolLiteral -> {
                operandStack.push(Type.BOOL)
                if (primaryExpression == VeraAst.BoolLiteral.TRUE) emit { iconst_1() } else emit { iconst_0() }
                Either.Left(Type.BOOL)
            }

            is VeraAst.IfExpression -> {
                val expressionType = Type.STRING // TODO primaryExpression.thenBlock...

                val elseLabel = codeBuilder.newLabel()
                val endLabel = codeBuilder.newLabel()

                val conditonType = processExpression(primaryExpression.condition)
                if (conditonType != Type.BOOL) error("condition type must be bool")
                // a Bool is pushed and then immediately consumed by ifne / ifeq
                // a value of the expression type is pushed by the if-expression
                operandStack.push(expressionType)
                emit { if }
                Either.Left(Type.BOOL)
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
            Type.INT, Type.BOOL -> emit { istore(local.slot.id) }
            else -> emit { astore(local.slot.id) }
        }
    }

    private fun emitLoad(local: Locals.Local) {
        when (local.type) {
            Type.INT, Type.BOOL -> emit { iload(local.slot.id) }
            else -> emit { aload(local.slot.id) }
        }
    }
}
