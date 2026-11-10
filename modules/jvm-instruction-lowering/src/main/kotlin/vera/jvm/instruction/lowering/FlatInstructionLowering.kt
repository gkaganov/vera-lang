package vera.jvm.instruction.lowering

import vera.jvm.ir.JvmComparisonOperator
import vera.jvm.ir.JvmBlock
import vera.jvm.ir.JvmBuiltin
import vera.jvm.ir.JvmBuiltinCall
import vera.jvm.ir.JvmClass
import vera.jvm.ir.JvmComparison
import vera.jvm.ir.JvmConstructor
import vera.jvm.ir.JvmExplicitReturnBlock
import vera.jvm.ir.JvmExpression
import vera.jvm.ir.JvmExpressionStatement
import vera.jvm.ir.JvmGetField
import vera.jvm.ir.JvmIfExpression
import vera.jvm.ir.JvmImplicitReturnBlock
import vera.jvm.ir.JvmIntBinary
import vera.jvm.ir.JvmIntLiteral
import vera.jvm.ir.JvmIntUnary
import vera.jvm.ir.JvmInstanceCall
import vera.jvm.ir.JvmLocal
import vera.jvm.ir.JvmLocalRead
import vera.jvm.ir.JvmLogicalAnd
import vera.jvm.ir.JvmLogicalOr
import vera.jvm.ir.JvmMethod
import vera.jvm.ir.JvmMethodSignature
import vera.jvm.ir.JvmPutStatic
import vera.jvm.ir.JvmParameter
import vera.jvm.ir.JvmProgram
import vera.jvm.ir.JvmRebind
import vera.jvm.ir.JvmRecordConstruction
import vera.jvm.ir.JvmReturn
import vera.jvm.ir.JvmStatement
import vera.jvm.ir.JvmStaticCall
import vera.jvm.ir.JvmStringLiteral
import vera.jvm.ir.JvmType
import vera.jvm.ir.JvmUnitLiteral
import vera.jvm.ir.JvmUnitReturnBlock
import vera.jvm.ir.JvmUnitType
import vera.jvm.ir.JvmVarDeclaration
import vera.jvm.ir.JvmWhile
import vera.jvm.ir.JvmBoolLiteral
import vera.jvm.instruction.FlatBindLabel
import vera.jvm.instruction.FlatBoolEqualityJump
import vera.jvm.instruction.FlatCreateLabel
import vera.jvm.instruction.FlatDup
import vera.jvm.instruction.FlatField
import vera.jvm.instruction.FlatGetField
import vera.jvm.instruction.FlatGetStatic
import vera.jvm.instruction.FlatIfFalseJump
import vera.jvm.instruction.FlatIntBinaryOperation
import vera.jvm.instruction.FlatIntComparisonJump
import vera.jvm.instruction.FlatIntUnaryOperation
import vera.jvm.instruction.FlatInvokeSpecial
import vera.jvm.instruction.FlatInvokeVirtual
import vera.jvm.instruction.FlatInvokestatic
import vera.jvm.instruction.FlatInstruction
import vera.jvm.instruction.FlatClass
import vera.jvm.instruction.FlatInstructionException
import vera.jvm.instruction.FlatMethod
import vera.jvm.instruction.FlatMethodBuilder
import vera.jvm.instruction.FlatProgram
import vera.jvm.instruction.FlatLabel
import vera.jvm.instruction.FlatJump
import vera.jvm.instruction.FlatLoadConstant
import vera.jvm.instruction.FlatLoadLocal
import vera.jvm.instruction.FlatLocalTable
import vera.jvm.instruction.FlatNew
import vera.jvm.instruction.FlatPop
import vera.jvm.instruction.FlatPutField
import vera.jvm.instruction.FlatPutStatic
import vera.jvm.instruction.FlatReturn
import vera.jvm.instruction.FlatStoreLocal
import vera.jvm.instruction.FlatStringEqualityJump
import vera.shared.model.Identifier

object FlatInstructionLowering {

    fun lower(jvmProgram: JvmProgram): FlatProgram =
        FlatInstructionLoweringState().lower(jvmProgram)
}

private class FlatInstructionLoweringState {

