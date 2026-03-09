package dev.irij.cli;

import dev.irij.interpreter.IrijRepl;
import dev.irij.interpreter.PurityMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Main CLI entry point for Irij.
 *
 * <p>Usage:
 * <pre>
 *   irij                              REPL
 *   irij repl                         REPL
 *   irij file.irj                     interpret file
 *   irij run file.irj                 interpret file
 *   irij build file.irj               compile to .class (not yet implemented)
 *   irij -e 'code'                    evaluate inline code
 *   irij --help                       show help
 *   irij --version                    show version
 * </pre>
 *
 * <p>Flags: --strict, --warn-impure, --allow-impure, -o &lt;dir&gt;
 */
public final class IrijCli {

    static final String VERSION = "0.1.0";

    public static void main(String[] args) {
        PurityMode purityMode = PurityMode.ALLOW;
        List<String> positional = new ArrayList<>();
        String outputDir = null;

        // Parse flags
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--strict", "--warn-impure", "--allow-impure" ->
                        purityMode = PurityMode.fromFlag(args[i]);
                case "-o", "--output" -> {
                    if (i + 1 < args.length) {
                        outputDir = args[++i];
                    } else {
                        System.err.println("Error: -o requires a directory argument");
                        System.exit(1);
                    }
                }
                default -> positional.add(args[i]);
            }
        }

        // Determine command
        if (positional.isEmpty()) {
            runRepl();
            return;
        }

        String first = positional.get(0);

        switch (first) {
            case "--help", "-h" -> printHelp();
            case "--version", "-v" -> System.out.println("irij " + VERSION);
            case "repl" -> runRepl();
            case "run" -> {
                if (positional.size() < 2) {
                    System.err.println("Usage: irij run <file.irj>");
                    System.exit(1);
                }
                System.exit(runFile(positional.get(1), purityMode));
            }
            case "build" -> {
                if (positional.size() < 2) {
                    System.err.println("Usage: irij build <file.irj> [-o <dir>]");
                    System.exit(1);
                }
                System.err.println("Compiler not yet implemented. Use 'irij run' to interpret.");
                System.exit(1);
            }
            case "-e" -> {
                if (positional.size() < 2) {
                    System.err.println("Usage: irij -e '<code>'");
                    System.exit(1);
                }
                System.exit(Pipeline.interpretScript(positional.get(1), purityMode));
            }
            default -> {
                if (first.endsWith(".irj")) {
                    System.exit(runFile(first, purityMode));
                } else {
                    System.err.println("Unknown command: " + first);
                    System.err.println("Run 'irij --help' for usage.");
                    System.exit(1);
                }
            }
        }
    }

    private static int runFile(String path, PurityMode purityMode) {
        try {
            String source = Files.readString(Path.of(path));
            return Pipeline.interpret(source, purityMode);
        } catch (IOException e) {
            System.err.println("Error: cannot read file: " + path);
            return 1;
        }
    }

    private static void runRepl() {
        try {
            new IrijRepl().run();
        } catch (IOException e) {
            System.err.println("Error: failed to start REPL: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printHelp() {
        System.out.println("""
                irij — the Irij programming language

                Usage:
                  irij [file.irj]            Run an Irij program
                  irij run <file.irj>        Run an Irij program
                  irij build <file.irj>      Compile to JVM bytecode (coming soon)
                  irij repl                  Start interactive REPL
                  irij -e '<code>'           Evaluate inline code

                Flags:
                  --allow-impure             Allow direct IO (default)
                  --warn-impure              Warn on direct IO
                  --strict                   Error on direct IO outside handlers
                  -o, --output <dir>         Output directory for build
                  --version, -v              Show version
                  --help, -h                 Show this help

                Examples:
                  irij examples/showcase.irj
                  irij -e 'fn main :: () -> () / io.stdout.write "hello"'
                  irij repl""");
    }
}
