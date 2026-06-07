package vera.jvm.ir

import vera.jvm.ir.LocalTable.LocalName
import vera.jvm.ir.LocalTable.LocalWithName
import vera.jvm.ir.LocalTable.LocalWithSlot
import vera.shared.model.Identifier

data class JvmProgram(val classes: List<JvmClass>)

data class JvmClass(val name: Identifier, val methods: List<JvmMethod>)

data class JvmValue(val type: JvmType)

data class JvmMethod(val instructions: List<VerifiedInstruction>, val signature: JvmMethodSignature)

data class JvmMethodSignature(val parameters: List<JvmParameter>, val returnType: JvmType)

data class JvmParameter(val name: Identifier, val type: JvmType)

class JvmMethodBuilder(val parameters: List<JvmParameter>, val returnType: JvmType) {
    private val locals = LocalTable()
    private val stack = OperandStack()

    private val _instructions = mutableListOf<VerifiedInstruction>()
    val instructions: List<VerifiedInstruction>
        get() = _instructions

    init {
        parameters.forEach { param ->
            locals.define(LocalWithName(LocalName(param.name), JvmValue(param.type)))
        }
    }

    fun addInstruction(instruction: JvmInstruction) {
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

    fun build(): JvmMethod = JvmMethod(instructions, JvmMethodSignature(parameters, returnType))

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

        instruction.effect.localReads.forEach { localByName ->
            if (!locals.exists(localByName.name)) {
                error("Read from undefined local: $localByName")
            }
        }
    }

    private fun applyInstruction(instruction: JvmInstruction) {
        repeat(instruction.effect.pops.size) {
            stack.pop()
        }

        instruction.effect.localWrites.forEach {
            locals.define(LocalWithName(it.name, it.jvmValue))
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

data class FrameSnapshot(val stack: List<JvmValue>, val locals: Map<LocalName, LocalWithSlot>)

class OperandStack {
    private val values = mutableListOf<JvmValue>()

    fun push(value: JvmValue) {
        values += value
    }

    fun pop(): JvmValue {
        return values.removeLast()
    }

    fun peek(amount: Int): List<JvmValue> {
        return values.takeLast(amount)
    }

    fun size(): Int {
        return values.size
    }

    fun snapshot(): List<JvmValue> {
        return values.toList()
    }
}

class LocalTable {
    @JvmInline value class LocalSlot(val index: Int)
    @JvmInline value class LocalName(val value: Identifier)

    /** Local with slot for jvm bytecode emitter. */
    data class LocalWithSlot(val slot: LocalSlot, val jvmValue: JvmValue)

    /** Local with name for vera compiler. */
    data class LocalWithName(val name: LocalName, val jvmValue: JvmValue)

    private val locals = mutableMapOf<LocalName, LocalWithSlot>()
    private var nextFreeSlot = LocalSlot(0)

    fun define(localWithName: LocalWithName): LocalSlot {
        // TODO check if redeclaration
        val localWithSlot = LocalWithSlot(nextFreeSlot, localWithName.jvmValue)
        locals[localWithName.name] = localWithSlot
        nextFreeSlot = LocalSlot(nextFreeSlot.index + 1)
        return localWithSlot.slot
    }

    fun read(name: LocalName): JvmValue = locals[name]?.jvmValue ?: error("Undefined local: $name")
    fun snapshot(): Map<LocalName, LocalWithSlot> = locals.toMap()
    fun exists(name: LocalName): Boolean = locals[name] != null
}

data class InstructionEffect(
    val pops: List<JvmValue> = emptyList(),
    val pushes: List<JvmValue> = emptyList(),
    val localReads: List<LocalWithName> = emptyList(),
    val localWrites: List<LocalWithName> = emptyList(),
)

sealed interface JvmInstruction {
    val effect: InstructionEffect
}

enum class JvmType { INT, REFERENCE, VOID }

data class Load(val localWithName: LocalWithName) : JvmInstruction {
    override val effect = InstructionEffect(
        localReads = listOf(localWithName),
        pushes = listOf(localWithName.jvmValue),
    )
}

data class Store(val localWithName: LocalWithName) : JvmInstruction {
    override val effect = InstructionEffect(
        pops = listOf(localWithName.jvmValue),
        localWrites = listOf(localWithName),
    )
}

data class ConstInt(val value: Int) : JvmInstruction {
    override val effect = InstructionEffect(
        pushes = listOf(JvmValue(JvmType.INT)),
    )
}

enum class IntBinaryOperator { ADD, SUB, MUL, DIV, }

data class IntBinaryOperation(val operator: IntBinaryOperator) : JvmInstruction {
    override val effect = InstructionEffect(
        pops = listOf(JvmValue(JvmType.INT), JvmValue(JvmType.INT)),
        pushes = listOf(JvmValue(JvmType.INT)),
    )
}

data class Invokestatic(val ownerClass: JvmClass, val methodName: Identifier, val methodSignature: JvmMethodSignature) : JvmInstruction {
    override val effect = InstructionEffect(
        pushes = listOf(JvmValue(JvmType.INT)),
    )
}

data class Return(val value: JvmValue? = null) : JvmInstruction {
    override val effect = InstructionEffect(
        pops = if (value != null) listOf(value) else emptyList()
    )
}
