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
        boolean verbose = false;
        List<String> rest = new ArrayList<>();
        for (String arg : args) {
            if (arg.equals("-v") || arg.equals("--verbose")) verbose = true;
            else rest.add(arg);
        }
        runTests(resolveTestFiles(rest.toArray(String[]::new)), verbose, Ansi.detect());
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

    // ── Output ───────────────────────────────────────────────────────
    //
    // Colour only on a terminal (Ansi). The plain text keeps the grammar
    // tools parse — invar's gates (invar/lang/irij.irj) read it from
    // runs in a container: a file line `✓ name (…)` / `✗ name (…)` with
    // the name directly before ` (`, `PARSE ERROR` on that line, a
    // `[FAIL] name -- why` line with its message continued on lines
    // indented 8, and a summary line starting `All passed:` / `Total:`.
    // TestCommandTest pins it. Timings and alignment come after.

    /**
     * Run test files, print per-file and grand-total summaries, and exit.
     */
    static void runTests(List<Path> files, boolean verbose, Ansi a) {
        if (files.isEmpty()) {
            System.err.println("No test files found.");
            System.exit(1);
            return;
        }

        int grandPass = 0;
        int grandFail = 0;
        int crashCount = 0;
        List<String> failedFiles = new ArrayList<>();
        long started = System.nanoTime();
        int column = files.stream().mapToInt(f -> f.getFileName().toString().length()).max().orElse(0)
                + 20;   // "✓ " + name + " (12/12)" or most "(1/2, 1 FAILED)", + a gap

        System.out.println(a.boldCyan("ℑ") + " " + a.bold("irij test") + a.dim(" · " + plural(files.size(), "file")));
        System.out.println();

        for (Path file : files) {
            String fileName = file.getFileName().toString();
            long t0 = System.nanoTime();
            try {
                // Parse
                var result = IrijParseDriver.parseFile(file);
                if (result.hasErrors()) {
                    System.out.println(a.red("✗ ") + a.bold(fileName) + " " + a.boldRed("(PARSE ERROR)"));
                    for (var err : result.errors()) {
                        System.out.println("    " + a.red(file + ":" + err));
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
                grandPass += okCount;
                grandFail += failCount;

                System.out.println(fileLine(a, fileName, okCount, failCount, millis(t0), column));
                if (failCount > 0) failedFiles.add(fileName);
                for (String line : testLines(a, output, verbose)) System.out.println(line);

            } catch (IrijRuntimeError e) {
                System.out.println(a.red("✗ ") + a.bold(fileName) + " " + a.boldRed("(CRASH: " + e.getMessage() + ")"));
                crashCount++;
                failedFiles.add(fileName);
            } catch (IOException e) {
                System.out.println(a.red("✗ ") + a.bold(fileName) + " " + a.boldRed("(IO ERROR: " + e.getMessage() + ")"));
                crashCount++;
                failedFiles.add(fileName);
            }
        }

        // Grand total
        System.out.println();
        System.out.println(a.dim("─────────────────────────────────────"));
        for (String line : summaryLines(a, grandPass, grandFail, crashCount, files.size(),
                failedFiles, millis(started))) {
            System.out.println(line);
        }

        System.exit((grandFail == 0 && crashCount == 0) ? 0 : 1);
    }

    /** `✓ name (ok/total)` or `✗ name (ok/total, n FAILED)`, then the time
     *  at {@code column}. */
    static String fileLine(Ansi a, String name, int ok, int fail, long ms, int column) {
        int total = ok + fail;
        String head = fail == 0
                ? a.green("✓ ") + a.bold(name) + " " + a.dim("(" + ok + "/" + total + ")")
                : a.red("✗ ") + a.bold(name) + " " + a.red("(" + ok + "/" + total + ", " + fail + " FAILED)");
        return Ansi.pad(head, Math.max(column, Ansi.width(head) + 2)) + a.dim(seconds(ms));
    }

    /** The lines shown under a file: each [FAIL] with the indented lines
     *  that continue its message (std.test indents a multi-line message's
     *  later lines by six spaces; std.quint's report runs over several),
     *  and with {@code verbose} each [ok] too, in the order they ran. */
    static List<String> testLines(Ansi a, String output, boolean verbose) {
        List<String> out = new ArrayList<>();
        boolean inFailure = false;
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[FAIL]")) {
                String rest = trimmed.substring("[FAIL]".length()).trim();
                int sep = rest.indexOf(" -- ");
                String name = sep < 0 ? rest : rest.substring(0, sep);
                String why = sep < 0 ? "" : rest.substring(sep + " -- ".length());
                out.add("    " + a.boldRed("[FAIL]") + " " + a.bold(name)
                        + (sep < 0 ? "" : a.dim(" -- ") + why));
                inFailure = true;
            } else if (inFailure && line.startsWith("      ") && !trimmed.isEmpty()) {
                out.add("        " + trimmed);
            } else {
                inFailure = false;
                if (verbose && trimmed.startsWith("[ok]")) {
                    out.add("    " + a.green("[ok]") + " " + trimmed.substring("[ok]".length()).trim());
                }
            }
        }
        return out;
    }

    /** What `irij test` shows of a failing file, uncoloured. */
    static List<String> failureLines(String output) {
        return testLines(new Ansi(false), output, false);
    }

    /** `All passed: …` or `Total: …` plus `Failed: …`. */
    static List<String> summaryLines(Ansi a, int pass, int fail, int crashed, int files,
                                     List<String> failedFiles, long ms) {
        int total = pass + fail;
        String time = a.dim("  · " + seconds(ms));
        if (fail == 0 && crashed == 0) {
            return List.of(a.boldGreen("All passed:") + " " + a.green(pass + "/" + total + " tests")
                    + " in " + plural(files, "file") + time);
        }
        return List.of(
                a.boldRed("Total:") + " " + pass + "/" + total + " tests passed"
                        + (fail > 0 ? ", " + a.red(fail + " failed") : "")
                        + (crashed > 0 ? ", " + a.red(crashed + " file(s) crashed") : "") + time,
                a.boldRed("Failed:") + " " + a.red(String.join(", ", failedFiles)));
    }

    private static long millis(long t0) { return (System.nanoTime() - t0) / 1_000_000; }

    static String seconds(long ms) {
        return ms < 1000 ? ms + " ms" : String.format(java.util.Locale.ROOT, "%.1f s", ms / 1000.0);
    }

    private static String plural(int n, String what) {
        return n + " " + what + (n == 1 ? "" : "s");
    }

    private TestCommand() {}
}
