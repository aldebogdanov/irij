package dev.irij.interpreter;

import dev.irij.ast.AstBuilder;
import dev.irij.parser.IrijParseDriver;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Irij interpreter, covering all Phase 1 language features.
 * Each test parses Irij source, builds AST, interprets, and checks output.
 */
class InterpreterTest {

    // ═══════════════════════════════════════════════════════════════════
    // Test Infrastructure
    // ═══════════════════════════════════════════════════════════════════

    private String run(String source) {
        var baos = new ByteArrayOutputStream();
        var out = new PrintStream(baos);
        var parseResult = IrijParseDriver.parse(source + "\n");
        assertFalse(parseResult.hasErrors(),
            () -> "Parse errors: " + parseResult.errors());
        var ast = new AstBuilder().build(parseResult.tree());
        new Interpreter(out).run(ast);
        return baos.toString().strip();
    }

    private Object eval(String source) {
        var parseResult = IrijParseDriver.parse(source + "\n");
        assertFalse(parseResult.hasErrors(),
            () -> "Parse errors: " + parseResult.errors());
        var ast = new AstBuilder().build(parseResult.tree());
        return new Interpreter().run(ast);
    }

    private void assertRuntimeError(String source) {
        var parseResult = IrijParseDriver.parse(source + "\n");
        if (parseResult.hasErrors()) return; // parse error is fine too
        var ast = new AstBuilder().build(parseResult.tree());
        assertThrows(IrijRuntimeError.class, () -> new Interpreter().run(ast));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 2: Arithmetic
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class Arithmetic {
        @Test void intAddition() {
            assertEquals("3", run("println (1 + 2)"));
        }

        @Test void intMultiplication() {
            assertEquals("6", run("println (2 * 3)"));
        }

        @Test void precedence() {
            assertEquals("7", run("println (1 + 2 * 3)"));
        }

        @Test void parentheses() {
            assertEquals("9", run("println ((1 + 2) * 3)"));
        }

        @Test void subtraction() {
            assertEquals("1", run("println (3 - 2)"));
        }

        @Test void divisionReturnsFloat() {
            assertEquals("3.5", run("println (7 / 2)"));
        }

        @Test void modulo() {
            assertEquals("1", run("println (7 % 2)"));
        }

        @Test void power() {
            assertEquals("8", run("println (2 ** 3)"));
        }

        @Test void unaryMinus() {
            assertEquals("-5", run("println (-5)"));
        }

        @Test void numericWidening() {
            assertEquals("2.5", run("println (1 + 1.5)"));
        }

        @Test void floatArithmetic() {
            assertEquals("6.28", run("println (3.14 * 2)"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 3: Bindings and Print
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class Bindings {
        @Test void immutableBinding() {
            assertEquals("42", run("x := 42\nprintln x"));
        }

        @Test void bindingWithExpr() {
            assertEquals("15", run("x := 10\nprintln (x + 5)"));
        }

        @Test void multipleBindings() {
            assertEquals("30", run("x := 10\ny := 20\nprintln (x + y)"));
        }

        @Test void printString() {
            assertEquals("hello", run("println \"hello\""));
        }

        @Test void toStr() {
            assertEquals("42", run("println (to-str 42)"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 4: Lambda and Application
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class Lambdas {
        @Test void simpleLambda() {
            assertEquals("6", run("f := (x -> x + 1)\nprintln (f 5)"));
        }

        @Test void twoArgLambda() {
            assertEquals("7", run("println ((x y -> x + y) 3 4)"));
        }

        @Test void zeroArgLambda() {
            assertEquals("42", run("f := (-> 42)\nprintln (f ())"));
        }

        @Test void closureCapture() {
            assertEquals("15", run("x := 10\nf := (y -> x + y)\nprintln (f 5)"));
        }

        @Test void higherOrder() {
            assertEquals("10", run("""
                apply := (f x -> f x)
                double := (x -> x * 2)
                println (apply double 5)"""));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 5: fn Declarations
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class FnDecl {
        @Test void lambdaBodyFn() {
            assertEquals("7", run("""
                fn add :: Int -> Int -> Int
                  (x y -> x + y)
                println (add 3 4)"""));
        }

        @Test void fnNoTypeAnnotation() {
            assertEquals("10", run("""
                fn double
                  (x -> x * 2)
                println (double 5)"""));
        }

        @Test void recursiveFn() {
            assertEquals("120", run("""
                fn fact
                  (n -> if (n == 0) 1 else (n * fact (n - 1)))
                println (fact 5)"""));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 6: Booleans, Comparison, Logic
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class Booleans {
        @Test void trueAndFalse() {
            assertEquals("true", run("println true"));
            assertEquals("false", run("println false"));
        }

        @Test void comparison() {
            assertEquals("true", run("println (3 > 2)"));
            assertEquals("false", run("println (1 > 2)"));
        }

        @Test void equality() {
            assertEquals("true", run("println (1 == 1)"));
            assertEquals("false", run("println (1 == 2)"));
        }

        @Test void notEqual() {
            assertEquals("true", run("println (1 /= 2)"));
        }

        @Test void logicalAnd() {
            assertEquals("true", run("println (true && true)"));
            assertEquals("false", run("println (true && false)"));
        }

        @Test void logicalOr() {
            assertEquals("true", run("println (false || true)"));
            assertEquals("false", run("println (false || false)"));
        }

        @Test void logicalNot() {
            assertEquals("false", run("println (!true)"));
            assertEquals("true", run("println (!false)"));
        }

        @Test void combinedLogic() {
            assertEquals("true", run("println (3 > 2 && 1 < 5)"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 7: If Expressions
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class IfExpressions {
        @Test void inlineIfTrue() {
            assertEquals("1", run("println (if true 1 else 0)"));
        }

        @Test void inlineIfFalse() {
            assertEquals("0", run("println (if false 1 else 0)"));
        }

        @Test void blockIf() {
            assertEquals("yes", run("""
                if true
                  println "yes"
                else
                  println "no\""""));
        }

        @Test void blockIfFalse() {
            assertEquals("no", run("""
                if false
                  println "yes"
                else
                  println "no\""""));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 8: Pattern Matching
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class PatternMatching {
        @Test void matchLiteral() {
            assertEquals("one", run("""
                match 1
                  0 => println "zero"
                  1 => println "one"
                  _ => println "other\""""));
        }

        @Test void matchVariable() {
            assertEquals("42", run("""
                match 42
                  x => println x"""));
        }

        @Test void matchWildcard() {
            assertEquals("default", run("""
                match 99
                  0 => println "zero"
                  _ => println "default\""""));
        }

        @Test void matchArmFn() {
            assertEquals("55", run("""
                fn fib
                  0 => 0
                  1 => 1
                  n => fib (n - 1) + fib (n - 2)
                println (fib 10)"""));
        }

        @Test void factorialMatchFn() {
            assertEquals("120", run("""
                fn fact
                  0 => 1
                  n => n * fact (n - 1)
                println (fact 5)"""));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 9: Imperative Blocks
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class ImperativeBlocks {
        @Test void simpleImperativeBlock() {
            assertEquals("hello", run("""
                fn greet
                  =>
                  println "hello"
                greet ()"""));
        }

        @Test void imperativeWithParam() {
            assertEquals("hi Jo", run("""
                fn greet
                  => name
                  println ("hi " ++ name)
                greet "Jo\""""));
        }

        @Test void imperativeWithTwoParams() {
            assertEquals("hi Jo", run("""
                fn greet
                  => hi name
                  println (hi ++ " " ++ name)
                greet "hi" "Jo\""""));
        }

        @Test void imperativeMultiStmt() {
            assertEquals("1\n2\n3", run("""
                fn count-to-three
                  =>
                  println 1
                  println 2
                  println 3
                count-to-three ()"""));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 10: Mutable Bindings
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class Mutability {
        @Test void mutableBinding() {
            assertEquals("1", run("""
                x :! 0
                x <- x + 1
                println x"""));
        }

        @Test void closureCapturesMutable() {
            assertEquals("1", run("""
                x :! 0
                fn inc
                  =>
                  x <- x + 1
                inc ()
                println x"""));
        }

        @Test void cannotAssignToImmutable() {
            assertRuntimeError("x := 42\nx <- 0");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 11: Collections
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class Collections {
        @Test void vectorLiteral() {
            assertEquals("#[1 2 3]", run("println #[1 2 3]"));
        }

        @Test void emptyVector() {
            assertEquals("#[]", run("println #[]"));
        }

        @Test void setLiteral() {
            var result = run("println #{1 2 3}");
            assertTrue(result.startsWith("#{") && result.endsWith("}"));
        }

        @Test void tupleLiteral() {
            assertEquals("#(1 hello)", run("println #(1 \"hello\")"));
        }

        @Test void mapLiteral() {
            var result = run("println {name= \"Jo\"}");
            assertTrue(result.contains("name="));
        }

        @Test void recordUpdate() {
            assertEquals("31", run("""
                person := {name= "Jo" age= 30}
                updated := {...person age= 31}
                println updated.age"""));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 12: Type Declarations (ADTs)
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class TypeDeclarations {
        @Test void sumType() {
            assertEquals("Some 42", run("""
                type Option a
                  Some a
                  None
                println (Some 42)"""));
        }

        @Test void zeroArgConstructor() {
            assertEquals("None", run("""
                type Option a
                  Some a
                  None
                println None"""));
        }

        @Test void patternMatchOnADT() {
            assertEquals("42", run("""
                type Option a
                  Some a
                  None
                match (Some 42)
                  Some x => println x
                  None => println "nothing\""""));
        }

        @Test void resultType() {
            assertEquals("ok: hello", run("""
                type Result a e
                  Ok a
                  Err e
                match (Ok "hello")
                  Ok v => println ("ok: " ++ v)
                  Err e => println ("err: " ++ e)"""));
        }

        @Test void productType() {
            assertEquals("Person {name= Jo age= 42}", run("""
                type Person
                  name :: Str
                  age :: Int
                println (Person "Jo" 42)"""));
        }

        @Test void productTypeDotAccess() {
            assertEquals("Jo\n42", run("""
                type Person
                  name :: Str
                  age :: Int
                p := Person "Jo" 42
                println p.name
                println p.age"""));
        }

        @Test void productTypePositionalMatch() {
            assertEquals("Jo 42", run("""
                type Person
                  name :: Str
                  age :: Int
                match (Person "Jo" 42)
                  Person n a => println (n ++ " " ++ (to-str a))"""));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 13: Destructuring
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class Destructuring {
        @Test void mapDestructure() {
            assertEquals("Jo", run("""
                {name= n} := {name= "Jo" age= 30}
                println n"""));
        }

        @Test void vectorSpread() {
            assertEquals("1\n#[2 3]", run("""
                match #[1 2 3]
                  #[x ...r] => do (println x) (println r)"""));
        }

        @Test void productTypeDestructure() {
            assertEquals("Jo is 42", run("""
                type Person
                  name :: Str
                  age :: Int
                {name= n age= a} := Person "Jo" 42
                println (n ++ " is " ++ (to-str a))"""));
        }

        @Test void productTypeMatchDestructure() {
            assertEquals("Jo", run("""
                type Person
                  name :: Str
                  age :: Int
                fn get-name
                  {name= n} => n
                println (get-name (Person "Jo" 42))"""));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 14: Guards
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class Guards {
        @Test void matchWithGuard() {
            assertEquals(":good", run("""
                match 80
                  s | s >= 90 => println ":excellent"
                  s | s >= 70 => println ":good"
                  _ => println ":needs-work\""""));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 15: Pipeline and Composition
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class Pipelines {
        @Test void pipeForward() {
            assertEquals("10", run("""
                println (5 |> (x -> x * 2))"""));
        }

        @Test void pipeChain() {
            assertEquals("12", run("""
                println (5 |> (x -> x + 1) |> (x -> x * 2))"""));
        }

        @Test void composeForward() {
            assertEquals("8", run("""
                f := (x -> x + 1) >> (x -> x * 2)
                println (f 3)"""));
        }

        @Test void composeBackward() {
            assertEquals("8", run("""
                f := (x -> x * 2) << (x -> x + 1)
                println (f 3)"""));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 16: Seq Ops
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class SeqOps {
        @Test void reduceSum() {
            assertEquals("6", run("println (#[1 2 3] |> /+)"));
        }

        @Test void reduceProduct() {
            assertEquals("42", run("println (#[2 3 7] |> /*)"));
        }

        @Test void count() {
            assertEquals("3", run("println (#[1 2 3] |> /#)"));
        }

        @Test void mapOver() {
            assertEquals("#[2 4 6]", run("""
                println (#[1 2 3] |> @ (x -> x * 2))"""));
        }

        @Test void filter() {
            assertEquals("#[3 4]", run("""
                println (#[1 2 3 4] |> /? (x -> x > 2))"""));
        }

        @Test void allTrue() {
            assertEquals("false", run("println (#[true true false] |> /&)"));
        }

        @Test void anyTrue() {
            assertEquals("true", run("println (#[false true] |> /|)"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 17: Ranges
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class Ranges {
        @Test void inclusiveRange() {
            assertEquals("15", run("println (1 .. 5 |> /+)"));
        }

        @Test void exclusiveRange() {
            assertEquals("#[1 2 3 4]", run("println (1 ..< 5 |> to-vec)"));
        }

        @Test void rangeToVec() {
            assertEquals("#[1 2 3 4 5]", run("println (1 .. 5 |> to-vec)"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 18: String Interpolation
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class StringInterpolation {
        @Test void simpleInterpolation() {
            assertEquals("hello world", run("""
                name := "world"
                println "hello ${name}\""""));
        }

        @Test void exprInterpolation() {
            assertEquals("2 + 3 = 5", run("""
                println "2 + 3 = ${2 + 3}\""""));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 19: Remaining Builtins
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class MoreBuiltins {
        @Test void concat() {
            assertEquals("#[1 2 3 4]", run("println (#[1 2] ++ #[3 4])"));
        }

        @Test void stringConcat() {
            assertEquals("helloworld", run("println (\"hello\" ++ \"world\")"));
        }

        @Test void headTail() {
            assertEquals("1\n#[2 3]", run("""
                println (head #[1 2 3])
                println (tail #[1 2 3])"""));
        }

        @Test void length() {
            assertEquals("3", run("println (length #[1 2 3])"));
        }

        @Test void integerDivision() {
            assertEquals("3", run("println (div 7 2)"));
        }

        @Test void moduloOperator() {
            assertEquals("1", run("println (7 % 2)"));
        }

        @Test void dotAccessOnMap() {
            assertEquals("Jo", run("""
                person := {name= "Jo" age= 30}
                println person.name"""));
        }

        @Test void keywords() {
            assertEquals(":ok", run("println :ok"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 20: Partial Application
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class PartialApplication {
        @Test void partialApply() {
            assertEquals("6", run("""
                fn add
                  (x y -> x + y)
                add-one := add 1
                println (add-one 5)"""));
        }

        @Test void partialBuiltin() {
            assertEquals("3", run("""
                d := div 7
                println (d 2)"""));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 21: Rational Arithmetic
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class Rationals {
        @Test void rationalAddition() {
            assertEquals("1/1", run("println (2/3 + 1/3)"));
        }

        @Test void rationalDisplay() {
            assertEquals("2/3", run("println 2/3"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 22: Stub Declarations
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class Stubs {
        @Test void effectDeclParses() {
            // Should not throw
            run("""
                effect Console
                  print :: Str -> ()
                  read-line :: () -> Str
                println "ok\"""");
        }

        @Test void moduleDeclParses() {
            run("""
                mod myapp.core
                println "ok\"""");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 23: Error Messages
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class Errors {
        @Test void undefinedVariable() {
            var e = assertThrows(IrijRuntimeError.class, () -> eval("x"));
            assertTrue(e.getMessage().contains("Undefined variable: x"));
        }

        @Test void divisionByZero() {
            var e = assertThrows(IrijRuntimeError.class, () -> eval("div 1 0"));
            assertTrue(e.getMessage().contains("Division by zero"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 24: Reduce (/^) and Scan (/$)
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class ReduceAndScan {
        @Test void reduceWithPlus() {
            assertEquals("10", run("println (#[1 2 3 4] |> /^ (+))"));
        }

        @Test void reduceWithMultiply() {
            assertEquals("24", run("println (#[1 2 3 4] |> /^ (*))"));
        }

        @Test void reduceWithLambda() {
            assertEquals("10", run("println (#[1 2 3 4] |> /^ (a b -> a + b))"));
        }

        @Test void reduceWithMax() {
            assertEquals("5", run("println (#[3 1 5 2] |> /^ (a b -> if (a > b) a else b))"));
        }

        @Test void scanWithPlus() {
            assertEquals("#[1 3 6 10]", run("println (#[1 2 3 4] |> /$ (+))"));
        }

        @Test void scanWithMultiply() {
            assertEquals("#[1 2 6 24]", run("println (#[1 2 3 4] |> /$ (*))"));
        }

        @Test void scanWithLambda() {
            assertEquals("#[1 3 6 10]", run("println (#[1 2 3 4] |> /$ (a b -> a + b))"));
        }

        @Test void scanEmpty() {
            assertEquals("#[]", run("println (#[] |> /$ (+))"));
        }

        @Test void scanSingleElement() {
            assertEquals("#[42]", run("println (#[42] |> /$ (+))"));
        }

        @Test void reduceEmptyThrows() {
            assertRuntimeError("#[] |> /^ (+)");
        }

        @Test void foldWithInit() {
            assertEquals("10", run("println (fold (+) 0 #[1 2 3 4])"));
        }

        @Test void foldEmptyReturnsInit() {
            assertEquals("0", run("println (fold (+) 0 #[])"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 25: Operator Sections
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class OperatorSections {
        @Test void plusSection() {
            assertEquals("5", run("println ((+) 2 3)"));
        }

        @Test void minusSection() {
            assertEquals("2", run("println ((-) 5 3)"));
        }

        @Test void multiplySection() {
            assertEquals("12", run("println ((*) 3 4)"));
        }

        @Test void concatSection() {
            assertEquals("helloworld", run("println ((++) \"hello\" \"world\")"));
        }

        @Test void eqSection() {
            assertEquals("true", run("println ((==) 1 1)"));
        }

        @Test void sectionAsArg() {
            assertEquals("10", run("println (#[1 2 3 4] |> /^ (+))"));
        }

        @Test void sectionPartial() {
            assertEquals("5", run("""
                add := (+)
                println (add 2 3)"""));
        }

        @Test void sectionInMap() {
            assertEquals("#[2 4 6]", run("""
                double := (x -> (*) 2 x)
                println (#[1 2 3] |> @ double)"""));
        }

        @Test void comparisonSection() {
            assertEquals("true", run("println ((<) 1 2)"));
            assertEquals("false", run("println ((<) 2 1)"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 26: nth, last, get on vectors
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class CollectionAccess {
        @Test void nthVector() {
            assertEquals("20", run("println (nth 1 #[10 20 30])"));
        }

        @Test void nthFirst() {
            assertEquals("10", run("println (nth 0 #[10 20 30])"));
        }

        @Test void nthOutOfBoundsThrows() {
            assertRuntimeError("nth 5 #[10 20 30]");
        }

        @Test void lastVector() {
            assertEquals("30", run("println (last #[10 20 30])"));
        }

        @Test void lastEmptyThrows() {
            assertRuntimeError("last #[]");
        }

        @Test void getOnVector() {
            assertEquals("20", run("println (get 1 #[10 20 30])"));
        }

        @Test void getOnVectorOutOfBounds() {
            assertEquals("()", run("println (get 5 #[10 20 30])"));
        }

        @Test void getOnMap() {
            assertEquals("Jo", run("""
                println (get "name" {name= "Jo" age= 30})"""));
        }

        @Test void nthInPipeline() {
            assertEquals("30", run("""
                println (#[10 20 30] |> nth 2)"""));
        }

        @Test void lastInPipeline() {
            assertEquals("30", run("""
                println (#[10 20 30] |> last)"""));
        }

        @Test void lengthVsCount() {
            // length and /# both return count, but length works on more types
            assertEquals("3", run("println (length #[1 2 3])"));
            assertEquals("3", run("println (#[1 2 3] |> /#)"));
            assertEquals("5", run("println (length \"hello\")"));
        }
    }
}
