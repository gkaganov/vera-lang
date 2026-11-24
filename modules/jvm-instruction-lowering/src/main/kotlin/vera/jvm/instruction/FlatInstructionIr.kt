package vera.jvm.instruction

import vera.jvm.ir.JvmComparisonOperator
import vera.jvm.ir.JvmIntBinaryOperator
import vera.jvm.ir.JvmIntUnaryOperator
import vera.jvm.ir.JvmMethodSignature
import vera.jvm.ir.JvmType
import vera.shared.model.Identifier

data class FlatProgram(val classes: List<FlatClass>)

/** A class, or an interface when [isInterface]; see [vera.jvm.ir.JvmClass]. */
data class FlatClass(
    val name: Identifier,
    val methods: List<FlatMethod>,
    val fields: List<FlatField> = emptyList(),
    val interfaces: List<Identifier> = emptyList(),
    val isInterface: Boolean = false,
    val abstractMethods: List<FlatAbstractMethod> = emptyList(),
    val permittedSubclasses: List<Identifier> = emptyList(),
)

data class FlatAbstractMethod(val name: Identifier, val signature: JvmMethodSignature)

data class FlatField(val name: Identifier, val type: JvmType, val isStatic: Boolean = false)

data class FlatMethod(
    val name: Identifier,
    val instructions: List<FlatInstructionWithDebugInfo>,
    val signature: JvmMethodSignature,
    val isStatic: Boolean = true,
    val isPrivate: Boolean = false,
)

data class FlatFrameSnapshot(
    val stack: List<JvmType>,
    val locals: Map<FlatLocalTable.Slot, FlatLocalTable.Local>,
)

sealed interface FlatInstruction {
    val effect: FlatInstructionEffect
}

data class FlatInstructionEffect(
    val stackPops: List<JvmType> = emptyList(),
    val stackPushes: List<JvmType> = emptyList(),
    val localReads: List<FlatLocalTable.Local> = emptyList(),
    val localWrites: List<FlatLocalTable.Local> = emptyList(),
)

data class FlatInstructionWithDebugInfo(
    val instruction: FlatInstruction,
    val before: FlatFrameSnapshot,
    val after: FlatFrameSnapshot,
)

data class FlatLoadLocal(val local: FlatLocalTable.Local) : FlatInstruction {
    override val effect = FlatInstructionEffect(
        stackPushes = listOf(local.jvmType),
        localReads = listOf(local),
    )
}

data class FlatStoreLocal(val local: FlatLocalTable.Local) : FlatInstruction {
    override val effect = FlatInstructionEffect(
        stackPops = listOf(local.jvmType),
        localWrites = listOf(local),
    )
}

data class FlatLoadConstant(val type: JvmType, val value: Any) : FlatInstruction {
    override val effect = FlatInstructionEffect(
        stackPushes = listOf(type)
    )
}

data class FlatIntUnaryOperation(val operator: JvmIntUnaryOperator) : FlatInstruction {
    override val effect = FlatInstructionEffect(
        stackPops = listOf(JvmType.INT),
        stackPushes = listOf(JvmType.INT),
    )
}

/** Logical negation of the `Bool` on top of the stack. */
data object FlatBoolNot : FlatInstruction {
    override val effect = FlatInstructionEffect(
        stackPops = listOf(JvmType.BOOL),
        stackPushes = listOf(JvmType.BOOL),
    )
}

data class FlatIntBinaryOperation(val operator: JvmIntBinaryOperator) : FlatInstruction {
    override val effect = FlatInstructionEffect(
        stackPops = listOf(JvmType.INT, JvmType.INT),
        stackPushes = listOf(JvmType.INT),
    )
}

data class FlatIntComparisonJump(
    val operator: JvmComparisonOperator,
    val label: FlatLabel,
) : FlatInstruction {
    override val effect = FlatInstructionEffect(
        stackPops = listOf(JvmType.INT, JvmType.INT),
    )
}

data class FlatBoolEqualityJump(
    val negated: Boolean,
    val label: FlatLabel,
) : FlatInstruction {
    override val effect = FlatInstructionEffect(
        stackPops = listOf(JvmType.BOOL, JvmType.BOOL),
    )
}

data class FlatStringEqualityJump(
    val negated: Boolean,
    val label: FlatLabel,
) : FlatInstruction {
    override val effect = FlatInstructionEffect(
        stackPops = listOf(JvmType.STRING, JvmType.STRING),
    )
}

data class FlatInvokestatic(
    val ownerClass: Identifier,
    val methodName: Identifier,
    val methodSignature: JvmMethodSignature,
) : FlatInstruction {
    override val effect = FlatInstructionEffect(
        stackPops = methodSignature.parameters.map { it.type },
        stackPushes = if (methodSignature.returnType == JvmType.VOID) emptyList() else listOf(methodSignature.returnType),
    )
}

