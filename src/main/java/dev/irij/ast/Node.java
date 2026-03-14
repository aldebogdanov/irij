package dev.irij.ast;

/**
 * Base interface for all AST nodes.
 * Every node carries a source location for error reporting.
 */
public sealed interface Node permits Expr, Stmt, Decl, Pattern {
    SourceLoc loc();

    /**
     * Source location: line and column (1-based).
     */
    record SourceLoc(int line, int col) {
        public static final SourceLoc UNKNOWN = new SourceLoc(0, 0);

        @Override
        public String toString() {
            return line + ":" + col;
        }
    }
}
