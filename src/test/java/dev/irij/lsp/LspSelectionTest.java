package dev.irij.lsp;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SelectionRange;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LspSelectionTest {

    /** The selection chain at (line, col), innermost first, as source text. */
    static List<String> chain(String src, int line, int col) {
        SelectionRange r = LspSelection.compute(src, List.of(new Position(line, col))).get(0);
        List<String> out = new ArrayList<>();
        for (; r != null; r = r.getParent()) out.add(slice(src, r.getRange()));
        return out;
    }

    static String slice(String src, Range r) {
        return src.substring(offset(src, r.getStart()), offset(src, r.getEnd()));
    }

    static int offset(String src, Position p) {
        return LspText.offsetOf(src, p);
    }

    @Test void expandsFromIdentifierThroughLambdaToDecl() {
        String src = "fn square\n  (n -> n * n)\n\nx := 1\n";
        List<String> c = chain(src, 1, 8);   // second `n`
        assertEquals("n", c.get(0));
        assertTrue(c.contains("n * n"), c::toString);
        assertTrue(c.contains("n -> n * n"), c::toString);      // bracket contents
        assertTrue(c.contains("(n -> n * n)"), c::toString);    // whole group
        assertTrue(c.contains("fn square\n  (n -> n * n)"), c::toString);  // no trailing newline
        assertEquals(src, c.get(c.size() - 1));
    }

    @Test void stringContentsBeforeQuotes() {
        String src = "x := \"hello ${name}\"\n";
        List<String> c = chain(src, 0, 7);
        assertEquals("hello ${name}", c.get(0));
        assertEquals("\"hello ${name}\"", c.get(1));
        assertTrue(c.contains("x := \"hello ${name}\""), c::toString);
    }

    @Test void blockSpanStopsAtLastRealToken() {
        String src = "fn f\n  =>\n  if c\n    a\n  else\n    b\n\ny := 2\n";
        List<String> c = chain(src, 3, 4);   // `a`
        assertTrue(c.contains("if c\n    a\n  else\n    b"), c::toString);
        assertTrue(c.contains("fn f\n  =>\n  if c\n    a\n  else\n    b"), c::toString);
    }

    @Test void separateGroupsGetNoBogusContents() {
        String src = "x := (a) + (b)\n";
        List<String> c = chain(src, 0, 9);   // `+`
        assertTrue(c.contains("(a) + (b)"), c::toString);
        assertTrue(c.stream().noneMatch("a) + (b"::equals), c::toString);
    }

    @Test void cursorJustAfterWordSelectsIt() {
        String src = "x := foo\n";
        assertEquals("foo", chain(src, 0, 8).get(0));
    }

    @Test void utf16ColumnsAroundAstralCharacters() {
        String src = "x := \"𝕏\" ++ yy\n";   // "𝕏" is two UTF-16 units
        List<String> c = chain(src, 0, 14);  // inside `yy`
        assertEquals("yy", c.get(0));
    }

    @Test void oneResultPerPosition() {
        String src = "a := 1\nb := 2\n";
        assertEquals(3, LspSelection.compute(src,
                List.of(new Position(0, 0), new Position(1, 0), new Position(5, 0))).size());
    }

    @Test void brokenSourceStillAnswers() {
        String src = "fn f\n  (x -> \n";
        List<String> c = chain(src, 1, 4);
        assertEquals(src, c.get(c.size() - 1));
    }

    /** LSP requires each parent to contain its child; check positions
     *  throughout every example program. */
    @Test void parentsStrictlyContainChildren() throws Exception {
        try (var files = Files.list(Path.of("examples"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".irj")).sorted().toList())
                checkNesting(Files.readString(f));
        }
    }

    private static void checkNesting(String src) {
        String[] lines = src.split("\n", -1);
        List<Position> ps = new ArrayList<>();
        for (int l = 0; l < lines.length; l++)
            for (int col = 0; col <= lines[l].length(); col += 3) ps.add(new Position(l, col));
        for (SelectionRange r : LspSelection.compute(src, ps)) {
            for (SelectionRange p = r.getParent(); p != null; r = p, p = p.getParent()) {
                int cs = offset(src, r.getRange().getStart()), ce = offset(src, r.getRange().getEnd());
                int ps0 = offset(src, p.getRange().getStart()), pe = offset(src, p.getRange().getEnd());
                String msg = "bad nesting " + r.getRange() + " in " + p.getRange();
                assertTrue(ps0 <= cs && ce <= pe && (pe - ps0) > (ce - cs), msg);
            }
        }
    }
}
