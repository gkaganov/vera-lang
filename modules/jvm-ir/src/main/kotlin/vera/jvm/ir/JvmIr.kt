package vera.jvm.ir

import vera.shared.model.Identifier

data class JvmProgram(val classes: List<JvmClass>)
data class JvmClass(val name: Identifier, val methods: List<JvmMethod>)
data class JvmMethod(val name: Identifier, val instructions: List<InstructionWithDebugInfo>, val signature: JvmMethodSignature)
data class JvmMethodSignature(val parameters: List<JvmParameter>, val returnType: JvmType)
data class JvmParameter(val name: Identifier, val type: JvmType)
enum class JvmType { INT, STRING, BOOL, VOID }

data class DebugFrameSnapshot(val stack: List<JvmType>, val locals: Map<LocalTable.Slot, LocalTable.Local>)

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

sealed interface BinaryOperator
enum class IntBinaryOperator : BinaryOperator { ADD, SUB, MUL, DIV }
enum class ComparisonOperator : BinaryOperator { EQ, NEQ }

data class IntBinaryOperation(val operator: IntBinaryOperator) : JvmInstruction {
    override val effect = InstructionEffect(
        stackPops = listOf(JvmType.INT, JvmType.INT),
        stackPushes = listOf(JvmType.INT),
    )
}

data class ComparisonOperation(val operator: ComparisonOperator, val comparedType: JvmType) : JvmInstruction {
    override val effect = InstructionEffect(
        stackPops = listOf(comparedType, comparedType),
        stackPushes = listOf(JvmType.BOOL),
    )
}

data class Invokestatic(
    val ownerClass: Identifier,
    val methodName: Identifier,
    val methodSignature: JvmMethodSignature
) : JvmInstruction {
    override val effect = InstructionEffect(
        stackPops = methodSignature.parameters.map { it.type },
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
        stackPops = listOf(JvmType.BOOL)
    )
}

data class Print(val type: JvmType) : JvmInstruction {
    override val effect = InstructionEffect(
        stackPops = listOf(type)
    )
}

data class Return(val type: JvmType = JvmType.VOID) : JvmInstruction {
    override val effect = InstructionEffect(
        stackPops = if (type != JvmType.VOID) listOf(type) else emptyList()
    )
}
