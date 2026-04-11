grammar GLang;

@header {
package org.greg.antlr4;
}

program: expr+ EOF;
expr: prefixOperation* term (infixOperation term)* EOL;
prefixOperation: PRINT;
infixOperation: PLUS | MINUS;
term: INT;

PRINT: 'print';

PLUS: '+';
MINUS: '-';
INT: [0-9]+;

EOL: '\r\n' | '\n' | '\r' ;
WHITESPACE: ' '+ -> skip;
