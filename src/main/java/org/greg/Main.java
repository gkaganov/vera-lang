package org.greg;

import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.CharStreams;
import org.greg.antlr.SimpleLangLexer;
import org.greg.antlr.SimpleLangParser;

public class Main {
    public static void main(String[] args) {
        var lexer = new SimpleLangLexer(CharStreams.fromString("1 + 2"));
        var tokenStream = new BufferedTokenStream(lexer);
        var parser = new SimpleLangParser(tokenStream);
        var tree = parser.program();

        var stringTree = tree.toStringTree(parser);
        System.out.println(stringTree);
    }
}
