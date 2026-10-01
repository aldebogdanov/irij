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

    @Test void hidden_runtime_class_is_not_reachable() throws Exception {
        var p = new ProcessBuilder("true").start();
        p.waitFor();
        assertFalse(JavaInterop.reachable(p.getClass()), p.getClass().getName());
        assertTrue(JavaInterop.reachable(Process.class));
    }

    @Test void override_on_hidden_class_resolves_to_public_declaration() throws Exception {
        var p = new ProcessBuilder("true").start();
        p.waitFor();
        var m = JavaInterop.accessible(p.getClass().getMethod("getInputStream"));
        assertEquals(Process.class, m.getDeclaringClass());
    }

    @Test void reachable_method_is_kept_as_is() throws Exception {
        var m = String.class.getMethod("trim");
        assertEquals(m, JavaInterop.accessible(m));
    }

    @Test void program_reads_a_child_process_output() throws Exception {
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
}
