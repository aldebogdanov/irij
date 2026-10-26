package dev.irij.module.jvm;

import dev.irij.module.DependencyResolver;
import dev.irij.module.ProjectFile;
import dev.irij.runtime.DepsClassLoader;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything a project's {@code irij.toml} pulls in: its seeds (name →
 * source dir, transitively), its Java libraries ({@link JvmDeps}) and the
 * {@code java/} sources of the project and its seeds, compiled
 * ({@link JavaSources}). {@code jars} is all of it — libraries first, then
 * the compiled sources — the list the program's class loader and
 * {@code irij build} take.
 * The one entry point for "run in a project context" — {@code irij run},
 * {@code irij test}, the REPL, nREPL, {@code irij build}, {@code irij
 * install} — so they all see the same seeds and the same jars.
 */
public record ProjectDeps(Map<String, Path> seeds, JvmDeps.Plan plan, JavaSources.Plan java,
                          List<Path> jars) {

    public static final ProjectDeps NONE = new ProjectDeps(Map.of(), null, JavaSources.Plan.EMPTY, List.of());

    public List<Path> seedRoots() {
        return new ArrayList<>(seeds.values());
    }

    /** Resolve seeds, fetch Java libraries and compile Java sources for
     *  {@code projectRoot} (null, or no irij.toml: nothing). Untrusted
     *  seeds are refused before anything is downloaded or compiled. */
    public static ProjectDeps resolve(Path projectRoot, PrintStream out) throws IOException {
        if (projectRoot == null) return NONE;
        Path toml = projectRoot.resolve("irij.toml");
        if (!Files.exists(toml)) return NONE;
        var manifest = ProjectFile.parseFile(toml);
        Map<String, Path> seeds = manifest.deps().isEmpty() ? Map.of()
                : new DependencyResolver(projectRoot, out).resolveAll(manifest.deps());
        var plan = JvmDeps.plan(manifest, seeds);
        var java = JavaSources.plan(manifest, projectRoot, seeds);
        java.requireTrusted();
        var libraries = JvmDeps.fetch(projectRoot, plan, out);
        var jars = new ArrayList<>(libraries);
        jars.addAll(JavaSources.compile(java, libraries, out));
        return new ProjectDeps(seeds, plan, java, List.copyOf(jars));
    }

    /** {@link #resolve}, then make the jars the program's Java libraries
     *  ({@link DepsClassLoader#install}) — an empty list restores the
     *  runtime's own loader. */
    public static ProjectDeps load(Path projectRoot, PrintStream out) throws IOException {
        var deps = resolve(projectRoot, out);
        DepsClassLoader.install(deps.jars());
        return deps;
    }
}
