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
    ) : VeraLexException("unexpected character `$character`", location)

    class InvalidStringEscape(
        val escape: Char,
        location: SourceLocation,
    ) : VeraLexException(
        "invalid string escape `\\$escape`; the escapes are `\\\"`, `\\\\`, `\\n`, `\\t`, `\\r`, `\\uXXXX`, and `\\{` " +
            "to start a placeholder",
        location,
    )

    class InvalidUnicodeEscape(
        location: SourceLocation,
    ) : VeraLexException("`\\u` needs exactly four hex digits, as in `\\u00E9`", location)

    /** The start of a Java or Kotlin block comment; Vera has only line comments. */
    class BlockComment(
        location: SourceLocation,
    ) : VeraLexException("Vera has no block comments; start each line with `//`", location)

    /** A Java or Kotlin `'c'` character literal. */
    class CharLiteral(
        location: SourceLocation,
    ) : VeraLexException("Vera strings use double quotes, and there is no `Char` type: write `\"c\"`", location)

    /** A Java or Kotlin triple-quoted text block. */
    class TextBlock(
        location: SourceLocation,
    ) : VeraLexException("Vera has no multi-line strings; write `\\n` for a line break", location)

    /** A number other than a decimal `Int`, such as `0x10`, `1_000`, `10L`, or `1.5`. */
    class UnsupportedNumberLiteral(
        location: SourceLocation,
    ) : VeraLexException("Vera supports only decimal `Int` literals such as `1000`", location)

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
    ) : VeraParseException(
        "expected a function, class, trait, or interface declaration, found ${found.describe()}",
        found,
    )

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

    /** `fn` inside a function body. */
    class NestedFunction(
        val token: VeraToken,
    ) : VeraParseException("functions can only be declared at top level", token)

    /** `\\{}` with nothing inside. */
    class EmptyTemplatePlaceholder(
        val token: VeraToken,
    ) : VeraParseException("empty template placeholder; write an expression inside `\\{...}`", token)

    class InvalidIntLiteral(
        val token: VeraToken,
        val negative: Boolean = false,
    ) : VeraParseException(
        "Int literal ${if (negative) "-" else ""}${token.text} is out of range; `Int` is 32-bit " +
            "(-2147483648 to 2147483647)",
        token,
    )

    /** `: None` as a return type; a function returning `None` leaves the return type out. */
    class ExplicitNoneReturnType(
        val found: VeraToken,
    ) : VeraParseException("leave out the return type for a function that returns `None`; remove `: None`", found)

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
    else -> "`$text`"
}
