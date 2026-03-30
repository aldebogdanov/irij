package dev.irij.interpreter;

import dev.irij.ast.AstBuilder;
import dev.irij.ast.Decl;
import dev.irij.parser.IrijParseDriver;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

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

        @Test void matchAsExpression() {
            // match in a binding (the original user issue)
            assertEquals("3", run("""
                x := match last #[#[] #[1 2]]
                  #[a b] => a + b
                  _ => 0
                println x"""));
        }

        @Test void matchExprInBinding() {
            assertEquals("yes", run("""
                result := match 42
                  0 => "no"
                  42 => "yes"
                  _ => "maybe"
                println result"""));
        }

        @Test void matchExprNested() {
            // match expression used inside a function body
            assertEquals("zero", run("""
                fn classify
                  x => match x
                    0 => "zero"
                    _ => "other"
                println ~ classify 0"""));
        }

        @Test void matchExprWithGuards() {
            assertEquals("medium", run("""
                x := 50
                label := match x
                  n | n < 10 => "small"
                  n | n < 100 => "medium"
                  _ => "large"
                println label"""));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Step 9: Imperative Blocks
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class ImperativeBlocks {
        @Test void simpleImperativeBlock() {
            assertEquals("hello", run("""
                fn greet ::: Console
                  =>
                  println "hello"
                greet ()"""));
        }

        @Test void imperativeWithParam() {
            assertEquals("hi Jo", run("""
                fn greet ::: Console
                  => name
                  println ("hi " ++ name)
                greet "Jo\""""));
        }

        @Test void imperativeWithTwoParams() {
            assertEquals("hi Jo", run("""
                fn greet ::: Console
                  => hi name
                  println (hi ++ " " ++ name)
                greet "hi" "Jo\""""));
        }

        @Test void imperativeMultiStmt() {
            assertEquals("1\n2\n3", run("""
                fn count-to-three ::: Console
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
                spec Option a
                  Some a
                  None
                println (Some 42)"""));
        }

        @Test void zeroArgConstructor() {
            assertEquals("None", run("""
                spec Option a
                  Some a
                  None
                println None"""));
        }

        @Test void patternMatchOnADT() {
            assertEquals("42", run("""
                spec Option a
                  Some a
                  None
                match (Some 42)
                  Some x => println x
                  None => println "nothing\""""));
        }

        @Test void resultType() {
            assertEquals("ok: hello", run("""
                spec Result a e
                  Ok a
                  Err e
                match (Ok "hello")
                  Ok v => println ("ok: " ++ v)
                  Err e => println ("err: " ++ e)"""));
        }

        @Test void productType() {
            assertEquals("Person {name= Jo age= 42}", run("""
                spec Person
                  name :: Str
                  age :: Int
                println (Person "Jo" 42)"""));
        }

        @Test void productTypeDotAccess() {
            assertEquals("Jo\n42", run("""
                spec Person
                  name :: Str
                  age :: Int
                p := Person "Jo" 42
                println p.name
                println p.age"""));
        }

        @Test void productTypePositionalMatch() {
            assertEquals("Jo 42", run("""
                spec Person
                  name :: Str
                  age :: Int
                match (Person "Jo" 42)
                  Person n a => println (n ++ " " ++ (to-str a))"""));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Spec System (Phase 8a)
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class SpecSystem {
        @Test void sumConstructorCertifiesWithSpecName() {
            var result = eval("""
                spec Shape
                  Circle Float
                  Rect Float Float
                Circle 5.0""");
            assertInstanceOf(Values.Tagged.class, result);
            var tagged = (Values.Tagged) result;
            assertEquals("Circle", tagged.tag());
            assertEquals("Shape", tagged.specName());
        }

        @Test void zeroArgConstructorCertifiesWithSpecName() {
            var result = eval("""
                spec Option a
                  Some a
                  None
                None""");
            assertInstanceOf(Values.Tagged.class, result);
            var tagged = (Values.Tagged) result;
            assertEquals("None", tagged.tag());
            assertEquals("Option", tagged.specName());
        }

        @Test void productConstructorCertifiesWithSpecName() {
            var result = eval("""
                spec Person
                  name :: Str
                  age :: Int
                Person "Alice" 30""");
            assertInstanceOf(Values.Tagged.class, result);
            var tagged = (Values.Tagged) result;
            assertEquals("Person", tagged.tag());
            assertEquals("Person", tagged.specName());
            assertEquals("Alice", tagged.namedFields().get("name"));
            assertEquals(30L, tagged.namedFields().get("age"));
        }

        @Test void parametricSpecCertification() {
            var result = eval("""
                spec Result ok err
                  Ok ok
                  Err err
                Ok "hello\"""");
            assertInstanceOf(Values.Tagged.class, result);
            var tagged = (Values.Tagged) result;
            assertEquals("Ok", tagged.tag());
            assertEquals("Result", tagged.specName());
        }

        @Test void specRegistryHotReload() {
            // Re-evaluating a spec updates the registry — important for nREPL
            assertEquals("Alice\n30\nBob\n25\ntrue", run("""
                spec Person
                  name :: Str
                  age :: Int
                p1 := Person "Alice" 30
                println p1.name
                println p1.age
                spec Person
                  name :: Str
                  age :: Int
                p2 := Person "Bob" 25
                println p2.name
                println p2.age
                println (p2.age == 25)"""));
        }

        @Test void patternMatchingOnSpecVariants() {
            assertEquals("78.53975\n12.0", run("""
                spec Shape
                  Circle Float
                  Rect Float Float
                fn area
                  (Circle r) => 3.14159 * r * r
                  (Rect w h) => w * h
                println (area (Circle 5.0))
                println (area (Rect 3.0 4.0))"""));
        }

        @Test void certifiedValuePassesBoundaryCheck() {
            // Already certified → O(1) pass (no re-validation)
            var result = eval("""
                spec Person
                  name :: Str
                  age :: Int
                p := Person "Jo" 42
                q := p :: Person
                q""");
            assertInstanceOf(Values.Tagged.class, result);
            var tagged = (Values.Tagged) result;
            assertEquals("Person", tagged.specName());
        }

        @Test void uncertifiedMapValidatedAtBinding() {
            // Plain map gets validated and certified as Person
            var result = eval("""
                spec Person
                  name :: Str
                  age :: Int
                raw := {name= "Bob" age= 25}
                p := raw :: Person
                p""");
            assertInstanceOf(Values.Tagged.class, result);
            var tagged = (Values.Tagged) result;
            assertEquals("Person", tagged.specName());
            assertEquals("Bob", tagged.namedFields().get("name"));
            assertEquals(25L, tagged.namedFields().get("age"));
        }

        @Test void validationRejectsMissingField() {
            assertRuntimeError("""
                spec Person
                  name :: Str
                  age :: Int
                raw := {name= "Bob"}
                p := raw :: Person""");
        }

        @Test void validationRejectsWrongVariant() {
            assertRuntimeError("""
                spec Shape
                  Circle Float
                  Rect Float Float
                spec Color
                  Red
                  Blue
                c := Red
                s := c :: Shape""");
        }

        @Test void validationRejectsNonTaggedForSum() {
            assertRuntimeError("""
                spec Shape
                  Circle Float
                  Rect Float Float
                s := 42 :: Shape""");
        }

        @Test void unknownSpecAnnotationErrors() {
            assertRuntimeError("""
                x := 42 :: Nonexistent""");
        }

        @Test void fnBoundaryValidatesInput() {
            // fn takes Person, validates untagged map arg
            assertEquals("Alice", run("""
                spec Person
                  name :: Str
                  age :: Int
                fn get-name :: Person Str
                  (p -> p.name)
                println (get-name {name= "Alice" age= 30})"""));
        }

        @Test void fnBoundaryRejectsInvalidInput() {
            assertRuntimeError("""
                spec Person
                  name :: Str
                  age :: Int
                fn get-name :: Person Str
                  (p -> p.name)
                get-name {name= "Alice"}""");
        }

        @Test void fnBoundaryCertifiedArgPassesO1() {
            // Certified value passes boundary check in O(1)
            assertEquals("Alice", run("""
                spec Person
                  name :: Str
                  age :: Int
                fn get-name :: Person Str
                  (p -> p.name)
                p := Person "Alice" 30
                println (get-name p)"""));
        }

        @Test void fnBoundaryValidatesReturnValue() {
            // Return value validated against output spec
            assertRuntimeError("""
                spec Person
                  name :: Str
                  age :: Int
                fn make-person :: Str Person
                  (name -> {name= name})
                make-person "Alice\"""");
        }

        @Test void fnBoundaryWithMatchFn() {
            assertEquals("circle\nrect", run("""
                spec Shape
                  Circle Float
                  Rect Float Float
                fn describe :: Shape Str
                  (Circle _) => "circle"
                  (Rect _ _) => "rect"
                println (describe (Circle 5.0))
                println (describe (Rect 3.0 4.0))"""));
        }

        @Test void fnBoundaryWithImperativeFn() {
            assertEquals("Alice is 30", run("""
                spec Person
                  name :: Str
                  age :: Int
                fn describe :: Person Str ::: Console
                  => p
                  println (p.name ++ " is " ++ (to-str p.age))
                describe (Person "Alice" 30)"""));
        }

        @Test void fnBoundaryWithWildcard() {
            // _ in spec annotation skips validation for that position
            assertEquals("42", run("""
                spec Person
                  name :: Str
                  age :: Int
                fn identity :: _ _
                  (x -> x)
                println (identity 42)"""));
        }

        @Test void primitiveTypeAnnotationNoOp() {
            // Int, Str etc. are not in spec registry — no validation (type hints only)
            assertEquals("7", run("""
                fn add :: Int Int Int
                  (x y -> x + y)
                println (add 3 4)"""));
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
                spec Person
                  name :: Str
                  age :: Int
                {name= n age= a} := Person "Jo" 42
                println (n ++ " is " ++ (to-str a))"""));
        }

        @Test void productTypeMatchDestructure() {
            assertEquals("Jo", run("""
                spec Person
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

    // ═══════════════════════════════════════════════════════════════════
    // VarCell / Hot Redefinition
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class VarRedefinition {

        @Test void nameBasedCallSeesRedefinedFn() {
            assertEquals("105", run("""
                fn helper
                  (x -> x + 1)
                fn caller
                  (x -> helper x)
                fn helper
                  (x -> x + 100)
                println (caller 5)"""));
        }

        @Test void topLevelBindRedefVisibleThroughClosure() {
            assertEquals("2", run("""
                x := 1
                fn get-x
                  (-> x)
                x := 2
                println (get-x ())"""));
        }

        @Test void closureCapturedByValueUnaffected() {
            // add-n 10 creates a closure with n=10 baked in.
            // Redefining add-n does NOT affect the existing closure.
            assertEquals("15", run("""
                fn add-n
                  (n -> (x -> n + x))
                adder := add-n 10
                fn add-n
                  (n -> (x -> n * x))
                println (adder 5)"""));
        }

        @Test void localBindingsStillImmutable() {
            // Local bindings inside fn bodies use ImmutableCell, not VarCell.
            // Assignment to them should fail.
            assertRuntimeError("""
                fn f
                  =>
                  x := 42
                  x <- 0
                f ()""");
        }

        @Test void assignToTopLevelVarStillFails() {
            // VarCell supports re-declaration (:=) but NOT assignment (<-)
            assertRuntimeError("x := 42\nx <- 0");
        }

        @Test void typeConstructorRedef() {
            assertEquals("New \"hello\"", run("""
                spec Old
                  Old a
                spec New
                  New a
                println (New "hello")"""));
        }

        @Test void multipleRedefsChain() {
            // g calls f twice: f(f(1)) = f(10) = 100
            assertEquals("100", run("""
                fn f
                  (x -> x + 1)
                fn g
                  (x -> f (f x))
                fn f
                  (x -> x * 10)
                println (g 1)"""));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Concurrency: spawn and sleep
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class Concurrency {

        /** Helper: parse source into AST declarations. */
        private List<Decl> parse(String source) {
            var pr = IrijParseDriver.parse(source + "\n");
            assertFalse(pr.hasErrors(), () -> "Parse errors: " + pr.errors());
            return new AstBuilder().build(pr.tree());
        }

        @Test void sleepDelaysExecution() {
            long start = System.currentTimeMillis();
            run("sleep 100");
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed >= 90, "sleep 100 should delay ~100ms, took " + elapsed + "ms");
        }

        @Test void sleepAcceptsFloat() {
            long start = System.currentTimeMillis();
            run("sleep 0.1");
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed >= 90, "sleep 0.1 (100ms) should delay ~100ms, took " + elapsed + "ms");
        }

        @Test void spawnReturnsThread() {
            var result = eval("spawn (-> 42)");
            assertInstanceOf(Thread.class, result);
        }

        @Test void spawnRunsInBackground() throws Exception {
            var baos = new ByteArrayOutputStream();
            var out = new PrintStream(baos);
            var interp = new Interpreter(out);
            interp.run(parse("spawn (-> sleep 30 ; println \"from-spawn\")"));
            // spawn returns immediately — no output yet
            var immediate = baos.toString();
            assertFalse(immediate.contains("from-spawn"),
                "spawn should not block; output should be empty immediately");
            // Wait for spawned thread to finish
            Thread.sleep(100);
            var after = baos.toString();
            assertTrue(after.contains("from-spawn"),
                "Spawned thread should have printed after sleep");
        }

        @Test void spawnSeesVarCellUpdate() throws Exception {
            var baos = new ByteArrayOutputStream();
            var out = new PrintStream(baos);
            var interp = new Interpreter(out);

            // Define greet, spawn a loop that calls it 3 times with sleeps
            interp.run(parse("""
                fn greet ::: Console
                  => name
                  println name
                """));
            interp.run(parse("""
                spawn (-> greet "v1" ; sleep 150 ; greet "v1" ; sleep 150 ; greet "v1")
                """));

            // Let first call execute
            Thread.sleep(80);

            // Redefine greet — VarCell updates in-place
            interp.run(parse("""
                fn greet ::: Console
                  => name
                  println ("UPDATED-" ++ name)
                """));

            // Wait for remaining calls
            Thread.sleep(500);
            out.flush();
            var output = baos.toString();

            assertTrue(output.contains("v1"),
                "First call should have used original definition");
            assertTrue(output.contains("UPDATED-v1"),
                "Later calls should see the redefined function via VarCell");
        }

        @Test void spawnErrorDoesNotCrash() throws Exception {
            var baos = new ByteArrayOutputStream();
            var out = new PrintStream(baos);
            var interp = new Interpreter(out);

            interp.run(parse("spawn (-> 1 + \"bad\")"));
            Thread.sleep(100);

            var output = baos.toString();
            assertTrue(output.contains("[spawn] error:"),
                "Error in spawned thread should be logged, not crash");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Tilde (apply-to-rest) operator
    // ═══════════════════════════════════════════════════════════════

    @Nested
    class TildeOperator {

        @Test void tildeAppliesFnToRest() {
            assertEquals("hello", run("println ~ \"hello\""));
        }

        @Test void tildeHasLowestPrecedence() {
            // println ~ "a" ++ "b"  →  println ("a" ++ "b")  →  println "ab"
            assertEquals("ab", run("println ~ \"a\" ++ \"b\""));
        }

        @Test void tildeRightAssociative() {
            // identity ~ println ~ "chain"  →  identity (println ("chain"))
            assertEquals("chain", run("identity ~ println ~ \"chain\""));
        }

        @Test void tildeWithArithmetic() {
            // println ~ 2 + 3  →  println (2 + 3)  →  println 5
            assertEquals("5", run("println ~ 2 + 3"));
        }

        @Test void tildeWithComparison() {
            // println ~ 3 > 2  →  println (3 > 2)  →  println true
            assertEquals("true", run("println ~ 3 > 2"));
        }

    }

    // ═══════════════════════════════════════════════════════════════════
    // Phase 3: Algebraic Effects
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class Effects {

        // ── Effect declaration ────────────────────────────────────────

        @Test void effectDeclRegistersOps() {
            // Effect ops become callable functions (error outside handler)
            assertRuntimeError("""
                effect Console
                  print :: Str -> ()
                print "hello"
                """);
        }

        @Test void effectDeclRegistersDescriptor() {
            var result = eval("""
                effect Console
                  print :: Str -> ()
                  read-line :: () -> Str
                Console
                """);
            assertEquals("<effect Console>", result.toString());
        }

        // ── Handler declaration ──────────────────────────────────────

        @Test void handlerDeclCreatesValue() {
            var result = eval("""
                effect Console
                  print :: Str -> ()
                handler test-console :: Console
                  print msg => resume ()
                test-console
                """);
            assertTrue(result.toString().contains("handler test-console"));
        }

        // ── Basic with + resume ──────────────────────────────────────

        @Test void withHandlerRunsBody() {
            assertEquals("42", run("""
                effect Console
                  print :: Str -> ()
                handler test-console :: Console
                  print msg => resume ()
                with test-console
                  println 42
                """));
        }

        @Test void effectOpDispatchesToHandler() {
            // print is the EFFECT op, not the builtin;
            // handler intercepts it and uses println (builtin) to actually output
            assertEquals("handled: hello", run("""
                effect Log
                  log :: Str -> ()
                handler console-log :: Log ::: Console
                  log msg =>
                    println ~ "handled: " ++ msg
                    resume ()
                with console-log
                  log "hello"
                """));
        }

        @Test void resumeSendsValueToBody() {
            // read-line is an effect op; handler resumes with "Alice"
            assertEquals("Hello, Alice!", run("""
                effect Console
                  read-line :: () -> Str
                handler mock-input :: Console
                  read-line () => resume "Alice"
                with mock-input
                  name := read-line ()
                  println ~ "Hello, " ++ name ++ "!"
                """));
        }

        @Test void multipleEffectOps() {
            assertEquals("a\nb", run("""
                effect Log
                  log :: Str -> ()
                handler print-log :: Log ::: Console
                  log msg =>
                    println msg
                    resume ()
                with print-log
                  log "a"
                  log "b"
                """));
        }

        @Test void withBlockReturnsBodyValue() {
            var result = eval("""
                effect Log
                  log :: Str -> ()
                handler silent-log :: Log
                  log msg => resume ()
                with silent-log
                  log "ignored"
                  42
                """);
            assertEquals(42L, result);
        }

        // ── Abort (no resume) ────────────────────────────────────────

        @Test void noResumeAbortsBody() {
            // Handler doesn't call resume → body is aborted,
            // handler arm's return value becomes the with block result
            var result = eval("""
                effect Exn
                  throw :: Str -> ()
                handler catch-all :: Exn
                  throw msg => "caught: " ++ msg
                with catch-all
                  throw "boom"
                  println "should not run"
                  99
                """);
            assertEquals("caught: boom", result);
        }

        @Test void abortPreventsSubsequentStatements() {
            // The println after throw should NOT execute
            assertEquals("", run("""
                effect Exn
                  throw :: Str -> ()
                handler catch-all :: Exn
                  throw msg => "caught"
                with catch-all
                  throw "boom"
                  println "should not appear"
                """));
        }

        // ── Resume with transform ────────────────────────────────────

        @Test void resumeReturnsBodyFinalValue() {
            // After resume, handler arm can observe body's final result
            var result = eval("""
                effect Log
                  log :: Str -> ()
                handler counting-log :: Log
                  log msg =>
                    body-result := resume ()
                    body-result + 1
                with counting-log
                  log "once"
                  41
                """);
            assertEquals(42L, result);
        }

        // ── Nested handlers ──────────────────────────────────────────

        @Test void nestedHandlersSameEffect() {
            // Inner handler takes precedence
            assertEquals("inner: hello", run("""
                effect Log
                  log :: Str -> ()
                handler outer-log :: Log ::: Console
                  log msg =>
                    println ~ "outer: " ++ msg
                    resume ()
                handler inner-log :: Log ::: Console
                  log msg =>
                    println ~ "inner: " ++ msg
                    resume ()
                with outer-log
                  with inner-log
                    log "hello"
                """));
        }

        @Test void nestedHandlersDifferentEffects() {
            assertEquals("hello\n42", run("""
                effect Log
                  log :: Str -> ()
                effect State
                  get-val :: () -> Int
                handler print-log :: Log ::: Console
                  log msg =>
                    println msg
                    resume ()
                handler const-state :: State
                  get-val () => resume 42
                with print-log
                  with const-state
                    log "hello"
                    println ~ get-val ()
                """));
        }

        // ── Unhandled effect ─────────────────────────────────────────

        @Test void unhandledEffectThrows() {
            assertRuntimeError("""
                effect Console
                  print :: Str -> ()
                print "no handler!"
                """);
        }

        // ── One-shot enforcement ─────────────────────────────────────

        @Test void doubleResumeThrows() {
            assertRuntimeError("""
                effect Ask
                  ask :: () -> Int
                handler bad-handler :: Ask
                  ask () =>
                    resume 1
                    resume 2
                with bad-handler
                  ask ()
                """);
        }

        // ── Handler-local state (Phase 3b preview) ──────────────────

        @Test void handlerLocalState() {
            assertEquals("3", run("""
                effect Counter
                  inc :: () -> ()
                  get-count :: () -> Int
                handler counting :: Counter
                  state :! 0
                  inc () =>
                    state <- state + 1
                    resume ()
                  get-count () => resume state
                with counting
                  inc ()
                  inc ()
                  inc ()
                  println ~ get-count ()
                """));
        }

        @Test void mockConsolePattern() {
            // The canonical test from the spec: mock-console collects output
            assertEquals("#[Hello, world! done]", run("""
                effect Console
                  print :: Str -> ()
                  get-output :: () -> ()
                handler mock-console :: Console
                  state :! #[]
                  print msg =>
                    state <- state ++ #[msg]
                    resume ()
                  get-output () => resume state
                with mock-console
                  print "Hello, world!"
                  print "done"
                  println ~ get-output ()
                """));
        }

        // ── on-failure clause ─────────────────────────────────────

        @Test void onFailureHandlesBodyError() {
            assertEquals("recovered", run("""
                effect Log
                  log :: Str -> ()
                handler silent :: Log
                  log msg => resume ()
                with silent
                  x := 1 / 0
                on-failure
                  println "recovered"
                """));
        }

        @Test void onFailureNotTriggeredOnSuccess() {
            assertEquals("ok", run("""
                effect Log
                  log :: Str -> ()
                handler silent :: Log
                  log msg => resume ()
                with silent
                  log "hello"
                  println "ok"
                on-failure
                  println "should not run"
                """));
        }

        @Test void onFailureBindsErrorMessage() {
            var result = eval("""
                effect Log
                  log :: Str -> ()
                handler silent :: Log
                  log msg => resume ()
                with silent
                  x := 1 / 0
                on-failure
                  error
                """);
            assertTrue(result.toString().contains("Division by zero"));
        }

        // ── Handler composition (>>) ──────────────────────────────

        @Test void handlerCompositionTwoEffects() {
            assertEquals("logged: hello\n42", run("""
                effect Log
                  log :: Str -> ()
                effect State
                  get-val :: () -> Int
                handler print-log :: Log ::: Console
                  log msg =>
                    println ~ "logged: " ++ msg
                    resume ()
                handler const-state :: State
                  get-val () => resume 42
                with (print-log >> const-state)
                  log "hello"
                  println ~ get-val ()
                """));
        }

        @Test void handlerCompositionThreeHandlers() {
            assertEquals("a\nb\n10", run("""
                effect A
                  get-a :: () -> Str
                effect B
                  get-b :: () -> Str
                effect C
                  get-c :: () -> Int
                handler ha :: A
                  get-a () => resume "a"
                handler hb :: B
                  get-b () => resume "b"
                handler hc :: C
                  get-c () => resume 10
                with (ha >> hb >> hc)
                  println ~ get-a ()
                  println ~ get-b ()
                  println ~ get-c ()
                """));
        }

        // ── Handler dot-access ────────────────────────────────────

        @Test void handlerDotAccessReadsState() {
            var result = eval("""
                effect Counter
                  inc :: () -> ()
                handler counting :: Counter
                  state :! 0
                  inc () =>
                    state <- state + 1
                    resume ()
                with counting
                  inc ()
                  inc ()
                  inc ()
                counting.state
                """);
            assertEquals(3L, result);
        }

        @Test void handlerDotAccessNoField() {
            assertRuntimeError("""
                effect Log
                  log :: Str -> ()
                handler h :: Log
                  log msg => resume ()
                h.nonexistent
                """);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // New Builtins (Phase 4)
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class NewBuiltins {
        // ── Error & type-of ─────────────────────────────────────────
        @Test void errorThrows() {
            assertRuntimeError("error \"boom\"");
        }

        @Test void typeOfInt() {
            assertEquals("Int", run("println ~ type-of 42"));
        }

        @Test void typeOfStr() {
            assertEquals("Str", run("println ~ type-of \"hello\""));
        }

        @Test void typeOfVector() {
            assertEquals("Vector", run("println ~ type-of #[1 2 3]"));
        }

        // ── Map operations ──────────────────────────────────────────
        @Test void assocAddsKey() {
            assertEquals("{x= 1 y= 2}", run("println ~ assoc {x= 1} \"y\" 2"));
        }

        @Test void assocOverrides() {
            assertEquals("{x= 99}", run("println ~ assoc {x= 1} \"x\" 99"));
        }

        @Test void dissocRemovesKey() {
            assertEquals("{x= 1}", run("println ~ dissoc {x= 1 y= 2} \"y\""));
        }

        @Test void mergeMaps() {
            assertEquals("{x= 1 y= 2}", run("println ~ merge {x= 1} {y= 2}"));
        }

        @Test void mergeOverrides() {
            assertEquals("{x= 99}", run("println ~ merge {x= 1} {x= 99}"));
        }

        // ── String operations ───────────────────────────────────────
        @Test void splitString() {
            assertEquals("#[a b c]", run("println ~ split \"a,b,c\" \",\""));
        }

        @Test void splitIntoChars() {
            assertEquals("#[h i]", run("println ~ split \"hi\" \"\""));
        }

        @Test void joinStrings() {
            assertEquals("a-b-c", run("println ~ join \"-\" #[\"a\" \"b\" \"c\"]"));
        }

        @Test void trimString() {
            assertEquals("hello", run("println ~ trim \"  hello  \""));
        }

        @Test void upperCase() {
            assertEquals("HELLO", run("println ~ upper-case \"hello\""));
        }

        @Test void lowerCase() {
            assertEquals("hello", run("println ~ lower-case \"HELLO\""));
        }

        @Test void startsWithTrue() {
            assertEquals("true", run("println ~ starts-with? \"hello\" \"hel\""));
        }

        @Test void endsWithFalse() {
            assertEquals("false", run("println ~ ends-with? \"hello\" \"world\""));
        }

        @Test void replaceString() {
            assertEquals("hellX", run("println ~ replace \"hello\" \"o\" \"X\""));
        }

        @Test void substringExtract() {
            assertEquals("ell", run("println ~ substring \"hello\" 1 4"));
        }

        @Test void charAtIndex() {
            assertEquals("e", run("println ~ char-at \"hello\" 1"));
        }

        @Test void indexOfFound() {
            assertEquals("2", run("println ~ index-of \"hello\" \"ll\""));
        }

        @Test void indexOfNotFound() {
            assertEquals("-1", run("println ~ index-of \"hello\" \"xyz\""));
        }

        // ── Math operations ─────────────────────────────────────────
        @Test void sqrtOf16() {
            assertEquals("4.0", run("println ~ sqrt 16"));
        }

        @Test void floorValue() {
            assertEquals("3", run("println ~ floor 3.7"));
        }

        @Test void ceilValue() {
            assertEquals("4", run("println ~ ceil 3.2"));
        }

        @Test void roundValue() {
            assertEquals("4", run("println ~ round 3.5"));
        }

        @Test void powFunction() {
            assertEquals("8.0", run("println ~ pow 2 3"));
        }

        @Test void sinZero() {
            assertEquals("0.0", run("println ~ sin 0"));
        }

        // ── Conversion ──────────────────────────────────────────────
        @Test void parseIntValid() {
            assertEquals("42", run("println ~ parse-int \"42\""));
        }

        @Test void parseIntInvalid() {
            assertRuntimeError("parse-int \"abc\"");
        }

        @Test void parseFloatValid() {
            assertEquals("3.14", run("println ~ parse-float \"3.14\""));
        }

        @Test void charCodeAndBack() {
            assertEquals("65", run("println ~ char-code \"A\""));
        }

        @Test void fromCharCode() {
            assertEquals("A", run("println ~ from-char-code 65"));
        }

        // ── IO ──────────────────────────────────────────────────────
        @Test void nowMsReturnsNumber() {
            var result = eval("now-ms ()");
            assertInstanceOf(Long.class, result);
            assertTrue((Long) result > 0);
        }

        @Test void getEnvPath() {
            // PATH should always exist on any system
            var result = eval("get-env \"PATH\"");
            assertInstanceOf(String.class, result);
        }

        @Test void getEnvMissing() {
            var result = eval("get-env \"IRIJ_NONEXISTENT_VAR_12345\"");
            assertEquals(Values.UNIT, result);
        }

        @Test void readWriteFileRoundTrip() {
            var tmpFile = System.getProperty("java.io.tmpdir") + "/irij-test-" + System.nanoTime() + ".txt";
            run("write-file \"" + tmpFile + "\" \"hello irij\"");
            assertEquals("hello irij", run("println ~ read-file \"" + tmpFile + "\""));
            // cleanup
            new java.io.File(tmpFile).delete();
        }

        @Test void fileExistsCheck() {
            assertEquals("false", run("println ~ file-exists? \"/nonexistent/path/xyz\""));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Module System (Phase 4)
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class Modules {
        @Test void useStdMathQualified() {
            assertEquals("4.0", run("""
                use std.math
                println ~ math.sqrt 16
                """));
        }

        @Test void useStdMathSelective() {
            assertEquals("4.0", run("""
                use std.math {sqrt pow}
                println ~ sqrt 16
                """));
        }

        @Test void useStdTextOpen() {
            assertEquals("hello", run("""
                use std.text :open
                println ~ trim "  hello  "
                """));
        }

        @Test void moduleNotFound() {
            assertRuntimeError("use nonexistent.module");
        }

        @Test void selectiveImportMissing() {
            assertRuntimeError("""
                use std.math {nonexistent-fn}
                """);
        }

        @Test void stdTextChars() {
            assertEquals("#[h i]", run("""
                use std.text :open
                println ~ chars "hi"
                """));
        }

        @Test void stdTextWords() {
            assertEquals("#[hello world]", run("""
                use std.text :open
                println ~ words "hello world"
                """));
        }

        @Test void stdTextBlank() {
            assertEquals("true", run("""
                use std.text :open
                println ~ blank? "  "
                """));
        }

        @Test void stdTextRepeat() {
            assertEquals("abcabcabc", run("""
                use std.text :open
                println ~ repeat 3 "abc"
                """));
        }

        @Test void stdMathEven() {
            assertEquals("true", run("""
                use std.math :open
                println ~ even? 4
                """));
        }

        @Test void stdMathOdd() {
            assertEquals("true", run("""
                use std.math :open
                println ~ odd? 3
                """));
        }

        @Test void stdMathGcd() {
            assertEquals("6", run("""
                use std.math :open
                println ~ gcd 12 18
                """));
        }

        @Test void stdMathClamp() {
            assertEquals("10", run("""
                use std.math :open
                println ~ clamp 0 10 15
                """));
        }

        @Test void stdCollectionZip() {
            assertEquals("#[#(1 :a) #(2 :b)]", run("""
                use std.collection :open
                println ~ zip #[1 2 3] #[:a :b]
                """));
        }

        @Test void stdCollectionEnumerate() {
            assertEquals("#[#(0 a) #(1 b) #(2 c)]", run("""
                use std.collection :open
                println ~ enumerate #["a" "b" "c"]
                """));
        }

        @Test void stdCollectionFlatten() {
            assertEquals("#[1 2 3 4]", run("""
                use std.collection :open
                println ~ flatten #[#[1 2] #[3 4]]
                """));
        }

        @Test void stdCollectionDistinct() {
            assertEquals("#[1 2 3]", run("""
                use std.collection :open
                println ~ distinct #[1 2 1 3 2]
                """));
        }

        @Test void stdCollectionAllAny() {
            assertEquals("true\nfalse", run("""
                use std.collection :open
                println ~ all? (x -> x > 0) #[1 2 3]
                println ~ any? (x -> x > 5) #[1 2 3]
                """));
        }

        @Test void stdCollectionGroupBy() {
            assertEquals("2", run("""
                use std.collection :open
                result := group-by (x -> x % 2) #[1 2 3 4 5]
                println ~ length (keys result)
                """));
        }

        @Test void stdCollectionPartition() {
            assertEquals("#[#[1 2] #[3 4] #[5]]", run("""
                use std.collection :open
                println ~ partition 2 #[1 2 3 4 5]
                """));
        }

        @Test void stdFnFlip() {
            assertEquals("1", run("""
                use std.func :open
                println ~ flip (-) 3 4
                """));
        }

        @Test void stdFnCompose() {
            assertEquals("6", run("""
                use std.func :open
                double := (x -> x * 2)
                inc := (x -> x + 1)
                f := compose double inc
                println ~ f 2
                """));
        }

        @Test void stdCollectionSortBy() {
            assertEquals("#[c bb aaa]", run("""
                use std.collection :open
                println ~ sort-by length #["aaa" "c" "bb"]
                """));
        }

        @Test void stdCollectionMapVals() {
            assertEquals("{x= 2 y= 4}", run("""
                use std.collection :open
                println ~ map-vals (x -> x * 2) {x= 1 y= 2}
                """));
        }

        @Test void stdCollectionEach() {
            assertEquals("1\n2\n3", run("""
                use std.collection :open
                fn print-each ::: Console
                  => vec
                  fold (_ x -> println x) () vec
                  ()
                print-each #[1 2 3]
                """));
        }

        @Test void moduleLoadCached() {
            // Loading same module twice should work (cached)
            assertEquals("hello\nhello", run("""
                use std.text :open
                println ~ trim "  hello  "
                use std.text :open
                println ~ trim "  hello  "
                """));
        }

        @Test void qualifiedAndDotAccess() {
            assertEquals("3.0", run("""
                use std.math
                println ~ math.sqrt 9
                """));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Destructuring Bindings (Phase 4.5a)
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class DestructuringBindings {

        @Test void vectorDestructure() {
            assertEquals("1 2 3", run("""
                #[a b c] := #[1 2 3]
                println ~ (to-str a) ++ " " ++ (to-str b) ++ " " ++ (to-str c)
                """));
        }

        @Test void vectorDestructureWithSpread() {
            assertEquals("10 #[20 30 40]", run("""
                #[first ...rest] := #[10 20 30 40]
                println ~ (to-str first) ++ " " ++ (to-str rest)
                """));
        }

        @Test void tupleDestructure() {
            assertEquals("42 hello", run("""
                #(x y) := #(42 "hello")
                println ~ (to-str x) ++ " " ++ y
                """));
        }

        @Test void nestedDestructure() {
            assertEquals(":name Jo", run("""
                #[#(k v) _] := #[#(:name "Jo") #(:age 30)]
                println ~ (to-str k) ++ " " ++ v
                """));
        }

        @Test void destructureBindFails() {
            assertThrows(IrijRuntimeError.class, () -> run("""
                #[a b c] := #[1 2]
                """));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Implicit Continuation (Phase 4.5a)
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class ImplicitContinuation {

        @Test void pipelineContinuation() {
            assertEquals("220", run("""
                result := #[1 2 3 4 5 6 7 8 9 10]
                  |> /? (n -> n % 2 == 0)
                  |> @ (n -> n * n)
                  |> /+
                println (to-str result)
                """));
        }

        @Test void concatContinuation() {
            assertEquals("hello world", run("""
                msg := "hello"
                  ++ " "
                  ++ "world"
                println msg
                """));
        }

        @Test void arithmeticContinuation() {
            assertEquals("10", run("""
                total := 1
                  + 2
                  + 3
                  + 4
                println (to-str total)
                """));
        }

        @Test void booleanContinuation() {
            assertEquals("true", run("""
                check := true
                  && true
                  && (1 < 2)
                println (to-str check)
                """));
        }

        @Test void comparisonContinuation() {
            assertEquals("true", run("""
                result := 42
                  == 42
                println (to-str result)
                """));
        }

        @Test void rangeContinuation() {
            assertEquals("#[1 2 3 4 5]", run("""
                r := 1
                  .. 5
                println (to-str (to-vec r))
                """));
        }

        @Test void continuationInsideFnBody() {
            assertEquals("90", run("""
                fn process
                  => xs
                  xs
                    |> /? (x -> x > 3)
                    |> @ (x -> x * 10)
                    |> /+
                println (to-str (process #[1 2 3 4 5]))
                """));
        }

        @Test void normalIndentStillWorks() {
            assertEquals("positive", run("""
                x := 5
                if (x > 0)
                  println "positive"
                """));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Protocols & Implementations
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class Protocols {

        @Test void basicProtoAndImpl() {
            assertEquals("hello", run("""
                proto Show a
                  show :: a -> Str

                impl Show for Str
                  show := (s -> s)

                println (show "hello")
                """));
        }

        @Test void protoDispatchOnInt() {
            assertEquals("42", run("""
                proto Stringify a
                  stringify :: a -> Str

                impl Stringify for Int
                  stringify := (n -> to-str n)

                println (stringify 42)
                """));
        }

        @Test void protoMultipleMethods() {
            assertEquals("0\nhi hi", run("""
                proto Monoid a
                  empty :: a
                  append :: a -> a -> a

                impl Monoid for Str
                  empty := ""
                  append := (a b -> a ++ " " ++ b)

                impl Monoid for Int
                  empty := 0
                  append := (+)

                println (empty 999)
                println (append "hi" "hi")
                """));
        }

        @Test void protoDispatchByType() {
            assertEquals("10\nhi world", run("""
                proto Combine a
                  combine :: a -> a -> a

                impl Combine for Int
                  combine := (+)

                impl Combine for Str
                  combine := (a b -> a ++ " " ++ b)

                println (combine 3 7)
                println (combine "hi" "world")
                """));
        }

        @Test void protoWithTaggedType() {
            assertEquals("Point(1, 2)", run("""
                spec Point
                  Point Int Int

                proto Displayable a
                  display :: a -> Str

                fn fmt-point
                  (Point x y) => "Point(" ++ to-str x ++ ", " ++ to-str y ++ ")"

                impl Displayable for Point
                  display := fmt-point

                println (display (Point 1 2))
                """));
        }

        @Test void protoWithProductType() {
            assertEquals("Person: Alice age 30", run("""
                spec Person
                  name :: Str
                  age :: Int

                proto Describe a
                  describe :: a -> Str

                impl Describe for Person
                  describe := (p -> "Person: " ++ p.name ++ " age " ++ to-str p.age)

                println (describe (Person "Alice" 30))
                """));
        }

        @Test void protoErrorNoImpl() {
            assertRuntimeError("""
                proto Foo a
                  foo :: a -> Str

                foo 42
                """);
        }

        @Test void protoErrorUnknownProtocol() {
            assertRuntimeError("""
                impl Nonexistent for Int
                  bar := (x -> x)
                """);
        }

        @Test void protoMethodAsValue() {
            // show with no args just returns the dispatch function
            assertEquals("<builtin show>", run("""
                proto Show a
                  show :: a -> Str

                impl Show for Int
                  show := (n -> to-str n)

                println show
                """));
        }

        @Test void protoEmptyMethodDispatch() {
            // empty is a zero-arg concept but dispatch still needs the type hint
            // In practice the user passes a "seed" value for type dispatch
            assertEquals("0", run("""
                proto Monoid a
                  empty :: a
                  append :: a -> a -> a

                impl Monoid for Int
                  empty := 0
                  append := (+)

                println (empty 999)
                """));
        }

        @Test void multipleProtos() {
            assertEquals("42\ntrue", run("""
                proto Show a
                  show :: a -> Str

                proto Eq a
                  eq :: a -> a -> Bool

                impl Show for Int
                  show := (n -> to-str n)

                impl Eq for Int
                  eq := (a b -> a == b)

                println (show 42)
                println (eq 10 10)
                """));
        }

        @Test void protoLambdaImpl() {
            assertEquals("6", run("""
                proto Doubler a
                  double-it :: a -> a

                impl Doubler for Int
                  double-it := (x -> x * 2)

                println (double-it 3)
                """));
        }

        @Test void protoWithOperatorSection() {
            assertEquals("30", run("""
                proto Combine a
                  combine :: a -> a -> a

                impl Combine for Int
                  combine := (+)

                println (combine 10 20)
                """));
        }

        @Test void protoWithFnRef() {
            assertEquals("HELLO", run("""
                proto Transform a
                  transform :: a -> a

                impl Transform for Str
                  transform := upper-case

                println (transform "hello")
                """));
        }

        @Test void protoOverridePerType() {
            // Second impl for same type replaces the first
            assertEquals("200", run("""
                proto Process a
                  process :: a -> a

                impl Process for Int
                  process := (x -> x + 1)

                impl Process for Int
                  process := (x -> x * 2)

                println (process 100)
                """));
        }

        @Test void protoDispatchOnFloat() {
            assertEquals("3.14", run("""
                proto Stringify a
                  stringify :: a -> Str

                impl Stringify for Float
                  stringify := (x -> to-str x)

                println (stringify 3.14)
                """));
        }

        @Test void protoDispatchOnBool() {
            assertEquals("yes", run("""
                proto Label a
                  label :: a -> Str

                impl Label for Bool
                  label := (b -> if b "yes" else "no")

                println (label true)
                """));
        }

        @Test void protoDispatchOnKeyword() {
            assertEquals(":ok", run("""
                proto Stringify a
                  stringify :: a -> Str

                impl Stringify for Keyword
                  stringify := (k -> to-str k)

                println (stringify :ok)
                """));
        }

        @Test void protoDispatchOnVector() {
            assertEquals("3", run("""
                proto Size a
                  size :: a -> Int

                impl Size for Vector
                  size := (v -> length v)

                println (size #[1 2 3])
                """));
        }

        @Test void protoDispatchOnMap() {
            assertEquals("2", run("""
                proto Size a
                  size :: a -> Int

                impl Size for Map
                  size := (m -> length (keys m))

                println (size {a= 1 b= 2})
                """));
        }

        @Test void protoInPipeline() {
            assertEquals("10", run("""
                proto Double a
                  dbl :: a -> a

                impl Double for Int
                  dbl := (x -> x * 2)

                println (5 |> dbl)
                """));
        }

        @Test void protoMethodInMap() {
            assertEquals("#[2 4 6]", run("""
                proto Double a
                  dbl :: a -> a

                impl Double for Int
                  dbl := (x -> x * 2)

                println (#[1 2 3] |> @ dbl)
                """));
        }

        @Test void implExtraMethodError() {
            // impl binding not declared in protocol should error
            assertRuntimeError("""
                proto Show a
                  show :: a -> Str

                impl Show for Int
                  show := (n -> to-str n)
                  extra := 42
                """);
        }

        @Test void protoRedefinitionOverwrites() {
            // Redefining a proto replaces the old one
            assertEquals("42", run("""
                proto P a
                  foo :: a -> Int

                impl P for Str
                  foo := (s -> 0)

                proto P a
                  foo :: a -> Int

                impl P for Str
                  foo := (s -> 42)

                println (foo "x")
                """));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Structured Concurrency
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class StructuredConcurrency {

        @Test void scopeBasicNoForks() {
            assertEquals("42", run("""
                scope s
                  println 42
                """));
        }

        @Test void scopeForkAndAwait() {
            assertEquals("30", run("""
                scope s
                  f := s.fork (-> 10 + 20)
                  println (await f)
                """));
        }

        @Test void scopeMultipleForks() {
            assertEquals("30\n21", run("""
                scope s
                  f1 := s.fork (-> 10 + 20)
                  f2 := s.fork (-> 3 * 7)
                  println (await f1)
                  println (await f2)
                """));
        }

        @Test void scopeWaitsForAllFibers() {
            // Even without explicit await, scope waits for all forks
            assertEquals("from fiber", run("""
                scope s
                  s.fork (-> sleep 10; println "from fiber")
                """));
        }

        @Test void scopePropagatesError() {
            assertRuntimeError("""
                scope s
                  s.fork (-> error "boom")
                """);
        }

        @Test void scopeRaceFirstWins() {
            assertEquals("fast", run("""
                result := race (-> sleep 200; "slow") (-> sleep 10; "fast")
                println result
                """));
        }

        @Test void scopeRaceBlock() {
            // scope.race block returns first fiber success
            // We verify the fast fiber finishes first by printing inside it
            assertEquals("fast", run("""
                scope.race s
                  s.fork (-> sleep 200; "slow")
                  s.fork (-> sleep 10; println "fast")
                """));
        }

        @Test void scopeSupervisedIsolatesErrors() {
            // supervised: one fiber failing doesn't cancel others
            assertEquals("[supervised] fiber error: boom\nok", run("""
                scope.supervised s
                  s.fork (-> error "boom")
                  s.fork (-> sleep 30; println "ok")
                """));
        }

        @Test void awaitReturnsResult() {
            assertEquals("42", run("""
                scope s
                  f := s.fork (-> 42)
                  println (await f)
                """));
        }

        @Test void awaitPropagatesError() {
            assertRuntimeError("""
                scope s
                  f := s.fork (-> error "fiber failed")
                  await f
                """);
        }

        @Test void timeoutReturnsResult() {
            assertEquals("42", run("""
                result := timeout 1000 (-> 42)
                println result
                """));
        }

        @Test void timeoutThrowsOnExpiry() {
            assertRuntimeError("""
                timeout 10 (-> sleep 5000; 42)
                """);
        }

        @Test void parCombinesResults() {
            assertEquals("30", run("""
                result := par (+) (-> 10) (-> 20)
                println result
                """));
        }

        @Test void parThreeThunks() {
            assertEquals("#[1 2 3]", run("""
                fn collect
                  => a b c
                  #[a b c]
                result := par collect (-> 1) (-> 2) (-> 3)
                println result
                """));
        }

        @Test void parPropagatesError() {
            assertRuntimeError("""
                par (+) (-> 10) (-> error "fail")
                """);
        }

        @Test void raceStandalone() {
            assertEquals("fast", run("""
                result := race (-> sleep 200; "slow") (-> "fast")
                println result
                """));
        }

        @Test void raceAllFail() {
            assertRuntimeError("""
                race (-> error "a") (-> error "b")
                """);
        }

        @Test void nestedScopes() {
            assertEquals("inner\nouter", run("""
                fn inner-work ::: Console
                  => x
                  scope s
                    s.fork (-> println "inner")
                scope outer
                  outer.fork (-> inner-work 1)
                  sleep 50
                  println "outer"
                """));
        }

        @Test void fiberTypeName() {
            assertEquals("Fiber", run("""
                scope s
                  f := s.fork (-> 42)
                  println (type-of f)
                  await f
                """));
        }

        @Test void scopeHandleTypeName() {
            assertEquals("Scope", run("""
                scope s
                  println (type-of s)
                """));
        }

        @Test void timeoutWithFloatDuration() {
            assertEquals("ok", run("""
                result := timeout 1.0 (-> "ok")
                println result
                """));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Contracts (pre/post)
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class Contracts {

        @Test void prePassing() {
            assertEquals("10", run("""
                fn positive
                  pre (x -> x > 0)
                  (x -> x * 2)
                println (positive 5)
                """));
        }

        @Test void preFailing() {
            assertRuntimeError("""
                fn positive
                  pre (x -> x > 0)
                  (x -> x * 2)
                positive (0 - 3)
                """);
        }

        @Test void preBlameMessage() {
            assertEquals("true", run("""
                fn positive
                  pre (x -> x > 0)
                  (x -> x)
                result := try (-> positive 0)
                match result
                  (Err msg) => println (index-of msg "caller" /= -1)
                """));
        }

        @Test void postPassing() {
            assertEquals("5", run("""
                fn half
                  post (r -> r >= 0)
                  (x -> div x 2)
                println (half 10)
                """));
        }

        @Test void postFailing() {
            assertRuntimeError("""
                fn only-positive
                  post (r -> r > 0)
                  (x -> x)
                only-positive (0 - 5)
                """);
        }

        @Test void postBlameMessage() {
            assertEquals("true", run("""
                fn only-positive
                  post (r -> r > 0)
                  (x -> x)
                result := try (-> only-positive (0 - 1))
                match result
                  (Err msg) => println (index-of msg "implementation" /= -1)
                """));
        }

        @Test void preAndPostTogether() {
            assertEquals("70", run("""
                fn withdraw
                  pre  (account amt -> amt > 0)
                  pre  (account amt -> account.balance >= amt)
                  post (result -> result.balance >= 0)
                  => account amount
                  {...account balance= account.balance - amount}
                result := withdraw {balance= 100} 30
                println result.balance
                """));
        }

        @Test void multiplePresCombined() {
            // All pre-conditions must pass (AND semantics)
            assertRuntimeError("""
                fn bounded
                  pre (x -> x > 0)
                  pre (x -> x < 100)
                  (x -> x)
                bounded 200
                """);
        }

        @Test void contractOnImperativeFn() {
            assertEquals("6", run("""
                fn double-positive
                  pre (x -> x > 0)
                  post (r -> r > 0)
                  => x
                  x * 2
                println (double-positive 3)
                """));
        }

        @Test void contractOnMatchFn() {
            assertEquals("5\n3", run("""
                fn abs-val
                  post (r -> r >= 0)
                  x | x >= 0 => x
                  x => 0 - x
                println (abs-val 5)
                println (abs-val (0 - 3))
                """));
        }

        @Test void contractWithPartialApplication() {
            // Contracts deferred until all args provided
            assertEquals("8", run("""
                fn add-positive
                  pre (a b -> a > 0 && b > 0)
                  (a b -> a + b)
                add5 := add-positive 5
                println (add5 3)
                """));
        }

        @Test void contractPartialThenFail() {
            assertRuntimeError("""
                fn add-positive
                  pre (a b -> a > 0 && b > 0)
                  (a b -> a + b)
                add5 := add-positive 5
                add5 (0 - 1)
                """);
        }

        @Test void contractInPipeline() {
            assertEquals("20", run("""
                fn double-positive
                  pre (x -> x > 0)
                  (x -> x * 2)
                println (10 |> double-positive)
                """));
        }

        @Test void contractPipelineFails() {
            assertRuntimeError("""
                fn double-positive
                  pre (x -> x > 0)
                  (x -> x * 2)
                (0 - 5) |> double-positive
                """);
        }

        @Test void noContractFnUnaffected() {
            // Functions without contracts work as before
            assertEquals("6", run("""
                fn double
                  (x -> x * 2)
                println (double 3)
                """));
        }

        // ── Phase 6b: in/out module-boundary contracts ──────────────

        @Test void inContractPassing() {
            assertEquals("10", run("""
                fn safe-double
                  in (x -> x > 0)
                  (x -> x * 2)
                println (safe-double 5)
                """));
        }

        @Test void inContractFailing() {
            var output = run("""
                fn safe-double
                  in (x -> x > 0)
                  (x -> x * 2)
                println (try (-> safe-double 0))
                """);
            assertTrue(output.contains("Err"));
            assertTrue(output.contains("caller"));
        }

        @Test void outContractPassing() {
            assertEquals("5", run("""
                fn positive-fn
                  out (r -> r > 0)
                  (x -> x + 1)
                println (positive-fn 4)
                """));
        }

        @Test void outContractFailing() {
            var output = run("""
                fn positive-fn
                  out (r -> r > 0)
                  (x -> 0 - x)
                println (try (-> positive-fn 5))
                """);
            assertTrue(output.contains("Err"));
            assertTrue(output.contains("output contract") || output.contains("Output contract"));
        }

        @Test void inAndOutTogether() {
            assertEquals("10", run("""
                fn safe-fn
                  in (x -> x > 0)
                  out (r -> r > 0)
                  (x -> x * 2)
                println (safe-fn 5)
                """));
        }

        @Test void inOutWithPrePost() {
            // in/out and pre/post can coexist
            assertEquals("10", run("""
                fn safe-fn
                  pre (x -> x > 0)
                  in (x -> x < 100)
                  post (r -> r > 0)
                  out (r -> r < 200)
                  (x -> x * 2)
                println (safe-fn 5)
                """));
        }

        @Test void inContractBlameMessage() {
            var output = run("""
                fn api-endpoint
                  in (r -> r > 0)
                  (r -> r * 2)
                println (try (-> api-endpoint 0))
                """);
            assertTrue(output.contains("caller"));
            assertTrue(output.contains("input contract") || output.contains("Input contract"));
        }

        @Test void outContractBlameMessage() {
            var output = run("""
                fn api-endpoint
                  out (r -> r > 0)
                  (x -> 0 - x)
                println (try (-> api-endpoint 5))
                """);
            assertTrue(output.contains("output contract") || output.contains("Output contract"));
        }

        @Test void inOnImperativeFn() {
            assertEquals("15", run("""
                fn safe-add
                  in (a b -> a > 0 && b > 0)
                  => a b
                  result := a + b
                  result
                println (safe-add 5 10)
                """));
        }

        @Test void inOnMatchFn() {
            assertEquals("1", run("""
                fn safe-head
                  in (xs -> length xs > 0)
                  #[x ...rest] => x
                println (safe-head #[1 2 3])
                """));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Phase 6c — Law Verification
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class LawVerification {

        @Test void protoLawAllPass() {
            var output = run("""
                proto Monoid a
                  empty :: a
                  append :: a -> a -> a
                  law identity-left = forall x. append empty x == x
                  law identity-right = forall x. append x empty == x

                impl Monoid for Int
                  empty := 0
                  append := (+)

                verify-laws Monoid
                """);
            assertTrue(output.contains("PASS"));
            assertTrue(output.contains("identity-left"));
            assertTrue(output.contains("identity-right"));
            assertFalse(output.contains("FAIL"));
        }

        @Test void protoLawFailing() {
            var output = run("""
                proto BadMonoid a
                  empty :: a
                  combine :: a -> a -> a
                  law commutative = forall x y. combine x y == combine y x

                impl BadMonoid for Str
                  empty := ""
                  combine := (++)

                verify-laws BadMonoid
                """);
            assertTrue(output.contains("FAIL"));
            assertTrue(output.contains("commutative"));
            assertTrue(output.contains("with"));
        }

        @Test void protoLawMultipleTypes() {
            var output = run("""
                proto Monoid a
                  empty :: a
                  append :: a -> a -> a
                  law identity-left = forall x. append empty x == x

                impl Monoid for Int
                  empty := 0
                  append := (+)

                impl Monoid for Str
                  empty := ""
                  append := (++)

                verify-laws Monoid
                """);
            assertTrue(output.contains("Monoid/Int"));
            assertTrue(output.contains("Monoid/Str"));
        }

        @Test void protoNoLaws() {
            var output = run("""
                proto Show a
                  show :: a -> a

                verify-laws Show
                """);
            assertTrue(output.contains("no laws"));
        }

        @Test void fnLevelLaw() {
            var output = run("""
                fn double-it
                  law doubles = forall x. double-it x == x + x
                  (x -> x * 2)

                verify-laws "double-it"
                """);
            assertTrue(output.contains("PASS"));
            assertTrue(output.contains("doubles"));
        }

        @Test void fnLevelLawFailing() {
            var output = run("""
                fn buggy
                  law always-positive = forall x. buggy x > 0
                  (x -> x)

                verify-laws "buggy"
                """);
            assertTrue(output.contains("FAIL"));
            assertTrue(output.contains("always-positive"));
        }

        @Test void verifyLawsReturnsVector() {
            var output = run("""
                proto Eq a
                  eq :: a -> a -> a
                  law reflexive = forall x. eq x x == true

                impl Eq for Int
                  eq := (==)

                results := verify-laws Eq
                println (type-of results)
                """);
            assertTrue(output.contains("Vector"));
        }

        @Test void protoLawAssociativity() {
            var output = run("""
                proto Monoid a
                  empty :: a
                  append :: a -> a -> a
                  law assoc = forall x y z. append (append x y) z == append x (append y z)

                impl Monoid for Int
                  empty := 0
                  append := (+)

                verify-laws Monoid
                """);
            assertTrue(output.contains("PASS"));
            assertTrue(output.contains("assoc"));
        }

        @Test void customTrialCount() {
            var output = run("""
                proto Monoid a
                  empty :: a
                  append :: a -> a -> a
                  law identity = forall x. append empty x == x

                impl Monoid for Int
                  empty := 0
                  append := (+)

                verify-laws Monoid 10
                """);
            assertTrue(output.contains("10 trials"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Apply builtin + Rest params
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class ApplyAndRestParams {

        // ── apply builtin ──────────────────────────────────────────────

        @Test void applySpreadVector() {
            assertEquals(6L, eval("apply (+) #[2 4]"));
        }

        @Test void applyWithLeadingArgs() {
            // apply fn arg1 vec → fn(arg1, ...vec)
            var result = run("""
                fn my-fn ::: Console
                  => a b c
                  println (to-str a ++ " " ++ to-str b ++ " " ++ to-str c)
                apply my-fn 1 #[2 3]
                """);
            assertEquals("1 2 3", result);
        }

        @Test void applyWithEmptyVector() {
            assertEquals(42L, eval("apply (x -> x) #[42]"));
        }

        @Test void applyOnBuiltin() {
            assertEquals("hello world", eval("apply concat #[\"hello \" \"world\"]"));
        }

        @Test void applyTooFewArgs() {
            assertRuntimeError("apply (+)");
        }

        @Test void applyWithPartialAndSpread() {
            // apply (f a1 ...rest) where rest needs spreading
            assertEquals(10L, eval("apply (+) #[3 7]"));
        }

        // ── rest params in lambdas ─────────────────────────────────────

        @Test void lambdaRestParamBasic() {
            var result = eval("(x ...rest -> rest) 1 2 3");
            assertEquals(new Values.IrijVector(List.of(2L, 3L)), result);
        }

        @Test void lambdaRestParamEmpty() {
            var result = eval("(x ...rest -> rest) 42");
            assertEquals(new Values.IrijVector(List.of()), result);
        }

        @Test void lambdaRestParamNoFixed() {
            var result = eval("(...args -> length args) 1 2 3 4 5");
            assertEquals(5L, result);
        }

        @Test void lambdaRestParamWithApply() {
            // Combine rest params with apply
            var result = eval("""
                (f ...args -> apply f args) (+) 3 7
                """);
            assertEquals(10L, result);
        }

        // ── rest params in fn declarations (lambda body) ───────────────

        @Test void fnDeclLambdaRestParam() {
            var result = run("""
                fn variadic ::: Console
                  (first ...rest -> println (to-str first ++ " got " ++ to-str (length rest) ++ " more"))
                variadic 1 2 3 4
                """);
            assertEquals("1 got 3 more", result);
        }

        // ── rest params in imperative fn ────────────────────────────────

        @Test void imperativeFnRestParam() {
            var result = run("""
                fn sum-all
                  => ...nums
                  /+ nums
                println (sum-all 1 2 3 4 5)
                """);
            assertEquals("15", result);
        }

        @Test void imperativeFnRestParamWithFixed() {
            var result = run("""
                fn log-msg ::: Console
                  => level ...parts
                  msg := join " " parts
                  println (to-str level ++ ": " ++ msg)
                log-msg "INFO" "hello" "world"
                """);
            assertEquals("INFO: hello world", result);
        }

        // ── par with apply + rest params ───────────────────────────────

        @Test void parWithDynamicThunks() {
            // Build a vector of thunks, use apply to pass them to par
            var result = eval("""
                thunks := #[(-> 1) (-> 2) (-> 3)]
                apply par (x y z -> x + y + z) thunks
                """);
            assertEquals(6L, result);
        }

        // ── rest params forwarding ─────────────────────────────────────

        @Test void restParamForwarding() {
            // Collect args via rest, then reduce with /+
            var result = eval("""
                (...args -> /+ args) 10 20 30
                """);
            assertEquals(60L, result);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Effect Row Checking
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class EffectRows {

        // ── Parsing ────────────────────────────────────────────────────

        @Test void effectAnnotationParsesShorthand() {
            assertEquals("Hello, world", run("""
                fn greet ::: Console
                  (name -> println ("Hello, " ++ name))
                greet "world"
                """));
        }

        @Test void effectAnnotationWithTypeAnnotation() {
            assertEquals("Hello, world", run("""
                fn greet :: Str () ::: Console
                  (name -> println ("Hello, " ++ name))
                greet "world"
                """));
        }

        @Test void unannotatedFnIsPureByDefault() {
            // unannotated fn is pure — no effects allowed, but pure computation works
            var result = eval("""
                fn add
                  (a b -> a + b)
                add 3 4
                """);
            assertEquals(7L, result);
        }

        // ── Enforcement: Console effect ────────────────────────────────

        @Test void pureFnCannotCallPrintln() {
            // unannotated fn is pure — calling println should fail
            assertThrows(IrijRuntimeError.class, () -> eval("""
                fn bad
                  (x -> println x)
                bad "hello"
                """));
        }

        @Test void consoleFnCanCallPrintln() {
            assertEquals("hello", run("""
                fn greet ::: Console
                  (x -> println x)
                greet "hello"
                """));
        }

        @Test void unannotatedFnIsPure() {
            // Unannotated fn is now pure — calling println should fail
            assertThrows(IrijRuntimeError.class, () -> eval("""
                fn greet
                  (x -> println x)
                greet "hello"
                """));
        }

        @Test void topLevelPrintlnAlwaysWorks() {
            assertEquals("top-level", run("println \"top-level\""));
        }

        @Test void pureFnCallingPureFnWorks() {
            var result = eval("""
                fn double
                  (x -> x * 2)
                fn quad
                  (x -> double (double x))
                quad 5
                """);
            assertEquals(20L, result);
        }

        @Test void pureFnCanCallAnnotatedConsoleFn() {
            // Each fn establishes its own effect context.
            // pure-caller is pure (unannotated), but say has its own ::: Console context
            assertEquals("hello", run("""
                fn say ::: Console
                  (x -> println x)
                fn pure-caller
                  (x -> say x)
                pure-caller "hello"
                """));
        }

        @Test void pureFnCannotDirectlyUsePrintln() {
            // A pure fn (unannotated) CANNOT call println directly
            assertThrows(IrijRuntimeError.class, () -> eval("""
                fn pure-caller
                  (x -> println x)
                pure-caller "boom"
                """));
        }

        // ── Effect via with block ──────────────────────────────────────

        @Test void withBlockAddsEffectToContext() {
            assertEquals("Hello, world", run("""
                effect Greet
                  greet :: Str -> ()
                handler friendly :: Greet ::: Console
                  greet name => resume (println ("Hello, " ++ name))
                fn go ::: Greet
                  => name
                  greet name
                with friendly
                  go "world"
                """));
        }

        @Test void pureFnWithInternalHandlerWorks() {
            assertEquals("working", run("""
                effect Log
                  log :: Str -> ()
                handler console-log :: Log ::: Console
                  log msg => resume (println msg)
                fn do-work ::: Console Log
                  =>
                  with console-log
                    log "working"
                do-work ()
                """));
        }

        // ── User-defined effects ───────────────────────────────────────

        @Test void userEffectCheckedInAnnotatedFn() {
            assertEquals("test", run("""
                effect MyLog
                  log-msg :: Str -> ()
                handler my-logger :: MyLog ::: Console
                  log-msg msg => resume (println msg)
                fn do-stuff ::: MyLog
                  (x -> log-msg x)
                with my-logger
                  do-stuff "test"
                """));
        }

        @Test void pureFnCannotCallUserEffectOps() {
            assertThrows(IrijRuntimeError.class, () -> eval("""
                effect Beep
                  beep :: () -> ()
                handler beeper :: Beep
                  beep () => resume ()
                fn silent
                  (_ -> beep ())
                with beeper
                  silent ()
                """));
        }

        // ── Multiple effects ───────────────────────────────────────────

        @Test void multipleEffectsInRow() {
            assertEquals("a\nb\nc", run("""
                effect A
                  op-a :: () -> ()
                effect B
                  op-b :: () -> ()
                handler ha :: A ::: Console
                  op-a () => resume (println "a")
                handler hb :: B ::: Console
                  op-b () => resume (println "b")
                fn both ::: A B Console
                  =>
                  op-a ()
                  op-b ()
                  println "c"
                with ha >> hb
                  both ()
                """));
        }

        @Test void missingOneEffectFails() {
            // Declares ::: A but calls op-b which requires B
            assertThrows(IrijRuntimeError.class, () -> eval("""
                effect A
                  op-a :: () -> ()
                effect B
                  op-b :: () -> ()
                handler ha :: A
                  op-a () => resume ()
                handler hb :: B
                  op-b () => resume ()
                fn only-a ::: A
                  (_ -> op-b ())
                with ha >> hb
                  only-a ()
                """));
        }

        // ── Imperative and match fn styles ─────────────────────────────

        @Test void imperativeFnWithEffectRow() {
            assertEquals("Hi, Irij", run("""
                fn greet ::: Console
                  => name
                  println ("Hi, " ++ name)
                greet "Irij"
                """));
        }

        @Test void matchFnWithEffectRow() {
            assertEquals("zero\nnumber: 42", run("""
                fn describe ::: Console
                  0 => println "zero"
                  n => println ("number: " ++ to-str n)
                describe 0
                describe 42
                """));
        }

        // ── Partial application ────────────────────────────────────────

        @Test void partialAppPreservesEffectRow() {
            assertEquals("Hi, Irij", run("""
                fn greet ::: Console
                  (prefix name -> println (prefix ++ name))
                hi := greet "Hi, "
                hi "Irij"
                """));
        }

        // ── Contracts + effects ────────────────────────────────────────

        @Test void contractedFnWithEffectRow() {
            assertEquals("works", run("""
                fn safe-print ::: Console
                  pre (x -> x /= "")
                  (x -> println x)
                safe-print "works"
                """));
        }

        // ── read-line builtin exists ───────────────────────────────────

        @Test void readLineRequiresConsole() {
            // read-line should require Console
            assertThrows(IrijRuntimeError.class, () -> eval("""
                fn pure-read
                  (_ -> read-line ())
                pure-read ()
                """));
        }

        // ── Handler effect annotations ─────────────────────────────────

        @Test void handlerWithoutAnnotationIsPure() {
            // Handler clause that calls println should fail if handler is unannotated (pure)
            assertThrows(IrijRuntimeError.class, () -> eval("""
                effect MyLog
                  log-msg :: Str -> ()
                handler pure-logger :: MyLog
                  log-msg msg => resume (println msg)
                fn do-log ::: MyLog
                  (x -> log-msg x)
                with pure-logger
                  do-log "test"
                """));
        }

        @Test void handlerWithAnnotationAllowsDeclaredEffects() {
            assertEquals("hello", run("""
                effect MyLog
                  log-msg :: Str -> ()
                handler console-logger :: MyLog ::: Console
                  log-msg msg => resume (println msg)
                fn do-log ::: MyLog
                  (x -> log-msg x)
                with console-logger
                  do-log "hello"
                """));
        }

        @Test void handlerAnnotationBlocksUndeclaredEffects() {
            // Handler declares ::: Console but tries to use an effect it didn't declare
            // (in this case, a user effect that requires something else)
            // For now, test that a handler with no annotation can't call println
            assertThrows(IrijRuntimeError.class, () -> eval("""
                effect Ping
                  ping :: () -> ()
                handler bad-ping :: Ping
                  ping () => resume (println "ping!")
                with bad-ping
                  ping ()
                """));
        }

        @Test void pureHandlerClauseWorks() {
            // A handler whose clauses don't need any effects works without annotation
            var result = eval("""
                effect Greet
                  greet :: Str -> Str
                handler friendly :: Greet
                  greet name => resume ("Hello, " ++ name)
                with friendly
                  greet "world"
                """);
            assertEquals("Hello, world", result);
        }

        // ── Error message quality ──────────────────────────────────────

        @Test void errorMessageIncludesEffectName() {
            try {
                eval("""
                    fn bad
                      (x -> println x)
                    bad "test"
                    """);
                fail("Should have thrown");
            } catch (IrijRuntimeError e) {
                assertTrue(e.getMessage().contains("Console"),
                    "Error should mention 'Console': " + e.getMessage());
                assertTrue(e.getMessage().contains("println"),
                    "Error should mention 'println': " + e.getMessage());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // JSON
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class Json {
        @Test void parseObject() {
            assertEquals("{name= Alice age= 30}", run("""
                println (json-parse "{\\"name\\": \\"Alice\\", \\"age\\": 30}")"""));
        }

        @Test void parseArray() {
            assertEquals("#[1 2 3]", run("""
                println (json-parse "[1, 2, 3]")"""));
        }

        @Test void parseBoolNull() {
            assertEquals("true\n()", run("""
                println (json-parse "true")
                println (json-parse "null")"""));
        }

        @Test void parseNested() {
            assertEquals("Alice", run("""
                data := json-parse "{\\"user\\": {\\"name\\": \\"Alice\\"}}"
                println data.user.name"""));
        }

        @Test void parseFloat() {
            assertEquals("3.14", run("""
                println (json-parse "3.14")"""));
        }

        @Test void encodeMap() {
            var result = eval("""
                json-encode {name= "Bob" age= 25}""");
            assertTrue(result instanceof String);
            var s = (String) result;
            assertTrue(s.contains("\"name\""));
            assertTrue(s.contains("\"Bob\""));
            assertTrue(s.contains("\"age\""));
            assertTrue(s.contains("25"));
        }

        @Test void encodeVector() {
            assertEquals("[1,2,3]", eval("""
                json-encode #[1 2 3]"""));
        }

        @Test void encodeString() {
            assertEquals("\"hello\"", eval("""
                json-encode "hello\""""));
        }

        @Test void encodeBooleans() {
            assertEquals("true", eval("""
                json-encode true"""));
        }

        @Test void encodeUnit() {
            assertEquals("null", eval("""
                json-encode ()"""));
        }

        @Test void encodeTaggedSum() {
            var result = (String) eval("""
                spec Shape
                  Circle Float
                json-encode (Circle 5.0)""");
            assertTrue(result.contains("\"_tag\""));
            assertTrue(result.contains("\"Circle\""));
            assertTrue(result.contains("5.0"));
        }

        @Test void encodeTaggedProduct() {
            var result = (String) eval("""
                spec Person
                  name :: Str
                  age :: Int
                json-encode (Person "Jo" 42)""");
            assertTrue(result.contains("\"_tag\""));
            assertTrue(result.contains("\"Person\""));
            assertTrue(result.contains("\"Jo\""));
            assertTrue(result.contains("42"));
        }

        @Test void roundTrip() {
            assertEquals("{name= Alice age= 30}", run("""
                original := {name= "Alice" age= 30}
                println (json-parse (json-encode original))"""));
        }

        @Test void parseInvalidThrows() {
            assertRuntimeError("""
                json-parse "not valid json{" """);
        }

        @Test void encodeKeyword() {
            assertEquals("\":ok\"", eval("""
                json-encode :ok"""));
        }
    }
}
