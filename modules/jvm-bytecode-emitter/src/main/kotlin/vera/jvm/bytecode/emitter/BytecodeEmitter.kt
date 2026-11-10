package vera.jvm.bytecode.emitter

import vera.jvm.ir.JvmComparisonOperator
import vera.jvm.ir.JvmIntBinaryOperator
import vera.jvm.ir.JvmIntUnaryOperator
import vera.jvm.ir.JvmMethodSignature
import vera.jvm.ir.JvmType
import vera.jvm.instruction.FlatBindLabel
import vera.jvm.instruction.FlatCreateLabel
import vera.jvm.instruction.FlatDup
import vera.jvm.instruction.FlatGetField
import vera.jvm.instruction.FlatGetStatic
import vera.jvm.instruction.FlatIfFalseJump
import vera.jvm.instruction.FlatInstructionWithDebugInfo
import vera.jvm.instruction.FlatIntBinaryOperation
import vera.jvm.instruction.FlatIntComparisonJump
import vera.jvm.instruction.FlatIntUnaryOperation
import vera.jvm.instruction.FlatInvokeSpecial
import vera.jvm.instruction.FlatInvokeVirtual
import vera.jvm.instruction.FlatInvokestatic
import vera.jvm.instruction.FlatProgram
import vera.jvm.instruction.FlatLabel
import vera.jvm.instruction.FlatJump
import vera.jvm.instruction.FlatLoadConstant
import vera.jvm.instruction.FlatLoadLocal
import vera.jvm.instruction.FlatNew
import vera.jvm.instruction.FlatPop
import vera.jvm.instruction.FlatPutField
import vera.jvm.instruction.FlatPutStatic
import vera.jvm.instruction.FlatReturn
import vera.jvm.instruction.FlatStoreLocal
import vera.shared.model.Identifier
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassFile.ACC_FINAL
import java.lang.classfile.ClassFile.ACC_PUBLIC
import java.lang.classfile.ClassFile.ACC_STATIC
import java.lang.classfile.CodeBuilder
import java.lang.classfile.Label
import java.lang.classfile.TypeKind
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.CD_Object
import java.lang.constant.ConstantDescs.CD_boolean
import java.lang.constant.ConstantDescs.CD_String
import java.lang.constant.ConstantDescs.CD_int
import java.lang.constant.ConstantDescs.CD_void
import java.lang.constant.MethodTypeDesc

object BytecodeEmitter {
    fun emit(flatProgram: FlatProgram): Map<Identifier, ByteArray> =
        BytecodeEmitterState(flatProgram).emit()
}

/** Mutable state for a single emit run. */
private class BytecodeEmitterState(private val flatProgram: FlatProgram) {

    private val labels = mutableMapOf<FlatLabel, Label>()

    /** @return a map of classnames to their bytecode */
    fun emit(): Map<Identifier, ByteArray> {
        val classFile = ClassFile.of()
        val bytecode = mutableMapOf<Identifier, ByteArray>()

        flatProgram.classes.forEach { clazz ->
            val bytes = classFile.build(ClassDesc.of(clazz.name.value)) { classBuilder ->
                classBuilder.withFlags(ACC_PUBLIC or ACC_FINAL)
                classBuilder.withSuperclass(CD_Object)
                clazz.fields.forEach { flatField ->
                    val fieldFlags = (ACC_PUBLIC or ACC_FINAL) or
                        (if (flatField.isStatic) ACC_STATIC else 0)
                    classBuilder.withField(
                        flatField.name.value,
                        flatField.type.toClassDesc(),
                        fieldFlags,
                    )
                }
                clazz.methods.forEach { method ->
                    val methodFlags = ACC_PUBLIC or if (method.isStatic) ACC_STATIC else 0
                    classBuilder.withMethod(
                        method.name.value,
                        method.signature.toMethodTypeDesc(),
                        methodFlags,
                    ) { methodBuilder ->
                        methodBuilder.withCode { codeBuilder ->
                            method.instructions.forEach { instruction ->
                                emitInstruction(codeBuilder, instruction)
                            }
                        }
                    }
                }
            }

            bytecode[clazz.name] = bytes
        }

        return bytecode
    }

