package dev.irij.ast;

import java.util.List;

/**
 * All pattern AST nodes for pattern matching and destructuring.
 */
public sealed interface Pattern extends Node {

    /** Variable binding pattern: x. */
    record VarPat(String name, SourceLoc loc) implements Pattern {}

    /** Wildcard pattern: _. */
    record WildcardPat(SourceLoc loc) implements Pattern {}

    /** Literal pattern: 0, 1, "hello", etc. */
    record LitPat(Expr literal, SourceLoc loc) implements Pattern {}

    /** Constructor pattern: Ok value, Leaf 1, Node left x right. */
    record ConstructorPat(String name, List<Pattern> args, SourceLoc loc) implements Pattern {}

    /** Keyword pattern: :ok, :error value. arg may be null. */
    record KeywordPat(String name, Pattern arg, SourceLoc loc) implements Pattern {}

    /** Unit pattern: (). */
    record UnitPat(SourceLoc loc) implements Pattern {}

    /** Grouped pattern: (pat). */
    record GroupedPat(Pattern inner, SourceLoc loc) implements Pattern {}

    /** Vector pattern: #[x y ...rest]. spread may be null. */
    record VectorPat(List<Pattern> elements, SpreadPat spread, SourceLoc loc) implements Pattern {}

    /** Tuple pattern: #(a b). */
    record TuplePat(List<Pattern> elements, SourceLoc loc) implements Pattern {}

    /** Destructure pattern: {name= n age= a}. */
    record DestructurePat(List<DestructureField> fields, SourceLoc loc) implements Pattern {}

    /** A single destructure field: name= pattern. */
    record DestructureField(String key, Pattern value) {}

    /** Spread/rest pattern: ...rest or ..._. name is "_" for ignore. */
    record SpreadPat(String name, SourceLoc loc) implements Pattern {}
}
