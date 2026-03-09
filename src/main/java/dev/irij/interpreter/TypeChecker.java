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
    private final List<String> errors = new ArrayList<>();

    /** Known function arities (name → param count, -1 for variadic) */
    private final Map<String, Integer> functionArities = new LinkedHashMap<>();

    /** type name → list of constructor names (for exhaustiveness checking) */
    private final Map<String, List<String>> typeVariants = new LinkedHashMap<>();
    /** constructor name → type name (reverse lookup) */
    private final Map<String, String> constructorToType = new LinkedHashMap<>();

    /** effect operation name → effect name (for effect row validation) */
    private final Map<String, String> effectOps = new LinkedHashMap<>();

    private PurityMode purityMode = PurityMode.STRICT;

    /** Set the purity enforcement mode. */
    public void setPurityMode(PurityMode mode) {
        this.purityMode = mode;
    }

    /** Get any purity errors (only populated in STRICT mode). */
    public List<String> getErrors() {
        return errors;
    }

    /**
     * Run the type checker on a compilation unit.
     * Returns a list of warning messages.
     */
    public List<String> check(IrijParser.CompilationUnitContext cu) {
        // Collect declarations for checking
        for (var decl : cu.topLevelDecl()) {
            if (decl.fnDecl() != null) {
                collectFnArity(decl.fnDecl());
            }
            if (decl.typeDecl() != null) {
                collectTypeVariants(decl.typeDecl());
            }
            if (decl.effectDecl() != null) {
                collectEffectOps(decl.effectDecl());
            }
        }

        // Check each function body
        for (var decl : cu.topLevelDecl()) {
            if (decl.fnDecl() != null) {
                checkFnBody(decl.fnDecl());
                checkEffectRows(decl.fnDecl());
            }
        }

        // Purity check: detect direct IO usage outside handler bodies
        if (purityMode != PurityMode.ALLOW) {
            for (var decl : cu.topLevelDecl()) {
                if (decl.fnDecl() != null) {
                    checkPurity(decl.fnDecl());
                }
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
                if (ctx.matchExpr() != null) {
                    checkMatchExhaustiveness(ctx.matchExpr());
                    yield IrijType.UNKNOWN;
                }
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

    // ── Exhaustiveness checking ────────────────────────────────────────

    private void collectTypeVariants(IrijParser.TypeDeclContext typeDecl) {
        if (typeDecl.typeBody() == null) return;
        String typeName = typeDecl.typeName().getText();
        var variants = new ArrayList<String>();
        for (var v : typeDecl.typeBody().typeVariant()) {
            String ctorName = v.UPPER_ID().getText();
            variants.add(ctorName);
            constructorToType.put(ctorName, typeName);
        }
        if (!variants.isEmpty()) {
            typeVariants.put(typeName, variants);
        }
    }

    private void checkMatchExhaustiveness(IrijParser.MatchExprContext matchCtx) {
        var arms = matchCtx.matchArms().matchArm();

        // 1. If any unguarded arm has wildcard or variable catch-all → exhaustive
        for (var arm : arms) {
            if (arm.guard() != null) continue;
            var pat = arm.pattern();
            if (pat.UNDERSCORE() != null) return;
            if (pat.LOWER_ID() != null && pat.UPPER_ID() == null
                    && pat.literal() == null) return;
        }

        // 2. Collect constructor names from unguarded arms
        var coveredCtors = new LinkedHashSet<String>();
        for (var arm : arms) {
            if (arm.guard() != null) continue;
            var pat = arm.pattern();
            if (pat.UPPER_ID() != null) {
                coveredCtors.add(pat.UPPER_ID().getText());
            }
        }

        // 3. Check constructor coverage against type declaration
        if (!coveredCtors.isEmpty()) {
            String firstCtor = coveredCtors.iterator().next();
            String typeName = constructorToType.get(firstCtor);
            if (typeName != null) {
                var allVariants = typeVariants.get(typeName);
                if (allVariants != null) {
                    var missing = new ArrayList<>(allVariants);
                    missing.removeAll(coveredCtors);
                    if (!missing.isEmpty()) {
                        warn(matchCtx, "Non-exhaustive match: missing %s of type %s",
                                String.join(", ", missing), typeName);
                    }
                }
            }
        }

        // 4. Boolean exhaustiveness
        boolean hasTrue = false, hasFalse = false;
        for (var arm : arms) {
            if (arm.guard() != null) continue;
            var pat = arm.pattern();
            if (pat.LOWER_ID() != null) {
                if ("true".equals(pat.LOWER_ID().getText())) hasTrue = true;
                if ("false".equals(pat.LOWER_ID().getText())) hasFalse = true;
            }
        }
        if ((hasTrue || hasFalse) && !(hasTrue && hasFalse)) {
            warn(matchCtx, "Non-exhaustive boolean match: missing %s",
                    hasTrue ? "false" : "true");
        }
    }

    // ── Effect row validation ──────────────────────────────────────────

    private void collectEffectOps(IrijParser.EffectDeclContext effectDecl) {
        String effectName = effectDecl.typeName().getText();
        for (var op : effectDecl.effectBody().effectOp()) {
            effectOps.put(op.LOWER_ID().getText(), effectName);
        }
    }

    private Set<String> declaredEffects(IrijParser.FnDeclContext fn) {
        var result = new LinkedHashSet<String>();
        if (fn.typeAnnotation() == null) return result;
        collectEffectsFromTypeExpr(fn.typeAnnotation().typeExpr(), result);
        return result;
    }

    private void collectEffectsFromTypeExpr(IrijParser.TypeExprContext typeExpr, Set<String> effects) {
        if (typeExpr == null) return;
        if (typeExpr.effectArrow() != null) {
            for (var effectType : typeExpr.effectArrow().typeExpr()) {
                effects.add(effectType.getText());
            }
        }
        for (var child : typeExpr.typeExpr()) {
            collectEffectsFromTypeExpr(child, effects);
        }
    }

    private void checkEffectRows(IrijParser.FnDeclContext fn) {
        var declared = declaredEffects(fn);
        if (declared.isEmpty() && fn.typeAnnotation() == null) return;
        String fnName = fn.fnName().getText();
        var body = fn.fnBody();
        if (body != null) {
            scanForUndeclaredEffects(body, fnName, declared);
        }
    }

    private void scanForUndeclaredEffects(ParseTree node, String fnName, Set<String> declared) {
        if (node == null) return;
        // Skip with-blocks — effect operations inside are handled by the handler
        if (node instanceof IrijParser.WithExprContext) return;
        if (node instanceof IrijParser.AtomExprContext atom && atom.LOWER_ID() != null) {
            String name = atom.LOWER_ID().getText();
            String effect = effectOps.get(name);
            if (effect != null && !declared.contains(effect) && !declared.contains("IO")) {
                warn(atom, "Function '%s' uses effect operation '%s' (%s) not declared in type",
                        fnName, name, effect);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            scanForUndeclaredEffects(node.getChild(i), fnName, declared);
        }
    }

    // ── Purity checking ─────────────────────────────────────────────────

    /** Direct IO paths that require effect handlers in pure code. */
    private static final List<String> IMPURE_PREFIXES = List.of(
            "io.stdout.write", "io.stderr.write", "io.stdin.read-line"
    );

    /**
     * Check a function for direct IO usage outside handler bodies.
     */
    private void checkPurity(IrijParser.FnDeclContext fn) {
        var body = fn.fnBody();
        if (body == null) return;
        String fnName = fn.fnName().getText();
        for (var stmt : body.statement()) {
            scanForImpureIO(stmt, fnName, false);
        }
    }

    /**
     * Recursively scan a parse tree node for direct IO usage.
     * @param inHandler true if we're inside a handler/with body (IO is allowed there)
     */
    private void scanForImpureIO(ParseTree node, String fnName, boolean inHandler) {
        if (node == null) return;

        // Inside a with-block's handler body, direct IO is permitted
        if (node instanceof IrijParser.WithExprContext withCtx) {
            // The handler body (first expr) is pure context
            // The wrapped expression (second expr) runs under the handler — allow IO there
            if (withCtx.expr().size() >= 2) {
                // Scan handler expression in current purity context
                scanForImpureIO(withCtx.expr(0), fnName, inHandler);
                // The body under the handler is allowed to do IO
                scanForImpureIO(withCtx.expr(1), fnName, true);
            }
            return; // don't recurse further, we handled children
        }

        // Check postfix expressions for io.* paths
        if (node instanceof IrijParser.PostfixExprContext postfix && !inHandler) {
            String text = postfix.getText();
            for (String prefix : IMPURE_PREFIXES) {
                if (text.startsWith(prefix)) {
                    reportImpure(postfix, fnName, prefix);
                    break;
                }
            }
        }

        // Recurse into children
        for (int i = 0; i < node.getChildCount(); i++) {
            scanForImpureIO(node.getChild(i), fnName, inHandler);
        }
    }

    private void reportImpure(ParseTree ctx, String fnName, String ioPath) {
        String msg = String.format(
                "Direct IO call '%s' in function '%s' — use an effect handler instead",
                ioPath, fnName);
        var token = ctx instanceof org.antlr.v4.runtime.ParserRuleContext prc
                ? prc.getStart() : null;
        if (purityMode == PurityMode.STRICT) {
            if (token != null) {
                errors.add(String.format("Purity error [line %d:%d]: %s",
                        token.getLine(), token.getCharPositionInLine(), msg));
            } else {
                errors.add("Purity error: " + msg);
            }
        } else {
            // WARN mode
            if (token != null) {
                warnings.add(String.format("Purity warning [line %d:%d]: %s",
                        token.getLine(), token.getCharPositionInLine(), msg));
            } else {
                warnings.add("Purity warning: " + msg);
            }
        }
    }
}
