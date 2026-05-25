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

    // ── (4) Migrated from tests/test-effect-rows.irj ─────────────────
    //
    // These cases used to be runtime-pattern tests (`try (-> bad-fn ())`
    // → expect Err). Under strict static checking they trip
    // EffectRowChecker before the test fixture can even compile, so
    // they belong here.

    @Test
    void unannotatedFnCallingPrintlnRejected() {
        // `bad-pure` has no `:::` row → empty avail → println needs Console.
        String src = """
            fn bad-pure
              (x -> println x)
            """;
        IrijCompiler.CompileException e = expectFail(src);
        assertTrue(e.getMessage().contains("Console"),
                () -> "expected Console-effect error, got: " + e.getMessage());
    }

    @Test
    void unannotatedFnCallingUserEffectOpRejected() {
        // `silent` is unannotated, but its body performs `log-it` from
        // the user-declared `MyLog` effect → MyLog not in avail → reject.
        String src = """
            effect MyLog
              log-it :: Str -> ()
            fn silent
              (_ -> log-it "nope")
            """;
        IrijCompiler.CompileException e = expectFail(src);
        assertTrue(e.getMessage().contains("MyLog"),
                () -> "expected MyLog-effect error, got: " + e.getMessage());
    }

    @Test
    void fnDeclaresOneEffectButPerformsAnotherRejected() {
        // `only-log` declares ::: MyLog, body performs Beep → reject.
        String src = """
            effect MyLog
              log-it :: Str -> ()
            effect Beep
              beep :: () -> ()
            fn only-log ::: MyLog
              (_ -> beep ())
            """;
        IrijCompiler.CompileException e = expectFail(src);
        assertTrue(e.getMessage().contains("Beep"),
                () -> "expected Beep-effect error, got: " + e.getMessage());
    }

    @Test
    void handlerWithoutEffectAnnotationRejectsClauseUsingEffectBuiltin() {
        // `pure-logger :: MyLog` (no `::: Console`) — its clause body
        // calls println which needs Console. Clause body runs under the
        // handler's declared row; with empty requiredEffects, Console is
        // unavailable → reject.
        String src = """
            effect MyLog
              log-it :: Str -> ()
            handler pure-logger :: MyLog
              log-it msg => resume (println msg)
            """;
        IrijCompiler.CompileException e = expectFail(src);
        assertTrue(e.getMessage().contains("Console"),
                () -> "expected Console-effect error, got: " + e.getMessage());
    }

    // ── (4) capability declarations (cap … :: Eff = "JavaClass") ────
    //
    // Phase 1 surface: the `cap` decl parses + registers; references to
    // a cap name are accepted only as dot-access targets inside clauses
    // of handlers for the matching effect. Bare references and
    // cross-effect references are rejected.

    @Test
    void capDeclParsesAndIsAcceptedInMatchingClause() {
        // Cap bound to Db; used as dot-access target inside a Db-handler
        // clause. (The clause body just calls a Java method on the cap
        // and resumes; the emitter doesn't run this in phase 1 — the
        // lint pass alone has to succeed.)
        String src = """
            effect Db
              db-open :: Str -> Int
            cap db-jdbc :: Db = "dev.irij.runtime.JdbcCapability"
            handler default-db :: Db ::: JVM
              db-open path => resume (db-jdbc.open path)
            """;
        assertDoesNotThrow(() -> IrijCompiler.compileSource(src, "irij.Program"));
    }

    @Test
    void pubCapDeclParses() {
        String src = """
            effect Fs
              fs-read :: Str -> Str
            pub cap fs-files :: Fs = "dev.irij.runtime.FsCapability"
            handler default-fs :: Fs ::: JVM
              fs-read path => resume (fs-files.read path)
            """;
        assertDoesNotThrow(() -> IrijCompiler.compileSource(src, "irij.Program"));
    }

    @Test
    void capRejectedOutsideAnyHandlerClause() {
        // Reference to the cap from a plain user fn — not inside any
        // handler clause — must be rejected.
        String src = """
            effect Db
              db-open :: Str -> Int
            cap db-jdbc :: Db = "dev.irij.runtime.JdbcCapability"
            fn leak :: Str Int ::: JVM
              (path -> db-jdbc.open path)
            """;
        IrijCompiler.CompileException e = expectFail(src);
        assertTrue(e.getMessage().contains("db-jdbc")
                        && e.getMessage().toLowerCase().contains("cap"),
                () -> "expected cap-misuse error, got: " + e.getMessage());
    }

    @Test
    void capRejectedInWrongEffectClause() {
        // Cap bound to Db, referenced from a Logger-handler clause body.
        // Lint must reject the cross-effect reference.
        String src = """
            effect Db
              db-open :: Str -> Int
            effect Logger
              log :: Str -> ()
            cap db-jdbc :: Db = "dev.irij.runtime.JdbcCapability"
            handler default-logger :: Logger ::: JVM
              log msg => resume (db-jdbc.open msg)
            """;
        IrijCompiler.CompileException e = expectFail(src);
        assertTrue(e.getMessage().contains("Db")
                        && e.getMessage().contains("Logger"),
                () -> "expected cross-effect cap error, got: " + e.getMessage());
    }

    @Test
    void bareCapReferenceAsValueRejected() {
        // Option (a) stance: caps are not first-class values. Using the
        // cap-name in any non-dot-access position (here: as a bind RHS)
        // is rejected even inside a matching-effect clause.
        String src = """
            effect Db
              db-open :: Str -> Int
            cap db-jdbc :: Db = "dev.irij.runtime.JdbcCapability"
            handler default-db :: Db ::: JVM
              db-open path =>
                stashed := db-jdbc
                resume (stashed.open path)
            """;
        IrijCompiler.CompileException e = expectFail(src);
        assertTrue(e.getMessage().toLowerCase().contains("value")
                        || e.getMessage().contains("dot-access"),
                () -> "expected bare-cap-reference error, got: " + e.getMessage());
    }

    @Test
    void duplicateCapNameWithDifferentEffectsRejected() {
        // Two `cap` decls binding the same name to two different
        // effects — should error at registration time, not silently
        // overwrite.
        String src = """
            effect Db
              db-open :: Str -> Int
            effect Fs
              fs-open :: Str -> Int
            cap shared :: Db = "dev.irij.runtime.JdbcCapability"
            cap shared :: Fs = "dev.irij.runtime.FsCapability"
            """;
        IrijCompiler.CompileException e = expectFail(src);
        assertTrue(e.getMessage().contains("shared"),
                () -> "expected duplicate-cap error, got: " + e.getMessage());
    }

    // ── (5) record specs + nested row-vars (phase 2a) ───────────────
    //
    // A fn arg typed as `{action :: (Fn):eff …}` (or `Vec[…]` thereof)
    // exposes the row-var `eff` to the fn's effect row. The caller's
    // effects must subsume the union of all action fns' effect rows
    // passed in the literal.

    @Test
    void recordSpecRowVarPropagatesFromMapLitField() {
        // Caller passes a map literal whose `action` field is a fn
        // requiring Console. `dispatch :: {action :: (Fn):eff …} ::: eff`
        // — the caller (top-level) is ambient so accepted.
        String src = """
            fn act :: Str Str ::: Console
              (s -> s)
            fn dispatch :: {action :: (Fn):eff} Str ::: eff
              (r -> r.action "go")
            dispatch {action= act}
            """;
        assertDoesNotThrow(() -> IrijCompiler.compileSource(src, "irij.Program"));
    }

    @Test
    void recordSpecRowVarRejectsCallerWithoutBoundEffect() {
        // Same shape, but the caller `do-it` lacks Console in its row —
        // dispatch's eff-binding (= {Console}) exceeds do-it's avail.
        String src = """
            fn act :: Str Str ::: Console
              (s -> s)
            fn dispatch :: {action :: (Fn):eff} Str ::: eff
              (r -> r.action "go")
            fn do-it :: Str
              (_ -> dispatch {action= act})
            (do-it ())
            """;
        IrijCompiler.CompileException e = expectFail(src);
        assertTrue(e.getMessage().contains("Console"),
                () -> "expected Console-effect error, got: " + e.getMessage());
    }

    @Test
    void recordSpecRowVarInsideVecUnionsAllElementEffects() {
        // Vec[{action :: (Fn):eff}] — eff binds to the union of every
        // element's action-fn effects. Two routes, one Console one Time;
        // caller (top-level ambient) accepted.
        String src = """
            fn echo :: Str Str ::: Console
              (s -> s)
            fn tick :: Str Int ::: Time
              (_ -> now-ms ())
            fn router :: #[{action :: (Fn):eff}] Str ::: eff
              (routes -> "ok")
            router #[{action= echo} {action= tick}]
            """;
        assertDoesNotThrow(() -> IrijCompiler.compileSource(src, "irij.Program"));
    }

    @Test
    void recordSpecRowVarInsideVecRejectsMissingBoundEffect() {
        // Same Vec[Record] sig, but caller (`do-it`) declares only
        // Console. tick contributes Time → eff = {Console, Time};
        // do-it's avail = {Console} doesn't subsume → reject.
        String src = """
            fn echo :: Str Str ::: Console
              (s -> s)
            fn tick :: Str Int ::: Time
              (_ -> now-ms ())
            fn router :: #[{action :: (Fn):eff}] Str ::: eff
              (routes -> "ok")
            fn do-it :: Str ::: Console
              (_ -> router #[{action= echo} {action= tick}])
            (do-it ())
            """;
        IrijCompiler.CompileException e = expectFail(src);
        assertTrue(e.getMessage().contains("Time"),
                () -> "expected Time-effect error, got: " + e.getMessage());
    }
}
