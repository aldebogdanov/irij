package dev.irij.compiler;

import dev.irij.ast.AstBuilder;
import dev.irij.ast.Decl;
import dev.irij.parser.IrijParseDriver;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `cond` (first condition that holds) and `case` (equality dispatch) —
 * soft keywords that desugar to an `if` chain and a `match`.
 */
class CaseCondTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(CaseCondTest.class.getClassLoader()); }
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

    // ── cond ────────────────────────────────────────────────────────────

    @Test
    void condTakesTheFirstConditionThatHolds() throws Exception {
        assertEquals("negative\nzero\npositive", run("""
                fn sign
                  => n
                  cond
                    n < 0 => "negative"
                    n == 0 => "zero"
                    _ => "positive"
                println (sign (-3))
                println (sign 0)
                println (sign 9)
                """));
    }

    @Test
    void condIsAnExpression() throws Exception {
        assertEquals("big", run("""
                limit := 10
                label := cond
                  limit > 5 => "big"
                  _ => "small"
                println label
                """));
    }

    @Test
    void condWithoutFallbackRaises() throws Exception {
        assertEquals("true", run("""
                fn f
                  => n
                  cond
                    n > 100 => "huge"
                match (try (-> f 5))
                  Err msg => println (starts-with? msg "cond: no condition held")
                  _ => println false
                """));
    }

    @Test
    void condArmBodiesMayBeBlocks() throws Exception {
        assertEquals("negative 4", run("""
                fn f
                  => n
                  cond
                    n < 0 =>
                      m := 0 - n
                      "negative " ++ to-str m
                    _ => "other"
                println (f (-4))
                """));
    }

    @Test
    void condConditionMayPerformInAWithBody() throws Exception {
        assertEquals("2", run("""
                effect Ask
                  ask :: Str -> Str
                handler ada :: Ask
                  ask _ => resume "a"
                v := with ada
                  cond
                    ask "q" == "b" => 1
                    ask "q" == "a" => 2
                    _ => 3
                println v
                """));
    }

    // ── case ────────────────────────────────────────────────────────────

    @Test
    void caseComparesValues() throws Exception {
        assertEquals("running\nbye\nbye\nhallmark\nusage\nusage", run("""
                fn route
                  => cmd args
                  case cmd
                    "run" => "running"
                    "q" "quit" => "bye"
                    "hallmark" | length args > 2 => "hallmark"
                    _ => "usage"
                println (route "run" #[])
                println (route "q" #[])
                println (route "quit" #[])
                println (route "hallmark" #[1 2 3])
                println (route "hallmark" #[1])
                println (route "nope" #[])
                """));
    }

    @Test
    void caseLooksNamesUpInsteadOfBindingThem() throws Exception {
        assertEquals("at the limit\nelsewhere", run("""
                limit := 10
                fn at
                  => n
                  case n
                    limit => "at the limit"
                    _ => "elsewhere"
                println (at 10)
                println (at 3)
                """));
    }

    @Test
    void caseOnVariantsAndKeywords() throws Exception {
        assertEquals("Green\n1", run("""
                spec Light
                  Red
                  Green
                fn next
                  => l
                  case l
                    Red => Green
                    Green => Red
                println (next Red)
                k := case :ok
                  :ok => 1
                  _ => 2
                println k
                """));
    }

    @Test
    void caseWithoutFallbackNamesTheValue() throws Exception {
        assertEquals("true", run("""
                fn f
                  => x
                  case x
                    1 => "one"
                match (try (-> f 2))
                  Err msg => println (starts-with? msg "case: no arm for 2")
                  _ => println false
                """));
    }

    @Test
    void caseArmsMayPerformInAWithBody() throws Exception {
        assertEquals("#[\"A\"]", run("""
                effect Log
                  log :: Str -> ()
                handler rec :: Log
                  lines :! #[]
                  log m =>
                    lines <- conj lines m
                    resume ()
                with rec
                  case "a"
                    "a" => log "A"
                    _ => log "other"
                println rec.lines
                """));
    }

    // ── soft keywords ───────────────────────────────────────────────────

    @Test
    void condAndCaseStayOrdinaryNames() throws Exception {
        assertEquals("cond is a variable\n8\n1\n2", run("""
                cond := true
                if cond
                  println "cond is a variable"
                case := 7
                println (case + 1)
                m := {case= 1 cond= 2}
                println m.case
                println m.cond
                """));
    }

    @Test
    void imperativeParamNamedCondIsAName() {
        var parsed = IrijParseDriver.parse("""
                fn f
                  => cond
                  if cond 1 else 2
                """);
        assertTrue(parsed.errors().isEmpty(), () -> "parse errors: " + parsed.errors());
        var decls = new AstBuilder().build(parsed.tree());
        assertTrue(decls.get(0) instanceof Decl.FnDecl);
    }
}
