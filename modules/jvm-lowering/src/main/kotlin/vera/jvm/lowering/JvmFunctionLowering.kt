package vera.jvm.lowering

import vera.ast.VeraArguments
import vera.ast.VeraBinaryOperation
import vera.ast.VeraBindStatement
import vera.ast.VeraBlock
import vera.ast.VeraBoolLiteral
import vera.ast.VeraBreakStatement
import vera.ast.VeraChainedExpression
import vera.ast.VeraContinueStatement
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
import vera.ast.VeraClassDeclaration
import vera.ast.VeraReturnStatement
import vera.ast.VeraStatement
import vera.ast.VeraStringLiteral
import vera.ast.VeraType
import vera.ast.VeraUnaryOperation
import vera.ast.VeraUnaryOperator
import vera.ast.VeraUnitLiteral
import vera.ast.VeraUnitReturnBlock
import vera.ast.VeraWhileStatement
import vera.ast.render
import vera.jvm.ir.JvmComparisonOperator
import vera.jvm.ir.JvmIntBinaryOperator
import vera.jvm.ir.JvmBlock
import vera.jvm.ir.JvmBreak
import vera.jvm.ir.JvmBuiltin
import vera.jvm.ir.JvmBuiltinCall
import vera.jvm.ir.JvmBoolLiteral
import vera.jvm.ir.JvmComparison
import vera.jvm.ir.JvmContinue
import vera.jvm.ir.JvmExplicitReturnBlock
import vera.jvm.ir.JvmExpression
import vera.jvm.ir.JvmExpressionStatement
import vera.jvm.ir.JvmGetField
import vera.jvm.ir.JvmIfExpression
import vera.jvm.ir.JvmImplicitReturnBlock
import vera.jvm.ir.JvmInstanceCall
import vera.jvm.ir.JvmIntBinary
import vera.jvm.ir.JvmIntLiteral
import vera.jvm.ir.JvmIntUnary
import vera.jvm.ir.JvmIntUnaryOperator
import vera.jvm.ir.JvmLet
import vera.jvm.ir.JvmLocal
import vera.jvm.ir.JvmLocalRead
import vera.jvm.ir.JvmLogicalAnd
import vera.jvm.ir.JvmLogicalNot
import vera.jvm.ir.JvmLogicalOr
import vera.jvm.ir.JvmMethod
import vera.jvm.ir.JvmMethodSignature
import vera.jvm.ir.JvmParameter
import vera.jvm.ir.JvmRebind
import vera.jvm.ir.JvmObjectConstruction
import vera.jvm.ir.JvmReturn
import vera.jvm.ir.JvmStaticCall
import vera.jvm.ir.JvmStatement
import vera.jvm.ir.JvmStringLiteral
import vera.jvm.ir.JvmType
import vera.jvm.ir.JvmUnitLiteral
import vera.jvm.ir.JvmUnitReturnBlock
import vera.jvm.ir.JvmUnitType
import vera.jvm.ir.JvmVarDeclaration
import vera.jvm.ir.JvmWhile
import vera.shared.model.Identifier

private enum class Builtin(val keyword: Identifier, val jvmBuiltin: JvmBuiltin) {

    PRINT(Identifier("print"), JvmBuiltin.PRINT);

    companion object {
        private val byKeyword = entries.associateBy { it.keyword }
        fun from(keyword: Identifier): Builtin? = byKeyword[keyword]
        fun allNames(): Set<Identifier> = byKeyword.keys
    }
}

private sealed interface PrimaryResolution {
    data class Resolved(val expression: JvmExpression) : PrimaryResolution
    data class Pending(val identifier: Identifier) : PrimaryResolution
}

internal object JvmFunctionLowering {
    internal fun isBuiltinName(name: Identifier): Boolean = Builtin.from(name) != null

    internal fun builtinNames(): Set<Identifier> = Builtin.allNames()

    fun lower(
        className: Identifier,
        visibleFunctions: Map<Identifier, VeraFunctionDeclaration>,
        visibleClasses: Map<Identifier, VeraClassDeclaration>,
        veraFunction: VeraFunctionDeclaration,
        receiverClass: VeraClassDeclaration? = null,
    ): JvmMethod = JvmFunctionLoweringState(className, visibleFunctions, visibleClasses, receiverClass)
        .lower(veraFunction)
}

