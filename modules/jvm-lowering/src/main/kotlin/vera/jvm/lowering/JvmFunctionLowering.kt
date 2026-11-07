package vera.jvm.lowering

import vera.ast.VeraArguments
import vera.ast.VeraBinaryOperation
import vera.ast.VeraBindStatement
import vera.ast.VeraBlock
import vera.ast.VeraBoolLiteral
import vera.ast.VeraChainedExpression
import vera.ast.VeraExplicitReturnBlock
import vera.ast.VeraExpression
import vera.ast.VeraFunctionDeclaration
import vera.ast.VeraIdentifierAsExpression
import vera.ast.VeraIfExpression
import vera.ast.VeraImplicitReturnBlock
import vera.ast.VeraIntLiteral
import vera.ast.VeraMemberAccess
import vera.ast.VeraOperator
import vera.ast.VeraPrimaryExpression
import vera.ast.VeraRebindStatement
import vera.ast.VeraReturnStatement
import vera.ast.VeraStatement
import vera.ast.VeraStringLiteral
import vera.ast.VeraType
import vera.ast.VeraUnitReturnBlock
import vera.ast.VeraWhileStatement
import vera.jvm.ir.JvmComparisonOperator
import vera.jvm.ir.JvmIntBinaryOperator
import vera.jvm.ir.JvmBlock
import vera.jvm.ir.JvmBuiltin
import vera.jvm.ir.JvmBuiltinCall
import vera.jvm.ir.JvmBoolLiteral
import vera.jvm.ir.JvmComparison
import vera.jvm.ir.JvmExplicitReturnBlock
import vera.jvm.ir.JvmExpression
import vera.jvm.ir.JvmExpressionStatement
import vera.jvm.ir.JvmIfExpression
import vera.jvm.ir.JvmImplicitReturnBlock
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
import vera.jvm.ir.JvmUnitReturnBlock
import vera.jvm.ir.JvmVarDeclaration
import vera.jvm.ir.JvmWhile
import vera.shared.model.Identifier

private enum class Builtin(val keyword: Identifier, val jvmBuiltin: JvmBuiltin) {

    PRINT(Identifier("print"), JvmBuiltin.PRINT);

    companion object {
        private val byKeyword = entries.associateBy { it.keyword }
        fun from(keyword: Identifier): Builtin? = byKeyword[keyword]
    }
}

