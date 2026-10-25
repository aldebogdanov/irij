package dev.irij.compiler;

import dev.irij.runtime.Values;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A spec checks what it says. Before: a name nothing declares accepted
 * every value; `Ok` / `Err` and variant names as specs accepted every
 * value; an applied parametric spec (`(Maybe Int)`) checked nothing; a
 * variant's payload (`Circle "x"` for `Circle Float`) was never checked,
 * and a newtype's (`Email 42` for `newtype Email := Str`) neither.
 */
class SpecStrictnessTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(SpecStrictnessTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }

    private static String run(String source) throws Exception {
        byte[] bytes = IrijCompiler.compileSource(source, "irij.Program");
        PrintStream orig = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf));
        try {
            Method main = new BytesLoader().define("irij.Program", bytes).getMethod("main", String[].class);
            main.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(orig);
        }
        return buf.toString().replace("\r\n", "\n").trim();
    }

    /** "ok" when the call passes its specs, else the failure. */
    private static String verdict(String decls, String call) throws Exception {
        return run(decls + "\nmatch (try (-> " + call + "))\n  Ok _ => println \"ok\"\n  Err e => println e\n");
    }

    private static IrijCompiler.CompileException refused(String src) {
        return assertThrows(IrijCompiler.CompileException.class, () -> IrijCompiler.compileSource(src, "irij.Program"));
    }

    // ── Names nothing declares ──────────────────────────────────────

    @Test void anUnknownSpecNameIsACompileError() {
        var e = refused("fn f :: NonExistant Int\n  (x -> 1)\nf 1\n");
        assertTrue(e.getMessage().contains("`NonExistant` is not a spec: nothing declares it."), e.getMessage());
        assertTrue(refused("fn f :: Int NonExistant\n  (x -> x)\n").getMessage().contains("NonExistant"));
        assertTrue(refused("fn f :: #[(Maybe Int)] Int\n  (x -> 1)\n").getMessage().contains("`Maybe`"));
        assertTrue(refused("spec P\n  x :: Nope\n").getMessage().contains("`Nope`"));
        assertTrue(refused("spec S\n  A Nope\n").getMessage().contains("`Nope`"));
        assertTrue(refused("newtype Email := Strng\n").getMessage().contains("did you mean `Str`?"));
    }

    @Test void declaredBuiltinAndEffectNamesAreKnown() {
        assertDoesNotThrow(() -> IrijCompiler.compileSource("""
                spec Coin
                  Nickel
                  Dime
                effect Log
                  log :: Str -> ()
                fn f :: Coin Nickel (Handler Log) Ok Err Any Int
                  (a b h o e x -> 1)
                """, "irij.Program"));
    }

    // ── Ok, Err and variant names ───────────────────────────────────

    @Test void okAndErrCheckTheVariant() throws Exception {
        assertEquals("Spec failure on output of f: expected Ok, got Int",
                verdict("fn f :: Int Ok\n  (x -> x)", "f 1"));
        assertEquals("Spec failure on output of f: expected Err, got Ok",
                verdict("fn f :: Int Err\n  (x -> Ok x)", "f 1"));
        assertEquals("ok", verdict("fn f :: Int (Ok Int)\n  (x -> Ok x)", "f 1"));
        assertEquals("Spec failure on output of f: Ok field 1: expected Int, got Str",
                verdict("fn f :: Int (Ok Int)\n  (x -> Ok \"s\")", "f 1"));
    }

    @Test void aVariantNameChecksTheVariant() throws Exception {
        String coin = "spec Coin\n  Nickel\n  Dime\n";
        assertEquals("Spec failure on output of f: expected Nickel, got Dime",
                verdict(coin + "fn f :: Int Nickel\n  (n -> Dime)", "f 1"));
        assertEquals("ok", verdict(coin + "fn f :: Int Nickel\n  (n -> Nickel)", "f 1"));
    }

    // ── Payloads and applied specs ──────────────────────────────────

    @Test void aVariantsPayloadIsCheckedWhenBuilt() throws Exception {
        String shape = "spec Shape\n  Circle Float\n  Rect Float Float\n";
        assertEquals("Circle field 1: expected Float, got Str", verdict(shape, "Circle \"x\""));
        assertEquals("ok", verdict(shape, "Rect 1.0 2.0"));
        assertEquals("Email field 1: expected Str, got Int", verdict("newtype Email := Str\n", "Email 42"));
    }

    @Test void anAppliedSpecSubstitutesItsParameters() throws Exception {
        String result = "spec Result a e\n  Ok a\n  Err e\n";
        assertEquals("ok", verdict(result + "fn f :: Int (Result Int Str)\n  (n -> Err \"no\")", "f 1"));
        assertEquals("Spec failure on output of f: Result Err field 1: expected Str, got Int",
                verdict(result + "fn f :: Int (Result Int Str)\n  (n -> Err n)", "f 1"));
        assertEquals("Spec failure on output of f: expected Result variant, got Int",
                verdict(result + "fn f :: Int (Result Int Str)\n  (n -> n)", "f 1"));
        // A builtin Ok (from try) is a Result by its tag.
        assertEquals("ok", verdict(result + "fn f :: Str (Result Int Str)\n  (s -> try (-> parse-int s))", "f \"7\""));
        // Unapplied, a parameter accepts anything.
        assertEquals("ok", verdict("spec Maybe a\n  Just a\n  Nothing\nfn f :: Int Maybe\n  (n -> Just \"s\")", "f 1"));
        assertEquals("Spec failure on output of f: Maybe Just field 1: expected Int, got Str",
                verdict("spec Maybe a\n  Just a\n  Nothing\nfn f :: Int (Maybe Int)\n  (n -> Just \"s\")", "f 1"));
    }

    @Test void aProductAppliedToItsParametersChecksThem() throws Exception {
        String pair = "spec Pair a b\n  fst :: a\n  snd :: b\n";
        assertEquals("ok", verdict(pair + "fn f :: Int (Pair Int Str)\n  (n -> Pair n \"s\")", "f 1"));
        assertEquals("Spec failure on output of f: Pair field 'snd': expected Str, got Int",
                verdict(pair + "fn f :: Int (Pair Int Str)\n  (n -> Pair n n)", "f 1"));
    }

    @Test void aProductAppliedToARowVarIsAnOpenRecord() throws Exception {
        // std.serve's Route: `(Route):eff` checks `action`, lets `method` through.
        String route = "spec Route ::: eff\n  action :: (Fn):eff\n"
                + "fn act :: (Route):eff Int ::: eff\n  (r -> 1)\n";
        assertEquals("ok", verdict(route, "act {action= (x -> x) method= \"GET\"}"));
        assertTrue(verdict(route, "act {method= \"GET\"}").contains("Route requires field 'action'"));
    }

    // ── Sessions ────────────────────────────────────────────────────

    @Test void aSpecFromAnEarlierEvalIsKnown() {
        String s = (String) RuntimeSessions.rawSessionCreate();
        try {
            var r1 = ((Values.IrijMap) RuntimeSessions.rawSessionEval(s, "spec Coin\n  Nickel\n  Dime\n", 5000L)).entries();
            assertEquals(true, r1.get("ok"), "error: " + r1.get("error"));
            var r2 = ((Values.IrijMap) RuntimeSessions.rawSessionEval(s,
                    "fn f :: Coin Int\n  (c -> 5)\nf Nickel", 5000L)).entries();
            assertEquals("5", r2.get("value"), "error: " + r2.get("error"));
            var r3 = ((Values.IrijMap) RuntimeSessions.rawSessionEval(s,
                    "fn g :: Coyn Int\n  (c -> 5)", 5000L)).entries();
            assertTrue(String.valueOf(r3.get("error")).contains("did you mean `Coin`?"), "error: " + r3.get("error"));
        } finally {
            RuntimeSessions.rawSessionDestroy(s);
        }
    }

    // ── The builtin Result ──────────────────────────────────────────

    @Test void resultIsBuiltin() throws Exception {
        assertEquals("ok", verdict("fn f :: Int Result\n  (n -> Err n)", "f 1"));
        assertEquals("Spec failure on output of f: expected Result (Ok or Err), got Int",
                verdict("fn f :: Int Result\n  (n -> n)", "f 1"));
        assertEquals("ok", verdict("fn f :: Str (Result Int Str)\n  (s -> try (-> parse-int s))", "f \"x\""));
        assertEquals("Spec failure on output of f: Result Ok field 1: expected Str, got Int",
                verdict("fn f :: Int (Result Str Str)\n  (n -> Ok n)", "f 1"));
        assertEquals("Spec failure on output of f: Result takes 2 spec parameters (Result ok err), given 1",
                verdict("fn f :: Int (Result Int)\n  (n -> Ok n)", "f 1"));
    }

    @Test void aProgramsOwnResultTakesPrecedence() throws Exception {
        String own = "spec Result a\n  Good a\n  Bad\n";
        assertEquals("ok", verdict(own + "fn f :: Int (Result Int)\n  (n -> Good n)", "f 1"));
        assertEquals("Spec failure on output of f: 'Ok' is not a variant of Result (expected one of: [Good, Bad])",
                verdict(own + "fn f :: Int Result\n  (n -> Ok n)", "f 1"));
    }
}
