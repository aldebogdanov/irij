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
        var parsed = IrijParseDriver.parse(source);
        if (parsed.hasErrors()) {
            throw new CompileException("Parse errors:\n" + String.join("\n", parsed.errors()));
        }
        List<Decl> decls = new AstBuilder().build(parsed.tree());
        return new ClassEmitter(className).emit(decls);
    }

    /** Compile an Irij file to a classfile byte[]. */
    public static byte[] compileFile(Path path, String className) throws IOException {
        return compileSource(Files.readString(path), className);
    }

    public static final class CompileException extends RuntimeException {
        public CompileException(String msg) { super(msg); }
        public CompileException(String msg, Throwable cause) { super(msg, cause); }
    }
}
