package vera.jvm.lowering

import arrow.core.Either
import arrow.core.Either.Left
import arrow.core.Either.Right
import vera.ast.VeraArguments
import vera.ast.VeraBinaryOperation
import vera.ast.VeraBindStatement
import vera.ast.VeraBoolLiteral
import vera.ast.VeraChainedExpression
import vera.ast.VeraExpression
import vera.ast.VeraFunctionDeclaration
import vera.ast.VeraIdentifierAsExpression
import vera.ast.VeraIfExpression
import vera.ast.VeraIntLiteral
import vera.ast.VeraMemberAccess
import vera.ast.VeraOperator
import vera.ast.VeraPrimaryExpression
import vera.ast.VeraRebindStatement
import vera.ast.VeraReturnStatement
import vera.ast.VeraStatement
import vera.ast.VeraStringLiteral
import vera.ast.VeraType
import vera.jvm.ir.BindLabel
import vera.jvm.ir.ComparisonOperator
import vera.jvm.ir.ComparisonOperation
import vera.jvm.ir.CreateLabel
import vera.jvm.ir.IfFalseJumpTo
import vera.jvm.ir.IntBinaryOperation
import vera.jvm.ir.IntBinaryOperator
import vera.jvm.ir.Invokestatic
import vera.jvm.ir.JumpTo
import vera.jvm.ir.JvmLabel
import vera.jvm.ir.JvmMethod
import vera.jvm.ir.JvmMethodBuilder
import vera.jvm.ir.JvmMethodSignature
import vera.jvm.ir.JvmParameter
import vera.jvm.ir.JvmType
import vera.jvm.ir.LoadConstant
import vera.jvm.ir.LoadLocal
import vera.jvm.ir.LocalTable
import vera.jvm.ir.LocalTable.Local
import vera.jvm.ir.Print
import vera.jvm.ir.Return
import vera.jvm.ir.StoreLocal
import vera.shared.model.Identifier

@JvmInline private value class PendingSymbol(val text: Identifier)

private enum class Builtin(val keyword: Identifier) {

    PRINT(Identifier("print"));

    companion object {
        private val byKeyword = entries.associateBy { it.keyword }
        fun from(keyword: Identifier): Builtin? = byKeyword[keyword]
    }
}

internal object JvmFunctionLowering {
    fun lower(
        className: Identifier,
        visibleFunctions: Map<Identifier, VeraFunctionDeclaration>,
        function: VeraFunctionDeclaration,
    ): JvmMethod = FunctionLoweringState(className, visibleFunctions).lower(function)
}

