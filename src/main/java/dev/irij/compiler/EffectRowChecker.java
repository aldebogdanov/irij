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
    /** fn-name → spec annotations (inputs..., output). Used at call
     *  sites to find which input positions carry parametric row-var
     *  bindings (e.g. the {@code (_ -> _):eff} in fold's signature). */
    private final Map<String, List<SpecExpr>> fnSpecs = new HashMap<>();
    private final Map<String, String> effectOps = new HashMap<>();
    private final Map<String, String> handlerEffect = new HashMap<>();
    /** Set of declared effect names (from EffectDecl). Used to detect
     *  capability binds: `rnd :: Random := java.util.Random/new 42`
     *  binds `rnd` carrying the Random capability if Random is in this set. */
    private final java.util.Set<String> effectNames = new java.util.HashSet<>();

    /** Builtin name → effect required to call it. Mirrors the
     *  effectRow metadata each BuiltinFn carries in Interpreter / Builtins
     *  Java code. The static checker needs this registry because
     *  builtins aren't user fns (so they're not in {@link #fnRows})
     *  and aren't effect ops (not in {@link #effectOps}), yet they
     *  do require an effect in the caller's row at runtime.
     *
     *  <p>Single-effect builtins for now. If a builtin requires
     *  multiple effects, extend the map's value type. */
    private static final java.util.Map<String, String> BUILTIN_EFFECTS;
    static {
        var m = new java.util.HashMap<String, String>();
        // Console — stdout/stderr/dbg
        for (String n : java.util.List.of("println", "print", "dbg", "read-line")) {
            m.put(n, "Console");
        }
        // Time
        for (String n : java.util.List.of("sleep", "now-ms")) m.put(n, "Time");
        // FileIO — read/write/list/delete/make-dir/append-file/file-exists?
        for (String n : java.util.List.of(
                "read-file", "write-file", "make-dir", "list-dir",
                "delete-file", "append-file", "file-exists?",
                "raw-multipart-save")) {
            m.put(n, "FileIO");
        }
        // Env
        for (String n : java.util.List.of("get-env", "env")) m.put(n, "Env");
        // Http (client)
        for (String n : java.util.List.of("raw-http-request")) m.put(n, "Http");
        // Db
        for (String n : java.util.List.of(
                "raw-db-open", "raw-db-close", "raw-db-query",
                "raw-db-exec", "raw-db-transaction")) {
            m.put(n, "Db");
        }
        // Serve (HTTP server + SSE)
        for (String n : java.util.List.of(
                "raw-http-serve", "raw-sse-response", "raw-sse-send",
                "raw-sse-close", "raw-sse-closed?")) {
            m.put(n, "Serve");
        }
        // Session (Playground sandbox)
        for (String n : java.util.List.of(
                "raw-session-create", "raw-session-eval", "raw-session-destroy",
                "raw-session-subscribe", "raw-session-unsubscribe",
                "raw-session-cleanup")) {
            m.put(n, "Session");
        }
        BUILTIN_EFFECTS = java.util.Map.copyOf(m);
    }

    /** Local-var-name → capability-effect map for the fn body currently
     *  being walked. Populated by walking Stmt.Bind with an effect-name
     *  spec annotation. Consulted at DotAccess sites to require the
     *  capability in the caller's effect row. */
    private java.util.Map<String, String> varCap = new java.util.HashMap<>();

    public static void check(List<Decl> decls) {
        new EffectRowChecker().run(decls);
    }

    private void run(List<Decl> decls) {
        // Module-track in pass 1: each fn gets stamped with the
        // qualified module name from the most-recent preceding
        // ModDecl. Required for the stdlib-only `::: Any` escape
        // hatch (Phase 5).
        String currentMod = null;
        for (Decl d : decls) {
            if (d instanceof Decl.ModDecl md) {
                currentMod = md.qualifiedName();
                continue;
            }
            Object inner = (d instanceof Decl.PubDecl pd) ? pd.inner() : d;
            if (inner instanceof Decl.FnDecl fn) {
                fnRows.put(fn.name(), fn.effectRow());
                fnModule.put(fn.name(), currentMod);
                if (fn.specAnnotations() != null) {
                    fnSpecs.put(fn.name(), fn.specAnnotations());
                }
            } else if (inner instanceof Decl.EffectDecl ed) {
                effectNames.add(ed.name());
                for (var op : ed.ops()) effectOps.put(op.name(), ed.name());
            } else if (inner instanceof Decl.HandlerDecl hd) {
                handlerEffect.put(hd.name(), hd.effectName());
            }
        }
        // Phase 5: reject `::: Any` in user code (non-stdlib modules).
        // Stdlib keeps it as the transitional escape hatch for
        // dispatch through opaque records (e.g. std.serve router).
        for (var e : fnRows.entrySet()) {
            List<String> row = e.getValue();
            if (row == null) continue;
            if (!row.contains("Any")) continue;
            String mod = fnModule.get(e.getKey());
            if (mod == null || !mod.startsWith("std.")) {
                throw new IrijCompiler.CompileException(
                        "`::: Any` is no longer allowed in user code "
                                + "(use a parametric row variable like "
                                + "`:eff` / `::: eff` instead). "
                                + "Fn: '" + e.getKey() + "'"
                                + (mod != null ? " in module '" + mod + "'" : ""));
            }
        }
        for (Decl d : decls) {
            if (d instanceof Decl.ModDecl) continue;
            Object inner = (d instanceof Decl.PubDecl pd) ? pd.inner() : d;
            if (inner instanceof Decl.FnDecl fn) {
                checkFn(fn);
            } else if (inner instanceof Decl.HandlerDecl hd) {
                checkHandler(hd);
            }
        }
    }

    /** fn-name → originating module qualified name. Used for the
     *  stdlib-only `::: Any` allowance. {@code null} means the fn
     *  lives in the program's top-level script (no `mod` decl). */
    private final Map<String, String> fnModule = new HashMap<>();

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
     *  - Row containing {@code "Any"} → ambient (legacy polymorphism
     *    marker; being replaced by parametric row variables).
     *  - Row containing a parametric row-variable (lowercase entry
     *    like {@code eff}) → ambient. The body can't be checked
     *    precisely because the variable's binding is only known at
     *    each call site. Precision lives at the call-site
     *    substitution.
     *  - Otherwise → exactly the listed effects.
     */
    private Set<String> available(List<String> declared) {
        if (declared == null) return new HashSet<>();
        if (declared.contains("Any")) return AMBIENT;
        for (String e : declared) {
            if (isRowVar(e)) return AMBIENT;
        }
        return new HashSet<>(declared);
    }

    /** Lowercase first character = parametric row-variable. */
    private static boolean isRowVar(String name) {
        return name != null && !name.isEmpty()
                && Character.isLowerCase(name.charAt(0));
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
                // Builtin call-site enforcement: `println`, `read-file`,
                // `sleep`, … each carry a declared effect (see
                // {@link #BUILTIN_EFFECTS}); the caller's row must include
                // it. Symmetric with the user-effect-op check above —
                // catches `pure fn calls println` at compile time
                // instead of waiting for the runtime PerformSignal.
                if (app.fn() instanceof Expr.Var bv && BUILTIN_EFFECTS.containsKey(bv.name())) {
                    String builtinEff = BUILTIN_EFFECTS.get(bv.name());
                    requireEffect(builtinEff, "'" + bv.name() + "'", ctx, avail, app.loc());
                    for (Expr a : app.args()) walkExpr(a, avail, ctx);
                    return;
                }
                if (app.fn() instanceof Expr.Var v && fnRows.containsKey(v.name())) {
                    List<String> calleeRow = fnRows.get(v.name());
                    if (hasRowVar(calleeRow)) {
                        // Parametric — substitute from actual arg
                        // effects then check.
                        checkParametricCall(v.name(), calleeRow, app.args(),
                                ctx, avail, app.loc());
                    } else {
                        requireRow(calleeRow, "call to " + v.name(),
                                ctx, avail, app.loc());
                    }
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

    /** True if any entry in {@code row} is a parametric row-variable
     *  (lowercase first letter). */
    private static boolean hasRowVar(List<String> row) {
        if (row == null) return false;
        for (String e : row) if (isRowVar(e)) return true;
        return false;
    }

    /** Parametric call-site check. Walks the callee's spec annotations
     *  to find row-variable bindings on input positions, computes the
     *  effects of each corresponding actual argument expression,
     *  unions them into the bindings, then substitutes into the
     *  callee's declared row to get the concrete effective row.
     *  Finally checks every concrete effect in that row against the
     *  caller's available set, with a chain-tracked error message on
     *  failure: "row-var `eff` bound to {Log} at arg position N". */
    private void checkParametricCall(String fnName, List<String> calleeRow,
                                      List<Expr> args, String ctx,
                                      Set<String> callerAvail, SourceLoc loc) {
        List<SpecExpr> specs = fnSpecs.get(fnName);
        Map<String, Set<String>> bindings = new HashMap<>();
        Map<String, Integer> bindingArgIdx = new HashMap<>();
        if (specs != null) {
            // Last spec is the return spec; earlier are inputs in order.
            int inputCount = specs.size() - 1;
            for (int i = 0; i < inputCount && i < args.size(); i++) {
                String rv = rowVarOf(specs.get(i));
                if (rv == null) continue;
                Set<String> argEffects = collectExprEffects(args.get(i));
                bindings.merge(rv, argEffects, (a, b) -> {
                    Set<String> u = new HashSet<>(a);
                    u.addAll(b);
                    return u;
                });
                bindingArgIdx.putIfAbsent(rv, i);
            }
        }
        // Substitute the callee's row using the collected bindings.
        Set<String> effective = new HashSet<>();
        for (String entry : calleeRow) {
            if (isRowVar(entry)) {
                Set<String> bound = bindings.get(entry);
                if (bound != null) effective.addAll(bound);
                // else: unbound — the callee declared `::: eff` but no
                // arg position carries that variable. Skip (defensive).
            } else {
                effective.add(entry);
            }
        }
        // Verify caller's avail ⊇ effective.
        for (String eff : effective) {
            if (callerAvail.contains(eff)) continue;
            throw new IrijCompiler.CompileException(
                    formatChainError(eff, fnName, ctx, bindings, bindingArgIdx, loc));
        }
    }

    /** Extract the row-var name from a function-shaped spec, if any. */
    private static String rowVarOf(SpecExpr s) {
        return switch (s) {
            case SpecExpr.Arrow a -> a.rowVar();
            case SpecExpr.App a -> a.rowVar();
            default -> null;
        };
    }

    /** Render a multi-line error chain that points at the row-var
     *  binding site, the propagation point, and the enclosing fn
     *  that's missing the effect. Format mirrors what an IDE / LSP
     *  would surface as inline diagnostics.
     *
     *  Example output:
     *
     *  <pre>
     *  Effect 'Console' not declared in fn `process`
     *    at 4:9
     *    propagated to call 'fold' via row-variable `eff`,
     *    bound from arg 0 (the callback's effect row = {Console})
     *  Fix: add ::: Console to fn `process`, or pass a callback
     *       that doesn't perform Console, or wrap the call in a
     *       `with` for a Console-handling handler.
     *  </pre>
     */
    private static String formatChainError(String eff, String fnName, String ctx,
                                            Map<String, Set<String>> bindings,
                                            Map<String, Integer> argIdx,
                                            SourceLoc loc) {
        // Find which row-var contributed `eff`, and which arg bound it.
        String rowVar = null;
        Integer idx = null;
        Set<String> rowBinding = null;
        for (var e : bindings.entrySet()) {
            if (e.getValue().contains(eff)) {
                rowVar = e.getKey();
                idx = argIdx.get(rowVar);
                rowBinding = e.getValue();
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Effect '").append(eff).append("' not declared in ")
                .append(ctx).append('\n');
        if (loc != null) {
            sb.append("  at ").append(loc.line()).append(':').append(loc.col()).append('\n');
        }
        if (rowVar != null) {
            sb.append("  propagated to call '").append(fnName)
                    .append("' via row-variable `").append(rowVar).append("`,\n");
            if (idx != null) {
                sb.append("  bound from arg ").append(idx);
                if (rowBinding != null && !rowBinding.isEmpty()) {
                    sb.append(" (callback's effect row = ").append(rowBinding).append(")");
                }
                sb.append('\n');
            }
        } else {
            sb.append("  via call to '").append(fnName).append("'\n");
        }
        sb.append("Fix: add ::: ").append(eff).append(" to ")
                .append(ctx)
                .append(", or pass a callback without ::: ")
                .append(eff)
                .append(", or wrap the call in `with` for a handler\n     of ")
                .append(eff).append(".");
        return sb.toString();
    }

    /** Collect the effects an expression might perform. Used at call
     *  sites of row-var-parameterised fns to bind the variable from
     *  the actual argument's row. Best-effort: opaque expressions
     *  (App returning an unknown fn, complex shapes) produce no
     *  binding contribution — the substitution silently treats the
     *  variable as empty for that position. */
    private Set<String> collectExprEffects(Expr e) {
        Set<String> out = new HashSet<>();
        collectInto(e, out);
        return out;
    }

    private void collectInto(Expr e, Set<String> out) {
        if (e == null) return;
        switch (e) {
            case Expr.Lambda lam -> collectInto(lam.body(), out);
            case Expr.App app -> {
                if (app.fn() instanceof Expr.Var v) {
                    String eff = effectOps.get(v.name());
                    if (eff != null) out.add(eff);
                    String builtinEff = BUILTIN_EFFECTS.get(v.name());
                    if (builtinEff != null) out.add(builtinEff);
                    List<String> row = fnRows.get(v.name());
                    if (row != null) {
                        for (String r : row) if (!isRowVar(r)) out.add(r);
                    }
                }
                collectInto(app.fn(), out);
                for (Expr a : app.args()) collectInto(a, out);
            }
            case Expr.Var v -> {
                // User-fn-as-value: include its row.
                List<String> row = fnRows.get(v.name());
                if (row != null) {
                    for (String r : row) if (!isRowVar(r)) out.add(r);
                }
            }
            case Expr.JavaRef jr -> out.add("JVM");
            case Expr.DotAccess da -> {
                collectInto(da.target(), out);
                if (da.target() instanceof Expr.Var v
                        && varCap.containsKey(v.name())) {
                    out.add(varCap.get(v.name()));
                }
            }
            case Expr.IfExpr ie -> {
                collectInto(ie.cond(), out);
                collectInto(ie.thenBranch(), out);
                collectInto(ie.elseBranch(), out);
            }
            case Expr.MatchExpr me -> {
                collectInto(me.scrutinee(), out);
                for (var arm : me.arms()) {
                    if (arm.guard() != null) collectInto(arm.guard(), out);
                    collectInto(arm.body(), out);
                }
            }
            case Expr.BinaryOp bo -> {
                collectInto(bo.left(), out);
                collectInto(bo.right(), out);
            }
            case Expr.UnaryOp uo -> collectInto(uo.operand(), out);
            case Expr.Block blk -> {
                for (Stmt s : blk.stmts()) collectStmtInto(s, out);
            }
            case Expr.VectorLit vl -> { for (Expr x : vl.elements()) collectInto(x, out); }
            case Expr.SetLit sl -> { for (Expr x : sl.elements()) collectInto(x, out); }
            case Expr.TupleLit tl -> { for (Expr x : tl.elements()) collectInto(x, out); }
            case Expr.MapLit ml -> {
                for (var me : ml.entries()) {
                    if (me instanceof Expr.MapEntry.Field f) collectInto(f.value(), out);
                }
            }
            case Expr.Pipe p -> { collectInto(p.left(), out); collectInto(p.right(), out); }
            case Expr.Compose c -> { collectInto(c.left(), out); collectInto(c.right(), out); }
            default -> {}
        }
    }

    private void collectStmtInto(Stmt s, Set<String> out) {
        switch (s) {
            case Stmt.ExprStmt es -> collectInto(es.expr(), out);
            case Stmt.Bind b -> collectInto(b.value(), out);
            case Stmt.MutBind mb -> collectInto(mb.value(), out);
            case Stmt.Assign a -> collectInto(a.value(), out);
            case Stmt.IfStmt ifs -> {
                collectInto(ifs.cond(), out);
                for (Stmt sub : ifs.thenBranch()) collectStmtInto(sub, out);
                if (ifs.elseBranch() != null) {
                    for (Stmt sub : ifs.elseBranch()) collectStmtInto(sub, out);
                }
            }
            case Stmt.MatchStmt ms -> {
                collectInto(ms.scrutinee(), out);
                for (var arm : ms.arms()) collectInto(arm.body(), out);
            }
            case Stmt.With w -> {
                // Inside the `with`, the handler's effect is discharged.
                // We collect effects from the body but remove the
                // handled effect — those don't escape the with block.
                Set<String> inner = new HashSet<>();
                for (Stmt sub : w.body()) collectStmtInto(sub, inner);
                Set<String> handled = new HashSet<>();
                collectHandlerEffects(w.handler(), handled);
                inner.removeAll(handled);
                out.addAll(inner);
            }
            default -> {}
        }
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
