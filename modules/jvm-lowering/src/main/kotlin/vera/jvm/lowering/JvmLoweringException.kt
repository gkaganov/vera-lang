package vera.jvm.lowering

import vera.ast.VeraTraitKind
import vera.ast.VeraType
import vera.ast.render
import vera.jvm.ir.JvmBuiltin
import vera.jvm.ir.JvmType
import vera.jvm.ir.render
import vera.shared.model.Identifier
import vera.shared.model.SourceSpan
import vera.shared.model.VeraException

enum class JvmForwardApplicationIssue {
    RIGHT_SIDE_NOT_CALLABLE,
    RIGHT_SIDE_CALLS_CALL_RESULT,
}

sealed class JvmLoweringException(message: String) : VeraException(message) {
    class ConditionNotBool(val actual: JvmType) : JvmLoweringException(
        "conditions must result in a bool, got ${actual.render()}",
    )

    class UnknownVariable(
        val name: Identifier,
        val suggestion: Identifier? = null,
        val hint: String? = null,
    ) : JvmLoweringException(
        JvmNameHints.withHelp("variable ${name.display} is not defined", hint, suggestion),
    )

    /** Rebinding a `val`; only `mut val` bindings can be rebound. */
    class ImmutableBindingRebound(val name: Identifier) : JvmLoweringException(
        "${name.display} is immutable; declare it with `mut val` to rebind it",
    )

    class MutableParameterRequiresParameter(val name: Identifier) : JvmLoweringException(
        "`mut val ${name.display}` without a value makes a parameter rebindable, but ${name.display} is not a parameter",
    )

    class MutableParameterRepeated(val name: Identifier) : JvmLoweringException(
        "parameter ${name.display} is already rebindable",
    )

    class MutableParameterOutsideFunctionBody(val name: Identifier) : JvmLoweringException(
        "`mut val ${name.display}` must be written directly in the function body, not inside a block",
    )

    /** Rebinding a parameter; parameters are immutable. */
    class ParameterRebound(val name: Identifier) : JvmLoweringException(
        "parameter ${name.display} cannot be rebound; write `mut val ${name.display}` first to make it rebindable",
    )

    class RebindTypeMismatch(
        val name: Identifier,
        val declared: JvmType,
        val actual: JvmType,
    ) : JvmLoweringException(
        "cannot rebind variable ${name.display} of type ${declared.render()} to ${actual.render()}",
    )

    class BindTypeMismatch(
        val name: Identifier,
        val declared: String,
        val actual: JvmType,
    ) : JvmLoweringException(
        "variable ${name.display} is declared as $declared but bound to a value of type ${actual.render()}",
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
        val missingElse: Boolean = false,
    ) : JvmLoweringException(
        "if branches must have the same type, got ${thenType.render()} and ${elseType.render()}" +
            if (missingElse) "; an `if` without `else` produces `Unit` when the condition is false, so add an `else`" else "",
    )

    /** The right side of `|>` names a callable without calling it. */
    class ForwardApplicationNeedsCall(val callee: String) : JvmLoweringException(
        "the right side of `|>` must be a call: write `$callee()`",
    )

    class InvalidForwardApplication(val issue: JvmForwardApplicationIssue) : JvmLoweringException(
        when (issue) {
            JvmForwardApplicationIssue.RIGHT_SIDE_NOT_CALLABLE ->
                "right-hand side of '|>' must be a callable name or member call"
            JvmForwardApplicationIssue.RIGHT_SIDE_CALLS_CALL_RESULT ->
                "right-hand side of '|>' cannot call the result of another call"
        },
    )

    class UnknownFunction(
        val name: Identifier,
        val suggestion: Identifier? = null,
        val hint: String? = null,
    ) : JvmLoweringException(
        JvmNameHints.withHelp("function ${name.display} is not defined", hint, suggestion),
    )

    class FunctionNameConflictsWithBuiltin(val name: Identifier) : JvmLoweringException(
        "function ${name.display} conflicts with the built-in function ${name.display}",
    )

    class FunctionArityMismatch(
        val function: Identifier,
        val expected: Int,
        val actual: Int,
    ) : JvmLoweringException(
        "function ${function.display} requires $expected ${if (expected == 1) "argument" else "arguments"}, got $actual",
    )

