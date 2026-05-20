package dev.irij.parser;

import dev.irij.ast.AstBuilder;
import dev.irij.ast.Decl;
import dev.irij.ast.SpecExpr;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parametric effect-row syntax tests (Phase 1: parser + AST only,
 * no enforcement).
 *
 * <p>Verifies that {@code :eff} suffix on grouped function specs
 * survives parse + AST build, and that lowercase identifiers in
 * {@code ::: …} position are captured as row variables.
 */
class RowVarTest {

    private static Decl.FnDecl parseFn(String source) {
        var parsed = IrijParseDriver.parse(source);
        assertTrue(parsed.errors().isEmpty(),
                () -> "parse errors: " + parsed.errors());
        List<Decl> decls = new AstBuilder().build(parsed.tree());
        for (Decl d : decls) {
            if (d instanceof Decl.FnDecl fn) return fn;
            if (d instanceof Decl.PubDecl pd && pd.inner() instanceof Decl.FnDecl fn) return fn;
        }
        throw new IllegalStateException("no fn decl in source");
    }

    @Test
    void singleRowVarOnGroupedArrow() {
        var fn = parseFn("""
                pub fn fold-like :: (_ _ -> _):eff _ Vec _ ::: eff
                  (f acc v -> acc)
                """);
        // Effect row carries the row-var name.
        assertEquals(List.of("eff"), fn.effectRow());
        // First spec slot is an Arrow with rowVar set.
        var specs = fn.specAnnotations();
        assertNotNull(specs);
        assertTrue(specs.size() >= 1, "expected at least one spec annotation");
        SpecExpr first = specs.get(0);
        assertTrue(first instanceof SpecExpr.Arrow,
                () -> "expected Arrow as first spec, got " + first.getClass().getSimpleName());
        var arrow = (SpecExpr.Arrow) first;
        assertEquals("eff", arrow.rowVar());
    }

    @Test
    void twoRowVarsInOneSignature() {
        var fn = parseFn("""
                pub fn par-like :: (_ -> _):e1 (_ -> _):e2 _ _ ::: e1 e2
                  (a b -> #[a b])
                """);
        assertEquals(List.of("e1", "e2"), fn.effectRow());
        var specs = fn.specAnnotations();
        assertTrue(specs.get(0) instanceof SpecExpr.Arrow,
                () -> "first spec expected Arrow, got " + specs.get(0).getClass().getSimpleName());
        assertTrue(specs.get(1) instanceof SpecExpr.Arrow,
                () -> "second spec expected Arrow, got " + specs.get(1).getClass().getSimpleName());
        assertEquals("e1", ((SpecExpr.Arrow) specs.get(0)).rowVar());
        assertEquals("e2", ((SpecExpr.Arrow) specs.get(1)).rowVar());
    }

    @Test
    void mixedConcreteAndRowVarInEffectRow() {
        var fn = parseFn("""
                pub fn logged :: (_ -> _):eff _ _ ::: Console eff
                  (thunk x -> thunk x)
                """);
        // Order preserved: Console first (declared), eff second (row-var).
        assertEquals(List.of("Console", "eff"), fn.effectRow());
        var arrow = (SpecExpr.Arrow) fn.specAnnotations().get(0);
        assertEquals("eff", arrow.rowVar());
    }

    @Test
    void plainArrowSpecHasNullRowVar() {
        var fn = parseFn("""
                pub fn id :: (_ -> _) _ _
                  (f x -> f x)
                """);
        var arrow = (SpecExpr.Arrow) fn.specAnnotations().get(0);
        assertNull(arrow.rowVar(),
                () -> "plain arrow should have null rowVar, got " + arrow.rowVar());
    }

    @Test
    void rowVarOnGroupedFnAtom() {
        // `(Fn):eff` form — promotes the bare `Fn` Name into an
        // App so the row-var has a node to attach to.
        var fn = parseFn("""
                pub fn run-it :: (Fn):eff _ _ ::: eff
                  (f x -> f x)
                """);
        var first = fn.specAnnotations().get(0);
        assertTrue(first instanceof SpecExpr.App,
                () -> "expected App, got " + first.getClass().getSimpleName());
        var app = (SpecExpr.App) first;
        assertEquals("Fn", app.head());
        assertEquals("eff", app.rowVar());
    }

    @Test
    void enumStillParsesWithoutCollision() {
        // Regression: the row-var grammar must not break Enum specs.
        var fn = parseFn("""
                pub fn classify :: Str (Enum :ok :error)
                  (s -> :ok)
                """);
        var ret = fn.specAnnotations().get(fn.specAnnotations().size() - 1);
        assertTrue(ret instanceof SpecExpr.Enum,
                () -> "expected Enum, got " + ret.getClass().getSimpleName());
        assertEquals(List.of("ok", "error"), ((SpecExpr.Enum) ret).values());
    }
}
