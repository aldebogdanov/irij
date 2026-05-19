package dev.irij.compiler;

import dev.irij.ast.Decl;
import dev.irij.ast.Expr;
import dev.irij.ast.Node.SourceLoc;
import dev.irij.ast.SpecExpr;
import dev.irij.ast.Stmt;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compile-time effect-row subsumption check. Walks a flat
 * {@code List<Decl>} (post-module-inliner) and rejects any fn whose
 * body either:
 *
 * <ul>
 *   <li>Calls another user fn whose declared effect row contains
 *       effects not in the caller's declared row.</li>
 *   <li>Performs an effect op whose effect isn't in the caller's row.</li>
 *   <li>Touches Java interop ({@code JVM} required) outside a row
 *       containing {@code JVM}.</li>
 * </ul>
 *
 * <p>Available-set semantics:
 * <ul>
 *   <li>{@code null} or {@code Any}-containing row → ambient
 *       (everything OK). Polymorphic; runtime widens.</li>
 *   <li>Other row → exactly the declared effects.</li>
 *   <li>{@code with X body} → caller's set + X's effect for body's
 *       lexical extent.</li>
 * </ul>
 *
 * <p>Lambda literals inherit the enclosing fn's row.
 *
 * <p>Top-level decls run under ambient — no caller to constrain them.
 *
 * <p>Failures throw {@link IrijCompiler.CompileException} with the
 * source location of the offending call site.
 */
public final class EffectRowChecker {

    private final Map<String, List<String>> fnRows = new HashMap<>();
    private final Map<String, String> effectOps = new HashMap<>();
    private final Map<String, String> handlerEffect = new HashMap<>();
    /** Set of declared effect names (from EffectDecl). Used to detect
     *  capability binds: `rnd :: Random := java.util.Random/new 42`
     *  binds `rnd` carrying the Random capability if Random is in this set. */
    private final java.util.Set<String> effectNames = new java.util.HashSet<>();

    /** Local-var-name → capability-effect map for the fn body currently
     *  being walked. Populated by walking Stmt.Bind with an effect-name
     *  spec annotation. Consulted at DotAccess sites to require the
     *  capability in the caller's effect row. */
    private java.util.Map<String, String> varCap = new java.util.HashMap<>();

    public static void check(List<Decl> decls) {
        new EffectRowChecker().run(decls);
    }

    private void run(List<Decl> decls) {
        for (Decl d : decls) {
            Object inner = (d instanceof Decl.PubDecl pd) ? pd.inner() : d;
            if (inner instanceof Decl.FnDecl fn) {
                fnRows.put(fn.name(), fn.effectRow());
            } else if (inner instanceof Decl.EffectDecl ed) {
                effectNames.add(ed.name());
                for (var op : ed.ops()) effectOps.put(op.name(), ed.name());
            } else if (inner instanceof Decl.HandlerDecl hd) {
                handlerEffect.put(hd.name(), hd.effectName());
            }
        }
        for (Decl d : decls) {
            Object inner = (d instanceof Decl.PubDecl pd) ? pd.inner() : d;
            if (inner instanceof Decl.FnDecl fn) {
                checkFn(fn);
            } else if (inner instanceof Decl.HandlerDecl hd) {
                checkHandler(hd);
            }
        }
    }

    private void checkFn(Decl.FnDecl fn) {
        Set<String> avail = available(fn.effectRow());
        String ctx = "fn " + fn.name();
        // Reset per-fn local-var-capability map. Each fn has its own
        // scope; bindings don't leak between fns.
        varCap = new java.util.HashMap<>();
        switch (fn.body()) {
            case Decl.FnBody.LambdaBody lb -> walkExpr(lb.body(), avail, ctx);
            case Decl.FnBody.MatchArmsBody mab -> {
                for (var arm : mab.arms()) {
                    if (arm.guard() != null) walkExpr(arm.guard(), avail, ctx);
                    walkExpr(arm.body(), avail, ctx);
                }
            }
            case Decl.FnBody.ImperativeBody ib -> walkStmts(ib.stmts(), avail, ctx);
            default -> {}
        }
    }

    private void checkHandler(Decl.HandlerDecl hd) {
        List<String> declared = hd.requiredEffects() != null
                ? hd.requiredEffects() : List.of();
        Set<String> avail = available(declared);
        // Add the handler's own effect (resume etc. operate within it).
        Set<String> inner = avail == AMBIENT ? AMBIENT : new HashSet<>(avail);
        if (inner != AMBIENT) inner.add(hd.effectName());
        String ctx = "handler " + hd.name();
        for (var s : hd.stateBindings()) walkStmt(s, inner, ctx);
        for (var c : hd.clauses()) {
            walkExpr(c.body(), inner, ctx + " clause " + c.opName());
        }
    }

