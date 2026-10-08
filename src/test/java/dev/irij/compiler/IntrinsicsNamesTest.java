package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link IntrinsicsEmitter#NAMES} is what {@code emitBuiltinApp} handles:
 *  a builtin missing from it would need an import it can't have. */
class IntrinsicsNamesTest {

    @Test void namesAreTheSwitchLabels() throws Exception {
        String src = Files.readString(Path.of("src/main/java/dev/irij/compiler/IntrinsicsEmitter.java"));
        int start = src.indexOf("boolean emitBuiltinApp(");
        int depth = 0, i = src.indexOf('{', start), end = i;
        for (; end < src.length(); end++) {
            char c = src.charAt(end);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) break;
        }
        Set<String> labels = new TreeSet<>();
        Matcher m = Pattern.compile("(?m)^\\s*case ((?:\"[^\"]+\"(?:,\\s*)?)+)\\s*->").matcher(src.substring(i, end));
        while (m.find()) {
            Matcher n = Pattern.compile("\"([^\"]+)\"").matcher(m.group(1));
            while (n.find()) labels.add(n.group(1));
        }
        assertEquals(labels, new TreeSet<>(IntrinsicsEmitter.NAMES));
    }
}
