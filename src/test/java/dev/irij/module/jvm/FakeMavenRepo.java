package dev.irij.module.jvm;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/**
 * A Maven repository on disk (served as {@code file:}) holding jars
 * compiled from Java source in the test — so resolution, the lock and
 * class loading run for real, offline.
 */
final class FakeMavenRepo {

    final Path root;
    private final Path work;

    FakeMavenRepo(Path dir) throws IOException {
        this.root = Files.createDirectories(dir.resolve("repo"));
        this.work = Files.createDirectories(dir.resolve("work"));
    }

    String url() {
        return root.toUri().toString();
    }

    /** Compile {@code sources} (class name → Java source) against
     *  {@code classpath} into a jar. */
    Path compile(String jarName, Map<String, String> sources, List<Path> classpath) throws IOException {
        Path src = Files.createDirectories(work.resolve(jarName + "-src"));
        Path out = Files.createDirectories(work.resolve(jarName + "-classes"));
        var files = new ArrayList<String>();
        for (var e : sources.entrySet()) {
            Path f = src.resolve(e.getKey().replace('.', '/') + ".java");
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
            files.add(f.toString());
        }
        var args = new ArrayList<>(List.of("--release", "21", "-d", out.toString()));
        if (!classpath.isEmpty()) {
            args.add("-cp");
            args.add(String.join(java.io.File.pathSeparator, classpath.stream().map(Path::toString).toList()));
        }
        args.addAll(files);
        var err = new ByteArrayOutputStream();
        int rc = ToolProvider.getSystemJavaCompiler().run(null, OutputStream.nullOutputStream(), err,
                args.toArray(String[]::new));
        if (rc != 0) throw new IllegalStateException("javac failed: " + err);
        Path jar = work.resolve(jarName + ".jar");
        try (var jos = new JarOutputStream(Files.newOutputStream(jar));
             Stream<Path> walk = Files.walk(out)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                jos.putNextEntry(new JarEntry(out.relativize(p).toString().replace('\\', '/')));
                Files.copy(p, jos);
                jos.closeEntry();
            }
        }
        return jar;
    }

    /** Publish {@code jar} as {@code group:artifact:version}, depending on
     *  {@code deps} ({@code group:artifact:version} each). */
    void publish(String group, String artifact, String version, Path jar, String... deps) throws IOException {
        Path dir = Files.createDirectories(root.resolve(group.replace('.', '/')).resolve(artifact).resolve(version));
        String base = artifact + "-" + version;
        var pom = new StringBuilder("""
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
            """);
        pom.append("  <groupId>").append(group).append("</groupId>\n");
        pom.append("  <artifactId>").append(artifact).append("</artifactId>\n");
        pom.append("  <version>").append(version).append("</version>\n");
        pom.append("  <dependencies>\n");
        for (String d : deps) {
            String[] c = d.split(":");
            pom.append("    <dependency><groupId>").append(c[0]).append("</groupId><artifactId>")
               .append(c[1]).append("</artifactId><version>").append(c[2]).append("</version></dependency>\n");
        }
        pom.append("  </dependencies>\n</project>\n");
        write(dir.resolve(base + ".pom"), pom.toString().getBytes());
        write(dir.resolve(base + ".jar"), Files.readAllBytes(jar));
    }

    /** A file plus the {@code .sha1} Maven Resolver's "fail" checksum
     *  policy insists on. */
    private static void write(Path file, byte[] bytes) throws IOException {
        Files.write(file, bytes);
        try {
            String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
            Files.writeString(file.resolveSibling(file.getFileName() + ".sha1"), sha1);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** lib-b 1.0 / 2.0 (testlib.b.Name.name() = "b1" / "b2") and lib-a
     *  1.0 (testlib.a.Greeter.greet, depending on lib-b 1.0). */
    static FakeMavenRepo standard(Path dir) throws IOException {
        var repo = new FakeMavenRepo(dir);
        Path b1 = repo.compile("b1", Map.of("testlib.b.Name",
                "package testlib.b; public class Name { public static String name() { return \"b1\"; } }"),
                List.of());
        Path b2 = repo.compile("b2", Map.of("testlib.b.Name",
                "package testlib.b; public class Name { public static String name() { return \"b2\"; } }"),
                List.of());
        Path a = repo.compile("a", Map.of("testlib.a.Greeter", """
                package testlib.a;
                public class Greeter {
                    public static String greet(Object who) {
                        return "hello " + who + " from " + testlib.b.Name.name();
                    }
                }
                """), List.of(b1));
        repo.publish("testlib", "lib-b", "1.0", b1);
        repo.publish("testlib", "lib-b", "2.0", b2);
        repo.publish("testlib", "lib-a", "1.0", a, "testlib:lib-b:1.0");
        return repo;
    }
}
