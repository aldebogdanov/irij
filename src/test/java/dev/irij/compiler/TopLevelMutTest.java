package dev.irij.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A top-level binding is one value, whoever reads or writes it. It
 * lives in a static field; main also keeps a local copy for the
 * binding's initializer. Top-level code used to read that copy, so a
 * write made by a fn or lambda never reached it: {@code println hits}
 * printed the initial value after {@code bump ()} had changed it.
 */
class TopLevelMutTest {

    @TempDir Path root;

    private static final String BUMP = """
            hits :! 0

            fn bump :: () Int
              => _
              hits <- hits + 1
              hits

            fn show :: () Int
              (_ -> hits)

            """;

    @Test void fnWritesReachTopLevelReads() throws Exception {
        assertEquals("2\n2", run(BUMP + """
                bump ()
                bump ()
                println hits
                println (show ())
                """));
    }

    @Test void topLevelWritesReachFnsAndBack() throws Exception {
        assertEquals("5\n5\n6", run(BUMP + """
                hits <- 5
                println hits
                println (show ())
                bump ()
                println hits
                """));
    }

    /** Branches and match arms of top-level code, and a lambda built
     *  inside one, read the binding too — not a copy taken earlier. */
    @Test void nestedTopLevelCodeReadsTheBinding() throws Exception {
        assertEquals("1\n2\n2", run(BUMP + """
                fn call-it :: Fn _
                  => f
                  f ()

                if true
                  bump ()
                  println hits
                match 1
                  _ =>
                    bump ()
                    println hits
                    println (call-it (_ -> hits))
                """));
    }

    @Test void localsNamedLikeTheBindingStillShadowIt() throws Exception {
        assertEquals("7\n9\n3\n1", run(BUMP + """
                fn echo :: Int Int
                  (hits -> hits)

                bump ()
                match 7
                  hits => println hits
                println ((hits -> hits) 9)
                println (echo 3)
                println hits
                """));
    }

    /** Assigning a local that shadows the binding leaves the binding alone. */
    @Test void assigningAShadowingLocalLeavesTheBindingAlone() throws Exception {
        assertEquals("11\n0\n0", run(BUMP + """
                fn local-shadow :: Int Int
                  => n
                  hits :! n
                  hits <- hits + 1
                  hits

                println (local-shadow 10)
                println (show ())
                println hits
                """));
    }

    /** A destructuring top-level bind binds top-level names too. */
    @Test void fnsReadTopLevelDestructuredNames() throws Exception {
        assertEquals("3\n3", run("""
                #[a b] := #[1 2]

                fn s :: () Int
                  (_ -> a + b)

                println (a + b)
                println (s ())
                """));
    }

    @Test void modulePubBindingWrittenByItsFnsReadsCurrentFromImporter() throws Exception {
        Path p = root.resolve("lib/counter.irj");
        Files.createDirectories(p.getParent());
        Files.writeString(p, """
                mod lib.counter

                pub hits :! 0

                pub fn bump :: () Int
                  => _
                  hits <- hits + 1
                  hits
                """);
        assertEquals("2", runMulti("""
                use lib.counter :as c

                c.bump ()
                c.bump ()
                println c.hits
                """));
    }

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(TopLevelMutTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static String run(String source) throws Exception {
        byte[] bytes = IrijCompiler.compileSource(source, "irij.Program");
        return captureMain(new BytesLoader().define("irij.Program", bytes));
    }

    private String runMulti(String source) throws Exception {
        Map<String, byte[]> classes = IrijCompiler.compileSourceMulti(source, "irij.Program",
                root, CompileOptions.defaults().withSpecLint(false), List.of(), "main.irj");
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] b = classes.get(name);
                if (b == null) throw new ClassNotFoundException(name);
                return defineClass(name, b, 0, b.length);
            }
        };
        return captureMain(loader.loadClass("irij.Program"));
    }

    private static String captureMain(Class<?> cls) throws Exception {
        PrintStream origOut = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true));
        try {
            Method main = cls.getMethod("main", String[].class);
            main.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(origOut);
        }
        return buf.toString().trim();
    }
}