    private val flatLocalSlots = mutableMapOf<JvmLocal, FlatLocalTable.Local>()
    private lateinit var flatBuilder: FlatMethodBuilder
    private var currentReturnType: JvmType = JvmType.VOID

    fun lower(jvmProgram: JvmProgram): FlatProgram {
        val instructionClasses = mutableListOf<FlatClass>()
        jvmProgram.classes.forEach { jvmClass ->
            instructionClasses += lowerClass(jvmClass)
        }
        return FlatProgram(instructionClasses)
    }

    private fun lowerClass(jvmClass: JvmClass): FlatClass {
        val flatFields = jvmClass.fields.map { jvmField ->
            FlatField(jvmField.name, jvmField.type, jvmField.isStatic)
        }
        val instructionMethods = mutableListOf<FlatMethod>()
        jvmClass.constructors.forEach { jvmConstructor ->
            instructionMethods += lowerConstructor(jvmClass, jvmConstructor)
        }
        jvmClass.methods.forEach { jvmMethod ->
            instructionMethods += lowerMethod(jvmMethod)
        }
        return FlatClass(jvmClass.name, instructionMethods, flatFields)
    }

    private fun lowerConstructor(jvmClass: JvmClass, jvmConstructor: JvmConstructor): FlatMethod {
        flatLocalSlots.clear()
        currentReturnType = JvmType.VOID
        val receiverType = JvmType.ClassType(jvmClass.name)
        flatBuilder = FlatMethodBuilder(
            Identifier("<init>"),
            jvmConstructor.parameters,
            JvmType.VOID,
            receiverType,
        )
        val receiver = flatBuilder.receiverLocal()
        append(FlatLoadLocal(receiver))
        append(
            FlatInvokeSpecial(
                receiverType = receiverType,
                ownerClass = Identifier("java.lang.Object"),
                methodName = Identifier("<init>"),
                methodSignature = JvmMethodSignature(emptyList(), JvmType.VOID),
            ),
        )
        jvmClass.fields.filterNot { jvmField -> jvmField.isStatic }
            .forEachIndexed { index, jvmField ->
                append(FlatLoadLocal(receiver))
                append(FlatLoadLocal(flatBuilder.parameterLocal(index)))
                append(FlatPutField(jvmClass.name, jvmField.name, jvmField.type))
            }
        append(FlatReturn(JvmType.VOID))
        return flatBuilder.build()
    }

    private fun lowerMethod(jvmMethod: JvmMethod): FlatMethod {
        flatLocalSlots.clear()
        currentReturnType = jvmMethod.returnType
        flatBuilder = FlatMethodBuilder(
            jvmMethod.name,
            jvmMethod.parameters,
            jvmMethod.returnType,
            jvmMethod.receiver?.type,
        )

        val jvmReceiver = jvmMethod.receiver
        if (jvmReceiver != null) {
            flatLocalSlots[jvmReceiver] = flatBuilder.receiverLocal()
        }
        jvmMethod.parameters.forEachIndexed { index, jvmParameter ->
            val local = flatBuilder.parameterLocal(index)
            flatLocalSlots[JvmLocal(jvmParameter.name, jvmParameter.type)] = local
        }

        jvmMethod.body.statements.forEach { jvmStatement ->
            lowerStatement(jvmStatement)
        }
        return flatBuilder.build()
    }

    private fun lowerStatement(jvmStatement: JvmStatement) {
        when (jvmStatement) {
            is JvmReturn -> {
                val jvmReturnExpression = jvmStatement.expression
                val jvmReturnType = if (jvmReturnExpression != null) {
                    lowerExpression(jvmReturnExpression)
                } else {
                    if (currentReturnType == JvmUnitType) {
                        emitUnitValue()
                    }
                    currentReturnType
                }
                append(FlatReturn(jvmReturnType))
            }

            is JvmPutStatic -> {
                lowerExpression(jvmStatement.value)
                append(
                    FlatPutStatic(
                        jvmStatement.ownerClass,
                        jvmStatement.fieldName,
                        jvmStatement.fieldType,
                        jvmStatement.fieldDescriptor,
                    ),
                )
            }

            is JvmVarDeclaration -> {
                lowerExpression(jvmStatement.expression)
                val flatLocal = flatBuilder.allocateLocal(jvmStatement.local.type)
                flatLocalSlots[jvmStatement.local] = flatLocal
                append(FlatStoreLocal(flatLocal))
            }

            is JvmRebind -> {
                lowerExpression(jvmStatement.expression)
                val flatLocal = flatLocalSlots[jvmStatement.local]
                    ?: throw FlatInstructionException.UndefinedLocal(jvmStatement.local.name)
                append(FlatStoreLocal(flatLocal))
            }

            is JvmExpressionStatement -> {
                val jvmExpressionType = lowerExpression(jvmStatement.expression)
                if (jvmExpressionType != JvmType.VOID) {
                    append(FlatPop(jvmExpressionType))
                }
            }

            is JvmWhile -> {
                lowerWhile(jvmStatement)
            }
        }
    }

