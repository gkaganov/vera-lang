package vera.jvm.ir

data class JvmProgram(
    val methods: List<JvmMethod>,
)

class JvmMethod(
    parameterTypes: List<JvmType>,
    val returnType: JvmType,
) {
    private val locals = LocalTable()
    private val stack = OperandStack()

    private val _instructions = mutableListOf<VerifiedInstruction>()
    val instructions: List<VerifiedInstruction>
        get() = _instructions

    init {
        parameterTypes.forEachIndexed { index, type ->
            locals.define(LocalSlot(index), type)
        }
    }

    fun emit(instruction: JvmInstruction) {
        val before = FrameSnapshot(
            stack = stack.snapshot(),
            locals = locals.snapshot(),
        )

        verifyInstruction(instruction)
        applyInstruction(instruction)

        val after = FrameSnapshot(
            stack = stack.snapshot(),
            locals = locals.snapshot(),
        )

        _instructions += VerifiedInstruction(
            instruction = instruction,
            before = before,
            after = after,
        )
    }

    private fun verifyInstruction(instruction: JvmInstruction) {
        val pops = instruction.effect.pops

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

        instruction.effect.localReads.forEach { slot ->
            if (!locals.exists(slot)) {
                error("Read from undefined local: $slot")
            }
        }
    }

    private fun applyInstruction(instruction: JvmInstruction) {
        repeat(instruction.effect.pops.size) {
            stack.pop()
        }

        instruction.effect.localWrites.forEach {
            locals.define(it.slot, it.type)
        }

        instruction.effect.pushes.forEach {
            stack.push(it)
        }
    }
}

data class VerifiedInstruction(
    val instruction: JvmInstruction,
    val before: FrameSnapshot,
    val after: FrameSnapshot,
)

data class FrameSnapshot(
    val stack: List<JvmType>,
    val locals: Map<LocalSlot, JvmType>,
)

class OperandStack {
    private val values = mutableListOf<JvmType>()

    fun push(type: JvmType) {
        values += type
    }

    fun pop(): JvmType {
        return values.removeLast()
    }

    fun peek(amount: Int): List<JvmType> {
        return values.takeLast(amount)
    }

    fun size(): Int {
        return values.size
    }

    fun snapshot(): List<JvmType> {
        return values.toList()
    }
}

@JvmInline value class LocalSlot(val index: Int)

class LocalTable {
    private val locals = mutableMapOf<LocalSlot, JvmType>()

    fun define(slot: LocalSlot, type: JvmType) {
        locals[slot] = type
    }

    fun read(slot: LocalSlot): JvmType {
        return locals[slot]
            ?: error("Undefined local: $slot")
    }

    fun exists(slot: LocalSlot): Boolean {
        return slot in locals
    }

    fun snapshot(): Map<LocalSlot, JvmType> {
        return locals.toMap()
    }
}

data class InstructionEffect(
    val pops: List<JvmType> = emptyList(),
    val pushes: List<JvmType> = emptyList(),
    val localReads: List<LocalSlot> = emptyList(),
    val localWrites: List<LocalWrite> = emptyList(),
)

data class LocalWrite(
    val slot: LocalSlot,
    val type: JvmType,
)

sealed interface JvmInstruction {
    val effect: InstructionEffect
}

enum class JvmType { INT, REFERENCE }

data class IConst(val value: Int) : JvmInstruction {
    override val effect = InstructionEffect(
        pushes = listOf(JvmType.INT),
    )
}

data class ILoad(val slot: LocalSlot) : JvmInstruction {
    override val effect = InstructionEffect(
        localReads = listOf(slot),
        pushes = listOf(JvmType.INT),
    )
}

data class ALoad(val slot: LocalSlot) : JvmInstruction {
    override val effect = InstructionEffect(
        localReads = listOf(slot),
        pushes = listOf(JvmType.REFERENCE),
    )
}

data class IStore(val slot: LocalSlot) : JvmInstruction {
    override val effect = InstructionEffect(
        pops = listOf(JvmType.INT),
        localWrites = listOf(LocalWrite(slot, JvmType.INT)),
    )
}

data class AStore(val slot: LocalSlot) : JvmInstruction {
    override val effect = InstructionEffect(
        pops = listOf(JvmType.REFERENCE),
        localWrites = listOf(LocalWrite(slot, JvmType.REFERENCE)),
    )
}

object IAdd : JvmInstruction {
    override val effect = InstructionEffect(
        pops = listOf(JvmType.INT, JvmType.INT),
        pushes = listOf(JvmType.INT),
    )
}

object ISub : JvmInstruction {
    override val effect = InstructionEffect(
        pops = listOf(JvmType.INT, JvmType.INT),
        pushes = listOf(JvmType.INT),
    )
}

object IMul : JvmInstruction {
    override val effect = InstructionEffect(
        pops = listOf(JvmType.INT, JvmType.INT),
        pushes = listOf(JvmType.INT),
    )
}

object IDiv : JvmInstruction {
    override val effect = InstructionEffect(
        pops = listOf(JvmType.INT, JvmType.INT),
        pushes = listOf(JvmType.INT),
    )
}

data class Return(val type: JvmType?) : JvmInstruction {
    override val effect = InstructionEffect(
        pops = if (type != null) listOf(type) else emptyList()
    )
}
