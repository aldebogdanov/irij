package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multi-class emission (Stage B): inlined module functions land in
 * their own JVM class carrying the module's {@code SourceFile}, so
 * stack frames name the right file. Verifies the class split, the
 * cross-class call path, and that a crash in a module fn reports the
 * module's source file — not the root program's.
 */
class MultiClassEmissionTest {

    /** Loader that defines every emitted class, returns the main one. */
    static final class MultiLoader extends ClassLoader {
        MultiLoader() { super(MultiClassEmissionTest.class.getClassLoader()); }
        Class<?> defineAll(Map<String, byte[]> classes, String main) {
            Class<?> m = null;
            for (var e : classes.entrySet()) {
                Class<?> c = defineClass(e.getKey(), e.getValue(), 0, e.getValue().length);
                if (e.getKey().equals(main)) m = c;
            }
            return m;
        }
    }

    /** Write a tiny project (main.irj + lib/<mod>.irj + irij.toml) to a
     *  temp dir and compile via the file path so the inliner resolves
     *  the module from the local path seed. */
    private Map<String, byte[]> compileProject(Path dir, String mainSrc,
            String modName, String modSrc, String className) throws Exception {
        Files.createDirectories(dir.resolve("lib"));
        Files.writeString(dir.resolve("lib/" + modName + ".irj"), modSrc);
        Files.writeString(dir.resolve("main.irj"), mainSrc);
        return IrijCompiler.compileFileMulti(dir.resolve("main.irj"), className,
                CompileOptions.defaults(),
                java.util.List.of(dir.resolve("lib")));
    }

    @Test void moduleFnLandsInSeparateClass(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        Map<String, byte[]> classes = compileProject(dir,
                "use greeter :open\nprintln (make-greeting \"World\")\n",
                "greeter",
                "mod greeter\npub fn make-greeting :: Str Str\n  (n -> \"Hi, \" ++ n)\n",
                "irij.MCProg1");
        // Main class + one per-module class.
        assertTrue(classes.containsKey("irij.MCProg1"),
                () -> "main class missing: " + classes.keySet());
        assertTrue(classes.keySet().stream().anyMatch(k -> k.contains("greeter")),
                () -> "expected a greeter module class, got: " + classes.keySet());
    }

    @Test void crossModuleCallRunsAndReturns(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        Map<String, byte[]> classes = compileProject(dir,
                "use greeter :open\nprintln (make-greeting \"World\")\n",
                "greeter",
                "mod greeter\npub fn make-greeting :: Str Str\n  (n -> \"Hi, \" ++ n)\n",
                "irij.MCProg2");
        Class<?> main = new MultiLoader().defineAll(classes, "irij.MCProg2");
        PrintStream orig = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf));
        try {
            Method m = main.getMethod("main", String[].class);
            m.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(orig);
        }
        assertEquals("Hi, World", buf.toString().trim());
    }

    @Test void crashInModuleFnNamesModuleSourceFile(
            @org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Map<String, byte[]> classes = compileProject(dir,
                "use greeter :open\nprintln (boom \"x\")\n",
                "greeter",
                "mod greeter\npub fn boom :: Str Str\n  (n -> error ~ \"kaboom\")\n",
                "irij.MCProg3");
        Class<?> main = new MultiLoader().defineAll(classes, "irij.MCProg3");
        Method m = main.getMethod("main", String[].class);
        var ite = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> m.invoke(null, (Object) new String[0]));
        Throwable cause = ite.getTargetException();
        // Walk the frames: the boom frame must point at greeter.irj,
        // and a main frame at main.irj.
        boolean moduleFrame = false, rootFrame = false;
        for (StackTraceElement f : cause.getStackTrace()) {
            if ("boom".equals(f.getMethodName())
                    && "greeter.irj".equals(f.getFileName())) moduleFrame = true;
            if ("main".equals(f.getMethodName())
                    && "main.irj".equals(f.getFileName())) rootFrame = true;
        }
        assertTrue(moduleFrame,
                () -> "expected boom@greeter.irj frame, trace: "
                        + java.util.Arrays.toString(cause.getStackTrace()));
        assertTrue(rootFrame,
                () -> "expected main@main.irj frame, trace: "
                        + java.util.Arrays.toString(cause.getStackTrace()));
    }
}
