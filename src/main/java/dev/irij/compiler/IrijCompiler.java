package dev.irij.compiler;

import dev.irij.ast.AstBuilder;
import dev.irij.ast.Decl;
import dev.irij.parser.IrijParseDriver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        if (opts.handlerStrategy() == CompileOptions.HandlerStrategy.STATE_MACHINE) {
            throw new CompileException(
                    "handler-strategy=state-machine (14c.3) is not yet implemented");
        }
        var parsed = IrijParseDriver.parse(source);
        if (parsed.hasErrors()) {
            throw new CompileException("Parse errors:\n" + String.join("\n", parsed.errors()));
        }
        List<Decl> decls = new AstBuilder().build(parsed.tree());
        var inliner = new ModuleInliner(sourceRoot);
        List<Decl> inlined = inliner.inline(decls);
        return new ClassEmitter(className, inliner.aliases(), opts).emit(inlined);
    }

    /** Compile an Irij file to a classfile byte[]. */
    public static byte[] compileFile(Path path, String className) throws IOException {
        return compileFile(path, className, CompileOptions.defaults());
    }

    public static byte[] compileFile(Path path, String className, CompileOptions opts)
            throws IOException {
        return compileSource(Files.readString(path), className,
                path.toAbsolutePath().getParent(), opts);
    }

    public static final class CompileException extends RuntimeException {
        public CompileException(String msg) { super(msg); }
        public CompileException(String msg, Throwable cause) { super(msg, cause); }
    }
}
