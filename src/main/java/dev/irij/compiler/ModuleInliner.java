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
import java.util.HashMap;
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
    /** root → the seed name that root provides, resolved lazily. */
    private final java.util.Map<Path, String> seedNames = new HashMap<>();
    private final Set<String> loaded = new HashSet<>();
    private final Set<String> loading = new HashSet<>();
    private final Set<String> aliases = new HashSet<>();

    ModuleInliner(Path sourceRoot) { this(sourceRoot, List.of()); }

    ModuleInliner(Path sourceRoot, List<Path> extraRoots) {
        this.sourceRoot = sourceRoot;
        this.extraRoots = extraRoots == null ? List.of() : extraRoots;
    }

    /**
     * Which seed a resolved root provides.
     *
     * <p>The root's own {@code irij.toml} is authoritative — every
     * published seed and every path dep is a project and has one. The
     * directory-name fallbacks cover a bare directory used as a root:
     * a path dep is {@code …/uzor}, and a registry seed is
     * {@code …/uzor/0.1.12}, so the name is either the last segment or
     * the one above it.
     */
    private String seedNameOf(Path root) {
        return seedNames.computeIfAbsent(root, r -> {
            Path toml = r.resolve("irij.toml");
            if (Files.exists(toml)) {
                try {
                    var meta = dev.irij.module.ProjectFile.parseFile(toml).meta();
                    if (meta != null && meta.name() != null && !meta.name().isBlank()) {
                        return meta.name();
                    }
                } catch (Exception ignored) {
                    // Fall through to the directory-name guesses.
                }
            }
            Path self = r.getFileName();
            return self == null ? "" : self.toString();
        });
    }

    /** True when {@code root} provides the seed {@code name}, allowing
     *  for the {@code <name>/<version>} layout of an installed seed. */
    private boolean rootProvides(Path root, String name) {
        if (name.equals(seedNameOf(root))) return true;
        Path parent = root.getParent();
        return parent != null && parent.getFileName() != null
                && name.equals(parent.getFileName().toString());
    }

    /** Short-name aliases registered via `use` (e.g. "json" for "std.json"). */
    Set<String> aliases() { return aliases; }

    /** fn name → source file it came from. Built during inlining so
     *  the emitter can group functions into per-source-file classes
     *  (multi-class emission → correct {@code SourceFile} in stack
     *  traces). Last definition wins, matching the emitter's
     *  last-wins fn dedup. Keyed by bare fn name; root-program fns
     *  map to {@code rootFile}. */
    private final java.util.Map<String, String> fnFile = new java.util.LinkedHashMap<>();

    java.util.Map<String, String> fnFile() { return fnFile; }

    /** @param rootFile source filename of the top-level program (used
     *  as the origin for its own fns; module fns get their module's
     *  derived file). */
    List<Decl> inline(List<Decl> decls, String rootFile) {
        List<Decl> out = new ArrayList<>();
        expand(decls, out, rootFile != null ? rootFile : "Program.irj");
        return out;
    }

    /** Back-compat: inline without origin tracking. */
    List<Decl> inline(List<Decl> decls) {
        return inline(decls, "Program.irj");
    }

    /** Module qualified name → display source file. {@code vrata.html}
     *  → {@code vrata/html.irj}. Used for the SourceFile attribute. */
    private static String moduleFile(String qualifiedName) {
        return qualifiedName.replace('.', '/') + ".irj";
    }

    private void expand(List<Decl> decls, List<Decl> out, String currentFile) {
        for (Decl d : decls) {
            Decl inner = d instanceof Decl.PubDecl pd && pd.inner() instanceof Decl di ? di : d;
            if (inner instanceof Decl.FnDecl fn) {
                fnFile.put(fn.name(), currentFile);
            }
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
            // (FnDecl origin already recorded above.)
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
            expand(modDecls, out, moduleFile(qualifiedName));
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
            // Only strip the seed prefix against the root that
            // actually provides that seed. `<root>/core.irj` matches
            // for ANY qualified name ending in `.core`, so without
            // this check `use uzor.core` could resolve to butterfly's
            // core.irj — whichever root happened to come first. The
            // failure is silent: the module loads, and every name the
            // caller wanted is simply missing.
            if (stripped != null && rootProvides(root, parts[0])) {
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