private class JvmFunctionLoweringState(
    private val className: Identifier,
    private val visibleFunctions: Map<Identifier, VeraFunctionDeclaration>,
    private val visibleClasses: Map<Identifier, VeraClassDeclaration>,
    private val receiverClass: VeraClassDeclaration?,
) {

    private val receiverLocal: JvmLocal? = receiverClass?.let { veraClass ->
        JvmLocal(Identifier("<this>"), JvmType.ClassType(veraClass.name))
    }

    private val reservedNames: Set<Identifier> = buildSet {
        addAll(visibleFunctions.keys)
        addAll(visibleClasses.keys)
        addAll(JvmFunctionLowering.builtinNames())
        receiverClass?.let { veraClass ->
            addAll(veraClass.fields.map { field -> field.name })
            addAll(veraClass.methods.map { method -> method.name })
        }
    }

    /**
     * A lowered statement, whether it always returns from the current function, and, when it always
     * leaves its block, a description of how; any statement after it in the same block is unreachable.
     */
    private data class LoweredStatement(
        val jvmStatement: JvmStatement,
        val returns: Boolean,
        val exit: String? = null,
    )

    /**
     * A lowered call argument. [explicitName] is set for `name = value`, [shorthandName] for a bare
     * identifier (which may stand for the parameter of the same name), and [forwarded] for the value
     * passed by `|>`, which always fills the first parameter.
     */
    private data class LoweredArgument(
        val jvmExpression: JvmExpression,
        val explicitName: Identifier? = null,
        val shorthandName: Identifier? = null,
        val forwarded: Boolean = false,
    )

    /** A lowered expression and whether evaluating it always returns from the current function. */
    private data class LoweredExpression(val jvmExpression: JvmExpression, val returns: Boolean)

    /** A lowered block and whether executing it always returns from the current function. */
    private data class LoweredBlock(val jvmBlock: JvmBlock, val returns: Boolean)

    /** Visible locals by block scope, innermost last. The first scope holds parameters and the body. */
    private val scopes = mutableListOf(mutableMapOf<Identifier, JvmLocal>())

    /**
     * How many enclosing expressions are partially evaluated at this point. `break` and `continue` may
     * only target a loop entered at the same depth, so a jump never leaves values on the operand stack.
     */
    private var expressionDepth = 0

    /** The [expressionDepth] at which each enclosing loop was entered, innermost last. */
    private val loopExpressionDepths = mutableListOf<Int>()

    private var temporaryCount = 0
    private lateinit var currentReturnType: JvmType

    fun lower(veraFunction: VeraFunctionDeclaration): JvmMethod {
        currentReturnType = JvmTypeLowering.lowerReturn(veraFunction.returnType, visibleClasses.keys)

        val jvmParameters = veraFunction.parameters
            .map { veraParameter ->
                val jvmLocal = declareLocal(veraParameter.name, lowerType(veraParameter.type))
                JvmParameter(jvmLocal.name, jvmLocal.type)
            }

        val loweredStatements = lowerStatementList(veraFunction.body.statements)
        val jvmStatements = loweredStatements.map { loweredStatement -> loweredStatement.jvmStatement }.toMutableList()
        if (loweredStatements.none { loweredStatement -> loweredStatement.returns }) {
            if (currentReturnType != JvmUnitType) {
                throw JvmLoweringException.MissingReturnStatement(currentReturnType)
            }
            jvmStatements += JvmReturn(null)
        }

        return JvmMethod(
            veraFunction.name,
            jvmParameters,
            currentReturnType,
            JvmExplicitReturnBlock(jvmStatements),
            receiver = receiverLocal,
        )
    }

    /** @return the lowered statement and whether it explicitly returns from the current function. */
    private fun lowerStatement(veraStatement: VeraStatement): LoweredStatement {
        return when (veraStatement) {
            is VeraBindStatement -> LoweredStatement(lowerBindStatement(veraStatement), false)

            is VeraExpression -> {
                val loweredExpression = lowerStatementExpression(veraStatement.expression)
                LoweredStatement(
                    JvmExpressionStatement(loweredExpression.jvmExpression),
                    loweredExpression.returns,
                    exit = if (loweredExpression.returns) "an if whose branches all return" else null,
                )
            }

            is VeraReturnStatement -> {
                val jvmReturnExpression = veraStatement.expression
                    ?.let { veraExpression -> lowerExpression(veraExpression) }
                if (jvmReturnExpression == null) {
                    if (currentReturnType != JvmUnitType) {
                        throw JvmLoweringException.MissingReturnValue(currentReturnType)
                    }
                } else if (jvmReturnExpression.type != currentReturnType) {
                    throw JvmLoweringException.ReturnTypeMismatch(
                        currentReturnType,
                        jvmReturnExpression.type,
                    )
                }
                LoweredStatement(JvmReturn(jvmReturnExpression), true, exit = "return")
            }

            is VeraWhileStatement -> LoweredStatement(lowerWhileStatement(veraStatement), false)

            is VeraRebindStatement -> LoweredStatement(lowerRebindStatement(veraStatement), false)

            VeraBreakStatement -> {
                val loopDepth = loopExpressionDepths.lastOrNull()
                    ?: throw JvmLoweringException.BreakOutsideLoop()
                requireLoopJumpDepth("break", loopDepth)
                LoweredStatement(JvmBreak, false, exit = "break")
            }

            VeraContinueStatement -> {
                val loopDepth = loopExpressionDepths.lastOrNull()
                    ?: throw JvmLoweringException.ContinueOutsideLoop()
                requireLoopJumpDepth("continue", loopDepth)
                LoweredStatement(JvmContinue, false, exit = "continue")
            }
        }
    }

    private fun requireLoopJumpDepth(keyword: String, loopDepth: Int) {
        if (expressionDepth != loopDepth) {
            throw JvmLoweringException.LoopJumpInsideExpression(keyword)
        }
    }

    private fun lowerWhileStatement(veraWhileStatement: VeraWhileStatement): JvmStatement {
        val jvmCondition = nested { lowerPrimaryExpression(veraWhileStatement.condition) }
        if (jvmCondition.type != JvmType.BOOL) {
            throw JvmLoweringException.ConditionNotBool(jvmCondition.type)
        }
        loopExpressionDepths += expressionDepth
        val jvmBody = try {
            withScope { lowerBlockStatements(veraWhileStatement.body.statements) }
        } finally {
            loopExpressionDepths.removeLast()
        }
        return JvmWhile(jvmCondition, JvmUnitReturnBlock(jvmBody))
    }

    private fun lowerRebindStatement(veraRebindStatement: VeraRebindStatement): JvmStatement {
        val jvmLocal = lookupLocal(veraRebindStatement.name)
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
        requireFreshName(veraBindStatement.name)
        val jvmExpression = lowerExpression(veraBindStatement.expression)
        val jvmExpressionType = jvmExpression.type
        if (veraBindStatement.type != VeraType.UNKNOWN && lowerType(veraBindStatement.type) != jvmExpressionType) {
            throw JvmLoweringException.BindTypeMismatch(veraBindStatement.type.render(), jvmExpressionType)
        }
        val jvmLocal = declareLocal(veraBindStatement.name, jvmExpressionType)
        return JvmVarDeclaration(jvmLocal, jvmExpression)
    }

    /** Lowers the expression of a bind, rebind, or return statement. */
    private fun lowerExpression(veraExpression: VeraExpression): JvmExpression =
        lowerStatementExpression(veraExpression.expression).jvmExpression

    /**
     * Lowers an expression that forms a whole statement, so it starts on an empty operand stack. Only an
     * `if` keeps that position for its branches; any other expression nests its operands.
     */
    private fun lowerStatementExpression(veraExpression: VeraPrimaryExpression): LoweredExpression =
        if (veraExpression is VeraIfExpression) {
            lowerIfExpression(veraExpression)
        } else {
            LoweredExpression(nested { lowerPrimaryExpression(veraExpression) }, returns = false)
        }

    private fun lowerIfExpression(veraIfExpression: VeraIfExpression): LoweredExpression {
        val jvmCondition = nested { lowerPrimaryExpression(veraIfExpression.condition) }
        if (jvmCondition.type != JvmType.BOOL) {
            throw JvmLoweringException.ConditionNotBool(jvmCondition.type)
        }
        val loweredThen = lowerBlock(veraIfExpression.thenBlock)
        val loweredElse = veraIfExpression.elseBlock?.let { veraElseBlock -> lowerBlock(veraElseBlock) }
        if (loweredElse != null && loweredThen.jvmBlock.type != loweredElse.jvmBlock.type) {
            throw JvmLoweringException.IfBranchTypeMismatch(loweredThen.jvmBlock.type, loweredElse.jvmBlock.type)
        }
        return LoweredExpression(
            JvmIfExpression(jvmCondition, loweredThen.jvmBlock, loweredElse?.jvmBlock),
            returns = loweredElse != null && loweredThen.returns && loweredElse.returns,
        )
    }

    private inline fun <T> nested(lowering: () -> T): T {
        expressionDepth++
        try {
            return lowering()
        } finally {
            expressionDepth--
        }
    }

    private inline fun <T> withScope(lowering: () -> T): T {
        scopes += mutableMapOf()
        try {
            return lowering()
        } finally {
            scopes.removeLast()
        }
    }

    private fun lookupLocal(name: Identifier): JvmLocal? =
        scopes.asReversed().firstNotNullOfOrNull { scope -> scope[name] }

    private fun declareLocal(name: Identifier, jvmType: JvmType): JvmLocal {
        requireFreshName(name)
        val jvmLocal = JvmLocal(name, jvmType)
        scopes.last()[name] = jvmLocal
        return jvmLocal
    }

    private fun lowerPrimaryExpression(veraPrimaryExpression: VeraPrimaryExpression): JvmExpression {
        return when (veraPrimaryExpression) {
            is VeraBinaryOperation -> lowerBinaryOperation(veraPrimaryExpression)
            is VeraChainedExpression -> lowerChainedExpression(veraPrimaryExpression)
            else -> when (val resolution = resolvePrimaryExpression(veraPrimaryExpression)) {
                is PrimaryResolution.Resolved -> resolution.expression
                is PrimaryResolution.Pending -> {
                    if (visibleClasses.containsKey(resolution.identifier)) {
                        throw JvmLoweringException.ClassTypeUsedAsValue(resolution.identifier)
                    }
                    val receiver = receiverClass
                    if (receiver != null && receiver.methods.any { it.name == resolution.identifier }) {
                        throw JvmLoweringException.ClassMethodUsedAsValue(receiver.name, resolution.identifier)
                    }
                    throw JvmLoweringException.UnknownVariable(resolution.identifier)
                }
            }
        }
    }

    private fun lowerUnaryOperation(veraUnaryOperation: VeraUnaryOperation): JvmExpression {
        val jvmOperand = lowerPrimaryExpression(veraUnaryOperation.operand)
        return when (veraUnaryOperation.operator) {
            VeraUnaryOperator.NEGATE -> {
                if (jvmOperand.type != JvmType.INT) {
                    throw JvmLoweringException.IntOperandsRequired(
                        veraUnaryOperation.operator.symbol,
                        jvmOperand.type,
                    )
                }
                JvmIntUnary(JvmIntUnaryOperator.NEGATE, jvmOperand)
            }

            VeraUnaryOperator.NOT -> {
                requireBoolOperand(jvmOperand.type, veraUnaryOperation.operator.symbol)
                JvmLogicalNot(jvmOperand)
            }
        }
    }

    private fun lowerBinaryOperation(veraOperation: VeraBinaryOperation): JvmExpression {
        when (veraOperation.operator) {
            VeraOperator.AND -> {
                val jvmLeft = lowerPrimaryExpression(veraOperation.left)
                val jvmRight = lowerPrimaryExpression(veraOperation.right)
                requireBoolOperand(jvmLeft.type, veraOperation.operator.symbol)
                requireBoolOperand(jvmRight.type, veraOperation.operator.symbol)
                return JvmLogicalAnd(jvmLeft, jvmRight)
            }

            VeraOperator.OR -> {
                val jvmLeft = lowerPrimaryExpression(veraOperation.left)
                val jvmRight = lowerPrimaryExpression(veraOperation.right)
                requireBoolOperand(jvmLeft.type, veraOperation.operator.symbol)
                requireBoolOperand(jvmRight.type, veraOperation.operator.symbol)
                return JvmLogicalOr(jvmLeft, jvmRight)
            }

            VeraOperator.FORWARD_APPLICATION -> return lowerForwardApplication(veraOperation)
            else -> {}
        }

        val jvmLeft = lowerPrimaryExpression(veraOperation.left)
        val jvmRight = lowerPrimaryExpression(veraOperation.right)
        if (veraOperation.operator == VeraOperator.EQUALS || veraOperation.operator == VeraOperator.NOT_EQUALS) {
            validateEqualityOperands(veraOperation.operator, jvmLeft.type, jvmRight.type)
            return lowerEquality(veraOperation.operator, jvmLeft, jvmRight)
        }
        validateIntOperands(veraOperation.operator, jvmLeft.type, jvmRight.type)

        return when (veraOperation.operator) {
            VeraOperator.EQUALS, VeraOperator.NOT_EQUALS ->
                throw JvmLoweringException.InternalInvariant("unreachable equality operator")
            VeraOperator.LESS -> JvmComparison(JvmComparisonOperator.LT, jvmLeft, jvmRight)
            VeraOperator.GREATER -> JvmComparison(JvmComparisonOperator.GT, jvmLeft, jvmRight)
            VeraOperator.LESS_EQ -> JvmComparison(JvmComparisonOperator.LTE, jvmLeft, jvmRight)
            VeraOperator.GREATER_EQ -> JvmComparison(JvmComparisonOperator.GTE, jvmLeft, jvmRight)
            VeraOperator.ADD -> JvmIntBinary(JvmIntBinaryOperator.ADD, jvmLeft, jvmRight)
            VeraOperator.SUBTRACT -> JvmIntBinary(JvmIntBinaryOperator.SUB, jvmLeft, jvmRight)
            VeraOperator.MULTIPLY -> JvmIntBinary(JvmIntBinaryOperator.MUL, jvmLeft, jvmRight)
            VeraOperator.DIVIDE -> JvmIntBinary(JvmIntBinaryOperator.DIV, jvmLeft, jvmRight)
            VeraOperator.PERCENT -> JvmIntBinary(JvmIntBinaryOperator.REM, jvmLeft, jvmRight)
            VeraOperator.AND, VeraOperator.OR, VeraOperator.FORWARD_APPLICATION ->
                throw JvmLoweringException.InternalInvariant("unreachable binary operator")
        }
    }

    private fun lowerForwardApplication(veraOperation: VeraBinaryOperation): JvmExpression {
        return when (val veraRight = veraOperation.right) {
            is VeraIdentifierAsExpression -> {
                val loweredArguments = listOf(
                    LoweredArgument(lowerPrimaryExpression(veraOperation.left), forwarded = true),
                )
                lowerNamedCall(veraRight.identifier, loweredArguments)
            }

            // A bare call such as `f(a)` evaluates the forwarded value first anyway.
            is VeraChainedExpression if veraRight.data.size == 1 -> lowerChainedExpression(veraRight) {
                lowerPrimaryExpression(veraOperation.left)
            }

            // A member chain such as `box().plus(a)` evaluates its receiver first, so the forwarded
            // value is computed into a temporary beforehand to keep left-to-right source order.
            is VeraChainedExpression -> {
                val jvmForwarded = lowerPrimaryExpression(veraOperation.left)
                val jvmTemporary = JvmLocal(Identifier("<forwarded${temporaryCount++}>"), jvmForwarded.type)
                val jvmBody = lowerChainedExpression(veraRight) { JvmLocalRead(jvmTemporary) }
                JvmLet(jvmTemporary, jvmForwarded, jvmBody)
            }

            else -> throw JvmLoweringException.InvalidForwardApplication(
                JvmForwardApplicationIssue.RIGHT_SIDE_NOT_CALLABLE,
            )
        }
    }

    private fun requireBoolOperand(jvmType: JvmType, operatorSymbol: String) {
        if (jvmType != JvmType.BOOL) {
            throw JvmLoweringException.BoolOperandsRequired(operatorSymbol, jvmType)
        }
    }

    private fun lowerEquality(
        veraOperator: VeraOperator,
        jvmLeft: JvmExpression,
        jvmRight: JvmExpression,
    ): JvmExpression {
        val jvmType = jvmLeft.type
        if (jvmType is JvmType.ClassType) {
            if (!hasBuiltinTrait(jvmType.name, JvmBuiltinTraits.eq)) {
                throw JvmLoweringException.ObjectEqualityRequiresTrait(jvmType.name)
            }
            val equalsCall = JvmObjectMethods.equalsCall(jvmLeft, jvmType.name, jvmRight)
            return if (veraOperator == VeraOperator.EQUALS) equalsCall else JvmLogicalNot(equalsCall)
        }
        val jvmOperator = if (veraOperator == VeraOperator.EQUALS) {
            JvmComparisonOperator.EQ
        } else {
            JvmComparisonOperator.NEQ
        }
        return JvmComparison(jvmOperator, jvmLeft, jvmRight)
    }

    private fun hasBuiltinTrait(className: Identifier, trait: Identifier): Boolean =
        className == JvmUnitType.name || visibleClasses[className]?.traits?.contains(trait) == true

    private fun validateEqualityOperands(
        veraOperator: VeraOperator,
        jvmLeftType: JvmType,
        jvmRightType: JvmType,
    ) {
        if (jvmLeftType != jvmRightType) {
            throw JvmLoweringException.OperandTypesDiffer(veraOperator.symbol, jvmLeftType, jvmRightType)
        }
        when (jvmLeftType) {
            JvmType.INT, JvmType.BOOL, JvmType.STRING -> {}
            is JvmType.ClassType -> {}
            else -> throw JvmLoweringException.EqualityOperandsRequired(veraOperator.symbol, jvmLeftType)
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

    private fun lowerChainedExpression(
        veraChainedExpression: VeraChainedExpression,
        forwardedArgument: (() -> JvmExpression)? = null,
    ): JvmExpression {
        val forwardedArgumentIndex = if (forwardedArgument == null) {
            -1
        } else {
            if (veraChainedExpression.data.lastOrNull() !is VeraArguments) {
                throw JvmLoweringException.InvalidForwardApplication(
                    JvmForwardApplicationIssue.RIGHT_SIDE_NOT_CALLABLE,
                )
            }
            var previousWasArguments = false
            veraChainedExpression.data.forEach { chainedData ->
                val isArguments = chainedData is VeraArguments
                if (isArguments && previousWasArguments) {
                    throw JvmLoweringException.InvalidForwardApplication(
                        JvmForwardApplicationIssue.RIGHT_SIDE_CALLS_CALL_RESULT,
                    )
                }
                previousWasArguments = isArguments
            }
            veraChainedExpression.data.lastIndex
        }
        var jvmExpression: JvmExpression
        var dataIndex: Int

        when (val resolution = resolvePrimaryExpression(veraChainedExpression.primaryExpression)) {
            is PrimaryResolution.Resolved -> {
                jvmExpression = resolution.expression
                dataIndex = 0
            }

            is PrimaryResolution.Pending -> {
                val veraName = resolution.identifier
                val firstData = veraChainedExpression.data.firstOrNull()
                if (firstData !is VeraArguments) {
                    if (visibleClasses.containsKey(veraName)) {
                        throw JvmLoweringException.ClassTypeUsedAsValue(veraName)
                    }
                    val receiver = receiverClass
                    if (receiver != null && receiver.methods.any { it.name == veraName }) {
                        throw JvmLoweringException.ClassMethodUsedAsValue(receiver.name, veraName)
                    }
                    throw JvmLoweringException.UnknownVariable(veraName)
                }

                val loweredArguments = lowerForwardedArguments(
                    forwardedArgument,
                    forwardedArgumentIndex == 0,
                ) { lowerArguments(firstData) }
                jvmExpression = lowerNamedCall(veraName, loweredArguments)
                dataIndex = 1
            }
        }

        while (dataIndex < veraChainedExpression.data.size) {
            when (val chainedData = veraChainedExpression.data[dataIndex]) {
                is VeraMemberAccess -> {
                    val ownerType = jvmExpression.type as? JvmType.ClassType
                        ?: throw JvmLoweringException.MemberAccessRequiresObject(jvmExpression.type)
                    val veraClass = visibleClasses[ownerType.name]
                        ?: throw JvmLoweringException.UnknownClassType(ownerType.name)
                    val member = chainedData.member
                    val nextData = veraChainedExpression.data.getOrNull(dataIndex + 1)
                    val veraField = veraClass.fields.firstOrNull { it.name == member }
                    if (veraField != null) {
                        if (nextData is VeraArguments) {
                            throw JvmLoweringException.ValueCallNotSupported()
                        }
                        jvmExpression = JvmGetField(
                            receiver = jvmExpression,
                            ownerClass = ownerType.name,
                            fieldName = member,
                            fieldType = lowerType(veraField.type),
                        )
                        dataIndex++
                    } else {
                        val veraMethod = veraClass.methods.firstOrNull { it.name == member }
                        if (veraMethod == null) {
                            if (nextData is VeraArguments) {
                                throw JvmLoweringException.UnknownClassMethod(ownerType.name, member)
                            }
                            throw JvmLoweringException.UnknownClassField(ownerType.name, member)
                        }
                        if (nextData is VeraArguments) {
                            val loweredArguments = lowerForwardedArguments(
                                forwardedArgument,
                                forwardedArgumentIndex == dataIndex + 1,
                            ) { lowerArguments(nextData) }
                            jvmExpression = lowerInstanceMethodCall(
                                jvmExpression,
                                veraClass,
                                veraMethod,
                                loweredArguments,
                            )
                            dataIndex += 2
                        } else {
                            throw JvmLoweringException.ClassMethodUsedAsValue(ownerType.name, member)
                        }
                    }
                }

                is VeraArguments -> throw JvmLoweringException.ValueCallNotSupported()
            }
        }

        return jvmExpression
    }

    private fun lowerForwardedArguments(
        forwardedArgument: (() -> JvmExpression)?,
        shouldInject: Boolean,
        lowerExplicitArguments: () -> List<LoweredArgument>,
    ): List<LoweredArgument> {
        if (!shouldInject) {
            return lowerExplicitArguments()
        }
        val forward = forwardedArgument
            ?: throw JvmLoweringException.InternalInvariant("missing forwarded argument")
        return listOf(LoweredArgument(forward(), forwarded = true)) + lowerExplicitArguments()
    }

    private fun lowerNamedCall(
        veraName: Identifier,
        loweredArguments: List<LoweredArgument>,
    ): JvmExpression {
        val veraClass = visibleClasses[veraName]
        val receiver = receiverClass
        val receiverMethod = receiver?.methods?.firstOrNull { it.name == veraName }
        return when {
            veraClass != null -> lowerObjectConstruction(veraClass, loweredArguments)
            receiver != null && receiverMethod != null ->
                lowerInstanceMethodCall(readReceiver(), receiver, receiverMethod, loweredArguments)
            else -> lowerFunctionCall(veraName, loweredArguments)
        }
    }

    private fun requireFreshName(name: Identifier) {
        if (name in reservedNames || lookupLocal(name) != null) {
            throw JvmLoweringException.ShadowingNotAllowed(name)
        }
    }

    private fun readReceiver(): JvmExpression {
        val jvmReceiver = receiverLocal
            ?: throw JvmLoweringException.InternalInvariant("method call without a receiver")
        return JvmLocalRead(jvmReceiver)
    }

    private fun lowerInstanceMethodCall(
        jvmReceiver: JvmExpression,
        veraClass: VeraClassDeclaration,
        veraMethod: VeraFunctionDeclaration,
        loweredArguments: List<LoweredArgument>,
    ): JvmExpression {
        val jvmParameters = veraMethod.parameters.map { veraParameter ->
            JvmParameter(veraParameter.name, lowerType(veraParameter.type))
        }
        if (loweredArguments.size != jvmParameters.size) {
            throw JvmLoweringException.MethodArityMismatch(
                veraClass.name,
                veraMethod.name,
                jvmParameters.size,
                loweredArguments.size,
            )
        }
        requireArgumentNames(
            "method ${veraClass.name.value}.${veraMethod.name.value}",
            veraMethod.parameters.map { veraParameter -> veraParameter.name },
            loweredArguments,
        )
        val jvmArguments = loweredArguments.map { loweredArgument -> loweredArgument.jvmExpression }
        jvmArguments.forEachIndexed { index, jvmArgument ->
            val expectedType = jvmParameters[index].type
            if (jvmArgument.type != expectedType) {
                throw JvmLoweringException.MethodArgumentTypeMismatch(
                    veraClass.name,
                    veraMethod.name,
                    veraMethod.parameters[index].name,
                    expectedType,
                    jvmArgument.type,
                )
            }
        }
        return JvmInstanceCall(
            receiver = jvmReceiver,
            ownerClass = veraClass.name,
            methodName = veraMethod.name,
            arguments = jvmArguments,
            signature = JvmMethodSignature(
                jvmParameters,
                JvmTypeLowering.lowerReturn(veraMethod.returnType, visibleClasses.keys),
            ),
        )
    }

    private fun lowerObjectConstruction(
        veraClass: VeraClassDeclaration,
        loweredArguments: List<LoweredArgument>,
    ): JvmExpression {
        if (loweredArguments.size != veraClass.fields.size) {
            throw JvmLoweringException.ClassConstructorArityMismatch(
                veraClass.name,
                veraClass.fields.size,
                loweredArguments.size,
            )
        }
        requireArgumentNames(
            "class ${veraClass.name.value}",
            veraClass.fields.map { field -> field.name },
            loweredArguments,
        )
        val jvmArguments = loweredArguments.map { loweredArgument -> loweredArgument.jvmExpression }

        val jvmFieldTypes = veraClass.fields.map { field -> lowerType(field.type) }
        jvmArguments.forEachIndexed { index, jvmArgument ->
            val expectedType = jvmFieldTypes[index]
            if (jvmArgument.type != expectedType) {
                throw JvmLoweringException.ClassConstructorArgumentTypeMismatch(
                    veraClass.name,
                    veraClass.fields[index].name,
                    expectedType,
                    jvmArgument.type,
                )
            }
        }

        val jvmParameters = veraClass.fields.mapIndexed { index, field ->
            JvmParameter(field.name, jvmFieldTypes[index])
        }
        return JvmObjectConstruction(
            className = veraClass.name,
            arguments = jvmArguments,
            signature = JvmMethodSignature(jvmParameters, JvmType.VOID),
        )
    }

    private fun lowerFunctionCall(
        veraFunctionName: Identifier,
        loweredArguments: List<LoweredArgument>,
    ): JvmExpression {
        val builtin = Builtin.from(veraFunctionName)
        if (builtin != null) {
            return lowerBuiltinCall(builtin, loweredArguments)
        }

        val veraFunctionDeclaration = visibleFunctions[veraFunctionName]
            ?: throw JvmLoweringException.UnknownFunction(veraFunctionName)
        val jvmParameters = veraFunctionDeclaration.parameters.map { veraParameter ->
            JvmParameter(veraParameter.name, lowerType(veraParameter.type))
        }
        if (loweredArguments.size != jvmParameters.size) {
            throw JvmLoweringException.FunctionArityMismatch(
                veraFunctionName,
                jvmParameters.size,
                loweredArguments.size,
            )
        }
        requireArgumentNames(
            "function ${veraFunctionName.value}",
            veraFunctionDeclaration.parameters.map { veraParameter -> veraParameter.name },
            loweredArguments,
        )
        val jvmArguments = loweredArguments.map { loweredArgument -> loweredArgument.jvmExpression }
        jvmArguments.forEachIndexed { index, jvmArgument ->
            val expectedType = jvmParameters[index].type
            if (jvmArgument.type != expectedType) {
                throw JvmLoweringException.FunctionArgumentTypeMismatch(
                    veraFunctionName,
                    veraFunctionDeclaration.parameters[index].name,
                    expectedType,
                    jvmArgument.type,
                )
            }
        }
        val jvmSignature = JvmMethodSignature(
            jvmParameters,
            JvmTypeLowering.lowerReturn(veraFunctionDeclaration.returnType, visibleClasses.keys),
        )
        return JvmStaticCall(
            className,
            veraFunctionDeclaration.name,
            jvmArguments,
            jvmSignature,
        )
    }

    private fun lowerArguments(veraArguments: VeraArguments): List<LoweredArgument> =
        veraArguments.arguments.map { veraArgument ->
            val veraExpression = veraArgument.expression
            LoweredArgument(
                jvmExpression = lowerPrimaryExpression(veraExpression),
                explicitName = veraArgument.name,
                shorthandName = (veraExpression as? VeraIdentifierAsExpression)
                    ?.identifier
                    ?.takeIf { veraArgument.name == null },
            )
        }

    /**
     * Checks argument names against [parameterNames] once the arity is known to match. Only written
     * arguments count: the value forwarded by `|>` fills the first parameter without a name. A call
     * with one written argument passes it without a name. A call with two or more written arguments
     * names every one in parameter order, either as `name = value` or as a bare identifier with the
     * parameter's name.
     */
    private fun requireArgumentNames(
        callee: String,
        parameterNames: List<Identifier>,
        loweredArguments: List<LoweredArgument>,
    ) {
        val writtenArguments = loweredArguments.filterNot { loweredArgument -> loweredArgument.forwarded }
        if (writtenArguments.size < 2) {
            val explicitName = writtenArguments.firstOrNull()?.explicitName ?: return
            throw JvmLoweringException.SingleArgumentNamed(callee, explicitName)
        }
        loweredArguments.forEachIndexed { index, loweredArgument ->
            val expectedName = parameterNames[index]
            val position = index + 1
            val explicitName = loweredArgument.explicitName
            val shorthandName = loweredArgument.shorthandName
            when {
                loweredArgument.forwarded -> {}
                explicitName == expectedName -> {}
                explicitName != null ->
                    throw JvmLoweringException.ArgumentNameMismatch(callee, position, explicitName, expectedName)
                shorthandName == expectedName -> {}
                shorthandName != null && shorthandName in parameterNames ->
                    throw JvmLoweringException.ArgumentNameMismatch(callee, position, shorthandName, expectedName)
                else -> throw JvmLoweringException.ArgumentNameRequired(callee, position, expectedName)
            }
        }
    }

    private fun lowerBuiltinCall(
        builtin: Builtin,
        loweredArguments: List<LoweredArgument>,
    ): JvmExpression {
        return when (builtin.jvmBuiltin) {
            JvmBuiltin.PRINT -> {
                if (loweredArguments.size != 1) {
                    throw JvmLoweringException.BuiltinArityMismatch(
                        JvmBuiltin.PRINT,
                        1,
                        loweredArguments.size,
                    )
                }
                requireArgumentNames("print", listOf(Identifier("value")), loweredArguments)
                val jvmArguments = loweredArguments.map { loweredArgument -> loweredArgument.jvmExpression }
                val argument = jvmArguments.first()
                val argumentType = argument.type
                if (argumentType is JvmType.ClassType) {
                    if (!hasBuiltinTrait(argumentType.name, JvmBuiltinTraits.display)) {
                        throw JvmLoweringException.PrintRequiresDisplayTrait(argumentType.name)
                    }
                    return JvmBuiltinCall(
                        JvmBuiltin.PRINT,
                        listOf(JvmObjectMethods.toStringCall(argument, argumentType.name)),
                    )
                }
                if (argumentType != JvmType.INT && argumentType != JvmType.STRING && argumentType != JvmType.BOOL) {
                    throw JvmLoweringException.PrintArgumentTypeNotSupported(argumentType)
                }
                if (argumentType == JvmType.BOOL) {
                    return JvmBuiltinCall(JvmBuiltin.PRINT, listOf(JvmObjectMethods.boolToString(argument)))
                }
                JvmBuiltinCall(JvmBuiltin.PRINT, jvmArguments)
            }
        }
    }

    private fun lowerBlock(veraBlock: VeraBlock): LoweredBlock = withScope {
        val loweredStatements = lowerStatementList(veraBlock.statements)
        val jvmStatements = loweredStatements.map { loweredStatement -> loweredStatement.jvmStatement }
        val statementsReturn = loweredStatements.any { loweredStatement -> loweredStatement.returns }
        when (veraBlock) {
            is VeraExplicitReturnBlock -> LoweredBlock(JvmExplicitReturnBlock(jvmStatements), statementsReturn)

            is VeraImplicitReturnBlock -> {
                requireReachable(loweredStatements)
                val loweredResult = lowerStatementExpression(veraBlock.result)
                LoweredBlock(
                    JvmImplicitReturnBlock(jvmStatements, loweredResult.jvmExpression),
                    statementsReturn || loweredResult.returns,
                )
            }

            is VeraUnitReturnBlock -> LoweredBlock(JvmUnitReturnBlock(jvmStatements), statementsReturn)
        }
    }

    private fun lowerBlockStatements(veraStatements: List<VeraStatement>): List<JvmStatement> =
        lowerStatementList(veraStatements).map { loweredStatement -> loweredStatement.jvmStatement }

    /** Lowers a block's statements in order, rejecting any statement after one that always leaves it. */
    private fun lowerStatementList(veraStatements: List<VeraStatement>): List<LoweredStatement> {
        val loweredStatements = mutableListOf<LoweredStatement>()
        veraStatements.forEach { veraStatement ->
            requireReachable(loweredStatements)
            loweredStatements += lowerStatement(veraStatement)
        }
        return loweredStatements
    }

    private fun requireReachable(precedingStatements: List<LoweredStatement>) {
        val exit = precedingStatements.lastOrNull()?.exit ?: return
        throw JvmLoweringException.UnreachableStatement(exit)
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

            is VeraUnitLiteral -> PrimaryResolution.Resolved(JvmUnitLiteral)

            is VeraUnaryOperation -> PrimaryResolution.Resolved(lowerUnaryOperation(veraPrimaryExpression))

            is VeraIfExpression -> PrimaryResolution.Resolved(lowerIfExpression(veraPrimaryExpression).jvmExpression)

            is VeraIdentifierAsExpression -> {
                val veraIdentifier = veraPrimaryExpression.identifier
                val jvmLocal = lookupLocal(veraIdentifier)
                if (jvmLocal != null) {
                    PrimaryResolution.Resolved(JvmLocalRead(jvmLocal))
                } else {
                    resolveReceiverField(veraIdentifier) ?: PrimaryResolution.Pending(veraIdentifier)
                }
            }

            is VeraBinaryOperation -> PrimaryResolution.Resolved(lowerBinaryOperation(veraPrimaryExpression))

            is VeraChainedExpression -> PrimaryResolution.Resolved(lowerChainedExpression(veraPrimaryExpression))
        }
    }

    private fun resolveReceiverField(identifier: Identifier): PrimaryResolution.Resolved? {
        val jvmReceiver = receiverLocal ?: return null
        val veraClass = receiverClass ?: return null
        val veraField = veraClass.fields.firstOrNull { it.name == identifier } ?: return null
        return PrimaryResolution.Resolved(
            JvmGetField(
                receiver = JvmLocalRead(jvmReceiver),
                ownerClass = veraClass.name,
                fieldName = identifier,
                fieldType = lowerType(veraField.type),
            ),
        )
    }

    private fun lowerType(veraType: VeraType): JvmType =
        JvmTypeLowering.lower(veraType, visibleClasses.keys)
}
