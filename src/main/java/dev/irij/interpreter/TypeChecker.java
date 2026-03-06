package dev.irij.interpreter;

import dev.irij.parser.IrijParser;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.*;

/**
 * Warning-only type checker for Irij.
 *
 * <p>Runs before interpretation and prints warnings for likely type errors.
 * Does not block execution — aligns with the spec's "verification is a dial" philosophy.
 *
 * <p>Currently checks:
 * <ul>
 *   <li>Type mismatches in arithmetic operators (e.g., {@code "hello" + 3})
 *   <li>Function argument count mismatches
 *   <li>Basic type inference for literals and expressions
 * </ul>
 */
public final class TypeChecker {

    /** Inferred types for expressions */
    enum IrijType {
        INT, FLOAT, STR, BOOL, VEC, MAP, SET, UNIT, TAGGED, FUNCTION, UNKNOWN;

        @Override
        public String toString() {
            return switch (this) {
                case INT -> "Int";
                case FLOAT -> "Float";
                case STR -> "Str";
                case BOOL -> "Bool";
                case VEC -> "Vec";
                case MAP -> "Map";
                case SET -> "Set";
                case UNIT -> "()";
                case TAGGED -> "Tagged";
                case FUNCTION -> "Fn";
                case UNKNOWN -> "?";
            };
        }

        boolean isNumeric() {
            return this == INT || this == FLOAT;
        }
    }

    private final List<String> warnings = new ArrayList<>();

    /** Known function arities (name → param count, -1 for variadic) */
    private final Map<String, Integer> functionArities = new LinkedHashMap<>();

    /**
     * Run the type checker on a compilation unit.
     * Returns a list of warning messages.
     */
    public List<String> check(IrijParser.CompilationUnitContext cu) {
        // Collect function declarations for arity checking
        for (var decl : cu.topLevelDecl()) {
            if (decl.fnDecl() != null) {
                collectFnArity(decl.fnDecl());
            }
        }

        // Check each function body
        for (var decl : cu.topLevelDecl()) {
            if (decl.fnDecl() != null) {
                checkFnBody(decl.fnDecl());
            }
        }

        return warnings;
    }

    private void collectFnArity(IrijParser.FnDeclContext fn) {
        String name = fn.fnName().getText();
        // Try to infer arity from type annotation
        if (fn.typeAnnotation() != null) {
            int arity = countArrowParams(fn.typeAnnotation().typeExpr());
            functionArities.put(name, arity);
        }
    }

    /**
     * Count the number of parameters in a function type expression.
     * {@code A -> B -> C} has 2 params, {@code () -> A} has 0 real params.
     */
    private int countArrowParams(IrijParser.TypeExprContext typeExpr) {
        if (typeExpr == null) return -1;
        // typeExpr: typeExpr ARROW typeExpr | typeExpr effectArrow typeExpr | typeAtom
        if (typeExpr.ARROW() != null || typeExpr.effectArrow() != null) {
            return 1 + countArrowParams(typeExpr.typeExpr(1));
        }
        return 0;
    }

    private void checkFnBody(IrijParser.FnDeclContext fn) {
        var body = fn.fnBody();
        if (body == null) return;
        for (var stmt : body.statement()) {
            if (stmt.expr() != null) {
                inferType(stmt.expr());
            }
            if (stmt.binding() != null && stmt.binding().expr() != null) {
                inferType(stmt.binding().expr());
            }
        }
    }

