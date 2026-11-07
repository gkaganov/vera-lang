package vera.jvm.instruction

import vera.jvm.ir.JvmMethodSignature
import vera.jvm.ir.JvmParameter
import vera.jvm.ir.JvmType
import vera.shared.model.Identifier

class FlatOperandStack {
    private val types = mutableListOf<JvmType>()

    fun push(value: JvmType) {
        types += value
    }

    fun pop(): JvmType {
        return types.removeLast()
    }

    fun peek(amount: Int): List<JvmType> {
        return types.takeLast(amount)
    }

    fun size(): Int {
        return types.size
    }

    fun snapshot(): List<JvmType> {
        return types.toList()
    }
}

class FlatLocalTable {
    @JvmInline
    value class Slot(val index: Int)

    data class Local(val slot: Slot, val jvmType: JvmType)

    private val locals = mutableMapOf<Slot, Local>()
    private var _nextFreeSlot = Slot(0)
    val nextFreeSlot: Slot
        get() = _nextFreeSlot

    fun define(jvmType: JvmType): Slot {
        val slotUsed = _nextFreeSlot
        locals[slotUsed] = Local(slotUsed, jvmType)
        _nextFreeSlot = Slot(_nextFreeSlot.index + 1)
        return slotUsed
    }

    fun read(slot: Slot): Local? = locals[slot]
    fun snapshot(): Map<Slot, Local> = locals.toMap()
}

class FlatMethodBuilder(
    val name: Identifier,
    val parameters: List<JvmParameter>,
    val returnType: JvmType,
) {
    private val locals = FlatLocalTable()
    private val stack = FlatOperandStack()

    val instructions: List<FlatInstructionWithDebugInfo>
        field = mutableListOf<FlatInstructionWithDebugInfo>()

    val nextFreeLocalSlot: FlatLocalTable.Slot
        get() = locals.nextFreeSlot

    fun allocateLocal(jvmType: JvmType): FlatLocalTable.Local {
        val slot = locals.define(jvmType)
        return FlatLocalTable.Local(slot, jvmType)
    }

    fun parameterLocal(index: Int): FlatLocalTable.Local {
        val parameter = parameters.getOrNull(index)
            ?: throw FlatInstructionException.InternalInvariant("parameter index $index is out of bounds")
        return FlatLocalTable.Local(FlatLocalTable.Slot(index), parameter.type)
    }

    init {
        parameters.forEach { parameter ->
            locals.define(parameter.type)
        }
    }

    fun addInstruction(instruction: FlatInstruction) {
        verifyInstruction(instruction)

        val before = FlatFrameSnapshot(
            stack = stack.snapshot(),
            locals = locals.snapshot(),
        )

        applyInstruction(instruction)

        val after = FlatFrameSnapshot(
            stack = stack.snapshot(),
            locals = locals.snapshot(),
        )

        instructions += FlatInstructionWithDebugInfo(
            instruction = instruction,
            before = before,
            after = after,
        )
    }

    fun build(): FlatMethod = FlatMethod(
        name,
        instructions,
        JvmMethodSignature(parameters, returnType),
    )

    private fun verifyInstruction(instruction: FlatInstruction) {
        val pops = instruction.effect.stackPops

        if (stack.size() < pops.size) {
            throw FlatInstructionException.StackUnderflow(
                instruction::class.simpleName ?: "instruction",
                pops.size,
                stack.size(),
            )
        }

        val actual = stack.peek(pops.size)

        actual.zip(pops).forEach { (actualType, expectedType) ->
            if (actualType != expectedType) {
                throw FlatInstructionException.StackTypeMismatch(
                    instruction::class.simpleName ?: "instruction",
                    expectedType,
                    actualType,
                )
            }
        }

        instruction.effect.localReads.forEach { local ->
            if (locals.read(local.slot) == null) {
                throw FlatInstructionException.InternalInvariant("read from undefined local: $local")
            }
        }
    }

    private fun applyInstruction(instruction: FlatInstruction) {
        repeat(instruction.effect.stackPops.size) {
            stack.pop()
        }

        instruction.effect.stackPushes.forEach {
            stack.push(it)
        }
    }
}