    private fun lowerWhile(jvmWhile: JvmWhile) {
        val flatConditionLabel = FlatLabel()
        val flatEndLabel = FlatLabel()

        createLabel(flatConditionLabel)
        createLabel(flatEndLabel)
        bindLabel(flatConditionLabel)
        lowerExpression(jvmWhile.condition)
        jumpIfFalse(flatEndLabel)
        jvmWhile.body.statements.forEach { jvmStatement ->
            lowerStatement(jvmStatement)
        }
        jump(flatConditionLabel)
        bindLabel(flatEndLabel)
    }

    private fun lowerExpression(jvmExpression: JvmExpression): JvmType {
        return when (jvmExpression) {
            is JvmIntLiteral -> {
                append(FlatLoadConstant(JvmType.INT, jvmExpression.value))
                JvmType.INT
            }

            is JvmStringLiteral -> {
                append(FlatLoadConstant(JvmType.STRING, jvmExpression.value))
                JvmType.STRING
            }

            is JvmBoolLiteral -> {
                append(FlatLoadConstant(JvmType.BOOL, jvmExpression.value))
                JvmType.BOOL
            }

            is JvmUnitLiteral -> {
                emitUnitValue()
                JvmUnitType
            }

            is JvmLocalRead -> {
                val flatLocal = flatLocalSlots[jvmExpression.local]
                    ?: throw FlatInstructionException.UndefinedLocal(jvmExpression.local.name)
                append(FlatLoadLocal(flatLocal))
                flatLocal.jvmType
            }

            is JvmIntUnary -> {
                lowerExpression(jvmExpression.operand)
                append(FlatIntUnaryOperation(jvmExpression.operator))
                JvmType.INT
            }

            is JvmIntBinary -> {
                lowerExpression(jvmExpression.left)
                lowerExpression(jvmExpression.right)
                append(FlatIntBinaryOperation(jvmExpression.operator))
                JvmType.INT
            }

            is JvmComparison -> {
                val operandType = jvmExpression.left.type
                lowerExpression(jvmExpression.left)
                lowerExpression(jvmExpression.right)
                lowerComparison(operandType, jvmExpression.operator)
                JvmType.BOOL
            }

            is JvmLogicalAnd -> {
                lowerLogicalAnd(jvmExpression)
                JvmType.BOOL
            }

            is JvmLogicalOr -> {
                lowerLogicalOr(jvmExpression)
                JvmType.BOOL
            }

            is JvmRecordConstruction -> {
                val classType = JvmType.ClassType(jvmExpression.recordName)
                append(FlatNew(classType))
                append(FlatDup(classType))
                jvmExpression.arguments.forEach { jvmArgument ->
                    lowerExpression(jvmArgument)
                }
                append(
                    FlatInvokeSpecial(
                        receiverType = classType,
                        ownerClass = jvmExpression.recordName,
                        methodName = Identifier("<init>"),
                        methodSignature = jvmExpression.signature,
                    ),
                )
                classType
            }

            is JvmGetField -> {
                lowerExpression(jvmExpression.receiver)
                append(
                    FlatGetField(
                        jvmExpression.ownerClass,
                        jvmExpression.fieldName,
                        jvmExpression.fieldType,
                    ),
                )
                jvmExpression.fieldType
            }

            is JvmStaticCall -> {
                jvmExpression.arguments.forEach { jvmArgument ->
                    lowerExpression(jvmArgument)
                }
                append(
                    FlatInvokestatic(
                        jvmExpression.ownerClass,
                        jvmExpression.methodName,
                        jvmExpression.signature,
                    ),
                )
                jvmExpression.signature.returnType
            }

            is JvmInstanceCall -> {
                lowerExpression(jvmExpression.receiver)
                jvmExpression.arguments.forEach { jvmArgument ->
                    lowerExpression(jvmArgument)
                }
                append(
                    FlatInvokeVirtual(
                        jvmExpression.ownerClass,
                        jvmExpression.methodName,
                        jvmExpression.signature,
                        receiverType = jvmExpression.receiver.type,
                    ),
                )
                jvmExpression.signature.returnType
            }

            is JvmBuiltinCall -> lowerBuiltinCall(jvmExpression)

            is JvmIfExpression -> {
                lowerIfExpression(jvmExpression)
                jvmExpression.type
            }
        }
    }

