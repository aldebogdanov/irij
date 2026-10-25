package dev.irij.compiler;

import dev.irij.module.ProjectFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * irij.toml's {@code [trust]}: which modules may declare a Java cap or
 * write Java interop by hand. The compiler refuses the rest; {@code irij
 * audit} lists every site.
 */
class TrustCheckerTest {

    private static final String CLOCK = """
            mod acme.clock

            pub effect Clock
              utc-now :: () -> Str

            cap instant :: Clock = "java.time.Instant"
            cap text :: Clock = "java.lang.String"

            pub handler system-clock :: Clock ::: Clock
              utc-now () => resume (text.valueOf (instant.now ()))
            """;

    private static final String UTIL = """
            mod acme.util

            pub fn shout :: Str Str ::: JVM
              (s -> s#toUpperCase ())
            """;

    private static final String MAIN = """
            use acme.clock :as clock
            use acme.util :as util

            fn stamp :: Str ::: clock.Clock
              (-> clock.utc-now ())

            with clock.system-clock
              println (util.shout (stamp ()))
            """;

    private static Path project(Path dir, String toml, String main) throws Exception {
        Files.createDirectories(dir.resolve("acme"));
        Files.writeString(dir.resolve("acme/clock.irj"), CLOCK);
        Files.writeString(dir.resolve("acme/util.irj"), UTIL);
        Files.writeString(dir.resolve("main.irj"), main);
        if (toml != null) Files.writeString(dir.resolve("irij.toml"), toml);
        return dir.resolve("main.irj");
    }

    private static void compile(Path main) throws Exception {
        IrijCompiler.compileFileMulti(main, "irij.TrustProgram", CompileOptions.defaults(), List.of());
    }

    @Test
    void withoutTrustNothingIsEnforced(@TempDir Path dir) throws Exception {
        Path main = project(dir, "[project]\nname = \"p\"\n", MAIN);
        assertDoesNotThrow(() -> compile(main));
    }

    @Test
    void handWrittenJavaInAnUnlistedModuleIsRefused(@TempDir Path dir) throws Exception {
        Path main = project(dir, "[trust]\ncaps = [\"acme.clock\"]\n", MAIN);
        var e = assertThrows(IrijCompiler.CompileException.class, () -> compile(main));
        assertTrue(e.getMessage().contains("acme/util.irj:4:9  acme.util: Java interop #toUpperCase")
                && e.getMessage().contains("add \"acme.util\" to [trust] jvm"), e.getMessage());
    }

    @Test
    void aJavaCapInAnUnlistedModuleIsRefused(@TempDir Path dir) throws Exception {
        Path main = project(dir, "[trust]\njvm = [\"acme.util\"]\n", MAIN);
        var e = assertThrows(IrijCompiler.CompileException.class, () -> compile(main));
        assertTrue(e.getMessage().contains("acme.clock: cap instant = java.time.Instant (Clock)")
                && e.getMessage().contains("add \"acme.clock\" to [trust] caps"), e.getMessage());
    }

    @Test
    void listedModulesAndPrefixesAreTrusted(@TempDir Path dir) throws Exception {
        Path main = project(dir, "[trust]\ncaps = [\"acme.*\"]\njvm = [\"acme.util\"]\n", MAIN);
        assertDoesNotThrow(() -> compile(main));
    }

    @Test
    void theEntryProgramIsMain(@TempDir Path dir) throws Exception {
        String javaInMain = "println (Math/abs (-3))\n";
        Path main = project(dir, "[trust]\ncaps = [\"acme.*\"]\n", javaInMain);
        var e = assertThrows(IrijCompiler.CompileException.class, () -> compile(main));
        assertTrue(e.getMessage().contains("main: Java interop Math/abs"), e.getMessage());
        Files.writeString(dir.resolve("irij.toml"), "[trust]\njvm = [\"main\"]\n");
        assertDoesNotThrow(() -> compile(main));
    }

    @Test
    void stdAndRecordCapsAreNotSites(@TempDir Path dir) throws Exception {
        // std.fs declares a Java cap; a record cap has no Java behind it.
        Path main = project(dir, "[trust]\n", """
                use std.fs :as fs
                effect Greet
                  greet :: Str -> Str
                cap mock :: Greet = {greet= (n -> "Hi, " ++ n)}
                handler mocked :: Greet
                  greet n => resume (mock.greet n)
                with fs.default-fs do (println (fs.fs-exists? "main.irj"))
                println (with mocked do (greet "Ada"))
                """);
        assertDoesNotThrow(() -> compile(main));
    }

    @Test
    void auditListsEverySiteByModule(@TempDir Path dir) throws Exception {
        Path main = project(dir, "[trust]\ncaps = [\"acme.clock\"]\n", MAIN);
        var audit = IrijCompiler.audit(main, List.of());
        assertNotNull(audit.trust());
        assertEquals(List.of("acme.clock CAP", "acme.clock CAP", "acme.util JVM"),
                audit.sites().stream().map(s -> s.module() + " " + s.kind()).toList());
    }

    @Test
    void trustParsesAndRejectsMalformedEntries() {
        var t = ProjectFile.parse("[trust]\ncaps = [\"acme.*\"]\njvm = [\"seed-readme\"]\n").trust();
        assertTrue(t.allowsCaps("acme") && t.allowsCaps("acme.clock.x") && !t.allowsCaps("acmex"));
        assertTrue(t.allowsJvm("seed-readme") && !t.allowsJvm("main") && t.allowsJvm("std.db"));
        assertNull(ProjectFile.parse("[project]\nname = \"p\"\n").trust());
        assertThrows(ProjectFile.ParseError.class, () -> ProjectFile.parse("[trust]\ncaps = \"acme\"\n"));
        assertThrows(ProjectFile.ParseError.class, () -> ProjectFile.parse("[trust]\nunsafe = [\"x\"]\n"));
    }
}
