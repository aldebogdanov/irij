package dev.irij.ast;

import java.util.List;

/**
 * AST representation of spec expressions (type annotations).
 *
 * Used in fn signatures ({@code fn f :: Int Str}), binding annotations
 * ({@code x := 42 :: Int}), and spec field declarations.
 *
 * <p>Design: Malli-style, not HM. Validates concrete shapes at runtime.
 * No parametric type variable tracking.
 */
public sealed interface SpecExpr {

    /** A named spec reference: {@code Int}, {@code Person}, {@code Str}. */
    record Name(String name) implements SpecExpr {
        @Override public String toString() { return name; }
    }

    /** A wildcard: {@code _}. No validation. */
    record Wildcard() implements SpecExpr {
        @Override public String toString() { return "_"; }
    }

    /** A type variable: {@code a}, {@code b}. No validation (documentation only). */
    record Var(String name) implements SpecExpr {
        @Override public String toString() { return name; }
    }

    /** Unit type: {@code ()}. */
    record Unit() implements SpecExpr {
        @Override public String toString() { return "()"; }
    }

    /**
     * Parameterized spec application: {@code Vec Int}, {@code Map Str Int}, {@code Fn 2}.
     * The head is the spec name; args are the parameters.
     *
     * <p>{@code rowVar} (nullable) carries a parametric effect-row
     * binding when the spec position is function-shaped: {@code Fn:eff}
     * parses to {@code App("Fn", [], "eff")}. The variable is bound
     * by the enclosing fn signature and substituted at each call
     * site against the actual argument's effect row. Null on
     * non-function specs and on Fn specs that don't track a row.
     */
    record App(String head, List<SpecExpr> args, String rowVar) implements SpecExpr {
        public App(String head, List<SpecExpr> args) { this(head, args, null); }
        @Override public String toString() {
            var sb = new StringBuilder("(").append(head);
            for (var a : args) sb.append(' ').append(a);
            sb.append(')');
            if (rowVar != null) sb.append(':').append(rowVar);
            return sb.toString();
        }
    }

    /**
     * Arrow (function) spec: {@code (Int -> Str)}, {@code (Person -> Str -> Bool)}.
     * Concrete arrow specs get wrapped in validating contracts at fn boundaries.
     * inputs = all types before the last arrow; output = the type after the last arrow.
     *
     * <p>{@code rowVar} (nullable) carries a parametric effect-row
     * binding when the arrow appears in a spec annotation, e.g.
     * {@code (_ -> Bool):eff}. Same substitution semantics as
     * {@link App#rowVar}.
     */
    record Arrow(List<SpecExpr> inputs, SpecExpr output, String rowVar) implements SpecExpr {
        public Arrow(List<SpecExpr> inputs, SpecExpr output) { this(inputs, output, null); }
        @Override public String toString() {
            var sb = new StringBuilder("(");
            for (int i = 0; i < inputs.size(); i++) {
                if (i > 0) sb.append(" -> ");
                sb.append(inputs.get(i));
            }
            sb.append(" -> ").append(output).append(')');
            if (rowVar != null) sb.append(':').append(rowVar);
            return sb.toString();
        }
    }

    /**
     * Enum spec: {@code (Enum :admin :user :guest)}.
     * Validates that a keyword value is one of the listed options.
     */
    record Enum(List<String> values) implements SpecExpr {
        @Override public String toString() {
            var sb = new StringBuilder("(Enum");
            for (var v : values) sb.append(" :").append(v);
            return sb.append(')').toString();
        }
    }

    /**
     * Vector spec: {@code #[Int]}. Validates all elements match the element spec.
     */
    record VecSpec(SpecExpr elemSpec) implements SpecExpr {
        @Override public String toString() { return "#[" + elemSpec + "]"; }
    }

    /**
     * Set spec: {@code #{Str}}. Validates all elements match the element spec.
     */
    record SetSpec(SpecExpr elemSpec) implements SpecExpr {
        @Override public String toString() { return "#{" + elemSpec + "}"; }
    }

    /**
     * Tuple spec: {@code #(Int Str Bool)}. Validates positional element specs.
     */
    record TupleSpec(List<SpecExpr> elemSpecs) implements SpecExpr {
        @Override public String toString() {
            var sb = new StringBuilder("#(");
            for (int i = 0; i < elemSpecs.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(elemSpecs.get(i));
            }
            return sb.append(')').toString();
        }
    }

    // ── Utility methods ─────────────────────────────────────────────────

    /**
     * Returns true if this spec expression contains only concrete (validatable) specs.
     * False if any type variables appear — those are documentation only.
     */
    default boolean isConcrete() {
        return switch (this) {
            case Name n -> true;
            case Wildcard w -> false;
            case Var v -> false;
            case Unit u -> true;
            case App a -> a.args().stream().allMatch(SpecExpr::isConcrete);
            case Arrow a -> a.inputs().stream().allMatch(SpecExpr::isConcrete) && a.output().isConcrete();
            case Enum e -> true;
            case VecSpec v -> v.elemSpec().isConcrete();
            case SetSpec s -> s.elemSpec().isConcrete();
            case TupleSpec t -> t.elemSpecs().stream().allMatch(SpecExpr::isConcrete);
        };
    }
}
