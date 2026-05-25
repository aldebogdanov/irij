package dev.irij.lsp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LspIndexTest {

    @Test void indexesFnDecl() {
        String src = "fn greet :: Str Str ::: Console\n  (n -> \"Hi, \" ++ n)\n";
        List<LspIndex.Symbol> idx = LspIndex.build(src);
        LspIndex.Symbol fn = LspIndex.findByName(idx, "greet");
        assertNotNull(fn);
        assertEquals(LspIndex.Kind.FN, fn.kind());
        assertEquals(1, fn.loc().line());
        assertTrue(fn.signature().startsWith("fn greet"),
                () -> "expected fn greet sig, got: " + fn.signature());
        assertTrue(fn.signature().contains("Console"));
    }

    @Test void indexesEffectAndHandler() {
        String src = """
            effect Logger
              log :: Str ()
            handler quiet :: Logger
              log _ => resume ()
            """;
        List<LspIndex.Symbol> idx = LspIndex.build(src);
        assertNotNull(LspIndex.findByName(idx, "Logger"));
        assertEquals(LspIndex.Kind.EFFECT, LspIndex.findByName(idx, "Logger").kind());
        assertNotNull(LspIndex.findByName(idx, "quiet"));
        assertEquals(LspIndex.Kind.HANDLER, LspIndex.findByName(idx, "quiet").kind());
        assertTrue(LspIndex.findByName(idx, "quiet").signature().contains("Logger"));
    }

    @Test void indexesCapDecl() {
        String src = """
            effect Db
              db-open :: Str Int
            cap db-jdbc :: Db = "dev.irij.runtime.JdbcCapability"
            """;
        List<LspIndex.Symbol> idx = LspIndex.build(src);
        var cap = LspIndex.findByName(idx, "db-jdbc");
        assertNotNull(cap);
        assertEquals(LspIndex.Kind.CAP, cap.kind());
        assertTrue(cap.signature().contains("Db"));
    }

    @Test void indexesNamedSpecWithRowParam() {
        String src = """
            spec Route ::: eff
              action :: (Fn):eff
            """;
        List<LspIndex.Symbol> idx = LspIndex.build(src);
        var spec = LspIndex.findByName(idx, "Route");
        assertNotNull(spec);
        assertEquals(LspIndex.Kind.SPEC, spec.kind());
        assertTrue(spec.signature().contains("::: eff"),
                () -> "expected row-param in sig, got: " + spec.signature());
    }

    @Test void docCommentAttachedToFn() {
        String src = """
            ;; Greets a person by name.
            ;; Used in the welcome flow.
            fn greet :: Str Str ::: Console
              (n -> "Hi, " ++ n)
            """;
        List<LspIndex.Symbol> idx = LspIndex.build(src);
        var fn = LspIndex.findByName(idx, "greet");
        assertNotNull(fn);
        assertTrue(fn.docComment().contains("Greets a person by name"),
                () -> "expected doc, got: " + fn.docComment());
        assertTrue(fn.docComment().contains("welcome flow"));
    }

    @Test void noDocCommentLeavesFieldEmpty() {
        String src = "fn solo :: Str Str ::: Console\n  (x -> x)\n";
        List<LspIndex.Symbol> idx = LspIndex.build(src);
        var fn = LspIndex.findByName(idx, "solo");
        assertNotNull(fn);
        assertEquals("", fn.docComment());
    }

    @Test void brokenSourceProducesPartialIndex() {
        // Half-typed source — the LSP must stay responsive. The
        // builder swallows parse errors and returns what it could
        // extract before the error.
        String src = "fn greet :: Str Str\n  (n -> ";
        List<LspIndex.Symbol> idx = LspIndex.build(src);
        // Either empty or contains greet — both are acceptable as
        // long as no exception escapes.
        assertNotNull(idx);
    }
}
