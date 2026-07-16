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

/** {@code irij install} (alias {@code seed}) — fetch seeds from irij.toml. */
final class InstallCommand {

    static void run() {
        var projectRoot = Path.of(System.getProperty("user.dir"));
        var tomlFile = projectRoot.resolve("irij.toml");

        if (!Files.exists(tomlFile)) {
            System.out.println("No irij.toml found in " + projectRoot);
            return;
        }

        try {
            var deps = dev.irij.module.ProjectFile.parseDeps(tomlFile);
            if (deps.isEmpty()) {
                System.out.println("irij.toml has no seeds to install.");
                return;
            }

            System.out.println("Installing " + deps.size() + " seed"
                + (deps.size() == 1 ? "" : "s") + " ...");
            var resolver = new dev.irij.module.DependencyResolver(projectRoot, System.out);
            var resolved = resolver.resolveAll(deps);

            System.out.println();
            for (var entry : resolved.entrySet()) {
                System.out.println("  " + entry.getKey() + " → " + entry.getValue());
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
