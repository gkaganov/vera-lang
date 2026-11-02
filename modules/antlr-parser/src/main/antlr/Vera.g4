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
typeRef: INT_TYPE | STRING_TYPE | BOOL_TYPE | UNIT_TYPE;

/* Blocks & Statements */
block: LBRACE layout statement* RBRACE;
statement: (bindStatement | rebindStatement | returnStatement | expression) statementTerminator;

/* Binding */
bindStatement: (VAR_KEYWORD | VAL_KEYWORD) name=IDENTIFIER (COLON typeRef)? BIND expression;
rebindStatement: name=IDENTIFIER REBIND rebindRhs;
rebindRhs: expression | updateBlock;
updateBlock: LBRACE layout RBRACE;

/* Return */
returnStatement: RETURN_KEYWORD expression?;

/* Expressions — layered by precedence (lowest to highest) */
expression: forwardApplication;

forwardApplication: logicalOr (PIPE logicalOr)*;

logicalOr: logicalAnd (OR logicalAnd)*;
logicalAnd: equality (AND equality)*;

equality: comparison (EQ comparison | NEQ comparison)*;
comparison: additive (LT additive | GT additive | LTE additive | GTE additive)*;

additive: multiplicative (PLUS multiplicative | MINUS multiplicative)*;
multiplicative: chainedPrimary (STAR chainedPrimary | SLASH chainedPrimary | PERCENT chainedPrimary)*;

chainedPrimary: primary (memberAccess | argumentList)*;

primary: literal
       | IDENTIFIER
       | LPAREN expression RPAREN
       | ifExpression;

ifExpression: IF_KEYWORD condition=expression thenBranch=block
              (ELSE_KEYWORD elseBranch=block)?;
memberAccess: DOT name=IDENTIFIER;
argumentList: LPAREN arguments? RPAREN;
arguments: expression (COMMA expression)*;

/* Literals */
literal: INT_LITERAL | STRING_LITERAL | BOOL_LITERAL;

/* Layout */
statementTerminator: EOL* EOF | EOL+;
layout: EOL*;

/* Lexer */
FN_KEYWORD: 'fn';
IF_KEYWORD: 'if';
ELSE_KEYWORD: 'else';
VAR_KEYWORD: 'var';
VAL_KEYWORD: 'val';
RETURN_KEYWORD: 'return';

INT_TYPE: 'Int';
STRING_TYPE: 'String';
BOOL_TYPE: 'Bool';
UNIT_TYPE: 'Unit';

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
STAR: '*';
SLASH: '/';
PERCENT: '%';
EQ: '==';
NEQ: '!=';
LT: '<';
GT: '>';
LTE: '<=';
GTE: '>=';
PIPE: '|';
OR: 'or';
AND: 'and';

INT_LITERAL: [0-9]+;
STRING_LITERAL: '"'[ a-zA-Z0-9!]*'"';
BOOL_LITERAL: 'True' | 'False';
IDENTIFIER: [a-zA-Z_][a-zA-Z0-9_]*;

EOL: '\r\n' | '\n' | '\r';
WHITESPACE: [ \t]+ -> skip;
