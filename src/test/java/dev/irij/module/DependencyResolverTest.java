package dev.irij.module;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DependencyResolverTest {

    @Nested
    class LocalPathDeps {

        @Test void resolveLocalPath(@TempDir Path tmp) throws IOException {
            var libDir = tmp.resolve("mylib");
            Files.createDirectories(libDir);
            Files.writeString(libDir.resolve("mod.irj"), "pub fn hello\n  (-> :hello)\n");

            var dep = new ProjectFile.Dependency("mylib", new ProjectFile.DepSource.PathDep("mylib"));
            var out = new PrintStream(new ByteArrayOutputStream());
            var resolver = new DependencyResolver(tmp, out);
            var resolved = resolver.resolveAll(List.of(dep));

            assertEquals(1, resolved.size());
            assertTrue(resolved.containsKey("mylib"));
            assertEquals(libDir, resolved.get("mylib"));
        }

        @Test void localPathNotFound(@TempDir Path tmp) {
            var dep = new ProjectFile.Dependency("missing", new ProjectFile.DepSource.PathDep("nonexistent"));
            var out = new PrintStream(new ByteArrayOutputStream());
            var resolver = new DependencyResolver(tmp, out);
            assertThrows(IOException.class, () -> resolver.resolveAll(List.of(dep)));
        }

        @Test void resolveMultipleLocalDeps(@TempDir Path tmp) throws IOException {
            var lib1 = tmp.resolve("lib1");
            var lib2 = tmp.resolve("lib2");
            Files.createDirectories(lib1);
            Files.createDirectories(lib2);

            var deps = List.of(
                new ProjectFile.Dependency("lib1", new ProjectFile.DepSource.PathDep("lib1")),
                new ProjectFile.Dependency("lib2", new ProjectFile.DepSource.PathDep("lib2"))
            );
            var out = new PrintStream(new ByteArrayOutputStream());
            var resolver = new DependencyResolver(tmp, out);
            var resolved = resolver.resolveAll(deps);

            assertEquals(2, resolved.size());
            assertEquals(lib1, resolved.get("lib1"));
            assertEquals(lib2, resolved.get("lib2"));
        }
    }

    @Nested
    class TransitiveDeps {

        @Test void resolveTransitiveDeps(@TempDir Path tmp) throws IOException {
            // lib-b has no deps
            var libB = tmp.resolve("lib-b");
            Files.createDirectories(libB);
            Files.writeString(libB.resolve("mod.irj"), "mod lib-b\npub fn b (-> :b)\n");

            // lib-a depends on lib-b
            var libA = tmp.resolve("lib-a");
            Files.createDirectories(libA);
            Files.writeString(libA.resolve("mod.irj"), "mod lib-a\npub fn a (-> :a)\n");
            Files.writeString(libA.resolve("irij.toml"), """
                [deps.lib-b]
                path = "../lib-b"
                """);

            // Project depends on lib-a
            var deps = List.of(
                new ProjectFile.Dependency("lib-a", new ProjectFile.DepSource.PathDep("lib-a"))
            );
            var out = new PrintStream(new ByteArrayOutputStream());
            var resolver = new DependencyResolver(tmp, out);
            var resolved = resolver.resolveAll(deps);

            assertEquals(2, resolved.size());
            assertEquals(libA, resolved.get("lib-a"));
            assertEquals(libB, resolved.get("lib-b"));
        }

        @Test void transitiveDepAlreadyDeclared(@TempDir Path tmp) throws IOException {
            // lib-b exists
            var libB = tmp.resolve("lib-b");
            Files.createDirectories(libB);
            Files.writeString(libB.resolve("mod.irj"), "mod lib-b\npub fn b (-> :b)\n");

            // lib-a depends on lib-b
            var libA = tmp.resolve("lib-a");
            Files.createDirectories(libA);
            Files.writeString(libA.resolve("mod.irj"), "mod lib-a\npub fn a (-> :a)\n");
            Files.writeString(libA.resolve("irij.toml"), """
                [deps.lib-b]
                path = "../lib-b"
                """);

            // Project declares both — lib-b should only appear once (first wins)
            var deps = List.of(
                new ProjectFile.Dependency("lib-b", new ProjectFile.DepSource.PathDep("lib-b")),
                new ProjectFile.Dependency("lib-a", new ProjectFile.DepSource.PathDep("lib-a"))
            );
            var out = new PrintStream(new ByteArrayOutputStream());
            var resolver = new DependencyResolver(tmp, out);
            var resolved = resolver.resolveAll(deps);

            assertEquals(2, resolved.size());
            assertTrue(resolved.containsKey("lib-a"));
            assertTrue(resolved.containsKey("lib-b"));
        }

        @Test void deepTransitiveDeps(@TempDir Path tmp) throws IOException {
            // lib-c (leaf)
            var libC = tmp.resolve("lib-c");
            Files.createDirectories(libC);
            Files.writeString(libC.resolve("mod.irj"), "mod lib-c\npub fn c (-> :c)\n");

            // lib-b depends on lib-c
            var libB = tmp.resolve("lib-b");
            Files.createDirectories(libB);
            Files.writeString(libB.resolve("mod.irj"), "mod lib-b\npub fn b (-> :b)\n");
            Files.writeString(libB.resolve("irij.toml"), """
                [deps.lib-c]
                path = "../lib-c"
                """);

            // lib-a depends on lib-b
            var libA = tmp.resolve("lib-a");
            Files.createDirectories(libA);
            Files.writeString(libA.resolve("mod.irj"), "mod lib-a\npub fn a (-> :a)\n");
            Files.writeString(libA.resolve("irij.toml"), """
                [deps.lib-b]
                path = "../lib-b"
                """);

            // Project depends on lib-a only
            var deps = List.of(
                new ProjectFile.Dependency("lib-a", new ProjectFile.DepSource.PathDep("lib-a"))
            );
            var out = new PrintStream(new ByteArrayOutputStream());
            var resolver = new DependencyResolver(tmp, out);
            var resolved = resolver.resolveAll(deps);

            assertEquals(3, resolved.size());
            assertTrue(resolved.containsKey("lib-a"));
            assertTrue(resolved.containsKey("lib-b"));
            assertTrue(resolved.containsKey("lib-c"));
        }

        @Test void circularDependencyDetected(@TempDir Path tmp) throws IOException {
            // lib-a depends on lib-b
            var libA = tmp.resolve("lib-a");
            Files.createDirectories(libA);
            Files.writeString(libA.resolve("mod.irj"), "mod lib-a\n");
            Files.writeString(libA.resolve("irij.toml"), """
                [deps.lib-b]
                path = "../lib-b"
                """);

            // lib-b depends on lib-a (cycle!)
            var libB = tmp.resolve("lib-b");
            Files.createDirectories(libB);
            Files.writeString(libB.resolve("mod.irj"), "mod lib-b\n");
            Files.writeString(libB.resolve("irij.toml"), """
                [deps.lib-a]
                path = "../lib-a"
                """);

            var deps = List.of(
                new ProjectFile.Dependency("lib-a", new ProjectFile.DepSource.PathDep("lib-a"))
            );
            var out = new PrintStream(new ByteArrayOutputStream());
            var resolver = new DependencyResolver(tmp, out);
            var ex = assertThrows(IOException.class, () -> resolver.resolveAll(deps));
            assertTrue(ex.getMessage().contains("Circular dependency"));
        }

        @Test void depWithNoTomlSkipped(@TempDir Path tmp) throws IOException {
            // lib with no irij.toml — should resolve fine, no transitive lookup
            var lib = tmp.resolve("simple-lib");
            Files.createDirectories(lib);
            Files.writeString(lib.resolve("mod.irj"), "mod simple-lib\npub fn x (-> :x)\n");

            var deps = List.of(
                new ProjectFile.Dependency("simple-lib", new ProjectFile.DepSource.PathDep("simple-lib"))
            );
            var out = new PrintStream(new ByteArrayOutputStream());
            var resolver = new DependencyResolver(tmp, out);
            var resolved = resolver.resolveAll(deps);

            assertEquals(1, resolved.size());
            assertEquals(lib, resolved.get("simple-lib"));
        }
    }
}
