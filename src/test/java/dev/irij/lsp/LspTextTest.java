package dev.irij.lsp;

import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LspTextTest {

    @Test void wordAt_simpleIdent() {
        // Cursor in the middle of `println` → returns the whole identifier.
        String src = "fn greet ::: Console\n  => name\n  println name\n";
        // Position is 0-based; line 2, column 4 lands inside "println".
        assertEquals("println", LspText.wordAt(src, new Position(2, 4)));
    }

    @Test void wordAt_kebabCase() {
        // Irij identifiers include `-` — `db-open` is one word.
        String src = "x := db-open \":memory:\"\n";
        assertEquals("db-open", LspText.wordAt(src, new Position(0, 6)));
    }

    @Test void wordAt_questionSuffix() {
        // `?` is a valid trailing predicate suffix on Irij names.
        String src = "fn ok? :: Bool\n  (-> true)\n";
        assertEquals("ok?", LspText.wordAt(src, new Position(0, 4)));
    }

    @Test void wordAt_onWhitespace_emptyString() {
        String src = "fn   greet\n";
        // Column 3 is the middle space between `fn` and `greet`.
        assertEquals("", LspText.wordAt(src, new Position(0, 3)));
    }
}
