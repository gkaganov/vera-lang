package vera.jvm.instruction.lowering

import vera.jvm.ir.ComparisonOperator
import vera.jvm.ir.JvmBuiltin
import vera.jvm.ir.JvmBuiltinCall
import vera.jvm.ir.JvmClass
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
import vera.jvm.ir.JvmProgram
import vera.jvm.ir.JvmRebind
import vera.jvm.ir.JvmReturn
import vera.jvm.ir.JvmStatement
import vera.jvm.ir.JvmStaticCall
import vera.jvm.ir.JvmStringLiteral
import vera.jvm.ir.JvmType
import vera.jvm.ir.JvmVarDeclaration
import vera.jvm.ir.JvmWhile
import vera.jvm.ir.JvmAssign
import vera.jvm.ir.JvmBoolLiteral
import vera.jvm.instruction.BindLabel
import vera.jvm.instruction.CreateLabel
import vera.jvm.instruction.GetStatic
import vera.jvm.instruction.IfFalseJumpTo
import vera.jvm.instruction.IntBinaryOperation
import vera.jvm.instruction.IntComparisonJumpTo
import vera.jvm.instruction.InvokeVirtual
import vera.jvm.instruction.Invokestatic
import vera.jvm.instruction.JvmInstruction
import vera.jvm.instruction.JvmInstructionClass
import vera.jvm.instruction.JvmInstructionMethod
import vera.jvm.instruction.JvmInstructionMethodBuilder
import vera.jvm.instruction.JvmInstructionProgram
import vera.jvm.instruction.JvmLabel
import vera.jvm.instruction.JumpTo
import vera.jvm.instruction.LoadConstant
import vera.jvm.instruction.LoadLocal
import vera.jvm.instruction.LocalTable
import vera.jvm.instruction.Return
import vera.jvm.instruction.StoreLocal
import vera.shared.model.Identifier

object JvmInstructionLowering {

    fun lower(jvmProgram: JvmProgram): JvmInstructionProgram =
        JvmInstructionLoweringState().lower(jvmProgram)
}

private class JvmInstructionLoweringState {

    private val jvmLocalSlots = mutableMapOf<JvmLocal, LocalTable.Local>()
    private lateinit var jvmBuilder: JvmInstructionMethodBuilder

    fun lower(jvmProgram: JvmProgram): JvmInstructionProgram {
        val instructionClasses = jvmProgram.classes.map { jvmClass -> lowerClass(jvmClass) }
        return JvmInstructionProgram(instructionClasses)
    }

    private fun lowerClass(jvmClass: JvmClass): JvmInstructionClass {
        val instructionMethods = jvmClass.methods.map { jvmMethod -> lowerMethod(jvmMethod) }
        return JvmInstructionClass(jvmClass.name, instructionMethods)
    }

    private fun lowerMethod(jvmMethod: JvmMethod): JvmInstructionMethod {
        jvmLocalSlots.clear()
        jvmBuilder = JvmInstructionMethodBuilder(
            jvmMethod.name,
            jvmMethod.parameters,
            jvmMethod.returnType,
        )

        jvmMethod.parameters.forEachIndexed { index, jvmParameter ->
            jvmLocalSlots[JvmLocal(jvmParameter.name, jvmParameter.type)] =
                jvmBuilder.parameterLocal(index)
        }

        jvmMethod.body.forEach { jvmStatement -> lowerStatement(jvmStatement) }
        return jvmBuilder.build()
    }

    private fun lowerStatement(jvmStatement: JvmStatement) {
        when (jvmStatement) {
            is JvmReturn -> {
                val jvmReturnType = jvmStatement.expression
                    ?.let { jvmExpression -> lowerExpression(jvmExpression) }
                    ?: JvmType.VOID
                append(Return(jvmReturnType))
            }

            is JvmVarDeclaration -> {
                lowerExpression(jvmStatement.expression)
                val jvmLocal = jvmBuilder.allocateLocal(jvmStatement.local.type)
                jvmLocalSlots[jvmStatement.local] = jvmLocal
                append(StoreLocal(jvmLocal))
            }

            is JvmAssign -> TODO()
            is JvmRebind -> TODO()

            is JvmExpressionStatement -> {
                lowerExpression(jvmStatement.expression)
            }

            is JvmWhile -> {
                lowerWhile(jvmStatement)
            }
        }
    }

