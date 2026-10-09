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
 * A file's own top-level definition shadows a builtin of the same name, for
 * that file only (spec §2.4). Modules inline into one program, so a
 * program's {@code fn length} used to be the {@code length} every library
 * called, and the emitter's builtin special cases beat the program's own
 * {@code e := 3} in its fns. A module's self-referential binding
 * ({@code pub quo := quo}, a recursive {@code fact := (n -> … fact …)})
 * stayed program-wide, where the program could replace it.
 */
class BuiltinShadowingTest {

    @TempDir Path root;

    @Test void programFnNamedLikeABuiltinShadowsIt() throws Exception {
        assertEquals("root of x\n4.0", run("""
                use std.math :as math

                fn sqrt :: Str Str
                  (s -> "root of " ++ s)

                println (sqrt "x")
                println (math.sqrt 16)
                """));
    }

    @Test void aLibrarysBuiltinCallsIgnoreTheProgramsNames() throws Exception {
        module("lib.q", """
                mod lib.q

                pub fn len2 :: Vec Int
                  (v -> length v)

                pub fn q2 :: Int Int
                  (n -> quo n 2)
                """);
        assertEquals("2\n5\n42", run("""
                use lib.q :as q

                fn length :: _ Int
                  (_ -> 42)

                fn quo :: _ _ Str
                  (a b -> "mine")

                println (q.len2 #[1 2])
                println (q.q2 10)
                println (length #[1 2])
                """));
    }

    @Test void fnsReadTheProgramsBuiltinNamedBindings() throws Exception {
        assertEquals("#[3 5 4]\n#[3 5 4]", run("""
                e := 3
                length := 5
                pi := 4

                fn f :: () _
                  (_ -> #[e length pi])

                println #[e length pi]
                println (f ())
                """));
    }

    @Test void builtinNamedFnIsTheProgramsAsAValueToo() throws Exception {
        assertEquals("#[42 42]", run("""
                fn length :: _ Int
                  (_ -> 42)

                println (@ length #[#[1] #[2 3]])
                """));
    }

    @Test void reexportedBuiltinIsAValue() throws Exception {
        assertEquals("3\n#[1 2]", run("""
                use std.math :as math

                f := math.quo
                println (f 10 3)
                println (@ math.abs #[(-1) 2])
                """));
    }

    @Test void aModulesRecursiveBindingIsItsOwn() throws Exception {
        module("lib.r", """
                mod lib.r

                fact := (n -> if (n < 2) 1 else (n * (fact (n - 1))))

                pub fn f5 :: () Int
                  (_ -> fact 5)
                """);
        assertEquals("120\n7", run("""
                use lib.r :as r

                fact := 7

                println (r.f5 ())
                println fact
                """));
    }

    /** Names a module binds by destructuring are its own too. */
    @Test void aModulesDestructuredNamesAreItsOwn() throws Exception {
        module("lib.d", """
                mod lib.d

                #[a b] := #[1 2]

                pub fn sum2 :: () Int
                  (_ -> a + b)
                """);
        assertEquals("3\n100", run("""
                use lib.d :as d

                a := 100

                println (d.sum2 ())
                println a
                """));
    }

    /** In a binding's initializer its own name means the binding only
     *  inside a lambda; elsewhere it means what it meant before. */
    @Test void aBuiltinNamedBindingsInitializerSeesTheBuiltinOrItself() throws Exception {
        assertEquals("4.0\n3", run("""
                sqrt := sqrt
                length := (v -> if (empty? v) 0 else (1 + (length (tail v))))

                println (sqrt 16)
                println (length #[1 2 3])
                """));
    }

    private void module(String qname, String source) throws Exception {
        Path p = root.resolve(qname.replace('.', '/') + ".irj");
        Files.createDirectories(p.getParent());
        Files.writeString(p, source);
    }

    private String run(String source) throws Exception {
        Map<String, byte[]> classes = IrijCompiler.compileSourceMulti(source, "irij.Program",
                root, CompileOptions.defaults().withSpecLint(false), List.of(), "main.irj");
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] b = classes.get(name);
                if (b == null) throw new ClassNotFoundException(name);
                return defineClass(name, b, 0, b.length);
            }
        };
        PrintStream orig = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true));
        try {
            Method m = loader.loadClass("irij.Program").getMethod("main", String[].class);
            m.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(orig);
        }
        return buf.toString().trim();
    }
}
