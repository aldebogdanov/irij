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
            // Create a local dep directory
            var libDir = tmp.resolve("mylib");
            Files.createDirectories(libDir);
            Files.writeString(libDir.resolve("mod.irj"), "pub fn hello\n  (-> :hello)\n");

            var dep = new DepsFile.Dependency("mylib", new DepsFile.DepSource.PathDep("mylib"));
            var out = new PrintStream(new ByteArrayOutputStream());
            var resolver = new DependencyResolver(tmp, out);
            var resolved = resolver.resolveAll(List.of(dep));

            assertEquals(1, resolved.size());
            assertTrue(resolved.containsKey("mylib"));
            assertEquals(libDir, resolved.get("mylib"));
        }

        @Test void localPathNotFound(@TempDir Path tmp) {
            var dep = new DepsFile.Dependency("missing", new DepsFile.DepSource.PathDep("nonexistent"));
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
                new DepsFile.Dependency("lib1", new DepsFile.DepSource.PathDep("lib1")),
                new DepsFile.Dependency("lib2", new DepsFile.DepSource.PathDep("lib2"))
            );
            var out = new PrintStream(new ByteArrayOutputStream());
            var resolver = new DependencyResolver(tmp, out);
            var resolved = resolver.resolveAll(deps);

            assertEquals(2, resolved.size());
            assertEquals(lib1, resolved.get("lib1"));
            assertEquals(lib2, resolved.get("lib2"));
        }
    }
}
