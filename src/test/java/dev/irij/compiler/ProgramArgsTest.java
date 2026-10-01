package dev.irij.compiler;

import dev.irij.cli.IrijCliAccess;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Program arguments reach the program: every emitted {@code main(String[])}
 * records its argument array first, {@code program-args ()} reads it, and
 * {@code std.env}'s {@code env-args} op hands it out under default-env.
 */
class ProgramArgsTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(ProgramArgsTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }

    private static String run(String source, String... args) throws Exception {
        byte[] bytes = IrijCompiler.compileSource(source, "irij.Program", null, CompileOptions.defaults());
        PrintStream origOut = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf));
        try {
            var cls = new BytesLoader().define("irij.Program", bytes);
            cls.getMethod("main", String[].class).invoke(null, (Object) args);
        } finally {
            System.setOut(origOut);
        }
        return buf.toString().trim().replace("\r", "");
    }

    @Test void program_args_are_the_main_arguments() throws Exception {
        String src = String.join("\n",
                "fn show ::: Env Console",
                "  =>",
                "  xs := program-args ()",
                "  println (length xs)",
                "  println (nth 0 xs)",
                "  println (nth 1 xs)",
                "show ()");
        assertEquals("2\n--port\n9090", run(src, "--port", "9090"));
    }

    @Test void no_arguments_is_an_empty_vec() throws Exception {
        String src = String.join("\n",
                "fn show ::: Env Console",
                "  =>",
                "  println (length (program-args ()))",
                "show ()");
        assertEquals("0", run(src));
    }

    @Test void cli_hands_everything_after_the_file_to_the_program() {
        String[] argv = {"--no-spec-lint", "app.irj", "--ui", "tui", "x"};
        assertArrayEquals(new String[]{"--ui", "tui", "x"}, IrijCliAccess.programArgsAfter(argv, 1));
        assertArrayEquals(new String[]{}, IrijCliAccess.programArgsAfter(new String[]{"app.irj"}, 0));
    }
}
