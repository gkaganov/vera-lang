package vera.jvm.instruction

import vera.jvm.ir.JvmType
import vera.shared.model.Identifier
import vera.shared.model.VeraException

sealed class JvmInstructionException(message: String) : VeraException(message) {
    class StackUnderflow(
        val instruction: String,
        val expected: Int,
        val actual: Int,
    ) : JvmInstructionException(
        "stack underflow in $instruction: expected $expected values, actual $actual",
    )

    class StackTypeMismatch(
        val instruction: String,
        val expected: JvmType,
        val actual: JvmType,
    ) : JvmInstructionException(
        "invalid stack type in $instruction: expected $expected, actual $actual",
    )

    class UndefinedLocal(val name: Identifier) : JvmInstructionException(
        "local ${name.value} is not defined",
    )

    class BuiltinArityMismatch(
        val expected: Int,
        val actual: Int,
    ) : JvmInstructionException(
        "print requires exactly $expected argument, got $actual",
    )

    class InternalInvariant(val detail: String) : JvmInstructionException(
        "internal instruction error: $detail",
    )
}
