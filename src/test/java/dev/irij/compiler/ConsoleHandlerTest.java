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
 * `print`, `println` and `read-line` perform std.console's Console ops:
 * a handler with the clause answers and nothing reaches stdout / stdin;
 * with none they write and read for real. These check the real streams,
 * which tests/test-console.irj can't see. They used to ignore every
 * handler: a `handler h :: Console` with a `println` clause compiled and
 * was never called.
 */
class ConsoleHandlerTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(ConsoleHandlerTest.class.getClassLoader()); }
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
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
        try {
            Class<?> cls = new BytesLoader().define("irij.Program", bytes);
            Method main = cls.getMethod("main", String[].class);
            main.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(origOut);
            System.setIn(origIn);
        }
        return buf.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test void capturedOutputNeverReachesStdout() throws Exception {
        assertEquals("after: #[\"a\" \"b\"]\n", run("""
            handler grab :: Console
              got :! #[]
              println m =>
                got <- got ++ #[m]
                resume ()
              print m =>
                got <- got ++ #[m]
                resume ()

            with grab
              println "a"
              print "b"
            println "after: ${grab.got}"
            """, ""));
    }

    @Test void defaultConsoleWritesOnce() throws Exception {
        // default-console's clauses call the builtins they stand in for;
        // those reach stdout, not the clause again.
        assertEquals("one\ntwo|line=x\n", run("""
            use std.console :as console

            with console.default-console
              println "one"
              print "two|"
              l := read-line ()
              println "line=${l}"
            """, "x\n"));
    }

    @Test void clauseForwardsToTheNextHandlerOut() throws Exception {
        assertEquals("> hi\n", run("""
            handler prefix :: Console ::: Console
              println m =>
                println ("> " ++ m)
                resume ()

            with prefix
              println "hi"
            """, ""));
    }

    @Test void mockedReadLineLeavesStdinAlone() throws Exception {
        assertEquals("Alice then real\n", run("""
            handler alice :: Console
              read-line () => resume "Alice"

            fn two ::: Console
              =>
              a := with alice
                read-line ()
              b := read-line ()
              println "${a} then ${b}"

            two ()
            """, "real\n"));
    }

    @Test void aProgramsOwnConsoleEffectKeepsTheBuiltins() throws Exception {
        // Spec §3.1 declares `effect Console` itself. Its print is the
        // builtin's op, so outside a handler it still prints — it used to
        // be an unhandled-effect error.
        assertEquals("plain\n", run("""
            effect Console
              print :: Str -> ()
              read-line :: () -> Str

            print "plain\\n"
            """, ""));
    }
}