    /** Convert a declared row into the available-set for that fn's body.
     *
     *  - {@code null} declared → empty set (the fn is unannotated; the
     *    contract is "pure" so the body sees no effects available).
     *    Matches interpreter semantics: `fn.effectRow() != null ? ... :
     *    List.<String>of()`.
     *  - Row containing {@code "Any"} → ambient (everything OK);
     *    polymorphism marker, runtime widens.
     *  - Otherwise → exactly the listed effects.
     */
    private Set<String> available(List<String> declared) {
        if (declared == null) return new HashSet<>();
        if (declared.contains("Any")) return AMBIENT;
        return new HashSet<>(declared);
    }

    /** Identity-comparable ambient sentinel — `contains` always true. */
    private static final Set<String> AMBIENT = new HashSet<>() {
        @Override public boolean contains(Object o) { return true; }
    };

    // ── Walkers ─────────────────────────────────────────────────────

    private void walkStmts(List<Stmt> stmts, Set<String> avail, String ctx) {
        for (Stmt s : stmts) walkStmt(s, avail, ctx);
    }

    private void walkStmt(Stmt s, Set<String> avail, String ctx) {
        switch (s) {
            case Stmt.ExprStmt es -> walkExpr(es.expr(), avail, ctx);
            case Stmt.Bind b -> {
                walkExpr(b.value(), avail, ctx);
                recordCapability(b);
            }
            case Stmt.MutBind mb -> walkExpr(mb.value(), avail, ctx);
            case Stmt.Assign a -> walkExpr(a.value(), avail, ctx);
            case Stmt.IfStmt ifs -> {
                walkExpr(ifs.cond(), avail, ctx);
                walkStmts(ifs.thenBranch(), avail, ctx);
                if (ifs.elseBranch() != null) walkStmts(ifs.elseBranch(), avail, ctx);
            }
            case Stmt.MatchStmt ms -> {
                walkExpr(ms.scrutinee(), avail, ctx);
                for (var arm : ms.arms()) {
                    if (arm.guard() != null) walkExpr(arm.guard(), avail, ctx);
                    walkExpr(arm.body(), avail, ctx);
                }
            }
            case Stmt.With w -> {
                walkExpr(w.handler(), avail, ctx);
                // Collect every effect the handler expression provides
                // (single handler Var, or `>>`-composition of multiple).
                Set<String> added = new HashSet<>();
                boolean opaque = !collectHandlerEffects(w.handler(), added);
                Set<String> inner;
                if (avail == AMBIENT) {
                    inner = avail;
                } else if (opaque) {
                    // Handler shape we can't analyse statically (local
                    // var bound to a compose, fn returning a handler,
                    // etc.) — escape to AMBIENT inside body. Runtime
                    // check is the safety net.
                    inner = AMBIENT;
                } else if (added.isEmpty()) {
                    inner = avail;
                } else {
                    inner = new HashSet<>(avail);
                    inner.addAll(added);
                }
                walkStmts(w.body(), inner, ctx);
                if (w.onFailure() != null) walkStmts(w.onFailure(), inner, ctx);
            }
            default -> {}
        }
    }

