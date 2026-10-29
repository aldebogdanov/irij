package dev.irij.module.jvm;

import dev.irij.cli.BytecodeRunner;
import dev.irij.module.ProjectFile;
import dev.irij.runtime.DepsClassLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Java sources in a project's or seed's {@code java/}: compiled into a
 *  cached jar that joins the program's Java libraries, so a {@code cap}
 *  binds one of its classes — and the handler performs the cap's effect,
 *  not {@code JVM}. */
class JavaSourcesTest {

    @TempDir Path dir;
    Path cache;

    @BeforeEach
    void setUp() {
        cache = dir.resolve("java-cache");
        System.setProperty("irij.java.dir", cache.toString());
        System.setProperty("irij.jars.dir", dir.resolve("jars").toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("irij.java.dir");
        System.clearProperty("irij.jars.dir");
        DepsClassLoader.install(List.of());
    }

    private Path write(Path p, String text) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
        return p;
    }

    private static final String CLOCK = """
        package acme;

        import java.util.LinkedHashMap;
        import java.util.List;
        import java.util.Map;

        /** A cap provider: static methods over plain Java values. */
        public final class Clock {
            private Clock() {}
            public static String hello(String who) { return "hello " + who; }
            public static Map<String, Object> info() {
                var m = new LinkedHashMap<String, Object>();
                m.put("n", 42L);
                m.put("xs", List.of(1L, 2L));
                return m;
            }
            public static long size(List<Object> xs) { return xs.size(); }
        }
        """;

    /** Uses the provider through a cap; `run` declares Greet, not JVM. */
    private static final String PROGRAM = """
        effect Greet
          hello :: Str Str
          info :: Map
          size :: Vec Int

        cap clock :: Greet = "acme.Clock"

        handler real :: Greet ::: Greet
          hello who => resume (clock.hello who)
          info () => resume (clock.info ())
          size xs => resume (clock.size xs)

        fn run :: () () ::: Greet Console
          =>
          with real
            println (hello "java")
            println (info ())
            println (size #[1 2 3])

        run ()
        """;

    private String runMain(Path app) throws Exception {
        Path main = write(app.resolve("main.irj"), PROGRAM);
        var out = new ByteArrayOutputStream();
        BytecodeRunner.runFile(main, new PrintStream(out, true, StandardCharsets.UTF_8));
        return out.toString(StandardCharsets.UTF_8);
    }

    private Path app(String toml) throws Exception {
        Path app = dir.resolve("app");
        write(app.resolve("irij.toml"), toml);
        return app;
    }

    private long cachedJars() throws Exception {
        if (!Files.isDirectory(cache)) return 0;
        try (Stream<Path> s = Files.list(cache)) {
            return s.filter(p -> p.toString().endsWith(".jar")).count();
        }
    }

    @Test
    void theProjectsOwnJavaIsACapProvider() throws Exception {
        Path app = app("[project]\nname = \"app\"\n");
        write(app.resolve("java/acme/Clock.java"), CLOCK);
        assertEquals("hello java\n{n= 42 xs= #[1 2]}\n3\n", runMain(app));
    }

    @Test
    void underTrustTheProjectsOwnJavaNeedsNoEntry() throws Exception {
        Path app = app("[project]\nname = \"app\"\n\n[trust]\ncaps = [\"main\"]\n");
        write(app.resolve("java/acme/Clock.java"), CLOCK);
        assertEquals("hello java\n{n= 42 xs= #[1 2]}\n3\n", runMain(app));
    }

    @Test
    void theJarIsCachedUntilASourceChanges() throws Exception {
        Path app = app("[project]\nname = \"app\"\n");
        Path src = write(app.resolve("java/acme/Clock.java"), CLOCK);
        var first = ProjectDeps.resolve(app, quiet()).jars();
        var log = new ByteArrayOutputStream();
        var second = ProjectDeps.resolve(app, new PrintStream(log, true, StandardCharsets.UTF_8)).jars();
        assertEquals(first, second);
        assertFalse(log.toString(StandardCharsets.UTF_8).contains("Compiling"), "a cache hit compiles nothing");
        assertEquals(1, cachedJars());

        write(src, CLOCK.replace("\"hello \"", "\"hi \""));
        var third = ProjectDeps.resolve(app, quiet()).jars();
        assertNotEquals(first, third);
        assertEquals(2, cachedJars());
    }

    @Test
    void aCompileErrorNamesTheFileAndLine() throws Exception {
        Path app = app("[project]\nname = \"app\"\n");
        write(app.resolve("java/acme/Broken.java"), "package acme;\n\npublic class Broken {\n  int x = \"no\";\n}\n");
        var e = assertThrows(JavaSources.CompileError.class, () -> ProjectDeps.resolve(app, quiet()));
        assertTrue(e.getMessage().contains("java/acme/Broken.java:4:"), e.getMessage());
        assertEquals(0, cachedJars(), "nothing cached for a failed compile");
    }

