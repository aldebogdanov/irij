package dev.irij.module;

import dev.irij.compiler.CompileOptions;
import dev.irij.compiler.IrijCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Resolving {@code use <seed>.<module>} across several seed roots.
 *
 * <p>A seed's modules live flat in its root — {@code uzor.core} is
 * {@code <uzorRoot>/core.irj}, with no per-seed directory — so the
 * inliner strips the seed prefix and looks for {@code core.irj}. That
 * strip used to be tried against *every* root in turn, which made
 * resolution depend on the order the roots happened to be in: with two
 * seeds that both have a {@code core.irj}, {@code use uzor.core} could
 * load butterfly's.
 *
 * <p>The failure was silent. The module loaded, so the only symptom was
 * every name the caller wanted being suddenly undefined.
 */
class SeedModuleResolutionTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(SeedModuleResolutionTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /** Write a seed root with an irij.toml and one flat module. */
    private static Path seed(Path parent, String name, String moduleBody) throws Exception {
        Path root = parent.resolve(name);
        Files.createDirectories(root);
        Files.writeString(root.resolve("irij.toml"),
                "[project]\nname = \"" + name + "\"\nversion = \"0.1\"\n");
        Files.writeString(root.resolve("core.irj"), moduleBody);
        return root;
    }

    private static String run(String source, Path sourceRoot, List<Path> seedRoots) throws Exception {
        var classes = IrijCompiler.compileSourceMulti(source, "irij.SeedProbe", sourceRoot,
                CompileOptions.defaults(), seedRoots, "probe.irj");
        PrintStream orig = System.out;
        var buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf));
        try {
            var loader = new BytesLoader();
            Class<?> main = null;
            for (var e : classes.entrySet()) {
                Class<?> c = loader.define(e.getKey(), e.getValue());
                if (e.getKey().equals("irij.SeedProbe")) main = c;
            }
            Method m = main.getMethod("main", String[].class);
            m.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(orig);
        }
        return buf.toString().trim();
    }

    @Test
    void eachSeedResolvesToItsOwnModule(@TempDir Path tmp) throws Exception {
        Path alpha = seed(tmp, "alpha", """
                mod alpha.core
                pub fn who :: Str
                  =>
                  "alpha"
                """);
        Path beta = seed(tmp, "beta", """
                mod beta.core
                pub fn who :: Str
                  =>
                  "beta"
                """);
        Path proj = tmp.resolve("proj");
        Files.createDirectories(proj);

        // Both orders must give the same answer. Before the fix, the
        // first root won for every stripped lookup, so one of these
        // resolved `alpha.core` to beta's core.irj.
        assertEquals("alpha", run("""
                use alpha.core :open
                println (who ())
                """, proj, List.of(alpha, beta)));

        assertEquals("alpha", run("""
                use alpha.core :open
                println (who ())
                """, proj, List.of(beta, alpha)));

        assertEquals("beta", run("""
                use beta.core :open
                println (who ())
                """, proj, List.of(alpha, beta)));
    }

    @Test
    void bothSeedsUsableInOneProgram(@TempDir Path tmp) throws Exception {
        Path alpha = seed(tmp, "alpha", """
                mod alpha.core
                pub fn alpha-name :: Str
                  =>
                  "A"
                """);
        Path beta = seed(tmp, "beta", """
                mod beta.core
                pub fn beta-name :: Str
                  =>
                  "B"
                """);
        Path proj = tmp.resolve("proj");
        Files.createDirectories(proj);

        assertEquals("AB", run("""
                use alpha.core :open
                use beta.core :open
                println ((alpha-name ()) ++ (beta-name ()))
                """, proj, List.of(alpha, beta)));
    }

    /**
     * An installed seed lives at {@code <name>/<version>/}, so the root
     * directory is named for the version rather than the seed. The
     * seed's own irij.toml settles it.
     */
    @Test
    void versionedSeedRootStillResolves(@TempDir Path tmp) throws Exception {
        Path versioned = seed(tmp.resolve("cache").resolve("gamma"), "0.1.7", """
                mod gamma.core
                pub fn who :: Str
                  =>
                  "gamma"
                """);
        // The generated irij.toml names the directory (0.1.7); rewrite
        // it to the real seed name, as a published seed would have.
        Files.writeString(versioned.resolve("irij.toml"),
                "[project]\nname = \"gamma\"\nversion = \"0.1.7\"\n");

        Path other = seed(tmp, "delta", """
                mod delta.core
                pub fn who :: Str
                  =>
                  "delta"
                """);
        Path proj = tmp.resolve("proj");
        Files.createDirectories(proj);

        assertEquals("gamma", run("""
                use gamma.core :open
                println (who ())
                """, proj, List.of(other, versioned)));
    }

    /** A root with no irij.toml falls back to its directory name. */
    @Test
    void bareDirectoryRootResolvesByName(@TempDir Path tmp) throws Exception {
        Path eps = tmp.resolve("epsilon");
        Files.createDirectories(eps);
        Files.writeString(eps.resolve("core.irj"), """
                mod epsilon.core
                pub fn who :: Str
                  =>
                  "epsilon"
                """);
        Path zeta = seed(tmp, "zeta", """
                mod zeta.core
                pub fn who :: Str
                  =>
                  "zeta"
                """);
        Path proj = tmp.resolve("proj");
        Files.createDirectories(proj);

        assertEquals("epsilon", run("""
                use epsilon.core :open
                println (who ())
                """, proj, List.of(zeta, eps)));
    }
}
