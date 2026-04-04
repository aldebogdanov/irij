package dev.irij.module;

import dev.irij.ast.AstBuilder;
import dev.irij.interpreter.Interpreter;
import dev.irij.parser.IrijParseDriver;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DepIntegrationTest {

    private String runWithDeps(Path projectRoot, String source) {
        var baos = new ByteArrayOutputStream();
        var out = new PrintStream(baos);
        var parseResult = IrijParseDriver.parse(source + "\n");
        assertFalse(parseResult.hasErrors(),
            () -> "Parse errors: " + parseResult.errors());
        var ast = new AstBuilder().build(parseResult.tree());
        var interp = new Interpreter(out);
        interp.setSpecLintEnabled(false);
        interp.setSourcePath(projectRoot);
        interp.loadDeps(projectRoot);
        interp.run(ast);
        return baos.toString().strip();
    }

    @Nested
    class LocalDepModules {

        @Test void useLocalDepModule(@TempDir Path tmp) throws IOException {
            // Create a local dependency with a module
            var libDir = tmp.resolve("mylib");
            Files.createDirectories(libDir);
            Files.writeString(libDir.resolve("mylib.irj"), """
                mod mylib
                pub fn greet
                  (name -> "hello " ++ name)
                """);

            // Create deps.irj
            Files.writeString(tmp.resolve("deps.irj"), """
                dep mylib
                  path "mylib"
                """);

            var output = runWithDeps(tmp, """
                use mylib
                println ~ mylib.greet "world"
                """);
            assertEquals("hello world", output);
        }

        @Test void useLocalDepSubModule(@TempDir Path tmp) throws IOException {
            // Create a dep with sub-modules
            var libDir = tmp.resolve("utils");
            Files.createDirectories(libDir.resolve("src"));
            Files.writeString(libDir.resolve("src/helpers.irj"), """
                mod utils.helpers
                pub fn double
                  (x -> x * 2)
                """);

            Files.writeString(tmp.resolve("deps.irj"), """
                dep utils
                  path "utils"
                """);

            var output = runWithDeps(tmp, """
                use utils.helpers :open
                println ~ double 21
                """);
            assertEquals("42", output);
        }

        @Test void useLocalDepOpenImport(@TempDir Path tmp) throws IOException {
            var libDir = tmp.resolve("mathext");
            Files.createDirectories(libDir);
            Files.writeString(libDir.resolve("mathext.irj"), """
                mod mathext
                pub fn cube
                  (x -> x * x * x)
                pub fn square
                  (x -> x * x)
                """);

            Files.writeString(tmp.resolve("deps.irj"), """
                dep mathext
                  path "mathext"
                """);

            var output = runWithDeps(tmp, """
                use mathext :open
                println ~ cube 3
                """);
            assertEquals("27", output);
        }

        @Test void useLocalDepSelectiveImport(@TempDir Path tmp) throws IOException {
            var libDir = tmp.resolve("tools");
            Files.createDirectories(libDir);
            Files.writeString(libDir.resolve("tools.irj"), """
                mod tools
                pub fn add-one
                  (x -> x + 1)
                pub fn add-two
                  (x -> x + 2)
                """);

            Files.writeString(tmp.resolve("deps.irj"), """
                dep tools
                  path "tools"
                """);

            var output = runWithDeps(tmp, """
                use tools {add-one}
                println ~ add-one 9
                """);
            assertEquals("10", output);
        }

        @Test void noDepsFileIsOk(@TempDir Path tmp) {
            // No deps.irj — should work fine, just no dep modules available
            var output = runWithDeps(tmp, "println 42\n");
            assertEquals("42", output);
        }

        @Test void emptyDepsFileIsOk(@TempDir Path tmp) throws IOException {
            Files.writeString(tmp.resolve("deps.irj"), ";; no deps yet\n");
            var output = runWithDeps(tmp, "println 42\n");
            assertEquals("42", output);
        }

        @Test void depWithModIrjFallback(@TempDir Path tmp) throws IOException {
            // When dep name doesn't match any file, fall back to mod.irj
            var libDir = tmp.resolve("greeter");
            Files.createDirectories(libDir);
            Files.writeString(libDir.resolve("mod.irj"),
                "mod greeter\npub fn hi\n  (x -> \"hi \" ++ x)\n");

            Files.writeString(tmp.resolve("deps.irj"), """
                dep greeter
                  path "greeter"
                """);

            var output = runWithDeps(tmp, """
                use greeter :open
                println ~ hi "world"
                """);
            assertEquals("hi world", output);
        }

        @Test void depWithSrcSubdir(@TempDir Path tmp) throws IOException {
            // Dep has src/ subdirectory convention
            var libDir = tmp.resolve("fancy");
            Files.createDirectories(libDir.resolve("src"));
            Files.writeString(libDir.resolve("src/fancy.irj"), """
                mod fancy
                pub fn decorate
                  (s -> "~" ++ s ++ "~")
                """);

            Files.writeString(tmp.resolve("deps.irj"), """
                dep fancy
                  path "fancy"
                """);

            var output = runWithDeps(tmp, """
                use fancy :open
                println ~ decorate "hello"
                """);
            assertEquals("~hello~", output);
        }
    }
}
