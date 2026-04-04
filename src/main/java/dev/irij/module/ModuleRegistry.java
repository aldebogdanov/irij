package dev.irij.module;

import dev.irij.ast.AstBuilder;
import dev.irij.ast.Node.SourceLoc;
import dev.irij.interpreter.IrijRuntimeError;
import dev.irij.interpreter.Values.ModuleValue;
import dev.irij.parser.IrijParseDriver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

/**
 * Module registry: caches loaded modules, resolves module names to files or
 * classpath resources, and detects circular dependencies.
 *
 * <p>Resolution order for {@code use some.module}:
 * <ol>
 *   <li>Already-loaded cache
 *   <li>Registered factory (Java-implemented std modules)
 *   <li>Classpath resource ({@code std/*.irj} for {@code std.*} modules)
 *   <li>File system (relative to {@code sourcePath})
 * </ol>
 */
public final class ModuleRegistry {

    /** Functional interface for the interpreter to load a module from source. */
    @FunctionalInterface
    public interface ModuleLoader {
        ModuleValue load(String source, String qualifiedName, SourceLoc loc);
    }

    private final Map<String, ModuleValue> loaded = new HashMap<>();
    private final Map<String, Supplier<ModuleValue>> factories = new HashMap<>();
    private final Set<String> loading = new HashSet<>();
    private final ModuleLoader loader;
    private Path sourcePath;

    /** Dependency search paths: dep name → source directory (from deps.irj). */
    private final Map<String, Path> depPaths = new LinkedHashMap<>();

    public ModuleRegistry(ModuleLoader loader) {
        this.loader = loader;
    }

    /** Register a lazy factory for a Java-implemented module. */
    public void registerFactory(String qualifiedName, Supplier<ModuleValue> factory) {
        factories.put(qualifiedName, factory);
    }

    /** Set the source root for file-based module resolution. */
    public void setSourcePath(Path sourcePath) {
        this.sourcePath = sourcePath;
    }

    public Path getSourcePath() {
        return sourcePath;
    }

    /** Register a dependency search path (from deps.irj resolution). */
    public void addDepPath(String depName, Path depSourceDir) {
        depPaths.put(depName, depSourceDir);
    }

    /**
     * Resolve a module by qualified name.
     * Checks cache, factories, classpath resources, then file system.
     */
    public ModuleValue resolve(String qualifiedName, SourceLoc loc) {
        // 1. Cache
        var cached = loaded.get(qualifiedName);
        if (cached != null) return cached;

        // Circular dependency check
        if (loading.contains(qualifiedName)) {
            throw new IrijRuntimeError("Circular module dependency: " + qualifiedName, loc);
        }
        loading.add(qualifiedName);
        try {
            ModuleValue mod;

            // 2. Registered factory (Java-implemented std modules)
            var factory = factories.get(qualifiedName);
            if (factory != null) {
                mod = factory.get();
                loaded.put(qualifiedName, mod);
                return mod;
            }

            // 3. Classpath resource (std/*.irj)
            mod = loadFromClasspath(qualifiedName, loc);
            if (mod != null) {
                loaded.put(qualifiedName, mod);
                return mod;
            }

            // 4. Dependency paths (from deps.irj)
            mod = loadFromDeps(qualifiedName, loc);
            if (mod != null) {
                loaded.put(qualifiedName, mod);
                return mod;
            }

            // 5. File system (relative to sourcePath)
            mod = loadFromFile(qualifiedName, loc);
            if (mod != null) {
                loaded.put(qualifiedName, mod);
                return mod;
            }

            throw new IrijRuntimeError("Module not found: " + qualifiedName, loc);
        } finally {
            loading.remove(qualifiedName);
        }
    }

    /** Pre-register a loaded module (e.g., for testing). */
    public void register(String qualifiedName, ModuleValue module) {
        loaded.put(qualifiedName, module);
    }

    // ── Internal resolution strategies ──────────────────────────────────

    private ModuleValue loadFromClasspath(String qualifiedName, SourceLoc loc) {
        // Convert dots to '/' and append .irj
        var resourcePath = qualifiedName.replace('.', '/') + ".irj";
        InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) return null;

        try (is) {
            var source = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return loader.load(source, qualifiedName, loc);
        } catch (IOException e) {
            throw new IrijRuntimeError("Error reading module resource: " + qualifiedName + ": " + e.getMessage(), loc);
        }
    }

    private ModuleValue loadFromDeps(String qualifiedName, SourceLoc loc) {
        // Try to resolve "depname.module.path" against registered dep paths.
        // The first segment of the qualified name must match a dep name.
        // e.g., "utils.helpers" → depPaths["utils"] / "helpers.irj"
        // e.g., "utils" → depPaths["utils"] / look for "mod.irj" or single module
        for (var entry : depPaths.entrySet()) {
            var depName = entry.getKey();
            var depDir = entry.getValue();

            if (qualifiedName.equals(depName)) {
                // Direct dep name: look for src/ or lib/ subdirs, then root
                // Try <dep>/src/<depname>.irj, then <dep>/<depname>.irj, then <dep>/mod.irj
                for (var candidate : List.of(
                        depDir.resolve("src/" + depName + ".irj"),
                        depDir.resolve(depName + ".irj"),
                        depDir.resolve("mod.irj"))) {
                    if (Files.exists(candidate)) {
                        return loadFile(candidate, qualifiedName, loc);
                    }
                }
            } else if (qualifiedName.startsWith(depName + ".")) {
                // Sub-module within dep: "utils.helpers" → depDir/helpers.irj or depDir/src/helpers.irj
                var rest = qualifiedName.substring(depName.length() + 1);
                var relativePath = rest.replace('.', '/') + ".irj";
                for (var base : List.of(depDir.resolve("src"), depDir)) {
                    var candidate = base.resolve(relativePath);
                    if (Files.exists(candidate)) {
                        return loadFile(candidate, qualifiedName, loc);
                    }
                }
            }
        }
        return null;
    }

    private ModuleValue loadFile(Path file, String qualifiedName, SourceLoc loc) {
        try {
            var source = Files.readString(file);
            return loader.load(source, qualifiedName, loc);
        } catch (IOException e) {
            throw new IrijRuntimeError("Error reading module file: " + file + ": " + e.getMessage(), loc);
        }
    }

    private ModuleValue loadFromFile(String qualifiedName, SourceLoc loc) {
        var relativePath = qualifiedName.replace('.', '/') + ".irj";
        Path base = sourcePath != null ? sourcePath : Path.of(".");
        Path file = base.resolve(relativePath);
        if (!Files.exists(file)) return null;
        return loadFile(file, qualifiedName, loc);
    }
}
