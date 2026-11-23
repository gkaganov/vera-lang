package vera.ast

import vera.shared.model.Identifier
import vera.shared.model.SourceSpan

// Every declaration, statement, and expression carries the source span it was parsed from, so
// later stages can report errors at the construct that caused them. Declaration spans cover the
// header up to the name (`fn add`, `class Point`), not the whole body.

data class VeraProgram(val declarations: List<VeraDeclaration>)

sealed interface VeraDeclaration {
    val name: Identifier
    val span: SourceSpan
}

data class VeraFunctionDeclaration(
    override val name: Identifier,
    val parameters: List<VeraParameter>,
    val returnType: VeraType,
    val body: VeraFunctionBody,
    override val span: SourceSpan,
) : VeraDeclaration

/** A function body: a block with explicit `return`s, or `= expression`. */
sealed interface VeraFunctionBody
data class VeraBlockBody(val block: VeraExplicitReturnBlock) : VeraFunctionBody
data class VeraExpressionBody(val expression: VeraPrimaryExpression) : VeraFunctionBody

data class VeraClassDeclaration(
    override val name: Identifier,
    val fields: List<VeraField>,
    val methods: List<VeraFunctionDeclaration>,
    val traits: List<Identifier>,
    override val span: SourceSpan,
) : VeraDeclaration

data class VeraField(val name: Identifier, val type: VeraType, val span: SourceSpan)

data class VeraTraitDeclaration(
    override val name: Identifier,
    val methods: List<VeraTraitMethod>,
    override val span: SourceSpan,
) : VeraDeclaration

data class VeraTraitMethod(
    val name: Identifier,
    val parameters: List<VeraParameter>,
    val returnType: VeraType,
    val span: SourceSpan,
)

sealed interface VeraStatement {
    val span: SourceSpan
}

/** `val name = value`, or `mut val name = value` when [mutable] so the binding can be rebound. */
data class VeraBindStatement(
    val name: Identifier,
    val type: VeraType,
    val expression: VeraExpression,
    val mutable: Boolean,
    override val span: SourceSpan,
) : VeraStatement

/** `mut val name` without a value: makes the parameter [name] rebindable from here on. */
data class VeraMutableParameter(val name: Identifier, override val span: SourceSpan) : VeraStatement

data class VeraRebindStatement(
    val name: Identifier,
    val expression: VeraExpression,
    override val span: SourceSpan,
) : VeraStatement

data class VeraReturnStatement(val expression: VeraExpression?, override val span: SourceSpan) : VeraStatement

data class VeraWhileStatement(
    val condition: VeraPrimaryExpression,
    val body: VeraUnitReturnBlock,
    override val span: SourceSpan,
) : VeraStatement

/** An expression used as a statement or as the value of a bind, rebind, or return. */
data class VeraExpression(val expression: VeraPrimaryExpression) : VeraStatement {
    override val span: SourceSpan get() = expression.span
}

data class VeraBreakStatement(override val span: SourceSpan) : VeraStatement

data class VeraContinueStatement(override val span: SourceSpan) : VeraStatement

data class VeraChainedExpression(
    val primaryExpression: VeraPrimaryExpression,
    val data: List<VeraChainedExpressionData>,
    override val span: SourceSpan,
) : VeraPrimaryExpression

sealed interface VeraChainedExpressionData {
    val span: SourceSpan
}

/** `.member`; the span covers the member name. */
data class VeraMemberAccess(val member: Identifier, override val span: SourceSpan) : VeraChainedExpressionData

/** `(arguments)`; the span covers the parentheses. */
data class VeraArguments(val arguments: List<VeraArgument>, override val span: SourceSpan) :
    VeraChainedExpressionData

/** A call argument. [name] is set for `name = value`; a bare argument has no name. */
data class VeraArgument(val name: Identifier?, val expression: VeraPrimaryExpression, val span: SourceSpan)

enum class VeraOperator(val symbol: String) {
    FORWARD_APPLICATION("|>"),
    OR("or"),
    AND("and"),
    EQUALS("=="), NOT_EQUALS("!="),
    LESS("<"), GREATER(">"), LESS_EQ("<="), GREATER_EQ(">="),
    ADD("+"), SUBTRACT("-"),
    MULTIPLY("*"), DIVIDE("/"), PERCENT("%")
}

enum class VeraUnaryOperator(val symbol: String) {
    NEGATE("-"),
    NOT("not"),
}

sealed interface VeraPrimaryExpression {
    val span: SourceSpan
}

data class VeraUnaryOperation(
    val operator: VeraUnaryOperator,
    val operand: VeraPrimaryExpression,
    override val span: SourceSpan,
) : VeraPrimaryExpression

data class VeraBinaryOperation(
    val left: VeraPrimaryExpression,
    val operator: VeraOperator,
    val right: VeraPrimaryExpression,
    override val span: SourceSpan,
) : VeraPrimaryExpression

sealed interface VeraLiteral : VeraPrimaryExpression
data class VeraIntLiteral(val value: Int, override val span: SourceSpan) : VeraLiteral
data class VeraStringLiteral(val value: String, override val span: SourceSpan) : VeraLiteral
data class VeraBoolLiteral(val value: Boolean, override val span: SourceSpan) : VeraLiteral
data class VeraUnitLiteral(override val span: SourceSpan) : VeraLiteral

/** A string with `\{expr}` placeholders, as text and value parts in source order. */
data class VeraStringTemplate(val parts: List<VeraTemplatePart>, override val span: SourceSpan) :
    VeraPrimaryExpression

sealed interface VeraTemplatePart
data class VeraTemplateText(val text: String) : VeraTemplatePart
data class VeraTemplateValue(val expression: VeraPrimaryExpression) : VeraTemplatePart

data class VeraIdentifierAsExpression(val identifier: Identifier, override val span: SourceSpan) :
    VeraPrimaryExpression

sealed interface VeraBlock {
    val statements: List<VeraStatement>
}

/** A block whose statements use explicit `return` statements. */
data class VeraExplicitReturnBlock(override val statements: List<VeraStatement>) : VeraBlock

/** A block whose value is its final expression; it returns implicitly. */
data class VeraImplicitReturnBlock(
    override val statements: List<VeraStatement>,
    val result: VeraPrimaryExpression,
) : VeraBlock

/** A block that produces no value and returns implicitly as `Unit`. */
data class VeraUnitReturnBlock(override val statements: List<VeraStatement>) : VeraBlock

data class VeraIfExpression(
    val condition: VeraPrimaryExpression,
    val thenBlock: VeraBlock,
    val elseBlock: VeraBlock?,
    override val span: SourceSpan,
) : VeraPrimaryExpression

data class VeraParameter(val name: Identifier, val type: VeraType, val span: SourceSpan)

sealed interface VeraType {
    data object INT : VeraType
    data object STRING : VeraType
    data object BOOL : VeraType
    data object UNIT : VeraType
    data object UNKNOWN : VeraType
    data class ClassType(val name: Identifier) : VeraType
}

/** Renders the type as Vera source spells it, for user-facing messages. */
fun VeraType.render(): String = when (this) {
    VeraType.INT -> "Int"
    VeraType.STRING -> "String"
    VeraType.BOOL -> "Bool"
    VeraType.UNIT -> "Unit"
    VeraType.UNKNOWN -> "an inferred type"
    is VeraType.ClassType -> name.value
}
