package dev.irij.module;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectVersionTest {

    @Test void majorMinorRecognition() {
        assertTrue(ProjectVersion.isMajorMinor("0.2"));
        assertTrue(ProjectVersion.isMajorMinor("10.135"));
        assertFalse(ProjectVersion.isMajorMinor("0.2.1"));
        assertFalse(ProjectVersion.isMajorMinor("0"));
        assertFalse(ProjectVersion.isMajorMinor("v0.2"));
        assertFalse(ProjectVersion.isMajorMinor(""));
        assertFalse(ProjectVersion.isMajorMinor(null));
    }

    @Test void requireBaseAcceptsTwoPart() {
        assertDoesNotThrow(() -> ProjectVersion.requireMajorMinorBase("0.2"));
        assertDoesNotThrow(() -> ProjectVersion.requireMajorMinorBase("  1.0  "));
    }

    @Test void requireBaseRejectsThreePartWithSuggestion() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> ProjectVersion.requireMajorMinorBase("0.2.1"));
        // Actionable: tells the user to use the 2-part base.
        assertTrue(e.getMessage().contains("0.2"), e.getMessage());
        assertTrue(e.getMessage().toLowerCase().contains("commit count"), e.getMessage());
    }

    @Test void requireBaseRejectsBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> ProjectVersion.requireMajorMinorBase(""));
        assertThrows(IllegalArgumentException.class,
                () -> ProjectVersion.requireMajorMinorBase(null));
    }

    @Test void latestPatchPicksHighestCount() {
        var available = List.of("0.2.1", "0.2.10", "0.2.9", "0.1.99");
        assertEquals("0.2.10", ProjectVersion.latestPatch(available, "0.2").orElseThrow());
    }

    @Test void latestPatchDoesNotConfuseAdjacentMinors() {
        // "0.20.x" must not be picked up by base "0.2" (prefix-only would).
        var available = List.of("0.2.3", "0.20.100", "0.2.7");
        assertEquals("0.2.7", ProjectVersion.latestPatch(available, "0.2").orElseThrow());
    }

    @Test void latestPatchEmptyWhenNoneInLine() {
        var available = List.of("0.1.5", "0.3.2");
        assertTrue(ProjectVersion.latestPatch(available, "0.2").isEmpty());
    }

    @Test void releaseBranchNames() {
        assertTrue(ProjectVersion.isReleaseBranch("main"));
        assertTrue(ProjectVersion.isReleaseBranch("master"));
        assertFalse(ProjectVersion.isReleaseBranch("feature/x"));
        assertFalse(ProjectVersion.isReleaseBranch(""));
    }

    @Test void branchSanitization() {
        assertEquals("feature-x", ProjectVersion.sanitizeBranch("feature/x"));
        assertEquals("fix-123", ProjectVersion.sanitizeBranch("fix_123"));
        assertEquals("a-b-c", ProjectVersion.sanitizeBranch("a/b@c"));
        assertEquals("dev", ProjectVersion.sanitizeBranch("///"));
    }
}