    /**
     * Infer the type of an expression and emit warnings for type mismatches.
     */
    IrijType inferType(ParseTree node) {
        if (node == null) return IrijType.UNKNOWN;

        return switch (node) {
            case IrijParser.ExprContext ctx -> {
                if (ctx.pipeExpr() != null) yield inferType(ctx.pipeExpr());
                if (ctx.ifExpr() != null) yield inferType(ctx.ifExpr());
                if (ctx.matchExpr() != null) yield IrijType.UNKNOWN;
                if (ctx.doExpr() != null) yield IrijType.UNKNOWN;
                if (ctx.withExpr() != null) yield IrijType.UNKNOWN;
                if (ctx.lambdaExpr() != null) yield IrijType.FUNCTION;
                yield IrijType.UNKNOWN;
            }
            case IrijParser.PipeExprContext ctx -> {
                if (ctx.composeExpr().size() == 1) yield inferType(ctx.composeExpr(0));
                yield IrijType.UNKNOWN; // pipe result depends on last function
            }
            case IrijParser.ComposeExprContext ctx -> {
                if (ctx.choreographyExpr().size() == 1) yield inferType(ctx.choreographyExpr(0));
                yield IrijType.FUNCTION;
            }
            case IrijParser.ChoreographyExprContext ctx -> inferType(ctx.orExpr());
            case IrijParser.OrExprContext ctx -> {
                if (ctx.andExpr().size() == 1) yield inferType(ctx.andExpr(0));
                yield IrijType.BOOL;
            }
            case IrijParser.AndExprContext ctx -> {
                if (ctx.eqExpr().size() == 1) yield inferType(ctx.eqExpr(0));
                yield IrijType.BOOL;
            }
            case IrijParser.EqExprContext ctx -> {
                if (ctx.compExpr().size() == 1) yield inferType(ctx.compExpr(0));
                yield IrijType.BOOL;
            }
            case IrijParser.CompExprContext ctx -> {
                if (ctx.concatExpr().size() == 1) yield inferType(ctx.concatExpr(0));
                yield IrijType.BOOL;
            }
            case IrijParser.ConcatExprContext ctx -> {
                if (ctx.rangeExpr().size() == 1) yield inferType(ctx.rangeExpr(0));
                // ++ on strings → Str, on vectors → Vec
                var leftType = inferType(ctx.rangeExpr(0));
                yield leftType;
            }
            case IrijParser.RangeExprContext ctx -> {
                if (ctx.addExpr().size() == 1) yield inferType(ctx.addExpr(0));
                yield IrijType.VEC; // ranges produce vectors
            }
            case IrijParser.AddExprContext ctx -> {
                if (ctx.mulExpr().size() == 1) yield inferType(ctx.mulExpr(0));
                var leftType = inferType(ctx.mulExpr(0));
                var rightType = inferType(ctx.mulExpr(1));
                yield checkArithmetic(leftType, rightType, ctx);
            }
            case IrijParser.MulExprContext ctx -> {
                if (ctx.powExpr().size() == 1) yield inferType(ctx.powExpr(0));
                var leftType = inferType(ctx.powExpr(0));
                var rightType = inferType(ctx.powExpr(1));
                yield checkArithmetic(leftType, rightType, ctx);
            }
            case IrijParser.PowExprContext ctx -> inferType(ctx.unaryExpr(0));
            case IrijParser.UnaryExprContext ctx -> {
                if (ctx.seqOpExpr() != null) yield inferType(ctx.seqOpExpr());
                if (ctx.NOT() != null) yield IrijType.BOOL;
                if (ctx.MINUS() != null) {
                    var inner = inferType(ctx.unaryExpr());
                    yield inner.isNumeric() ? inner : IrijType.UNKNOWN;
                }
                yield IrijType.UNKNOWN;
            }
            case IrijParser.SeqOpExprContext ctx -> {
                if (ctx.postfixExpr() != null) yield inferType(ctx.postfixExpr());
                yield IrijType.FUNCTION; // seq ops are functions
            }
            case IrijParser.PostfixExprContext ctx -> inferType(ctx.appExpr());
            case IrijParser.AppExprContext ctx -> {
                var atoms = ctx.atomExpr();
                if (atoms.size() == 1) yield inferType(atoms.get(0));
                // Function application — check arity
                String fnName = atoms.get(0).LOWER_ID() != null ?
                        atoms.get(0).LOWER_ID().getText() : null;
                if (fnName != null && functionArities.containsKey(fnName)) {
                    int expected = functionArities.get(fnName);
                    int actual = atoms.size() - 1;
                    if (expected >= 0 && actual != expected) {
                        warn(ctx, "Function '%s' expects %d argument(s) but got %d",
                                fnName, expected, actual);
                    }
                }
                yield IrijType.UNKNOWN;
            }
            case IrijParser.AtomExprContext ctx -> inferAtomType(ctx);
            case IrijParser.LiteralContext ctx -> inferLiteralType(ctx);
            case IrijParser.IfExprContext ctx -> {
                // Condition should be boolean-ish
                if (ctx.expr().size() >= 3) {
                    var thenType = inferType(ctx.expr(1));
                    var elseType = inferType(ctx.expr(2));
                    if (thenType != IrijType.UNKNOWN && elseType != IrijType.UNKNOWN
                            && thenType != elseType) {
                        warn(ctx, "If branches have different types: %s vs %s",
                                thenType, elseType);
                    }
                    yield thenType;
                }
                yield IrijType.UNKNOWN;
            }
            case IrijParser.LambdaExprContext ignored -> IrijType.FUNCTION;
            default -> {
                if (node.getChildCount() == 1) yield inferType(node.getChild(0));
                yield IrijType.UNKNOWN;
            }
        };
    }

