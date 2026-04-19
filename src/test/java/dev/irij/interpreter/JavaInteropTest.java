package dev.irij.interpreter;

import dev.irij.ast.AstBuilder;
import dev.irij.interpreter.Values.*;
import dev.irij.parser.IrijParseDriver;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for Clojure-style Java interop: Class/member, dot-access on Java objects. */
class JavaInteropTest {

    private Object eval(String source) {
        var baos = new ByteArrayOutputStream();
        var out = new PrintStream(baos);
        var parseResult = IrijParseDriver.parse(source + "\n");
        assertFalse(parseResult.hasErrors(),
            () -> "Parse errors: " + parseResult.errors());
        var ast = new AstBuilder().build(parseResult.tree());
        var interp = new Interpreter(out);
        interp.setSpecLintEnabled(false);
        return interp.run(ast);
    }

    // ── Class resolution ────────────────────────────────────────────

    @Test void langAutoImported() {
        assertEquals(String.class, JavaInterop.resolveClass("String"));
        assertEquals(Math.class,   JavaInterop.resolveClass("Math"));
        assertEquals(System.class, JavaInterop.resolveClass("System"));
    }

    @Test void fullyQualifiedClass() {
        assertEquals(java.util.UUID.class,     JavaInterop.resolveClass("java.util.UUID"));
        assertEquals(java.time.Instant.class,  JavaInterop.resolveClass("java.time.Instant"));
    }

    @Test void unknownClassThrows() {
        assertThrows(IrijRuntimeError.class, () -> JavaInterop.resolveClass("com.nope.Missing"));
    }

    // ── Static method calls ─────────────────────────────────────────

    @Test void mathAbsPreservesLong() {
        assertEquals(7L, eval("Math/abs (-7)"));
    }

    @Test void mathMaxPicksLongOverload() {
        assertEquals(5L, eval("Math/max 3 5"));
    }

    @Test void mathPowReturnsDouble() {
        assertEquals(8.0, eval("Math/pow 2.0 3.0"));
    }

    @Test void longParseLong() {
        assertEquals(42L, eval("java.lang.Long/parseLong \"42\""));
    }

    @Test void systemGetenvReturnsString() {
        var result = eval("System/getenv \"PATH\"");
        assertTrue(result instanceof String || result == Values.UNIT);
    }

    // ── Static field reads ──────────────────────────────────────────

    @Test void staticFieldRead() {
        var v = eval("Math/PI");
        assertTrue(v instanceof Double);
        assertTrue((Double) v > 3.0);
    }

    // ── Instance methods via dot-access ─────────────────────────────

    @Test void stringToUpperCase() {
        assertEquals("HELLO", eval("\"hello\".toUpperCase ()"));
    }

    @Test void stringLength() {
        assertEquals(5L, eval("\"hello\".length ()"));
    }

    @Test void stringTrim() {
        assertEquals("x", eval("\"   x   \".trim ()"));
    }

    @Test void stringStartsWith() {
        assertEquals(true, eval("\"hello\".startsWith \"he\""));
    }

    // ── Constructors via Class/new ──────────────────────────────────

    @Test void randomSeededIsDeterministic() {
        var source = """
            r1 := java.util.Random/new 42
            r2 := java.util.Random/new 42
            a := r1.nextInt 1000
            b := r2.nextInt 1000
            a == b
            """;
        assertEquals(true, eval(source));
    }

    @Test void stringBuilderChain() {
        var source = """
            sb := java.lang.StringBuilder/new ()
            a := sb.append "a"
            b := sb.append "b"
            c := sb.append "c"
            sb.toString ()
            """;
        assertEquals("abc", eval(source));
    }

    // ── Coercion ────────────────────────────────────────────────────

    @Test void longToIntCoercion() {
        assertEquals(3L, eval("Math/floorDiv 10 3"));
    }

    @Test void unknownMethodThrows() {
        assertThrows(IrijRuntimeError.class, () -> eval("Math/nonsenseXYZ 1"));
    }

    @Test void unitTreatedAsZeroArgs() {
        // .toUpperCase () should invoke the 0-arg overload, not toUpperCase(Locale)
        assertEquals("HELLO", eval("\"hello\".toUpperCase ()"));
    }

    @Test void classNewZeroArg() {
        // Objects auto-converted via javaToIrij: ArrayList → IrijVector
        var v = eval("java.util.ArrayList/new ()");
        assertTrue(v instanceof IrijVector);
        assertEquals(0, ((IrijVector) v).elements().size());
    }

    @Test void opaqueJavaObjectRoundtrip() {
        // Random has no Java→Irij conversion → returned as opaque object
        var v = eval("java.util.Random/new 1");
        assertTrue(v instanceof java.util.Random);
    }

    // ── javaToIrij conversion ───────────────────────────────────────

    @Test void javaListToIrijVector() {
        var jlist = List.of("a", "b", "c");
        var v = JavaInterop.javaToIrij(jlist);
        assertTrue(v instanceof IrijVector);
        assertEquals(List.of("a","b","c"), ((IrijVector) v).elements());
    }

    @Test void javaArrayToIrijVector() {
        var arr = new int[]{1, 2, 3};
        var v = JavaInterop.javaToIrij(arr);
        assertTrue(v instanceof IrijVector);
        assertEquals(List.of(1L, 2L, 3L), ((IrijVector) v).elements());
    }

    @Test void javaNullToUnit() {
        assertEquals(Values.UNIT, JavaInterop.javaToIrij(null));
    }
}
