package vera.jvm.ir

import vera.shared.model.Identifier

class OperandStack {
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

class LocalTable {
    @JvmInline value class Slot(val index: Int)
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

class JvmMethodBuilder(val name: Identifier, val parameters: List<JvmParameter>, val returnType: JvmType) {
    private val locals = LocalTable()
    private val stack = OperandStack()

    private val _instructions = mutableListOf<InstructionWithDebugInfo>()
    val instructions: List<InstructionWithDebugInfo>
        get() = _instructions

    val nextFreeLocalSlot: LocalTable.Slot
        get() = locals.nextFreeSlot

    init {
        parameters.forEach { param ->
            locals.define(param.type)
        }
    }

    fun addInstruction(instruction: JvmInstruction) {
        val before = DebugFrameSnapshot(
            stack = stack.snapshot(),
            locals = locals.snapshot(),
        )

        verifyInstruction(instruction)
        applyInstruction(instruction)

        val after = DebugFrameSnapshot(
            stack = stack.snapshot(),
            locals = locals.snapshot(),
        )

        _instructions += InstructionWithDebugInfo(
            instruction = instruction,
            before = before,
            after = after,
        )
    }

    fun build(): JvmMethod = JvmMethod(name, instructions, JvmMethodSignature(parameters, returnType))

    fun getLocal(slot: LocalTable.Slot): LocalTable.Local? = locals.read(slot)

    private fun verifyInstruction(instruction: JvmInstruction) {
        val pops = instruction.effect.stackPops

        if (stack.size() < pops.size) {
            error(
                "Stack underflow. " +
                        "Instruction=${instruction::class.simpleName}, " +
                        "Expected=${pops.size}, Actual=${stack.size()}"
            )
        }

        val actual = stack.peek(pops.size)

        actual.zip(pops).forEach { (actualType, expectedType) ->
            if (actualType != expectedType) {
                error(
                    "Invalid stack type. " +
                            "Instruction=${instruction::class.simpleName}, " +
                            "Expected=$expectedType, Actual=$actualType"
                )
            }
        }

        instruction.effect.localReads.forEach { local ->
            if (locals.read(local.slot) == null) {
                error("Read from undefined local: $local")
            }
        }
    }

    private fun applyInstruction(instruction: JvmInstruction) {
        repeat(instruction.effect.stackPops.size) {
            stack.pop()
        }

        instruction.effect.localWrites.forEach {
            locals.define(it.jvmType)
        }

        instruction.effect.stackPushes.forEach {
            stack.push(it)
        }
    }
}
