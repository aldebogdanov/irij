package dev.irij.cli;

import dev.irij.ast.AstBuilder;
import dev.irij.compiler.IrijCompiler;
import dev.irij.IrijRuntimeError;
import dev.irij.mcp.IrijMcpServer;
import dev.irij.nrepl.NReplServer;
import dev.irij.parser.IrijParseDriver;
import dev.irij.repl.IrijRepl;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Irij command-line entry point. Word subcommands dispatch to their
 * own classes ({@link TestCommand}, {@link BuildCommand}, …); flags
 * and bare file paths fall through to the file runner.
 *
 * Usage:
 *   irij                       — launch interactive REPL
 *   irij <file.irj>            — parse and run a source file
 *   irij run <file.irj>        — same, explicit
 *   irij test [files|dirs...]  — run test files (auto-discovers test-*.irj)
 *   irij build | compile | install | publish | version | lsp | help
 *   irij --parse-only <file>   — parse and report errors, no evaluation
 *   irij --ast <file>          — dump parsed AST (debug)
 *   irij --nrepl-server[=PORT] — start nREPL server (default port 7888)
 *   irij --mcp-server          — start MCP server (stdio)
 *   irij --version             — print engine version
 */
public final class IrijCli {

    public static final String VERSION = loadVersion();
    private static final int DEFAULT_NREPL_PORT = 7888;

    private static String loadVersion() {
        try (var is = IrijCli.class.getResourceAsStream("/irij-version.properties")) {
            if (is != null) {
                var props = new java.util.Properties();
                props.load(is);
                return props.getProperty("version", "dev");
            }
        } catch (Exception ignored) {}
        return "dev";
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            launchRepl();
            return;
        }

        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
        switch (args[0]) {
            case "test"             -> { TestCommand.run(args); return; }
            case "install", "seed"  -> { InstallCommand.run(); return; }
            case "publish", "sow"   -> { PublishCommand.run(); return; }
            case "version"          -> { VersionCommand.run(rest); return; }
            case "build"            -> { BuildCommand.run(rest); return; }
            case "compile"          -> { CompileCommand.run(rest); return; }
            case "lsp"              -> { dev.irij.lsp.IrijLspServer.run(); return; }
            case "repl"             -> { launchRepl(); return; }
            case "help"             -> { printHelp(); return; }
            case "run"              -> {
                if (rest.length == 0) {
                    System.err.println("Usage: irij run <file.irj>");
                    System.exit(1);
                }
                args = rest;
            }
            default -> { /* flags / file path — handled below */ }
        }

        // Walk flags
        boolean parseOnly    = false;
        boolean dumpAst      = false;
        boolean mcpServer    = false;
        boolean noSpecLint   = false;
        int     nreplPort    = -1;
        String  filePath     = null;

        for (String arg : args) {
            switch (arg) {
                case "--parse-only" -> parseOnly = true;
                case "--ast"        -> dumpAst   = true;
                case "--nrepl-server" -> nreplPort = DEFAULT_NREPL_PORT;
                case "--mcp-server" -> mcpServer = true;
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
                        System.err.println("Run 'irij help' for usage.");
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

        runFile(Path.of(filePath), parseOnly, dumpAst, noSpecLint);
    }

    // ── File runner ──────────────────────────────────────────────────────

    private static void runFile(Path path, boolean parseOnly, boolean dumpAst, boolean noSpecLint) throws IOException {
        IrijParseDriver.ParseResult result;
        try {
            result = IrijParseDriver.parseFile(path);
        } catch (IOException e) {
            System.err.println("Cannot read file: " + path + ": " + e.getMessage());
            System.err.println("Run 'irij help' for usage.");
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

        if (dumpAst) {
            var ast = new AstBuilder().build(result.tree());
            for (var decl : ast) {
                System.out.println(decl);
            }
            return;
        }

        // v0.6.13: single execution model — bytecode. The interpreter
        // was removed in R5d.
        try {
            BytecodeRunner.runFile(path, null);
        } catch (IrijCompiler.CompileException e) {
            System.err.println(path + ":" + e.getMessage());
            System.exit(1);
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

    // ── Help ─────────────────────────────────────────────────────────────

    private static void printHelp() {
        System.out.println("""
            Irij ℑ  programming language

            Usage:
              irij                       start interactive REPL (alias: irij repl)
              irij <file.irj>            run a source file (alias: irij run <file.irj>)
              irij build                 package app into self-contained JAR (bytecode-sm, default since v0.6.x)
              irij build <file.irj>      build with explicit entry point
              irij build -o out.jar      build with custom output path
              irij build --mode=interp   build with legacy interpreter bundling (deprecated)
              irij compile <file.irj>    (experimental) compile to .class
              irij compile <file> -o j.jar  (experimental) compile to runnable jar
              irij install               fetch seeds from irij.toml (alias: seed)
              irij publish               publish seed to registry (alias: sow)
              irij version               print this project's MAJOR.MINOR.<commit-count> version
              irij version --base|--count   print just the base / commit count
              irij test                  run all test-*.irj in ./tests/
              irij test <file.irj>       run a specific test file
              irij test <dir/>           run all test-*.irj in directory
              irij test f1.irj f2.irj    run multiple test files
              irij --parse-only <file>   parse only, report errors
              irij --ast <file>          dump AST (debug)
              irij --no-spec-lint <file> disable spec lint warnings (on by default)
              irij lsp                   start LSP server (stdio, for editor integration)
              irij --mcp-server          start MCP server (stdio, for Claude Code)
              irij --nrepl-server        start nREPL server (port 7888)
              irij --nrepl-server=PORT   start nREPL server on PORT
              irij --version             print engine version
              irij help                  this message (also --help)""");
    }
}
