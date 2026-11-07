package vera.jvm.lowering

import vera.jvm.ir.JvmBuiltin
import vera.jvm.ir.JvmType
import vera.shared.model.Identifier
import vera.shared.model.VeraException

enum class JvmForwardApplicationIssue {
    RIGHT_SIDE_NOT_FUNCTION_NAME,
    RIGHT_SIDE_CONTAINS_MEMBER_ACCESS,
    MULTIPLE_ARGUMENT_LISTS,
}

sealed class JvmLoweringException(message: String) : VeraException(message) {
    class ConditionNotBool(val actual: JvmType) : JvmLoweringException(
        "conditions must result in a bool, got $actual",
    )

    class UnknownVariable(val name: Identifier) : JvmLoweringException(
        "variable ${name.value} is not defined",
    )

    class RebindTypeMismatch(
        val name: Identifier,
        val declared: JvmType,
        val actual: JvmType,
    ) : JvmLoweringException(
        "cannot rebind variable ${name.value} of type $declared to $actual",
    )

    class BindTypeMismatch(val declared: String, val actual: JvmType) : JvmLoweringException(
        "the type of the bind statement must either UNKNOWN (to be inferred) or the same as the containing expression",
    )

    class BoolOperandsRequired(val operator: String, val actual: JvmType) : JvmLoweringException(
        "operator '$operator' is only defined for Bool operands, got $actual",
    )

    class OperandTypesDiffer(
        val operator: String,
        val left: JvmType,
        val right: JvmType,
    ) : JvmLoweringException(
        "operator '$operator' requires both operands to have the same type, got $left and $right",
    )

    class IntOperandsRequired(val operator: String, val actual: JvmType) : JvmLoweringException(
        "operator '$operator' is only defined for Int operands, got $actual",
    )

    class IfBranchTypeMismatch(
        val thenType: JvmType,
        val elseType: JvmType,
    ) : JvmLoweringException(
        "if branches must have the same type, got $thenType and $elseType",
    )

    class InvalidForwardApplication(val issue: JvmForwardApplicationIssue) : JvmLoweringException(
        when (issue) {
            JvmForwardApplicationIssue.RIGHT_SIDE_NOT_FUNCTION_NAME ->
                "right-hand side of '|' must be a function name"
            JvmForwardApplicationIssue.RIGHT_SIDE_CONTAINS_MEMBER_ACCESS ->
                "right-hand side of '|' must not contain member access"
            JvmForwardApplicationIssue.MULTIPLE_ARGUMENT_LISTS ->
                "right-hand side of '|' must contain at most one argument list"
        },
    )

    class UnknownFunction(val name: Identifier) : JvmLoweringException(
        "function ${name.value} not found.",
    )

    class BuiltinArityMismatch(
        val builtin: JvmBuiltin,
        val expected: Int,
        val actual: Int,
    ) : JvmLoweringException(
        "${builtin.name.lowercase()} requires exactly $expected argument",
    )

    class MemberAccessNotSupported : JvmLoweringException(
        "member access and call chains on resolved expressions are not supported yet",
    )

    class UnexpectedPendingSymbol : JvmLoweringException(
        "unexpected pending symbol in primary expression",
    )

    class InternalInvariant(val detail: String) : JvmLoweringException(
        "internal compiler error: $detail",
    )
}
