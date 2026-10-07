package dev.irij.compiler;

import dev.irij.ast.AstBuilder;
import dev.irij.ast.Decl;
import dev.irij.parser.IrijParseDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Spec-lint: a `pub fn` without a spec annotation is reported. */
class SpecLintTest {

    private static List<String> lint(String source) {
        List<Decl> decls = new AstBuilder().build(IrijParseDriver.parse(source).tree());
        List<String> warnings = new ArrayList<>();
        new ModuleInliner(null, List.of(), warnings::add).inline(decls, "lib.irj");
        return warnings;
    }

    @Test void pubFnWithoutSpecIsReported() {
        var w = lint("pub fn twice\n  (x -> x * 2)\n");
        assertEquals(1, w.size(), w.toString());
        assertTrue(w.get(0).contains("'twice'") && w.get(0).contains("lib.irj"), w.get(0));
    }

    @Test void aModulesPubFnIsNamedAsWritten(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib/d.irj"), "mod lib.d\n\npub fn double\n  (x -> x * 2)\n");
        List<Decl> decls = new AstBuilder().build(IrijParseDriver.parse("use lib.d :open\n").tree());
        List<String> warnings = new ArrayList<>();
        new ModuleInliner(root, List.of(), warnings::add).inline(decls, "main.irj");
        assertEquals(List.of("warning: pub fn 'double' in lib/d.irj has no spec annotation (3:5)"), warnings);
    }

    @Test void specdPrivateAndWildcardFnsAreNot() {
        assertEquals(List.of(), lint("""
                pub fn thrice :: Int Int
                  (x -> x * 3)

                pub fn opaque :: _ _
                  (x -> x)

                fn helper
                  (x -> x)
                """));
    }

    @Test void stdlibIsClean() {
        var src = new StringBuilder();
        for (var m : List.of("time", "auth", "jvm", "datastar", "http", "quint", "serve", "fs",
                "list", "session", "env", "term", "proc", "test", "random", "math", "db",
                "convert", "text", "log", "collection", "func", "quint.itf")) {
            src.append("use std.").append(m).append(" :open\n");
        }
        assertEquals(List.of(), lint(src.toString()));
    }

    @Test void namespaceModeTurnsTheLintOff() {
        assertTrue(CompileOptions.defaults().specLint());
        assertFalse(CompileOptions.defaults().withNamespaceMode(true).specLint());
    }
}
