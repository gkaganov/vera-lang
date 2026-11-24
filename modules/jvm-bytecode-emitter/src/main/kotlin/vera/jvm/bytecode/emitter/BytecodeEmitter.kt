package vera.jvm.bytecode.emitter

import vera.jvm.ir.JvmComparisonOperator
import vera.jvm.ir.JvmIntBinaryOperator
import vera.jvm.ir.JvmIntUnaryOperator
import vera.jvm.ir.JvmMethodSignature
import vera.jvm.ir.JvmType
import vera.jvm.instruction.FlatBindLabel
import vera.jvm.instruction.FlatBoolEqualityJump
import vera.jvm.instruction.FlatCreateLabel
import vera.jvm.instruction.FlatDup
import vera.jvm.instruction.FlatGetField
import vera.jvm.instruction.FlatGetStatic
import vera.jvm.instruction.FlatIfFalseJump
import vera.jvm.instruction.FlatInstructionWithDebugInfo
import vera.jvm.instruction.FlatIntBinaryOperation
import vera.jvm.instruction.FlatIntComparisonJump
import vera.jvm.instruction.FlatIntUnaryOperation
import vera.jvm.instruction.FlatInvokeInterface
import vera.jvm.instruction.FlatUpcast
import vera.jvm.instruction.FlatCheckCast
import vera.jvm.instruction.FlatInstanceOf
import vera.jvm.instruction.FlatBoolNot
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
import vera.jvm.instruction.FlatStringEqualityJump
import vera.shared.model.Identifier
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassFile.ACC_ABSTRACT
import java.lang.classfile.ClassFile.ACC_FINAL
import java.lang.classfile.ClassFile.ACC_INTERFACE
import java.lang.classfile.ClassFile.ACC_PUBLIC
import java.lang.classfile.ClassFile.ACC_STATIC
import java.lang.classfile.ClassHierarchyResolver
import java.lang.classfile.CodeBuilder
import java.lang.classfile.Label
import java.lang.classfile.TypeKind
import java.lang.classfile.attribute.PermittedSubclassesAttribute
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
        val classFile = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(programClassHierarchy()))
        val bytecode = mutableMapOf<Identifier, ByteArray>()

        flatProgram.classes.forEach { clazz ->
            val bytes = classFile.build(ClassDesc.of(clazz.name.value)) { classBuilder ->
                if (clazz.isInterface) {
                    classBuilder.withFlags(ACC_PUBLIC or ACC_INTERFACE or ACC_ABSTRACT)
                } else {
                    classBuilder.withFlags(ACC_PUBLIC or ACC_FINAL)
                }
                classBuilder.withSuperclass(CD_Object)
                if (clazz.interfaces.isNotEmpty()) {
                    classBuilder.withInterfaceSymbols(clazz.interfaces.map { name -> ClassDesc.of(name.value) })
                }
                if (clazz.permittedSubclasses.isNotEmpty()) {
                    classBuilder.with(
                        PermittedSubclassesAttribute.ofSymbols(clazz.permittedSubclasses.map { name -> ClassDesc.of(name.value) }),
                    )
                }
                clazz.abstractMethods.forEach { abstractMethod ->
                    classBuilder.withMethod(
                        abstractMethod.name.value,
                        abstractMethod.signature.toMethodTypeDesc(),
                        ACC_PUBLIC or ACC_ABSTRACT,
                    ) { _ -> }
                }
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

    /**
     * Stack maps merge types at branch joins, such as two classes that meet as their shared interface,
     * so the ClassFile API needs the program's own classes and interfaces, not only the JDK's.
     */
    private fun programClassHierarchy(): ClassHierarchyResolver {
        val interfaces = flatProgram.classes.filter { clazz -> clazz.isInterface }.map { clazz -> ClassDesc.of(clazz.name.value) }
        val superclasses = flatProgram.classes.filterNot { clazz -> clazz.isInterface }
            .associate { clazz -> ClassDesc.of(clazz.name.value) to CD_Object }
        return ClassHierarchyResolver.of(interfaces, superclasses).orElse(ClassHierarchyResolver.defaultResolver())
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

            is FlatBoolNot -> {
                codeBuilder.iconst_1()
                codeBuilder.ixor()
            }

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

            is FlatBoolEqualityJump -> {
                val label = labels[instruction.label]
                    ?: throw BytecodeEmitException.UnboundLabel(instruction.label)
                if (instruction.negated) {
                    codeBuilder.if_icmpne(label)
                } else {
                    codeBuilder.if_icmpeq(label)
                }
            }

            is FlatStringEqualityJump -> {
                val label = labels[instruction.label]
                    ?: throw BytecodeEmitException.UnboundLabel(instruction.label)
                codeBuilder.invokevirtual(
                    CD_String,
                    "equals",
                    MethodTypeDesc.of(CD_boolean, CD_Object),
                )
                if (instruction.negated) {
                    codeBuilder.ifeq(label)
                } else {
                    codeBuilder.ifne(label)
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

            is FlatInvokeInterface -> {
                codeBuilder.invokeinterface(
                    ClassDesc.of(instruction.ownerClass.value),
                    instruction.methodName.value,
                    instruction.methodSignature.toMethodTypeDesc(),
                )
            }

            is FlatInstanceOf -> codeBuilder.instanceOf(ClassDesc.of(instruction.className.value))

            is FlatCheckCast -> codeBuilder.checkcast(ClassDesc.of(instruction.to.name.value))

            is FlatUpcast -> {} // An interface accepts its implementing class; nothing to emit.

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
        is JvmType.InterfaceType -> ClassDesc.of(name.value)
        is JvmType.Array -> elementType.toClassDesc().arrayType()
    }
}
