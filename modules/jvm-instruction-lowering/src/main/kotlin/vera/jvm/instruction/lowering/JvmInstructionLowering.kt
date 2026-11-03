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
import vera.jvm.ir.JvmBoolLiteral
import vera.jvm.ir.JvmAssign
import vera.jvm.instruction.BindLabel
import vera.jvm.instruction.CreateLabel
import vera.jvm.instruction.GetStatic
import vera.jvm.instruction.IfFalseJumpTo
import vera.jvm.instruction.IntBinaryOperation
import vera.jvm.instruction.IntComparisonJumpTo
import vera.jvm.instruction.InvokeVirtual
import vera.jvm.instruction.Invokestatic
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
    private lateinit var jvmMethodBuilder: JvmInstructionMethodBuilder

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
        jvmMethodBuilder = JvmInstructionMethodBuilder(
            jvmMethod.name,
            jvmMethod.parameters,
            jvmMethod.returnType,
        )

        jvmMethod.parameters.forEachIndexed { index, jvmParameter ->
            jvmLocalSlots[JvmLocal(jvmParameter.name, jvmParameter.type)] =
                jvmMethodBuilder.parameterLocal(index)
        }

        jvmMethod.body.forEach { jvmStatement -> lowerStatement(jvmStatement) }
        return jvmMethodBuilder.build()
    }

    private fun lowerStatement(jvmStatement: JvmStatement) {
        when (jvmStatement) {
            is JvmReturn -> {
                val jvmReturnType = jvmStatement.expression
                    ?.let { jvmExpression -> lowerExpression(jvmExpression) }
                    ?: JvmType.VOID
                jvmMethodBuilder.addInstruction(Return(jvmReturnType))
            }

            is JvmVarDeclaration -> {
                lowerExpression(jvmStatement.expression)
                val jvmLocal = jvmMethodBuilder.allocateLocal(jvmStatement.local.type)
                jvmLocalSlots[jvmStatement.local] = jvmLocal
                jvmMethodBuilder.addInstruction(StoreLocal(jvmLocal))
            }

            is JvmAssign -> TODO()
            is JvmRebind -> TODO()

            is JvmExpressionStatement -> {
                lowerExpression(jvmStatement.expression)
            }
        }
    }

    private fun lowerExpression(jvmExpression: JvmExpression): JvmType {
        return when (jvmExpression) {
            is JvmIntLiteral -> {
                jvmMethodBuilder.addInstruction(LoadConstant(JvmType.INT, jvmExpression.value))
                JvmType.INT
            }

            is JvmStringLiteral -> {
                jvmMethodBuilder.addInstruction(LoadConstant(JvmType.STRING, jvmExpression.value))
                JvmType.STRING
            }

            is JvmBoolLiteral -> {
                jvmMethodBuilder.addInstruction(LoadConstant(JvmType.BOOL, jvmExpression.value))
                JvmType.BOOL
            }

            is JvmLocalRead -> {
                val jvmLocal = jvmLocalSlots[jvmExpression.local]
                    ?: error("local ${jvmExpression.local.name.value} is not defined")
                jvmMethodBuilder.addInstruction(LoadLocal(jvmLocal))
                jvmLocal.jvmType
            }

            is JvmIntBinary -> {
                lowerExpression(jvmExpression.left)
                lowerExpression(jvmExpression.right)
                jvmMethodBuilder.addInstruction(IntBinaryOperation(jvmExpression.operator))
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
                jvmMethodBuilder.addInstruction(
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
        val jvmResultLocal = jvmMethodBuilder.allocateLocal(JvmType.BOOL)
        val jvmTrueLabel = JvmLabel()
        val jvmEndLabel = JvmLabel()

        jvmMethodBuilder.addInstruction(CreateLabel(jvmTrueLabel))
        jvmMethodBuilder.addInstruction(CreateLabel(jvmEndLabel))
        jvmMethodBuilder.addInstruction(IntComparisonJumpTo(jvmOperator, jvmTrueLabel))
        jvmMethodBuilder.addInstruction(LoadConstant(JvmType.BOOL, false))
        jvmMethodBuilder.addInstruction(StoreLocal(jvmResultLocal))
        jvmMethodBuilder.addInstruction(JumpTo(jvmEndLabel))
        jvmMethodBuilder.addInstruction(BindLabel(jvmTrueLabel))
        jvmMethodBuilder.addInstruction(LoadConstant(JvmType.BOOL, true))
        jvmMethodBuilder.addInstruction(StoreLocal(jvmResultLocal))
        jvmMethodBuilder.addInstruction(BindLabel(jvmEndLabel))
        jvmMethodBuilder.addInstruction(LoadLocal(jvmResultLocal))
    }

    private fun lowerLogicalAnd(jvmExpression: JvmLogicalAnd) {
        val jvmResultLocal = jvmMethodBuilder.allocateLocal(JvmType.BOOL)
        val jvmFalseLabel = JvmLabel()
        val jvmEndLabel = JvmLabel()

        lowerExpression(jvmExpression.left)

        jvmMethodBuilder.addInstruction(CreateLabel(jvmFalseLabel))
        jvmMethodBuilder.addInstruction(IfFalseJumpTo(jvmFalseLabel))

        lowerExpression(jvmExpression.right)
        jvmMethodBuilder.addInstruction(StoreLocal(jvmResultLocal))
        jvmMethodBuilder.addInstruction(CreateLabel(jvmEndLabel))
        jvmMethodBuilder.addInstruction(JumpTo(jvmEndLabel))

        jvmMethodBuilder.addInstruction(BindLabel(jvmFalseLabel))
        jvmMethodBuilder.addInstruction(LoadConstant(JvmType.BOOL, false))
        jvmMethodBuilder.addInstruction(StoreLocal(jvmResultLocal))

        jvmMethodBuilder.addInstruction(BindLabel(jvmEndLabel))
        jvmMethodBuilder.addInstruction(LoadLocal(jvmResultLocal))
    }

    private fun lowerLogicalOr(jvmExpression: JvmLogicalOr) {
        val jvmResultLocal = jvmMethodBuilder.allocateLocal(JvmType.BOOL)
        val jvmRightLabel = JvmLabel()
        val jvmEndLabel = JvmLabel()

        lowerExpression(jvmExpression.left)

        jvmMethodBuilder.addInstruction(CreateLabel(jvmRightLabel))
        jvmMethodBuilder.addInstruction(IfFalseJumpTo(jvmRightLabel))
        jvmMethodBuilder.addInstruction(LoadConstant(JvmType.BOOL, true))
        jvmMethodBuilder.addInstruction(StoreLocal(jvmResultLocal))
        jvmMethodBuilder.addInstruction(CreateLabel(jvmEndLabel))
        jvmMethodBuilder.addInstruction(JumpTo(jvmEndLabel))

        jvmMethodBuilder.addInstruction(BindLabel(jvmRightLabel))
        lowerExpression(jvmExpression.right)
        jvmMethodBuilder.addInstruction(StoreLocal(jvmResultLocal))

        jvmMethodBuilder.addInstruction(BindLabel(jvmEndLabel))
        jvmMethodBuilder.addInstruction(LoadLocal(jvmResultLocal))
    }

    private fun lowerBuiltinCall(jvmBuiltinCall: JvmBuiltinCall): JvmType {
        return when (jvmBuiltinCall.builtin) {
            JvmBuiltin.PRINT -> {
                if (jvmBuiltinCall.arguments.size != 1) error("print requires exactly 1 argument")
                jvmMethodBuilder.addInstruction(
                    GetStatic(
                        Identifier("java.lang.System"),
                        Identifier("out"),
                        JvmType.OBJECT,
                        Identifier("java.io.PrintStream"),
                    )
                )
                val jvmArgumentType = lowerExpression(jvmBuiltinCall.arguments.first())
                jvmMethodBuilder.addInstruction(
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
        jvmMethodBuilder.addInstruction(CreateLabel(jvmElseLabel))
        jvmMethodBuilder.addInstruction(IfFalseJumpTo(jvmElseLabel))
        jvmIfExpression.thenBlock.forEach { jvmStatement -> lowerStatement(jvmStatement) }

        if (jvmIfExpression.elseBlock.isEmpty()) {
            jvmMethodBuilder.addInstruction(BindLabel(jvmElseLabel))
        } else {
            val jvmEndLabel = JvmLabel()
            jvmMethodBuilder.addInstruction(CreateLabel(jvmEndLabel))
            jvmMethodBuilder.addInstruction(JumpTo(jvmEndLabel))

            jvmMethodBuilder.addInstruction(BindLabel(jvmElseLabel))
            jvmIfExpression.elseBlock.forEach { jvmStatement -> lowerStatement(jvmStatement) }

            jvmMethodBuilder.addInstruction(BindLabel(jvmEndLabel))
        }
    }

}
