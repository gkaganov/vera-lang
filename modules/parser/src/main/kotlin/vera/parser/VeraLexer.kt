package vera.parser

import vera.shared.model.SourceLocation

object VeraLexer {
    fun tokenize(source: String): List<VeraToken> = VeraLexerState(source).tokenize()
}

/** Mutable state for a single lexing run. */
private class VeraLexerState(private val source: String) {
    private var index = 0
    private var line = 1
    private var column = 1

    private val tokens = mutableListOf<VeraToken>()

    fun tokenize(): List<VeraToken> {
        while (index < source.length) {
            when (val char = source[index]) {
                ' ', '\t' -> advance()
                '\n' -> lexLineFeed()
                '\r' -> lexCarriageReturn()
                '"' -> lexString()
                else -> when {
                    char in '0'..'9' -> lexInt()
                    char.isIdentifierStart() -> lexIdentifierOrKeyword()
                    else -> lexSymbol(char)
                }
            }
        }
        tokens += VeraToken(VeraTokenType.EOF, "", line, column)
        return tokens
    }

    private fun lexLineFeed() {
        val startLine = line
        val startColumn = column
        advance()
        tokens += VeraToken(VeraTokenType.EOL, "\n", startLine, startColumn)
    }

    private fun lexCarriageReturn() {
        val startLine = line
        val startColumn = column
        index++
        column = 1
        if (index < source.length && source[index] == '\n') {
            index++
        }
        line++
        tokens += VeraToken(VeraTokenType.EOL, "\n", startLine, startColumn)
    }

    private fun lexString() {
        val startLine = line
        val startColumn = column
        val startLocation = SourceLocation(startLine, startColumn)
        advance() // opening quote

        val value = StringBuilder()
        while (index < source.length && source[index] != '"') {
            val char = source[index]
            when {
                char == '\n' || char == '\r' -> throw VeraLexException.UnterminatedString(startLocation)

                char == '\\' -> {
                    advance()
                    if (index >= source.length || source[index] == '\n' || source[index] == '\r') {
                        throw VeraLexException.UnterminatedString(startLocation)
                    }
                    value.append(lexStringEscape())
                }

                else -> {
                    value.append(char)
                    advance()
                }
            }
        }
        if (index >= source.length) {
            throw VeraLexException.UnterminatedString(startLocation)
        }
        advance() // closing quote
        tokens += VeraToken(VeraTokenType.STRING_LITERAL, value.toString(), startLine, startColumn)
    }

    private fun lexStringEscape(): Char {
        val escapeLocation = SourceLocation(line, column)
        val decoded = when (source[index]) {
            'n' -> '\n'
            't' -> '\t'
            'r' -> '\r'
            '"' -> '"'
            '\\' -> '\\'
            else -> throw VeraLexException.InvalidStringEscape(source[index], escapeLocation)
        }
        advance()
        return decoded
    }

    private fun lexInt() {
        val startLine = line
        val startColumn = column
        val start = index
        while (index < source.length && source[index] in '0'..'9') {
            advance()
        }
        tokens += VeraToken(VeraTokenType.INT_LITERAL, source.substring(start, index), startLine, startColumn)
    }

    private fun lexIdentifierOrKeyword() {
        val startLine = line
        val startColumn = column
        val start = index
        while (index < source.length && source[index].isIdentifierPart()) {
            advance()
        }
        val text = source.substring(start, index)
        val type = keywords[text] ?: VeraTokenType.IDENTIFIER
        tokens += VeraToken(type, text, startLine, startColumn)
    }

    private fun lexSymbol(char: Char) {
        val startLine = line
        val startColumn = column
        val next = source.getOrNull(index + 1)

        val (type, length) = when (char) {
            '=' if next == '=' -> VeraTokenType.EQ to 2
            '!' if next == '=' -> VeraTokenType.NEQ to 2
            '<' if next == '=' -> VeraTokenType.LTE to 2
            '>' if next == '=' -> VeraTokenType.GTE to 2
            '=' -> VeraTokenType.BIND to 1
            ':' -> VeraTokenType.COLON to 1
            ',' -> VeraTokenType.COMMA to 1
            '.' -> VeraTokenType.DOT to 1
            '(' -> VeraTokenType.LPAREN to 1
            ')' -> VeraTokenType.RPAREN to 1
            '{' -> VeraTokenType.LBRACE to 1
            '}' -> VeraTokenType.RBRACE to 1
            '+' -> VeraTokenType.PLUS to 1
            '-' -> VeraTokenType.MINUS to 1
            '*' -> VeraTokenType.STAR to 1
            '/' -> VeraTokenType.SLASH to 1
            '%' -> VeraTokenType.PERCENT to 1
            '<' -> VeraTokenType.LT to 1
            '>' -> VeraTokenType.GT to 1
            '|' if next == '>' -> VeraTokenType.FORWARD_APPLICATION to 2
            else -> throw VeraLexException.UnexpectedCharacter(char, SourceLocation(startLine, startColumn))
        }

        repeat(length) { advance() }
        tokens += VeraToken(type, source.substring(index - length, index), startLine, startColumn)
    }

    private fun advance() {
        if (source[index] == '\n') {
            line++
            column = 1
        } else {
            column++
        }
        index++
    }

    private fun Char.isIdentifierStart(): Boolean = this == '_' || this in 'a'..'z' || this in 'A'..'Z'

    private fun Char.isIdentifierPart(): Boolean = isIdentifierStart() || this in '0'..'9'

    private companion object {
        val keywords: Map<String, VeraTokenType> = mapOf(
            "fn" to VeraTokenType.FN,
            "record" to VeraTokenType.RECORD,
            "if" to VeraTokenType.IF,
            "else" to VeraTokenType.ELSE,
            "while" to VeraTokenType.WHILE,
            "break" to VeraTokenType.BREAK,
            "continue" to VeraTokenType.CONTINUE,
            "var" to VeraTokenType.VAR,
            "val" to VeraTokenType.VAL,
            "return" to VeraTokenType.RETURN,
            "and" to VeraTokenType.AND,
            "or" to VeraTokenType.OR,
            "Int" to VeraTokenType.INT_TYPE,
            "String" to VeraTokenType.STRING_TYPE,
            "Bool" to VeraTokenType.BOOL_TYPE,
            "Unit" to VeraTokenType.UNIT_TYPE,
            "True" to VeraTokenType.BOOL_LITERAL,
            "False" to VeraTokenType.BOOL_LITERAL,
        )
    }
}
