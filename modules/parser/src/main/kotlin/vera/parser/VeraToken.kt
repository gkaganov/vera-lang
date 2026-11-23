package vera.parser

enum class VeraTokenType {
    // Keywords
    FN,
    CLASS,
    TRAIT,
    HAS,
    IF,
    ELSE,
    WHILE,
    BREAK,
    CONTINUE,
    MUT,
    VAL,
    RETURN,
    AND,
    OR,
    NOT,

    // Types
    INT_TYPE,
    STRING_TYPE,
    BOOL_TYPE,
    UNIT_TYPE,

    // Literals & identifiers
    IDENTIFIER,
    INT_LITERAL,
    STRING_LITERAL,
    // A string template `"a \{x} b \{y} c"` lexes as TEMPLATE_START("a "), tokens of x,
    // TEMPLATE_MIDDLE(" b "), tokens of y, TEMPLATE_END(" c").
    TEMPLATE_START,
    TEMPLATE_MIDDLE,
    TEMPLATE_END,
    BOOL_LITERAL,

    // Punctuation & operators
    BIND,
    COLON,
    COMMA,
    DOT,
    LPAREN,
    RPAREN,
    LBRACE,
    RBRACE,
    PLUS,
    MINUS,
    STAR,
    SLASH,
    PERCENT,
    EQ,
    NEQ,
    LT,
    GT,
    LTE,
    GTE,
    FORWARD_APPLICATION,

    // Layout
    EOL,
    EOF,
}

/** A token from [line]:[column] up to, but not including, [endColumn] on the same line. */
data class VeraToken(
    val type: VeraTokenType,
    val text: String,
    val line: Int,
    val column: Int,
    val endColumn: Int,
)
