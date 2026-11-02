package vera.parser

enum class TokenType {
    // Keywords
    FN,
    IF,
    ELSE,
    VAR,
    VAL,
    RETURN,
    AND,
    OR,

    // Types
    INT_TYPE,
    STRING_TYPE,
    BOOL_TYPE,
    UNIT_TYPE,

    // Literals & identifiers
    IDENTIFIER,
    INT_LITERAL,
    STRING_LITERAL,
    BOOL_LITERAL,

    // Punctuation & operators
    BIND,
    REBIND,
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
    PIPE,

    // Layout
    EOL,
    EOF,
}

data class Token(
    val type: TokenType,
    val text: String,
    val line: Int,
    val column: Int,
)
