package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HelloWorldCompileTest {

    /** Minimal classloader that defines one class from bytes. */
    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(HelloWorldCompileTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static String runProgram(String source) throws Exception {
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
        return buf.toString();
    }

    private static String nl(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) { sb.append(l).append(System.lineSeparator()); }
        return sb.toString();
    }

    @Test
    void printStringLiteral() throws Exception {
        assertEquals(nl("hi"), runProgram("println \"hi\""));
    }

    @Test
    void printIntLiteral() throws Exception {
        assertEquals(nl("42"), runProgram("println 42"));
    }

    @Test
    void printFloatLiteral() throws Exception {
        assertEquals(nl("3.14"), runProgram("println 3.14"));
    }

    @Test
    void printBoolLiteral() throws Exception {
        assertEquals(nl("true"), runProgram("println true"));
    }

    @Test
    void printArithmetic() throws Exception {
        assertEquals(nl("5"), runProgram("println (1 + 2 + 2)"));
        assertEquals(nl("6"), runProgram("println (2 * 3)"));
        assertEquals(nl("4"), runProgram("println (10 - 6)"));
        assertEquals(nl("2"), runProgram("println (10 / 5)"));
    }

    @Test
    void printComparison() throws Exception {
        assertEquals(nl("true"), runProgram("println (1 < 2)"));
        assertEquals(nl("false"), runProgram("println (3 == 4)"));
        assertEquals(nl("true"), runProgram("println (5 >= 5)"));
    }

    @Test
    void printMixedNumeric() throws Exception {
        assertEquals(nl("3.5"), runProgram("println (1 + 2.5)"));
    }

    @Test
    void localBinding() throws Exception {
        assertEquals(nl("12"), runProgram("x := 5\ny := 7\nprintln (x + y)"));
    }

    @Test
    void bindingShadow() throws Exception {
        assertEquals(nl("10"), runProgram("x := 3\ny := x * 2\nx := 100\nprintln (y + 4)"));
    }

    @Test
    void ifExprTrue() throws Exception {
        assertEquals(nl("yes"),
            runProgram("r := if (1 < 2) \"yes\" else \"no\"\nprintln r"));
    }

    @Test
    void ifExprFalse() throws Exception {
        assertEquals(nl("no"),
            runProgram("r := if (1 > 2) \"yes\" else \"no\"\nprintln r"));
    }

    @Test
    void ifExprNumeric() throws Exception {
        assertEquals(nl("42"),
            runProgram("x := 10\nr := if (x == 10) 42 else 0\nprintln r"));
    }

    @Test
    void singleArgFn() throws Exception {
        String src = """
            fn double
              (n -> n * 2)
            println (double 21)
            """;
        assertEquals(nl("42"), runProgram(src));
    }

    @Test
    void twoArgFn() throws Exception {
        String src = """
            fn add3
              (x y -> x + y + 3)
            println (add3 10 20)
            """;
        assertEquals(nl("33"), runProgram(src));
    }

    @Test
    void fnCallsFn() throws Exception {
        String src = """
            fn square
              (x -> x * x)
            fn sum-of-squares
              (a b -> (square a) + (square b))
            println (sum-of-squares 3 4)
            """;
        assertEquals(nl("25"), runProgram(src));
    }

    @Test
    void recursiveFn() throws Exception {
        String src = """
            fn fact
              (n -> if (n <= 1) 1 else (n * (fact (n - 1))))
            println (fact 6)
            """;
        assertEquals(nl("720"), runProgram(src));
    }

    @Test
    void pubFnWorks() throws Exception {
        String src = """
            pub fn inc
              (x -> x + 1)
            println (inc 41)
            """;
        assertEquals(nl("42"), runProgram(src));
    }
}