    private fun lowerWhile(jvmWhile: JvmWhile) {
        val jvmConditionLabel = JvmLabel()
        val jvmEndLabel = JvmLabel()

        createLabel(jvmConditionLabel)
        createLabel(jvmEndLabel)
        bindLabel(jvmConditionLabel)
        lowerExpression(jvmWhile.condition)
        jumpIfFalse(jvmEndLabel)
        jvmWhile.body.forEach { jvmStatement -> lowerStatement(jvmStatement) }
        jump(jvmConditionLabel)
        bindLabel(jvmEndLabel)
    }

    private fun lowerExpression(jvmExpression: JvmExpression): JvmType {
        return when (jvmExpression) {
            is JvmIntLiteral -> {
                append(LoadConstant(JvmType.INT, jvmExpression.value))
                JvmType.INT
            }

            is JvmStringLiteral -> {
                append(LoadConstant(JvmType.STRING, jvmExpression.value))
                JvmType.STRING
            }

            is JvmBoolLiteral -> {
                append(LoadConstant(JvmType.BOOL, jvmExpression.value))
                JvmType.BOOL
            }

            is JvmLocalRead -> {
                val jvmLocal = jvmLocalSlots[jvmExpression.local]
                    ?: error("local ${jvmExpression.local.name.value} is not defined")
                append(LoadLocal(jvmLocal))
                jvmLocal.jvmType
            }

            is JvmIntBinary -> {
                lowerExpression(jvmExpression.left)
                lowerExpression(jvmExpression.right)
                append(IntBinaryOperation(jvmExpression.operator))
                JvmType.INT
            }

            is JvmComparison -> {
                lowerExpression(jvmExpression.left)
                lowerExpression(jvmExpression.right)
                lowerComparison(jvmExpression.operator)
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

            is JvmStaticCall -> {
                jvmExpression.arguments.forEach { jvmArgument -> lowerExpression(jvmArgument) }
                append(
                    Invokestatic(
                        jvmExpression.ownerClass,
                        jvmExpression.methodName,
                        jvmExpression.signature,
                    )
                )
                jvmExpression.signature.returnType
            }

            is JvmBuiltinCall -> {
                lowerBuiltinCall(jvmExpression)
            }

            is JvmIfExpression -> {
                lowerIfExpression(jvmExpression)
                JvmType.VOID
            }
        }
    }

    private fun lowerComparison(jvmOperator: ComparisonOperator) {
        val jvmResultLocal = jvmBuilder.allocateLocal(JvmType.BOOL)
        val jvmTrueLabel = JvmLabel()
        val jvmEndLabel = JvmLabel()

        createLabel(jvmTrueLabel)
        createLabel(jvmEndLabel)
        jumpIfIntComparison(jvmOperator, jvmTrueLabel)
        append(LoadConstant(JvmType.BOOL, false))
        append(StoreLocal(jvmResultLocal))
        jump(jvmEndLabel)
        bindLabel(jvmTrueLabel)
        append(LoadConstant(JvmType.BOOL, true))
        append(StoreLocal(jvmResultLocal))
        bindLabel(jvmEndLabel)
        append(LoadLocal(jvmResultLocal))
    }

    private fun lowerLogicalAnd(jvmExpression: JvmLogicalAnd) {
        val jvmResultLocal = jvmBuilder.allocateLocal(JvmType.BOOL)
        val jvmFalseLabel = JvmLabel()
        val jvmEndLabel = JvmLabel()

        lowerExpression(jvmExpression.left)

        createLabel(jvmFalseLabel)
        jumpIfFalse(jvmFalseLabel)

        lowerExpression(jvmExpression.right)
        append(StoreLocal(jvmResultLocal))
        createLabel(jvmEndLabel)
        jump(jvmEndLabel)

        bindLabel(jvmFalseLabel)
        append(LoadConstant(JvmType.BOOL, false))
        append(StoreLocal(jvmResultLocal))

        bindLabel(jvmEndLabel)
        append(LoadLocal(jvmResultLocal))
    }

    private fun lowerLogicalOr(jvmExpression: JvmLogicalOr) {
        val jvmResultLocal = jvmBuilder.allocateLocal(JvmType.BOOL)
        val jvmRightLabel = JvmLabel()
        val jvmEndLabel = JvmLabel()

        lowerExpression(jvmExpression.left)

        createLabel(jvmRightLabel)
        jumpIfFalse(jvmRightLabel)
        append(LoadConstant(JvmType.BOOL, true))
        append(StoreLocal(jvmResultLocal))
        createLabel(jvmEndLabel)
        jump(jvmEndLabel)

        bindLabel(jvmRightLabel)
        lowerExpression(jvmExpression.right)
        append(StoreLocal(jvmResultLocal))

        bindLabel(jvmEndLabel)
        append(LoadLocal(jvmResultLocal))
    }

    private fun lowerBuiltinCall(jvmBuiltinCall: JvmBuiltinCall): JvmType {
        return when (jvmBuiltinCall.builtin) {
            JvmBuiltin.PRINT -> {
                if (jvmBuiltinCall.arguments.size != 1) error("print requires exactly 1 argument")
                append(
                    GetStatic(
                        Identifier("java.lang.System"),
                        Identifier("out"),
                        JvmType.OBJECT,
                        Identifier("java.io.PrintStream"),
                    )
                )
                val jvmArgumentType = lowerExpression(jvmBuiltinCall.arguments.first())
                append(
                    InvokeVirtual(
                        Identifier("java.io.PrintStream"),
                        Identifier("println"),
                        JvmMethodSignature(
                            listOf(JvmParameter(Identifier("value"), jvmArgumentType)),
                            JvmType.VOID,
                        ),
                    )
                )
                JvmType.VOID
            }
        }
    }

    private fun lowerIfExpression(jvmIfExpression: JvmIfExpression) {
        lowerExpression(jvmIfExpression.condition)

        val jvmElseLabel = JvmLabel()
        createLabel(jvmElseLabel)
        jumpIfFalse(jvmElseLabel)
        jvmIfExpression.thenBlock.forEach { jvmStatement -> lowerStatement(jvmStatement) }

        if (jvmIfExpression.elseBlock.isEmpty()) {
            bindLabel(jvmElseLabel)
        } else {
            val jvmEndLabel = JvmLabel()
            createLabel(jvmEndLabel)
            jump(jvmEndLabel)

            bindLabel(jvmElseLabel)
            jvmIfExpression.elseBlock.forEach { jvmStatement -> lowerStatement(jvmStatement) }

            bindLabel(jvmEndLabel)
        }
    }

    private fun append(jvmInstruction: JvmInstruction) {
        jvmBuilder.addInstruction(jvmInstruction)
    }

    private fun createLabel(jvmLabel: JvmLabel) = append(CreateLabel(jvmLabel))

    private fun bindLabel(jvmLabel: JvmLabel) = append(BindLabel(jvmLabel))

    private fun jump(jvmLabel: JvmLabel) = append(JumpTo(jvmLabel))

    private fun jumpIfFalse(jvmLabel: JvmLabel) = append(IfFalseJumpTo(jvmLabel))

    private fun jumpIfIntComparison(jvmOperator: ComparisonOperator, jvmLabel: JvmLabel) =
        append(IntComparisonJumpTo(jvmOperator, jvmLabel))
}
