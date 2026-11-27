package vera.jvm.bytecode.emitter

import vera.jvm.instruction.FlatLabel
import vera.jvm.ir.JvmType
import vera.shared.model.Identifier
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

    /** A generated class that fails JVM bytecode verification: always a compiler bug. */
    class InvalidBytecode(val className: Identifier, val problems: List<String>) : BytecodeEmitException(
        "internal compiler error: generated class ${className.display} failed bytecode verification: " +
            problems.joinToString("; "),
    )
}