    private IrijType inferAtomType(IrijParser.AtomExprContext ctx) {
        if (ctx.literal() != null) return inferLiteralType(ctx.literal());
        if (ctx.LOWER_ID() != null) {
            String name = ctx.LOWER_ID().getText();
            if ("true".equals(name) || "false".equals(name)) return IrijType.BOOL;
            return IrijType.UNKNOWN; // would need scope tracking
        }
        if (ctx.UPPER_ID() != null) return IrijType.TAGGED;
        if (ctx.LPAREN() != null && ctx.RPAREN() != null && ctx.expr() == null)
            return IrijType.UNIT;
        if (ctx.expr() != null) return inferType(ctx.expr());
        if (ctx.vectorLiteral() != null) return IrijType.VEC;
        if (ctx.setLiteral() != null) return IrijType.SET;
        if (ctx.mapLiteral() != null) return IrijType.MAP;
        return IrijType.UNKNOWN;
    }

    private IrijType inferLiteralType(IrijParser.LiteralContext ctx) {
        if (ctx.INT_LIT() != null) return IrijType.INT;
        if (ctx.FLOAT_LIT() != null) return IrijType.FLOAT;
        if (ctx.HEX_LIT() != null) return IrijType.INT;
        if (ctx.STRING_LIT() != null) return IrijType.STR;
        if (ctx.KEYWORD_LIT() != null) return IrijType.STR;
        return IrijType.UNKNOWN;
    }

    private IrijType checkArithmetic(IrijType left, IrijType right, ParseTree ctx) {
        if (left == IrijType.UNKNOWN || right == IrijType.UNKNOWN) {
            return left.isNumeric() ? left : (right.isNumeric() ? right : IrijType.UNKNOWN);
        }
        if (!left.isNumeric() || !right.isNumeric()) {
            if (left != right) {
                warn(ctx, "Arithmetic on incompatible types: %s and %s", left, right);
            }
        }
        if (left == IrijType.FLOAT || right == IrijType.FLOAT) return IrijType.FLOAT;
        return IrijType.INT;
    }

    private void warn(ParseTree ctx, String format, Object... args) {
        String msg = String.format(format, args);
        var token = ctx instanceof org.antlr.v4.runtime.ParserRuleContext prc
                ? prc.getStart() : null;
        if (token != null) {
            warnings.add(String.format("Warning [line %d:%d]: %s",
                    token.getLine(), token.getCharPositionInLine(), msg));
        } else {
            warnings.add("Warning: " + msg);
        }
    }
}