    class FunctionArgumentTypeMismatch(
        val function: Identifier,
        val parameter: Identifier,
        val expected: JvmType,
        val actual: JvmType,
    ) : JvmLoweringException(
        "function ${function.display} parameter ${parameter.display} requires ${expected.render()}, got ${actual.render()}",
    )

    class ReturnTypeMismatch(
        val expected: JvmType,
        val actual: JvmType,
    ) : JvmLoweringException(
        "return requires ${expected.render()}, got ${actual.render()}",
    )

    class MissingReturnStatement(val expected: JvmType) : JvmLoweringException(
        "missing return statement for type ${expected.render()}",
    )

    class BuiltinArityMismatch(
        val builtin: JvmBuiltin,
        val expected: Int,
        val actual: Int,
    ) : JvmLoweringException(
        "${builtin.name.lowercase()} requires exactly $expected argument",
    )

    class DuplicateFunction(val name: Identifier) : JvmLoweringException(
        "function ${name.display} is declared more than once",
    )

    class TopLevelNameConflict(val name: Identifier) : JvmLoweringException(
        "top-level name ${name.display} is declared more than once",
    )

    class ClassNameConflictsWithBuiltin(val name: Identifier) : JvmLoweringException(
        "class ${name.display} conflicts with the built-in function ${name.display}",
    )

    class ShadowingNotAllowed(val name: Identifier) : JvmLoweringException(
        "name ${name.display} is already in scope; shadowing is not allowed",
    )

    class DuplicateClassType(val name: Identifier) : JvmLoweringException(
        "class ${name.display} is declared more than once",
    )

    class ClassNameConflictsWithProgramClass(val name: Identifier) : JvmLoweringException(
        "class ${name.display} conflicts with the generated program class",
    )

    class ProgramClassConflictsWithUnit(val name: Identifier) : JvmLoweringException(
        "program class ${name.display} conflicts with the built-in Unit class",
    )

    class DuplicateClassField(
        val className: Identifier,
        val field: Identifier,
    ) : JvmLoweringException(
        "class ${className.display} has duplicate field ${field.display}",
    )

    class UnknownClassType(
        val name: Identifier,
        val suggestion: Identifier? = null,
        val hint: String? = null,
    ) : JvmLoweringException(
        JvmNameHints.withHelp("class type ${name.display} is not defined", hint, suggestion),
    )

    class MatchScrutineeNotAnObject(val actual: JvmType) : JvmLoweringException(
        "match needs an object of a class or trait, got ${actual.render()}",
    )

    /** A pattern class that is not one of the scrutinee type's cases. */
    class MatchPatternNotACase(
        val className: Identifier,
        val scrutinee: Identifier,
        val suggestion: Identifier? = null,
    ) : JvmLoweringException(
        JvmNameHints.withHelp("${className.display} is not a case of ${scrutinee.display}", null, suggestion),
    )

    class MatchArmRepeated(val className: Identifier) : JvmLoweringException(
        "${className.display} is matched more than once",
    )

    class PatternFieldBoundTwice(val field: Identifier) : JvmLoweringException(
        "field ${field.display} is bound more than once in the pattern",
    )

    /** A `match` that leaves cases uncovered; Vera has no catch-all arm, so each case is listed. */
    class MatchNotExhaustive(val scrutinee: Identifier, val missing: List<Identifier>) : JvmLoweringException(
        "match on ${scrutinee.display} does not cover ${missing.joinToString(", ") { it.display }}",
    )

    class MatchArmTypeMismatch(val types: List<JvmType>) : JvmLoweringException(
        "match arms must have the same type or share one trait, got ${types.joinToString(" and ") { it.render() }}",
    )

    class DuplicateTraitField(
        val trait: Identifier,
        val field: Identifier,
        val kind: VeraTraitKind = VeraTraitKind.TRAIT,
    ) : JvmLoweringException(
        "${kind.keyword} ${trait.display} has duplicate field ${field.display}",
    )

    class TraitFieldConflictsWithMethod(
        val trait: Identifier,
        val name: Identifier,
        val kind: VeraTraitKind = VeraTraitKind.TRAIT,
    ) : JvmLoweringException(
        "${kind.keyword} ${trait.display} has both a field and a method named ${name.display}",
    )

