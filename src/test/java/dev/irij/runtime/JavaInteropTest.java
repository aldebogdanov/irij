package dev.irij.runtime;

import dev.irij.compiler.CompileOptions;
import dev.irij.compiler.IrijCompiler;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java instance members ({@code obj#member}), method references
 * ({@code #member}), and {@code .} on a Java object being an error (spec
 * §9.1). Also instance calls on objects whose runtime class the caller
 * cannot reach: {@code ProcessBuilder.start()} returns a
 * {@code java.lang.ProcessImpl}, which is package-private in
 * {@code java.base}; its methods must be invoked through {@code Process},
 * which declares them public.
 */
class JavaInteropTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(JavaInteropTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }

    private static String run(String source) throws Exception {
        byte[] bytes = IrijCompiler.compileSource(source, "irij.Program", null, CompileOptions.defaults());
        PrintStream origOut = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf));
        try {
            var cls = new BytesLoader().define("irij.Program", bytes);
            cls.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(origOut);
        }
        return buf.toString().trim();
    }

    @Test void hiddenRuntimeClassIsNotReachable() throws Exception {
        var p = new ProcessBuilder("true").start();
        p.waitFor();
        assertFalse(JavaInterop.reachable(p.getClass()), p.getClass().getName());
        assertTrue(JavaInterop.reachable(Process.class));
    }

    @Test void overrideOnHiddenClassResolvesToPublicDeclaration() throws Exception {
        var p = new ProcessBuilder("true").start();
        p.waitFor();
        var m = JavaInterop.accessible(p.getClass().getMethod("getInputStream"));
        assertEquals(Process.class, m.getDeclaringClass());
    }

    @Test void reachableMethodIsKeptAsIs() throws Exception {
        var m = String.class.getMethod("trim");
        assertEquals(m, JavaInterop.accessible(m));
    }

    @Test void programReadsAChildProcessOutput() throws Exception {
        String src = String.join("\n",
                "pb := java.lang.ProcessBuilder/new (java.util.List/of \"echo\" \"hi\")",
                "p := pb#start ()",
                "stream := p#getInputStream ()",
                "text := String/new (stream#readAllBytes ())",
                "code := p#waitFor ()",
                "println (text#trim ())",
                "println code");
        assertEquals("hi\n0", run(src).replace("\r", ""));
    }

    private static String failure(String source) {
        var ite = assertThrows(InvocationTargetException.class, () -> run(source));
        Throwable cause = ite.getTargetException();
        String msg = cause.getMessage();
        if (msg == null && cause.getCause() != null) msg = cause.getCause().getMessage();
        return msg == null ? "" : msg;
    }

    @Test void memberCallsAMethodOrReadsAField() throws Exception {
        String src = String.join("\n",
                "println (\"  x  \"#trim ())",
                "println (\"foo-bar\"#startsWith \"foo\")",
                "p := java.awt.Point/new 3 4",
                "println p#x",
                "d := java.time.LocalDate/of 2026 1 1",
                "println ((d#plusDays 30)#toString ())");
        assertEquals("x\ntrue\n3\n2026-01-31", run(src).replace("\r", ""));
    }

    @Test void methodReferenceIsAFunctionOfTheReceiver() throws Exception {
        String src = String.join("\n",
                "println (#[\"ann\" \"bo\"] |> @ #toUpperCase)",
                "println (\"  x  \" |> #trim)",
                "d := java.time.LocalDate/of 2026 1 1",
                "println (#[d] |> @ (#plusDays 30))",
                "starts := #startsWith \"he\"",
                "println (starts \"hello\")",
                "p := java.awt.Point/new 3 4",
                "println (#[p] |> @ #y)");
        assertEquals("#[\"ANN\" \"BO\"]\nx\n#[2026-01-31]\ntrue\n#[4]", run(src).replace("\r", ""));
    }

    @Test void referenceArgumentsAreEvaluatedOnce() throws Exception {
        String src = String.join("\n",
                "n :! 0",
                "fn next :: _ Int",
                "  _ =>",
                "    n <- n + 1",
                "    n",
                "add := #plusDays (next ())",
                "d := java.time.LocalDate/of 2026 1 1",
                "println (#[d d] |> @ add)",
                "println n");
        assertEquals("#[2026-01-02 2026-01-02]\n1", run(src).replace("\r", ""));
    }

    @Test void spaceBeforeHashMakesAReference() throws Exception {
        // `f s#trim` passes the bound method; `f s #trim` passes `s` and
        // the reference.
        String src = String.join("\n",
                "fn call0 :: _ _ ::: JVM",
                "  (m -> m ())",
                "fn call1 :: _ _ _ ::: JVM",
                "  (x f -> f x)",
                "s := \"  x  \"",
                "println (call0 s#trim)",
                "println (call1 s #trim)");
        assertEquals("x\nx", run(src).replace("\r", ""));
    }

    @Test void dotOnAJavaObjectIsAnError() {
        String msg = failure("println (\"  x  \".trim ())");
        assertTrue(msg.contains("`.trim`") && msg.contains("#trim"), msg);
    }

    @Test void hashOnAnIrijValueIsAnError() {
        String msg = failure("println (#[1 2]#size ())");
        assertTrue(msg.contains("#size") && msg.contains("Irij"), msg);
        String ref = failure("println (#[#[1]] |> @ #size)");
        assertTrue(ref.contains("#size") && ref.contains("Irij"), ref);
    }

    @Test void dotStillReadsIrijFields() throws Exception {
        String src = String.join("\n",
                "m := {name= \"ann\"}",
                "println m.name");
        assertEquals("ann", run(src));
    }

    @Test void lambdaCalledFromPureFnIsRefusedAtRunTime() {
        // Written at top level, where JVM is available, so the static
        // check passes; called inside a pure fn, the runtime row refuses.
        String src = String.join("\n",
                "fn apply-pure :: _ Str Str",
                "  (f s -> f s)",
                "trim := (s -> s#trim ())",
                "println (apply-pure trim \"  x  \")");
        String msg = failure(src);
        assertTrue(msg.contains("JVM"), msg);
    }
}
