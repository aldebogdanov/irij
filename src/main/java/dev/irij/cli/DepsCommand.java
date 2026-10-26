package dev.irij.cli;

import dev.irij.module.ProjectFile;
import dev.irij.module.jvm.JvmDeps;
import dev.irij.module.jvm.ProjectDeps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code irij deps} — the Java libraries {@code irij.toml} {@code [jvm]}
 * and the project's seeds ask for, and the jars they resolve to. Resolves
 * (and writes {@code irij.lock}) like a run would.
 */
final class DepsCommand {

    private DepsCommand() {}

    static void run() {
        Path root = Path.of(System.getProperty("user.dir"));
        if (!Files.exists(root.resolve("irij.toml"))) {
            System.out.println("No irij.toml found in " + root);
            return;
        }
        try {
            var deps = ProjectDeps.resolve(root, System.out);
            if (deps.plan().isEmpty()) {
                System.out.println("No Java libraries: [jvm] is empty here and in every seed.");
                return;
            }
            System.out.println("Asked for:");
            for (var e : JvmDeps.byDeclarer(deps.plan()).entrySet()) {
                System.out.println("  " + e.getKey());
                for (var d : e.getValue()) System.out.println("    " + d.coord());
            }
            System.out.println();
            System.out.println("Resolved (" + JvmDeps.LOCK_FILE + ", classpath order):");
            for (var j : deps.jars()) System.out.println("  " + j.getFileName());
        } catch (ProjectFile.ParseError e) {
            System.err.println("Error in irij.toml: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
}
