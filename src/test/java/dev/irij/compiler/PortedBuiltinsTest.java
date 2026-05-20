package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the Phase R3 builtin ports — each {@link RuntimeSupport}
 * static method exposed via emitter fast-path produces the same
 * output in compiled bytecode as the interpreter would.
 */
class PortedBuiltinsTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(PortedBuiltinsTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static String run(String source) throws Exception {
        byte[] bytes = IrijCompiler.compileSource(source, "irij.Program");
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
        return buf.toString().replace("\r\n", "\n").trim();
    }

    // ── Strings ─────────────────────────────────────────────────────────

    @Test void stringReplace() throws Exception {
        assertEquals("foo bar baz", run("println (replace \"foo-bar-baz\" \"-\" \" \")"));
        assertEquals("xxx", run("println (replace \"aaa\" \"a\" \"x\")"));
    }

    @Test void stringSubstring() throws Exception {
        assertEquals("ell", run("println (substring \"hello\" 1 4)"));
    }

    @Test void stringSplitJoin() throws Exception {
        assertEquals("a|b|c", run("println (join \"|\" (split \"a,b,c\" \",\"))"));
    }

    @Test void stringTrimUpperLower() throws Exception {
        assertEquals("HI", run("println (upper-case (trim \"  hi  \"))"));
        assertEquals("hi", run("println (lower-case \"HI\")"));
    }

    @Test void stringStartsEndsWith() throws Exception {
        assertEquals("true\nfalse", run(
                "println (starts-with? \"foobar\" \"foo\")\n"
              + "println (ends-with? \"foobar\" \"foo\")"));
    }

    @Test void stringIndexOf() throws Exception {
        assertEquals("3", run("println (index-of \"foobar\" \"bar\")"));
        assertEquals("-1", run("println (index-of \"foobar\" \"zzz\")"));
    }

    @Test void urlEncodeDecode() throws Exception {
        assertEquals("hello+world", run("println (url-encode \"hello world\")"));
        assertEquals("hello world", run("println (url-decode \"hello+world\")"));
    }

    // ── Maps ────────────────────────────────────────────────────────────

    @Test void mapAssocGet() throws Exception {
        assertEquals("42", run(
                "m := assoc {} \"k\" 42\n"
              + "println (get \"k\" m)"));
    }

    @Test void mapDissoc() throws Exception {
        assertEquals("()", run(
                "m := assoc (assoc {} \"a\" 1) \"b\" 2\n"
              + "m2 := dissoc m \"a\"\n"
              + "println (get \"a\" m2)"));
    }

    @Test void mapMerge() throws Exception {
        assertEquals("1\n9", run(
                "a := assoc {} \"x\" 1\n"
              + "b := assoc {} \"y\" 9\n"
              + "m := merge a b\n"
              + "println (get \"x\" m)\n"
              + "println (get \"y\" m)"));
    }

    @Test void mapKeysVals() throws Exception {
        // assoc order preserved (LinkedHashMap). Note: Irij's display
        // of strings inside a Vector is unquoted (matches interp).
        assertEquals("#[x y]", run(
                "m := assoc (assoc {} \"x\" 1) \"y\" 2\n"
              + "println (keys m)"));
        assertEquals("#[1 2]", run(
                "m := assoc (assoc {} \"x\" 1) \"y\" 2\n"
              + "println (vals m)"));
    }

    // ── Collections ─────────────────────────────────────────────────────

    @Test void vecContains() throws Exception {
        assertEquals("true\nfalse", run(
                "println (contains? #[1 2 3] 2)\n"
              + "println (contains? #[1 2 3] 9)"));
    }

    @Test void vecLast() throws Exception {
        assertEquals("3", run("println (last #[1 2 3])"));
    }

    @Test void toVecFromSet() throws Exception {
        // to-vec on a vector is identity-ish; on set it gives ordered elements
        assertEquals("#[1 2 3]", run("println (to-vec #[1 2 3])"));
    }

    // ── Misc ────────────────────────────────────────────────────────────

    @Test void notOpAndTypeOf() throws Exception {
        assertEquals("false\ntrue", run(
                "println (not true)\n"
              + "println (not false)"));
        assertEquals("Int\nStr\nMap", run(
                "println (type-of 42)\n"
              + "println (type-of \"hi\")\n"
              + "println (type-of {})"));
    }

    @Test void notEqualOperatorAlias() throws Exception {
        assertEquals("true\nfalse", run(
                "println (1 /= 2)\n"
              + "println (1 /= 1)"));
    }
}
