package dev.irij.compiler;

import dev.irij.ast.AstBuilder;
import dev.irij.ast.Decl;
import dev.irij.parser.IrijParseDriver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Preprocess: resolve `use X.Y` by reading the module source (classpath
 * {@code std/*.irj} or {@code <sourceRoot>/X/Y.irj}), parsing it, and
 * inlining its declarations ahead of the current program's. ModDecl and
 * UseDecl are stripped; PubDecl is unwrapped. Each module is loaded once.
 *
 * <p>Also collects short-name aliases (last segment of each qualified name)
 * so the emitter can resolve {@code mod.fn x} to {@code fn x}.
 */
final class ModuleInliner {

    private final Path sourceRoot;
    private final Set<String> loaded = new HashSet<>();
    private final Set<String> loading = new HashSet<>();
    private final Set<String> aliases = new HashSet<>();

    ModuleInliner(Path sourceRoot) { this.sourceRoot = sourceRoot; }

    /** Short-name aliases registered via `use` (e.g. "json" for "std.json"). */
    Set<String> aliases() { return aliases; }

    List<Decl> inline(List<Decl> decls) {
        List<Decl> out = new ArrayList<>();
        expand(decls, out);
        return out;
    }

    private void expand(List<Decl> decls, List<Decl> out) {
        for (Decl d : decls) {
            Decl inner = d instanceof Decl.PubDecl pd && pd.inner() instanceof Decl di ? di : d;
            if (inner instanceof Decl.ModDecl) continue;
            if (inner instanceof Decl.UseDecl ud) {
                // Register alias (last segment).
                String[] parts = ud.qualifiedName().split("\\.");
                aliases.add(parts[parts.length - 1]);
                loadAndInline(ud.qualifiedName(), out);
                continue;
            }
            // Unwrap PubDecl for the emitter's benefit (treat pub fn as fn).
            if (d instanceof Decl.PubDecl pd && pd.inner() instanceof Decl di) {
                out.add(di);
            } else {
                out.add(d);
            }
        }
    }

    private void loadAndInline(String qualifiedName, List<Decl> out) {
        if (!loaded.add(qualifiedName)) return;
        if (!loading.add(qualifiedName)) {
            throw new IrijCompiler.CompileException(
                    "Circular module dependency: " + qualifiedName);
        }
        try {
            String source = readSource(qualifiedName);
            var parsed = IrijParseDriver.parse(source);
            if (parsed.hasErrors()) {
                throw new IrijCompiler.CompileException(
                        "Parse errors in module '" + qualifiedName + "': "
                                + String.join("\n", parsed.errors()));
            }
            List<Decl> modDecls = new AstBuilder().build(parsed.tree());
            expand(modDecls, out);
        } finally {
            loading.remove(qualifiedName);
        }
    }

    private String readSource(String qualifiedName) {
        String resourcePath = qualifiedName.replace('.', '/') + ".irj";
        ClassLoader cl = getClass().getClassLoader();
        try (InputStream is = cl.getResourceAsStream(resourcePath)) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IrijCompiler.CompileException(
                    "Error reading module resource '" + qualifiedName + "': " + e.getMessage());
        }
        if (sourceRoot != null) {
            Path p = sourceRoot.resolve(qualifiedName.replace('.', '/') + ".irj");
            if (Files.exists(p)) {
                try {
                    return Files.readString(p, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IrijCompiler.CompileException(
                            "Error reading module file '" + p + "': " + e.getMessage());
                }
            }
        }
        throw new IrijCompiler.CompileException(
                "Module not found: " + qualifiedName
                        + " (searched classpath + " + sourceRoot + ")");
    }
}
