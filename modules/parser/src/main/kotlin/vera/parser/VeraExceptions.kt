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
