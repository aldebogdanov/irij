package dev.irij.module;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProjectFileTest {

    @Nested
    class Parsing {

        @Test void parseGitDepWithTag() {
            var result = ProjectFile.parse("""
                [deps.utils]
                git = "https://github.com/user/irij-utils.git"
                tag = "v0.1.0"
                """);
            assertEquals(1, result.deps().size());
            assertEquals("utils", result.deps().get(0).name());
            assertInstanceOf(ProjectFile.DepSource.GitDep.class, result.deps().get(0).source());
            var git = (ProjectFile.DepSource.GitDep) result.deps().get(0).source();
            assertEquals("https://github.com/user/irij-utils.git", git.url());
            assertEquals("v0.1.0", git.ref());
        }

        @Test void parseGitDepWithCommit() {
            var result = ProjectFile.parse("""
                [deps.mylib]
                git = "https://github.com/user/mylib.git"
                commit = "a7f3b2c1"
                """);
            assertEquals(1, result.deps().size());
            var git = (ProjectFile.DepSource.GitDep) result.deps().get(0).source();
            assertEquals("a7f3b2c1", git.ref());
        }

        @Test void parsePathDep() {
            var result = ProjectFile.parse("""
                [deps.local-lib]
                path = "../my-lib"
                """);
            assertEquals(1, result.deps().size());
            assertEquals("local-lib", result.deps().get(0).name());
            assertInstanceOf(ProjectFile.DepSource.PathDep.class, result.deps().get(0).source());
            var pathDep = (ProjectFile.DepSource.PathDep) result.deps().get(0).source();
            assertEquals("../my-lib", pathDep.path());
        }

        @Test void parseMultipleDeps() {
            var result = ProjectFile.parse("""
                # My project dependencies
                [deps.utils]
                git = "https://github.com/user/utils.git"
                tag = "v1.0"

                [deps.local]
                path = "./libs/local"

                [deps.other]
                git = "https://github.com/user/other.git"
                commit = "abc123"
                """);
            assertEquals(3, result.deps().size());
        }

        @Test void parseEmptyFile() {
            var result = ProjectFile.parse("");
            assertTrue(result.deps().isEmpty());
            assertNull(result.meta());
        }

        @Test void parseCommentsOnly() {
            var result = ProjectFile.parse("""
                # just a comment
                # another comment
                """);
            assertTrue(result.deps().isEmpty());
        }

        @Test void parseProjectMeta() {
            var result = ProjectFile.parse("""
                [project]
                name = "my-app"
                version = "0.1.0"
                description = "My Irij application"
                author = "user"
                license = "MIT"
                """);
            assertNotNull(result.meta());
            assertEquals("my-app", result.meta().name());
            assertEquals("0.1.0", result.meta().version());
            assertEquals("My Irij application", result.meta().description());
            assertEquals("user", result.meta().author());
            assertEquals("MIT", result.meta().license());
        }

        @Test void parseProjectMetaWithDeps() {
            var result = ProjectFile.parse("""
                [project]
                name = "my-app"
                version = "0.1.0"

                [deps.vrata]
                git = "https://github.com/aldebogdanov/vrata.git"
                tag = "v0.1.1"
                """);
            assertNotNull(result.meta());
            assertEquals("my-app", result.meta().name());
            assertEquals(1, result.deps().size());
            assertEquals("vrata", result.deps().get(0).name());
        }

        @Test void errorMissingGitRef() {
            assertThrows(ProjectFile.ParseError.class, () ->
                ProjectFile.parse("""
                    [deps.broken]
                    git = "https://example.com/repo.git"
                    """));
        }

        @Test void errorMissingSource() {
            assertThrows(ProjectFile.ParseError.class, () ->
                ProjectFile.parse("""
                    [deps.no-source]
                    """));
        }
    }

    @Nested
    class FileLoading {

        @Test void nonExistentFileReturnsEmpty(@TempDir Path tmp) throws IOException {
            var result = ProjectFile.parseFile(tmp.resolve("irij.toml"));
            assertTrue(result.deps().isEmpty());
            assertNull(result.meta());
        }

        @Test void loadFromFile(@TempDir Path tmp) throws IOException {
            var tomlFile = tmp.resolve("irij.toml");
            Files.writeString(tomlFile, """
                [deps.mylib]
                path = "./lib"
                """);
            var result = ProjectFile.parseFile(tomlFile);
            assertEquals(1, result.deps().size());
            assertEquals("mylib", result.deps().get(0).name());
        }
    }
}
