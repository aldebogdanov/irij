package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The {@code model} declaration, which binds a Quint specification to
 * the Irij code implementing it.
 *
 * <p>It desugars in {@link dev.irij.ast.AstBuilder} to the std.quint
 * model record plus one hoisted function per clause, so nothing
 * downstream of the parser knows it exists. These tests read the
 * desugared record back to pin what it produces; replay against real
 * recorded traces lives in {@code tests/test-quint.irj}.
 */
class ModelDeclTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(ModelDeclTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static String run(String source) throws Exception {
        var classes = IrijCompiler.compileSourceMulti(source, "irij.ModelTest",
                null, CompileOptions.defaults(), java.util.List.of(), "ModelTest.irj");
        var loader = new BytesLoader();
        Class<?> main = null;
        for (var e : classes.entrySet()) {
            Class<?> c = loader.define(e.getKey(), e.getValue());
            if (e.getKey().equals("irij.ModelTest")) main = c;
        }
        PrintStream orig = System.out;
        var buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf));
        try {
            Method m = main.getMethod("main", String[].class);
            m.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(orig);
        }
        return buf.toString().trim();
    }

    @Test
    void headerBecomesRecordFields() throws Exception {
        String src = """
            model bank :: "spec/bank.qnt" :pure {main= "bankTest"}
              start           => {n= 0}
              incr  st amount => {...st n= (st.n + amount)}
            println bank.spec-file
            println bank.mode
            println bank.main
            println bank.start
            """;
        assertEquals("spec/bank.qnt\n:pure\nbankTest\n{n= 0}", run(src));
    }

    @Test
    void picksBindByNameInTheParameterList() throws Exception {
        // The whole point of the clause form: `amount` is the spec's
        // pick name, read out of the picks record by the desugaring.
        String src = """
            model bank :: "spec/bank.qnt" :pure
              start           => {n= 0}
              incr  st amount => {...st n= (st.n + amount)}
            f := get "incr" bank.actions
            println (f {n= 1} {amount= 41})
            """;
        assertEquals("{n= 42}", run(src));
    }

    @Test
    void aPickTheTraceDoesNotCarryIsUnit() throws Exception {
        String src = """
            model bank :: "spec/bank.qnt" :pure
              start          => {n= 0}
              incr  st missing => {...st n= missing}
            f := get "incr" bank.actions
            println (f {n= 1} {})
            """;
        assertEquals("{n= ()}", run(src));
    }

    @Test
    void aLiveActionTakesOnlyPicks() throws Exception {
        String src = """
            model bank :: "spec/bank.qnt" :live
              init          => 1
              state         => {n= 7}
              incr  amount  => amount
            f := get "incr" bank.actions
            println (f {amount= 5})
            println (bank.state ())
            """;
        assertEquals("5\n{n= 7}", run(src));
    }

    @Test
    void everyNonLifecycleClauseIsAnAction() throws Exception {
        String src = """
            model bank :: "spec/bank.qnt" :pure
              start        => {}
              incr  st     => st
              decr  st     => st
            println (sort (keys bank.actions))
            """;
        assertEquals("#[decr incr]", run(src));
    }

    @Test
    void modelStaysUsableAsAnOrdinaryName() throws Exception {
        // A soft keyword: `model` heads a declaration, and is still a
        // field name everywhere a name this ordinary turns up.
        String src = """
            cfg := {model= "opus" temp= 1}
            println cfg.model
            println (get "model" cfg)
            """;
        assertEquals("opus\nopus", run(src));
    }

    @Test
    void aModeThatIsNeitherPureNorLiveIsRejected() {
        String src = """
            model bank :: "spec/bank.qnt" :sideways
              start => {}
            """;
        Exception e = assertThrows(Exception.class, () -> run(src));
        assertTrue(rootMessage(e).contains("mode must be :pure or :live"), rootMessage(e));
    }

    @Test
    void aPureActionWithoutAStateParameterIsRejected() {
        String src = """
            model bank :: "spec/bank.qnt" :pure
              start => {}
              incr  => 1
            """;
        Exception e = assertThrows(Exception.class, () -> run(src));
        assertTrue(rootMessage(e).contains("takes the state as its first parameter"),
                rootMessage(e));
    }

    @Test
    void startTakesNoParameters() {
        String src = """
            model bank :: "spec/bank.qnt" :pure
              start st => {}
            """;
        Exception e = assertThrows(Exception.class, () -> run(src));
        assertTrue(rootMessage(e).contains("is the initial state, not a function"),
                rootMessage(e));
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        return String.valueOf(cur.getMessage());
    }
}
