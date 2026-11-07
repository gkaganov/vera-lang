package vera.parser

import vera.shared.model.SourceLocation
import vera.shared.model.VeraException

sealed class VeraLexException(message: String) : VeraException(message) {
    abstract val location: SourceLocation

    class UnexpectedCharacter(
        val character: Char,
        override val location: SourceLocation,
    ) : VeraLexException("unexpected character '$character' ${location.render()}")

    class UnexpectedStringCharacter(
        val character: Char,
        override val location: SourceLocation,
    ) : VeraLexException("unexpected character '$character' in string literal ${location.render()}")

    class UnterminatedString(
        override val location: SourceLocation,
    ) : VeraLexException("unterminated string literal ${location.render()}")
}

sealed class VeraParseException(message: String) : VeraException(message) {
    abstract val location: SourceLocation

    class ExpectedFunctionDeclaration(
        val found: Token,
        override val location: SourceLocation,
    ) : VeraParseException("expected a function declaration ${location.render()} (found '${found.text}')")

    class ExpectedToken(
        val expected: String,
        val found: Token,
        override val location: SourceLocation,
    ) : VeraParseException("expected $expected ${location.render()} (found '${found.text}')")

    class ExpectedType(
        val found: Token,
        override val location: SourceLocation,
    ) : VeraParseException("expected a type ${location.render()} (found '${found.text}')")

    class ExpectedExpression(
        val found: Token,
        override val location: SourceLocation,
    ) : VeraParseException("expected an expression ${location.render()} (found '${found.text}')")

    class ExpectedEndOfLine(
        val found: Token,
        override val location: SourceLocation,
    ) : VeraParseException("expected end of line ${location.render()} (found '${found.text}')")

    class InvalidIntLiteral(
        val token: Token,
        override val location: SourceLocation,
    ) : VeraParseException("invalid Int literal '${token.text}' ${location.render()}")

    class UnsupportedRebindUpdateBlock(
        override val location: SourceLocation,
    ) : VeraParseException("rebind update blocks are not supported ${location.render()}")
}

internal fun Token.location(): SourceLocation = SourceLocation(line, column)

internal fun SourceLocation.render(): String = "at line $line, column $column"
