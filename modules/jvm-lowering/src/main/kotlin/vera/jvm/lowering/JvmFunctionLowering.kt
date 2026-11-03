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
import vera.ast.VeraWhileStatement
import vera.jvm.ir.ComparisonOperator
import vera.jvm.ir.IntBinaryOperator
import vera.jvm.ir.JvmBuiltin
import vera.jvm.ir.JvmBuiltinCall
import vera.jvm.ir.JvmBoolLiteral
import vera.jvm.ir.JvmComparison
import vera.jvm.ir.JvmExpression
import vera.jvm.ir.JvmExpressionStatement
import vera.jvm.ir.JvmIfExpression
import vera.jvm.ir.JvmIntBinary
import vera.jvm.ir.JvmIntLiteral
import vera.jvm.ir.JvmLocal
import vera.jvm.ir.JvmLocalRead
import vera.jvm.ir.JvmLogicalAnd
import vera.jvm.ir.JvmLogicalOr
import vera.jvm.ir.JvmMethod
import vera.jvm.ir.JvmMethodSignature
import vera.jvm.ir.JvmParameter
import vera.jvm.ir.JvmRebind
import vera.jvm.ir.JvmReturn
import vera.jvm.ir.JvmStaticCall
import vera.jvm.ir.JvmStatement
import vera.jvm.ir.JvmStringLiteral
import vera.jvm.ir.JvmType
import vera.jvm.ir.JvmVarDeclaration
import vera.jvm.ir.JvmWhile
import vera.shared.model.Identifier

@JvmInline private value class PendingSymbol(val identifier: Identifier)

private enum class Builtin(val keyword: Identifier, val jvmBuiltin: JvmBuiltin) {

    PRINT(Identifier("print"), JvmBuiltin.PRINT);

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

    private data class LoweredStatement(val jvmStatement: JvmStatement, val returns: Boolean)

    private val jvmLocals = mutableMapOf<Identifier, JvmLocal>()

    fun lower(veraFunction: VeraFunctionDeclaration): JvmMethod {
        val jvmParameters = veraFunction.parameters
            .map { veraParameter -> JvmParameter(veraParameter.name, veraParameter.type.toJvmType()) }
        veraFunction.parameters.forEach { veraParameter ->
            jvmLocals[veraParameter.name] = JvmLocal(veraParameter.name, veraParameter.type.toJvmType())
        }

        val jvmStatements = mutableListOf<JvmStatement>()
        var explicitReturnOnAllBranches = false
        veraFunction.statements.forEach { veraStatement ->
            val loweredStatement = lowerStatement(veraStatement)
            jvmStatements += loweredStatement.jvmStatement
            explicitReturnOnAllBranches = explicitReturnOnAllBranches || loweredStatement.returns
        }
        if (!explicitReturnOnAllBranches) {
            jvmStatements += JvmReturn(null)
        }

        return JvmMethod(
            veraFunction.name,
            jvmParameters,
            veraFunction.returnType.toJvmType(),
            jvmStatements,
        )
    }

    /** @return the lowered statement and whether it explicitly returns from the current function. */
    private fun lowerStatement(veraStatement: VeraStatement): LoweredStatement {
        return when (veraStatement) {
            is VeraBindStatement -> {
                LoweredStatement(lowerBindStatement(veraStatement), false)
            }

            is VeraExpression -> {
                LoweredStatement(JvmExpressionStatement(lowerExpression(veraStatement)), false)
            }

            is VeraReturnStatement -> {
                val jvmReturnExpression = veraStatement.expression?.let { veraExpression -> lowerExpression(veraExpression) }
                LoweredStatement(JvmReturn(jvmReturnExpression), true)
            }

            is VeraWhileStatement -> {
                LoweredStatement(lowerWhileStatement(veraStatement), false)
            }

            is VeraRebindStatement -> {
                LoweredStatement(lowerRebindStatement(veraStatement), false)
            }
        }
    }

    private fun lowerWhileStatement(veraWhileStatement: VeraWhileStatement): JvmStatement {
        val jvmCondition = lowerPrimaryExpression(veraWhileStatement.condition)
        if (jvmCondition.type != JvmType.BOOL) error("conditions must result in a bool")
        val jvmBody = veraWhileStatement.body.map { veraStatement ->
            lowerStatement(veraStatement).jvmStatement
        }
        return JvmWhile(jvmCondition, jvmBody)
    }

