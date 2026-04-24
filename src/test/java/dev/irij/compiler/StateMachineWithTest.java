package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 14c.3 step 2: end-to-end compilation of `with` under STATE_MACHINE
 * strategy. Covers pure bodies (no op call) and single-op bodies (exactly
 * one top-level op call, with or without `x := op args` binding).
 */
class StateMachineWithTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(StateMachineWithTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static String runSM(String source) throws Exception {
        byte[] bytes = IrijCompiler.compileSource(
                source, "irij.Program", null, CompileOptions.stateMachine());
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
        for (String l : lines) sb.append(l).append(System.lineSeparator());
        return sb.toString();
    }

    @Test void pure_body_no_ops() throws Exception {
        String src = """
            effect Log
              log :: Str -> ()
            handler noop :: Log
              log msg => resume ()
            with noop
              println "inside"
            println "after"
            """;
        assertEquals(nl("inside", "after"), runSM(src));
    }

    @Test void single_op_call_resumed() throws Exception {
        String src = """
            effect Log
              log :: Str -> ()
            handler echo :: Log
              log msg =>
                println ("got: " ++ msg)
                resume ()
            with echo
              log "hi"
            println "done"
            """;
        assertEquals(nl("got: hi", "done"), runSM(src));
    }

    @Test void single_op_with_pre_and_post_stmts() throws Exception {
        String src = """
            effect Log
              log :: Str -> ()
            handler echo :: Log
              log msg =>
                println ("got: " ++ msg)
                resume ()
            with echo
              println "before"
              log "middle"
              println "after"
            println "done"
            """;
        assertEquals(nl("before", "got: middle", "after", "done"), runSM(src));
    }

    @Test void single_op_binding_receives_resume_value() throws Exception {
        String src = """
            effect Ask
              ask :: () -> Int
            handler answer :: Ask
              ask => resume 42
            with answer
              x := ask ()
              println x
            """;
        assertEquals(nl("42"), runSM(src));
    }

    @Test void abort_path_returns_clause_value() throws Exception {
        String src = """
            effect Fail
              fail :: Str -> ()
            handler aborts :: Fail
              fail msg =>
                println ("aborted: " ++ msg)
                "gave-up"
            r := with aborts
              fail "boom"
              "unreachable"
            println r
            """;
        assertEquals(nl("aborted: boom", "gave-up"), runSM(src));
    }
}
