package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hot-redef via {@code invokedynamic} + {@link java.lang.invoke.MutableCallSite}.
 *
 * <p>Verifies that a fn compiled without {@code --direct-linking} can have
 * its implementation swapped at runtime via
 * {@link RuntimeSupport#redefine(String, MethodHandle)} — and that
 * subsequent calls through the indy site dispatch to the new impl.
 */
class HotRedefTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(HotRedefTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static Class<?> compileAndLoad(String source, String className,
                                           CompileOptions opts) throws Exception {
        byte[] bytes = IrijCompiler.compileSource(source, className, null, opts);
        return new BytesLoader().define(className, bytes);
    }

    private static String runMain(Class<?> cls) throws Exception {
        PrintStream origOut = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf));
        try {
            Method main = cls.getMethod("main", String[].class);
            main.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(origOut);
        }
        return buf.toString().trim();
    }

    @Test void redef_swaps_top_level_fn_impl() throws Exception {
        // Compile a small program that calls `greet` from `main`. Default
        // build (directLinking=false) emits an indy site through redefBootstrap.
        String src = """
            fn greet
              _ => println "hi"
            greet ()
            """;
        Class<?> cls = compileAndLoad(src, "irij.HotRedef1",
                CompileOptions.defaults());

        // First call → original impl prints "hi" and registers the site.
        assertEquals("hi", runMain(cls));

        // Build a replacement MethodHandle: a static helper that returns
        // null but prints "yo!" first. We install it onto the registered
        // site by key "owner.method:descriptor".
        MethodHandle replacement = MethodHandles.lookup()
                .findStatic(HotRedefTest.class, "greetReplacement",
                        MethodType.methodType(Object.class, Object.class));
        String key = cls.getName() + ".greet:(Ljava/lang/Object;)Ljava/lang/Object;";
        assertTrue(RuntimeSupport.redefine(key, replacement),
                "redefine() should find the registered site");

        // Subsequent call → swapped impl.
        assertEquals("yo!", runMain(cls));

        // Restore so a re-run of the test sees the original (defensive).
        MethodHandle original = MethodHandles.lookup()
                .findStatic(cls, "greet",
                        MethodType.methodType(Object.class, Object.class));
        RuntimeSupport.redefine(key, original);
    }

    @Test void direct_linking_skips_indy_so_redef_has_no_effect() throws Exception {
        // Compile the same program with --direct-linking. The call site is
        // a plain invokestatic; redefine() finds no site for that key.
        String src = """
            fn greet
              _ => println "hi-direct"
            greet ()
            """;
        Class<?> cls = compileAndLoad(src, "irij.HotRedef2",
                CompileOptions.defaults().withDirectLinking(true));
        assertEquals("hi-direct", runMain(cls));

        String key = cls.getName() + ".greet:(Ljava/lang/Object;)Ljava/lang/Object;";
        assertFalse(RuntimeSupport.redefine(key,
                        MethodHandles.constant(Object.class, null)),
                "no MutableCallSite registered under direct-linking");

        // Direct-linked call still produces the original output.
        assertEquals("hi-direct", runMain(cls));
    }

    /** Replacement implementation for {@code greet/1} — prints "yo!". */
    @SuppressWarnings("unused")
    public static Object greetReplacement(Object arg) {
        System.out.println("yo!");
        return null;
    }
}