    /** A member class that does not declare a field its trait or interface requires. */
    class MissingTraitField(
        val className: Identifier,
        val trait: Identifier,
        val field: Identifier,
        val fieldType: VeraType,
        val kind: VeraTraitKind = VeraTraitKind.TRAIT,
    ) : JvmLoweringException(
        "${memberOwner(className, trait, kind)} no field ${field.display}; " +
            "declare `val ${field.display}: ${fieldType.render()}`",
    )

    class TraitFieldTypeMismatch(
        val className: Identifier,
        val trait: Identifier,
        val field: Identifier,
        val expected: JvmType,
        val actual: JvmType,
        val kind: VeraTraitKind = VeraTraitKind.TRAIT,
    ) : JvmLoweringException(
        "class ${className.display} field ${field.display} has type ${actual.render()}, but ${kind.keyword} " +
            "${trait.display} requires ${expected.render()}",
    )

    class DuplicateRequirement(
        val trait: Identifier,
        val required: Identifier,
        val kind: VeraTraitKind = VeraTraitKind.TRAIT,
    ) : JvmLoweringException(
        "${kind.keyword} ${trait.display} requires ${required.display} more than once",
    )

    /** Requirements that lead back to their own trait, such as `A requires B` and `B requires A`. */
    class RequirementCycle(
        val traits: List<Identifier>,
        val kind: VeraTraitKind = VeraTraitKind.TRAIT,
    ) : JvmLoweringException(
        "${kind.keyword} requirements form a cycle: ${traits.joinToString(" requires ") { it.display }}; " +
            "${kind.keyword}s that require each other have the same members, so merge them or make only one " +
            "require the other",
    )

    /** A class with a trait or interface that does not list a built-in or trait it requires. */
    class MissingRequiredTrait(
        val className: Identifier,
        val trait: Identifier,
        val required: Identifier,
        val kind: VeraTraitKind = VeraTraitKind.TRAIT,
    ) : JvmLoweringException(
        "${className.display} ${kind.joinKeyword} ${trait.display}, which requires ${required.display}; " +
            "add `has ${required.display}`",
    )

    /** Printing, interpolating, or comparing a trait-typed value whose trait does not require the built-in. */
    class TraitRequirementMissing(
        val trait: Identifier,
        val builtin: Identifier,
        val action: String,
        val kind: VeraTraitKind = VeraTraitKind.TRAIT,
    ) : JvmLoweringException(
        "${kind.keyword} ${trait.display} does not require ${builtin.display}, so its values cannot be $action; " +
            "add `requires ${builtin.display}`",
    )

    /** `has` naming an interface; classes fit interfaces by their members, and `implements` checks it. */
    class InterfaceJoinedWithHas(val className: Identifier, val interfaceName: Identifier) : JvmLoweringException(
        "${interfaceName.display} is an interface, which ${className.display} fits by having its members; " +
            "write `implements ${interfaceName.display}` to check that, not `has`",
    )

    /** `implements` naming a trait or built-in trait, which classes join with `has`. */
    class TraitImplemented(val className: Identifier, val trait: Identifier) : JvmLoweringException(
        "${trait.display} is a trait, not an interface; write `${className.display} has ${trait.display}`",
    )

    class UnknownInterface(
        val name: Identifier,
        val suggestion: Identifier? = null,
    ) : JvmLoweringException(
        JvmNameHints.withHelp("interface ${name.display} is not defined", null, suggestion),
    )

    /** An interface requiring a trait; any class can fit an interface, but only a trait's classes have it. */
    class InterfaceRequiresTrait(val interfaceName: Identifier, val trait: Identifier) : JvmLoweringException(
        "interface ${interfaceName.display} cannot require trait ${trait.display}, because any class can fit " +
            "an interface but only ${trait.display}'s classes have it; require an interface instead",
    )

    /** A trait requiring an interface, which its values already fit when it has the interface's members. */
    class TraitRequiresInterface(val trait: Identifier, val interfaceName: Identifier) : JvmLoweringException(
        "trait ${trait.display} cannot require interface ${interfaceName.display}; ${trait.display} values fit " +
            "${interfaceName.display} automatically when ${trait.display} has its members",
    )

    /** A `match` on an interface value, whose cases can never all be known. */
    class MatchOnInterface(val interfaceName: Identifier) : JvmLoweringException(
        "interface ${interfaceName.display} cannot be matched, because any class with its members fits it; " +
            "use its members, or match on a trait",
    )

    /** A class joining a block-form trait, whose cases are all declared inside it. */
    class BlockTraitJoined(val className: Identifier, val trait: Identifier) : JvmLoweringException(
        "${trait.display} lists its cases inside it; add ${className.display} there instead of joining with `has`",
    )