    private fun emitInstruction(codeBuilder: CodeBuilder, instructionWithDebugInfo: FlatInstructionWithDebugInfo) {
        when (val instruction = instructionWithDebugInfo.instruction) {

            is FlatLoadLocal -> codeBuilder.loadLocal(
                TypeKind.from(instruction.local.jvmType.toClassDesc()),
                instruction.local.slot.index,
            )

            is FlatStoreLocal -> codeBuilder.storeLocal(
                TypeKind.from(instruction.local.jvmType.toClassDesc()),
                instruction.local.slot.index,
            )

            is FlatLoadConstant -> emitLoadConstant(codeBuilder, instruction)

            is FlatIntUnaryOperation -> {
                when (instruction.operator) {
                    JvmIntUnaryOperator.NEGATE -> codeBuilder.ineg()
                }
            }

            is FlatIntBinaryOperation -> {
                when (instruction.operator) {
                    JvmIntBinaryOperator.ADD -> codeBuilder.iadd()
                    JvmIntBinaryOperator.SUB -> codeBuilder.isub()
                    JvmIntBinaryOperator.MUL -> codeBuilder.imul()
                    JvmIntBinaryOperator.DIV -> codeBuilder.idiv()
                    JvmIntBinaryOperator.REM -> codeBuilder.irem()
                }
            }

            is FlatIntComparisonJump -> {
                val label = labels[instruction.label]
                    ?: throw BytecodeEmitException.UnboundLabel(instruction.label)
                when (instruction.operator) {
                    JvmComparisonOperator.EQ -> codeBuilder.if_icmpeq(label)
                    JvmComparisonOperator.NEQ -> codeBuilder.if_icmpne(label)
                    JvmComparisonOperator.LT -> codeBuilder.if_icmplt(label)
                    JvmComparisonOperator.GT -> codeBuilder.if_icmpgt(label)
                    JvmComparisonOperator.LTE -> codeBuilder.if_icmple(label)
                    JvmComparisonOperator.GTE -> codeBuilder.if_icmpge(label)
                }
            }

            is FlatInvokestatic -> {
                codeBuilder.invokestatic(
                    ClassDesc.of(instruction.ownerClass.value),
                    instruction.methodName.value,
                    instruction.methodSignature.toMethodTypeDesc(),
                )
            }

            is FlatGetStatic -> {
                codeBuilder.getstatic(
                    ClassDesc.of(instruction.ownerClass.value),
                    instruction.fieldName.value,
                    ClassDesc.of(instruction.fieldDescriptor.value),
                )
            }

            is FlatInvokeVirtual -> {
                codeBuilder.invokevirtual(
                    ClassDesc.of(instruction.ownerClass.value),
                    instruction.methodName.value,
                    instruction.methodSignature.toMethodTypeDesc(),
                )
            }

            is FlatInvokeSpecial -> {
                codeBuilder.invokespecial(
                    ClassDesc.of(instruction.ownerClass.value),
                    instruction.methodName.value,
                    instruction.methodSignature.toMethodTypeDesc(),
                )
            }

            is FlatNew -> codeBuilder.new_(ClassDesc.of(instruction.type.name.value))

            is FlatDup -> codeBuilder.dup()

            is FlatGetField -> {
                codeBuilder.getfield(
                    ClassDesc.of(instruction.ownerClass.value),
                    instruction.fieldName.value,
                    instruction.fieldType.toClassDesc(),
                )
            }

            is FlatPutField -> {
                codeBuilder.putfield(
                    ClassDesc.of(instruction.ownerClass.value),
                    instruction.fieldName.value,
                    instruction.fieldType.toClassDesc(),
                )
            }

            is FlatPutStatic -> {
                codeBuilder.putstatic(
                    ClassDesc.of(instruction.ownerClass.value),
                    instruction.fieldName.value,
                    ClassDesc.of(instruction.fieldDescriptor.value),
                )
            }

            is FlatCreateLabel -> labels[instruction.label] = codeBuilder.newLabel()
            is FlatBindLabel -> {
                val label = labels[instruction.label]
                    ?: throw BytecodeEmitException.UnboundLabel(instruction.label)
                codeBuilder.labelBinding(label)
            }
            is FlatJump -> {
                val label = labels[instruction.label]
                    ?: throw BytecodeEmitException.UnboundLabel(instruction.label)
                codeBuilder.goto_(label)
            }
            is FlatIfFalseJump -> {
                val label = labels[instruction.label]
                    ?: throw BytecodeEmitException.UnboundLabel(instruction.label)
                codeBuilder.ifeq(label)
            }
            is FlatPop -> codeBuilder.pop()
            is FlatReturn -> codeBuilder.return_(TypeKind.from(instruction.type.toClassDesc()))
        }
    }

    private fun emitLoadConstant(codeBuilder: CodeBuilder, instruction: FlatLoadConstant) {
        when (instruction.type) {
            JvmType.INT -> {
                val value = instruction.value
                if (value !is Int) {
                    throw BytecodeEmitException.InvalidConstant(instruction.type, value)
                }
                codeBuilder.loadConstant(value)
            }

            JvmType.STRING -> {
                val value = instruction.value
                if (value !is String) {
                    throw BytecodeEmitException.InvalidConstant(instruction.type, value)
                }
                codeBuilder.ldc(codeBuilder.constantPool().stringEntry(value))
            }

            JvmType.BOOL -> {
                val value = instruction.value
                if (value !is Boolean) {
                    throw BytecodeEmitException.InvalidConstant(instruction.type, value)
                }
                codeBuilder.loadConstant(if (value) 1 else 0)
            }

            else -> throw BytecodeEmitException.UnsupportedConstant(instruction.type)
        }
    }

    private fun JvmMethodSignature.toMethodTypeDesc(): MethodTypeDesc {
        val params = this.parameters.map { it.type.toClassDesc() }.toTypedArray()
        return MethodTypeDesc.of(this.returnType.toClassDesc(), *params)
    }

    private fun JvmType.toClassDesc(): ClassDesc = when (this) {
        JvmType.INT -> CD_int
        JvmType.STRING -> CD_String
        JvmType.BOOL -> CD_boolean
        JvmType.OBJECT -> CD_Object
        JvmType.VOID -> CD_void
        is JvmType.ClassType -> ClassDesc.of(name.value)
        is JvmType.Array -> elementType.toClassDesc().arrayType()
    }
}
