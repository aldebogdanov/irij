package dev.irij.runtime;

import dev.irij.compiler.CompileOptions;
import dev.irij.compiler.IrijCompiler;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Instance calls on objects whose runtime class the caller cannot reach.
 * {@code ProcessBuilder.start()} returns a {@code java.lang.ProcessImpl},
 * which is package-private in {@code java.base}; its methods must be invoked
 * through {@code Process}, which declares them public.
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
                "p := pb.start ()",
                "stream := p.getInputStream ()",
                "text := String/new (stream.readAllBytes ())",
                "code := p.waitFor ()",
                "println (text.trim ())",
                "println code");
        assertEquals("hi\n0", run(src).replace("\r", ""));
    }

    // ── A method call on a Java object is the JVM effect ────────────────
    // `s.toUpperCase ()` in a fn without `::: JVM` used to run: only
    // `Class/member` sites were checked. The receiver's kind is known only
    // at run time, so javaInstanceRef checks the fn's runtime row.

    @Test void pureFnCallingAJavaMethodIsRejected() throws Exception {
        String src = String.join("\n",
                "fn shout",
                "  => s",
                "  s.toUpperCase ()",
                "match (try (-> shout \"x\"))",
                "  Err msg => println msg",
                "  Ok v => println v");
        assertTrue(run(src).contains("Effect 'JVM' not declared: 'java-interop:.toUpperCase'"));
    }

    @Test void jvmRowAllowsAJavaMethod() throws Exception {
        String src = String.join("\n",
                "fn shout ::: JVM",
                "  => s",
                "  s.toUpperCase ()",
                "println (shout \"x\")");
        assertEquals("X", run(src));
    }

    @Test void recordFieldsAndScopeForksStayPure() throws Exception {
        String src = String.join("\n",
                "fn user-id",
                "  (m -> m.userId)",
                "println (user-id (json-parse \"{\\\"userId\\\": 7}\"))",
                "fn name-of",
                "  (r -> r.name)",
                "println (name-of {name= \"Ada\"})",
                "fn twice",
                "  =>",
                "  n :! 0",
                "  scope s",
                "    f := s.fork (-> 21)",
                "    n <- await f",
                "  n * 2",
                "println (twice ())");
        assertEquals("7\nAda\n42", run(src).replace("\r", ""));
    }
}