    /** `Owner.Name` where the trait has no such case. */
    class UnknownTraitCase(
        val trait: Identifier,
        val case: Identifier,
        val suggestion: Identifier? = null,
    ) : JvmLoweringException(
        JvmNameHints.withHelp("trait ${trait.display} has no case ${case.display}", null, suggestion),
    )

    /** A built-in trait such as `Eq` named as a type; only user traits are types. */
    class BuiltinTraitUsedAsType(val name: Identifier) : JvmLoweringException(
        "built-in trait ${name.display} cannot be used as a type",
    )

    /** A trait-typed field in a class with `Eq` or `Display`, which trait values do not support yet. */
    class ClassFieldTraitTypeNotSupported(
        val className: Identifier,
        val field: Identifier,
        val fieldType: Identifier,
        val trait: Identifier,
        val kind: VeraTraitKind = VeraTraitKind.TRAIT,
    ) : JvmLoweringException(
        "class ${className.display} has ${trait.display}, but field ${field.display} has ${kind.keyword} type " +
            "${fieldType.display}, which does not require ${trait.display}",
    )

    /** A trait-typed value has only the trait's fields and methods. */
    class UnknownTraitField(
        val trait: Identifier,
        val field: Identifier,
        val suggestion: Identifier? = null,
        val kind: VeraTraitKind = VeraTraitKind.TRAIT,
    ) : JvmLoweringException(
        JvmNameHints.withHelp("${kind.keyword} ${trait.display} has no field ${field.display}", null, suggestion),
    )

    /** A trait-typed value has only the trait's fields and methods. */
    class UnknownTraitMethod(
        val trait: Identifier,
        val method: Identifier,
        val suggestion: Identifier? = null,
        val kind: VeraTraitKind = VeraTraitKind.TRAIT,
    ) : JvmLoweringException(
        JvmNameHints.withHelp("${kind.keyword} ${trait.display} has no method ${method.display}", null, suggestion),
    )

    /** A trait or interface name that is also the name of the program class. */
    class TraitNameConflictsWithProgramClass(
        val name: Identifier,
        val kind: VeraTraitKind = VeraTraitKind.TRAIT,
    ) : JvmLoweringException(
        "${kind.keyword} ${name.display} conflicts with the program class ${name.display}",
    )

    class UnknownClassField(
        val className: Identifier,
        val field: Identifier,
        val suggestion: Identifier? = null,
    ) : JvmLoweringException(
        JvmNameHints.withHelp("class ${className.display} has no field ${field.display}", null, suggestion),
    )

    class MemberAccessRequiresObject(val actual: JvmType) : JvmLoweringException(
        "member access requires an object, got ${actual.render()}",
    )

    class ClassConstructorArityMismatch(
        val className: Identifier,
        val expected: Int,
        val actual: Int,
    ) : JvmLoweringException(
        "class ${className.display} constructor requires $expected ${if (expected == 1) "argument" else "arguments"}, got $actual",
    )

    class ClassConstructorArgumentTypeMismatch(
        val className: Identifier,
        val field: Identifier,
        val expected: JvmType,
        val actual: JvmType,
    ) : JvmLoweringException(
        "class ${className.display} field ${field.display} requires ${expected.render()}, got ${actual.render()}",
    )

    class DuplicateClassMethod(
        val className: Identifier,
        val method: Identifier,
    ) : JvmLoweringException(
        "class ${className.display} has duplicate method ${method.display}",
    )

    class ClassMethodConflictsWithField(
        val className: Identifier,
        val name: Identifier,
    ) : JvmLoweringException(
        "class ${className.display} has both a field and a method named ${name.display}",
    )

    class UnknownClassMethod(
        val className: Identifier,
        val method: Identifier,
        val suggestion: Identifier? = null,
    ) : JvmLoweringException(
        JvmNameHints.withHelp("class ${className.display} has no method ${method.display}", null, suggestion),
    )

    class MethodArityMismatch(
        val className: Identifier,
        val method: Identifier,
        val expected: Int,
        val actual: Int,
    ) : JvmLoweringException(
        "class ${className.display} method ${method.display} requires $expected ${if (expected == 1) "argument" else "arguments"}, got $actual",
    )

