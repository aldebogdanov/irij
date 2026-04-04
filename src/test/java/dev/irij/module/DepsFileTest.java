package dev.irij.module;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DepsFileTest {

    @Nested
    class Parsing {

        @Test void parseGitDepWithTag() {
            var deps = DepsFile.parse("""
                dep utils
                  git "https://github.com/user/irij-utils.git"
                  tag "v0.1.0"
                """);
            assertEquals(1, deps.size());
            assertEquals("utils", deps.get(0).name());
            assertInstanceOf(DepsFile.DepSource.GitDep.class, deps.get(0).source());
            var git = (DepsFile.DepSource.GitDep) deps.get(0).source();
            assertEquals("https://github.com/user/irij-utils.git", git.url());
            assertEquals("v0.1.0", git.ref());
        }

        @Test void parseGitDepWithCommit() {
            var deps = DepsFile.parse("""
                dep mylib
                  git "https://github.com/user/mylib.git"
                  commit "a7f3b2c1"
                """);
            assertEquals(1, deps.size());
            var git = (DepsFile.DepSource.GitDep) deps.get(0).source();
            assertEquals("a7f3b2c1", git.ref());
        }

        @Test void parsePathDep() {
            var deps = DepsFile.parse("""
                dep local-lib
                  path "../my-lib"
                """);
            assertEquals(1, deps.size());
            assertEquals("local-lib", deps.get(0).name());
            assertInstanceOf(DepsFile.DepSource.PathDep.class, deps.get(0).source());
            var pathDep = (DepsFile.DepSource.PathDep) deps.get(0).source();
            assertEquals("../my-lib", pathDep.path());
        }

        @Test void parseMultipleDeps() {
            var deps = DepsFile.parse("""
                ;; My project dependencies
                dep utils
                  git "https://github.com/user/utils.git"
                  tag "v1.0"

                dep local
                  path "./libs/local"

                dep other
                  git "https://github.com/user/other.git"
                  commit "abc123"
                """);
            assertEquals(3, deps.size());
            assertEquals("utils", deps.get(0).name());
            assertEquals("local", deps.get(1).name());
            assertEquals("other", deps.get(2).name());
        }

        @Test void parseEmptyFile() {
            var deps = DepsFile.parse("");
            assertTrue(deps.isEmpty());
        }

        @Test void parseCommentsOnly() {
            var deps = DepsFile.parse("""
                ;; just a comment
                ;; another comment
                """);
            assertTrue(deps.isEmpty());
        }

        @Test void errorMissingGitRef() {
            assertThrows(DepsFile.DepsParseError.class, () ->
                DepsFile.parse("""
                    dep broken
                      git "https://example.com/repo.git"
                    """));
        }

        @Test void errorMissingSource() {
            assertThrows(DepsFile.DepsParseError.class, () ->
                DepsFile.parse("""
                    dep no-source
                    """));
        }

        @Test void errorUnquotedValue() {
            assertThrows(DepsFile.DepsParseError.class, () ->
                DepsFile.parse("""
                    dep bad
                      path unquoted-value
                    """));
        }

        @Test void errorUnknownProperty() {
            assertThrows(DepsFile.DepsParseError.class, () ->
                DepsFile.parse("""
                    dep bad
                      url "https://example.com"
                    """));
        }

        @Test void errorMissingName() {
            assertThrows(DepsFile.DepsParseError.class, () ->
                DepsFile.parse("dep \n"));
        }
    }

    @Nested
    class FileLoading {

        @Test void nonExistentFileReturnsEmpty(@TempDir Path tmp) throws IOException {
            var deps = DepsFile.parse(tmp.resolve("deps.irj"));
            assertTrue(deps.isEmpty());
        }

        @Test void loadFromFile(@TempDir Path tmp) throws IOException {
            var depsFile = tmp.resolve("deps.irj");
            Files.writeString(depsFile, """
                dep mylib
                  path "./lib"
                """);
            var deps = DepsFile.parse(depsFile);
            assertEquals(1, deps.size());
            assertEquals("mylib", deps.get(0).name());
        }
    }
}
