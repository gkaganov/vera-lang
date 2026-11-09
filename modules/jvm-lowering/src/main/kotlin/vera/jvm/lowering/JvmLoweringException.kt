package vera.jvm.lowering

import vera.jvm.ir.JvmBuiltin
import vera.jvm.ir.JvmType
import vera.jvm.ir.render
import vera.shared.model.Identifier
import vera.shared.model.VeraException

enum class JvmForwardApplicationIssue {
    RIGHT_SIDE_NOT_FUNCTION_NAME,
    RIGHT_SIDE_CONTAINS_MEMBER_ACCESS,
    MULTIPLE_ARGUMENT_LISTS,
}

sealed class JvmLoweringException(message: String) : VeraException(message) {
    class ConditionNotBool(val actual: JvmType) : JvmLoweringException(
        "conditions must result in a bool, got ${actual.render()}",
    )

    class UnknownVariable(val name: Identifier) : JvmLoweringException(
        "variable ${name.value} is not defined",
    )

    class RebindTypeMismatch(
        val name: Identifier,
        val declared: JvmType,
        val actual: JvmType,
    ) : JvmLoweringException(
        "cannot rebind variable ${name.value} of type ${declared.render()} to ${actual.render()}",
    )

    class BindTypeMismatch(val declared: String, val actual: JvmType) : JvmLoweringException(
        "the type of the bind statement must either UNKNOWN (to be inferred) or the same as the containing expression",
    )

    class BoolOperandsRequired(val operator: String, val actual: JvmType) : JvmLoweringException(
        "operator '$operator' is only defined for Bool operands, got ${actual.render()}",
    )

    class OperandTypesDiffer(
        val operator: String,
        val left: JvmType,
        val right: JvmType,
    ) : JvmLoweringException(
        "operator '$operator' requires both operands to have the same type, got ${left.render()} and ${right.render()}",
    )

    class IntOperandsRequired(val operator: String, val actual: JvmType) : JvmLoweringException(
        "operator '$operator' is only defined for Int operands, got ${actual.render()}",
    )

    class IfBranchTypeMismatch(
        val thenType: JvmType,
        val elseType: JvmType,
    ) : JvmLoweringException(
        "if branches must have the same type, got ${thenType.render()} and ${elseType.render()}",
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

    class PrintArgumentTypeNotSupported(val actual: JvmType) : JvmLoweringException(
        "print does not support values of type ${actual.render()}",
    )

    class DuplicateRecordType(val name: Identifier) : JvmLoweringException(
        "record ${name.value} is declared more than once",
    )

    class RecordNameConflictsWithProgramClass(val name: Identifier) : JvmLoweringException(
        "record ${name.value} conflicts with the generated program class",
    )

    class ProgramClassConflictsWithUnit(val name: Identifier) : JvmLoweringException(
        "program class ${name.value} conflicts with the built-in Unit class",
    )

    class DuplicateRecordField(
        val record: Identifier,
        val field: Identifier,
    ) : JvmLoweringException(
        "record ${record.value} has duplicate field ${field.value}",
    )

    class RecordFieldTypeNotAllowed(
        val record: Identifier,
        val field: Identifier,
        val type: String,
    ) : JvmLoweringException(
        "record field ${record.value}.${field.value} cannot have type $type",
    )

    class UnknownRecordType(val name: Identifier) : JvmLoweringException(
        "record type ${name.value} is not defined",
    )

    class UnknownRecordField(
        val record: Identifier,
        val field: Identifier,
    ) : JvmLoweringException(
        "record ${record.value} has no field ${field.value}",
    )

    class MemberAccessRequiresRecord(val actual: JvmType) : JvmLoweringException(
        "member access requires a record value, got ${actual.render()}",
    )

    class RecordConstructorArityMismatch(
        val record: Identifier,
        val expected: Int,
        val actual: Int,
    ) : JvmLoweringException(
        "record ${record.value} constructor requires $expected arguments, got $actual",
    )

    class RecordConstructorArgumentTypeMismatch(
        val record: Identifier,
        val field: Identifier,
        val expected: JvmType,
        val actual: JvmType,
    ) : JvmLoweringException(
        "record ${record.value} field ${field.value} requires ${expected.render()}, got ${actual.render()}",
    )

    class RecordTypeUsedAsValue(val name: Identifier) : JvmLoweringException(
        "record type ${name.value} cannot be used as a value",
    )

    class MissingReturnValue(val actual: JvmType) : JvmLoweringException(
        "return without an expression requires a Unit function, but this function returns ${actual.render()}",
    )

    class ValueCallNotSupported : JvmLoweringException(
        "calling a value is not supported yet",
    )

    class UnexpectedPendingSymbol : JvmLoweringException(
        "unexpected pending symbol in primary expression",
    )

    class InternalInvariant(val detail: String) : JvmLoweringException(
        "internal compiler error: $detail",
    )
}
