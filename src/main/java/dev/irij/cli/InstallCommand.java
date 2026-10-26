package dev.irij.cli;

import dev.irij.IrijRuntimeError;
import dev.irij.parser.IrijParseDriver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** {@code irij install} (alias {@code seed}) — fetch seeds and Java
 *  libraries ({@code [jvm]}, pinned in irij.lock) from irij.toml. */
final class InstallCommand {

    static void run() {
        var projectRoot = Path.of(System.getProperty("user.dir"));
        var tomlFile = projectRoot.resolve("irij.toml");

        if (!Files.exists(tomlFile)) {
            System.out.println("No irij.toml found in " + projectRoot);
            return;
        }

        try {
            var manifest = dev.irij.module.ProjectFile.parseFile(tomlFile);
            var deps = manifest.deps();
            if (deps.isEmpty() && manifest.jvm().deps().isEmpty()) {
                System.out.println("irij.toml has no seeds or [jvm] libraries to install.");
                return;
            }

            if (!deps.isEmpty()) {
                System.out.println("Installing " + deps.size() + " seed"
                    + (deps.size() == 1 ? "" : "s") + " ...");
            }
            var resolved = dev.irij.module.jvm.ProjectDeps.resolve(projectRoot, System.out);

            System.out.println();
            for (var entry : resolved.seeds().entrySet()) {
                System.out.println("  " + entry.getKey() + " → " + entry.getValue());
            }
            for (var jar : resolved.jars()) {
                System.out.println("  " + jar.getFileName() + " → " + jar);
            }
            System.out.println("\nDone.");
        } catch (dev.irij.module.ProjectFile.ParseError e) {
            System.err.println("Error in irij.toml: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Error installing seeds: " + e.getMessage());
            System.exit(1);
        }
    }

    private InstallCommand() {}
}
