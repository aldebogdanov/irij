package dev.irij.ast;

import java.util.List;

/**
 * All expression AST nodes.
 */
public sealed interface Expr extends Node {

    // ── Literals ────────────────────────────────────────────────────────

    record IntLit(long value, SourceLoc loc) implements Expr {}
    record FloatLit(double value, SourceLoc loc) implements Expr {}
    record RationalLit(long num, long den, SourceLoc loc) implements Expr {}
    record HexLit(long value, SourceLoc loc) implements Expr {}
    record StrLit(String value, SourceLoc loc) implements Expr {}
    record BoolLit(boolean value, SourceLoc loc) implements Expr {}
    record KeywordLit(String name, SourceLoc loc) implements Expr {}
    record UnitLit(SourceLoc loc) implements Expr {}

    // ── References ──────────────────────────────────────────────────────

    /** Variable reference (kebab-case identifier). */
    record Var(String name, SourceLoc loc) implements Expr {}

    /** Type name used as expression (PascalCase — ADT constructor). */
    record TypeRef(String name, SourceLoc loc) implements Expr {}

    /** Role name reference ($BUYER etc). */
    record RoleRef(String name, SourceLoc loc) implements Expr {}

    // ── Operators ───────────────────────────────────────────────────────

    record BinaryOp(String op, Expr left, Expr right, SourceLoc loc) implements Expr {}
    record UnaryOp(String op, Expr operand, SourceLoc loc) implements Expr {}

    // ── Application & Lambda ────────────────────────────────────────────

    /** Function application: fn applied to args (juxtaposition). */
    record App(Expr fn, List<Expr> args, SourceLoc loc) implements Expr {}

    /** Lambda expression: (params -> body) or (params ...rest -> body). */
    record Lambda(List<Pattern> params, String restParam, Expr body, SourceLoc loc) implements Expr {
        /** Convenience constructor without rest param. */
        public Lambda(List<Pattern> params, Expr body, SourceLoc loc) {
            this(params, null, body, loc);
        }
    }

    // ── Pipeline & Composition ──────────────────────────────────────────

    /** Pipeline: a |> f  or  f <| a. forward=true means |>. */
    record Pipe(Expr left, Expr right, boolean forward, SourceLoc loc) implements Expr {}

    /** Composition: f >> g  or  g << f. forward=true means >>. */
    record Compose(Expr left, Expr right, boolean forward, SourceLoc loc) implements Expr {}

    // ── Seq Ops ─────────────────────────────────────────────────────────

    /** Sequence operator: /+, @, /?, /^, /$, etc. arg may be null for standalone use. */
    record SeqOp(String op, Expr arg, SourceLoc loc) implements Expr {}

    // ── Operator Section ──────────────────────────────────────────────

    /** Operator used as first-class value: (+), (-), (*), etc. */
    record OpSection(String op, SourceLoc loc) implements Expr {}

    // ── Control Flow ────────────────────────────────────────────────────

    /** If expression (inline or block). elseBranch may be null. */
    record IfExpr(Expr cond, Expr thenBranch, Expr elseBranch, SourceLoc loc) implements Expr {}

    /** Match expression with arms. */
    record MatchExpr(Expr scrutinee, List<MatchArm> arms, SourceLoc loc) implements Expr {}

    /** A single match arm: pattern, optional guard, body. */
    record MatchArm(Pattern pattern, Expr guard, Expr body) {}

    // ── Collections ─────────────────────────────────────────────────────

    record VectorLit(List<Expr> elements, SourceLoc loc) implements Expr {}
    record SetLit(List<Expr> elements, SourceLoc loc) implements Expr {}
    record TupleLit(List<Expr> elements, SourceLoc loc) implements Expr {}
    record MapLit(List<MapEntry> entries, SourceLoc loc) implements Expr {}

    /** A map entry: either a key=value or a spread. */
    sealed interface MapEntry {
        record Field(String key, Expr value) implements MapEntry {}
        record Spread(String name) implements MapEntry {}
    }

    /** Record update: {...base key= val}. */
    record RecordUpdate(String base, List<MapEntry> updates, SourceLoc loc) implements Expr {}

    // ── Range ───────────────────────────────────────────────────────────

    /** Range: from .. to  or  from ..< to. */
    record Range(Expr from, Expr to, boolean exclusive, SourceLoc loc) implements Expr {}

    // ── String Interpolation ────────────────────────────────────────────

    /** String with interpolated expressions: "hello ${name}". */
    record StringInterp(List<StringPart> parts, SourceLoc loc) implements Expr {}

    sealed interface StringPart {
        record Literal(String text) implements StringPart {}
        record Interpolation(Expr expr) implements StringPart {}
    }

    // ── Misc ────────────────────────────────────────────────────────────

    /** Dot access: expr.field. */
    record DotAccess(Expr target, String field, SourceLoc loc) implements Expr {}

    /** do expression: do (e1) (e2) ... */
    record DoExpr(List<Expr> exprs, SourceLoc loc) implements Expr {}

    /** A block of statements; the value is the last expression. */
    record Block(List<Stmt> stmts, SourceLoc loc) implements Expr {}

    /** Wildcard expression _ (placeholder). */
    record Wildcard(SourceLoc loc) implements Expr {}

    // ── Choreography (parsed, stubbed at runtime) ───────────────────────

    /** Choreographic send/recv: expr ~> $ROLE, expr <~ $ROLE, etc. */
    record ChoreoExpr(String op, Expr left, Expr right, SourceLoc loc) implements Expr {}
}