data class FlatGetStatic(
    val ownerClass: Identifier,
    val fieldName: Identifier,
    val fieldType: JvmType,
    val fieldDescriptor: Identifier,
) : FlatInstruction {
    override val effect = FlatInstructionEffect(
        stackPushes = listOf(fieldType),
    )
}

data class FlatPutStatic(
    val ownerClass: Identifier,
    val fieldName: Identifier,
    val fieldType: JvmType,
    val fieldDescriptor: Identifier,
) : FlatInstruction {
    override val effect = FlatInstructionEffect(
        stackPops = listOf(fieldType),
    )
}

data class FlatInvokeVirtual(
    val ownerClass: Identifier,
    val methodName: Identifier,
    val methodSignature: JvmMethodSignature,
    val receiverType: JvmType = JvmType.OBJECT,
) : FlatInstruction {
    override val effect = FlatInstructionEffect(
        stackPops = listOf(receiverType) + methodSignature.parameters.map { it.type },
        stackPushes = if (methodSignature.returnType == JvmType.VOID) emptyList() else listOf(methodSignature.returnType),
    )
}

/** Replaces the object on top of the stack with whether it is an instance of [className]. */
data class FlatInstanceOf(val operandType: JvmType, val className: Identifier) : FlatInstruction {
    override val effect = FlatInstructionEffect(stackPops = listOf(operandType), stackPushes = listOf(JvmType.BOOL))
}

/** Casts the object on top of the stack to [to]; the JVM checks the cast at run time. */
data class FlatCheckCast(val from: JvmType, val to: JvmType.ClassType) : FlatInstruction {
    override val effect = FlatInstructionEffect(stackPops = listOf(from), stackPushes = listOf(to))
}

/** Calls a trait method through its interface (`invokeinterface`). */
data class FlatInvokeInterface(
    val ownerClass: Identifier,
    val methodName: Identifier,
    val methodSignature: JvmMethodSignature,
    val receiverType: JvmType,
) : FlatInstruction {
    override val effect = FlatInstructionEffect(
        stackPops = listOf(receiverType) + methodSignature.parameters.map { it.type },
        stackPushes = if (methodSignature.returnType == JvmType.VOID) emptyList() else listOf(methodSignature.returnType),
    )
}

/**
 * Retypes the value on top of the stack from a class to one of its interfaces. It emits no bytecode;
 * the JVM accepts the class value wherever the interface is expected.
 */
data class FlatUpcast(val from: JvmType, val to: JvmType) : FlatInstruction {
    override val effect = FlatInstructionEffect(stackPops = listOf(from), stackPushes = listOf(to))
}

data class FlatInvokeSpecial(
    val receiverType: JvmType,
    val ownerClass: Identifier,
    val methodName: Identifier,
    val methodSignature: JvmMethodSignature,
) : FlatInstruction {
    override val effect = FlatInstructionEffect(
        stackPops = listOf(receiverType) + methodSignature.parameters.map { it.type },
        stackPushes = if (methodSignature.returnType == JvmType.VOID) emptyList() else listOf(methodSignature.returnType),
    )
}

data class FlatNew(val type: JvmType.ClassType) : FlatInstruction {
    override val effect = FlatInstructionEffect(
        stackPushes = listOf(type),
    )
}

data class FlatDup(val type: JvmType) : FlatInstruction {
    override val effect = FlatInstructionEffect(
        stackPops = listOf(type),
        stackPushes = listOf(type, type),
    )
}

data class FlatGetField(
    val ownerClass: Identifier,
    val fieldName: Identifier,
    val fieldType: JvmType,
) : FlatInstruction {
    override val effect = FlatInstructionEffect(
        stackPops = listOf(JvmType.ClassType(ownerClass)),
        stackPushes = listOf(fieldType),
    )
}

data class FlatPutField(
    val ownerClass: Identifier,
    val fieldName: Identifier,
    val fieldType: JvmType,
) : FlatInstruction {
    override val effect = FlatInstructionEffect(
        stackPops = listOf(JvmType.ClassType(ownerClass), fieldType),
    )
}

class FlatLabel

data class FlatCreateLabel(val label: FlatLabel) : FlatInstruction {
    override val effect = FlatInstructionEffect()
}

data class FlatBindLabel(val label: FlatLabel) : FlatInstruction {
    override val effect = FlatInstructionEffect()
}

data class FlatJump(val label: FlatLabel) : FlatInstruction {
    override val effect = FlatInstructionEffect()
}

data class FlatIfFalseJump(val label: FlatLabel) : FlatInstruction {
    override val effect = FlatInstructionEffect(
        stackPops = listOf(JvmType.BOOL),
    )
}

data class FlatReturn(val type: JvmType = JvmType.VOID) : FlatInstruction {
    override val effect = FlatInstructionEffect(
        stackPops = if (type != JvmType.VOID) listOf(type) else emptyList(),
    )
}

data class FlatPop(val type: JvmType) : FlatInstruction {
    override val effect = FlatInstructionEffect(
        stackPops = listOf(type),
    )
}
