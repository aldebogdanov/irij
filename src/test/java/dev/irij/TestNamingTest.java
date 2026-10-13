package dev.irij;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Test methods are named in camelCase — `wordAtSimpleIdent`, not
 *  `word_at_simple_ident` or `wordAt_simpleIdent` — so `./gradlew test`
 *  reads as one list. */
class TestNamingTest {

    private static final Pattern TEST_METHOD = Pattern.compile(
            "@(?:Test|ParameterizedTest|RepeatedTest|TestFactory)\\b[^;{]*?\\b(?:void|\\S+)\\s+"
                    + "([A-Za-z_][A-Za-z0-9_]*)\\s*\\(", Pattern.DOTALL);

    @Test void testMethodsAreCamelCase() throws Exception {
        List<String> bad = new ArrayList<>();
        try (var files = Files.walk(Path.of("src/test/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                Matcher m = TEST_METHOD.matcher(Files.readString(f));
                while (m.find()) {
                    if (!m.group(1).matches("[a-z][a-zA-Z0-9]*")) {
                        bad.add(f.getFileName() + ": " + m.group(1));
                    }
                }
            }
        }
        assertEquals(List.of(), bad, "rename these test methods to camelCase");
    }
}
