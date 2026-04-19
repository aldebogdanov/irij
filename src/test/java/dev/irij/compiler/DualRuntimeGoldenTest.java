package dev.irij.compiler;

import dev.irij.ast.AstBuilder;
import dev.irij.interpreter.Interpreter;
import dev.irij.parser.IrijParseDriver;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Dual-runtime golden tests: for each program, capture the interpreter's stdout
 * and the bytecode compiler's stdout and assert they match.
 *
 * The bytecode compiler's `print` is wired to `System.out.println`; the interpreter's
 * `print` does the same. Interp program uses `print` too for parity.
 */
class DualRuntimeGoldenTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(DualRuntimeGoldenTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /** Run source through the interpreter; return captured stdout. */
    private static String runInterp(String source) {
        var parsed = IrijParseDriver.parse(source);
        if (parsed.hasErrors()) {
            throw new IllegalStateException("Parse errors: " + String.join("\n", parsed.errors()));
        }
        var ast = new AstBuilder().build(parsed.tree());
        var baos = new ByteArrayOutputStream();
        var ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
        new Interpreter(ps).run(ast);
        return baos.toString(StandardCharsets.UTF_8);
    }

    /** Compile source and run the generated class; return captured stdout. */
    private static String runCompiled(String source, String className) throws Exception {
        byte[] bytes = IrijCompiler.compileSource(source, className);
        PrintStream orig = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            Class<?> cls = new BytesLoader().define(className, bytes);
            Method main = cls.getMethod("main", String[].class);
            main.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(orig);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static void assertSame(String label, String source) throws Exception {
        String interp = runInterp(source);
        String compiled = runCompiled(source, "irij.Golden_" + label);
        assertEquals(interp, compiled, "Divergence on " + label + ":\n  source:\n" + source);
    }

    // ── Programs ────────────────────────────────────────────────────────

    @Test
    void literalsAndArithmetic() throws Exception {
        assertSame("arith", """
            print 42
            print 3.14
            print true
            print (1 + 2 * 3)
            print (10 - 4)
            print (2.5 + 1)
            print (5 > 3)
            print (5 == 5)
            """);
    }

    @Test
    void bindingsAndIf() throws Exception {
        assertSame("bindings", """
            x := 10
            y := x * 2
            r := if (y > 15) "big" else "small"
            print r
            print y
            """);
    }

    @Test
    void userFunctions() throws Exception {
        assertSame("fns", """
            fn square
              (n -> n * n)
            fn hypot2
              (a b -> (square a) + (square b))
            print (square 7)
            print (hypot2 3 4)
            """);
    }

    @Test
    void recursiveFactorial() throws Exception {
        assertSame("fact", """
            fn fact
              (n -> if (n <= 1) 1 else (n * (fact (n - 1))))
            print (fact 1)
            print (fact 5)
            print (fact 10)
            """);
    }

    @Test
    void matchLiteralsAndVars() throws Exception {
        assertSame("match_lit", """
            println match 0
              0 => "zero"
              1 => "one"
              _ => "many"
            println match 1
              0 => "zero"
              1 => "one"
              _ => "many"
            println match 42
              0 => "zero"
              1 => "one"
              _ => "many"
            """);
    }

    @Test
    void matchWithGuards() throws Exception {
        assertSame("match_guard", """
            label := match 75
              s | s >= 90 => "excellent"
              s | s >= 70 => "good"
              _           => "needs-work"
            println label
            """);
    }

    @Test
    void collectionLiterals() throws Exception {
        assertSame("colls", """
            v := #[1 2 3]
            t := #(1 "a" true)
            m := { k= "v" }
            println v
            println t
            println m
            """);
    }

    @Test
    void stringConcat() throws Exception {
        assertSame("concat", """
            a := "hello"
            b := "world"
            println (a ++ " " ++ b)
            """);
    }

    @Test
    void matchArmsFnForm() throws Exception {
        assertSame("match_arms", """
            fn describe
              0 => "zero"
              1 => "one"
              n => "many"
            println (describe 0)
            println (describe 1)
            println (describe 42)
            """);
    }

    @Test
    void constructorPatterns() throws Exception {
        assertSame("ctor_pats", """
            spec Shape
              Circle Float
              Rect   Float Float
            fn area
              Circle r => r * r * 3.14
              Rect w h => w * h
            println (area (Circle 3.0))
            println (area (Rect 4.0 5.0))
            """);
    }

    @Test
    void destructureBind() throws Exception {
        assertSame("destr_bind", """
            spec Point
              x :: Float
              y :: Float
            p := Point 3.0 4.0
            {x= px y= py} := p
            println px
            println py
            """);
    }

    @Test
    void vectorDestructureBind() throws Exception {
        assertSame("vec_destr", """
            #[a b c] := #[10 20 30]
            println a
            println b
            println c
            """);
    }

    @Test
    void lambdaValues() throws Exception {
        assertSame("lam_values", """
            f := (n -> n * 2)
            println (f 5)
            println (f 10)
            """);
    }

    @Test
    void lambdaWithCaptures() throws Exception {
        assertSame("lam_capture", """
            base := 10
            bump := (n -> n + base)
            println (bump 5)
            println (bump 0)
            """);
    }

    @Test
    void higherOrderFn() throws Exception {
        assertSame("hof", """
            fn apply2
              (f x -> f (f x))
            double := (n -> n * 2)
            println (apply2 double 3)
            """);
    }

    @Test
    void lambdaRestParam() throws Exception {
        assertSame("lam_rest", """
            collect := (a ...rest -> rest)
            println (collect 1 2 3 4)
            """);
    }

    @Test
    void protocolsBasic() throws Exception {
        assertSame("proto", """
            proto Show a
              show :: a -> Str

            impl Show for Int
              show := (n -> "i=" ++ (to-str n))

            impl Show for Str
              show := (s -> "s=" ++ s)

            println (show 42)
            println (show "hi")
            """);
    }

    @Test
    void fibIterativeStyle() throws Exception {
        // Recursive fib; avoids laziness concerns.
        assertSame("fib", """
            fn fib
              (n -> if (n < 2) n else ((fib (n - 1)) + (fib (n - 2))))
            print (fib 0)
            print (fib 1)
            print (fib 10)
            """);
    }
}
