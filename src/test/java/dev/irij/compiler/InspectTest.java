package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A Str prints raw on its own and quoted inside a collection, variant or
 * record; {@code inspect} quotes it on its own too. The quoted form is
 * Irij source: reading it back gives an equal value.
 */
class InspectTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(InspectTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static String run(String source) throws Exception {
        byte[] bytes = IrijCompiler.compileSource(source, "irij.Program");
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
        return buf.toString().replace("\r\n", "\n").trim();
    }

    @Test void aStrOnItsOwnPrintsRaw() throws Exception {
        assertEquals("a b", run("println \"a b\""));
        assertEquals("a b", run("println (to-str \"a b\")"));
        assertEquals("x=a b", run("s := \"a b\"\nprintln \"x=${s}\""));
    }

    @Test void strsInsideCollectionsPrintQuoted() throws Exception {
        assertEquals("#[\"a b\" \"c\"]", run("println #[\"a b\" \"c\"]"));
        assertEquals("#[\"\" \"x\"]", run("println #[\"\" \"x\"]"));
        assertEquals("#(\"z\" 1 :k)", run("println #(\"z\" 1 :k)"));
        assertEquals("#{\"s\"}", run("println #{\"s\"}"));
        assertEquals("{name= \"Ada\" age= 36}", run("println {name= \"Ada\" age= 36}"));
    }

    @Test void escapesAreTheOnesTheLexerReads() throws Exception {
        assertEquals("#[\"q\\\"uote\" \"line\\nbreak\" \"tab\\there\" \"back\\\\slash\" \"\\${x}\" \"$5\"]",
                run("println #[\"q\\\"uote\" \"line\\nbreak\" \"tab\\there\" \"back\\\\slash\" \"\\${x}\" \"$5\"]"));
    }

    @Test void aKeyThatIsNoFieldNameIsQuoted() throws Exception {
        assertEquals("{\"Content-Type\"= \"text/html\" data-on:click= \"go\" ok?= true}",
                run("println {\"Content-Type\"= \"text/html\" data-on:click= \"go\" ok?= true}"));
    }

    @Test void nestedVariantsWithFieldsGoInParentheses() throws Exception {
        assertEquals("#[(Ok \"x\") (Err #[\"e\"])]", run("println #[(Ok \"x\") (Err #[\"e\"])]"));
        assertEquals("Ok (Ok 1)", run("println (Ok (Ok 1))"));
        assertEquals("Ok \"x\"", run("println (Ok \"x\")"));
    }

    @Test void recordsQuoteTheirFields() throws Exception {
        assertEquals("User {name= \"Ada\" age= 36}",
                run("spec User\n  name :: Str\n  age :: Int\nprintln (User \"Ada\" 36)"));
    }

    @Test void inspectQuotesAStrOnItsOwn() throws Exception {
        assertEquals("\"x\"\n42\n#[\"a\"]\n:k", run("""
                println (inspect "x")
                println (inspect 42)
                println (inspect #["a"])
                println (inspect :k)"""));
        assertEquals("#[\"1\" \"2\"]", run("println (#[1 2] |> @ inspect)"));
    }

    @Test void theQuotedFormReadsBackAsAnEqualValue() throws Exception {
        String src = "#[\"a b\" \"\" \"q\\\"uote\" \"line\\nbreak\" \"\\${x}\" {k= \"v\" \"Odd Key\"= #(\"t\" 1)} (Ok \"x\")]";
        String printed = run("println " + src);
        assertEquals("true", run("v := " + src + "\nprintln (v == " + printed + ")"));
    }
}