    private void walkExpr(Expr e, Set<String> avail, String ctx) {
        if (e == null) return;
        switch (e) {
            case Expr.App app -> {
                if (app.fn() instanceof Expr.Var v && effectOps.containsKey(v.name())) {
                    String eff = effectOps.get(v.name());
                    requireEffect(eff, "perform '" + v.name() + "'", ctx, avail, app.loc());
                    for (Expr a : app.args()) walkExpr(a, avail, ctx);
                    return;
                }
                if (app.fn() instanceof Expr.Var v && fnRows.containsKey(v.name())) {
                    requireRow(fnRows.get(v.name()), "call to " + v.name(),
                            ctx, avail, app.loc());
                }
                walkExpr(app.fn(), avail, ctx);
                for (Expr a : app.args()) walkExpr(a, avail, ctx);
            }
            case Expr.JavaRef jr ->
                    requireEffect("JVM", "java-interop:" + jr.ref(), ctx, avail, jr.loc());
            case Expr.DotAccess da -> {
                // Per-ref capability: if the receiver is a local var
                // whose Bind spec named a declared effect, calling a
                // method/field on it requires that effect — even if the
                // caller has JVM. This lets effect rows track which
                // declared resource the call touches.
                if (da.target() instanceof Expr.Var v && varCap.containsKey(v.name())) {
                    String eff = varCap.get(v.name());
                    requireEffect(eff, "method '" + da.field() + "' on " + v.name(),
                            ctx, avail, da.loc());
                }
                walkExpr(da.target(), avail, ctx);
            }
            case Expr.IfExpr ie -> {
                walkExpr(ie.cond(), avail, ctx);
                walkExpr(ie.thenBranch(), avail, ctx);
                walkExpr(ie.elseBranch(), avail, ctx);
            }
            case Expr.MatchExpr me -> {
                walkExpr(me.scrutinee(), avail, ctx);
                for (var arm : me.arms()) {
                    if (arm.guard() != null) walkExpr(arm.guard(), avail, ctx);
                    walkExpr(arm.body(), avail, ctx);
                }
            }
            case Expr.BinaryOp bo -> { walkExpr(bo.left(), avail, ctx); walkExpr(bo.right(), avail, ctx); }
            case Expr.UnaryOp uo -> walkExpr(uo.operand(), avail, ctx);
            case Expr.Block blk -> walkStmts(blk.stmts(), avail, ctx);
            case Expr.Lambda lam -> walkExpr(lam.body(), avail, ctx);
            case Expr.VectorLit vl -> { for (Expr x : vl.elements()) walkExpr(x, avail, ctx); }
            case Expr.SetLit sl -> { for (Expr x : sl.elements()) walkExpr(x, avail, ctx); }
            case Expr.TupleLit tl -> { for (Expr x : tl.elements()) walkExpr(x, avail, ctx); }
            case Expr.MapLit ml -> {
                for (var me : ml.entries()) {
                    if (me instanceof Expr.MapEntry.Field f) walkExpr(f.value(), avail, ctx);
                }
            }
            case Expr.Compose c -> { walkExpr(c.left(), avail, ctx); walkExpr(c.right(), avail, ctx); }
            default -> {}
        }
    }

    // ── Checks ──────────────────────────────────────────────────────

    private void requireEffect(String effect, String opName, String inCtx,
                                Set<String> avail, SourceLoc loc) {
        if (effect == null) return;
        if ("Any".equals(effect)) return;
        if (!avail.contains(effect)) {
            throw new IrijCompiler.CompileException(
                    "Effect '" + effect + "' not declared in " + inCtx
                            + ": '" + opName + "' requires ::: " + effect
                            + " in enclosing function's effect row"
                            + (loc != null ? " at " + loc.line() + ":" + loc.col() : ""));
        }
    }

    private void requireRow(List<String> calleeRow, String opName, String inCtx,
                             Set<String> avail, SourceLoc loc) {
        if (calleeRow == null || calleeRow.isEmpty()) return;
        if (calleeRow.contains("Any")) return;
        for (String e : calleeRow) requireEffect(e, opName, inCtx, avail, loc);
    }

    /** Inspect a Stmt.Bind: if its spec annotation names a declared
     *  effect (from an EffectDecl), record the bound variable as
     *  carrying that capability. Later DotAccess sites whose receiver
     *  is this variable require the effect in the caller's row. */
    private void recordCapability(Stmt.Bind b) {
        if (b.specAnnotation() == null) return;
        if (!(b.specAnnotation() instanceof SpecExpr.Name n)) return;
        if (!effectNames.contains(n.name())) return;
        if (!(b.target() instanceof Stmt.BindTarget.Simple s)) return;
        varCap.put(s.name(), n.name());
    }

    private Set<String> merge(Set<String> base, String extra) {
        Set<String> out = new HashSet<>(base);
        out.add(extra);
        return out;
    }

    /** Collect every effect-name provided by a `with` handler
     *  expression. Supports single Var (top-level handler decl) and
     *  `>>`-composed handlers. Returns true if the analysis was
     *  COMPLETE (the handler's effects are fully known); false if any
     *  part of the expression is opaque (local binding to a handler,
     *  fn call returning a handler, etc.). The caller treats opaque
     *  handlers as AMBIENT inside the with body. */
    private boolean collectHandlerEffects(Expr handler, Set<String> out) {
        if (handler instanceof Expr.Var v) {
            String e = handlerEffect.get(v.name());
            if (e != null) {
                out.add(e);
                return true;
            }
            return false; // local var or unknown — opaque
        }
        if (handler instanceof Expr.Compose c) {
            return collectHandlerEffects(c.left(), out)
                    & collectHandlerEffects(c.right(), out);
        }
        if (handler instanceof Expr.BinaryOp bo && ">>".equals(bo.op())) {
            return collectHandlerEffects(bo.left(), out)
                    & collectHandlerEffects(bo.right(), out);
        }
        return false; // App, Block, lambda, etc. — opaque
    }
}
