package vera.jvm.instruction

import vera.jvm.ir.JvmMethodSignature
import vera.jvm.ir.JvmType
import vera.shared.model.Identifier

data class JvmInstructionProgram(val classes: List<JvmInstructionClass>)

data class JvmInstructionClass(val name: Identifier, val methods: List<JvmInstructionMethod>)

data class JvmInstructionMethod(
    val name: Identifier,
    val instructions: List<InstructionWithDebugInfo>,
    val signature: JvmMethodSignature,
)

data class DebugFrameSnapshot(
    val stack: List<JvmType>,
    val locals: Map<LocalTable.Slot, LocalTable.Local>,
)

sealed interface JvmInstruction {
    val effect: InstructionEffect
}

data class InstructionEffect(
    val stackPops: List<JvmType> = emptyList(),
    val stackPushes: List<JvmType> = emptyList(),
    val localReads: List<LocalTable.Local> = emptyList(),
    val localWrites: List<LocalTable.Local> = emptyList(),
)

data class InstructionWithDebugInfo(
    val instruction: JvmInstruction,
    val before: DebugFrameSnapshot,
    val after: DebugFrameSnapshot,
)

data class LoadLocal(val local: LocalTable.Local) : JvmInstruction {
    override val effect = InstructionEffect(
        stackPushes = listOf(local.jvmType),
        localReads = listOf(local),
    )
}

data class StoreLocal(val local: LocalTable.Local) : JvmInstruction {
    override val effect = InstructionEffect(
        stackPops = listOf(local.jvmType),
        localWrites = listOf(local),
    )
}

data class LoadConstant(val type: JvmType, val value: Any) : JvmInstruction {
    override val effect = InstructionEffect(
        stackPushes = listOf(type)
    )
}

data class IntBinaryOperation(val operator: vera.jvm.ir.IntBinaryOperator) : JvmInstruction {
    override val effect = InstructionEffect(
        stackPops = listOf(JvmType.INT, JvmType.INT),
        stackPushes = listOf(JvmType.INT),
    )
}

data class IntComparisonJumpTo(
    val operator: vera.jvm.ir.ComparisonOperator,
    val label: JvmLabel,
) : JvmInstruction {
    override val effect = InstructionEffect(
        stackPops = listOf(JvmType.INT, JvmType.INT),
    )
}

data class Invokestatic(
    val ownerClass: Identifier,
    val methodName: Identifier,
    val methodSignature: JvmMethodSignature,
) : JvmInstruction {
    override val effect = InstructionEffect(
        stackPops = methodSignature.parameters.map { it.type },
        stackPushes = if (methodSignature.returnType == JvmType.VOID) emptyList() else listOf(methodSignature.returnType),
    )
}

data class GetStatic(
    val ownerClass: Identifier,
    val fieldName: Identifier,
    val fieldType: JvmType,
    val fieldDescriptor: Identifier,
) : JvmInstruction {
    override val effect = InstructionEffect(
        stackPushes = listOf(fieldType),
    )
}

data class InvokeVirtual(
    val ownerClass: Identifier,
    val methodName: Identifier,
    val methodSignature: JvmMethodSignature,
) : JvmInstruction {
    override val effect = InstructionEffect(
        stackPops = listOf(JvmType.OBJECT) + methodSignature.parameters.map { it.type },
        stackPushes = if (methodSignature.returnType == JvmType.VOID) emptyList() else listOf(methodSignature.returnType),
    )
}

class JvmLabel

data class CreateLabel(val label: JvmLabel) : JvmInstruction {
    override val effect = InstructionEffect()
}

data class BindLabel(val label: JvmLabel) : JvmInstruction {
    override val effect = InstructionEffect()
}

data class JumpTo(val label: JvmLabel) : JvmInstruction {
    override val effect = InstructionEffect()
}

data class IfFalseJumpTo(val label: JvmLabel) : JvmInstruction {
    override val effect = InstructionEffect(
        stackPops = listOf(JvmType.BOOL),
    )
}

data class Return(val type: JvmType = JvmType.VOID) : JvmInstruction {
    override val effect = InstructionEffect(
        stackPops = if (type != JvmType.VOID) listOf(type) else emptyList(),
    )
}