    private fun lowerRebindStatement(veraRebindStatement: VeraRebindStatement): JvmStatement {
        val jvmLocal = jvmLocals[veraRebindStatement.name]
            ?: error("variable ${veraRebindStatement.name.value} is not defined")
        val jvmExpression = lowerExpression(veraRebindStatement.expression)
        if (jvmExpression.type != jvmLocal.type) {
            error("cannot rebind variable ${jvmLocal.name.value} of type ${jvmLocal.type} to ${jvmExpression.type}")
        }
        return JvmRebind(jvmLocal, jvmExpression)
    }

    private fun lowerBindStatement(veraBindStatement: VeraBindStatement): JvmStatement {
        val jvmExpression = lowerExpression(veraBindStatement.expression)
        val jvmExpressionType = jvmExpression.type
        if (veraBindStatement.type != VeraType.UNKNOWN && veraBindStatement.type.toJvmType() != jvmExpressionType) {
            error("the type of the bind statement must either UNKNOWN (to be inferred) or the same as the containing expression")
        }
        val jvmLocal = JvmLocal(veraBindStatement.name, jvmExpressionType)
        jvmLocals[jvmLocal.name] = jvmLocal
        return JvmVarDeclaration(jvmLocal, jvmExpression)
    }

    private fun lowerExpression(veraExpression: VeraExpression): JvmExpression {
        return lowerPrimaryExpression(veraExpression.expression)
    }

    private fun lowerPrimaryExpression(veraPrimaryExpression: VeraPrimaryExpression): JvmExpression {
        return when (veraPrimaryExpression) {
            is VeraBinaryOperation -> lowerBinaryOperation(veraPrimaryExpression)
            is VeraChainedExpression -> lowerChainedExpression(veraPrimaryExpression)
            else -> tryLowerPrimaryExpression(veraPrimaryExpression).fold(
                { jvmExpression -> jvmExpression },
                { error("unexpected pending symbol in primary expression") },
            )
        }
    }

    private fun lowerBinaryOperation(veraOperation: VeraBinaryOperation): JvmExpression {
        when (veraOperation.operator) {
            VeraOperator.AND -> {
                val jvmLeft = lowerPrimaryExpression(veraOperation.left)
                val jvmRight = lowerPrimaryExpression(veraOperation.right)
                requireBoolOperand(jvmLeft.type, veraOperation.operator)
                requireBoolOperand(jvmRight.type, veraOperation.operator)
                return JvmLogicalAnd(jvmLeft, jvmRight)
            }

            VeraOperator.OR -> {
                val jvmLeft = lowerPrimaryExpression(veraOperation.left)
                val jvmRight = lowerPrimaryExpression(veraOperation.right)
                requireBoolOperand(jvmLeft.type, veraOperation.operator)
                requireBoolOperand(jvmRight.type, veraOperation.operator)
                return JvmLogicalOr(jvmLeft, jvmRight)
            }

            VeraOperator.PIPE -> return lowerForwardApplication(veraOperation)
            else -> {}
        }

        val jvmLeft = lowerPrimaryExpression(veraOperation.left)
        val jvmRight = lowerPrimaryExpression(veraOperation.right)
        validateIntOperands(veraOperation.operator, jvmLeft.type, jvmRight.type)

        return when (veraOperation.operator) {
            VeraOperator.EQUALS -> JvmComparison(ComparisonOperator.EQ, jvmLeft, jvmRight)
            VeraOperator.NOT_EQUALS -> JvmComparison(ComparisonOperator.NEQ, jvmLeft, jvmRight)
            VeraOperator.LESS -> JvmComparison(ComparisonOperator.LT, jvmLeft, jvmRight)
            VeraOperator.GREATER -> JvmComparison(ComparisonOperator.GT, jvmLeft, jvmRight)
            VeraOperator.LESS_EQ -> JvmComparison(ComparisonOperator.LTE, jvmLeft, jvmRight)
            VeraOperator.GREATER_EQ -> JvmComparison(ComparisonOperator.GTE, jvmLeft, jvmRight)
            VeraOperator.ADD -> JvmIntBinary(IntBinaryOperator.ADD, jvmLeft, jvmRight)
            VeraOperator.SUBTRACT -> JvmIntBinary(IntBinaryOperator.SUB, jvmLeft, jvmRight)
            VeraOperator.MULTIPLY -> JvmIntBinary(IntBinaryOperator.MUL, jvmLeft, jvmRight)
            VeraOperator.DIVIDE -> JvmIntBinary(IntBinaryOperator.DIV, jvmLeft, jvmRight)
            VeraOperator.PERCENT -> JvmIntBinary(IntBinaryOperator.REM, jvmLeft, jvmRight)
            VeraOperator.AND, VeraOperator.OR, VeraOperator.PIPE -> error("unreachable")
        }
    }

