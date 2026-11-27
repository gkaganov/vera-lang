package vera.jvm.bytecode.emitter

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import vera.jvm.instruction.FlatClass
import vera.jvm.instruction.FlatJump
import vera.jvm.instruction.FlatLabel
import vera.jvm.instruction.FlatLoadConstant
import vera.jvm.instruction.FlatMethod
import vera.jvm.instruction.FlatMethodBuilder
import vera.jvm.instruction.FlatProgram
import vera.jvm.instruction.FlatReturn
import vera.jvm.ir.JvmMethodSignature
import vera.jvm.ir.JvmType
import vera.shared.model.Identifier
import vera.shared.model.VeraException

/** Bytecode emission invariants, checked with hand-built flat IR. */
class BytecodeEmitterTests {
    @Test
    fun `bytecode emission rejects unbound labels`() {
        val builder = methodBuilder()
        builder.addInstruction(FlatJump(FlatLabel()))
        assertCompilerError<BytecodeEmitException.UnboundLabel>("unbound label") {
            emit(builder.build())
        }
    }

    @Test
    fun `bytecode emission rejects unsupported constants`() {
        val builder = methodBuilder()
        builder.addInstruction(FlatLoadConstant(JvmType.OBJECT, "value"))
        assertCompilerError<BytecodeEmitException.UnsupportedConstant>("unsupported constant type") {
            emit(builder.build())
        }
    }

    @Test
    fun `bytecode emission rejects constants of the wrong type`() {
        val builder = methodBuilder()
        builder.addInstruction(FlatLoadConstant(JvmType.INT, "not an Int"))
        assertCompilerError<BytecodeEmitException.InvalidConstant>("invalid constant for type INT") {
            emit(builder.build())
        }
    }

    @Test
    fun `bytecode emission rejects classes that fail verification`() {
        val builder = methodBuilder()
        builder.addInstruction(FlatReturn())
        // A void return in a method declared to return an Int passes the flat layer but not the JVM verifier.
        val method = builder.build().copy(signature = JvmMethodSignature(emptyList(), JvmType.INT))
        assertCompilerError<BytecodeEmitException.InvalidBytecode>("generated class ErrorTest failed bytecode verification") {
            emit(method)
        }
    }

    private fun methodBuilder(): FlatMethodBuilder = FlatMethodBuilder(
        Identifier("main"),
        emptyList(),
        JvmType.VOID,
    )

    private fun emit(method: FlatMethod): Map<Identifier, ByteArray> =
        BytecodeEmitter.emit(
            FlatProgram(
                listOf(FlatClass(Identifier("ErrorTest"), listOf(method))),
            ),
        )
}

/** Runs [block] and expects an error of type [T] whose message contains [messagePart]. */
private inline fun <reified T : VeraException> assertCompilerError(messagePart: String, crossinline block: () -> Unit) {
    val error = Assertions.assertThrows(T::class.java) { block() }
    Assertions.assertTrue(
        error.render().contains(messagePart),
        "Expected error message to contain '$messagePart', but was '${error.render()}'",
    )
}
