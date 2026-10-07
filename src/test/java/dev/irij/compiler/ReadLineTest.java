package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * `read-line` over piped stdin: every line arrives, in order, and the end
 * of input is (). It used to wrap System.in in a fresh reader per call, which
 * read ahead and dropped every line after the first, and it returned Java
 * null at the end, which printed as () but was not equal to it.
 */
class ReadLineTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(ReadLineTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static String run(String source, String stdin) throws Exception {
        byte[] bytes = IrijCompiler.compileSource(source, "irij.Program",
                null, CompileOptions.defaults());
        PrintStream origOut = System.out;
        InputStream origIn = System.in;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf));
        System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
        try {
            Class<?> cls = new BytesLoader().define("irij.Program", bytes);
            Method main = cls.getMethod("main", String[].class);
            main.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(origOut);
            System.setIn(origIn);
        }
        return buf.toString().trim();
    }

    @Test void every_piped_line_arrives() throws Exception {
        assertEquals("a|b|c", run("""
            x := read-line ()
            y := read-line ()
            z := read-line ()
            println (x ++ "|" ++ y ++ "|" ++ z)
            """, "a\nb\nc\n"));
    }

    @Test void end_of_input_is_unit() throws Exception {
        assertEquals("true", run("""
            x := read-line ()
            y := read-line ()
            println (y == ())
            """, "only\n"));
    }
}
