package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bytecode-mode user-declared spec tests. Verifies that product
 * specs ({@code spec Point { x y }}) and sum specs ({@code spec Shape
 * Circle Float | Rect Float Float}) are validated at fn boundaries
 * via the clinit-populated SpecValidator registry.
 */
class UserSpecTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(UserSpecTest.class.getClassLoader()); }
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
        return buf.toString().trim();
    }

    private static void expectFailure(String source, String fragment) {
        InvocationTargetException ite = assertThrows(
                InvocationTargetException.class, () -> run(source));
        Throwable cause = ite.getTargetException();
        String raw = cause.getMessage();
        if (raw == null && cause.getCause() != null) raw = cause.getCause().getMessage();
        final String msg = raw;
        assertTrue(msg != null && msg.contains(fragment),
                () -> "expected '" + fragment + "' in error, got: " + msg);
    }

    // ── Sum-spec input ──────────────────────────────────────────────────

    @Test
    void sumSpecInputAccepted() throws Exception {
        // Two variants: any tagged value with one of these tags + arity passes.
        String src = """
            spec Shape
              Circle Float
              Rect   Float Float

            fn describe :: Shape Str
              Circle r => "circle"
              Rect w h => "rect"

            println (describe (Circle 3.0))
            println (describe (Rect 4.0 5.0))
            """;
        assertEquals("circle\nrect", run(src).replace("\r\n", "\n"));
    }

    @Test
    void sumSpecInputRejectsWrongTag() {
        // Try to pass a foreign tagged value where Shape is expected.
        expectFailure("""
            spec Shape
              Circle Float
              Rect   Float Float

            spec Other
              Foo

            fn describe :: Shape Str
              Circle r => "circle"
              Rect w h => "rect"
              _ => "?"

            println (describe Foo)
            """, "not a variant of Shape");
    }

    @Test
    void sumSpecRegistryIsPopulated() throws Exception {
        // Compile a source that declares a sum spec, load class, then
        // verify the registry knows about it.
        byte[] bytes = IrijCompiler.compileSource("""
            spec Color
              Red
              Green
              Blue
            """, "irij.SpecReg");
        Class<?> cls = new BytesLoader().define("irij.SpecReg", bytes);
        cls.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        var d = SpecValidator.lookup("Color");
        assertTrue(d instanceof SpecValidator.Descriptor.Sum,
                () -> "expected Sum descriptor, got " + d);
    }

    // ── Product-spec input ──────────────────────────────────────────────

    @Test
    void productSpecInputAccepted() throws Exception {
        String src = """
            spec Point
              x :: Float
              y :: Float

            fn show :: Point Str
              (p -> "ok")

            println (show (Point 1.0 2.0))
            """;
        assertEquals("ok", run(src));
    }

    @Test
    void productSpecInputRejectsNonProduct() {
        expectFailure("""
            spec Point
              x :: Float
              y :: Float

            fn show :: Point Str
              (p -> "ok")

            println (show 42)
            """, "cannot validate Int as Point");
    }

    // ── Product fields: types and closedness ────────────────────────────
    //
    // The declared field specs used to be parsed and then dropped at
    // registration, so a product checked field *presence* only: a
    // `Str` satisfied `x :: Int`, and any value carrying a superset of
    // the names passed. Both are now checked.

    private static final String POINT = """
            spec Point
              x :: Int
              y :: Int

            fn show :: Point Str
              (p -> "ok")

            """;

    @Test
    void productFieldTypeIsChecked() {
        expectFailure(POINT + "println (show {x= \"nope\" y= 2})",
                "Point field 'x': expected Int, got Str");
    }

    @Test
    void productIsClosedToExtraFields() {
        // Not a superset — a record carrying the right names plus more
        // is a different shape, and letting it pass means the spec
        // doesn't actually pin down what it names.
        expectFailure(POINT + "println (show {x= 1 y= 2 z= 3})",
                "Point has no field 'z'");
    }

    @Test
    void exactProductPasses() throws Exception {
        assertEquals("ok", run(POINT + "println (show {x= 1 y= 2})"));
    }

    @Test
    void missingFieldStillReported() {
        expectFailure(POINT + "println (show {x= 1})", "Point requires field 'y'");
    }

    /**
     * The constructor is the one place a product value is built, and
     * validation downstream fast-paths on a matching specName — so an
     * unchecked constructor would be a hole straight through every
     * later check.
     */
    @Test
    void constructorCertifiesItsFields() {
        expectFailure(POINT + "v := Point \"nope\" 2\nprintln (show v)",
                "Point field 'x': expected Int, got Str");
    }

    /** Field specs are full SpecExprs, not just primitive names. */
    @Test
    void collectionFieldValidatesElements() {
        expectFailure("""
            spec Bag
              items :: #[Int]

            fn show :: Bag Str
              (b -> "ok")

            println (show {items= #[1 "two"]})
            """, "Bag field 'items': expected Int, got Str");
    }

    @Test
    void nestedProductFieldRecurses() {
        expectFailure("""
            spec Inner
              n :: Int
            spec Outer
              inner :: Inner

            fn show :: Outer Str
              (o -> "ok")

            println (show {inner= {n= "nope"}})
            """, "Outer field 'inner': Inner field 'n': expected Int, got Str");
    }

    /** A wildcard field is declared-but-unconstrained, as everywhere else. */
    @Test
    void wildcardFieldAcceptsAnything() throws Exception {
        assertEquals("ok", run("""
            spec Box
              v :: _

            fn show :: Box Str
              (b -> "ok")

            println (show {v= "anything"})
            """));
    }
}
