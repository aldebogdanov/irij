package dev.irij.cli;

import dev.irij.ast.AstBuilder;
import dev.irij.interpreter.Interpreter;
import dev.irij.interpreter.IrijRuntimeError;
import dev.irij.interpreter.Values;
import dev.irij.mcp.IrijMcpServer;
import dev.irij.nrepl.NReplServer;
import dev.irij.parser.IrijParseDriver;
import dev.irij.repl.IrijRepl;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Irij command-line entry point.
 *
 * Usage:
 *   irij                       — launch interactive REPL
 *   irij <file.irj>            — parse and run a source file
 *   irij --parse-only <file>   — parse and report errors, no evaluation
 *   irij --ast <file>          — dump parsed AST (debug)
 *   irij --nrepl-server[=PORT] — start nREPL server (default port 7888)
 *   irij --version             — print version
 */
public final class IrijCli {

    private static final String VERSION = "0.1.0";
    private static final int DEFAULT_NREPL_PORT = 7888;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            launchRepl();
            return;
        }

        // Walk flags
        boolean parseOnly    = false;
        boolean dumpAst      = false;
        boolean mcpServer    = false;
        boolean verifyLaws   = false;
        int     nreplPort    = -1;
        String  filePath     = null;

        for (String arg : args) {
            switch (arg) {
                case "--parse-only" -> parseOnly = true;
                case "--ast"        -> dumpAst   = true;
                case "--nrepl-server" -> nreplPort = DEFAULT_NREPL_PORT;
                case "--mcp-server" -> mcpServer = true;
                case "--verify-laws" -> verifyLaws = true;
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

        runFile(Path.of(filePath), parseOnly, dumpAst, verifyLaws);
    }

    // ── File runner ──────────────────────────────────────────────────────

    private static void runFile(Path path, boolean parseOnly, boolean dumpAst, boolean verifyLaws) throws IOException {
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
            interpreter.setSourcePath(path.toAbsolutePath().getParent());
            if (verifyLaws) interpreter.setAutoVerifyLaws(true);
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

    // ── Help ─────────────────────────────────────────────────────────────

    private static void printHelp() {
        System.out.println("""
            Irij ℑ  programming language

            Usage:
              irij                       start interactive REPL
              irij <file.irj>            run a source file
              irij --parse-only <file>   parse only, report errors
              irij --ast <file>          dump AST (debug)
              irij --verify-laws <file>   run file with automatic law verification on impl
              irij --mcp-server          start MCP server (stdio, for Claude Code)
              irij --nrepl-server        start nREPL server (port 7888)
              irij --nrepl-server=PORT   start nREPL server on PORT
              irij --version             print version
              irij --help                this message""");
    }
}
