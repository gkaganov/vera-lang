grammar SimpleLang;

@header {
package org.greg.antlr;
}

program: expr+;
expr: term ((PLUS | MINUS) term)*;
term: INT;

PLUS: '+';
MINUS: '-';
INT: [0-9]+;

WS: [ \t\r\n]+ -> skip;