    @Test
    void sourcesDontSeeIrijsOwnClasses() throws Exception {
        Path app = app("[project]\nname = \"app\"\n");
        write(app.resolve("java/acme/Inside.java"),
                "package acme;\npublic class Inside { Object m = dev.irij.runtime.Values.UNIT; }\n");
        var e = assertThrows(JavaSources.CompileError.class, () -> ProjectDeps.resolve(app, quiet()));
        assertTrue(e.getMessage().contains("Inside.java:2:"), e.getMessage());
    }

    // ── Seeds ───────────────────────────────────────────────────────────

    private Path seed(String name, String cls, String body) throws Exception {
        Path s = dir.resolve(name);
        write(s.resolve("irij.toml"), "[project]\nname = \"" + name + "\"\n");
        write(s.resolve("java/" + cls.replace('.', '/') + ".java"), body);
        return s;
    }

    private static final String SEEDY = """
        package seedy;
        public final class Util {
            private Util() {}
            public static String shout(String s) { return s.toUpperCase(); }
        }
        """;

    @Test
    void aSeedsJavaNeedsTrustWhenThereIsTrust() throws Exception {
        seed("seedy", "seedy.Util", SEEDY);
        Path app = app("[seeds]\nseedy = { path = \"../seedy\" }\n\n[trust]\n");
        var e = assertThrows(JvmDeps.DepsError.class, () -> ProjectDeps.resolve(app, quiet()));
        assertTrue(e.getMessage().contains("java = [\"seedy\"]"), e.getMessage());
        assertTrue(e.getMessage().contains("Nothing was compiled"), e.getMessage());
        assertEquals(0, cachedJars());

        write(app.resolve("irij.toml"), "[seeds]\nseedy = { path = \"../seedy\" }\n\n[trust]\njava = [\"seedy\"]\n");
        var jars = ProjectDeps.resolve(app, quiet()).jars();
        assertEquals(1, jars.size());
        try (var jar = new JarFile(jars.get(0).toFile())) {
            assertNotNull(jar.getEntry("seedy/Util.class"));
        }
    }

    @Test
    void withoutTrustASeedsJavaCompilesAndRunWarns() throws Exception {
        seed("seedy", "seedy.Util", SEEDY);
        Path app = app("[seeds]\nseedy = { path = \"../seedy\" }\n");
        var deps = ProjectDeps.resolve(app, quiet());
        assertEquals(1, deps.jars().size());
        String warning = BytecodeRunner.untrustedJavaWarning(app, deps);
        assertNotNull(warning);
        assertTrue(warning.contains("seeds ship Java sources: seedy"), warning);
        assertTrue(warning.contains("`irij build` refuses"), warning);

        write(app.resolve("irij.toml"), "[seeds]\nseedy = { path = \"../seedy\" }\n\n[trust]\njava = [\"seedy\"]\n");
        assertNull(BytecodeRunner.untrustedJavaWarning(app, ProjectDeps.resolve(app, quiet())));
    }

    @Test
    void aSeedsClassesLiveInItsPackage() throws Exception {
        seed("seedy", "other.Util", SEEDY.replace("package seedy;", "package other;"));
        Path app = app("[seeds]\nseedy = { path = \"../seedy\" }\n");
        var e = assertThrows(JavaSources.CompileError.class, () -> ProjectDeps.resolve(app, quiet()));
        assertTrue(e.getMessage().contains("other/Util.class"), e.getMessage());
        assertTrue(e.getMessage().contains("package seedy"), e.getMessage());
    }

    @Test
    void seedNamesMapToPackages() {
        assertEquals("neo4irij", JavaSources.packageOf("neo4irij"));
        assertEquals("chereda_redis", JavaSources.packageOf("chereda-redis"));
        assertEquals("_9lives", JavaSources.packageOf("9lives"));
        assertEquals("acme.db_x", JavaSources.packageOf("acme.db-x"));
    }

    @Test
    void theProjectsJavaSeesItsSeedsJava() throws Exception {
        seed("seedy", "seedy.Util", SEEDY);
        Path app = app("[seeds]\nseedy = { path = \"../seedy\" }\n");
        write(app.resolve("java/acme/Clock.java"),
                CLOCK.replace("return \"hello \" + who;", "return seedy.Util.shout(\"hello \" + who);"));
        assertEquals("HELLO JAVA\n{n= 42 xs= #[1 2]}\n3\n", runMain(app));
    }

    @Test
    void trustParsesTheJavaKey() {
        var trust = ProjectFile.parse("[trust]\njava = [\"neo4irij\", \"acme.*\"]\n").trust();
        assertTrue(trust.allowsJava("neo4irij"));
        assertTrue(trust.allowsJava("acme.db"));
        assertFalse(trust.allowsJava("other"));
        var e = assertThrows(ProjectFile.ParseError.class,
                () -> ProjectFile.parse("[trust]\njavas = []\n"));
        assertTrue(e.getMessage().contains("`java`"), e.getMessage());
    }

    private static PrintStream quiet() {
        return new PrintStream(PrintStream.nullOutputStream());
    }
}