    class MethodArgumentTypeMismatch(
        val className: Identifier,
        val method: Identifier,
        val parameter: Identifier,
        val expected: JvmType,
        val actual: JvmType,
    ) : JvmLoweringException(
        "class ${className.display} method ${method.display} parameter ${parameter.display} requires ${expected.render()}, got ${actual.render()}",
    )

    class ClassMethodUsedAsValue(
        val className: Identifier,
        val method: Identifier,
    ) : JvmLoweringException(
        "class ${className.display} method ${method.display} must be called",
    )

    /** `Empty()`: a field-less class is a singleton written by its name. */
    class SingletonConstructed(val className: Identifier) : JvmLoweringException(
        "${className.display} has no fields, so it has a single value; write `${className.display}` instead of `${className.display}()`",
    )

    class ClassTypeUsedAsValue(val name: Identifier) : JvmLoweringException(
        "class type ${name.display} cannot be used as a value",
    )

    class ObjectEqualityRequiresTrait(val className: Identifier) : JvmLoweringException(
        "class ${className.display} does not have the Eq trait, so its objects cannot be compared",
    )

    class PrintRequiresDisplayTrait(val className: Identifier) : JvmLoweringException(
        "class ${className.display} does not have the Display trait, so its objects cannot be printed",
    )

    class TemplateValueRequiresDisplayTrait(val className: Identifier) : JvmLoweringException(
        "class ${className.display} does not have the Display trait, so its objects cannot be used in a string template",
    )

    class ClassFieldRequiresTrait(
        val className: Identifier,
        val field: Identifier,
        val fieldType: Identifier,
        val trait: Identifier,
    ) : JvmLoweringException(
        "class ${className.display} field ${field.display} has type ${fieldType.display}, which does not have the ${trait.display} trait",
    )

    /** A member named like a generated `java.lang.Object` method. */
    class ReservedMemberName(val name: Identifier) : JvmLoweringException(
        "${name.display} is reserved for the method that Vera generates; " + when (name.value) {
            "toString" -> "printing comes from the `Display` trait, so add `has Display`"
            "equals" -> "`==` comes from the `Eq` trait, so add `has Eq`"
            else -> "it belongs with the `Eq` trait"
        },
    )

    class DuplicateTrait(val name: Identifier, val kind: VeraTraitKind = VeraTraitKind.TRAIT) : JvmLoweringException(
        "${kind.keyword} ${name.display} is declared more than once",
    )

    /** A function, class, or trait reuses a built-in trait name such as `Eq` or `Display`. */
    class NameConflictsWithBuiltinTrait(val name: Identifier) : JvmLoweringException(
        "name ${name.display} is reserved for a built-in trait",
    )

    class DuplicateTraitMethod(
        val trait: Identifier,
        val method: Identifier,
        val kind: VeraTraitKind = VeraTraitKind.TRAIT,
    ) : JvmLoweringException(
        "${kind.keyword} ${trait.display} has duplicate method ${method.display}",
    )

    class UnknownTrait(
        val name: Identifier,
        val suggestion: Identifier? = null,
    ) : JvmLoweringException(
        JvmNameHints.withHelp("trait ${name.display} is not defined", null, suggestion),
    )

    /** `+` with a `String` operand; Vera builds strings with templates. */
    class StringConcatenationNotSupported : JvmLoweringException(
        "Vera builds strings with templates, not `+`; write \"text \\{value}\"",
    )

    class DuplicateTraitImplementation(
        val className: Identifier,
        val trait: Identifier,
        val kind: VeraTraitKind = VeraTraitKind.TRAIT,
    ) : JvmLoweringException(
        "class ${className.display} ${kind.joinKeyword} ${kind.keyword} ${trait.display} more than once",
    )

    class MissingTraitMethod(
        val className: Identifier,
        val trait: Identifier,
        val method: Identifier,
        val kind: VeraTraitKind = VeraTraitKind.TRAIT,
    ) : JvmLoweringException(
        "${memberOwner(className, trait, kind)} no method ${method.display}",
    )

    class TraitMethodSignatureMismatch(
        val className: Identifier,
        val trait: Identifier,
        val method: Identifier,
        val expectedParameters: List<JvmType>,
        val expectedReturn: JvmType,
        val actualParameters: List<JvmType>,
        val actualReturn: JvmType,
        val kind: VeraTraitKind = VeraTraitKind.TRAIT,
    ) : JvmLoweringException(
        "class ${className.display} method ${method.display} does not match ${kind.keyword} ${trait.display}: " +
            "expected ${renderSignature(expectedParameters, expectedReturn)}, " +
            "got ${renderSignature(actualParameters, actualReturn)}",
    )

