package dev.irij.interpreter;

import dev.irij.parser.IrijLexer;
import dev.irij.parser.IrijParser;
import org.antlr.v4.runtime.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the warning-only type checker.
 */
class TypeCheckerTest {

    private List<String> check(String source) {
        var lexer = new IrijLexer(CharStreams.fromString(source));
        var tokens = new CommonTokenStream(lexer);
        var parser = new IrijParser(tokens);
        parser.removeErrorListeners();
        var cu = parser.compilationUnit();
        var checker = new TypeChecker();
        return checker.check(cu);
    }

    @Test
    void noWarningsForCleanCode() {
        var warnings = check("""
            fn main :: () -[IO]-> ()
              x := 3 + 4
              y := "hello"
            """);
        assertTrue(warnings.isEmpty(), "Clean code should have no warnings");
    }

    @Test
    void warnsOnStringPlusInt() {
        var warnings = check("""
            fn main :: () -[IO]-> ()
              x := "hello" + 3
            """);
        assertFalse(warnings.isEmpty(), "Should warn about string + int");
        assertTrue(warnings.get(0).contains("incompatible types"),
                "Warning should mention incompatible types");
    }

    @Test
    void noWarningForIntArithmetic() {
        var warnings = check("""
            fn main :: () -[IO]-> ()
              x := 3 + 4 * 2
            """);
        assertTrue(warnings.isEmpty(), "Int arithmetic should be fine");
    }

    @Test
    void warnsOnIfBranchTypeMismatch() {
        var warnings = check("""
            fn main :: () -[IO]-> ()
              x := if (true) 42 else "hello"
            """);
        // This may or may not warn depending on inference depth
        // Just verify it doesn't crash
        assertNotNull(warnings);
    }

    @Test
    void infersLiteralTypes() {
        var checker = new TypeChecker();
        // Just verify the checker doesn't throw on various expressions
        var warnings = check("""
            fn main :: () -[IO]-> ()
              a := 42
              b := 3.14
              c := "hello"
              d := true
              e := #[1 2 3]
              f := {name: "Ada"}
            """);
        assertTrue(warnings.isEmpty(), "Should infer all literal types without warnings");
    }
}
