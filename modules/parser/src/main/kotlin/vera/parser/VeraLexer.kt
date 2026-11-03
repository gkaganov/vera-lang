package vera.parser

object VeraLexer {
    fun tokenize(source: String): List<Token> = LexerState(source).tokenize()
}

/** Mutable state for a single lexing run. */
private class LexerState(private val source: String) {
    private var index = 0
    private var line = 1
    private var column = 1

    private val tokens = mutableListOf<Token>()

    fun tokenize(): List<Token> {
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
        tokens += Token(TokenType.EOF, "", line, column)
        return tokens
    }

    private fun lexLineFeed() {
        val startLine = line
        val startColumn = column
        advance()
        tokens += Token(TokenType.EOL, "\n", startLine, startColumn)
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
        tokens += Token(TokenType.EOL, "\n", startLine, startColumn)
    }

    private fun lexString() {
        val startLine = line
        val startColumn = column
        val start = index
        advance() // opening quote
        while (index < source.length && source[index] != '"') {
            val char = source[index]
            if (char == '\n' || char == '\r' || !char.isStringChar()) {
                error("unexpected character '$char' in string literal", startLine, startColumn)
            }
            advance()
        }
        if (index >= source.length) {
            error("unterminated string literal", startLine, startColumn)
        }
        advance() // closing quote
        tokens += Token(TokenType.STRING_LITERAL, source.substring(start, index), startLine, startColumn)
    }

    private fun lexInt() {
        val startLine = line
        val startColumn = column
        val start = index
        while (index < source.length && source[index] in '0'..'9') {
            advance()
        }
        tokens += Token(TokenType.INT_LITERAL, source.substring(start, index), startLine, startColumn)
    }

    private fun lexIdentifierOrKeyword() {
        val startLine = line
        val startColumn = column
        val start = index
        while (index < source.length && source[index].isIdentifierPart()) {
            advance()
        }
        val text = source.substring(start, index)
        val type = keywords[text] ?: TokenType.IDENTIFIER
        tokens += Token(type, text, startLine, startColumn)
    }

    private fun lexSymbol(char: Char) {
        val startLine = line
        val startColumn = column
        val next = source.getOrNull(index + 1)

        val (type, length) = when (char) {
            ':' if next == '=' -> TokenType.REBIND to 2
            '=' if next == '=' -> TokenType.EQ to 2
            '!' if next == '=' -> TokenType.NEQ to 2
            '<' if next == '=' -> TokenType.LTE to 2
            '>' if next == '=' -> TokenType.GTE to 2
            '=' -> TokenType.BIND to 1
            ':' -> TokenType.COLON to 1
            ',' -> TokenType.COMMA to 1
            '.' -> TokenType.DOT to 1
            '(' -> TokenType.LPAREN to 1
            ')' -> TokenType.RPAREN to 1
            '{' -> TokenType.LBRACE to 1
            '}' -> TokenType.RBRACE to 1
            '+' -> TokenType.PLUS to 1
            '-' -> TokenType.MINUS to 1
            '*' -> TokenType.STAR to 1
            '/' -> TokenType.SLASH to 1
            '%' -> TokenType.PERCENT to 1
            '<' -> TokenType.LT to 1
            '>' -> TokenType.GT to 1
            '|' -> TokenType.PIPE to 1
            else -> error("unexpected character '$char'", startLine, startColumn)
        }

        repeat(length) { advance() }
        tokens += Token(type, source.substring(index - length, index), startLine, startColumn)
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

    private fun error(message: String, errorLine: Int, errorColumn: Int): Nothing {
        throw IllegalStateException("$message at line $errorLine, column $errorColumn")
    }

    private fun Char.isIdentifierStart(): Boolean = this == '_' || this in 'a'..'z' || this in 'A'..'Z'

    private fun Char.isIdentifierPart(): Boolean = isIdentifierStart() || this in '0'..'9'

    private fun Char.isStringChar(): Boolean = this == ' ' || this == '!' || this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private companion object {
        val keywords: Map<String, TokenType> = mapOf(
            "fn" to TokenType.FN,
            "if" to TokenType.IF,
            "else" to TokenType.ELSE,
            "while" to TokenType.WHILE,
            "var" to TokenType.VAR,
            "val" to TokenType.VAL,
            "return" to TokenType.RETURN,
            "and" to TokenType.AND,
            "or" to TokenType.OR,
            "Int" to TokenType.INT_TYPE,
            "String" to TokenType.STRING_TYPE,
            "Bool" to TokenType.BOOL_TYPE,
            "Unit" to TokenType.UNIT_TYPE,
            "True" to TokenType.BOOL_LITERAL,
            "False" to TokenType.BOOL_LITERAL,
        )
    }
}