    /** `fn name() = expression` whose expression is not `Unit` but has no return type written. */
    class ExpressionBodyReturnTypeRequired(val function: Identifier, val actual: JvmType) : JvmLoweringException(
        "function ${function.display} returns ${actual.render()}, so its return type must be written: " +
            "`fn ${function.display}(...): ${actual.render()} = ...`",
    )

    class MissingReturnValue(val actual: JvmType) : JvmLoweringException(
        "return without an expression requires a Unit function, but this function returns ${actual.render()}",
    )

    class ValueCallNotSupported : JvmLoweringException(
        "calling a value is not supported yet",
    )

    /** A call with one written argument names it; a single written argument is passed without a name. */
    class SingleArgumentNamed(val callee: String, val name: Identifier) : JvmLoweringException(
        "a call to $callee with one written argument passes it without a name; remove `${name.display} =`",
    )

    /** A call with two or more arguments passes one without its parameter name. */
    class ArgumentNameRequired(
        val callee: String,
        val position: Int,
        val parameter: Identifier,
    ) : JvmLoweringException(
        "argument $position of $callee must be named: write `${parameter.display} = ...`",
    )

    /** An argument is named after no parameter of the callee. */
    class UnknownArgumentName(
        val callee: String,
        val name: Identifier,
        val suggestion: Identifier?,
    ) : JvmLoweringException(
        JvmNameHints.withHelp("$callee has no parameter named ${name.display}", null, suggestion),
    )

    /** An argument names a different parameter than the one at its position. */
    class ArgumentNameMismatch(
        val callee: String,
        val position: Int,
        val actual: Identifier,
        val expected: Identifier,
    ) : JvmLoweringException(
        "argument $position of $callee is named ${actual.display}, but parameter $position is " +
            "${expected.display}; arguments must follow the parameter order",
    )

    /** A class method implementing a trait renames one of the trait method's parameters. */
    class TraitMethodParameterNameMismatch(
        val className: Identifier,
        val trait: Identifier,
        val method: Identifier,
        val expected: Identifier,
        val actual: Identifier,
        val kind: VeraTraitKind = VeraTraitKind.TRAIT,
    ) : JvmLoweringException(
        "class ${className.display} method ${method.display} names a parameter ${actual.display}, but " +
            "${kind.keyword} ${trait.display} names it ${expected.display}; parameter names are part of the " +
            kind.keyword,
    )

    /** A statement follows one that always leaves the block, such as `return` or `break`. */
    class UnreachableStatement(val after: String) : JvmLoweringException(
        "unreachable statement after $after",
    )

    class BreakOutsideLoop : JvmLoweringException(
        "break is only allowed inside a loop",
    )

    class ContinueOutsideLoop : JvmLoweringException(
        "continue is only allowed inside a loop",
    )

    /** `break` or `continue` inside an expression whose evaluation is still in progress. */
    class LoopJumpInsideExpression(val keyword: String) : JvmLoweringException(
        "$keyword cannot be used inside a nested expression; use it in a statement or in an if that forms a whole statement",
    )

    class InternalInvariant(val detail: String) : JvmLoweringException(
        "internal compiler error: $detail",
    )
}

private fun renderSignature(parameters: List<JvmType>, returnType: JvmType): String =
    "(${parameters.joinToString(", ") { it.render() }}) -> ${returnType.render()}"

/** How a message about a member missing from a class starts: `class Login has trait Event but`. */
private fun memberOwner(className: Identifier, trait: Identifier, kind: VeraTraitKind): String = when (kind) {
    VeraTraitKind.TRAIT -> "class ${className.display} has trait ${trait.display} but"
    VeraTraitKind.INTERFACE -> "class ${className.display} implements interface ${trait.display} but has"
}

/**
 * Runs [lowering] and attaches [span] to any compiler error raised inside it that has no location
 * yet. Nested calls therefore report the innermost declaration, statement, or expression.
 */
internal inline fun <T> located(span: SourceSpan, lowering: () -> T): T {
    try {
        return lowering()
    } catch (exception: VeraException) {
        exception.attachSpan(span)
        throw exception
    }
}
