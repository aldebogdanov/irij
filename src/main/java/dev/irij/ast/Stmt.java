package dev.irij.ast;

import java.util.List;

/**
 * All statement AST nodes.
 */
public sealed interface Stmt extends Node {

    /** An expression used as a statement. */
    record ExprStmt(Expr expr, SourceLoc loc) implements Stmt {}

    /** Immutable binding: x := expr  or  {pat} := expr.  Optional spec annotation: x := expr :: Spec. */
    record Bind(BindTarget target, Expr value, SpecExpr specAnnotation, SourceLoc loc) implements Stmt {
        /** Convenience constructor without spec annotation. */
        public Bind(BindTarget target, Expr value, SourceLoc loc) {
            this(target, value, null, loc);
        }
    }

    /** Mutable binding: x :! expr. */
    record MutBind(BindTarget target, Expr value, SourceLoc loc) implements Stmt {}

    /** Mutable assignment: x <- expr. */
    record Assign(BindTarget target, Expr value, SourceLoc loc) implements Stmt {}

    /** with handler body (parsed, runtime stub for Phase 3). */
    record With(Expr handler, List<Stmt> body, List<Stmt> onFailure, SourceLoc loc) implements Stmt {}

    /** scope block (parsed, runtime stub for Phase 5). */
    record Scope(String modifier, String name, List<Stmt> body, SourceLoc loc) implements Stmt {}

    /** Block-level match statement. */
    record MatchStmt(Expr scrutinee, List<Expr.MatchArm> arms, SourceLoc loc) implements Stmt {}

    /** Block-level if/else statement. elseBranch may be null/empty. */
    record IfStmt(Expr cond, List<Stmt> thenBranch, List<Stmt> elseBranch, SourceLoc loc) implements Stmt {}

    // ── Bind Target ─────────────────────────────────────────────────────

    sealed interface BindTarget {
        record Simple(String name) implements BindTarget {}
        record Destructure(Pattern pattern) implements BindTarget {}
    }
}
