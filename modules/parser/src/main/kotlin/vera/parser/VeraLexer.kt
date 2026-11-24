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

    /** Open `(` and `{` brackets, innermost last. Newlines inside `(` do not end a statement. */
    private val openBrackets = mutableListOf<VeraTokenType>()

    /** A string template placeholder being lexed: where its string began and the bracket depth inside it. */
    private data class TemplatePlaceholder(val stringStart: SourceLocation, val bracketDepth: Int)

    /** Open template placeholders, innermost last; a `}` at their bracket depth closes one. */
    private val templatePlaceholders = mutableListOf<TemplatePlaceholder>()

    fun tokenize(): List<VeraToken> {
        while (index < source.length) {
            when (val char = source[index]) {
                ' ', '\t' -> advance()
                '\n' -> lexLineFeed()
                '\r' -> lexCarriageReturn()
                '}' if templatePlaceholders.lastOrNull()?.bracketDepth == openBrackets.size ->
                    lexPlaceholderEnd(templatePlaceholders.last())
                '"' -> lexString()
                '/' if source.startsWith("//", index) -> skipComment()
                else -> when {
                    char in '0'..'9' -> lexInt()
                    char.isIdentifierStart() -> lexIdentifierOrKeyword()
                    else -> lexSymbol(char)
                }
            }
        }
        requireNoOpenPlaceholder()
        tokens += VeraToken(VeraTokenType.EOF, "", line, column, column)
        return tokens
    }

    private fun lexLineFeed() {
        requireNoOpenPlaceholder()
        val startLine = line
        val startColumn = column
        advance()
        lexLineEnd(startLine, startColumn)
    }

    private fun lexCarriageReturn() {
        requireNoOpenPlaceholder()
        val startLine = line
        val startColumn = column
        index++
        column = 1
        if (index < source.length && source[index] == '\n') {
            index++
        }
        line++
        lexLineEnd(startLine, startColumn)
    }

    /**
     * Emits an `EOL` token unless the statement visibly continues on the next line: inside open
     * parentheses, after a trailing operator, `=`, `,` or `.`, or before a line starting with `|>` or `.`.
     */
    private fun lexLineEnd(startLine: Int, startColumn: Int) {
        val insideParentheses = openBrackets.lastOrNull() == VeraTokenType.LPAREN
        val endsWithContinuation = tokens.lastOrNull()?.type in continuationEnders
        if (insideParentheses || endsWithContinuation || nextLineStartsWithContinuation()) return
        tokens += VeraToken(VeraTokenType.EOL, "\n", startLine, startColumn, startColumn + 1)
    }

    /** Looks past blank and comment-only lines for a line starting with `|>` or `.`. */
    private fun nextLineStartsWithContinuation(): Boolean {
        var lookahead = index
        while (lookahead < source.length) {
            lookahead = when {
                source[lookahead] in " \t\r\n" -> lookahead + 1
                source.startsWith("//", lookahead) -> commentEnd(lookahead)
                else -> break
            }
        }
        return source.startsWith("|>", lookahead) || source.startsWith(".", lookahead)
    }

    /**
     * Skips a `//` comment or `///` doc comment up to, but not including, the line break, so the
     * newline still ends the statement and comments never affect line continuation.
     */
    private fun skipComment() {
        val end = commentEnd(index)
        while (index < end) {
            advance()
        }
    }

    private fun commentEnd(start: Int): Int {
        var end = start
        while (end < source.length && source[end] != '\n' && source[end] != '\r') {
            end++
        }
        return end
    }

    private fun lexString() {
        val startLocation = SourceLocation(line, column)
        advance() // opening quote
        lexStringText(startLocation, startLocation, continuesTemplate = false)
    }

    /**
     * Lexes string text up to the closing quote or the next `\{`. A string without placeholders becomes
     * one `STRING_LITERAL`. A template becomes `TEMPLATE_START`, the placeholder's tokens, a
     * `TEMPLATE_MIDDLE` before each further placeholder, and `TEMPLATE_END`.
     */
    private fun lexStringText(stringStart: SourceLocation, tokenStart: SourceLocation, continuesTemplate: Boolean) {
        val value = StringBuilder()
        while (index < source.length && source[index] != '"') {
            val char = source[index]
            when {
                char == '\n' || char == '\r' -> throw VeraLexException.UnterminatedString(stringStart)

                char == '\\' && source.getOrNull(index + 1) == '{' -> {
                    advance() // backslash
                    advance() // opening brace
                    val type = if (continuesTemplate) VeraTokenType.TEMPLATE_MIDDLE else VeraTokenType.TEMPLATE_START
                    tokens += VeraToken(type, value.toString(), tokenStart.line, tokenStart.column, column)
                    templatePlaceholders += TemplatePlaceholder(stringStart, openBrackets.size)
                    return
                }

                char == '\\' -> {
                    advance()
                    if (index >= source.length || source[index] == '\n' || source[index] == '\r') {
                        throw VeraLexException.UnterminatedString(stringStart)
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
            throw VeraLexException.UnterminatedString(stringStart)
        }
        advance() // closing quote
        val type = if (continuesTemplate) VeraTokenType.TEMPLATE_END else VeraTokenType.STRING_LITERAL
        tokens += VeraToken(type, value.toString(), tokenStart.line, tokenStart.column, column)
    }

    /** Ends the innermost placeholder at its closing `}` and resumes the template's text. */
    private fun lexPlaceholderEnd(placeholder: TemplatePlaceholder) {
        val tokenStart = SourceLocation(line, column)
        templatePlaceholders.removeLast()
        advance() // closing brace
        lexStringText(placeholder.stringStart, tokenStart, continuesTemplate = true)
    }

    /** Placeholders must fit on one line, like the string that contains them. */
    private fun requireNoOpenPlaceholder() {
        val placeholder = templatePlaceholders.lastOrNull() ?: return
        throw VeraLexException.UnterminatedString(placeholder.stringStart)
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
        tokens += VeraToken(VeraTokenType.INT_LITERAL, source.substring(start, index), startLine, startColumn, column)
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
        tokens += VeraToken(type, text, startLine, startColumn, column)
    }

    private fun lexSymbol(char: Char) {
        val startLine = line
        val startColumn = column
        val next = source.getOrNull(index + 1)

        val (type, length) = when (char) {
            '!' if next != '=' -> throw javaOperator("!", "write `not` instead, for example `not done`")
            '&' if next == '&' -> throw javaOperator("&&", "write `and` instead")
            '|' if next == '|' -> throw javaOperator("||", "write `or` instead")
            '+' if next == '+' -> throw javaOperator("++", "write `x = x + 1` instead")
            '+' if next == '=' -> throw compoundAssignment(char)
            '-' if next == '=' -> throw compoundAssignment(char)
            '-' if next == '>' -> VeraTokenType.ARROW to 2
            '*' if next == '=' -> throw compoundAssignment(char)
            '/' if next == '=' -> throw compoundAssignment(char)
            '%' if next == '=' -> throw compoundAssignment(char)
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
            ';' -> throw VeraLexException.UnexpectedSemicolon(SourceLocation(startLine, startColumn))
            else -> throw VeraLexException.UnexpectedCharacter(char, SourceLocation(startLine, startColumn))
        }

        repeat(length) { advance() }
        trackBracket(type)
        tokens += VeraToken(type, source.substring(index - length, index), startLine, startColumn, column)
    }

    private fun javaOperator(operator: String, hint: String): VeraLexException.JavaOperator =
        VeraLexException.JavaOperator(operator, hint, SourceLocation(line, column))

    private fun compoundAssignment(operator: Char): VeraLexException.JavaOperator =
        javaOperator("$operator=", "write `x = x $operator value` instead")

    private fun trackBracket(type: VeraTokenType) {
        when (type) {
            VeraTokenType.LPAREN, VeraTokenType.LBRACE -> openBrackets += type
            VeraTokenType.RPAREN, VeraTokenType.RBRACE -> openBrackets.removeLastOrNull()
            else -> {}
        }
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
        /** Tokens that cannot end a statement, so a line ending with one continues on the next line. */
        val continuationEnders: Set<VeraTokenType> = setOf(
            VeraTokenType.PLUS, VeraTokenType.MINUS, VeraTokenType.STAR, VeraTokenType.SLASH,
            VeraTokenType.PERCENT, VeraTokenType.EQ, VeraTokenType.NEQ, VeraTokenType.LT,
            VeraTokenType.GT, VeraTokenType.LTE, VeraTokenType.GTE, VeraTokenType.AND,
            VeraTokenType.OR, VeraTokenType.NOT, VeraTokenType.FORWARD_APPLICATION,
            VeraTokenType.BIND, VeraTokenType.COMMA, VeraTokenType.DOT, VeraTokenType.ARROW,
        )

        val keywords: Map<String, VeraTokenType> = mapOf(
            "fn" to VeraTokenType.FN,
            "class" to VeraTokenType.CLASS,
            "trait" to VeraTokenType.TRAIT,
            "match" to VeraTokenType.MATCH,
            "has" to VeraTokenType.HAS,
            "if" to VeraTokenType.IF,
            "else" to VeraTokenType.ELSE,
            "while" to VeraTokenType.WHILE,
            "break" to VeraTokenType.BREAK,
            "continue" to VeraTokenType.CONTINUE,
            "mut" to VeraTokenType.MUT,
            "val" to VeraTokenType.VAL,
            "return" to VeraTokenType.RETURN,
            "and" to VeraTokenType.AND,
            "or" to VeraTokenType.OR,
            "not" to VeraTokenType.NOT,
            "Int" to VeraTokenType.INT_TYPE,
            "String" to VeraTokenType.STRING_TYPE,
            "Bool" to VeraTokenType.BOOL_TYPE,
            "Unit" to VeraTokenType.UNIT_TYPE,
            "True" to VeraTokenType.BOOL_LITERAL,
            "False" to VeraTokenType.BOOL_LITERAL,
        )
    }
}
