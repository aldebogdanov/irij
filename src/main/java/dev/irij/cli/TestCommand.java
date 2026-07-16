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

/** {@code irij test} — run test-*.irj files with per-file and total summaries. */
final class TestCommand {

    static void run(String[] args) {
        runTests(resolveTestFiles(args));
    }

    /**
     * Resolve test file paths from CLI args.
     * If no args after "test", default to ./tests/ directory.
     */
    static List<Path> resolveTestFiles(String[] args) {
        if (args.length == 1) {
            // irij test — default to ./tests/
            Path testsDir = Path.of("tests").toAbsolutePath();
            return discoverTestFiles(testsDir);
        }

        List<Path> files = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            Path p = Path.of(args[i]).toAbsolutePath();
            if (Files.isDirectory(p)) {
                files.addAll(discoverTestFiles(p));
            } else {
                files.add(p);
            }
        }
        return files;
    }

    /**
     * Discover test-*.irj files in a directory, sorted by name.
     */
    static List<Path> discoverTestFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            System.err.println("Test directory not found: " + dir);
            System.exit(1);
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().matches("test-.*\\.irj"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            System.err.println("Cannot list directory: " + dir + ": " + e.getMessage());
            System.exit(1);
            return List.of();
        }
    }

    /**
     * Run test files, print per-file and grand-total summaries, and exit.
     */
    static void runTests(List<Path> files) {
        if (files.isEmpty()) {
            System.err.println("No test files found.");
            System.exit(1);
            return;
        }

        int grandPass = 0;
        int grandFail = 0;
        int crashCount = 0;
        List<String> failedFiles = new ArrayList<>();

        System.out.println("Running " + files.size() + " test file(s)...");
        System.out.println();

        for (Path file : files) {
            String fileName = file.getFileName().toString();
            try {
                // Parse
                var result = IrijParseDriver.parseFile(file);
                if (result.hasErrors()) {
                    System.out.println("✗ " + fileName + " (PARSE ERROR)");
                    for (var err : result.errors()) {
                        System.out.println("    " + file + ":" + err);
                    }
                    crashCount++;
                    failedFiles.add(fileName);
                    continue;
                }

                // v0.6.13: bytecode-only execution.
                var baos = new ByteArrayOutputStream();
                var ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
                BytecodeRunner.runFile(file, ps);

                // Count [ok] and [FAIL] lines
                String output = baos.toString(StandardCharsets.UTF_8);
                int okCount = 0;
                int failCount = 0;
                for (String line : output.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("[ok]")) okCount++;
                    else if (trimmed.startsWith("[FAIL]")) failCount++;
                }

                int total = okCount + failCount;
                grandPass += okCount;
                grandFail += failCount;

                if (failCount == 0) {
                    System.out.println("\u2713 " + fileName + " (" + okCount + "/" + total + ")");
                } else {
                    System.out.println("\u2717 " + fileName + " (" + okCount + "/" + total + ", " + failCount + " FAILED)");
                    // Print captured [FAIL] lines for context
                    for (String line : output.split("\n")) {
                        if (line.trim().startsWith("[FAIL]")) {
                            System.out.println("    " + line.trim());
                        }
                    }
                    failedFiles.add(fileName);
                }

            } catch (IrijRuntimeError e) {
                System.out.println("\u2717 " + fileName + " (CRASH: " + e.getMessage() + ")");
                crashCount++;
                failedFiles.add(fileName);
            } catch (IOException e) {
                System.out.println("\u2717 " + fileName + " (IO ERROR: " + e.getMessage() + ")");
                crashCount++;
                failedFiles.add(fileName);
            }
        }

        // Grand total
        int grandTotal = grandPass + grandFail;
        System.out.println();
        System.out.println("─────────────────────────────────────");
        if (grandFail == 0 && crashCount == 0) {
            System.out.println("All passed: " + grandPass + "/" + grandTotal + " tests in " + files.size() + " file(s)");
        } else {
            System.out.println("Total: " + grandPass + "/" + grandTotal + " tests passed"
                    + (crashCount > 0 ? ", " + crashCount + " file(s) crashed" : ""));
            System.out.println("Failed: " + String.join(", ", failedFiles));
        }

        System.exit((grandFail == 0 && crashCount == 0) ? 0 : 1);
    }

    private TestCommand() {}
}
