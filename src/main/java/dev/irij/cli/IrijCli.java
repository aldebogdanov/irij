package dev.irij.cli;

import dev.irij.ast.AstBuilder;
import dev.irij.interpreter.Interpreter;
import dev.irij.interpreter.IrijRuntimeError;
import dev.irij.interpreter.Values;
import dev.irij.mcp.IrijMcpServer;
import dev.irij.nrepl.NReplServer;
import dev.irij.parser.IrijParseDriver;
import dev.irij.repl.IrijRepl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Irij command-line entry point.
 *
 * Usage:
 *   irij                       — launch interactive REPL
 *   irij <file.irj>            — parse and run a source file
 *   irij test [files|dirs...]  — run test files (auto-discovers test-*.irj)
 *   irij --parse-only <file>   — parse and report errors, no evaluation
 *   irij --ast <file>          — dump parsed AST (debug)
 *   irij --nrepl-server[=PORT] — start nREPL server (default port 7888)
 *   irij --version             — print version
 */
public final class IrijCli {

    private static final String VERSION = "0.2.0";
    private static final int DEFAULT_NREPL_PORT = 7888;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            // Check if running from a bundled JAR (irij build output)
            String bundledEntry = getBundledEntryPoint();
            if (bundledEntry != null) {
                runBundled(bundledEntry);
                return;
            }
            launchRepl();
            return;
        }

        // ── test subcommand ──────────────────────────────────────────────
        if (args[0].equals("test")) {
            List<Path> testFiles = resolveTestFiles(args);
            runTests(testFiles);
            return;
        }

        // ── install subcommand ──────────────────────────────────────────
        if (args[0].equals("install")) {
            runInstall();
            return;
        }

        // ── build subcommand ────────────────────────────────────────────
        if (args[0].equals("build")) {
            BuildCommand.run(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }

        // Walk flags
        boolean parseOnly    = false;
        boolean dumpAst      = false;
        boolean mcpServer    = false;
        boolean verifyLaws   = false;
        boolean noSpecLint   = false;
        int     nreplPort    = -1;
        String  filePath     = null;

        for (String arg : args) {
            switch (arg) {
                case "--parse-only" -> parseOnly = true;
                case "--ast"        -> dumpAst   = true;
                case "--nrepl-server" -> nreplPort = DEFAULT_NREPL_PORT;
                case "--mcp-server" -> mcpServer = true;
                case "--verify-laws" -> verifyLaws = true;
                case "--no-spec-lint" -> noSpecLint = true;
                case "--version", "-v" -> {
                    System.out.println("Irij ℑ  version " + VERSION);
                    return;
                }
                case "--help", "-h" -> {
                    printHelp();
                    return;
                }
                default -> {
                    if (arg.startsWith("--nrepl-server=")) {
                        try {
                            nreplPort = Integer.parseInt(arg.substring("--nrepl-server=".length()));
                        } catch (NumberFormatException e) {
                            System.err.println("Invalid port: " + arg);
                            System.exit(1);
                        }
                    } else if (arg.startsWith("-")) {
                        System.err.println("Unknown flag: " + arg);
                        System.err.println("Run 'irij --help' for usage.");
                        System.exit(1);
                    } else {
                        filePath = arg;
                    }
                }
            }
        }

        // MCP server mode (stdio JSON-RPC)
        if (mcpServer) {
            var root = Path.of(System.getProperty("user.dir"));
            new IrijMcpServer(root).start();
            return;
        }

        // nREPL server mode
        if (nreplPort >= 0) {
            new NReplServer(nreplPort).start();
            return;
        }

        if (filePath == null) {
            System.err.println("Error: no input file specified.");
            printHelp();
            System.exit(1);
        }

        runFile(Path.of(filePath), parseOnly, dumpAst, verifyLaws, noSpecLint);
    }

    // ── File runner ──────────────────────────────────────────────────────

    private static void runFile(Path path, boolean parseOnly, boolean dumpAst, boolean verifyLaws, boolean noSpecLint) throws IOException {
        IrijParseDriver.ParseResult result;
        try {
            result = IrijParseDriver.parseFile(path);
        } catch (IOException e) {
            System.err.println("Cannot read file: " + path + ": " + e.getMessage());
            System.exit(1);
            return;
        }

        if (result.hasErrors()) {
            for (var err : result.errors()) {
                System.err.println(path + ":" + err);
            }
            System.exit(1);
            return;
        }

        if (parseOnly) {
            System.out.println("OK — no parse errors in " + path);
            return;
        }

        var ast = new AstBuilder().build(result.tree());

        if (dumpAst) {
            for (var decl : ast) {
                System.out.println(decl);
            }
            return;
        }

        try {
            var interpreter = new Interpreter();
            var projectRoot = path.toAbsolutePath().getParent();
            interpreter.setSourcePath(projectRoot);
            interpreter.loadDeps(projectRoot);
            if (verifyLaws) interpreter.setAutoVerifyLaws(true);
            if (noSpecLint) interpreter.setSpecLintEnabled(false);
            interpreter.run(ast);
        } catch (IrijRuntimeError e) {
            System.err.println(path + ":" + e.getMessage());
            System.exit(1);
        }
    }

    // ── REPL launcher ────────────────────────────────────────────────────

    private static void launchRepl() throws Exception {
        try {
            new IrijRepl().run();
        } catch (Exception e) {
            System.err.println("REPL error: " + e.getMessage());
            System.exit(1);
        }
    }

    // ── Test runner ────────────────────────────────────────────────────

    /**
     * Resolve test file paths from CLI args.
     * If no args after "test", default to ./tests/ directory.
     */
    private static List<Path> resolveTestFiles(String[] args) {
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
    private static List<Path> discoverTestFiles(Path dir) {
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
    private static void runTests(List<Path> files) {
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
                    System.out.println("\u2717 " + fileName + " (PARSE ERROR)");
                    for (var err : result.errors()) {
                        System.out.println("    " + file + ":" + err);
                    }
                    crashCount++;
                    failedFiles.add(fileName);
                    continue;
                }

                var ast = new AstBuilder().build(result.tree());

                // Run in fresh interpreter with captured output
                var baos = new ByteArrayOutputStream();
                var ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
                var interpreter = new Interpreter(ps);
                interpreter.setSourcePath(file.toAbsolutePath().getParent());
                interpreter.run(ast);

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

    // ── Install ──────────────────────────────────────────────────────────

    private static void runInstall() {
        var projectRoot = Path.of(System.getProperty("user.dir"));
        var depsFile = projectRoot.resolve("deps.irj");

        if (!Files.exists(depsFile)) {
            System.out.println("No deps.irj found in " + projectRoot);
            return;
        }

        try {
            var deps = dev.irij.module.DepsFile.parse(depsFile);
            if (deps.isEmpty()) {
                System.out.println("deps.irj is empty — no dependencies to install.");
                return;
            }

            System.out.println("Installing " + deps.size() + " dependenc"
                + (deps.size() == 1 ? "y" : "ies") + " ...");
            var resolver = new dev.irij.module.DependencyResolver(projectRoot, System.out);
            var resolved = resolver.resolveAll(deps);

            System.out.println();
            for (var entry : resolved.entrySet()) {
                System.out.println("  " + entry.getKey() + " → " + entry.getValue());
            }
            System.out.println("\nDone.");
        } catch (dev.irij.module.DepsFile.DepsParseError e) {
            System.err.println("Error in deps.irj: " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Error installing dependencies: " + e.getMessage());
            System.exit(1);
        }
    }

    // ── Bundled JAR runner ────────────────────────────────────────────────

    /** Check if this JAR has a bundled entry point (built with irij build). */
    private static String getBundledEntryPoint() {
        try {
            var url = IrijCli.class.getProtectionDomain().getCodeSource().getLocation();
            if (url != null) {
                var jarPath = Path.of(url.toURI());
                if (java.nio.file.Files.isRegularFile(jarPath) && jarPath.toString().endsWith(".jar")) {
                    try (var jf = new java.util.jar.JarFile(jarPath.toFile())) {
                        var manifest = jf.getManifest();
                        if (manifest != null) {
                            return manifest.getMainAttributes().getValue("Irij-Entry-Point");
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Run a bundled application from inside the JAR. */
    private static void runBundled(String entryPoint) {
        var cl = IrijCli.class.getClassLoader();

        // Load entry point source from __irij_app/
        String source;
        try (var is = cl.getResourceAsStream("__irij_app/" + entryPoint)) {
            if (is == null) {
                System.err.println("Bundled entry point not found: __irij_app/" + entryPoint);
                System.exit(1);
                return;
            }
            source = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Error reading bundled entry: " + e.getMessage());
            System.exit(1);
            return;
        }

        // Parse
        var result = IrijParseDriver.parse(source);
        if (result.hasErrors()) {
            for (var err : result.errors()) {
                System.err.println(entryPoint + ":" + err);
            }
            System.exit(1);
            return;
        }

        var ast = new AstBuilder().build(result.tree());

        try {
            var interpreter = new Interpreter();
            interpreter.setBundledMode(true);
            interpreter.run(ast);
        } catch (IrijRuntimeError e) {
            System.err.println(entryPoint + ":" + e.getMessage());
            System.exit(1);
        }
    }

    // ── Help ─────────────────────────────────────────────────────────────

    private static void printHelp() {
        System.out.println("""
            Irij ℑ  programming language

            Usage:
              irij                       start interactive REPL
              irij <file.irj>            run a source file
              irij build                 package app into self-contained JAR
              irij build <file.irj>      build with explicit entry point
              irij build -o out.jar      build with custom output path
              irij install               fetch dependencies from deps.irj
              irij test                  run all test-*.irj in ./tests/
              irij test <file.irj>       run a specific test file
              irij test <dir/>           run all test-*.irj in directory
              irij test f1.irj f2.irj    run multiple test files
              irij --parse-only <file>   parse only, report errors
              irij --ast <file>          dump AST (debug)
              irij --verify-laws <file>  run file with automatic law verification on impl
              irij --no-spec-lint <file> disable spec lint warnings (on by default)
              irij --mcp-server          start MCP server (stdio, for Claude Code)
              irij --nrepl-server        start nREPL server (port 7888)
              irij --nrepl-server=PORT   start nREPL server on PORT
              irij --version             print version
              irij --help                this message""");
    }
}
