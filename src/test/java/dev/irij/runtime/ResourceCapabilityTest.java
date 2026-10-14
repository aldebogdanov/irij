package dev.irij.runtime;

import dev.irij.IrijRuntimeError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What std.resource reaches: the program's resources/ directory, from the
 * jar first and then from disk, and nothing else.
 */
class ResourceCapabilityTest {

    @TempDir Path work;
    @TempDir Path jarRoot;

    private static final ClassLoader EMPTY = new URLClassLoader(new URL[0], null);

    private String find(String rel, ClassLoader loader) {
        byte[] b = ResourceCapability.find(rel, loader, work);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    @Test void readsFromResourcesOnDisk() throws Exception {
        Files.createDirectories(work.resolve("resources/conf"));
        Files.writeString(work.resolve("resources/version.txt"), "0.1.7");
        Files.writeString(work.resolve("resources/conf/a.toml"), "a");
        assertEquals("0.1.7", find("version.txt", EMPTY));
        assertEquals("a", find("conf/a.toml", EMPTY));
    }

    @Test void theBundleWinsOverDisk() throws Exception {
        Files.createDirectories(jarRoot.resolve("__irij_resources"));
        Files.writeString(jarRoot.resolve("__irij_resources/version.txt"), "bundled");
        Files.createDirectories(work.resolve("resources"));
        Files.writeString(work.resolve("resources/version.txt"), "disk");
        try (var loader = new URLClassLoader(new URL[]{jarRoot.toUri().toURL()}, null)) {
            assertEquals("bundled", find("version.txt", loader));
        }
    }

    @Test void aMissingResourceIsAbsent() {
        assertNull(find("version.txt", EMPTY));
    }

    @Test void neverReachesOutsideResources() throws Exception {
        Files.createDirectories(work.resolve("resources"));
        Files.writeString(work.resolve("irij.toml"), "[project]");
        assertNull(find("irij.toml", EMPTY));
        assertNull(find("resources", EMPTY));
    }

    @Test void refusesPathsThatAreNotUnderResources() {
        for (String bad : new String[]{"", "/etc/passwd", "../irij.toml", "a/../b",
                                       "./a", "a//b", "a\\b", "a/"}) {
            assertThrows(IrijRuntimeError.class,
                    () -> ResourceCapability.relPath(bad, "resources.read"), bad);
        }
        assertThrows(IrijRuntimeError.class,
                () -> ResourceCapability.relPath(1L, "resources.read"));
    }
}
