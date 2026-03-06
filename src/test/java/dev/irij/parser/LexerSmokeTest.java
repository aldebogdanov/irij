package dev.irij.parser;

import org.antlr.v4.runtime.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LexerSmokeTest {

    /** Get meaningful tokens (skips HIDDEN channel, NEWLINE, INDENT, DEDENT, EOF). */
    private List<? extends Token> tokens(String input) {
        var lex = new IrijLexer(CharStreams.fromString(input));
        return lex.getAllTokens().stream()
                .filter(t -> t.getChannel() == Token.DEFAULT_CHANNEL)
                .filter(t -> t.getType() != Token.EOF)
                .filter(t -> t.getType() != IrijLexer.NEWLINE)
                .filter(t -> t.getType() != IrijLexer.INDENT)
                .filter(t -> t.getType() != IrijLexer.DEDENT)
                .toList();
    }

    @Test
    void tokenizesSimpleBinding() {
        var toks = tokens("x := 42");
        assertEquals(3, toks.size());
        assertEquals(IrijLexer.LOWER_ID, toks.get(0).getType());
        assertEquals("x", toks.get(0).getText());
        assertEquals(IrijLexer.BIND, toks.get(1).getType());
        assertEquals(IrijLexer.INT_LIT, toks.get(2).getType());
        assertEquals("42", toks.get(2).getText());
    }

    @Test
    void tokenizesKeywords() {
        var toks = tokens("fn match type effect with scope");
        assertEquals(6, toks.size());
        assertEquals(IrijLexer.FN, toks.get(0).getType());
        assertEquals(IrijLexer.MATCH, toks.get(1).getType());
        assertEquals(IrijLexer.TYPE, toks.get(2).getType());
        assertEquals(IrijLexer.EFFECT, toks.get(3).getType());
        assertEquals(IrijLexer.WITH, toks.get(4).getType());
        assertEquals(IrijLexer.SCOPE, toks.get(5).getType());
    }

    @Test
    void tokenizesOperators() {
        var toks = tokens("|> >> := :! <- -> =>");
        assertEquals(7, toks.size());
        assertEquals(IrijLexer.PIPE, toks.get(0).getType());
        assertEquals(IrijLexer.COMPOSE, toks.get(1).getType());
        assertEquals(IrijLexer.BIND, toks.get(2).getType());
        assertEquals(IrijLexer.MUT_BIND, toks.get(3).getType());
        assertEquals(IrijLexer.ASSIGN, toks.get(4).getType());
        assertEquals(IrijLexer.ARROW, toks.get(5).getType());
        assertEquals(IrijLexer.FAT_ARROW, toks.get(6).getType());
    }

    @Test
    void tokenizesCollectionLiterals() {
        var toks = tokens("#[1 2 3]");
        assertEquals(5, toks.size());
        assertEquals(IrijLexer.HASH_LBRACK, toks.get(0).getType());
        assertEquals(IrijLexer.INT_LIT, toks.get(1).getType());
        assertEquals(IrijLexer.INT_LIT, toks.get(2).getType());
        assertEquals(IrijLexer.INT_LIT, toks.get(3).getType());
        assertEquals(IrijLexer.RBRACK, toks.get(4).getType());
    }

    @Test
    void tokenizesKebabCaseId() {
        var toks = tokens("my-value http-get");
        assertEquals(2, toks.size());
        assertEquals(IrijLexer.LOWER_ID, toks.get(0).getType());
        assertEquals("my-value", toks.get(0).getText());
        assertEquals(IrijLexer.LOWER_ID, toks.get(1).getType());
        assertEquals("http-get", toks.get(1).getText());
    }

    @Test
    void tokenizesKeywordLiteral() {
        var toks = tokens(":ok :error :pending");
        assertEquals(3, toks.size());
        assertEquals(IrijLexer.KEYWORD_LIT, toks.get(0).getType());
        assertEquals(":ok", toks.get(0).getText());
        assertEquals(IrijLexer.KEYWORD_LIT, toks.get(1).getType());
        assertEquals(":error", toks.get(1).getText());
        assertEquals(IrijLexer.KEYWORD_LIT, toks.get(2).getType());
    }

    @Test
    void tokenizesChoreographyOps() {
        var toks = tokens("~> <~ ~*> ~/");
        assertEquals(4, toks.size());
        assertEquals(IrijLexer.SEND, toks.get(0).getType());
        assertEquals(IrijLexer.RECV, toks.get(1).getType());
        assertEquals(IrijLexer.BROADCAST, toks.get(2).getType());
        assertEquals(IrijLexer.CHOREO_SEL, toks.get(3).getType());
    }

    @Test
    void tokenizesSeqOps() {
        var toks = tokens("/+ /* /# /& /|");
        assertEquals(5, toks.size());
        assertEquals(IrijLexer.REDUCE_PLUS, toks.get(0).getType());
        assertEquals(IrijLexer.REDUCE_STAR, toks.get(1).getType());
        assertEquals(IrijLexer.COUNT, toks.get(2).getType());
        assertEquals(IrijLexer.REDUCE_AND, toks.get(3).getType());
        assertEquals(IrijLexer.REDUCE_OR, toks.get(4).getType());
    }

    @Test
    void tokenizesComment() {
        var toks = tokens(";; this is a comment\nx := 1");
        boolean foundBind = toks.stream().anyMatch(t -> t.getType() == IrijLexer.BIND);
        assertTrue(foundBind, "Should find BIND token after comment");
    }

    @Test
    void tokenizesUpperId() {
        var toks = tokens("User HttpResponse ALICE");
        assertEquals(3, toks.size());
        assertEquals(IrijLexer.UPPER_ID, toks.get(0).getType());
        assertEquals("User", toks.get(0).getText());
        assertEquals(IrijLexer.UPPER_ID, toks.get(1).getType());
        assertEquals("HttpResponse", toks.get(1).getText());
        assertEquals(IrijLexer.UPPER_ID, toks.get(2).getType());
        assertEquals("ALICE", toks.get(2).getText());
    }

    @Test
    void tokenizesNumericLiterals() {
        var toks = tokens("42 3.14 0xFF 1_000_000 2/3");
        assertEquals(5, toks.size());
        assertEquals(IrijLexer.INT_LIT, toks.get(0).getType());
        assertEquals(IrijLexer.FLOAT_LIT, toks.get(1).getType());
        assertEquals(IrijLexer.HEX_LIT, toks.get(2).getType());
        assertEquals(IrijLexer.INT_LIT, toks.get(3).getType());
        assertEquals("1_000_000", toks.get(3).getText());
        assertEquals(IrijLexer.RATIONAL_LIT, toks.get(4).getType());
        assertEquals("2/3", toks.get(4).getText());
    }

    @Test
    void tokenizesStringWithInterpolation() {
        var toks = tokens("\"hello ${name}\"");
        assertEquals(1, toks.size());
        assertEquals(IrijLexer.STRING_LIT, toks.get(0).getType());
        assertEquals("\"hello ${name}\"", toks.get(0).getText());
    }
}