    private fun lowerComparison(operandType: JvmType, jvmOperator: JvmComparisonOperator) {
        val flatResultLocal = flatBuilder.allocateLocal(JvmType.BOOL)
        val flatTrueLabel = FlatLabel()
        val flatEndLabel = FlatLabel()

        createLabel(flatTrueLabel)
        createLabel(flatEndLabel)
        when (operandType) {
            JvmType.INT -> jumpIfIntComparison(jvmOperator, flatTrueLabel)
            JvmType.BOOL -> jumpIfBoolEquality(jvmOperator, flatTrueLabel)
            JvmType.STRING -> jumpIfStringEquality(jvmOperator, flatTrueLabel)
            else -> throw FlatInstructionException.InternalInvariant(
                "unsupported comparison operand type $operandType",
            )
        }
        append(FlatLoadConstant(JvmType.BOOL, false))
        append(FlatStoreLocal(flatResultLocal))
        jump(flatEndLabel)
        bindLabel(flatTrueLabel)
        append(FlatLoadConstant(JvmType.BOOL, true))
        append(FlatStoreLocal(flatResultLocal))
        bindLabel(flatEndLabel)
        append(FlatLoadLocal(flatResultLocal))
    }

    private fun lowerLogicalAnd(jvmExpression: JvmLogicalAnd) {
        val flatResultLocal = flatBuilder.allocateLocal(JvmType.BOOL)
        val flatFalseLabel = FlatLabel()
        val flatEndLabel = FlatLabel()

        lowerExpression(jvmExpression.left)

        createLabel(flatFalseLabel)
        jumpIfFalse(flatFalseLabel)

        lowerExpression(jvmExpression.right)
        append(FlatStoreLocal(flatResultLocal))
        createLabel(flatEndLabel)
        jump(flatEndLabel)

        bindLabel(flatFalseLabel)
        append(FlatLoadConstant(JvmType.BOOL, false))
        append(FlatStoreLocal(flatResultLocal))

        bindLabel(flatEndLabel)
        append(FlatLoadLocal(flatResultLocal))
    }

    private fun lowerLogicalOr(jvmExpression: JvmLogicalOr) {
        val flatResultLocal = flatBuilder.allocateLocal(JvmType.BOOL)
        val flatRightLabel = FlatLabel()
        val flatEndLabel = FlatLabel()

        lowerExpression(jvmExpression.left)

        createLabel(flatRightLabel)
        jumpIfFalse(flatRightLabel)
        append(FlatLoadConstant(JvmType.BOOL, true))
        append(FlatStoreLocal(flatResultLocal))
        createLabel(flatEndLabel)
        jump(flatEndLabel)

        bindLabel(flatRightLabel)
        lowerExpression(jvmExpression.right)
        append(FlatStoreLocal(flatResultLocal))

        bindLabel(flatEndLabel)
        append(FlatLoadLocal(flatResultLocal))
    }

    private fun lowerBuiltinCall(jvmBuiltinCall: JvmBuiltinCall): JvmType {
        return when (jvmBuiltinCall.builtin) {
            JvmBuiltin.PRINT -> {
                if (jvmBuiltinCall.arguments.size != 1) {
                    throw FlatInstructionException.BuiltinArityMismatch(1, jvmBuiltinCall.arguments.size)
                }
                append(
                    FlatGetStatic(
                        Identifier("java.lang.System"),
                        Identifier("out"),
                        JvmType.OBJECT,
                        Identifier("java.io.PrintStream"),
                    ),
                )
                val jvmArgumentType = lowerExpression(jvmBuiltinCall.arguments.first())
                append(
                    FlatInvokeVirtual(
                        Identifier("java.io.PrintStream"),
                        Identifier("println"),
                        JvmMethodSignature(
                            listOf(JvmParameter(Identifier("value"), jvmArgumentType)),
                            JvmType.VOID,
                        ),
                    ),
                )
                emitUnitValue()
                JvmUnitType
            }
        }
    }

