package dev.irij.compiler;

import dev.irij.ast.AstBuilder;
import dev.irij.ast.Decl;
import dev.irij.parser.IrijParseDriver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Entry point for Irij bytecode compilation (MVP spike).
 *
 * Usage: parses Irij source, produces a JVM .class whose `main` method
 * runs the top-level expressions.
 */
public final class IrijCompiler {

    private IrijCompiler() {}

    /** Compile Irij source string to a classfile byte[]. */
    public static byte[] compileSource(String source, String className) {
        return compileSource(source, className, null, CompileOptions.defaults());
    }

    /** Back-compat: compile with a source root (default options). */
    public static byte[] compileSource(String source, String className, Path sourceRoot) {
        return compileSource(source, className, sourceRoot, CompileOptions.defaults());
    }

    /**
     * Compile with a source root + explicit options.
     * {@code sourceRoot} resolves relative `use` modules that aren't on the
     * classpath. {@code opts} selects the handler lowering strategy (14c.2
     * threaded vs 14c.3 state-machine).
     */
    public static byte[] compileSource(String source, String className,
                                        Path sourceRoot, CompileOptions opts) {
        return compileSource(source, className, sourceRoot, opts, List.of());
    }

    /**
     * Compile with extra module-search roots — typically resolved seed
     * directories from {@link dev.irij.module.DependencyResolver}. Each
     * root is searched after the classpath and {@code sourceRoot}, in
     * order, until {@code use mod.X} matches.
     */
    public static byte[] compileSource(String source, String className,
                                        Path sourceRoot, CompileOptions opts,
                                        List<Path> seedRoots) {
        return compileSource(source, className, sourceRoot, opts, seedRoots, null);
    }

    /** Compile with an explicit Irij source-file name for the JVM
     *  {@code SourceFile} attribute. When supplied, stack traces from
     *  the emitted class show as
     *  {@code at irij.Program.main(server.irj:42)}. */
    public static byte[] compileSource(String source, String className,
                                        Path sourceRoot, CompileOptions opts,
                                        List<Path> seedRoots, String sourceFile) {
        var parsed = IrijParseDriver.parse(source);
        if (parsed.hasErrors()) {
            throw new CompileException("Parse errors:\n" + String.join("\n", parsed.errors()));
        }
        List<Decl> decls = new AstBuilder().build(parsed.tree());
        return compileDecls(decls, className, sourceRoot, opts, seedRoots, sourceFile);
    }

    /** Compile a pre-parsed (and possibly rewritten) decl list. Lets
     *  callers like {@link BytecodeSession} transform the AST before
     *  emission — e.g. capture the last top-level expression's value
     *  into the session namespace so Playground / REPL can surface
     *  it.
     *
     *  <p>Returns only the main class's bytes. Use {@link
     *  #compileDeclsMulti} when the program may inline modules (so the
     *  loader can define the per-source-file classes too). This
     *  single-byte form is safe for module-free snippets — most test
     *  callers — where the main class is the only one emitted. */
    public static byte[] compileDecls(List<Decl> decls, String className,
                                       Path sourceRoot, CompileOptions opts,
                                       List<Path> seedRoots, String sourceFile) {
        return compileDeclsMulti(decls, className, sourceRoot, opts, seedRoots, sourceFile)
                .get(className);
    }

    /** Multi-class compile: returns every emitted class keyed by its
     *  dotted binary name. The main class is keyed by {@code className}.
     *  Loaders must define all entries before invoking {@code main}. */
    public static Map<String, byte[]> compileDeclsMulti(List<Decl> decls, String className,
                                       Path sourceRoot, CompileOptions opts,
                                       List<Path> seedRoots, String sourceFile) {
        // Resolve the root source-file name ONCE so the inliner (which
        // stamps each root fn's origin) and the emitter (which derives
        // the root class's SourceFile) agree. A mismatch would split
        // root fns into a phantom per-file class.
        String rootFile = sourceFile != null ? sourceFile
                : (className.substring(className.lastIndexOf('.') + 1) + ".irj");
        var inliner = new ModuleInliner(sourceRoot, seedRoots);
        List<Decl> inlined = inliner.inline(decls, rootFile);
        EffectRowChecker.check(inlined);
        return new ClassEmitter(className, inliner.aliases(), opts, rootFile, inliner.fnFile())
                .emitProgram(inlined);
    }

    /** Multi-class compile from source text. */
    public static Map<String, byte[]> compileSourceMulti(String source, String className,
                                        Path sourceRoot, CompileOptions opts,
                                        List<Path> seedRoots, String sourceFile) {
        var parsed = IrijParseDriver.parse(source);
        if (parsed.hasErrors()) {
            throw new CompileException("Parse errors:\n" + String.join("\n", parsed.errors()));
        }
        List<Decl> decls = new AstBuilder().build(parsed.tree());
        return compileDeclsMulti(decls, className, sourceRoot, opts, seedRoots, sourceFile);
    }

    /** Multi-class compile from a file. */
    public static Map<String, byte[]> compileFileMulti(Path path, String className,
                                        CompileOptions opts, List<Path> seedRoots)
            throws IOException {
        return compileSourceMulti(Files.readString(path), className,
                path.toAbsolutePath().getParent(), opts, seedRoots,
                path.getFileName().toString());
    }

    /** Compile an Irij file to a classfile byte[]. */
    public static byte[] compileFile(Path path, String className) throws IOException {
        return compileFile(path, className, CompileOptions.defaults());
    }

    public static byte[] compileFile(Path path, String className, CompileOptions opts)
            throws IOException {
        return compileFile(path, className, opts, List.of());
    }

    public static byte[] compileFile(Path path, String className, CompileOptions opts,
                                      List<Path> seedRoots) throws IOException {
        return compileSource(Files.readString(path), className,
                path.toAbsolutePath().getParent(), opts, seedRoots,
                path.getFileName().toString());
    }

    public static final class CompileException extends RuntimeException {
        public CompileException(String msg) { super(msg); }
        public CompileException(String msg, Throwable cause) { super(msg, cause); }
    }
}
