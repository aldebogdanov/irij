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
 * <p>Each file's names are resolved against its own {@code use} lines
 * ({@link ModuleScope}) before its decls join the flat program, so a file
 * reaches exactly what it declares, imports, or has as a builtin.
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

    /** Receives spec-lint warnings; null = lint off. */
    private final java.util.function.Consumer<String> specLint;

    ModuleInliner(Path sourceRoot) { this(sourceRoot, List.of()); }

    ModuleInliner(Path sourceRoot, List<Path> extraRoots) {
        this(sourceRoot, extraRoots, null);
    }

    ModuleInliner(Path sourceRoot, List<Path> extraRoots,
                  java.util.function.Consumer<String> specLint) {
        this(sourceRoot, extraRoots, specLint, Set.of());
    }

    /** @param sessionNames top-level names earlier evals of a REPL session
     *                     defined: the program's own, though not in its decls */
    ModuleInliner(Path sourceRoot, List<Path> extraRoots,
                  java.util.function.Consumer<String> specLint, Set<String> sessionNames) {
        this.sourceRoot = sourceRoot;
        this.extraRoots = extraRoots == null ? List.of() : extraRoots;
        this.specLint = specLint;
        this.sessionNames = sessionNames == null ? Set.of() : sessionNames;
    }

    private final Set<String> sessionNames;

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

    /** fn name → source file it came from. Built during inlining so
     *  the emitter can group functions into per-source-file classes
     *  (multi-class emission → correct {@code SourceFile} in stack
     *  traces). Last definition wins, matching the emitter's
     *  last-wins fn dedup. Keyed by bare fn name; root-program fns
     *  map to {@code rootFile}. */
    private final java.util.Map<String, String> fnFile = new java.util.LinkedHashMap<>();

    java.util.Map<String, String> fnFile() { return fnFile; }

    /** Each loaded module's exports, in load order. */
    private final java.util.Map<String, ModuleScope.Exports> exportsByModule = new java.util.LinkedHashMap<>();

    /** The program's own top-level names: no module may reach them. */
    private Set<String> programNames = Set.of();

    /** @param rootFile source filename of the top-level program (used
     *  as the origin for its own fns; module fns get their module's
     *  derived file). */
    List<Decl> inline(List<Decl> decls, String rootFile) {
        List<Decl> out = new ArrayList<>();
        programNames = ModuleScope.ownNames(decls);
        decls = ModulePrivacy.privatizeProgram(decls);
        Set<String> own = new HashSet<>(ModuleScope.ownNames(decls));
        own.addAll(sessionNames);
        expand(decls, out, rootFile != null ? rootFile : "Program.irj", null, own, null);
        return out;
    }

    /** What a module passes on with {@code pub use}. */
    private record ReExports(java.util.Map<String, String> values,
                             java.util.Map<String, List<String>> types,
                             java.util.Map<String, String> kinds) {
        ReExports() { this(new HashMap<>(), new java.util.LinkedHashMap<>(), new HashMap<>()); }
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

    /** Inlines one file: the modules it uses first, then its own decls with
     *  every name resolved against its imports. {@code module} is null for
     *  the program; {@code reexports} collects a module's {@code pub use}s. */
    private void expand(List<Decl> decls, List<Decl> out, String currentFile, String module,
                        Set<String> own, ReExports reexports) {
        ModuleScope.Imports imports = new ModuleScope.Imports();
        List<Decl> body = new ArrayList<>(decls.size());
        for (Decl d : decls) {
            boolean isPub = d instanceof Decl.PubDecl;
            Decl inner = d instanceof Decl.PubDecl pd && pd.inner() instanceof Decl di ? di : d;
            if (!(inner instanceof Decl.UseDecl ud)) {
                body.add(d);
                continue;
            }
            //   use mod.path :open    → every pub name, bare
            //   use mod.path :as foo  → foo.name
            //   use mod.path {names}  → those names, bare
            //   use mod.path          → REJECTED: the implicit last-segment
            //     alias collided across modules ending in the same name.
            Decl.UseModifier um = ud.modifier();
            if (um == null) {
                throw new IrijCompiler.CompileException(
                        "`use " + ud.qualifiedName() + "` requires an "
                                + "explicit modifier: `:open` (flatten), "
                                + "`:as <alias>` (rename), or "
                                + "`{ name name ... }` (selective)");
            }
            loadAndInline(ud.qualifiedName(), out);
            ModuleScope.Exports ex = exportsByModule.get(ud.qualifiedName());
            imports.add(um, ex, currentFile, ud.loc());
            if (isPub) {
                if (reexports == null) {
                    throw new IrijCompiler.CompileException("`pub use " + ud.qualifiedName()
                            + "` re-exports from a module; the program has no importers"
                            + ModuleScope.at(currentFile, ud.loc()));
                }
                ModuleScope.Imports.reexport(um, ex, reexports.values(), reexports.types(),
                        reexports.kinds(), currentFile, ud.loc());
            }
        }
        var cx = new ModuleScope.Context(currentFile, module, imports, own,
                exportsByModule.values(), programNames);
        for (Decl d : ModuleScope.resolve(body, cx)) {
            Decl inner = d instanceof Decl.PubDecl pd && pd.inner() instanceof Decl di ? di : d;
            if (inner instanceof Decl.FnDecl fn) {
                fnFile.put(fn.name(), currentFile);
                // Spec-lint: every pub fn must carry a spec (`_` where the
                // shape is undetermined) — project policy, see specs.md.
                if (specLint != null && d instanceof Decl.PubDecl
                        && !(fn.body() instanceof Decl.FnBody.NoBody)
                        && (fn.specAnnotations() == null || fn.specAnnotations().isEmpty())) {
                    specLint.accept("warning: pub fn '" + ClassEmitter.displayName(fn.name()) + "' in " + currentFile
                            + " has no spec annotation"
                            + (fn.loc() != null ? " (" + fn.loc() + ")" : ""));
                }
            }
            // ModDecls are preserved so downstream passes (notably
            // EffectRowChecker) can determine which module each fn
            // came from — needed for stdlib-only escape hatches like
            // `::: Any`. The emitter skips them. The PubDecl wrapper goes:
            // the emitter treats a pub fn as a fn.
            out.add(inner);
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
            ModulePrivacy.Privatized p = ModulePrivacy.privatize(
                    IrijCompiler.buildAst(parsed), qualifiedName);
            ReExports re = new ReExports();
            expand(p.decls(), out, moduleFile(qualifiedName), qualifiedName,
                    ModuleScope.ownNames(p.decls()), re);
            ModuleScope.Exports own = ModuleScope.exportsOf(qualifiedName, p.decls(), p.exports());
            var values = new HashMap<>(re.values());
            values.putAll(own.values());
            var types = new java.util.LinkedHashMap<>(re.types());
            types.putAll(own.types());
            var kinds = new HashMap<>(re.kinds());
            kinds.putAll(own.kinds());
            exportsByModule.put(qualifiedName, new ModuleScope.Exports(qualifiedName,
                    java.util.Map.copyOf(values), types, kinds, own.privates()));
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
