package vera.compiler.test

import org.junit.jupiter.api.Test
import vera.jvm.bytecode.emitter.BytecodeEmitException
import vera.jvm.bytecode.emitter.BytecodeEmitter
import vera.jvm.instruction.FlatClass
import vera.jvm.instruction.FlatJump
import vera.jvm.instruction.FlatLabel
import vera.jvm.instruction.FlatLoadConstant
import vera.jvm.instruction.FlatMethod
import vera.jvm.instruction.FlatMethodBuilder
import vera.jvm.instruction.FlatProgram
import vera.jvm.ir.JvmType
import vera.shared.model.Identifier

/** Bytecode emission invariants, checked with hand-built flat IR. */
class BytecodeEmissionTests {
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

    private fun methodBuilder(): FlatMethodBuilder = FlatMethodBuilder(
        Identifier(TEST_MAIN),
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
