package dev.irij.cli;

import dev.irij.compiler.TrustChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** `irij build` / `irij publish` need [trust]; `irij run` warns. */
class TrustGateTest {

    @Test void buildNeedsATrustSection(@TempDir Path dir) throws Exception {
        String noFile = BuildCommand.trustProblem(dir);
        assertTrue(noFile.contains("irij build needs a [trust] section"), noFile);
        assertTrue(noFile.contains("irij audit --fix"), noFile);

        Files.writeString(dir.resolve("irij.toml"), "[project]\nname = \"x\"\n");
        assertNotNull(BuildCommand.trustProblem(dir));

        Files.writeString(dir.resolve("irij.toml"), "[project]\nname = \"x\"\n\n[trust]\n");
        assertNull(BuildCommand.trustProblem(dir), "an empty [trust] is a policy");
    }

    @Test void runWarnsOnlyAboutSeedsThatReachJava() {
        var sites = List.of(
                new TrustChecker.Site(TrustChecker.Kind.JVM, "main", "main.irj", "System/exit", null),
                new TrustChecker.Site(TrustChecker.Kind.CAP, "acme.clock", "acme/clock.irj", "c", null),
                new TrustChecker.Site(TrustChecker.Kind.JVM, "acme.clock", "acme/clock.irj", "#x", null));
        var warnings = new ArrayList<String>();
        TrustChecker.warnUntrusted(sites, Set.of("acme.clock"), warnings::add);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("acme.clock (2 sites)"), warnings.get(0));
        assertFalse(warnings.get(0).contains("main ("), "the project's own code isn't a seed");

        warnings.clear();
        TrustChecker.warnUntrusted(sites, Set.of(), warnings::add);
        assertTrue(warnings.isEmpty());
    }
}
