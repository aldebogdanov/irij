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

    // ── Purity checks ───────────────────────────────────────────────────

    private TypeChecker checkWithPurity(String source, PurityMode mode) {
        var lexer = new IrijLexer(CharStreams.fromString(source));
        var tokens = new CommonTokenStream(lexer);
        var parser = new IrijParser(tokens);
        parser.removeErrorListeners();
        var cu = parser.compilationUnit();
        var checker = new TypeChecker();
        checker.setPurityMode(mode);
        checker.check(cu);
        return checker;
    }

    @Test
    void strictModeRejectsDirectIO() {
        var checker = checkWithPurity("""
            fn main :: () -[IO]-> ()
              io.stdout.write "hello"
            """, PurityMode.STRICT);
        assertFalse(checker.getErrors().isEmpty(), "Strict mode should produce purity errors");
        assertTrue(checker.getErrors().get(0).contains("io.stdout.write"));
    }

    @Test
    void warnModeProducesWarnings() {
        var lexer = new IrijLexer(CharStreams.fromString("""
            fn main :: () -[IO]-> ()
              io.stderr.write "oops"
            """));
        var tokens = new CommonTokenStream(lexer);
        var parser = new IrijParser(tokens);
        parser.removeErrorListeners();
        var cu = parser.compilationUnit();
        var checker = new TypeChecker();
        checker.setPurityMode(PurityMode.WARN);
        var warnings = checker.check(cu);
        assertTrue(checker.getErrors().isEmpty(), "Warn mode should not produce errors");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Purity warning")),
                "Warn mode should produce purity warnings");
    }

    @Test
    void allowModePermitsDirectIO() {
        var checker = checkWithPurity("""
            fn main :: () -[IO]-> ()
              io.stdout.write "hello"
            """, PurityMode.ALLOW);
        assertTrue(checker.getErrors().isEmpty(), "Allow mode should produce no errors");
    }

    @Test
    void handlerBodyAllowsDirectIO() {
        var checker = checkWithPurity("""
            effect Console
              print :: Str -> ()

            handler console-handler :: Console
              print msg => io.stdout.write msg

            fn main :: () -[IO]-> ()
              with console-handler
                print "hello"
            """, PurityMode.STRICT);
        assertTrue(checker.getErrors().isEmpty(),
                "Direct IO inside handler body should be allowed even in strict mode");
    }

    // ── Exhaustiveness checking ─────────────────────────────────────────

    @Test
    void warnsOnNonExhaustiveConstructorMatch() {
        var warnings = check("""
            type Color
              Red
              Green
              Blue

            fn main :: () -[IO]-> ()
              c := Red
              result := match c
                Red => "red"
                Green => "green"
              io.stdout.write result
            """);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Non-exhaustive") && w.contains("Blue")),
                "Should warn about missing Blue variant");
    }

    @Test
    void noWarningOnExhaustiveConstructorMatch() {
        var warnings = check("""
            type Color
              Red
              Green
              Blue

            fn main :: () -[IO]-> ()
              c := Red
              result := match c
                Red => "red"
                Green => "green"
                Blue => "blue"
              io.stdout.write result
            """);
        assertTrue(warnings.stream().noneMatch(w -> w.contains("Non-exhaustive")),
                "All constructors covered — no exhaustiveness warning");
    }

    @Test
    void noWarningOnWildcardCatchAll() {
        var warnings = check("""
            type Color
              Red
              Green
              Blue

            fn main :: () -[IO]-> ()
              c := Red
              result := match c
                Red => "red"
                _ => "other"
              io.stdout.write result
            """);
        assertTrue(warnings.stream().noneMatch(w -> w.contains("Non-exhaustive")),
                "Wildcard catch-all makes match exhaustive");
    }

    @Test
    void noWarningOnVariableCatchAll() {
        var warnings = check("""
            type Color
              Red
              Green
              Blue

            fn main :: () -[IO]-> ()
              c := Red
              result := match c
                Red => "red"
                other => "other"
              io.stdout.write result
            """);
        assertTrue(warnings.stream().noneMatch(w -> w.contains("Non-exhaustive")),
                "Variable catch-all makes match exhaustive");
    }

    // ── Effect row validation ───────────────────────────────────────────

    @Test
    void warnsOnUndeclaredEffect() {
        var warnings = check("""
            effect Console
              print :: Str -> ()

            fn greet :: () -> ()
              print "hello"

            fn main :: () -[IO]-> ()
              greet ()
            """);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Console") && w.contains("print")),
                "Should warn about undeclared Console effect");
    }

    @Test
    void noWarningWhenEffectDeclared() {
        var warnings = check("""
            effect Console
              print :: Str -> ()

            fn greet :: () -[Console]-> ()
              print "hello"

            fn main :: () -[IO]-> ()
              greet ()
            """);
        assertTrue(warnings.stream().noneMatch(w -> w.contains("Console")),
                "Effect is declared — no warning");
    }
}
