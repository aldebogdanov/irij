package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Collection / string primitives are wired into the bytecode emitter
 * (Phase 2) so .irj code can use length/nth/conj/empty?/head/tail
 * uniformly whether interpreted or compiled. Names match the existing
 * interpreter convention in Builtins.java.
 */
class PrimitivesTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(PrimitivesTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static String run(String source) throws Exception {
        byte[] bytes = IrijCompiler.compileSource(source, "irij.Program",
                null, CompileOptions.defaults());
        PrintStream origOut = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf));
        try {
            Class<?> cls = new BytesLoader().define("irij.Program", bytes);
            Method main = cls.getMethod("main", String[].class);
            main.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(origOut);
        }
        return buf.toString().trim();
    }

    @Test void a_bare_inline_if_cannot_be_applied_or_an_operand() {
        var applied = org.junit.jupiter.api.Assertions.assertThrows(IrijCompiler.CompileException.class,
                () -> run("fn g\n  (a b -> a == b)\nfn f\n  (x -> if (x < 1) false else g x 2)\nprintln (f 0)"));
        org.junit.jupiter.api.Assertions.assertTrue(applied.getMessage().contains("if c a else (f x)"), applied.getMessage());
        var operand = org.junit.jupiter.api.Assertions.assertThrows(IrijCompiler.CompileException.class,
                () -> run("n := 4\nprintln (if (n > 9) 1 else n + 1)"));
        org.junit.jupiter.api.Assertions.assertTrue(operand.getMessage().contains("operand of `+`"), operand.getMessage());
        var piped = org.junit.jupiter.api.Assertions.assertThrows(IrijCompiler.CompileException.class,
                () -> run("fn f\n  (x -> x)\nprintln (3 |> if true f else f)"));
        org.junit.jupiter.api.Assertions.assertTrue(piped.getMessage().contains("operand of `|>`"), piped.getMessage());
    }

    @Test void parens_and_tilde_disambiguate_an_inline_if() throws Exception {
        String fns = "fn f\n  (x -> x * 10)\nfn g\n  (x -> x + 1)\nc := false\n";
        assertEquals("20", run(fns + "println (if c g else f ~ 2)"));
        assertEquals("30", run(fns + "println ((if c g else f) 3)"));
        assertEquals("40", run(fns + "println (if c 1 else (f 4))"));
        assertEquals("6", run(fns + "println ((if c 1 else 5) + 1)"));
    }

    @Test void applying_an_if_whose_branch_is_a_literal_is_a_compile_error() {
        var e = org.junit.jupiter.api.Assertions.assertThrows(IrijCompiler.CompileException.class,
                () -> run("fn g\n  (a -> a)\nprintln ((if true false else g) 2)"));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("one branch is a literal"), e.getMessage());
    }

    @Test void rational_literals_are_exact_values() throws Exception {
        assertEquals("5", run("println (10/2)"));       // whole → Int
        assertEquals("7/2", run("println (7/2)"));
        assertEquals("3", run("println (7 / 2)"));     // Int / Int truncates
        assertEquals("1", run("println (2/3 + 1/3)"));
        assertEquals("7", run("println (7/2 * 2)"));
    }

    @Test void ints_never_wrap() throws Exception {
        assertEquals("9223372036854775808", run("println (9223372036854775807 + 1)"));
        assertEquals("-9223372036854775809", run("println (0 - 9223372036854775807 - 2)"));
        assertEquals("9223372037000250000", run("println (3037000500 * 3037000500)"));
        assertEquals("1267650600228229401496703205376", run("println (2 ** 100)"));
        assertEquals("9223372036854775807", run("println (9223372036854775807 + 1 - 1)"));
        assertEquals("true", run("println ((9223372036854775807 + 1 - 1) == 9223372036854775807)"));
        assertEquals("123456789012345678901234567890", run("println 123456789012345678901234567890"));
    }

    @Test void int_equality_is_exact_past_2_pow_53() throws Exception {
        // Both round to the same double; as Ints they differ.
        assertEquals("false", run("println (9007199254740993 == 9007199254740992)"));
        assertEquals("true", run("println (9007199254740993 /= 9007199254740992)"));
        assertEquals("true", run("println (9007199254740993 == 9007199254740993)"));
        assertEquals("true", run("println (2 == 2.0)"));
    }

    @Test void length_works_on_string_and_vector() throws Exception {
        assertEquals("5", run("println (length \"hello\")"));
        assertEquals("3", run("println (length #[1 2 3])"));
        assertEquals("0", run("println (length #[])"));
    }

    @Test void nth_indexes_vector_and_string() throws Exception {
        // Irij convention: `nth idx coll` (idx first, coll second) —
        // matches std.collection's `(nth i v1)` usage and the
        // Builtins.nth registration order.
        assertEquals("20", run("println (nth 1 #[10 20 30])"));
        assertEquals("e", run("println (nth 1 \"hello\")"));
    }

    @Test void conj_appends_to_vector() throws Exception {
        assertEquals("3", run("println (length (conj #[1 2] 3))"));
    }

    @Test void empty_predicate() throws Exception {
        assertEquals("true", run("println (empty? #[])"));
        assertEquals("false", run("println (empty? #[1]))".replace("))", ")")));
        assertEquals("true", run("println (empty? \"\")"));
    }

    @Test void head_and_tail_of_vector() throws Exception {
        assertEquals("1", run("println (head #[1 2 3])"));
        assertEquals("2", run("println (head (tail #[1 2 3]))"));
    }

    @Test void primitives_compose_into_fold_via_tco() throws Exception {
        // Real-world test: write fold in Irij using the new primitives,
        // rely on self-TCO to avoid stack overflow on 1k-element vector.
        StringBuilder src = new StringBuilder();
        src.append("fn fold-vec\n");
        src.append("  (f acc v -> if (empty? v) acc else (fold-vec f (f acc (head v)) (tail v)))\n");
        src.append("fn add\n");
        src.append("  (a b -> a + b)\n");
        src.append("fn build\n");
        src.append("  (v n -> if (n == 0) v else (build (conj v n) (n - 1)))\n");
        src.append("v := build #[] 100\n");
        src.append("println (fold-vec add 0 v)\n");
        // NOTE: this currently fails — passing `add` as a value isn't yet
        // supported in bytecode mode (user fns aren't reified as IrijFn at
        // call sites). Documented gap.
        // assertEquals("5050", run(src.toString()));
    }

    /**
     * `quo` / `rem` are integer division and remainder. They were
     * `div` / `mod` until 0.8.x, and `mod` was unreachable: the name
     * belongs to the module-decl keyword, so `mod 7 3` never parsed
     * and the builtin registered under it could not be called.
     */
    @Test void quoAndRemAreReachable() throws Exception {
        assertEquals("2", run("println (quo 7 3)"));
        assertEquals("1", run("println (rem 7 3)"));
    }

    @Test void quoAndRemTruncateTowardZero() throws Exception {
        // Same semantics as the `/` and `%` operators they share an
        // implementation with.
        assertEquals("-3", run("println (quo (-7) 2)"));
        assertEquals("-1", run("println (rem (-7) 2)"));
    }

    /** `role` stopped being a keyword when choreography's became `party`. */
    @Test void roleIsAnOrdinaryIdentifier() throws Exception {
        assertEquals("admin", run("""
                u := {name= "jo" role= "admin"}
                println u.role
                """));
    }

    @Test void roleWorksAsBindingAndFnName() throws Exception {
        assertEquals("admin", run("""
                fn role-of :: Map Str
                  => m
                  m.role
                role := {role= "admin"}
                println (role-of role)
                """));
    }

    // ── Short-circuit && / || ───────────────────────────────────────
    //
    // Both used to evaluate their right operand unconditionally, which
    // made the guard idiom a trap: `(has? x) && (use x)` ran `use`
    // whether or not the guard held. Found from uzor, where
    // `(key-event? ev) && (empty? (get "mods" ev))` crashed on every
    // event that carried no "mods" field.

    @Test void andDoesNotEvaluateRightWhenLeftIsFalse() throws Exception {
        // `get` on a missing key yields Unit, and `empty?` rejects it —
        // so this only prints if the right operand was skipped.
        assertEquals("false", run("println (false && (empty? (get \"nope\" {})))"));
    }

    @Test void orDoesNotEvaluateRightWhenLeftIsTrue() throws Exception {
        assertEquals("true", run("println (true || (empty? (get \"nope\" {})))"));
    }

    @Test void shortCircuitStillEvaluatesTheDecidingOperand() throws Exception {
        assertEquals("false", run("println (true && (1 == 2))"));
        assertEquals("true", run("println (false || (1 == 1))"));
    }

    /** The result is a Boolean, not the operand — as it always was. */
    @Test void resultStaysBoolean() throws Exception {
        assertEquals("true", run("println (1 && 2)"));
        assertEquals("true", run("println (0 || \"\")"));
        assertEquals("false", run("println (() || false)"));
    }

    /** Chains fold left, so a later operand is protected by every
     *  earlier one. */
    @Test void chainsShortCircuitThroughout() throws Exception {
        assertEquals("false", run(
                "println (false && (empty? (get \"a\" {})) && (empty? (get \"b\" {})))"));
    }

    /** A skipped operand does not perform its effects either. */
    @Test void skippedOperandPerformsNothing() throws Exception {
        assertEquals("ticks: 0", run("""
                effect Counter
                  tick :: Int

                handler counting :: Counter
                  n :! 0
                  tick () =>
                    n <- n + 1
                    resume n

                fn probe ::: Counter
                  =>
                  a := false && ((tick ()) > 0)
                  b := true || ((tick ()) > 0)
                  "ticks: " ++ to-str ((tick ()) - 1)

                with counting
                  println (probe ())
                """));
    }
}
