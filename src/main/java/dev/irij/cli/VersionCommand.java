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

final class VersionCommand {

    /**
     * {@code irij version [--base|--count|--full]} — print the
     * commit-count version of the project in the current directory.
     * Default (and {@code --full}) prints {@code MAJOR.MINOR.<count>} on
     * main, or {@code MAJOR.MINOR.<count>-<branch>} on a dev branch.
     * {@code --base} prints the 2-part base from irij.toml; {@code --count}
     * prints just the commit count.
     */
    static void run(String[] args) {
        var projectRoot = Path.of(System.getProperty("user.dir"));
        var tomlFile = projectRoot.resolve("irij.toml");
        if (!Files.exists(tomlFile)) {
            System.err.println("No irij.toml in " + projectRoot + " — `irij version` "
                + "reports the version of an Irij project.");
            System.exit(1);
            return;
        }
        String mode = args.length > 0 ? args[0] : "--full";
        try {
            var meta = dev.irij.module.ProjectFile.parseFile(tomlFile).meta();
            String base = meta == null ? "" : meta.version();
            switch (mode) {
                case "--count" -> System.out.println(
                        dev.irij.module.ProjectVersion.commitCount(projectRoot).orElse(0));
                case "--base" -> {
                    dev.irij.module.ProjectVersion.requireMajorMinorBase(base);
                    System.out.println(base.trim());
                }
                case "--full", "" -> System.out.println(
                        dev.irij.module.ProjectVersion.buildVersion(projectRoot, base));
                default -> {
                    System.err.println("Unknown flag: " + mode
                        + " (expected --full, --base, or --count)");
                    System.exit(1);
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error reading irij.toml: " + e.getMessage());
            System.exit(1);
        }
    }

    private VersionCommand() {}
}
