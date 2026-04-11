grammar GLang;

@header {
package org.greg.antlr4;
}

program: fn+ EOF;
fn: FN FN_NAME PARAMETERS '{' EOL expr* '}' EOL+;
expr: prefixOperation? (term (infixOperation term)*)? EOL;
prefixOperation: fnBuiltinCall | fnCall;
fnCall: FN_NAME PARAMETERS;
infixOperation: PLUS | MINUS;
term: INT;
fnBuiltinCall: PRINT;

PRINT: 'print';
FN: 'fn';

PLUS: '+';
MINUS: '-';
INT: [0-9]+;
FN_NAME: [a-z]+;
PARAMETERS: '()';

EOL: '\r\n' | '\n' | '\r' ;
WHITESPACE: ' '+ -> skip;
