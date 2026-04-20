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
        return compileSource(source, className, null);
    }

    /**
     * Compile with a source root (used to resolve relative `use` modules that
     * aren't on the classpath).
     */
    public static byte[] compileSource(String source, String className, Path sourceRoot) {
        var parsed = IrijParseDriver.parse(source);
        if (parsed.hasErrors()) {
            throw new CompileException("Parse errors:\n" + String.join("\n", parsed.errors()));
        }
        List<Decl> decls = new AstBuilder().build(parsed.tree());
        var inliner = new ModuleInliner(sourceRoot);
        List<Decl> inlined = inliner.inline(decls);
        return new ClassEmitter(className, inliner.aliases()).emit(inlined);
    }

    /** Compile an Irij file to a classfile byte[]. */
    public static byte[] compileFile(Path path, String className) throws IOException {
        return compileSource(Files.readString(path), className,
                path.toAbsolutePath().getParent());
    }

    public static final class CompileException extends RuntimeException {
        public CompileException(String msg) { super(msg); }
        public CompileException(String msg, Throwable cause) { super(msg, cause); }
    }
}
