package vera.jvm.bytecode.emitter

import vera.jvm.ir.BindLabel
import vera.jvm.ir.ComparisonOperation
import vera.jvm.ir.CreateLabel
import vera.jvm.ir.IfFalseJumpTo
import vera.jvm.ir.InstructionWithDebugInfo
import vera.jvm.ir.IntBinaryOperation
import vera.jvm.ir.IntBinaryOperator
import vera.jvm.ir.Invokestatic
import vera.jvm.ir.JumpTo
import vera.jvm.ir.JvmLabel
import vera.jvm.ir.JvmMethodSignature
import vera.jvm.ir.JvmProgram
import vera.jvm.ir.JvmType
import vera.jvm.ir.LoadConstant
import vera.jvm.ir.LoadLocal
import vera.jvm.ir.Print
import vera.jvm.ir.Return
import vera.jvm.ir.StoreLocal
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

class JvmBytecodeEmitter {

    private val labels = mutableMapOf<JvmLabel, Label>()

    /** @return a map of classnames to their bytecode */
    fun emit(jvmProgram: JvmProgram): Map<Identifier, ByteArray> {
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
                }
            }

            is Invokestatic -> {
                codeBuilder.invokestatic(
                    ClassDesc.of(instruction.ownerClass.value),
                    instruction.methodName.value,
                    instruction.methodSignature.toMethodTypeDesc(),
                )
            }

            is CreateLabel -> labels[instruction.label] = codeBuilder.newLabel()
            is BindLabel -> codeBuilder.labelBinding(labels[instruction.label])
            is JumpTo -> codeBuilder.goto_(labels[instruction.label])
            is IfFalseJumpTo -> codeBuilder.ifeq(labels[instruction.label])

            is ComparisonOperation -> TODO()

            is Print -> {
                codeBuilder.getstatic(
                    ClassDesc.of("java.lang.System"),
                    "out",
                    ClassDesc.of("java.io.PrintStream"),
                )
                codeBuilder.swap() // invokevirtual expects: receiver, argument — but it is pushed first
                codeBuilder.invokevirtual(
                    ClassDesc.of("java.io.PrintStream"),
                    "println",
                    MethodTypeDesc.of(
                        CD_void,
                        instruction.type.toClassDesc()
                    ),
                )
            }

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
        JvmType.VOID -> CD_void
    }
}
