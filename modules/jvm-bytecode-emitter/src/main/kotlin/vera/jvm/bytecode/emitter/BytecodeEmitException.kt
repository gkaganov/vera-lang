package vera.jvm.bytecode.emitter

import vera.jvm.instruction.FlatLabel
import vera.jvm.ir.JvmType
import vera.shared.model.VeraException

sealed class BytecodeEmitException(message: String) : VeraException(message) {
    class UnboundLabel(val label: FlatLabel) : BytecodeEmitException(
        "unbound label during bytecode emission",
    )

    class UnsupportedConstant(val type: JvmType) : BytecodeEmitException(
        "unsupported constant type: $type",
    )

    class InvalidConstant(val type: JvmType, val value: Any) : BytecodeEmitException(
        "invalid constant for type $type: $value",
    )
}
