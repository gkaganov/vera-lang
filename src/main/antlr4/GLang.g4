grammar GLang;

@header {
package org.greg.antlr4;
}

program: expr+ EOF;
expr: term (operation term)*;
operation: PLUS | MINUS;
term: INT;

PLUS: '+';
MINUS: '-';
INT: [0-9]+;

WS: [ \t\r\n]+ -> skip;
