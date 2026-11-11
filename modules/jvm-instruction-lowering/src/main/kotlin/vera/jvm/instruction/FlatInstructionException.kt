package vera.jvm.instruction

import vera.jvm.ir.JvmType
import vera.shared.model.Identifier
import vera.shared.model.VeraException

sealed class FlatInstructionException(message: String) : VeraException(message) {
    class StackUnderflow(
        val instruction: String,
        val expected: Int,
        val actual: Int,
    ) : FlatInstructionException(
        "stack underflow in $instruction: expected $expected values, actual $actual",
    )

    class StackTypeMismatch(
        val instruction: String,
        val expected: JvmType,
        val actual: JvmType,
    ) : FlatInstructionException(
        "invalid stack type in $instruction: expected $expected, actual $actual",
    )

    class UndefinedLocal(val name: Identifier) : FlatInstructionException(
        "local ${name.value} is not defined",
    )

    class BuiltinArityMismatch(
        val expected: Int,
        val actual: Int,
    ) : FlatInstructionException(
        "print requires exactly $expected argument, got $actual",
    )

    class BreakOutsideLoop : FlatInstructionException(
        "break is only allowed inside a loop",
    )

    class ContinueOutsideLoop : FlatInstructionException(
        "continue is only allowed inside a loop",
    )

    class InternalInvariant(val detail: String) : FlatInstructionException(
        "internal instruction error: $detail",
    )
}
