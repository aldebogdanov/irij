package dev.irij.module.jvm;

import dev.irij.cli.BytecodeRunner;
import dev.irij.compiler.CompileOptions;
import dev.irij.compiler.IrijCompiler;
import dev.irij.module.ProjectFile;
import dev.irij.module.ProjectFile.JavaDep;
import dev.irij.runtime.DepsClassLoader;
import dev.irij.runtime.JarLauncher;
import dev.irij.runtime.JavaInterop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Java libraries from {@code [jvm]}: planning, the lock, the deps class
 *  loader, and a program (run and built) that calls into a library. */
class JvmDepsTest {

    @TempDir Path dir;
    FakeMavenRepo repo;
    Path cache;
    final PrintStream quiet = new PrintStream(PrintStream.nullOutputStream());

    @BeforeEach
    void setUp() throws Exception {
        repo = FakeMavenRepo.standard(dir.resolve("maven"));
        cache = dir.resolve("cache");
        System.setProperty("irij.jars.dir", cache.toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("irij.jars.dir");
        DepsClassLoader.install(List.of());
    }

    private Path project(String toml) throws Exception {
        Path p = Files.createDirectories(dir.resolve("app"));
        Files.writeString(p.resolve("irij.toml"), toml);
        return p;
    }

    private String jvm(String deps) {
        return "[jvm]\n" + deps + "\nrepos = [\"" + repo.url() + "\"]\n";
    }

    private static Path seed(Path parent, String name, String toml) throws Exception {
        Path s = Files.createDirectories(parent.resolve("seeds").resolve(name));
        Files.writeString(s.resolve("irij.toml"), toml);
        return s;
    }

    // ── Planning ─────────────────────────────────────────────────────────

    @Test
    void rootVersionWinsOverSeeds() throws Exception {
        var root = ProjectFile.parse("[jvm]\n\"testlib:lib-b\" = \"1.0\"\n");
        var seeds = Map.of("s", seed(dir, "s", "[jvm]\n\"testlib:lib-b\" = \"2.0\"\n"));
        var plan = JvmDeps.plan(root, seeds);
        assertEquals(List.of(new JavaDep("testlib", "lib-b", "1.0")), plan.roots());
        assertTrue(plan.notes().get(0).contains("irij.toml pins 1.0"), plan.notes().toString());
    }

    @Test
    void highestSeedVersionWinsWithinAMajor() throws Exception {
        var root = ProjectFile.parse("");
        var seeds = new LinkedHashMap<String, Path>();
        seeds.put("s1", seed(dir, "s1", "[jvm]\n\"testlib:lib-b\" = \"1.2\"\n"));
        seeds.put("s2", seed(dir, "s2", "[jvm]\n\"testlib:lib-b\" = \"1.10\"\n"));
        var plan = JvmDeps.plan(root, seeds);
        assertEquals("1.10", plan.roots().get(0).version());
    }

    @Test
    void seedsNeedingDifferentMajorsAreRefused() throws Exception {
        var root = ProjectFile.parse("");
        var seeds = new LinkedHashMap<String, Path>();
        seeds.put("s1", seed(dir, "s1", "[jvm]\n\"testlib:lib-b\" = \"1.0\"\n"));
        seeds.put("s2", seed(dir, "s2", "[jvm]\n\"testlib:lib-b\" = \"2.0\"\n"));
        var e = assertThrows(JvmDeps.DepsError.class, () -> JvmDeps.plan(root, seeds));
        assertTrue(e.getMessage().contains("different major versions"), e.getMessage());
        // …and pinning it in the root settles it.
        var pinned = ProjectFile.parse("[jvm]\n\"testlib:lib-b\" = \"2.0\"\n");
        assertEquals("2.0", JvmDeps.plan(pinned, seeds).roots().get(0).version());
    }

    @Test
    void seedMayNotChooseRepositories() throws Exception {
        var seeds = Map.of("s", seed(dir, "s", "[jvm]\nrepos = [\"https://evil.example/m2\"]\n"));
        var e = assertThrows(JvmDeps.DepsError.class, () -> JvmDeps.plan(ProjectFile.parse(""), seeds));
        assertTrue(e.getMessage().contains("sets [jvm] repos"), e.getMessage());
    }

    @Test
    void untrustedSeedIsRefusedBeforeAnythingIsDownloaded() throws Exception {
        Path app = project("[trust]\njars = []\n" + jvm(""));
        var root = ProjectFile.parseFile(app.resolve("irij.toml"));
        var seeds = Map.of("greeter", seed(dir, "greeter", "[jvm]\n\"testlib:lib-a\" = \"1.0\"\n"));
        var plan = JvmDeps.plan(root, seeds);
        assertEquals(List.of("greeter"), plan.untrusted());
        var e = assertThrows(JvmDeps.DepsError.class, () -> JvmDeps.fetch(app, plan, quiet));
        assertTrue(e.getMessage().contains("jars = [\"greeter\"]"), e.getMessage());
        assertFalse(Files.exists(cache), "nothing fetched");
        assertFalse(Files.exists(app.resolve("irij.lock")));

        var trusted = ProjectFile.parse("[trust]\njars = [\"greeter\"]\n" + jvm(""));
        assertTrue(JvmDeps.plan(trusted, seeds).untrusted().isEmpty());
    }

    @Test
    void withoutTrustSectionSeedsAreNotChecked() throws Exception {
        var seeds = Map.of("greeter", seed(dir, "greeter", "[jvm]\n\"testlib:lib-a\" = \"1.0\"\n"));
        var plan = JvmDeps.plan(ProjectFile.parse(""), seeds);
        assertTrue(plan.untrusted().isEmpty());
        assertFalse(plan.trustEnforced());
    }

    // ── Fetching and the lock ────────────────────────────────────────────

    @Test
    void fetchResolvesTransitivelyAndWritesTheLock() throws Exception {
        Path app = project(jvm("\"testlib:lib-a\" = \"1.0\""));
        var deps = ProjectDeps.resolve(app, quiet);
        assertEquals(List.of("lib-a-1.0.jar", "lib-b-1.0.jar"),
                deps.jars().stream().map(p -> p.getFileName().toString()).toList());

        var lock = JvmDeps.Lock.read(app.resolve("irij.lock"));
        assertNotNull(lock);
        assertEquals(List.of("testlib:lib-a:jar:1.0", "testlib:lib-b:jar:1.0"),
                lock.jars().stream().map(JvmDeps.Lock.Jar::coord).toList());
        assertEquals(JvmDeps.sha256(deps.jars().get(0)), lock.jars().get(0).sha256());
    }

    @Test
    void lockedJarsAreFetchedAgainAndChecked() throws Exception {
        Path app = project(jvm("\"testlib:lib-a\" = \"1.0\""));
        ProjectDeps.resolve(app, quiet);
        String lockBefore = Files.readString(app.resolve("irij.lock"));

        deleteTree(cache);                                  // a fresh machine
        var again = ProjectDeps.resolve(app, quiet);
        assertEquals(2, again.jars().size());
        assertEquals(lockBefore, Files.readString(app.resolve("irij.lock")));

        // The repository now serves a different lib-b 1.0 (its .sha1
        // matching, so only the lock can tell).
        Path evil = repo.compile("evil", Map.of("testlib.b.Name",
                "package testlib.b; public class Name { public static String name() { return \"evil\"; } }"),
                List.of());
        repo.publish("testlib", "lib-b", "1.0", evil);
        deleteTree(cache);
        var e = assertThrows(JvmDeps.DepsError.class, () -> ProjectDeps.resolve(app, quiet));
        assertTrue(e.getMessage().contains("pins testlib:lib-b:jar:1.0"), e.getMessage());
    }

    @Test
    void changedJvmSectionResolvesAgain() throws Exception {
        Path app = project(jvm("\"testlib:lib-b\" = \"1.0\""));
        ProjectDeps.resolve(app, quiet);
        Files.writeString(app.resolve("irij.toml"), jvm("\"testlib:lib-b\" = \"2.0\""));
        var deps = ProjectDeps.resolve(app, quiet);
        assertEquals("lib-b-2.0.jar", deps.jars().get(0).getFileName().toString());
        assertTrue(Files.readString(app.resolve("irij.lock")).contains("testlib:lib-b:jar:2.0"));
    }

    @Test
    void missingLibraryNamesIt() throws Exception {
        Path app = project(jvm("\"testlib:no-such\" = \"1.0\""));
        var e = assertThrows(JvmDeps.DepsError.class, () -> ProjectDeps.resolve(app, quiet));
        assertTrue(e.getMessage().contains("testlib:no-such"), e.getMessage());
    }

    // ── Class loading ────────────────────────────────────────────────────

    @Test
    void libraryClassesResolveThroughTheDepsLoader() throws Exception {
        Path app = project(jvm("\"testlib:lib-a\" = \"1.0\""));
        ProjectDeps.load(app, quiet);
        Class<?> greeter = JavaInterop.resolveClass("testlib.a.Greeter");
        assertSame(DepsClassLoader.current(), greeter.getClassLoader());
        assertEquals("hello x from b1", greeter.getMethod("greet", Object.class).invoke(null, "x"));

        DepsClassLoader.install(List.of());
        assertThrows(RuntimeException.class, () -> JavaInterop.resolveClass("testlib.a.Greeter"));
    }

    @Test
    void librariesGetTheirOwnCopyOfARuntimeLibraryButNeverOfIrij() throws Exception {
        Path shadow = repo.compile("shadow", Map.of(
                "com.google.gson.Gson",
                "package com.google.gson; public class Gson { public static String who() { return \"library's\"; } }",
                "dev.irij.runtime.JavaInterop",
                "package dev.irij.runtime; public class JavaInterop { }"), List.of());
        DepsClassLoader.install(List.of(shadow));

        Class<?> gson = Class.forName("com.google.gson.Gson", true, DepsClassLoader.current());
        assertSame(DepsClassLoader.current(), gson.getClassLoader(), "child-first");
        assertEquals("library's", gson.getMethod("who").invoke(null));

        Class<?> interop = Class.forName("dev.irij.runtime.JavaInterop", true, DepsClassLoader.current());
        assertSame(JavaInterop.class, interop, "the runtime can't be replaced");
    }

    // ── Programs ─────────────────────────────────────────────────────────

    static final String PROGRAM = """
        effect Greet
          greet :: Str -> Str

        cap greeter :: Greet = "testlib.a.Greeter"

        handler real-greet :: Greet ::: Greet
          greet who => resume (greeter.greet who)

        with real-greet
          println (greet "cap")
        println (testlib.a.Greeter/greet "interop")
        """;

    @Test
    void programCallsALibraryThroughACapAndInterop() throws Exception {
        Path app = project(jvm("\"testlib:lib-a\" = \"1.0\""));
        Path main = app.resolve("main.irj");
        Files.writeString(main, PROGRAM);
        var out = new ByteArrayOutputStream();
        BytecodeRunner.runFile(main, new PrintStream(out, true, StandardCharsets.UTF_8));
        assertEquals("hello cap from b1\nhello interop from b1\n", out.toString(StandardCharsets.UTF_8));
    }

    /** The layout {@code irij build} writes when there are libraries,
     *  started the way {@code java -jar} would: a fresh JVM. */
    @Test
    void builtJarStartsThroughTheLauncher() throws Exception {
        Path app = project(jvm("\"testlib:lib-a\" = \"1.0\""));
        var deps = ProjectDeps.resolve(app, quiet);
        Map<String, byte[]> classes = IrijCompiler.compileSourceMulti(PROGRAM, "irij.Program", null,
                CompileOptions.defaults(), List.of(), "main.irj");

        Path jar = dir.resolve("app.jar");
        try (var jos = new JarOutputStream(Files.newOutputStream(jar))) {
            for (var e : classes.entrySet()) {
                jos.putNextEntry(new JarEntry(JarLauncher.PROGRAM + e.getKey().replace('.', '/') + ".class"));
                jos.write(e.getValue());
                jos.closeEntry();
            }
            var index = new StringBuilder("main irij.Program\n");
            for (Path lib : deps.jars()) {
                String sha = JvmDeps.sha256(lib);
                index.append("jar ").append(sha).append(' ').append(lib.getFileName()).append('\n');
                jos.putNextEntry(new JarEntry(JarLauncher.LIB + sha + ".jar"));
                Files.copy(lib, jos);
                jos.closeEntry();
            }
            jos.putNextEntry(new JarEntry(JarLauncher.INDEX));
            jos.write(index.toString().getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        String javaBin = ProcessHandle.current().info().command().orElse("java");
        var proc = new ProcessBuilder(javaBin,
                "-Dirij.lib.dir=" + dir.resolve("libs"),
                "-cp", System.getProperty("java.class.path") + java.io.File.pathSeparator + jar,
                JarLauncher.class.getName())
                .redirectErrorStream(true).start();
        String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, proc.waitFor(), output);
        assertEquals("hello cap from b1\nhello interop from b1\n", output);
        try (Stream<Path> libs = Files.list(dir.resolve("libs"))) {
            assertEquals(2, libs.filter(p -> p.toString().endsWith(".jar")).count());
        }
    }

    private static void deleteTree(Path p) throws Exception {
        if (!Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            for (Path q : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(q);
        }
    }
}
