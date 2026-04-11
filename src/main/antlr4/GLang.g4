grammar GLang;

@header {
package org.greg.antlr4;
}

program: fn+ EOF;
fn: FN fnName PARAMETERS '{' EOL expr* '}' EOL+;
fnName: LETTERS;
expr: prefixOperation* term (infixOperation term)* EOL;
prefixOperation: PRINT;
infixOperation: PLUS | MINUS;
term: INT;

PRINT: 'print';
FN: 'fn';

PLUS: '+';
MINUS: '-';
INT: [0-9]+;
LETTERS: [a-z]+;
PARAMETERS: '()';

EOL: '\r\n' | '\n' | '\r' ;
WHITESPACE: ' '+ -> skip;
