package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parametric effect-row substitution tests (Phase 2: enforcement
 * in {@link EffectRowChecker}). Each test compiles a tiny program
 * and either succeeds or fails with an expected fragment in the
 * error.
 */
class RowVarSubstitutionTest {

    private static IrijCompiler.CompileException expectFail(String source) {
        return assertThrows(IrijCompiler.CompileException.class,
                () -> IrijCompiler.compileSource(source, "irij.Program"));
    }

    @Test
    void rowVarPropagatesCallbackEffect() {
        // The callback performs `println` (::: Console). Row-var `eff`
        // binds to {Console}. Caller `pretend-pure` doesn't declare
        // Console → compile-time error.
        var e = expectFail("""
                fn run-fn :: (_ -> _):eff _ _ ::: eff
                  (f x -> f x)

                fn pretend-pure :: Int Int
                  (n -> run-fn (x -> println x) n)
                """);
        assertTrue(e.getMessage().contains("Console"),
                () -> "expected Console-effect error, got: " + e.getMessage());
        assertTrue(e.getMessage().contains("eff"),
                () -> "expected row-var origin annotation, got: " + e.getMessage());
    }

    @Test
    void rowVarBoundFromUserFn() {
        // Pass a user fn that itself declares `::: Console`. The
        // row-var binds to {Console} via the user-fn lookup path.
        var e = expectFail("""
                fn shout :: Str () ::: Console
                  (s -> println s)

                fn run-fn :: (_ -> _):eff _ _ ::: eff
                  (f x -> f x)

                fn pretend-pure :: Str Str
                  (s -> run-fn shout s)
                """);
        assertTrue(e.getMessage().contains("Console"),
                () -> "expected Console propagation, got: " + e.getMessage());
    }

    @Test
    void rowVarOkWhenCallerDeclaresEffect() {
        // Caller declares Console → substitution succeeds.
        assertDoesNotThrow(() -> IrijCompiler.compileSource("""
                fn run-fn :: (_ -> _):eff _ _ ::: eff
                  (f x -> f x)

                fn caller :: Int Int ::: Console
                  (n -> run-fn (x -> println x) n)
                """, "irij.Program"));
    }

    @Test
    void twoRowVarsUnionedInCallerRow() {
        // par takes two callbacks with independent row vars. Each
        // binding contributes its effects to par's effective row.
        // pretend-pure misses Log but has Console → still rejected.
        var e = expectFail("""
                effect Log
                  log :: Str -> ()

                handler default-log :: Log
                  log msg => resume ()

                fn par-fn :: (_ -> _):e1 (_ -> _):e2 _ _ _ ::: e1 e2
                  (f g x y -> f x)

                fn pretend-console-only :: Int Int Int ::: Console
                  (x y -> par-fn (a -> println a) (b -> log (to-str b)) x y)
                """);
        assertTrue(e.getMessage().contains("Log"),
                () -> "expected Log-effect (from e2) propagation, got: "
                        + e.getMessage());
    }

    @Test
    void rowVarPlusConcreteEffectMix() {
        // Effect row `::: Console eff` combines a concrete effect
        // (Console required regardless) with a row-var. Caller must
        // declare both Console AND whatever eff binds to.
        var e = expectFail("""
                fn logged :: (_ -> _):eff _ _ ::: Console eff
                  (f x -> println "before" ; f x)

                effect Db
                  query :: Str -> Vec

                handler default-db :: Db
                  query sql => resume #[]

                fn caller :: Str Str ::: Console
                  (s -> logged (q -> to-str (query q)) s)
                """);
        assertTrue(e.getMessage().contains("Db"),
                () -> "expected Db propagation via eff, got: " + e.getMessage());
    }

    @Test
    void pureCallbackBindsRowVarToEmptySet() {
        // Callback performs no effects. Row-var binds to {}.
        // Callee's effective row = {} ∪ {} = empty. Caller passes
        // with no effect declaration.
        assertDoesNotThrow(() -> IrijCompiler.compileSource("""
                fn run-fn :: (_ -> _):eff _ _ ::: eff
                  (f x -> f x)

                fn pure-caller :: Int Int
                  (n -> run-fn (x -> x + 1) n)
                """, "irij.Program"));
    }
}
