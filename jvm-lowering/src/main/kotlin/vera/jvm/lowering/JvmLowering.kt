package vera.jvm.lowering

import arrow.core.Either
import vera.ast.Arguments
import vera.ast.BindStatement
import vera.ast.ChainedExpression
import vera.ast.Expression
import vera.ast.ExpressionIdentifier
import vera.ast.VeraFunctionDeclaration
import vera.ast.InfixOperator
import vera.ast.IntLiteral
import vera.ast.MemberAccess
import vera.ast.PrimaryExpression
import vera.ast.RebindStatement
import vera.ast.ReturnStatement
import vera.ast.VeraStatement
import vera.ast.StringLiteral
import vera.ast.VeraType
import vera.jvm.ir.IntBinaryOperation
import vera.jvm.ir.IntBinaryOperator
import vera.jvm.ir.JvmMethod
import vera.jvm.ir.JvmMethodBuilder
import vera.jvm.ir.JvmType
import vera.jvm.ir.JvmValue
import vera.jvm.ir.Return
import vera.jvm.ir.Store

import vera.jvm.ir.LocalTable.LocalName
import vera.jvm.ir.LocalTable.LocalWithName
import vera.jvm.ir.JvmParameter
import vera.shared.model.Identifier

@JvmInline private value class PendingSymbol(val text: Identifier)

private enum class Builtin(val keyword: Identifier) {

    PRINT(Identifier("print"));

    companion object {
        private val byKeyword = entries.associateBy { it.keyword }
        fun from(keyword: Identifier): Builtin? = byKeyword[keyword]
    }
}

class JvmLowering(
    private val className: Identifier,
    private val visibleFunctions: Map<Identifier, VeraFunctionDeclaration>,
) {
    private lateinit var jvmMethodBuilder: JvmMethodBuilder

    fun lowerFunction(veraFunction: VeraFunctionDeclaration): JvmMethod {
        val jvmParameters = veraFunction.parameters
            .map { veraParam -> JvmParameter(veraParam.name, mapType(veraParam.type)) }
        jvmMethodBuilder = JvmMethodBuilder(jvmParameters, mapType(veraFunction.returnType))

        val explicitReturnOnAllBranches = veraFunction.statements
            .map { processStatement(it) }
            .any()
        if (!explicitReturnOnAllBranches) {
            jvmMethodBuilder.addInstruction(Return())
        }
        return jvmMethodBuilder.build()
    }

    private fun mapType(type: VeraType): JvmType {
        return when (type) {
            VeraType.INT -> JvmType.INT
            VeraType.STRING -> JvmType.REFERENCE
            VeraType.BOOL -> JvmType.INT
            VeraType.UNIT -> JvmType.VOID
        }
    }

    /** @return `true` if this statement explicitly returns from the current function. */
    private fun processStatement(statement: VeraStatement): Boolean {
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
                val expression = statement.expression
                if (expression != null) {
                    val resultType = processExpression(expression)
                    jvmMethodBuilder.addInstruction(Return(JvmValue(mapType(resultType))))
                } else {
                    jvmMethodBuilder.addInstruction(Return())
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
        jvmMethodBuilder.addInstruction(
            Store(
                LocalWithName(LocalName(bindStatement.name), JvmValue(mapType(type)))
            )
        )
    }

    private fun processExpression(expression: Expression): VeraType {
        val mainExpression = expression.chainedExpressions.first()
        val chainType = processChainedExpression(mainExpression)
        val chainedExpressions = expression.chainedExpressions.tail

        if (expression.chainOperators.size != chainedExpressions.size) {
            error("the number of infix operators and chained expressions must be equal")
        }

        for (operatorExpr in expression.chainOperators.zip(chainedExpressions)) {
            val infixOperator = operatorExpr.first
            val chainedExpression = operatorExpr.second

            if (mapType(chainType) != JvmType.INT) error("infix operators are only defined for numeric types")
            val chainedtype = processChainedExpression(chainedExpression)
            if (chainedtype != chainType) error("all result types in a chained expression need to be the same")

            jvmMethodBuilder.addInstruction(
                IntBinaryOperation(mapOperator(infixOperator))
            )
        }
        return chainType
    }

    private fun mapOperator(operator: InfixOperator): IntBinaryOperator = when (operator) {
        InfixOperator.PLUS -> IntBinaryOperator.ADD
        InfixOperator.MINUS -> IntBinaryOperator.SUB
        InfixOperator.MUL -> IntBinaryOperator.MUL
        InfixOperator.DIV -> IntBinaryOperator.DIV
    }

    private fun processChainedExpression(chainedExpression: ChainedExpression): VeraType {
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

    private fun processArguments(arguments: Arguments): List<VeraType> {
        val types = mutableListOf<VeraType>()
        for (expression in arguments.expressions) {
            types.add(processExpression(expression))
        }
        return types
    }

    // TODO remove this and add builtins as normal predefined functions
    private fun processBuiltinCall(builtin: Builtin): VeraType {
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
                VeraType.UNIT
            }
        }
    }

    private fun processFunctionCall(fnName: String, fnType: MethodTypeDesc) {
        repeat(fnType.parameterCount()) { operandStack.pop() }
        operandStack.push(getTypeFrom(fnType.returnType()))
        return emit { invokestatic(ClassDesc.of(className), fnName, fnType) }
    }

    private fun processPrimaryExpression(primaryExpression: PrimaryExpression): Either<VeraType, PendingSymbol> {
        return when (primaryExpression) {
            is IntLiteral -> {
                operandStack.push(VeraType.INT)
                emit { loadConstant(primaryExpression.value) }
                Either.Left(VeraType.INT)
            }

            is StringLiteral -> {
                operandStack.push(VeraType.STRING)
                emit { ldc(constantPool().stringEntry(primaryExpression.value)) }
                Either.Left(VeraType.STRING)
            }

            is VeraAst.BoolLiteral -> {
                operandStack.push(VeraType.BOOL)
                if (primaryExpression == VeraAst.BoolLiteral.TRUE) emit { iconst_1() } else emit { iconst_0() }
                Either.Left(VeraType.BOOL)
            }

            is VeraAst.IfExpression -> {
                val expressionType = VeraType.STRING // TODO primaryExpression.thenBlock...

                val elseLabel = codeBuilder.newLabel()
                val endLabel = codeBuilder.newLabel()

                val conditonType = processExpression(primaryExpression.condition)
                if (conditonType != VeraType.BOOL) error("condition type must be bool")
                // a Bool is pushed and then immediately consumed by ifne / ifeq
                // a value of the expression type is pushed by the if-expression
                operandStack.push(expressionType)
                emit { if }
                Either.Left(VeraType.BOOL)
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
            VeraType.INT, VeraType.BOOL -> emit { istore(local.slot.id) }
            else -> emit { astore(local.slot.id) }
        }
    }

    private fun emitLoad(local: Locals.Local) {
        when (local.type) {
            VeraType.INT, VeraType.BOOL -> emit { iload(local.slot.id) }
            else -> emit { aload(local.slot.id) }
        }
    }
}
