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
    /** Extra roots to search for `use mod.X` after the classpath and
     *  the primary {@link #sourceRoot}. Used to point at resolved
     *  seed directories (e.g. {@code ~/.irij/seeds/vrata/0.1.3}) so a
     *  bytecode build can inline `use vrata.html`. The list is
     *  searched in declaration order; first match wins. */
    private final List<Path> extraRoots;
    private final Set<String> loaded = new HashSet<>();
    private final Set<String> loading = new HashSet<>();
    private final Set<String> aliases = new HashSet<>();

    ModuleInliner(Path sourceRoot) { this(sourceRoot, List.of()); }

    ModuleInliner(Path sourceRoot, List<Path> extraRoots) {
        this.sourceRoot = sourceRoot;
        this.extraRoots = extraRoots == null ? List.of() : extraRoots;
    }

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
            // ModDecls are preserved so downstream passes (notably
            // EffectRowChecker) can determine which module each fn
            // came from — needed for stdlib-only escape hatches like
            // `::: Any`. The emitter skips them.
            if (inner instanceof Decl.ModDecl) {
                out.add(inner);
                continue;
            }
            if (inner instanceof Decl.UseDecl ud) {
                // Register alias based on the use modifier.
                //
                //   use mod.path :open       → no alias; flatten exports
                //   use mod.path :as foo     → alias `foo`
                //   use mod.path {names}     → no alias; selective
                //   use mod.path             → REJECTED — was the
                //     implicit last-segment alias; ambiguous when
                //     two modules end in the same name. v0.6.4+
                //     requires an explicit modifier.
                Decl.UseModifier um = ud.modifier();
                if (um == null) {
                    throw new IrijCompiler.CompileException(
                            "`use " + ud.qualifiedName() + "` requires an "
                                    + "explicit modifier: `:open` (flatten), "
                                    + "`:as <alias>` (rename), or "
                                    + "`{ name name ... }` (selective)");
                }
                if (um instanceof Decl.UseModifier.As asMod) {
                    aliases.add(asMod.alias());
                }
                // `:open` and `:selective` paths don't register an alias.
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
        // Extra roots — typically resolved seed directories. The
        // module-prefix for a seed lives at <seedRoot>/<name>.irj
        // (no per-seed dotted dir). For `use vrata.html` we try
        // <root>/vrata/html.irj first; then, for single-segment
        // seed roots like ~/.irij/seeds/vrata/0.1.3/, also try
        // <root>/html.irj (stripping the leading "vrata.").
        String relative = qualifiedName.replace('.', '/') + ".irj";
        String[] parts = qualifiedName.split("\\.", 2);
        String stripped = parts.length == 2 ? parts[1].replace('.', '/') + ".irj" : null;
        for (Path root : extraRoots) {
            Path p = root.resolve(relative);
            if (Files.exists(p)) {
                try {
                    return Files.readString(p, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IrijCompiler.CompileException(
                            "Error reading module file '" + p + "': " + e.getMessage());
                }
            }
            if (stripped != null) {
                Path q = root.resolve(stripped);
                if (Files.exists(q)) {
                    try {
                        return Files.readString(q, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new IrijCompiler.CompileException(
                                "Error reading module file '" + q + "': " + e.getMessage());
                    }
                }
            }
        }
        throw new IrijCompiler.CompileException(
                "Module not found: " + qualifiedName
                        + " (searched classpath + " + sourceRoot
                        + (extraRoots.isEmpty() ? "" : " + " + extraRoots) + ")");
    }
}
