package dev.irij.interpreter;

import dev.irij.parser.IrijLexer;
import dev.irij.parser.IrijParser;
import org.antlr.v4.runtime.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Irij interpreter.
 * Each test parses and executes Irij source code, capturing stdout.
 */
class InterpreterTest {

    /**
     * Parse and execute Irij source, returning captured stdout lines.
     */
    private String run(String source) {
        var lexer = new IrijLexer(CharStreams.fromString(source));
        var tokens = new CommonTokenStream(lexer);
        var parser = new IrijParser(tokens);
        parser.removeErrorListeners();

        var errors = new StringBuilder();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> r, Object sym,
                                    int line, int col, String msg, RecognitionException e) {
                errors.append("line ").append(line).append(':').append(col).append(' ').append(msg).append('\n');
            }
        });

        var cu = parser.compilationUnit();
        if (parser.getNumberOfSyntaxErrors() > 0) {
            fail("Parse errors:\n" + errors);
        }

        var out = new ByteArrayOutputStream();
        var oldOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            var interpreter = new IrijInterpreter();
            interpreter.execute(cu);
        } finally {
            System.setOut(oldOut);
        }
        return out.toString().stripTrailing();
    }

    // ═════════════════════════════════════════════════════════════════════
    // Basic programs
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class BasicPrograms {
        @Test void helloWorld() {
            assertEquals("Hello, World!", run("""
                effect Console
                  print :: Str -> ()
                handler console-to-stdout :: Console
                  print msg => io.stdout.write msg
                fn main :: () -[IO]-> ()
                  with console-to-stdout
                    print "Hello, World!"
                """));
        }

        @Test void multipleStatements() {
            assertEquals("one\ntwo\nthree", run("""
                effect Console
                  print :: Str -> ()
                handler console-to-stdout :: Console
                  print msg => io.stdout.write msg
                fn main :: () -[IO]-> ()
                  with console-to-stdout
                    print "one"
                    print "two"
                    print "three"
                """));
        }

        @Test void bindings() {
            assertEquals("42", run("""
                effect Console
                  print :: Str -> ()
                handler console-to-stdout :: Console
                  print msg => io.stdout.write msg
                fn main :: () -[IO]-> ()
                  with console-to-stdout
                    x := 42
                    print (to-str x)
                """));
        }

        @Test void functionCall() {
            assertEquals("hello from greet", run("""
                effect Console
                  print :: Str -> ()
                handler console-to-stdout :: Console
                  print msg => io.stdout.write msg
                fn greet :: () -[Console]-> ()
                  print "hello from greet"
                fn main :: () -[IO]-> ()
                  with console-to-stdout
                    greet ()
                """));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Arithmetic & operators
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class Arithmetic {
        @Test void addition() {
            assertEquals("7", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write (to-str (3 + 4))
                """));
        }

        @Test void subtraction() {
            assertEquals("5", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write (to-str (10 - 5))
                """));
        }

        @Test void multiplication() {
            assertEquals("42", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write (to-str (6 * 7))
                """));
        }

        @Test void division() {
            assertEquals("5", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write (to-str (10 / 2))
                """));
        }

        @Test void modulo() {
            assertEquals("1", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write (to-str (7 % 3))
                """));
        }

        @Test void negation() {
            assertEquals("-5", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write (to-str (-5))
                """));
        }

        @Test void comparison() {
            assertEquals("true\nfalse", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write (to-str (3 < 5))
                  io.stdout.write (to-str (5 < 3))
                """));
        }

        @Test void equality() {
            assertEquals("true\nfalse", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write (to-str (42 == 42))
                  io.stdout.write (to-str (42 /= 42))
                """));
        }

        @Test void booleanLogic() {
            assertEquals("true\nfalse", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write (to-str (true && true))
                  io.stdout.write (to-str (true && false))
                """));
        }

        @Test void divisionByZero() {
            assertThrows(IrijRuntimeError.class, () -> run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write (to-str (1 / 0))
                """));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Stdlib builtins from Irij code
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class StdlibFromIrij {
        @Test void identityFunction() {
            assertEquals("42", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write (to-str (identity 42))
                """));
        }

        @Test void mathFunctions() {
            assertEquals("42\n3\n7\n12.0", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write (to-str (abs (-42)))
                  io.stdout.write (to-str (min 3 7))
                  io.stdout.write (to-str (max 3 7))
                  io.stdout.write (to-str (sqrt 144.0))
                """));
        }

        @Test void textFunctions() {
            assertEquals("5\nHELLO\nhello", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write (to-str (length "hello"))
                  io.stdout.write (to-upper "hello")
                  io.stdout.write (to-lower "HELLO")
                """));
        }

        @Test void vectorOperations() {
            assertEquals("#[10 20 30]\nSome 10\n#[20 30]", run("""
                fn main :: () -[IO]-> ()
                  v := #[10 20 30]
                  io.stdout.write (show v)
                  io.stdout.write (show (head v))
                  io.stdout.write (show (tail v))
                """));
        }

        @Test void mapOperations() {
            assertEquals("{name: \"Ada\"}\nSome Ada\n#[\"name\"]", run("""
                fn main :: () -[IO]-> ()
                  m := {name: "Ada"}
                  io.stdout.write (show m)
                  io.stdout.write (show (get "name" m))
                  io.stdout.write (show (keys m))
                """));
        }

        @Test void constructors() {
            assertEquals("Ok 42\nErr oops\nSome hi\nNone", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write (show (Ok 42))
                  io.stdout.write (show (Err "oops"))
                  io.stdout.write (show (Some "hi"))
                  io.stdout.write (show None)
                """));
        }

        @Test void unwrapOk() {
            assertEquals("42", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write (to-str (unwrap (Ok 42)))
                """));
        }

        @Test void unwrapOrDefault() {
            assertEquals("0", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write (to-str (unwrap-or (Err "fail") 0))
                """));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Sequence operators
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class SeqOps {
        @Test void reduceSum() {
            assertEquals("15", run("""
                fn main :: () -[IO]-> ()
                  v := #[1 2 3 4 5]
                  io.stdout.write (to-str (v |> /+))
                """));
        }

        @Test void reduceProduct() {
            assertEquals("120", run("""
                fn main :: () -[IO]-> ()
                  v := #[1 2 3 4 5]
                  io.stdout.write (to-str (v |> /*))
                """));
        }

        @Test void count() {
            assertEquals("5", run("""
                fn main :: () -[IO]-> ()
                  v := #[1 2 3 4 5]
                  io.stdout.write (to-str (v |> /#))
                """));
        }

        @Test void reduceAnd() {
            assertEquals("true", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write (to-str (#[true true true] |> /&))
                """));
        }

        @Test void reduceOr() {
            assertEquals("true", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write (to-str (#[false true false] |> /|))
                """));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Ranges and concat
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class RangesAndConcat {
        @Test void rangeInclusive() {
            assertEquals("#[1 2 3 4 5]", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write (show (1..5))
                """));
        }

        @Test void rangeExclusive() {
            assertEquals("#[1 2 3 4]", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write (show (1..<5))
                """));
        }

        @Test void stringConcat() {
            assertEquals("Hello World", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write ("Hello" ++ " " ++ "World")
                """));
        }

        @Test void vectorConcat() {
            assertEquals("#[1 2 3 4]", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write (show (#[1 2] ++ #[3 4]))
                """));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Pipes
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class Pipes {
        @Test void pipeToBuiltin() {
            assertEquals("HELLO", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write ("hello" |> to-upper)
                """));
        }

        @Test void pipeChain() {
            assertEquals("  hello", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write ("  HELLO  " |> to-lower)
                """));
        }

        @Test void pipeToSeqOp() {
            assertEquals("6", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write (to-str (#[1 2 3] |> /+))
                """));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Pattern matching with Tagged values
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class PatternMatching {
        @Test void matchOkErr() {
            assertEquals("got 42", run("""
                effect Console
                  print :: Str -> ()
                handler console-to-stdout :: Console
                  print msg => io.stdout.write msg
                fn main :: () -[IO]-> ()
                  with console-to-stdout
                    result := Ok 42
                    match result
                      Ok v => print ("got " ++ (to-str v))
                      Err e => print ("error: " ++ e)
                """));
        }

        @Test void matchNone() {
            assertEquals("nothing", run("""
                effect Console
                  print :: Str -> ()
                handler console-to-stdout :: Console
                  print msg => io.stdout.write msg
                fn main :: () -[IO]-> ()
                  with console-to-stdout
                    val := None
                    match val
                      Some x => print x
                      None => print "nothing"
                """));
        }

        @Test void matchSome() {
            assertEquals("found hello", run("""
                effect Console
                  print :: Str -> ()
                handler console-to-stdout :: Console
                  print msg => io.stdout.write msg
                fn main :: () -[IO]-> ()
                  with console-to-stdout
                    val := Some "hello"
                    match val
                      Some x => print ("found " ++ x)
                      None => print "nothing"
                """));
        }

        @Test void matchLiteral() {
            assertEquals("one", run("""
                effect Console
                  print :: Str -> ()
                handler console-to-stdout :: Console
                  print msg => io.stdout.write msg
                fn main :: () -[IO]-> ()
                  with console-to-stdout
                    x := 1
                    match x
                      1 => print "one"
                      2 => print "two"
                      _ => print "other"
                """));
        }

        @Test void matchWildcard() {
            assertEquals("other", run("""
                effect Console
                  print :: Str -> ()
                handler console-to-stdout :: Console
                  print msg => io.stdout.write msg
                fn main :: () -[IO]-> ()
                  with console-to-stdout
                    x := 99
                    match x
                      1 => print "one"
                      2 => print "two"
                      _ => print "other"
                """));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // If expressions
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class IfExprs {
        @Test void ifTrue() {
            assertEquals("yes", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write (if (3 > 2) "yes" else "no")
                """));
        }

        @Test void ifFalse() {
            assertEquals("no", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write (if (2 > 3) "yes" else "no")
                """));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // String interpolation
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class StringInterpolation {
        @Test void simpleInterpolation() {
            assertEquals("hello world", run("""
                fn main :: () -[IO]-> ()
                  name := "world"
                  io.stdout.write "hello ${name}"
                """));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Type predicates
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class TypePredicates {
        @Test void typeChecks() {
            assertEquals("Int\nStr\nFloat\nVec\nMap", run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write (type-of 42)
                  io.stdout.write (type-of "hi")
                  io.stdout.write (type-of 3.14)
                  io.stdout.write (type-of #[1 2])
                  io.stdout.write (type-of {a: 1})
                """));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Full program files
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class ProgramFiles {
        @Test void helloIrjFile() throws IOException {
            var source = Files.readString(Path.of("examples/hello.irj"));
            assertEquals("Hello, World!", run(source));
        }

        @Test void stdlibShowcaseFile() throws IOException {
            var source = Files.readString(Path.of("examples/stdlib-showcase.irj"));
            var output = run(source);
            assertTrue(output.contains("All stdlib tests passed!"),
                    "Showcase should complete successfully");
            assertTrue(output.contains("std.core"), "Should have std.core section");
            assertTrue(output.contains("std.math"), "Should have std.math section");
            assertTrue(output.contains("std.text"), "Should have std.text section");
            assertTrue(output.contains("constructors"), "Should have constructors section");
            assertTrue(output.contains("sequence operators"), "Should have seq ops section");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Error handling
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class ErrorHandling {
        @Test void noMainFunction() {
            assertThrows(IrijRuntimeError.class, () -> run("""
                fn greet :: () -> ()
                  io.stdout.write "hi"
                """));
        }

        @Test void undefinedVariable() {
            assertThrows(IrijRuntimeError.class, () -> run("""
                fn main :: () -[IO]-> ()
                  io.stdout.write (to-str undefined-var)
                """));
        }

        @Test void undefinedFunction() {
            assertThrows(IrijRuntimeError.class, () -> run("""
                fn main :: () -[IO]-> ()
                  nonexistent-fn 42
                """));
        }
    }
}
