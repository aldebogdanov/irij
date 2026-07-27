package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * String escapes: {@code \n \r \t \e \" \\ \$}.
 *
 * <p>{@code \e} (ESC, 0x1B) was added for terminal work — {@code
 * std.term} and anything drawing ANSI needs it in nearly every string,
 * and {@code from-char-code 27 ++ …} at each site is unreadable.
 *
 * <p>The backslash cases matter more than they look: unescaping used
 * to be a chain of {@code String.replace} calls, which mis-handled a
 * literal backslash followed by an escape letter — {@code "\\n"}
 * produced backslash + newline instead of backslash + "n", because the
 * {@code \n} rule consumed the second backslash before the {@code \\}
 * rule ran.
 */
class StringEscapeTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(StringEscapeTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static String run(String source) throws Exception {
        byte[] bytes = IrijCompiler.compileSource(source, "irij.Program",
                null, CompileOptions.defaults());
        PrintStream origOut = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf));
        try {
            Class<?> cls = new BytesLoader().define("irij.Program", bytes);
            Method main = cls.getMethod("main", String[].class);
            main.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(origOut);
        }
        return buf.toString().trim();
    }

    @Test void escEscapeIsTheEscByte() throws Exception {
        // Irij source: println (length "\e")  →  one char
        assertEquals("1", run("println (length \"\\e\")"));
        assertEquals("27", run("println (char-code \"\\e\")"));
    }

    @Test void escInAnAnsiSequence() throws Exception {
        // "\e[2J" — clear screen. Four chars, ESC first.
        assertEquals("4", run("println (length \"\\e[2J\")"));
        assertEquals("27", run("println (char-code (nth 0 \"\\e[2J\"))"));
        assertEquals("[", run("println (nth 1 \"\\e[2J\")"));
    }

    @Test void classicEscapesStillWork() throws Exception {
        assertEquals("3", run("println (length \"a\\nb\")"));
        assertEquals("3", run("println (length \"a\\tb\")"));
        assertEquals("3", run("println (length \"a\\\"b\")"));
    }

    @Test void literalBackslashFollowedByEscapeLetter() throws Exception {
        // Irij source: "\\n" — an escaped backslash, then the letter n.
        // Two chars, and the second is "n", not a newline.
        assertEquals("2", run("println (length \"\\\\n\")"));
        assertEquals("n", run("println (nth 1 \"\\\\n\")"));
    }

    @Test void literalBackslashFollowedByE() throws Exception {
        assertEquals("2", run("println (length \"\\\\e\")"));
        assertEquals("e", run("println (nth 1 \"\\\\e\")"));
    }

    @Test void dollarEscapeSuppressesInterpolation() throws Exception {
        assertEquals("${x}", run("println \"\\${x}\""));
    }
}
