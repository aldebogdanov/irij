package dev.irij.module;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TrustFixTest {

    @Test void appendsAWholeSectionWhenThereIsNone() {
        String out = TrustFix.apply("[project]\nname = \"x\"", Map.of("caps", List.of("main")));
        assertTrue(out.startsWith("[project]\nname = \"x\"\n\n# Who may reach Java"), out);
        var trust = ProjectFile.parse(out).trust();
        assertEquals(List.of("main"), trust.caps());
        assertEquals(List.of(), trust.jvm());
        assertEquals(List.of(), trust.jars());
    }

    @Test void anEmptyFileGetsAnEmptyButPresentSection() {
        var trust = ProjectFile.parse(TrustFix.apply("", Map.of())).trust();
        assertNotNull(trust, "present");
        assertTrue(trust.caps().isEmpty() && trust.jvm().isEmpty() && trust.jars().isEmpty());
    }

    @Test void extendsExistingLinesAndKeepsTheRest() {
        String before = """
            [trust]
            # ours
            caps = ["acme.clock"]   # the clock
            jvm = []

            [seeds]
            vrata = "0.2"
            """;
        String out = TrustFix.apply(before, Map.of("caps", List.of("acme.clock", "main"),
                "jvm", List.of("main"), "jars", List.of("chereda")));
        assertTrue(out.contains("# ours\n"), out);
        assertTrue(out.contains("caps = [\"acme.clock\", \"main\"]   # the clock"), out);
        assertTrue(out.contains("jvm = [\"main\"]"), out);
        var parsed = ProjectFile.parse(out);
        assertEquals(List.of("chereda"), parsed.trust().jars());
        assertEquals(1, parsed.deps().size(), "[seeds] untouched");
    }

    @Test void writesTheJavaKey() {
        var whole = ProjectFile.parse(TrustFix.apply("", Map.of("java", List.of("neo4irij")))).trust();
        assertEquals(List.of("neo4irij"), whole.java());
        String extended = TrustFix.apply("[trust]\njars = [\"neo4irij\"]\n",
                TrustFix.entries(List.of(), List.of(), List.of(), List.of("neo4irij")));
        var trust = ProjectFile.parse(extended).trust();
        assertEquals(List.of("neo4irij"), trust.jars());
        assertTrue(trust.allowsJava("neo4irij"), extended);
    }

    @Test void refusesAListSpanningLines() {
        String before = "[trust]\ncaps = [\n  \"a\",\n]\n";
        var e = assertThrows(IllegalStateException.class,
                () -> TrustFix.apply(before, Map.of("caps", List.of("main"))));
        assertTrue(e.getMessage().contains("by hand"));
    }
}
