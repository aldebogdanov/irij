package dev.irij.compiler;

import dev.irij.ast.AstBuilder;
import dev.irij.ast.Decl;
import dev.irij.ast.Expr;
import dev.irij.ast.Pattern;
import dev.irij.parser.IrijParseDriver;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Patterns the parser reads greedily, and the places a pattern couldn't
 * go: lambda parameters, a keyword followed by more patterns, nullary
 * constructors among siblings, `true`/`false`, and a record update on a
 * value built by a product spec's constructor.
 */
class PatternGapsTest {

    static final class BytesLoader extends ClassLoader {
        BytesLoader() { super(PatternGapsTest.class.getClassLoader()); }
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
        return buf.toString().trim();
    }

    private static List<Decl> ast(String source) {
        var parsed = IrijParseDriver.parse(source);
        assertTrue(parsed.errors().isEmpty(), () -> "parse errors: " + parsed.errors());
        return new AstBuilder().build(parsed.tree());
    }

    // ── Lambda parameters ───────────────────────────────────────────────

    @Test
    void lambdaTakesATuplePattern() throws Exception {
        assertEquals("3", run("""
                f := (#(a b) -> a + b)
                println (f #(1 2))
                """));
    }

    @Test
    void lambdaPatternsMixWithPlainParamsAndCurry() throws Exception {
        assertEquals("Ada7\n10", run("""
                g := ({name= n} x -> n ++ to-str x)
                println (g {name= "Ada" age= 3} 7)
                k := (#(a b) #(c d) -> a + b + c + d)
                add := k #(1 2)
                println (add #(3 4))
                """));
    }

    @Test
    void lambdaPatternsWorkInsidePipelines() throws Exception {
        assertEquals("#[\"one=1\" \"two=2\"]", run("""
                pairs := #[#(1 "one") #(2 "two")]
                println (pairs |> @ (#(n w) -> w ++ "=" ++ to-str n))
                """));
    }

    @Test
    void lambdaPatternMismatchNamesTheParameter() throws Exception {
        assertEquals("true", run("""
                f := (#(a b) -> a + b)
                r := try (-> f 5)
                match r
                  Err msg => println (starts-with? msg "Pattern match failure in lambda parameter")
                  _ => println false
                """));
    }

    @Test
    void lambdaPatternDesugarsToAMatch() {
        Decl d = ast("f := (#(a b) -> a)").get(0);
        var bind = (dev.irij.ast.Stmt.Bind) ((Decl.BindingDecl) d).stmt();
        var lam = assertInstanceOf(Expr.Lambda.class, bind.value());
        assertInstanceOf(Pattern.VarPat.class, lam.params().get(0));
        assertInstanceOf(Expr.MatchExpr.class, lam.body());
    }

    // ── Keywords take no arguments ──────────────────────────────────────

    @Test
    void keywordThenVariableInATuple() throws Exception {
        assertEquals("ok 42\nerror boom", run("""
                fn show
                  #(:ok v) => "ok " ++ to-str v
                  #(:error e) => "error " ++ e
                println (show #(:ok 42))
                println (show #(:error "boom"))
                """));
    }

    @Test
    void keywordPatternHasNoArgument() {
        Decl d = ast("""
                fn f
                  #(:ok v) => v
                """).get(0);
        var fn = (Decl.FnDecl) d;
        var arms = ((Decl.FnBody.MatchArmsBody) fn.body()).arms();
        var tuple = assertInstanceOf(Pattern.TuplePat.class, arms.get(0).pattern());
        assertEquals(2, tuple.elements().size());
    }

    // ── Constructors among siblings ─────────────────────────────────────

    @Test
    void nullaryConstructorsInATuple() throws Exception {
        assertEquals("red-green\nother", run("""
                spec Light
                  Red
                  Green
                fn both
                  #(Red Green) => "red-green"
                  _ => "other"
                println (both #(Red Green))
                println (both #(Green Red))
                """));
    }

    @Test
    void constructorTakesOnlyItsDeclaredFields() throws Exception {
        assertEquals("2.0\n2\n5", run("""
                spec Light
                  Red
                  Green
                spec Shape
                  Circle Float
                fn f
                  #(Circle r 1) => r
                  _ => 0.0
                println (f #((Circle 2.0) 1))
                fn v
                  #[Red ...rest] => length rest
                  _ => -1
                println (v #[Red Green Green])
                fn o
                  #(Ok x Red) => x
                  _ => 0
                println (o #((Ok 5) Red))
                """));
    }

    @Test
    void multiFieldVariantCountsEveryField() throws Exception {
        List<Decl> decls = ast("""
                spec Shape
                  Circle Float
                  Rect Float Float
                  Node (Shape) Int (Shape)
                """);
        var sum = (Decl.SpecBody.SumSpec) ((Decl.SpecDecl) decls.get(0)).body();
        assertEquals(List.of(1, 2, 3), sum.variants().stream().map(Decl.Variant::arity).toList());
        assertEquals("rg", run("""
                spec Light
                  Red
                  Green
                spec Pair
                  Pair Light Light
                fn pr
                  (Pair Red Green) => "rg"
                  _ => "?"
                println (pr (Pair Red Green))
                """));
    }

    @Test
    void tooManyFieldsIsACompileError() {
        var e = assertThrows(IrijCompiler.CompileException.class, () -> run("""
                spec Shape
                  Circle Float
                fn f
                  Circle r extra => r
                  _ => 0.0
                println (f (Circle 1.0))
                """));
        assertTrue(e.getMessage().contains("Circle takes 1 field, but is given 2"), e.getMessage());
    }

    // ── true / false ────────────────────────────────────────────────────

    @Test
    void boolPatternsMatchOnlyThemselves() throws Exception {
        assertEquals("F\nT\nstandard\nexpress", run("""
                fn tf
                  true => "T"
                  false => "F"
                println (tf false)
                println (tf true)
                fn ex
                  {express= true} => "express"
                  _ => "standard"
                println (ex {express= false})
                println (ex {express= true})
                """));
    }

    // ── Record update on a product-spec value ───────────────────────────

    @Test
    void recordUpdateKeepsTheSpec() throws Exception {
        assertEquals("User {name= \"Ada\" age= 37}\nUser\nhi Ada", run("""
                spec User
                  name :: Str
                  age :: Int
                ada := User "Ada" 36
                older := {...ada age= 37}
                println older
                println (type-of older)
                fn greet :: User Str
                  (u -> "hi " ++ u.name)
                println (greet older)
                """));
    }

    @Test
    void recordUpdateRecertifiesFields() throws Exception {
        assertEquals("Err \"User field 'age': expected Int, got Str\"", run("""
                spec User
                  name :: Str
                  age :: Int
                ada := User "Ada" 36
                println (try (-> {...ada age= "old"}))
                """));
    }

    @Test
    void recordUpdateAddingAFieldGivesAMap() throws Exception {
        assertEquals("Map\n{name= \"Ada\" age= 36 nick= \"A\"}", run("""
                spec User
                  name :: Str
                  age :: Int
                ada := User "Ada" 36
                ext := {...ada nick= "A"}
                println (type-of ext)
                println ext
                """));
    }
}
