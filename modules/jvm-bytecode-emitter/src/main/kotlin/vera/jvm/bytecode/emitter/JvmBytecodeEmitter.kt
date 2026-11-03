package vera.jvm.bytecode.emitter

import vera.jvm.ir.ComparisonOperator
import vera.jvm.ir.IntBinaryOperator
import vera.jvm.ir.JvmMethodSignature
import vera.jvm.ir.JvmType
import vera.jvm.instruction.BindLabel
import vera.jvm.instruction.CreateLabel
import vera.jvm.instruction.GetStatic
import vera.jvm.instruction.IfFalseJumpTo
import vera.jvm.instruction.InstructionWithDebugInfo
import vera.jvm.instruction.IntBinaryOperation
import vera.jvm.instruction.IntComparisonJumpTo
import vera.jvm.instruction.InvokeVirtual
import vera.jvm.instruction.Invokestatic
import vera.jvm.instruction.JvmInstructionProgram
import vera.jvm.instruction.JvmLabel
import vera.jvm.instruction.JumpTo
import vera.jvm.instruction.LoadConstant
import vera.jvm.instruction.LoadLocal
import vera.jvm.instruction.Return
import vera.jvm.instruction.StoreLocal
import vera.shared.model.Identifier
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassFile.ACC_PUBLIC
import java.lang.classfile.ClassFile.ACC_STATIC
import java.lang.classfile.CodeBuilder
import java.lang.classfile.Label
import java.lang.classfile.TypeKind
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.CD_Object
import java.lang.constant.ConstantDescs.CD_boolean
import java.lang.constant.ConstantDescs.CD_int
import java.lang.constant.ConstantDescs.CD_void
import java.lang.constant.MethodTypeDesc

object JvmBytecodeEmitter {
    fun emit(jvmProgram: JvmInstructionProgram): Map<Identifier, ByteArray> = EmitterState(jvmProgram).emit()
}

/** Mutable state for a single emit run. */
private class EmitterState(private val jvmProgram: JvmInstructionProgram) {

    private val labels = mutableMapOf<JvmLabel, Label>()

    /** @return a map of classnames to their bytecode */
    fun emit(): Map<Identifier, ByteArray> {
        val classFile = ClassFile.of()

        return jvmProgram.classes.associate { clazz ->
            val bytes = classFile.build(ClassDesc.of(clazz.name.value)) { classBuilder ->

                clazz.methods.forEach { method ->
                    classBuilder.withMethod(
                        method.name.value,
                        method.signature.toMethodTypeDesc(),
                        ACC_PUBLIC or ACC_STATIC
                    ) { methodBuilder ->
                        methodBuilder.withCode { codeBuilder ->
                            method.instructions.forEach { instruction ->
                                emitInstruction(codeBuilder, instruction)
                            }
                        }
                    }
                }
            }

            clazz.name to bytes
        }
    }

    private fun emitInstruction(codeBuilder: CodeBuilder, instructionWithDebugInfo: InstructionWithDebugInfo) {
        when (val instruction = instructionWithDebugInfo.instruction) {

            is LoadLocal -> codeBuilder.loadLocal(
                TypeKind.from(instruction.local.jvmType.toClassDesc()),
                instruction.local.slot.index
            )

            is StoreLocal -> codeBuilder.storeLocal(
                TypeKind.from(instruction.local.jvmType.toClassDesc()),
                instruction.local.slot.index
            )

            is LoadConstant -> {
                when (instruction.type) {
                    JvmType.INT -> codeBuilder.loadConstant(instruction.value as Int)
                    JvmType.STRING -> codeBuilder.ldc(codeBuilder.constantPool().stringEntry(instruction.value as String))
                    JvmType.BOOL -> codeBuilder.loadConstant(if (instruction.value as Boolean) 1 else 0)
                    else -> error("Unsupported constant: ${instruction.type}")
                }
            }

            is IntBinaryOperation -> {
                when (instruction.operator) {
                    IntBinaryOperator.ADD -> codeBuilder.iadd()
                    IntBinaryOperator.SUB -> codeBuilder.isub()
                    IntBinaryOperator.MUL -> codeBuilder.imul()
                    IntBinaryOperator.DIV -> codeBuilder.idiv()
                    IntBinaryOperator.REM -> codeBuilder.irem()
                }
            }

            is IntComparisonJumpTo -> {
                val label = labels[instruction.label] ?: error("unbound label in comparison jump")
                when (instruction.operator) {
                    ComparisonOperator.EQ -> codeBuilder.if_icmpeq(label)
                    ComparisonOperator.NEQ -> codeBuilder.if_icmpne(label)
                    ComparisonOperator.LT -> codeBuilder.if_icmplt(label)
                    ComparisonOperator.GT -> codeBuilder.if_icmpgt(label)
                    ComparisonOperator.LTE -> codeBuilder.if_icmple(label)
                    ComparisonOperator.GTE -> codeBuilder.if_icmpge(label)
                }
            }

            is Invokestatic -> {
                codeBuilder.invokestatic(
                    ClassDesc.of(instruction.ownerClass.value),
                    instruction.methodName.value,
                    instruction.methodSignature.toMethodTypeDesc(),
                )
            }

            is GetStatic -> {
                codeBuilder.getstatic(
                    ClassDesc.of(instruction.ownerClass.value),
                    instruction.fieldName.value,
                    ClassDesc.of(instruction.fieldDescriptor.value),
                )
            }

            is InvokeVirtual -> {
                codeBuilder.invokevirtual(
                    ClassDesc.of(instruction.ownerClass.value),
                    instruction.methodName.value,
                    instruction.methodSignature.toMethodTypeDesc(),
                )
            }

            is CreateLabel -> labels[instruction.label] = codeBuilder.newLabel()
            is BindLabel -> codeBuilder.labelBinding(labels[instruction.label] ?: error("unbound label"))
            is JumpTo -> codeBuilder.goto_(labels[instruction.label] ?: error("unbound label"))
            is IfFalseJumpTo -> codeBuilder.ifeq(labels[instruction.label] ?: error("unbound label"))

            is Return -> {
                codeBuilder.return_(TypeKind.from(instruction.type.toClassDesc()))
            }
        }
    }

    private fun JvmMethodSignature.toMethodTypeDesc(): MethodTypeDesc {
        val params = this.parameters.map { it.type.toClassDesc() }.toTypedArray()
        return MethodTypeDesc.of(this.returnType.toClassDesc(), *params)
    }

    private fun JvmType.toClassDesc(): ClassDesc = when (this) {
        JvmType.INT -> CD_int
        JvmType.STRING -> CD_Object
        JvmType.BOOL -> CD_boolean
        JvmType.OBJECT -> CD_Object
        JvmType.VOID -> CD_void
    }
}