private sealed interface PrimaryResolution {
    data class Resolved(val expression: JvmExpression) : PrimaryResolution
    data class Pending(val identifier: Identifier) : PrimaryResolution
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
        veraFunction.body.statements.forEach { veraStatement ->
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
            JvmExplicitReturnBlock(jvmStatements),
        )
    }

    /** @return the lowered statement and whether it explicitly returns from the current function. */
    private fun lowerStatement(veraStatement: VeraStatement): LoweredStatement {
        return when (veraStatement) {
            is VeraBindStatement -> LoweredStatement(lowerBindStatement(veraStatement), false)

            is VeraExpression -> LoweredStatement(
                JvmExpressionStatement(lowerExpression(veraStatement)),
                false,
            )

            is VeraReturnStatement -> {
                val jvmReturnExpression = veraStatement.expression
                    ?.let { veraExpression -> lowerExpression(veraExpression) }
                LoweredStatement(JvmReturn(jvmReturnExpression), true)
            }

            is VeraWhileStatement -> LoweredStatement(lowerWhileStatement(veraStatement), false)

            is VeraRebindStatement -> LoweredStatement(lowerRebindStatement(veraStatement), false)
        }
    }

    private fun lowerWhileStatement(veraWhileStatement: VeraWhileStatement): JvmStatement {
        val jvmCondition = lowerPrimaryExpression(veraWhileStatement.condition)
        if (jvmCondition.type != JvmType.BOOL) {
            throw JvmLoweringException.ConditionNotBool(jvmCondition.type)
        }
        val jvmBody = mutableListOf<JvmStatement>()
        veraWhileStatement.body.statements.forEach { veraStatement ->
            jvmBody += lowerStatement(veraStatement).jvmStatement
        }
        return JvmWhile(jvmCondition, JvmUnitReturnBlock(jvmBody))
    }

    private fun lowerRebindStatement(veraRebindStatement: VeraRebindStatement): JvmStatement {
        val jvmLocal = jvmLocals[veraRebindStatement.name]
            ?: throw JvmLoweringException.UnknownVariable(veraRebindStatement.name)
        val jvmExpression = lowerExpression(veraRebindStatement.expression)
        if (jvmExpression.type != jvmLocal.type) {
            throw JvmLoweringException.RebindTypeMismatch(
                jvmLocal.name,
                jvmLocal.type,
                jvmExpression.type,
            )
        }
        return JvmRebind(jvmLocal, jvmExpression)
    }

    private fun lowerBindStatement(veraBindStatement: VeraBindStatement): JvmStatement {
        val jvmExpression = lowerExpression(veraBindStatement.expression)
        val jvmExpressionType = jvmExpression.type
        if (veraBindStatement.type != VeraType.UNKNOWN && veraBindStatement.type.toJvmType() != jvmExpressionType) {
            throw JvmLoweringException.BindTypeMismatch(veraBindStatement.type.name, jvmExpressionType)
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
            else -> when (val resolution = resolvePrimaryExpression(veraPrimaryExpression)) {
                is PrimaryResolution.Resolved -> resolution.expression
                is PrimaryResolution.Pending -> throw JvmLoweringException.UnexpectedPendingSymbol()
            }
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
            VeraOperator.EQUALS -> JvmComparison(JvmComparisonOperator.EQ, jvmLeft, jvmRight)
            VeraOperator.NOT_EQUALS -> JvmComparison(JvmComparisonOperator.NEQ, jvmLeft, jvmRight)
            VeraOperator.LESS -> JvmComparison(JvmComparisonOperator.LT, jvmLeft, jvmRight)
            VeraOperator.GREATER -> JvmComparison(JvmComparisonOperator.GT, jvmLeft, jvmRight)
            VeraOperator.LESS_EQ -> JvmComparison(JvmComparisonOperator.LTE, jvmLeft, jvmRight)
            VeraOperator.GREATER_EQ -> JvmComparison(JvmComparisonOperator.GTE, jvmLeft, jvmRight)
            VeraOperator.ADD -> JvmIntBinary(JvmIntBinaryOperator.ADD, jvmLeft, jvmRight)
            VeraOperator.SUBTRACT -> JvmIntBinary(JvmIntBinaryOperator.SUB, jvmLeft, jvmRight)
            VeraOperator.MULTIPLY -> JvmIntBinary(JvmIntBinaryOperator.MUL, jvmLeft, jvmRight)
            VeraOperator.DIVIDE -> JvmIntBinary(JvmIntBinaryOperator.DIV, jvmLeft, jvmRight)
            VeraOperator.PERCENT -> JvmIntBinary(JvmIntBinaryOperator.REM, jvmLeft, jvmRight)
            VeraOperator.AND, VeraOperator.OR, VeraOperator.PIPE ->
                throw JvmLoweringException.InternalInvariant("unreachable binary operator")
        }
    }

    private fun lowerForwardApplication(veraOperation: VeraBinaryOperation): JvmExpression {
        val (veraFunctionName, veraArguments) = when (val veraRight = veraOperation.right) {
            is VeraIdentifierAsExpression -> veraRight.identifier to emptyList()
            is VeraChainedExpression -> {
                val veraBase = veraRight.primaryExpression
                if (veraBase !is VeraIdentifierAsExpression) {
                    throw JvmLoweringException.InvalidForwardApplication(
                        JvmForwardApplicationIssue.RIGHT_SIDE_NOT_FUNCTION_NAME,
                    )
                }
                val veraArgumentLists = mutableListOf<VeraArguments>()
                veraRight.data.forEach { veraChainedData ->
                    when (veraChainedData) {
                        is VeraArguments -> veraArgumentLists += veraChainedData
                        is VeraMemberAccess -> throw JvmLoweringException.InvalidForwardApplication(
                            JvmForwardApplicationIssue.RIGHT_SIDE_CONTAINS_MEMBER_ACCESS,
                        )
                    }
                }
                if (veraArgumentLists.size > 1) {
                    throw JvmLoweringException.InvalidForwardApplication(
                        JvmForwardApplicationIssue.MULTIPLE_ARGUMENT_LISTS,
                    )
                }
                veraBase.identifier to (veraArgumentLists.firstOrNull()?.expressions ?: emptyList())
            }

            else -> throw JvmLoweringException.InvalidForwardApplication(
                JvmForwardApplicationIssue.RIGHT_SIDE_NOT_FUNCTION_NAME,
            )
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
            throw JvmLoweringException.BoolOperandsRequired(veraOperator.symbol, jvmType)
        }
    }

    private fun validateIntOperands(
        veraOperator: VeraOperator,
        jvmLeftType: JvmType,
        jvmRightType: JvmType,
    ) {
        if (jvmLeftType != jvmRightType) {
            throw JvmLoweringException.OperandTypesDiffer(veraOperator.symbol, jvmLeftType, jvmRightType)
        }
        if (jvmLeftType != JvmType.INT) {
            throw JvmLoweringException.IntOperandsRequired(veraOperator.symbol, jvmLeftType)
        }
    }

    private fun lowerChainedExpression(veraChainedExpression: VeraChainedExpression): JvmExpression {
        return when (val resolution = resolvePrimaryExpression(veraChainedExpression.primaryExpression)) {
            is PrimaryResolution.Resolved -> {
                // expression fully resolved (literal, local, etc.)
                if (veraChainedExpression.data.isNotEmpty()) {
                    throw JvmLoweringException.MemberAccessNotSupported()
                }
                resolution.expression
            }

            is PrimaryResolution.Pending -> {
                // pending symbol -> function/builtin call
                val veraFunctionName = resolution.identifier
                val jvmArguments = mutableListOf<JvmExpression>()
                for (veraChainedData in veraChainedExpression.data) {
                    when (veraChainedData) {
                        is VeraArguments -> jvmArguments.addAll(lowerArguments(veraChainedData))

                        is VeraMemberAccess -> throw JvmLoweringException.MemberAccessNotSupported()
                    }
                }
                lowerFunctionCall(veraFunctionName, jvmArguments)
            }
        }
    }

    private fun lowerFunctionCall(
        veraFunctionName: Identifier,
        jvmArguments: List<JvmExpression>,
    ): JvmExpression {
        val builtin = Builtin.from(veraFunctionName)
        if (builtin != null) {
            return lowerBuiltinCall(builtin, jvmArguments)
        }

        val veraFunctionDeclaration = visibleFunctions[veraFunctionName]
            ?: throw JvmLoweringException.UnknownFunction(veraFunctionName)
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

    private fun lowerBuiltinCall(
        builtin: Builtin,
        jvmArguments: List<JvmExpression>,
    ): JvmExpression {
        return when (builtin.jvmBuiltin) {
            JvmBuiltin.PRINT -> {
                if (jvmArguments.size != 1) {
                    throw JvmLoweringException.BuiltinArityMismatch(
                        JvmBuiltin.PRINT,
                        1,
                        jvmArguments.size,
                    )
                }
                JvmBuiltinCall(JvmBuiltin.PRINT, jvmArguments)
            }
        }
    }

    private fun lowerBlock(veraBlock: VeraBlock): JvmBlock {
        return when (veraBlock) {
            is VeraExplicitReturnBlock -> JvmExplicitReturnBlock(lowerBlockStatements(veraBlock.statements))

            is VeraImplicitReturnBlock -> {
                val jvmStatements = lowerBlockStatements(veraBlock.statements)
                val jvmResult = lowerPrimaryExpression(veraBlock.result)
                JvmImplicitReturnBlock(jvmStatements, jvmResult)
            }

            is VeraUnitReturnBlock -> JvmUnitReturnBlock(lowerBlockStatements(veraBlock.statements))
        }
    }

    private fun lowerBlockStatements(veraStatements: List<VeraStatement>): List<JvmStatement> {
        val jvmStatements = mutableListOf<JvmStatement>()
        veraStatements.forEach { veraStatement ->
            jvmStatements += lowerStatement(veraStatement).jvmStatement
        }
        return jvmStatements
    }

    private fun resolvePrimaryExpression(
        veraPrimaryExpression: VeraPrimaryExpression,
    ): PrimaryResolution {
        return when (veraPrimaryExpression) {
            is VeraIntLiteral -> PrimaryResolution.Resolved(JvmIntLiteral(veraPrimaryExpression.value))

            is VeraStringLiteral -> PrimaryResolution.Resolved(JvmStringLiteral(veraPrimaryExpression.value))

            is VeraBoolLiteral -> PrimaryResolution.Resolved(
                JvmBoolLiteral(veraPrimaryExpression == VeraBoolLiteral.TRUE),
            )

            is VeraIfExpression -> {
                val jvmCondition = lowerPrimaryExpression(veraPrimaryExpression.condition)
                if (jvmCondition.type != JvmType.BOOL) {
                    throw JvmLoweringException.ConditionNotBool(jvmCondition.type)
                }

                val jvmThenBlock = lowerBlock(veraPrimaryExpression.thenBlock)
                val jvmElseBlock = veraPrimaryExpression.elseBlock?.let { veraElseBlock ->
                    lowerBlock(veraElseBlock)
                }
                if (jvmElseBlock != null && jvmThenBlock.type != jvmElseBlock.type) {
                    throw JvmLoweringException.IfBranchTypeMismatch(jvmThenBlock.type, jvmElseBlock.type)
                }
                PrimaryResolution.Resolved(JvmIfExpression(jvmCondition, jvmThenBlock, jvmElseBlock))
            }

            is VeraIdentifierAsExpression -> {
                val veraIdentifier = veraPrimaryExpression.identifier
                val jvmLocal = jvmLocals[veraIdentifier]
                if (jvmLocal != null) {
                    PrimaryResolution.Resolved(JvmLocalRead(jvmLocal))
                } else {
                    // not a local -> builtin/function name
                    PrimaryResolution.Pending(veraIdentifier)
                }
            }

            is VeraBinaryOperation -> PrimaryResolution.Resolved(lowerBinaryOperation(veraPrimaryExpression))

            is VeraChainedExpression -> PrimaryResolution.Resolved(lowerChainedExpression(veraPrimaryExpression))
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
