package dev.irij.parser;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests for the Irij parser.
 * Each test parses a source string and asserts zero parse errors.
 * Tests are organized by spec section.
 */
class ParserSmokeTest {

    /** Parse source and assert no errors. */
    private void assertParses(String source) {
        var result = IrijParseDriver.parse(source);
        if (result.hasErrors()) {
            fail("Parse errors:\n  " + String.join("\n  ", result.errors())
                 + "\n\nSource:\n" + source);
        }
    }

    /** Parse source and assert at least one error. */
    private void assertParseError(String source) {
        var result = IrijParseDriver.parse(source);
        assertTrue(result.hasErrors(),
            "Expected parse error but got none for:\n" + source);
    }

    // ═══════════════════════════════════════════════════════════════
    // §1.2 Canonical Form
    // ═══════════════════════════════════════════════════════════════

    @Nested class CanonicalForm {
        @Test void factorial() {
            assertParses("""
                fn factorial :: Int -> Int
                  0 => 1
                  n => n * factorial (n - 1)
                """);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // §2.1 Bindings, Functions & Closures
    // ═══════════════════════════════════════════════════════════════

    @Nested class BindingsAndFunctions {
        @Test void immutableBinding() {
            assertParses("x := 42\n");
        }

        @Test void bindingWithTypeAnnotation() {
            assertParses("x := 42 :: Int\n");
        }

        @Test void mutableBinding() {
            assertParses("x :! 0 :: Int\n");
        }

        @Test void lambdaFnBody() {
            assertParses("""
                fn add :: Int -> Int -> Int
                  (x y -> x + y)
                """);
        }

        @Test void patternMatchFnBody() {
            assertParses("""
                fn fib :: Int -> Int
                  0 => 0
                  1 => 1
                  n => fib (n - 1) + fib (n - 2)
                """);
        }

        @Test void imperativeBlockNoParams() {
            assertParses("""
                fn main :: () -[IO]> ()
                  =>
                  config := read-config ()
                  run-server config
                """);
        }

        @Test void imperativeBlockWithParams() {
            assertParses("""
                fn greet :: Str -[Console]> ()
                  => name
                  print "Hello"
                """);
        }

        @Test void partialApplication() {
            assertParses("add-one := add 1\n");
        }

        @Test void pointFreeComposition() {
            assertParses("process := parse >> validate >> transform >> serialize\n");
        }

        @Test void mutableReadWrite() {
            assertParses("""
                x :! 0
                x <- x + 1
                print x
                """);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // §2.2 Data Types & Records
    // ═══════════════════════════════════════════════════════════════

    @Nested class DataTypes {
        @Test void productType() {
            assertParses("""
                type User
                  name :: Str
                  age :: Int
                """);
        }

        @Test void sumType() {
            assertParses("""
                type Result a e
                  Ok a
                  Err e
                """);
        }

        @Test void parameterizedType() {
            assertParses("""
                type Tree a
                  Leaf a
                  Node (Tree a) a (Tree a)
                """);
        }

        @Test void newtypeDecl() {
            assertParses("newtype Email := Str\n");
        }

        @Test void anonymousRecord() {
            assertParses("""
                person := {name= "Jo" age= 30}
                """);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // §2.3 Pattern Matching & Destructuring
    // ═══════════════════════════════════════════════════════════════

    @Nested class PatternMatching {
        @Test void matchExpression() {
            assertParses("""
                match result
                  Ok value => process value
                  Err :timeout => retry
                """);
        }

        @Test void matchAsExpression() {
            assertParses("""
                x := match foo
                  0 => "zero"
                  _ => "other"
                """);
        }

        @Test void destructuringBinding() {
            assertParses("""
                {name= n age= a} := get-user id
                """);
        }

        @Test void guards() {
            assertParses("""
                match score
                  s | s >= 90 => :excellent
                  s | s >= 70 => :good
                  _ => :needs-work
                """);
        }

        @Test void vectorPatterns() {
            assertParses("""
                match xs
                  #[] => :empty
                  #[x] => :singleton x
                  #[x ...r] => :cons x r
                """);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // §2.4 Module System
    // ═══════════════════════════════════════════════════════════════

    @Nested class ModuleSystem {
        @Test void moduleDecl() {
            assertParses("mod myapp.auth.jwt\n");
        }

        @Test void useQualified() {
            assertParses("use std.io\n");
        }

        @Test void useOpen() {
            assertParses("use std.json :open\n");
        }

        @Test void useSelective() {
            assertParses("use std.http {get post}\n");
        }

        @Test void pubUse() {
            assertParses("pub use internal.helpers\n");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // §3.1 Effect Declarations
    // ═══════════════════════════════════════════════════════════════

    @Nested class Effects {
        @Test void effectDecl() {
            assertParses("""
                effect Console
                  print :: Str -> ()
                  read-line :: () -> Str
                """);
        }

        @Test void effectfulFunction() {
            assertParses("""
                fn greet :: Str -[Console]> ()
                  => name
                  print "Hello"
                """);
        }

        @Test void multipleEffects() {
            assertParses("""
                fn sync-data :: () -[Http FileIO Log]> Result Data Error
                """);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // §3.2 Effect Handlers
    // ═══════════════════════════════════════════════════════════════

    @Nested class Handlers {
        @Test void handlerDecl() {
            assertParses("""
                handler console-to-stdout :: Console
                  print msg => io.stdout.write msg
                  read-line () => io.stdin.read-line ()
                """);
        }

        @Test void withHandler() {
            assertParses("""
                with console-to-stdout
                  greet "world"
                """);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // §4 Structured Concurrency
    // ═══════════════════════════════════════════════════════════════

    @Nested class Concurrency {
        @Test void scopeWithForks() {
            assertParses("""
                fn fetch-dashboard :: Id -[Http Async]> Dashboard
                  => id
                  scope s
                    user-f := s.fork (-> fetch-user id)
                    orders-f := s.fork (-> fetch-orders id)
                    make-dashboard (await user-f) (await orders-f)
                """);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // §5 Choreographic Programming
    // ═══════════════════════════════════════════════════════════════

    @Nested class Choreography {
        @Test void roleDecl() {
            assertParses("role $CLIENT\n");
        }

        @Test void choreographicSend() {
            assertParses("""
                fn buy-protocol :: Str -[Choreo]> Result
                  => title
                  title ~> $SELLER
                  price := $SELLER.lookup title
                  price ~> $BUYER
                """);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Expressions
    // ═══════════════════════════════════════════════════════════════

    @Nested class Expressions {
        @Test void pipeline() {
            assertParses("result := xs |> @ f |> /? g |> /+\n");
        }

        @Test void arithmetic() {
            assertParses("x := 1 + 2 * 3 ** 4\n");
        }

        @Test void comparison() {
            assertParses("ok := x >= 0 && x <= 100\n");
        }

        @Test void vectorLiteral() {
            assertParses("xs := #[1 2 3]\n");
        }

        @Test void setLiteral() {
            assertParses("s := #{:a :b :c}\n");
        }

        @Test void tupleLiteral() {
            assertParses("t := #(1 \"a\" :ok)\n");
        }

        @Test void mapLiteral() {
            assertParses("m := {name= \"Jo\" age= 5}\n");
        }

        @Test void recordUpdate() {
            assertParses("updated := {...account balance= 0}\n");
        }

        @Test void range() {
            assertParses("r := 1 .. 10\n");
        }

        @Test void exclusiveRange() {
            assertParses("r := 0 ..< n\n");
        }

        @Test void concat() {
            assertParses("all := xs ++ ys\n");
        }

        @Test void stringLiteral() {
            assertParses("s := \"hello ${name}\"\n");
        }

        @Test void keywordAtom() {
            assertParses("status := :ok\n");
        }

        @Test void doExpression() {
            assertParses("do (print \"a\") (print \"b\")\n");
        }

        @Test void inlineIf() {
            assertParses("x := if cond 1 else 0\n");
        }

        @Test void blockIf() {
            assertParses("""
                if x > 0
                  Just x
                else
                  Nothing
                """);
        }

        @Test void semicolonsInsideParens() {
            assertParses("s.fork (-> cmd ~> $REPLICA; $REPLICA.store k v)\n");
        }

        @Test void seqOps() {
            assertParses("count := xs |> /#\n");
        }

        @Test void reduceOp() {
            assertParses("result := xs |> /^ (+)\n");
        }

        @Test void scanOp() {
            assertParses("sums := xs |> /$ (+)\n");
        }

        @Test void operatorAsValue() {
            assertParses("f := (+)\n");
        }

        @Test void operatorAsValueMinus() {
            assertParses("f := (-)\n");
        }

        @Test void operatorSectionInPipeline() {
            assertParses("result := #[1 2 3] |> /^ (+)\n");
        }

        @Test void tildeApplyToRest() {
            assertParses("println ~ \"Hi, \" ++ name\n");
        }

        @Test void tildeChained() {
            assertParses("f ~ g ~ x + 1\n");
        }

        @Test void tildeWithLambda() {
            assertParses("result := identity ~ 2 + 3\n");
        }

    }

    // ═══════════════════════════════════════════════════════════════
    // Comments
    // ═══════════════════════════════════════════════════════════════

    @Nested class Comments {
        @Test void lineComment() {
            assertParses("""
                ;; this is a comment
                x := 42
                """);
        }

        @Test void commentMidBlock() {
            assertParses("""
                fn f :: Int -> Int
                  ;; compute result
                  (x -> x + 1)
                """);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Lexer-level tests
    // ═══════════════════════════════════════════════════════════════

    @Nested class LexerTests {
        @Test void tokenizesDigraphs() {
            var tokens = IrijParseDriver.tokenize(":= :! <- -> => :: |> <| >> << -[ ]> ~> <~ ~*> ~/ == /= && ||");
            // Just verify no errors — tokens are produced
            assertTrue(tokens.size() > 1);
        }

        @Test void tokenizesCollectionOpeners() {
            var tokens = IrijParseDriver.tokenize("#[1 2] #{:a} #(1 2)");
            assertTrue(tokens.size() > 1);
        }

        @Test void tokenizesSeqOps() {
            var tokens = IrijParseDriver.tokenize("/+ /* /# /& /| /? /! /^ /$");
            assertTrue(tokens.size() > 1);
        }

        @Test void tokenizesKeywords() {
            var tokens = IrijParseDriver.tokenize(":ok :error :pending");
            assertTrue(tokens.size() > 1);
        }

        @Test void tokenizesRoleNames() {
            var tokens = IrijParseDriver.tokenize("$ALICE $BOB $DB-PRIMARY");
            assertTrue(tokens.size() > 1);
        }

        @Test void tokenizesNumbers() {
            var tokens = IrijParseDriver.tokenize("42 3.14 0xFF 1_000_000 2/3");
            assertTrue(tokens.size() > 1);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Phase 4.5a — Destructuring Bindings & Implicit Continuation
    // ═══════════════════════════════════════════════════════════════════

    @Nested class DestructuringBindings {
        @Test void vectorDestructure() {
            assertParses("#[a b c] := #[1 2 3]\n");
        }

        @Test void vectorSpreadDestructure() {
            assertParses("#[first ...rest] := #[1 2 3 4]\n");
        }

        @Test void tupleDestructure() {
            assertParses("#(x y) := #(42 \"hello\")\n");
        }

        @Test void nestedDestructure() {
            assertParses("#[#(a b) #(c d)] := vec\n");
        }
    }

    @Nested class ImplicitContinuation {
        @Test void pipelineContinuation() {
            assertParses("""
                x := data
                  |> filter
                  |> map
                """);
        }

        @Test void concatContinuation() {
            assertParses("""
                s := "a"
                  ++ "b"
                  ++ "c"
                """);
        }

        @Test void arithmeticContinuation() {
            assertParses("""
                x := 1
                  + 2
                  + 3
                """);
        }

        @Test void booleanContinuation() {
            assertParses("""
                b := x
                  && y
                  || z
                """);
        }

        @Test void continuationInsideBlock() {
            assertParses("""
                fn f
                  => x
                  x
                    |> double
                    |> square
                """);
        }

        @Test void normalIndentNotAffected() {
            assertParses("""
                if true
                  println "hello"
                """);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Protocols & Implementations
    // ═══════════════════════════════════════════════════════════════════

    @Nested
    class ProtocolsAndImpls {

        @Test void basicProtoDecl() {
            assertParses("""
                proto Show a
                  show :: a -> Str
                """);
        }

        @Test void protoMultipleMethods() {
            assertParses("""
                proto Monoid a
                  empty :: a
                  append :: a -> a -> a
                """);
        }

        @Test void protoWithLaws() {
            assertParses("""
                proto Monoid a
                  empty :: a
                  append :: a -> a -> a
                  law identity = forall x. append empty x == x
                """);
        }

        @Test void basicImplDecl() {
            assertParses("""
                impl Show for Int
                  show := (n -> to-str n)
                """);
        }

        @Test void implMultipleBindings() {
            assertParses("""
                impl Monoid for Str
                  empty := ""
                  append := (a b -> a ++ b)
                """);
        }

        @Test void protoAndImplTogether() {
            assertParses("""
                proto Eq a
                  eq :: a -> a -> Bool

                impl Eq for Int
                  eq := (a b -> a == b)

                impl Eq for Str
                  eq := (a b -> a == b)
                """);
        }
    }

    @Nested
    class Contracts {

        @Test void preCondition() {
            assertParses("""
                fn positive
                  pre (x -> x > 0)
                  (x -> x)
                """);
        }

        @Test void postCondition() {
            assertParses("""
                fn half
                  post (r -> r >= 0)
                  (x -> div x 2)
                """);
        }

        @Test void preAndPost() {
            assertParses("""
                fn withdraw
                  pre (amt bal -> amt > 0 && amt <= bal)
                  post (r -> r >= 0)
                  (amt bal -> bal - amt)
                """);
        }

        @Test void multiplePreConditions() {
            assertParses("""
                fn transfer
                  pre (amt from to -> amt > 0)
                  pre (amt from to -> from /= to)
                  (amt from to -> from - amt)
                """);
        }

        @Test void contractWithImperativeBody() {
            assertParses("""
                fn safe-div
                  pre (a b -> b /= 0)
                  => a b
                  result := div a b
                  result
                """);
        }

        @Test void contractWithMatchArms() {
            assertParses("""
                fn abs-val
                  post (r -> r >= 0)
                  x | x >= 0 => x
                  x           => 0 - x
                """);
        }

        // ── Phase 6b: in/out contracts ──────────────────────────

        @Test void inContract() {
            assertParses("""
                fn api-endpoint
                  in (r -> r > 0)
                  (r -> r * 2)
                """);
        }

        @Test void outContract() {
            assertParses("""
                fn api-endpoint
                  out (r -> r > 0)
                  (r -> r * 2)
                """);
        }

        @Test void inAndOutContracts() {
            assertParses("""
                fn api-endpoint
                  in (r -> r > 0)
                  out (r -> r < 500)
                  (r -> r * 2)
                """);
        }

        @Test void allContractTypes() {
            assertParses("""
                fn complete
                  pre (x -> x > 0)
                  in (x -> x < 100)
                  post (r -> r > 0)
                  out (r -> r < 200)
                  (x -> x * 2)
                """);
        }

        // ── Phase 6c: law clauses ───────────────────────────────

        @Test void fnLevelLaw() {
            assertParses("""
                fn my-sort
                  law idempotent = forall xs. my-sort (my-sort xs) == my-sort xs
                  (xs -> sort xs)
                """);
        }

        @Test void fnLevelLawNoForall() {
            assertParses("""
                fn add-zero
                  law identity = add-zero 0 == 0
                  (x -> x + 0)
                """);
        }

        @Test void protoWithLawAndForall() {
            assertParses("""
                proto Monoid a
                  empty :: a
                  append :: a -> a -> a
                  law identity-left = forall x. append empty x == x
                  law identity-right = forall x. append x empty == x
                  law associative = forall x y z. append (append x y) z == append x (append y z)
                """);
        }

        @Test void inOutOnImperativeFn() {
            assertParses("""
                fn safe-add
                  in (a b -> a > 0)
                  out (r -> r > 0)
                  => a b
                  a + b
                """);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Rest Parameters
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class RestParameters {

        @Test void lambdaRestParam() {
            assertParses("(x ...rest -> rest)");
        }

        @Test void lambdaRestParamNoFixed() {
            assertParses("(...args -> length args)");
        }

        @Test void fnDeclLambdaRestParam() {
            assertParses("""
                fn variadic
                  (first ...rest -> rest)
                """);
        }

        @Test void fnDeclImperativeRestParam() {
            assertParses("""
                fn sum-all
                  => ...nums
                  /+ nums
                """);
        }

        @Test void fnDeclImperativeRestParamWithFixed() {
            assertParses("""
                fn log-msg
                  => level ...parts
                  join " " parts
                """);
        }
    }
}