    private fun lowerIfExpression(jvmIfExpression: JvmIfExpression) {
        lowerExpression(jvmIfExpression.condition)

        val flatElseLabel = FlatLabel()
        createLabel(flatElseLabel)
        jumpIfFalse(flatElseLabel)

        val jvmElseBlock = jvmIfExpression.elseBlock
        if (jvmElseBlock == null) {
            lowerBlock(jvmIfExpression.thenBlock, discardResult = true)
            bindLabel(flatElseLabel)
            emitUnitValue()
            return
        }

        val flatResultLocal = if (jvmIfExpression.type == JvmType.VOID) {
            null
        } else {
            flatBuilder.allocateLocal(jvmIfExpression.type)
        }

        lowerBlock(jvmIfExpression.thenBlock, discardResult = flatResultLocal == null)
        if (flatResultLocal != null) {
            append(FlatStoreLocal(flatResultLocal))
        }

        val flatEndLabel = FlatLabel()
        createLabel(flatEndLabel)
        jump(flatEndLabel)

        bindLabel(flatElseLabel)
        lowerBlock(jvmElseBlock, discardResult = flatResultLocal == null)
        if (flatResultLocal != null) {
            append(FlatStoreLocal(flatResultLocal))
        }

        bindLabel(flatEndLabel)

        if (flatResultLocal != null) {
            append(FlatLoadLocal(flatResultLocal))
        }
    }

    private fun lowerBlock(jvmBlock: JvmBlock, discardResult: Boolean) {
        jvmBlock.statements.forEach { jvmStatement ->
            lowerStatement(jvmStatement)
        }
        when (jvmBlock) {
            is JvmImplicitReturnBlock -> {
                val jvmResultType = lowerExpression(jvmBlock.result)
                if (discardResult && jvmResultType != JvmType.VOID) {
                    append(FlatPop(jvmResultType))
                }
            }

            is JvmUnitReturnBlock -> {
                if (!discardResult) {
                    emitUnitValue()
                }
            }

            is JvmExplicitReturnBlock -> {}
        }
    }

    private fun emitUnitValue() {
        append(
            FlatGetStatic(
                JvmUnitType.name,
                Identifier("INSTANCE"),
                JvmUnitType,
                Identifier("Unit"),
            ),
        )
    }

    private fun append(flatInstruction: FlatInstruction) {
        flatBuilder.addInstruction(flatInstruction)
    }

    private fun createLabel(flatLabel: FlatLabel) = append(FlatCreateLabel(flatLabel))

    private fun bindLabel(flatLabel: FlatLabel) = append(FlatBindLabel(flatLabel))

    private fun jump(flatLabel: FlatLabel) = append(FlatJump(flatLabel))

    private fun jumpIfFalse(flatLabel: FlatLabel) = append(FlatIfFalseJump(flatLabel))

    private fun jumpIfIntComparison(jvmOperator: JvmComparisonOperator, flatLabel: FlatLabel) =
        append(FlatIntComparisonJump(jvmOperator, flatLabel))

    private fun jumpIfBoolEquality(jvmOperator: JvmComparisonOperator, flatLabel: FlatLabel) =
        append(FlatBoolEqualityJump(equalityIsNegated(jvmOperator), flatLabel))

    private fun jumpIfStringEquality(jvmOperator: JvmComparisonOperator, flatLabel: FlatLabel) =
        append(FlatStringEqualityJump(equalityIsNegated(jvmOperator), flatLabel))

    private fun equalityIsNegated(jvmOperator: JvmComparisonOperator): Boolean = when (jvmOperator) {
        JvmComparisonOperator.EQ -> false
        JvmComparisonOperator.NEQ -> true
        else -> throw FlatInstructionException.InternalInvariant(
            "operator $jvmOperator is not an equality operator",
        )
    }
}
