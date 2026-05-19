package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Compile-time effect-row lint tests. The {@link EffectRowChecker}
 * runs after module inlining and before either back-end. These tests
 * exercise its three main responsibilities:
 *
 * <ol>
 *   <li>Call-site subsumption — caller must declare callee's effects.</li>
 *   <li>JVM capability for plain Java interop sites.</li>
 *   <li>Per-ref capability — a Bind whose spec names a declared
 *       effect makes DotAccess on that variable require the effect.</li>
 * </ol>
 */
class EffectRowLintTest {

    private static IrijCompiler.CompileException expectFail(String source) {
        return assertThrows(IrijCompiler.CompileException.class,
                () -> IrijCompiler.compileSource(source, "irij.Program"));
    }

    // ── (1) call-site subsumption ───────────────────────────────────

    @Test
    void callerWithoutEffectRejectsCallToConsoleFn() {
        // `func` requires Console; `caller` has no row → empty avail.
        // Static lint must reject the call.
        String src = """
            fn func :: Int Unit ::: Console
              (x -> println x)
            fn caller :: Int Unit
              (x -> func x)
            """;
        IrijCompiler.CompileException e = expectFail(src);
        assertTrue(e.getMessage().contains("Console"),
                () -> "expected Console-effect error, got: " + e.getMessage());
    }

    @Test
    void callerWithMatchingEffectAccepted() {
        // Same callee but caller declares Console → accepted.
        String src = """
            fn func :: Int Unit ::: Console
              (x -> println x)
            fn caller :: Int Unit ::: Console
              (x -> func x)
            (caller 1)
            """;
        assertDoesNotThrow(() -> IrijCompiler.compileSource(src, "irij.Program"));
    }

    // ── (2) JVM capability ──────────────────────────────────────────

    @Test
    void javaRefRequiresJvmInCallerRow() {
        // Java/foo touched from a fn without ::: JVM → reject.
        String src = """
            fn make-rnd :: Int Any
              (seed -> java.util.Random/new seed)
            """;
        IrijCompiler.CompileException e = expectFail(src);
        assertTrue(e.getMessage().contains("JVM"),
                () -> "expected JVM-effect error, got: " + e.getMessage());
    }

    // ── (3) per-ref capability propagation ──────────────────────────

    @Test
    void dotAccessOnEffectTaggedRefRequiresEffect() {
        // `Random` is a declared effect. The bind `rnd :: Random := ...`
        // marks `rnd` as carrying the Random capability. Calling
        // `rnd.nextInt` from a fn lacking ::: Random must fail even
        // though ::: JVM is declared.
        String src = """
            effect Random
              next-int :: Int Int
            fn use-rnd :: Int Int ::: JVM
              => seed
              rnd := java.util.Random/new seed :: Random
              rnd.nextInt 100
            """;
        IrijCompiler.CompileException e = expectFail(src);
        assertTrue(e.getMessage().contains("Random"),
                () -> "expected Random-effect error, got: " + e.getMessage());
    }

    @Test
    void dotAccessOnEffectTaggedRefAcceptedWithEffectDeclared() {
        // Same code but caller declares ::: JVM Random → accepted.
        String src = """
            effect Random
              next-int :: Int Int
            fn use-rnd :: Int Int ::: JVM Random
              => seed
              rnd := java.util.Random/new seed :: Random
              rnd.nextInt 100
            """;
        assertDoesNotThrow(() -> IrijCompiler.compileSource(src, "irij.Program"));
    }

    @Test
    void plainDotAccessWithoutEffectSpecJustNeedsJvm() {
        // No `:: Random` annotation on bind → just regular JVM dot-access.
        // Caller declaring ::: JVM is enough.
        String src = """
            fn use-rnd :: Int Int ::: JVM
              => seed
              rnd := java.util.Random/new seed
              rnd.nextInt 100
            """;
        assertDoesNotThrow(() -> IrijCompiler.compileSource(src, "irij.Program"));
    }
}