    private fun lowerForwardApplication(veraOperation: VeraBinaryOperation): JvmExpression {
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

        val jvmArguments = mutableListOf<JvmExpression>()
        jvmArguments += lowerPrimaryExpression(veraOperation.left)
        for (veraArgument in veraArguments) {
            jvmArguments += lowerPrimaryExpression(veraArgument)
        }

        return lowerFunctionCall(veraFunctionName, jvmArguments)
    }

    private fun requireBoolOperand(jvmType: JvmType, veraOperator: VeraOperator) {
        if (jvmType != JvmType.BOOL) {
            error("operator '${veraOperator.symbol}' is only defined for Bool operands, got $jvmType")
        }
    }

    private fun validateIntOperands(veraOperator: VeraOperator, jvmLeftType: JvmType, jvmRightType: JvmType) {
        if (jvmLeftType != jvmRightType) {
            error("operator '${veraOperator.symbol}' requires both operands to have the same type, got $jvmLeftType and $jvmRightType")
        }
        if (jvmLeftType != JvmType.INT) {
            error("operator '${veraOperator.symbol}' is only defined for Int operands, got $jvmLeftType")
        }
    }

    private fun lowerChainedExpression(veraChainedExpression: VeraChainedExpression): JvmExpression {
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
                val jvmArguments = mutableListOf<JvmExpression>()
                for (veraChainedData in veraChainedExpression.data) {
                    when (veraChainedData) {
                        is VeraArguments -> jvmArguments.addAll(lowerArguments(veraChainedData))
                        is VeraMemberAccess -> TODO()
                    }
                }
                lowerFunctionCall(veraFunctionName, jvmArguments)
            }
        }
    }

    private fun lowerFunctionCall(veraFunctionName: Identifier, jvmArguments: List<JvmExpression>): JvmExpression {
        val builtin = Builtin.from(veraFunctionName)
        if (builtin != null) {
            return lowerBuiltinCall(builtin, jvmArguments)
        }

        val veraFunctionDeclaration = visibleFunctions[veraFunctionName]
            ?: error("function ${veraFunctionName.value} not found.")
        val jvmSignature = JvmMethodSignature(
            veraFunctionDeclaration.parameters.map { veraParameter ->
                JvmParameter(veraParameter.name, veraParameter.type.toJvmType())
            },
            veraFunctionDeclaration.returnType.toJvmType(),
        )
        return JvmStaticCall(
            className,
            veraFunctionDeclaration.name,
            jvmArguments,
            jvmSignature,
        )
    }

    private fun lowerArguments(veraArguments: VeraArguments): List<JvmExpression> {
        val jvmArguments = mutableListOf<JvmExpression>()
        for (veraPrimaryExpression in veraArguments.expressions) {
            jvmArguments.add(lowerPrimaryExpression(veraPrimaryExpression))
        }
        return jvmArguments
    }

    private fun lowerBuiltinCall(builtin: Builtin, jvmArguments: List<JvmExpression>): JvmExpression {
        return when (builtin.jvmBuiltin) {
            JvmBuiltin.PRINT -> {
                if (jvmArguments.size != 1) error("print requires exactly 1 argument")
                JvmBuiltinCall(JvmBuiltin.PRINT, jvmArguments)
            }
        }
    }

    private fun tryLowerPrimaryExpression(veraPrimaryExpression: VeraPrimaryExpression): Either<JvmExpression, PendingSymbol> {
        return when (veraPrimaryExpression) {
            is VeraIntLiteral -> {
                Left(JvmIntLiteral(veraPrimaryExpression.value))
            }

            is VeraStringLiteral -> {
                Left(JvmStringLiteral(veraPrimaryExpression.value))
            }

            is VeraBoolLiteral -> {
                Left(JvmBoolLiteral(veraPrimaryExpression == VeraBoolLiteral.TRUE))
            }

            is VeraIfExpression -> {
                val jvmCondition = lowerPrimaryExpression(veraPrimaryExpression.condition)
                if (jvmCondition.type != JvmType.BOOL) error("conditions must result in a bool")

                val jvmThenBlock = veraPrimaryExpression.thenBlock.map { veraStatement ->
                    lowerStatement(veraStatement).jvmStatement
                }
                val jvmElseBlock = veraPrimaryExpression.elseBlock.map { veraStatement ->
                    lowerStatement(veraStatement).jvmStatement
                }
                Left(JvmIfExpression(jvmCondition, jvmThenBlock, jvmElseBlock))
            }

            is VeraIdentifierAsExpression -> {
                val veraIdentifier = veraPrimaryExpression.identifier
                val jvmLocal = jvmLocals[veraIdentifier]
                if (jvmLocal != null) {
                    Left(JvmLocalRead(jvmLocal))
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
                Left(lowerChainedExpression(veraPrimaryExpression))
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