/** Mutable state for lowering a single function. */
private class FunctionLoweringState(
    private val className: Identifier,
    private val visibleFunctions: Map<Identifier, VeraFunctionDeclaration>,
) {

    private data class VeraLocal(val name: Identifier, val slot: LocalTable.Slot, val veraType: VeraType)

    private val veraLocals = mutableMapOf<Identifier, VeraLocal>()
    private lateinit var jvmMethodBuilder: JvmMethodBuilder

    fun lower(veraFunction: VeraFunctionDeclaration): JvmMethod {
        val jvmParameters = veraFunction.parameters
            .map { veraParam -> JvmParameter(veraParam.name, veraParam.type.toJvmType()) }
        jvmMethodBuilder = JvmMethodBuilder(veraFunction.name, jvmParameters, veraFunction.returnType.toJvmType())

        veraFunction.parameters.forEachIndexed { index, veraParam ->
            val newLocal = VeraLocal(veraParam.name, LocalTable.Slot(index), veraParam.type)
            veraLocals[newLocal.name] = newLocal
        }

        val explicitReturnOnAllBranches = veraFunction.statements
            .map { lowerStatement(it) }
            .any { it }
        if (!explicitReturnOnAllBranches) {
            jvmMethodBuilder.addInstruction(Return())
        }
        return jvmMethodBuilder.build()
    }

    /** @return `true` if this statement explicitly returns from the current function. */
    private fun lowerStatement(statement: VeraStatement): Boolean {
        return when (statement) {
            is VeraBindStatement -> {
                lowerBindStatement(statement)
                false
            }

            is VeraExpression -> {
                lowerExpression(statement)
                false
            }

            is VeraReturnStatement -> {
                val expression = statement.expression
                if (expression != null) {
                    val resultType = lowerExpression(expression)
                    jvmMethodBuilder.addInstruction(Return(resultType.toJvmType()))
                } else {
                    jvmMethodBuilder.addInstruction(Return())
                }
                true
            }

            is VeraRebindStatement -> TODO()
        }
    }

    private fun lowerBindStatement(bindStatement: VeraBindStatement) {
        val type = lowerExpression(bindStatement.expression)
        if (bindStatement.type != VeraType.UNKNOWN && bindStatement.type != type) {
            error("the type of the bind statement must either UNKNOWN (to be inferred) or the same as the containing expression")
        }
        declareLocal(bindStatement.name, type)
    }

    private fun declareLocal(name: Identifier, type: VeraType) {
        val newLocal = VeraLocal(name, jvmMethodBuilder.nextFreeLocalSlot, type)
        veraLocals[newLocal.name] = newLocal
        jvmMethodBuilder.addInstruction(
            StoreLocal(
                Local(newLocal.slot, newLocal.veraType.toJvmType())
            )
        )
    }

    private fun lowerExpression(expression: VeraExpression): VeraType {
        return lowerPrimaryExpression(expression.expression)
    }

    private fun lowerPrimaryExpression(primary: VeraPrimaryExpression): VeraType {
        return when (primary) {
            is VeraBinaryOperation -> lowerBinaryOperation(primary)
            is VeraChainedExpression -> lowerChainedExpression(primary)
            else -> tryResolvePrimaryExpression(primary).fold({ type -> type }, { error("unexpected pending symbol in primary expression") })
        }
    }

    private fun lowerBinaryOperation(operation: VeraBinaryOperation): VeraType {
        when (operation.operator) {
            VeraOperator.AND -> return lowerLogicalAnd(operation)
            VeraOperator.OR -> return lowerLogicalOr(operation)
            VeraOperator.PIPE -> return lowerForwardApplication(operation)
            else -> {}
        }

        val leftType = lowerPrimaryExpression(operation.left)
        val rightType = lowerPrimaryExpression(operation.right)
        validateIntOperands(operation.operator, leftType, rightType)

        return when (operation.operator) {
            VeraOperator.EQUALS -> emitComparison(ComparisonOperator.EQ)
            VeraOperator.NOT_EQUALS -> emitComparison(ComparisonOperator.NEQ)
            VeraOperator.LESS -> emitComparison(ComparisonOperator.LT)
            VeraOperator.GREATER -> emitComparison(ComparisonOperator.GT)
            VeraOperator.LESS_EQ -> emitComparison(ComparisonOperator.LTE)
            VeraOperator.GREATER_EQ -> emitComparison(ComparisonOperator.GTE)
            VeraOperator.ADD -> emitIntBinary(IntBinaryOperator.ADD)
            VeraOperator.SUBTRACT -> emitIntBinary(IntBinaryOperator.SUB)
            VeraOperator.MULTIPLY -> emitIntBinary(IntBinaryOperator.MUL)
            VeraOperator.DIVIDE -> emitIntBinary(IntBinaryOperator.DIV)
            VeraOperator.PERCENT -> emitIntBinary(IntBinaryOperator.REM)
            VeraOperator.AND, VeraOperator.OR, VeraOperator.PIPE -> error("unreachable")
        }
    }

    private fun lowerLogicalAnd(operation: VeraBinaryOperation): VeraType {
        val result = jvmMethodBuilder.allocateLocal(JvmType.BOOL)
        val falseLabel = JvmLabel()
        val endLabel = JvmLabel()

        val leftType = lowerPrimaryExpression(operation.left)
        requireBoolOperand(leftType, operation.operator)

        jvmMethodBuilder.addInstruction(CreateLabel(falseLabel))
        jvmMethodBuilder.addInstruction(IfFalseJumpTo(falseLabel))

        val rightType = lowerPrimaryExpression(operation.right)
        requireBoolOperand(rightType, operation.operator)
        jvmMethodBuilder.addInstruction(StoreLocal(result))
        jvmMethodBuilder.addInstruction(CreateLabel(endLabel))
        jvmMethodBuilder.addInstruction(JumpTo(endLabel))

        jvmMethodBuilder.addInstruction(BindLabel(falseLabel))
        jvmMethodBuilder.addInstruction(LoadConstant(JvmType.BOOL, false))
        jvmMethodBuilder.addInstruction(StoreLocal(result))

        jvmMethodBuilder.addInstruction(BindLabel(endLabel))
        jvmMethodBuilder.addInstruction(LoadLocal(result))
        return VeraType.BOOL
    }

    private fun lowerLogicalOr(operation: VeraBinaryOperation): VeraType {
        val result = jvmMethodBuilder.allocateLocal(JvmType.BOOL)
        val rightLabel = JvmLabel()
        val endLabel = JvmLabel()

        val leftType = lowerPrimaryExpression(operation.left)
        requireBoolOperand(leftType, operation.operator)

        jvmMethodBuilder.addInstruction(CreateLabel(rightLabel))
        jvmMethodBuilder.addInstruction(IfFalseJumpTo(rightLabel))
        jvmMethodBuilder.addInstruction(LoadConstant(JvmType.BOOL, true))
        jvmMethodBuilder.addInstruction(StoreLocal(result))
        jvmMethodBuilder.addInstruction(CreateLabel(endLabel))
        jvmMethodBuilder.addInstruction(JumpTo(endLabel))

        jvmMethodBuilder.addInstruction(BindLabel(rightLabel))
        val rightType = lowerPrimaryExpression(operation.right)
        requireBoolOperand(rightType, operation.operator)
        jvmMethodBuilder.addInstruction(StoreLocal(result))

        jvmMethodBuilder.addInstruction(BindLabel(endLabel))
        jvmMethodBuilder.addInstruction(LoadLocal(result))
        return VeraType.BOOL
    }

    private fun lowerForwardApplication(operation: VeraBinaryOperation): VeraType {
        val (functionName, arguments) = when (val right = operation.right) {
            is VeraIdentifierAsExpression -> right.identifier to emptyList()
            is VeraChainedExpression -> {
                val base = right.primaryExpression
                if (base !is VeraIdentifierAsExpression) {
                    error("right-hand side of '|' must be a function name")
                }
                val argumentLists = right.data.map { data ->
                    when (data) {
                        is VeraArguments -> data
                        is VeraMemberAccess -> error("right-hand side of '|' must not contain member access")
                    }
                }
                if (argumentLists.size > 1) {
                    error("right-hand side of '|' must contain at most one argument list")
                }
                base.identifier to (argumentLists.firstOrNull()?.expressions ?: emptyList())
            }
            else -> error("right-hand side of '|' must be a function name")
        }

        val argumentTypes = mutableListOf<VeraType>()
        argumentTypes += lowerPrimaryExpression(operation.left)
        for (argument in arguments) {
            argumentTypes += lowerPrimaryExpression(argument)
        }

        return lowerFunctionInvocation(functionName, argumentTypes)
    }

    private fun requireBoolOperand(type: VeraType, operator: VeraOperator) {
        if (type != VeraType.BOOL) {
            error("operator '${operator.symbol}' is only defined for Bool operands, got $type")
        }
    }

    private fun validateIntOperands(operator: VeraOperator, leftType: VeraType, rightType: VeraType) {
        if (leftType != rightType) {
            error("operator '${operator.symbol}' requires both operands to have the same type, got $leftType and $rightType")
        }
        if (leftType != VeraType.INT) {
            error("operator '${operator.symbol}' is only defined for Int operands, got $leftType")
        }
    }

    private fun emitComparison(operator: ComparisonOperator): VeraType {
        jvmMethodBuilder.addInstruction(ComparisonOperation(operator, JvmType.INT))
        return VeraType.BOOL
    }

    private fun emitIntBinary(operator: IntBinaryOperator): VeraType {
        jvmMethodBuilder.addInstruction(IntBinaryOperation(operator))
        return VeraType.INT
    }

    private fun lowerChainedExpression(chainedExpression: VeraChainedExpression): VeraType {
        return when (val expressionResult = tryResolvePrimaryExpression(chainedExpression.primaryExpression)) {
            is Left -> {
                // expression fully resolved (literal, local, etc.)
                if (chainedExpression.data.isNotEmpty()) {
                    error("member access and call chains on resolved expressions are not supported yet")
                }
                expressionResult.value
            }
            is Right -> {
                // pending symbol -> function/builtin call
                val fnName = expressionResult.value.text
                val arguments = mutableListOf<VeraType>()
                for (exprData in chainedExpression.data) {
                    when (exprData) {
                        is VeraArguments -> arguments.addAll(lowerArguments(exprData))
                        is VeraMemberAccess -> TODO()
                    }
                }
                lowerFunctionInvocation(fnName, arguments)
            }
        }
    }

    private fun lowerFunctionInvocation(fnName: Identifier, arguments: List<VeraType>): VeraType {
        val builtin = Builtin.from(fnName)
        if (builtin != null) {
            return lowerBuiltinCall(builtin, arguments)
        }

        val fnDeclaration = visibleFunctions[fnName] ?: error("function ${fnName.value} not found.")
        jvmMethodBuilder.addInstruction(
            Invokestatic(
                className,
                fnDeclaration.name,
                JvmMethodSignature(
                    fnDeclaration.parameters.map { veraParameter -> JvmParameter(veraParameter.name, veraParameter.type.toJvmType()) },
                    fnDeclaration.returnType.toJvmType()
                )
            )
        )
        return fnDeclaration.returnType
    }

    private fun lowerArguments(arguments: VeraArguments): List<VeraType> {
        val types = mutableListOf<VeraType>()
        for (primary in arguments.expressions) {
            types.add(lowerPrimaryExpression(primary))
        }
        return types
    }

    private fun lowerBuiltinCall(builtin: Builtin, arguments: List<VeraType>): VeraType {
        return when (builtin) {
            Builtin.PRINT -> {
                if (arguments.size != 1) error("print requires exactly 1 argument")
                jvmMethodBuilder.addInstruction(Print(arguments.first().toJvmType()))
                VeraType.UNIT
            }
        }
    }

    private fun tryResolvePrimaryExpression(primaryExpression: VeraPrimaryExpression): Either<VeraType, PendingSymbol> {
        return when (primaryExpression) {
            is VeraIntLiteral -> {
                jvmMethodBuilder.addInstruction(
                    LoadConstant(JvmType.INT, primaryExpression.value)
                )
                Left(VeraType.INT)
            }

            is VeraStringLiteral -> {
                jvmMethodBuilder.addInstruction(
                    LoadConstant(JvmType.STRING, primaryExpression.value)
                )
                Left(VeraType.STRING)
            }

            is VeraBoolLiteral -> {
                jvmMethodBuilder.addInstruction(
                    LoadConstant(JvmType.BOOL, primaryExpression == VeraBoolLiteral.TRUE)
                )
                Left(VeraType.BOOL)
            }

            is VeraIfExpression -> {
                val conditionType = lowerPrimaryExpression(primaryExpression.condition)
                if (conditionType != VeraType.BOOL) error("conditions must result in a bool")

                val elseLabel = JvmLabel()
                jvmMethodBuilder.addInstruction(CreateLabel(elseLabel))
                jvmMethodBuilder.addInstruction(IfFalseJumpTo(elseLabel))
                primaryExpression.thenBlock.forEach { lowerStatement(it) }

                if (primaryExpression.elseBlock.isEmpty()) {
                    jvmMethodBuilder.addInstruction(BindLabel(elseLabel))
                } else {
                    // do not fall through
                    val endLabel = JvmLabel()
                    jvmMethodBuilder.addInstruction(CreateLabel(endLabel))
                    jvmMethodBuilder.addInstruction(JumpTo(endLabel))

                    jvmMethodBuilder.addInstruction(BindLabel(elseLabel))
                    primaryExpression.elseBlock.forEach { lowerStatement(it) }

                    jvmMethodBuilder.addInstruction(BindLabel(endLabel))
                }
                Left(VeraType.UNIT)
            }

            is VeraIdentifierAsExpression -> {
                val name = primaryExpression.identifier
                val veraLocal = veraLocals[name]
                if (veraLocal != null) {
                    jvmMethodBuilder.addInstruction(
                        LoadLocal(
                            Local(veraLocal.slot, veraLocal.veraType.toJvmType())
                        )
                    )
                    Left(veraLocal.veraType)
                } else {
                    // not a local -> builtin/function name
                    Right(PendingSymbol(name))
                }
            }

            is VeraBinaryOperation -> {
                Left(lowerBinaryOperation(primaryExpression))
            }

            is VeraChainedExpression -> {
                // chained expressions are handled by lowerChainedExpression
                val type = lowerChainedExpression(primaryExpression)
                Left(type)
            }
        }
    }

    private fun VeraType.toJvmType(): JvmType {
        return when (this) {
            VeraType.INT -> JvmType.INT
            VeraType.STRING -> JvmType.STRING
            VeraType.BOOL -> JvmType.BOOL
            VeraType.UNIT, VeraType.UNKNOWN -> JvmType.VOID
        }
    }
}
