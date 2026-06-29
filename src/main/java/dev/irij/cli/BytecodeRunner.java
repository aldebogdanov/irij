package dev.irij.cli;

import dev.irij.compiler.CompileOptions;
import dev.irij.compiler.IrijCompiler;
import dev.irij.module.DependencyResolver;
import dev.irij.module.ProjectFile;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Run an Irij source file via the bytecode back-end.
 *
 * <p>Replaces the legacy {@code new Interpreter().run(ast)} path
 * for plain top-level execution ({@code irij run}, {@code irij
 * test}, {@code irij eval}, REPL bootstrap). Compiles to a fresh
 * class in a per-call {@link ClassLoader}, invokes {@code main} on
 * an isolated thread-relative System.out, and reports any
 * compile/runtime failures with the original Irij source coordinates
 * (via the SourceFile + LineNumber attributes the emitter writes).
 *
 * <p>Replaced commands:
 * <ul>
 *   <li>{@code irij run file.irj} — top-level program</li>
 *   <li>{@code irij test} — each test-*.irj run in a fresh runner</li>
 *   <li>{@code irij eval "code"} — wraps the snippet in a program</li>
 *   <li>REPL boot — every parsed expression runs through here</li>
 * </ul>
 *
 * <p>What it does NOT do (kept on the Interpreter for now):
 * <ul>
 *   <li>nREPL {@code eval-interp} legacy op (NReplSession)</li>
 *   <li>Playground sandboxed sessions (RuntimeSessions still
 *       creates a sandboxed Interpreter)</li>
 *   <li>MCP server (IrijMcpServer)</li>
 * </ul>
 * Those move in R5b.
 */
public final class BytecodeRunner {

    private BytecodeRunner() {}

    /** Unique class name per run so the classloader can hold many. */
    private static final AtomicInteger COUNTER = new AtomicInteger();

    /**
     * Compile and run {@code sourceFile} via bytecode. If {@code
     * captureOut} is non-null, System.out is redirected there for
     * the duration of the run (used by {@code irij test} to capture
     * each test file's stdout for [ok]/[FAIL] counting). On compile
     * failure throws {@link IrijCompiler.CompileException};
     * propagates any runtime exception thrown by the user program.
     */
    public static void runFile(Path sourceFile, PrintStream captureOut) throws IOException {
        Path projectRoot = sourceFile.toAbsolutePath().getParent();
        Map<String, Path> deps = resolveDeps(projectRoot);
        List<Path> seedRoots = new ArrayList<>(deps.values());

        String className = "irij.CliRun$" + COUNTER.incrementAndGet();
        Map<String, byte[]> classes = IrijCompiler.compileFileMulti(sourceFile, className,
                CompileOptions.defaults(), seedRoots);

        // Run in a fresh classloader so subsequent runs don't see
        // each other's static state (e.g. SpecValidator registry).
        BytesLoader loader = new BytesLoader();
        Class<?> cls = loader.defineAll(classes, className);

        PrintStream prevOut = System.out;
        if (captureOut != null) System.setOut(captureOut);
        try {
            Method main = cls.getMethod("main", String[].class);
            main.invoke(null, (Object) new String[0]);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        } finally {
            if (captureOut != null) System.setOut(prevOut);
        }
    }

    /** Compile + run a source string (no file). Used by {@code
     *  irij eval} and the REPL line evaluator. */
    public static void runSource(String source, String fileLabel, PrintStream captureOut) {
        String className = "irij.CliEval$" + COUNTER.incrementAndGet();
        Map<String, byte[]> classes = IrijCompiler.compileSourceMulti(source, className,
                null, CompileOptions.defaults(), List.of(), fileLabel);
        BytesLoader loader = new BytesLoader();
        Class<?> cls = loader.defineAll(classes, className);

        PrintStream prevOut = System.out;
        if (captureOut != null) System.setOut(captureOut);
        try {
            Method main = cls.getMethod("main", String[].class);
            main.invoke(null, (Object) new String[0]);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        } finally {
            if (captureOut != null) System.setOut(prevOut);
        }
    }

    private static Map<String, Path> resolveDeps(Path projectRoot) throws IOException {
        Path tomlFile = projectRoot.resolve("irij.toml");
        if (!Files.exists(tomlFile)) return Map.of();
        var deps = ProjectFile.parseDeps(tomlFile);
        if (deps.isEmpty()) return Map.of();
        var resolver = new DependencyResolver(projectRoot, System.out);
        return resolver.resolveAll(deps);
    }

    private static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(BytecodeRunner.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }

        /** Define every emitted class, then return the main one.
         *  Cross-class references resolve lazily at link time, so
         *  definition order doesn't matter as long as all are loaded
         *  in the same loader before {@code main} runs. */
        Class<?> defineAll(Map<String, byte[]> classes, String mainName) {
            Class<?> main = null;
            for (var e : classes.entrySet()) {
                Class<?> c = defineClass(e.getKey(), e.getValue(), 0, e.getValue().length);
                if (e.getKey().equals(mainName)) main = c;
            }
            return main;
        }
    }
}
