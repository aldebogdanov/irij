package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An inline {@code with h do (…)} whose body performs, nested in another
 * {@code with}'s body. The block form always lowered (a nested
 * {@code Stmt.With} is a segment of the outer state machine); the inline
 * form arrives as {@code ExprStmt(Block([With]))} or inside a larger
 * expression and used to be refused with "with: body shape not supported
 * by state-machine lowering".
 */
class NestedInlineWithTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(NestedInlineWithTest.class.getClassLoader()); }
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
        return buf.toString().trim().replace("\r", "");
    }

    private static final String FX = """
            effect Ask
              ask :: Int

            effect Tell
              tell :: Int Int

            effect T
              tick :: Str -> Int

            handler answer :: Ask
              ask () => resume 42

            handler answer2 :: Ask
              ask () => resume 7

            handler teller :: Tell
              tell n => resume (n + 1)

            handler logger :: T
              state :! #[]
              tick s =>
                state <- conj state s
                resume 1

            """;

    @Test void innermostHandlerAnswers() throws Exception {
        assertEquals("7", run(FX + "println (with answer do (with answer2 do (ask ())))"));
    }

    @Test void innerBodyPerformsTheOuterEffect() throws Exception {
        assertEquals("43", run(FX + "println (with answer do (with teller do (tell (ask ()))))"));
    }

    @Test void threeLevels() throws Exception {
        assertEquals("43", run(FX
                + "println (with logger do (with answer do (with teller do (tell (ask ())))))"));
    }

    @Test void inALambdaCalledLater() throws Exception {
        assertEquals("7", run(FX + """
                f := (-> with answer do (with answer2 do (ask ())))
                println (f ())
                """));
    }

    @Test void aHelperThatInstallsHandlersAroundACallback() throws Exception {
        assertEquals("43", run(FX + """
                fn live :: Fn Fn
                  (f -> (-> with answer do (with teller do (f ()))))

                println ((live (-> tell (ask ()))) ())
                """));
    }

    @Test void insideAnExpression() throws Exception {
        assertEquals("8", run(FX + "println (with answer do (1 + (with answer2 do (ask ()))))"));
    }

    @Test void asAnOpArgument() throws Exception {
        assertEquals("43", run(FX + "println (with teller do (tell (with answer do (ask ()))))"));
    }

    @Test void outerPerformsAroundTheInnerWithInOrder() throws Exception {
        assertEquals("44\n#[\"a\" \"in\" \"b\"]", run(FX + """
                r := with logger
                  a := tick "a"
                  x := with answer do (tick "in" + ask ())
                  b := tick "b"
                  a + x + b - 1
                println r
                println logger.state
                """));
    }

    @Test void aDiscardedStatementInTheMiddleOfABody() throws Exception {
        assertEquals("1\n#[\"in\" \"after\"]", run(FX + """
                r := with logger
                  with answer do (tick "in")
                  tick "after"
                println r
                println logger.state
                """));
    }

    @Test void theInnerWithAsTheBodysValue() throws Exception {
        assertEquals("43\n#[\"before\"]", run(FX + """
                r := with logger
                  tick "before"
                  with answer do (ask () + 1)
                println r
                println logger.state
                """));
    }
}
