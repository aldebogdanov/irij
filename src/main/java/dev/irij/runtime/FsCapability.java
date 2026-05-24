package dev.irij.runtime;

import dev.irij.IrijRuntimeError;
import dev.irij.runtime.Values.IrijVector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Capability provider for the {@code FileIO} effect.
 *
 * <p>Bound from Irij with:
 * <pre>
 *   cap fs-files :: FileIO = "dev.irij.runtime.FsCapability"
 * </pre>
 *
 * <p>Each method is {@code public static}; std.fs's clauses dot-
 * access them inside the handler. The plain raw read-file /
 * write-file / list-dir / etc. builtins were delisted in phase 3d
 * — the only way to touch the filesystem now is through the
 * {@code FileIO} effect.
 */
public final class FsCapability {

    private FsCapability() {}

    public static Object read(Object pathArg) {
        String path = asStr(pathArg, "fs-files.read");
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IrijRuntimeError("fs-files.read: " + e.getMessage());
        }
    }

    public static Object write(Object pathArg, Object contentArg) {
        String path = asStr(pathArg, "fs-files.write");
        String text = asStr(contentArg, "fs-files.write");
        try {
            Files.writeString(Path.of(path), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IrijRuntimeError("fs-files.write: " + e.getMessage());
        }
        return Values.UNIT;
    }

    public static Object append(Object pathArg, Object contentArg) {
        String path = asStr(pathArg, "fs-files.append");
        String text = asStr(contentArg, "fs-files.append");
        try {
            Files.writeString(Path.of(path), text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IrijRuntimeError("fs-files.append: " + e.getMessage());
        }
        return Values.UNIT;
    }

    public static Object existsQ(Object pathArg) {
        return Files.exists(Path.of(asStr(pathArg, "fs-files.exists?")));
    }

    public static Object listDir(Object pathArg) {
        String path = asStr(pathArg, "fs-files.list-dir");
        try (Stream<Path> stream = Files.list(Path.of(path))) {
            List<Object> names = new ArrayList<>();
            stream.forEach(p -> names.add(p.getFileName().toString()));
            return new IrijVector(names);
        } catch (IOException e) {
            throw new IrijRuntimeError("fs-files.list-dir: " + e.getMessage());
        }
    }

    public static Object delete(Object pathArg) {
        String path = asStr(pathArg, "fs-files.delete");
        try { Files.deleteIfExists(Path.of(path)); }
        catch (IOException e) {
            throw new IrijRuntimeError("fs-files.delete: " + e.getMessage());
        }
        return Values.UNIT;
    }

    public static Object mkdir(Object pathArg) {
        String path = asStr(pathArg, "fs-files.mkdir");
        try { Files.createDirectories(Path.of(path)); }
        catch (IOException e) {
            throw new IrijRuntimeError("fs-files.mkdir: " + e.getMessage());
        }
        return Values.UNIT;
    }

    private static String asStr(Object v, String op) {
        if (v instanceof String s) return s;
        throw new IrijRuntimeError(op + ": expected Str, got " + v);
    }
}
