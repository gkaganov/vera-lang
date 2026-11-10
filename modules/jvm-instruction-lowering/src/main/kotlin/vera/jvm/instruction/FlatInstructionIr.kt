package vera.jvm.instruction

import vera.jvm.ir.JvmComparisonOperator
import vera.jvm.ir.JvmIntBinaryOperator
import vera.jvm.ir.JvmIntUnaryOperator
import vera.jvm.ir.JvmMethodSignature
import vera.jvm.ir.JvmType
import vera.shared.model.Identifier

data class FlatProgram(val classes: List<FlatClass>)

data class FlatClass(
    val name: Identifier,
    val methods: List<FlatMethod>,
    val fields: List<FlatField> = emptyList(),
)

data class FlatField(val name: Identifier, val type: JvmType, val isStatic: Boolean = false)

data class FlatMethod(
    val name: Identifier,
    val instructions: List<FlatInstructionWithDebugInfo>,
    val signature: JvmMethodSignature,
    val isStatic: Boolean = true,
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
