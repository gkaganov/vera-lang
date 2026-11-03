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

@JvmInline private value class PendingSymbol(val identifier: Identifier)

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
        veraFunction: VeraFunctionDeclaration,
    ): JvmMethod = JvmFunctionLoweringState(className, visibleFunctions).lower(veraFunction)
}

private class JvmFunctionLoweringState(
    private val className: Identifier,
    private val visibleFunctions: Map<Identifier, VeraFunctionDeclaration>,
) {

    private data class JvmLocalBinding(val veraName: Identifier, val slot: LocalTable.Slot, val veraType: VeraType)

    private val jvmLocalBindings = mutableMapOf<Identifier, JvmLocalBinding>()
    private lateinit var jvmMethodBuilder: JvmMethodBuilder

    fun lower(veraFunction: VeraFunctionDeclaration): JvmMethod {
        val jvmParameters = veraFunction.parameters
            .map { veraParameter -> JvmParameter(veraParameter.name, veraParameter.type.toJvmType()) }
        jvmMethodBuilder = JvmMethodBuilder(veraFunction.name, jvmParameters, veraFunction.returnType.toJvmType())

        veraFunction.parameters.forEachIndexed { index, veraParameter ->
            val jvmLocalBinding = JvmLocalBinding(veraParameter.name, LocalTable.Slot(index), veraParameter.type)
            jvmLocalBindings[jvmLocalBinding.veraName] = jvmLocalBinding
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
    private fun lowerStatement(veraStatement: VeraStatement): Boolean {
        return when (veraStatement) {
            is VeraBindStatement -> {
                lowerBindStatement(veraStatement)
                false
            }

            is VeraExpression -> {
                lowerExpression(veraStatement)
                false
            }

            is VeraReturnStatement -> {
                val veraReturnExpression = veraStatement.expression
                if (veraReturnExpression != null) {
                    val veraResultType = lowerExpression(veraReturnExpression)
                    jvmMethodBuilder.addInstruction(Return(veraResultType.toJvmType()))
                } else {
                    jvmMethodBuilder.addInstruction(Return())
                }
                true
            }

            is VeraRebindStatement -> TODO()
        }
    }

    private fun lowerBindStatement(veraBindStatement: VeraBindStatement) {
        val veraExpressionType = lowerExpression(veraBindStatement.expression)
        if (veraBindStatement.type != VeraType.UNKNOWN && veraBindStatement.type != veraExpressionType) {
            error("the type of the bind statement must either UNKNOWN (to be inferred) or the same as the containing expression")
        }
        declareLocal(veraBindStatement.name, veraExpressionType)
    }

    private fun declareLocal(veraName: Identifier, veraType: VeraType) {
        val jvmLocalBinding = JvmLocalBinding(veraName, jvmMethodBuilder.nextFreeLocalSlot, veraType)
        jvmLocalBindings[jvmLocalBinding.veraName] = jvmLocalBinding
        jvmMethodBuilder.addInstruction(
            StoreLocal(
                Local(jvmLocalBinding.slot, jvmLocalBinding.veraType.toJvmType())
            )
        )
    }

    private fun lowerExpression(veraExpression: VeraExpression): VeraType {
        return lowerPrimaryExpression(veraExpression.expression)
    }

    private fun lowerPrimaryExpression(veraPrimaryExpression: VeraPrimaryExpression): VeraType {
        return when (veraPrimaryExpression) {
            is VeraBinaryOperation -> lowerBinaryOperation(veraPrimaryExpression)
            is VeraChainedExpression -> lowerChainedExpression(veraPrimaryExpression)
            else -> tryLowerPrimaryExpression(veraPrimaryExpression).fold({ veraType -> veraType }, { error("unexpected pending symbol in primary expression") })
        }
    }

    private fun lowerBinaryOperation(veraOperation: VeraBinaryOperation): VeraType {
        when (veraOperation.operator) {
            VeraOperator.AND -> return lowerLogicalAnd(veraOperation)
            VeraOperator.OR -> return lowerLogicalOr(veraOperation)
            VeraOperator.PIPE -> return lowerForwardApplication(veraOperation)
            else -> {}
        }

        val veraLeftType = lowerPrimaryExpression(veraOperation.left)
        val veraRightType = lowerPrimaryExpression(veraOperation.right)
        validateIntOperands(veraOperation.operator, veraLeftType, veraRightType)

        return when (veraOperation.operator) {
            VeraOperator.EQUALS -> lowerComparison(ComparisonOperator.EQ)
            VeraOperator.NOT_EQUALS -> lowerComparison(ComparisonOperator.NEQ)
            VeraOperator.LESS -> lowerComparison(ComparisonOperator.LT)
            VeraOperator.GREATER -> lowerComparison(ComparisonOperator.GT)
            VeraOperator.LESS_EQ -> lowerComparison(ComparisonOperator.LTE)
            VeraOperator.GREATER_EQ -> lowerComparison(ComparisonOperator.GTE)
            VeraOperator.ADD -> lowerIntBinary(IntBinaryOperator.ADD)
            VeraOperator.SUBTRACT -> lowerIntBinary(IntBinaryOperator.SUB)
            VeraOperator.MULTIPLY -> lowerIntBinary(IntBinaryOperator.MUL)
            VeraOperator.DIVIDE -> lowerIntBinary(IntBinaryOperator.DIV)
            VeraOperator.PERCENT -> lowerIntBinary(IntBinaryOperator.REM)
            VeraOperator.AND, VeraOperator.OR, VeraOperator.PIPE -> error("unreachable")
        }
    }

    private fun lowerLogicalAnd(veraOperation: VeraBinaryOperation): VeraType {
        val jvmResultLocal = jvmMethodBuilder.allocateLocal(JvmType.BOOL)
        val jvmFalseLabel = JvmLabel()
        val jvmEndLabel = JvmLabel()

        val veraLeftType = lowerPrimaryExpression(veraOperation.left)
        requireBoolOperand(veraLeftType, veraOperation.operator)

        jvmMethodBuilder.addInstruction(CreateLabel(jvmFalseLabel))
        jvmMethodBuilder.addInstruction(IfFalseJumpTo(jvmFalseLabel))

        val veraRightType = lowerPrimaryExpression(veraOperation.right)
        requireBoolOperand(veraRightType, veraOperation.operator)
        jvmMethodBuilder.addInstruction(StoreLocal(jvmResultLocal))
        jvmMethodBuilder.addInstruction(CreateLabel(jvmEndLabel))
        jvmMethodBuilder.addInstruction(JumpTo(jvmEndLabel))

        jvmMethodBuilder.addInstruction(BindLabel(jvmFalseLabel))
        jvmMethodBuilder.addInstruction(LoadConstant(JvmType.BOOL, false))
        jvmMethodBuilder.addInstruction(StoreLocal(jvmResultLocal))

        jvmMethodBuilder.addInstruction(BindLabel(jvmEndLabel))
        jvmMethodBuilder.addInstruction(LoadLocal(jvmResultLocal))
        return VeraType.BOOL
    }

    private fun lowerLogicalOr(veraOperation: VeraBinaryOperation): VeraType {
        val jvmResultLocal = jvmMethodBuilder.allocateLocal(JvmType.BOOL)
        val jvmRightLabel = JvmLabel()
        val jvmEndLabel = JvmLabel()

        val veraLeftType = lowerPrimaryExpression(veraOperation.left)
        requireBoolOperand(veraLeftType, veraOperation.operator)

        jvmMethodBuilder.addInstruction(CreateLabel(jvmRightLabel))
        jvmMethodBuilder.addInstruction(IfFalseJumpTo(jvmRightLabel))
        jvmMethodBuilder.addInstruction(LoadConstant(JvmType.BOOL, true))
        jvmMethodBuilder.addInstruction(StoreLocal(jvmResultLocal))
        jvmMethodBuilder.addInstruction(CreateLabel(jvmEndLabel))
        jvmMethodBuilder.addInstruction(JumpTo(jvmEndLabel))

        jvmMethodBuilder.addInstruction(BindLabel(jvmRightLabel))
        val veraRightType = lowerPrimaryExpression(veraOperation.right)
        requireBoolOperand(veraRightType, veraOperation.operator)
        jvmMethodBuilder.addInstruction(StoreLocal(jvmResultLocal))

        jvmMethodBuilder.addInstruction(BindLabel(jvmEndLabel))
        jvmMethodBuilder.addInstruction(LoadLocal(jvmResultLocal))
        return VeraType.BOOL
    }

    private fun lowerForwardApplication(veraOperation: VeraBinaryOperation): VeraType {
        val (veraFunctionName, veraArguments) = when (val veraRight = veraOperation.right) {
            is VeraIdentifierAsExpression -> veraRight.identifier to emptyList()
            is VeraChainedExpression -> {
                val veraBase = veraRight.primaryExpression
                if (veraBase !is VeraIdentifierAsExpression) {
                    error("right-hand side of '|' must be a function name")
                }
                val veraArgumentLists = veraRight.data.map { veraChainedData ->
                    when (veraChainedData) {
                        is VeraArguments -> veraChainedData
                        is VeraMemberAccess -> error("right-hand side of '|' must not contain member access")
                    }
                }
                if (veraArgumentLists.size > 1) {
                    error("right-hand side of '|' must contain at most one argument list")
                }
                veraBase.identifier to (veraArgumentLists.firstOrNull()?.expressions ?: emptyList())
            }
            else -> error("right-hand side of '|' must be a function name")
        }

        val argumentTypes = mutableListOf<VeraType>()
        argumentTypes += lowerPrimaryExpression(veraOperation.left)
        for (veraArgument in veraArguments) {
            argumentTypes += lowerPrimaryExpression(veraArgument)
        }

        return lowerFunctionCall(veraFunctionName, argumentTypes)
    }

    private fun requireBoolOperand(veraType: VeraType, veraOperator: VeraOperator) {
        if (veraType != VeraType.BOOL) {
            error("operator '${veraOperator.symbol}' is only defined for Bool operands, got $veraType")
        }
    }

    private fun validateIntOperands(veraOperator: VeraOperator, veraLeftType: VeraType, veraRightType: VeraType) {
        if (veraLeftType != veraRightType) {
            error("operator '${veraOperator.symbol}' requires both operands to have the same type, got $veraLeftType and $veraRightType")
        }
        if (veraLeftType != VeraType.INT) {
            error("operator '${veraOperator.symbol}' is only defined for Int operands, got $veraLeftType")
        }
    }

    private fun lowerComparison(jvmOperator: ComparisonOperator): VeraType {
        jvmMethodBuilder.addInstruction(ComparisonOperation(jvmOperator, JvmType.INT))
        return VeraType.BOOL
    }

    private fun lowerIntBinary(jvmOperator: IntBinaryOperator): VeraType {
        jvmMethodBuilder.addInstruction(IntBinaryOperation(jvmOperator))
        return VeraType.INT
    }

    private fun lowerChainedExpression(veraChainedExpression: VeraChainedExpression): VeraType {
        return when (val resolution = tryLowerPrimaryExpression(veraChainedExpression.primaryExpression)) {
            is Left -> {
                // expression fully resolved (literal, local, etc.)
                if (veraChainedExpression.data.isNotEmpty()) {
                    error("member access and call chains on resolved expressions are not supported yet")
                }
                resolution.value
            }
            is Right -> {
                // pending symbol -> function/builtin call
                val veraFunctionName = resolution.value.identifier
                val argumentTypes = mutableListOf<VeraType>()
                for (veraChainedData in veraChainedExpression.data) {
                    when (veraChainedData) {
                        is VeraArguments -> argumentTypes.addAll(lowerArguments(veraChainedData))
                        is VeraMemberAccess -> TODO()
                    }
                }
                lowerFunctionCall(veraFunctionName, argumentTypes)
            }
        }
    }

    private fun lowerFunctionCall(veraFunctionName: Identifier, argumentTypes: List<VeraType>): VeraType {
        val builtin = Builtin.from(veraFunctionName)
        if (builtin != null) {
            return lowerBuiltinCall(builtin, argumentTypes)
        }

        val veraFunctionDeclaration = visibleFunctions[veraFunctionName]
            ?: error("function ${veraFunctionName.value} not found.")
        jvmMethodBuilder.addInstruction(
            Invokestatic(
                className,
                veraFunctionDeclaration.name,
                JvmMethodSignature(
                    veraFunctionDeclaration.parameters.map { veraParameter ->
                        JvmParameter(veraParameter.name, veraParameter.type.toJvmType())
                    },
                    veraFunctionDeclaration.returnType.toJvmType()
                )
            )
        )
        return veraFunctionDeclaration.returnType
    }

    private fun lowerArguments(veraArguments: VeraArguments): List<VeraType> {
        val argumentTypes = mutableListOf<VeraType>()
        for (veraPrimaryExpression in veraArguments.expressions) {
            argumentTypes.add(lowerPrimaryExpression(veraPrimaryExpression))
        }
        return argumentTypes
    }

    private fun lowerBuiltinCall(builtin: Builtin, argumentTypes: List<VeraType>): VeraType {
        return when (builtin) {
            Builtin.PRINT -> {
                if (argumentTypes.size != 1) error("print requires exactly 1 argument")
                jvmMethodBuilder.addInstruction(Print(argumentTypes.first().toJvmType()))
                VeraType.UNIT
            }
        }
    }

    private fun tryLowerPrimaryExpression(veraPrimaryExpression: VeraPrimaryExpression): Either<VeraType, PendingSymbol> {
        return when (veraPrimaryExpression) {
            is VeraIntLiteral -> {
                jvmMethodBuilder.addInstruction(
                    LoadConstant(JvmType.INT, veraPrimaryExpression.value)
                )
                Left(VeraType.INT)
            }

            is VeraStringLiteral -> {
                jvmMethodBuilder.addInstruction(
                    LoadConstant(JvmType.STRING, veraPrimaryExpression.value)
                )
                Left(VeraType.STRING)
            }

            is VeraBoolLiteral -> {
                jvmMethodBuilder.addInstruction(
                    LoadConstant(JvmType.BOOL, veraPrimaryExpression == VeraBoolLiteral.TRUE)
                )
                Left(VeraType.BOOL)
            }

            is VeraIfExpression -> {
                val veraConditionType = lowerPrimaryExpression(veraPrimaryExpression.condition)
                if (veraConditionType != VeraType.BOOL) error("conditions must result in a bool")

                val jvmElseLabel = JvmLabel()
                jvmMethodBuilder.addInstruction(CreateLabel(jvmElseLabel))
                jvmMethodBuilder.addInstruction(IfFalseJumpTo(jvmElseLabel))
                veraPrimaryExpression.thenBlock.forEach { lowerStatement(it) }

                if (veraPrimaryExpression.elseBlock.isEmpty()) {
                    jvmMethodBuilder.addInstruction(BindLabel(jvmElseLabel))
                } else {
                    // do not fall through
                    val jvmEndLabel = JvmLabel()
                    jvmMethodBuilder.addInstruction(CreateLabel(jvmEndLabel))
                    jvmMethodBuilder.addInstruction(JumpTo(jvmEndLabel))

                    jvmMethodBuilder.addInstruction(BindLabel(jvmElseLabel))
                    veraPrimaryExpression.elseBlock.forEach { lowerStatement(it) }

                    jvmMethodBuilder.addInstruction(BindLabel(jvmEndLabel))
                }
                Left(VeraType.UNIT)
            }

            is VeraIdentifierAsExpression -> {
                val veraIdentifier = veraPrimaryExpression.identifier
                val jvmLocalBinding = jvmLocalBindings[veraIdentifier]
                if (jvmLocalBinding != null) {
                    jvmMethodBuilder.addInstruction(
                        LoadLocal(
                            Local(jvmLocalBinding.slot, jvmLocalBinding.veraType.toJvmType())
                        )
                    )
                    Left(jvmLocalBinding.veraType)
                } else {
                    // not a local -> builtin/function name
                    Right(PendingSymbol(veraIdentifier))
                }
            }

            is VeraBinaryOperation -> {
                Left(lowerBinaryOperation(veraPrimaryExpression))
            }

            is VeraChainedExpression -> {
                // chained expressions are handled by lowerChainedExpression
                val veraType = lowerChainedExpression(veraPrimaryExpression)
                Left(veraType)
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
