package vera.parser

import vera.shared.model.SourceLocation
import vera.shared.model.SourceSpan
import vera.shared.model.VeraException

/** A lexing error at a single source position; the position is attached as a one-character span. */
sealed class VeraLexException(message: String, val location: SourceLocation) : VeraException(message) {
    init {
        attachSpan(SourceSpan(location, SourceLocation(location.line, location.column + 1)))
    }

    class UnexpectedCharacter(
        val character: Char,
        location: SourceLocation,
    ) : VeraLexException("unexpected character '$character'", location)

    class InvalidStringEscape(
        val escape: Char,
        location: SourceLocation,
    ) : VeraLexException("invalid string escape '\\$escape'", location)

    class UnexpectedSemicolon(
        location: SourceLocation,
    ) : VeraLexException("Vera does not use semicolons; end the statement with a newline", location)

    /** A Java operator Vera spells differently, such as `!`, `&&`, `++`, or `+=`. */
    class JavaOperator(
        val operator: String,
        val hint: String,
        location: SourceLocation,
    ) : VeraLexException("Vera does not use `$operator`; $hint", location)

    class UnterminatedString(
        location: SourceLocation,
    ) : VeraLexException("unterminated string literal", location)
}

/** A parsing error, located at the offending token. */
sealed class VeraParseException(message: String, val location: SourceLocation, span: SourceSpan) :
    VeraException(message) {

    init {
        attachSpan(span)
    }

    constructor(message: String, token: VeraToken) : this(message, token.location(), token.span())

    class ExpectedDeclaration(
        val found: VeraToken,
    ) : VeraParseException("expected a function, class, or trait declaration, found ${found.describe()}", found)

    class ExpectedToken(
        val expected: String,
        val found: VeraToken,
    ) : VeraParseException("expected $expected, found ${found.describe()}", found)

    class ExpectedType(
        val found: VeraToken,
    ) : VeraParseException("expected a type, found ${found.describe()}", found)

    class ExpectedExpression(
        val found: VeraToken,
    ) : VeraParseException("expected an expression, found ${found.describe()}", found)

    class ExpectedEndOfLine(
        val found: VeraToken,
    ) : VeraParseException("expected end of line, found ${found.describe()}", found)

    class InvalidIntLiteral(
        val token: VeraToken,
    ) : VeraParseException("invalid Int literal '${token.text}'", token)

    /** `: Unit` as a return type; a function returning `Unit` leaves the return type out. */
    class ExplicitUnitReturnType(
        val found: VeraToken,
    ) : VeraParseException("leave out the return type for a function that returns `Unit`; remove `: Unit`", found)

    /** `ClassName()` in a pattern; a pattern that binds no fields is written without parentheses. */
    class EmptyPatternParentheses(
        val found: VeraToken,
        val className: String,
    ) : VeraParseException("a pattern that binds no fields has no parentheses: write `$className`", found)

    /** A trait case with `has` but no body; the body ends the `has` list, since commas also separate cases. */
    class CaseTraitsWithoutBody(
        val found: VeraToken,
        val caseName: String,
        val keyword: String = "has",
    ) : VeraParseException(
        "a case with `$keyword` needs a body to end its list: write `$caseName $keyword ... { }`, found ${found.describe()}",
        found,
    )

    /** A case inside an interface; interfaces are open, so only traits list their cases. */
    class InterfaceCase(
        val found: VeraToken,
    ) : VeraParseException(
        "interfaces are open and cannot list cases; declare a `trait` for a closed set of cases",
        found,
    )

    /** A class field written without `val`. */
    class FieldWithoutVal(
        val found: VeraToken,
    ) : VeraParseException("declare fields with `val`: write `val ${found.text}: ...`", found)

    /** `mut val` in a class body; mutable fields arrive together with `mut fn` methods. */
    class MutableFieldNotSupported(
        val found: VeraToken,
    ) : VeraParseException("mutable fields need `mut fn` methods, which are not implemented yet; use `val`", found)

    /** Java syntax with a different spelling, or no counterpart, in Vera. [hint] says what to write. */
    class JavaSyntax(
        val found: VeraToken,
        val hint: String,
    ) : VeraParseException(hint, found)

    class UnsupportedRebindUpdateBlock(
        val token: VeraToken,
    ) : VeraParseException("rebind update blocks are not supported", token)
}

internal fun VeraToken.location(): SourceLocation = SourceLocation(line, column)

internal fun VeraToken.span(): SourceSpan = SourceSpan(location(), SourceLocation(line, endColumn))

/** Describes a token for an error message, naming layout tokens instead of printing a raw newline. */
private fun VeraToken.describe(): String = when (type) {
    VeraTokenType.EOL -> "end of line"
    VeraTokenType.EOF -> "end of input"
    else -> "'$text'"
}
