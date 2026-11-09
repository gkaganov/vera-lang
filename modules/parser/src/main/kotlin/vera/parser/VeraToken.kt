package vera.parser

enum class VeraTokenType {
    // Keywords
    FN,
    RECORD,
    IF,
    ELSE,
    WHILE,
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

data class VeraToken(
    val type: VeraTokenType,
    val text: String,
    val line: Int,
    val column: Int,
)
