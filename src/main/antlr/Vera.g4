grammar Vera;

/* Program */
program: (layout declaration)* layout EOF;
declaration: functionDeclaration;

/* Declarations */
functionDeclaration: FN_KEYWORD name=IDENTIFIER parameterClause returnType? block;
parameterClause: LPAREN parameters? RPAREN;
parameters: parameter (COMMA parameter)*;
parameter: name=IDENTIFIER COLON typeRef;
returnType: COLON typeRef;

/* Types */
typeRef: INT_TYPE | STRING_TYPE | BOOL_TYPE;

/* Blocks & Statements */
block: LBRACE layout statement* RBRACE;
statement: (bindStatement | rebindStatement | returnStatement | expression) statementTerminator;

/* Binding */
bindStatement: (VAR | VAL) name=IDENTIFIER (COLON typeRef)? BIND expression;
rebindStatement: name=IDENTIFIER REBIND rebindRhs;
rebindRhs: expression | updateBlock;
updateBlock: LBRACE layout RBRACE;

/* Return */
returnStatement: RETURN expression?;

/* Expressions */
expression: chainedExpression (infixOperator chainedExpression)*;
chainedExpression: primaryExpression (memberAccess | argumentList)*;
primaryExpression: literal | IDENTIFIER | LPAREN expression RPAREN;
memberAccess: DOT name=IDENTIFIER;
argumentList: LPAREN arguments? RPAREN;
arguments: expression (COMMA expression)*;

/* Literals */
literal: INT_LITERAL | STRING_LITERAL | BOOL_LITERAL;

/* Layout */
statementTerminator: EOL* EOF | EOL+;
layout: EOL*;

/* Operators */
infixOperator: PLUS | MINUS | MUL | DIV;

/* Lexer */
FN_KEYWORD: 'fn';
VAR: 'var';
VAL: 'val';
INT_TYPE: 'Int';
STRING_TYPE: 'String';
BOOL_TYPE: 'Bool';
RETURN: 'return';

BIND: '=';
REBIND: ':=';

COLON: ':';
COMMA: ',';
DOT: '.';
LPAREN: '(';
RPAREN: ')';
LBRACE: '{';
RBRACE: '}';
PLUS: '+';
MINUS: '-';
MUL: '*';
DIV: '/';

INT_LITERAL: [0-9]+;
STRING_LITERAL: '"'[ a-zA-Z0-9!]*'"';
BOOL_LITERAL: 'True' | 'False';
IDENTIFIER: [a-zA-Z_][a-zA-Z0-9_]*;

EOL: '\r\n' | '\n' | '\r';
WHITESPACE: [ \t]+ -> skip;
