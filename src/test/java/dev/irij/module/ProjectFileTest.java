package dev.irij.module;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectFileTest {

    @Nested
    class Parsing {

        @Test void parseGitSeedWithTag() {
            var result = ProjectFile.parse("""
                [seeds.utils]
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

        @Test void parseGitSeedWithCommit() {
            var result = ProjectFile.parse("""
                [seeds.mylib]
                git = "https://github.com/user/mylib.git"
                commit = "a7f3b2c1"
                """);
            assertEquals(1, result.deps().size());
            var git = (ProjectFile.DepSource.GitDep) result.deps().get(0).source();
            assertEquals("a7f3b2c1", git.ref());
        }

        @Test void parsePathSeed() {
            var result = ProjectFile.parse("""
                [seeds.local-lib]
                path = "../my-lib"
                """);
            assertEquals(1, result.deps().size());
            assertEquals("local-lib", result.deps().get(0).name());
            assertInstanceOf(ProjectFile.DepSource.PathDep.class, result.deps().get(0).source());
            var pathDep = (ProjectFile.DepSource.PathDep) result.deps().get(0).source();
            assertEquals("../my-lib", pathDep.path());
        }

        @Test void parseRegistryShorthand() {
            var result = ProjectFile.parse("""
                [seeds]
                vrata = "0.1.1"
                utils = "1.0.0"
                """);
            assertEquals(2, result.deps().size());
            var vrata = result.deps().stream()
                .filter(d -> d.name().equals("vrata")).findFirst().orElseThrow();
            assertInstanceOf(ProjectFile.DepSource.RegistryDep.class, vrata.source());
            assertEquals("0.1.1", ((ProjectFile.DepSource.RegistryDep) vrata.source()).version());
        }

        @Test void parseInlineGitSeed() {
            var result = ProjectFile.parse("""
                [seeds]
                router = { git = "https://github.com/user/router.git", tag = "v0.2.0" }
                """);
            assertEquals(1, result.deps().size());
            assertInstanceOf(ProjectFile.DepSource.GitDep.class, result.deps().get(0).source());
            var git = (ProjectFile.DepSource.GitDep) result.deps().get(0).source();
            assertEquals("https://github.com/user/router.git", git.url());
            assertEquals("v0.2.0", git.ref());
        }

        @Test void parseInlinePathSeed() {
            var result = ProjectFile.parse("""
                [seeds]
                local-lib = { path = "../my-lib" }
                """);
            assertEquals(1, result.deps().size());
            assertInstanceOf(ProjectFile.DepSource.PathDep.class, result.deps().get(0).source());
        }

        @Test void parseMixedSeeds() {
            var result = ProjectFile.parse("""
                [seeds]
                vrata = "0.1.1"
                router = { git = "https://github.com/user/router.git", tag = "v0.2.0" }
                local-dev = { path = "../dev" }
                """);
            assertEquals(3, result.deps().size());
        }

        @Test void parseMultipleFullTableSeeds() {
            var result = ProjectFile.parse("""
                [seeds.utils]
                git = "https://github.com/user/utils.git"
                tag = "v1.0"

                [seeds.local]
                path = "./libs/local"

                [seeds.other]
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
            // Link fields absent → empty strings, never null
            assertEquals("", result.meta().website());
            assertEquals("", result.meta().repo());
            assertEquals("", result.meta().docs());
        }

        @Test void parseProjectMetaLinks() {
            var result = ProjectFile.parse("""
                [project]
                name = "my-app"
                version = "0.1"
                website = "https://example.org"
                repo = "https://github.com/u/my-app"
                docs = "https://example.org/docs"
                """);
            assertNotNull(result.meta());
            assertEquals("https://example.org", result.meta().website());
            assertEquals("https://github.com/u/my-app", result.meta().repo());
            assertEquals("https://example.org/docs", result.meta().docs());
        }

        @Test void parseProjectMetaWithSeeds() {
            var result = ProjectFile.parse("""
                [project]
                name = "my-app"
                version = "0.1.0"

                [seeds]
                vrata = "0.1.1"
                """);
            assertNotNull(result.meta());
            assertEquals("my-app", result.meta().name());
            assertEquals(1, result.deps().size());
            assertEquals("vrata", result.deps().get(0).name());
        }

        @Test void errorMissingGitRef() {
            assertThrows(ProjectFile.ParseError.class, () ->
                ProjectFile.parse("""
                    [seeds.broken]
                    git = "https://example.com/repo.git"
                    """));
        }

        @Test void errorMissingSource() {
            assertThrows(ProjectFile.ParseError.class, () ->
                ProjectFile.parse("""
                    [seeds.no-source]
                    """));
        }

        @Test void registryVersionInTable() {
            var result = ProjectFile.parse("""
                [seeds.vrata]
                version = "0.1.1"
                """);
            assertEquals(1, result.deps().size());
            assertInstanceOf(ProjectFile.DepSource.RegistryDep.class, result.deps().get(0).source());
            assertEquals("0.1.1", ((ProjectFile.DepSource.RegistryDep) result.deps().get(0).source()).version());
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
                [seeds]
                mylib = { path = "./lib" }
                """);
            var result = ProjectFile.parseFile(tomlFile);
            assertEquals(1, result.deps().size());
            assertEquals("mylib", result.deps().get(0).name());
        }
    }

    @Nested
    class JvmSection {

        @Test void parsesQuotedCoordinates() {
            var jvm = ProjectFile.parse("""
                [jvm]
                "org.neo4j.driver:neo4j-java-driver" = "5.28.5"
                "com.example:util" = "1.0"
                """).jvm();
            assertEquals(List.of("com.example:util:1.0", "org.neo4j.driver:neo4j-java-driver:5.28.5"),
                    jvm.deps().stream().map(ProjectFile.JavaDep::coord).toList());
            assertTrue(jvm.repos().isEmpty());
        }

        @Test void absentSectionIsEmpty() {
            assertTrue(ProjectFile.parse("[project]\nname = \"x\"\n").jvm().isEmpty());
        }

        @Test void reposAreRead() {
            var jvm = ProjectFile.parse("""
                [jvm]
                repos = ["https://repo.example/m2", "file:///tmp/m2"]
                """).jvm();
            assertEquals(List.of("https://repo.example/m2", "file:///tmp/m2"), jvm.repos());
        }

        @Test void versionMustBeExact() {
            for (String v : List.of("[1.0,2.0)", "LATEST", "RELEASE", "1.0-SNAPSHOT", "${v}", "1 .0")) {
                var e = assertThrows(ProjectFile.ParseError.class,
                        () -> ProjectFile.parse("[jvm]\n\"a:b\" = \"" + v + "\"\n"), v);
                assertTrue(e.getMessage().contains("must be exact"), e.getMessage());
            }
        }

        @Test void keyMustBeGroupColonArtifact() {
            for (String k : List.of("a", "a:b:c", "a:../b", ":b")) {
                var e = assertThrows(ProjectFile.ParseError.class,
                        () -> ProjectFile.parse("[jvm]\n\"" + k + "\" = \"1.0\"\n"), k);
                assertTrue(e.getMessage().contains("Maven coordinate"), e.getMessage());
            }
        }

        @Test void reposMustBeUrls() {
            assertThrows(ProjectFile.ParseError.class,
                    () -> ProjectFile.parse("[jvm]\nrepos = [\"ftp://x\"]\n"));
            assertThrows(ProjectFile.ParseError.class,
                    () -> ProjectFile.parse("[jvm]\nrepos = \"https://x\"\n"));
        }

        @Test void trustJarsNamesSeeds() {
            var trust = ProjectFile.parse("""
                [trust]
                jars = ["neo4irij", "acme.*"]
                """).trust();
            assertTrue(trust.allowsJars("neo4irij"));
            assertTrue(trust.allowsJars("acme.db"));
            assertFalse(trust.allowsJars("other"));
        }
    }
}
