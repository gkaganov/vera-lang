package vera.jvm.bytecode.emitter

import vera.jvm.instruction.JvmLabel
import vera.jvm.ir.JvmType
import vera.shared.model.VeraException

sealed class JvmEmitException(message: String) : VeraException(message) {
    class UnboundLabel(val label: JvmLabel) : JvmEmitException(
        "unbound label during bytecode emission",
    )

    class UnsupportedConstant(val type: JvmType) : JvmEmitException(
        "unsupported constant type: $type",
    )

    class InvalidConstant(val type: JvmType, val value: Any) : JvmEmitException(
        "invalid constant for type $type: $value",
    )
}
