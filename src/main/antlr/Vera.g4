grammar Vera;

/* Program */
program: (layout declaration)* layout EOF;
declaration: functionDeclaration;

/* Declarations */
functionDeclaration: FN IDENTIFIER parameterClause returnType? block;
parameterClause: LPAREN parameters? RPAREN;
parameters: parameter (COMMA parameter)*;
parameter: IDENTIFIER COLON typeRef;
returnType: COLON typeRef;

/* Types */
typeRef: INT_TYPE | STRING_TYPE;

/* Blocks & Statements */
block: LBRACE layout statement* RBRACE;
statement: (bindStatement | rebindStatement | returnStatement | expression) statementTerminator;

/* Binding */
bindStatement: (VAR | VAL) IDENTIFIER (COLON typeRef)? BIND expression;
rebindStatement: IDENTIFIER REBIND rebindRhs;
rebindRhs: expression | updateBlock;
updateBlock: LBRACE layout RBRACE;

/* Return */
returnStatement: RETURN expression?;

/* Expressions */
expression: chainedExpression (IDENTIFIER chainedExpression)*;
chainedExpression: primaryExpression (memberAccess | argumentList)*;
primaryExpression: literal | IDENTIFIER | LPAREN expression RPAREN;
memberAccess: DOT IDENTIFIER;
argumentList: LPAREN arguments? RPAREN;
arguments: expression (COMMA expression)*;

/* Literals */
literal: INT;

/* Layout */
statementTerminator: EOL+;
layout: EOL*;

/* Lexer */
FN: 'fn';
VAR: 'var';
VAL: 'val';
INT_TYPE: 'Int';
STRING_TYPE: 'String';
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

INT: [0-9]+;
IDENTIFIER: [a-zA-Z_][a-zA-Z0-9_]*;

EOL: '\r\n' | '\n' | '\r';
WHITESPACE: [ \t]+ -> skip;
