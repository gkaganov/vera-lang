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
import vera.ast.VeraRecordDeclaration
import vera.ast.VeraReturnStatement
import vera.ast.VeraStatement
import vera.ast.VeraStringLiteral
import vera.ast.VeraType
import vera.ast.VeraUnitLiteral
import vera.ast.VeraUnitReturnBlock
import vera.ast.VeraWhileStatement
import vera.ast.render
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
import vera.jvm.ir.JvmGetField
import vera.jvm.ir.JvmIfExpression
import vera.jvm.ir.JvmImplicitReturnBlock
import vera.jvm.ir.JvmInstanceCall
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
import vera.jvm.ir.JvmRecordConstruction
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
        visibleRecords: Map<Identifier, VeraRecordDeclaration>,
        veraFunction: VeraFunctionDeclaration,
        receiverRecord: VeraRecordDeclaration? = null,
    ): JvmMethod = JvmFunctionLoweringState(className, visibleFunctions, visibleRecords, receiverRecord)
        .lower(veraFunction)
}

private class JvmFunctionLoweringState(
    private val className: Identifier,
    private val visibleFunctions: Map<Identifier, VeraFunctionDeclaration>,
    private val visibleRecords: Map<Identifier, VeraRecordDeclaration>,
    private val receiverRecord: VeraRecordDeclaration?,
) {

    private val receiverLocal: JvmLocal? = receiverRecord?.let { record ->
        JvmLocal(Identifier("<this>"), JvmType.ClassType(record.name))
    }

    private val reservedNames: Set<Identifier> = buildSet {
        addAll(visibleFunctions.keys)
        addAll(visibleRecords.keys)
        addAll(JvmFunctionLowering.builtinNames())
        receiverRecord?.let { record ->
            addAll(record.fields.map { field -> field.name })
            addAll(record.methods.map { method -> method.name })
        }
    }

    private data class LoweredStatement(val jvmStatement: JvmStatement, val returns: Boolean)

    private val declaredNames = mutableSetOf<Identifier>()
    private val jvmLocals = mutableMapOf<Identifier, JvmLocal>()
    private lateinit var currentReturnType: JvmType

    fun lower(veraFunction: VeraFunctionDeclaration): JvmMethod {
        currentReturnType = JvmTypeLowering.lowerReturn(veraFunction.returnType, visibleRecords.keys)

        val jvmParameters = veraFunction.parameters
            .map { veraParameter ->
                requireFreshName(veraParameter.name)
                JvmParameter(veraParameter.name, lowerType(veraParameter.type))
            }
        veraFunction.parameters.forEach { veraParameter ->
            jvmLocals[veraParameter.name] = JvmLocal(veraParameter.name, lowerType(veraParameter.type))
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
            currentReturnType,
            JvmExplicitReturnBlock(jvmStatements),
            receiver = receiverLocal,
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
                if (jvmReturnExpression == null && currentReturnType != JvmUnitType) {
                    throw JvmLoweringException.MissingReturnValue(currentReturnType)
                }
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
        requireFreshName(veraBindStatement.name)
        val jvmExpression = lowerExpression(veraBindStatement.expression)
        val jvmExpressionType = jvmExpression.type
        if (veraBindStatement.type != VeraType.UNKNOWN && lowerType(veraBindStatement.type) != jvmExpressionType) {
            throw JvmLoweringException.BindTypeMismatch(veraBindStatement.type.render(), jvmExpressionType)
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
                is PrimaryResolution.Pending -> {
                    if (visibleRecords.containsKey(resolution.identifier)) {
                        throw JvmLoweringException.RecordTypeUsedAsValue(resolution.identifier)
                    }
                    val receiver = receiverRecord
                    if (receiver != null && receiver.methods.any { it.name == resolution.identifier }) {
                        throw JvmLoweringException.RecordMethodUsedAsValue(receiver.name, resolution.identifier)
                    }
                    throw JvmLoweringException.UnexpectedPendingSymbol()
                }
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

            VeraOperator.FORWARD_APPLICATION -> return lowerForwardApplication(veraOperation)
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
            VeraOperator.AND, VeraOperator.OR, VeraOperator.FORWARD_APPLICATION ->
                throw JvmLoweringException.InternalInvariant("unreachable binary operator")
        }
    }

    private fun lowerForwardApplication(veraOperation: VeraBinaryOperation): JvmExpression {
        return when (val veraRight = veraOperation.right) {
            is VeraIdentifierAsExpression -> {
                val jvmArguments = listOf(lowerPrimaryExpression(veraOperation.left))
                lowerNamedCall(veraRight.identifier, jvmArguments)
            }

            is VeraChainedExpression -> lowerChainedExpression(veraRight) {
                lowerPrimaryExpression(veraOperation.left)
            }

            else -> throw JvmLoweringException.InvalidForwardApplication(
                JvmForwardApplicationIssue.RIGHT_SIDE_NOT_CALLABLE,
            )
        }
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
                    if (visibleRecords.containsKey(veraName)) {
                        throw JvmLoweringException.RecordTypeUsedAsValue(veraName)
                    }
                    val receiver = receiverRecord
                    if (receiver != null && receiver.methods.any { it.name == veraName }) {
                        throw JvmLoweringException.RecordMethodUsedAsValue(receiver.name, veraName)
                    }
                    throw JvmLoweringException.UnexpectedPendingSymbol()
                }

                val jvmArguments = lowerForwardedArguments(
                    forwardedArgument,
                    forwardedArgumentIndex == 0,
                ) { lowerArguments(firstData) }
                jvmExpression = lowerNamedCall(veraName, jvmArguments)
                dataIndex = 1
            }
        }

        while (dataIndex < veraChainedExpression.data.size) {
            when (val chainedData = veraChainedExpression.data[dataIndex]) {
                is VeraMemberAccess -> {
                    val ownerType = jvmExpression.type as? JvmType.ClassType
                        ?: throw JvmLoweringException.MemberAccessRequiresRecord(jvmExpression.type)
                    val veraRecord = visibleRecords[ownerType.name]
                        ?: throw JvmLoweringException.UnknownRecordType(ownerType.name)
                    val member = chainedData.member
                    val nextData = veraChainedExpression.data.getOrNull(dataIndex + 1)
                    val veraField = veraRecord.fields.firstOrNull { it.name == member }
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
                        val veraMethod = veraRecord.methods.firstOrNull { it.name == member }
                        if (veraMethod == null) {
                            if (nextData is VeraArguments) {
                                throw JvmLoweringException.UnknownRecordMethod(ownerType.name, member)
                            }
                            throw JvmLoweringException.UnknownRecordField(ownerType.name, member)
                        }
                        if (nextData is VeraArguments) {
                            val jvmArguments = lowerForwardedArguments(
                                forwardedArgument,
                                forwardedArgumentIndex == dataIndex + 1,
                            ) { lowerArguments(nextData) }
                            jvmExpression = lowerInstanceMethodCall(
                                jvmExpression,
                                veraRecord,
                                veraMethod,
                                jvmArguments,
                            )
                            dataIndex += 2
                        } else {
                            throw JvmLoweringException.RecordMethodUsedAsValue(ownerType.name, member)
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
        lowerExplicitArguments: () -> List<JvmExpression>,
    ): List<JvmExpression> {
        if (!shouldInject) {
            return lowerExplicitArguments()
        }
        val forward = forwardedArgument
            ?: throw JvmLoweringException.InternalInvariant("missing forwarded argument")
        return listOf(forward()) + lowerExplicitArguments()
    }

    private fun lowerNamedCall(
        veraName: Identifier,
        jvmArguments: List<JvmExpression>,
    ): JvmExpression {
        val veraRecord = visibleRecords[veraName]
        val receiver = receiverRecord
        val receiverMethod = receiver?.methods?.firstOrNull { it.name == veraName }
        return when {
            veraRecord != null -> lowerRecordConstruction(veraRecord, jvmArguments)
            receiver != null && receiverMethod != null ->
                lowerInstanceMethodCall(readReceiver(), receiver, receiverMethod, jvmArguments)
            else -> lowerFunctionCall(veraName, jvmArguments)
        }
    }

    private fun requireFreshName(name: Identifier) {
        if (name in reservedNames || !declaredNames.add(name)) {
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
        veraRecord: VeraRecordDeclaration,
        veraMethod: VeraFunctionDeclaration,
        jvmArguments: List<JvmExpression>,
    ): JvmExpression {
        val jvmParameters = veraMethod.parameters.map { veraParameter ->
            JvmParameter(veraParameter.name, lowerType(veraParameter.type))
        }
        if (jvmArguments.size != jvmParameters.size) {
            throw JvmLoweringException.MethodArityMismatch(
                veraRecord.name,
                veraMethod.name,
                jvmParameters.size,
                jvmArguments.size,
            )
        }
        jvmArguments.forEachIndexed { index, jvmArgument ->
            val expectedType = jvmParameters[index].type
            if (jvmArgument.type != expectedType) {
                throw JvmLoweringException.MethodArgumentTypeMismatch(
                    veraRecord.name,
                    veraMethod.name,
                    veraMethod.parameters[index].name,
                    expectedType,
                    jvmArgument.type,
                )
            }
        }
        return JvmInstanceCall(
            receiver = jvmReceiver,
            ownerClass = veraRecord.name,
            methodName = veraMethod.name,
            arguments = jvmArguments,
            signature = JvmMethodSignature(
                jvmParameters,
                JvmTypeLowering.lowerReturn(veraMethod.returnType, visibleRecords.keys),
            ),
        )
    }

    private fun lowerRecordConstruction(
        veraRecord: VeraRecordDeclaration,
        jvmArguments: List<JvmExpression>,
    ): JvmExpression {
        if (jvmArguments.size != veraRecord.fields.size) {
            throw JvmLoweringException.RecordConstructorArityMismatch(
                veraRecord.name,
                veraRecord.fields.size,
                jvmArguments.size,
            )
        }

        val jvmFieldTypes = veraRecord.fields.map { field -> lowerType(field.type) }
        jvmArguments.forEachIndexed { index, jvmArgument ->
            val expectedType = jvmFieldTypes[index]
            if (jvmArgument.type != expectedType) {
                throw JvmLoweringException.RecordConstructorArgumentTypeMismatch(
                    veraRecord.name,
                    veraRecord.fields[index].name,
                    expectedType,
                    jvmArgument.type,
                )
            }
        }

        val jvmParameters = veraRecord.fields.mapIndexed { index, field ->
            JvmParameter(field.name, jvmFieldTypes[index])
        }
        return JvmRecordConstruction(
            recordName = veraRecord.name,
            arguments = jvmArguments,
            signature = JvmMethodSignature(jvmParameters, JvmType.VOID),
        )
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
                JvmParameter(veraParameter.name, lowerType(veraParameter.type))
            },
            JvmTypeLowering.lowerReturn(veraFunctionDeclaration.returnType, visibleRecords.keys),
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
                val argumentType = jvmArguments.first().type
                if (argumentType != JvmType.INT && argumentType != JvmType.STRING && argumentType != JvmType.BOOL) {
                    throw JvmLoweringException.PrintArgumentTypeNotSupported(argumentType)
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

            is VeraUnitLiteral -> PrimaryResolution.Resolved(JvmUnitLiteral)

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
                    resolveReceiverField(veraIdentifier) ?: PrimaryResolution.Pending(veraIdentifier)
                }
            }

            is VeraBinaryOperation -> PrimaryResolution.Resolved(lowerBinaryOperation(veraPrimaryExpression))

            is VeraChainedExpression -> PrimaryResolution.Resolved(lowerChainedExpression(veraPrimaryExpression))
        }
    }

    private fun resolveReceiverField(identifier: Identifier): PrimaryResolution.Resolved? {
        val jvmReceiver = receiverLocal ?: return null
        val record = receiverRecord ?: return null
        val veraField = record.fields.firstOrNull { it.name == identifier } ?: return null
        return PrimaryResolution.Resolved(
            JvmGetField(
                receiver = JvmLocalRead(jvmReceiver),
                ownerClass = record.name,
                fieldName = identifier,
                fieldType = lowerType(veraField.type),
            ),
        )
    }

    private fun lowerType(veraType: VeraType): JvmType =
        JvmTypeLowering.lower(veraType, visibleRecords.keys)
}
