package dev.irij.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What `irij publish` puts in a seed's tarball. */
class PublishBundleTest {

    private static void write(Path p, String text) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    @Test void bundlesIrjReadmeManifestAndJavaSources(@TempDir Path s) throws Exception {
        write(s.resolve("irij.toml"), "[project]\nname = \"seedy\"\n");
        write(s.resolve("core.irj"), "mod seedy.core\n");
        write(s.resolve("README.md"), "# seedy\n");
        write(s.resolve("java/seedy/deep/er/than/five/Util.java"), "package seedy.deep.er.than.five;\n");
        write(s.resolve("java/seedy/notes.txt"), "not bundled");
        write(s.resolve("java/seedy/Stray.class"), "not bundled");
        write(s.resolve("build/Out.irj"), "not bundled");
        var files = PublishCommand.bundleFiles(s).stream()
                .map(p -> s.relativize(p).toString().replace('\\', '/')).sorted().toList();
        assertEquals(List.of("README.md", "core.irj", "irij.toml",
                "java/seedy/deep/er/than/five/Util.java"), files);
    }
}
