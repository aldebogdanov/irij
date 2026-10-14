package dev.irij.runtime;

import dev.irij.IrijRuntimeError;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Capability provider for the {@code Resource} effect: read-only access
 * to the program's own {@code resources/} directory.
 *
 * <p>Bound from Irij with:
 * <pre>
 *   cap resources :: Resource = "dev.irij.runtime.ResourceCapability"
 * </pre>
 *
 * <p>A resource is looked up where {@code serve} looks up static assets:
 * bundled under {@code __irij_resources/} on the classpath (what
 * {@code irij build} writes into the jar), else {@code resources/} under
 * the working directory when run from source. Nothing outside that
 * directory is reachable, so granting {@code Resource} is not granting
 * {@code FileIO}.
 */
public final class ResourceCapability {

    private static final String BUNDLE_PREFIX = "__irij_resources/";

    private ResourceCapability() {}

    public static Object read(Object pathArg) {
        String rel = relPath(pathArg, "resources.read");
        byte[] bytes = find(rel, ResourceCapability.class.getClassLoader(), workDir());
        if (bytes == null) {
            throw new IrijRuntimeError("resources.read: no resource " + rel);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static Object existsQ(Object pathArg) {
        String rel = relPath(pathArg, "resources.exists?");
        return find(rel, ResourceCapability.class.getClassLoader(), workDir()) != null;
    }

    /** The resource's bytes, or null when neither the bundle nor
     *  {@code workDir/resources} holds it. */
    static byte[] find(String rel, ClassLoader loader, Path workDir) {
        try (InputStream is = loader.getResourceAsStream(BUNDLE_PREFIX + rel)) {
            if (is != null) return is.readAllBytes();
        } catch (IOException e) {
            throw new IrijRuntimeError("resources.read: " + e.getMessage());
        }
        Path root = workDir.resolve("resources").normalize();
        final Path file;
        try {
            file = root.resolve(rel).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
        if (!file.startsWith(root) || file.equals(root) || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IrijRuntimeError("resources.read: " + e.getMessage());
        }
    }

    /** A path relative to {@code resources/}. `..`, `.`, absolute paths
     *  and backslashes never name a resource; refuse them before they
     *  reach a classloader, whose resolution we don't control. */
    static String relPath(Object v, String op) {
        if (!(v instanceof String s)) {
            throw new IrijRuntimeError(op + ": expected Str, got " + v);
        }
        if (s.isEmpty() || s.startsWith("/")) {
            throw new IrijRuntimeError(op + ": not a path under resources/: " + s);
        }
        for (String seg : s.split("/", -1)) {
            if (seg.isEmpty() || seg.equals("..") || seg.equals(".")
                    || seg.indexOf('\\') >= 0 || seg.indexOf('\0') >= 0) {
                throw new IrijRuntimeError(op + ": not a path under resources/: " + s);
            }
        }
        return s;
    }

    private static Path workDir() {
        return Path.of("").toAbsolutePath();
    }
}
