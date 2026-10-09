package dev.irij.compiler;

import dev.irij.ast.Decl;
import dev.irij.ast.Expr;
import dev.irij.ast.Node;
import dev.irij.ast.Pattern;
import dev.irij.ast.Stmt;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SmClassifier implements Opcodes {

    private final ClassEmitter ce;
    /** Numbers the fresh names {@code EffIRBuilder.scoped} gives branch
     *  bindings — class-wide, so a nested `with` can't reuse one. */
    private int branchBindCounter = 0;

    SmClassifier(ClassEmitter ce) { this.ce = ce; }

    /** A-normalize a with-body (bridge for sibling emitters). */
    java.util.List<Stmt> aNormalize(java.util.List<Stmt> body) { return new ANormalizer().normalize(body); }

    /**
     * Whether SM lowering can run a {@code with} over this handler expression.
     * A false answer is a compile error ("handler shape not supported by
     * state-machine lowering") — there has been no other lowering since the
     * threaded one was removed in v0.6.13.
     */
    boolean smCanHandle(Expr handlerExpr) {
        for (String name : ce.effEm.collectHandlerNames(handlerExpr)) {
            Decl.HandlerDecl hd = ce.handlers.get(name);
            if (hd == null) return false; // unknown / dynamic — be conservative
            // Tier-c clauses (clause body performs foreign effects) are now
            // natively supported via clause-as-SM compilation, but only if
            // the body shape is SM-compilable.
            for (var clause : hd.clauses()) {
                if (exprPerformsForeignEffect(clause.body(), hd.effectName())) {
                    if (!tierCClauseCompilable(clause)) return false;
                }
            }
        }
        return true;
    }


    /**
     * Whether a tier-c clause body can be lowered to an SM step. v1 limits:
     * Block-or-Expr body, Sequence shape post-classification, no nested
     * `with` inside the clause.
     */
    boolean tierCClauseCompilable(Decl.HandlerClause c) {
        List<Stmt> stmts;
        if (c.body() instanceof Expr.Block blk) {
            stmts = new ArrayList<>(blk.stmts());
        } else {
            stmts = new ArrayList<>(List.of(new Stmt.ExprStmt(c.body(), null)));
        }
        try {
            stmts = new ANormalizer().normalize(stmts);
            WithBodyShape shape = classifyWithBody(stmts);
            WithBodyShape.Sequence seq;
            if (shape instanceof WithBodyShape.Sequence s) {
                seq = s;
            } else if (shape instanceof WithBodyShape.SingleOp so) {
                seq = singleOpToSequence(stmts, so);
            } else {
                return false;
            }
            for (Segment s : seq.segments()) {
                if (s.innerWith() != null) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }


    /** Promote a SingleOp shape into a 2-segment Sequence so tier-c
     *  lowering (which only emits Sequence shapes) can compile clause
     *  bodies whose only foreign op is a single perform. */
    WithBodyShape.Sequence singleOpToSequence(List<Stmt> body,
                                                       WithBodyShape.SingleOp so) {
        int idx = so.idx();
        List<Stmt> pre = new ArrayList<>(body.subList(0, idx));
        List<Stmt> post = new ArrayList<>(body.subList(idx + 1, body.size()));
        List<Segment> segments = new ArrayList<>();
        segments.add(new Segment(pre, so.opName(), so.opArgs(), so.bindName()));
        segments.add(new Segment(post, null, null, null));
        List<String> lifted = new ArrayList<>();
        if (so.bindName() != null) lifted.add(so.bindName());
        return new WithBodyShape.Sequence(segments, lifted);
    }


    boolean exprPerformsForeignEffect(Object node, String selfEffect) {
        if (node == null) return false;
        if (node instanceof Expr.App app && app.fn() instanceof Expr.Var v
                && ce.effectOps.containsKey(v.name())
                && !selfEffect.equals(ce.effectOps.get(v.name()))) {
            return true;
        }
        // Recurse via reflection-free structural traversal of common shapes.
        if (node instanceof Expr.Block b) {
            for (Stmt s : b.stmts()) if (stmtPerformsForeignEffect(s, selfEffect)) return true;
        } else if (node instanceof Expr.App app) {
            if (exprPerformsForeignEffect(app.fn(), selfEffect)) return true;
            for (Expr a : app.args()) if (exprPerformsForeignEffect(a, selfEffect)) return true;
        } else if (node instanceof Expr.IfExpr ie) {
            if (exprPerformsForeignEffect(ie.cond(), selfEffect)) return true;
            if (exprPerformsForeignEffect(ie.thenBranch(), selfEffect)) return true;
            if (exprPerformsForeignEffect(ie.elseBranch(), selfEffect)) return true;
        } else if (node instanceof Expr.Lambda lam) {
            if (exprPerformsForeignEffect(lam.body(), selfEffect)) return true;
        }
        return false;
    }


    boolean stmtPerformsForeignEffect(Stmt s, String selfEffect) {
        if (s instanceof Stmt.ExprStmt es) return exprPerformsForeignEffect(es.expr(), selfEffect);
        if (s instanceof Stmt.Bind b) return exprPerformsForeignEffect(b.value(), selfEffect);
        if (s instanceof Stmt.MutBind mb) return exprPerformsForeignEffect(mb.value(), selfEffect);
        if (s instanceof Stmt.Assign a) return exprPerformsForeignEffect(a.value(), selfEffect);
        if (s instanceof Stmt.IfStmt is) {
            if (exprPerformsForeignEffect(is.cond(), selfEffect)) return true;
            for (Stmt t : is.thenBranch()) if (stmtPerformsForeignEffect(t, selfEffect)) return true;
            if (is.elseBranch() != null)
                for (Stmt t : is.elseBranch()) if (stmtPerformsForeignEffect(t, selfEffect)) return true;
        }
        return false;
    }


    final class EffIRBuilder {
        final List<BB> blocks = new ArrayList<>();
        final List<List<Stmt>> pureAcc = new ArrayList<>();
        final Map<Integer, String> resumeBindOf = new LinkedHashMap<>();
        final List<String> lifted = new ArrayList<>();
        final Set<String> liftedSet = new HashSet<>();
        boolean ok = true;
        /** Block whose last pure stmt's value is the body result.
         *  Set when we reach the final fallthrough block. */
        int lastValueBlock = -1;

        int newBlock() {
            int id = blocks.size();
            blocks.add(null);
            pureAcc.add(new ArrayList<>());
            return id;
        }

        void finalize(int id, Term t) {
            blocks.set(id, new BB(id, pureAcc.get(id), t));
        }

        void liftName(String n) {
            if (liftedSet.add(n)) lifted.add(n);
        }

        /** A branch is a scope: its bindings end with it. Lowered here,
         *  into the step's own scope where lifted locals are keyed by
         *  name, a branch's `x := …` would rebind the body's `x` for the
         *  code after the `if`. So each binding the branch makes gets a
         *  fresh name, given to its binder and to every later use in the
         *  branch, but not to its own initializer (which still sees the
         *  enclosing `x`). Fresh names contain `$`, so no source name
         *  can capture them. */
        List<Stmt> scoped(List<Stmt> branch) {
            Map<String, String> renames = new HashMap<>();
            List<Stmt> out = new ArrayList<>(branch.size());
            for (Stmt s : branch) {
                switch (s) {
                    case Stmt.Bind b -> {
                        Expr value = ModulePrivacy.renamed(b.value(), renames);
                        out.add(new Stmt.Bind(freshTarget(b.target(), renames),
                                value, b.specAnnotation(), b.loc()));
                    }
                    case Stmt.MutBind mb -> {
                        Expr value = ModulePrivacy.renamed(mb.value(), renames);
                        out.add(new Stmt.MutBind(freshTarget(mb.target(), renames),
                                value, mb.loc()));
                    }
                    default -> out.add(ModulePrivacy.renamed(s, renames));
                }
            }
            return out;
        }

        /** {@code t} with each name it binds made fresh, recorded in {@code renames}. */
        Stmt.BindTarget freshTarget(Stmt.BindTarget t, Map<String, String> renames) {
            return switch (t) {
                case Stmt.BindTarget.Simple sm -> {
                    String fresh = sm.name() + "$if$" + branchBindCounter++;
                    renames.put(sm.name(), fresh);
                    yield new Stmt.BindTarget.Simple(fresh);
                }
                case Stmt.BindTarget.Destructure d -> {
                    Set<String> names = new HashSet<>();
                    ce.patEm.collectPatternBinds(d.pattern(), names);
                    for (String n : names) renames.put(n, n + "$if$" + branchBindCounter++);
                    yield new Stmt.BindTarget.Destructure(ModulePrivacy.renamed(d.pattern(), renames));
                }
            };
        }

        /** Lower a stmt list starting at `entry`. After the last stmt, jump
         *  to `exitJump` (null = terminate with Return of last expr).
         *  Returns the id of the tail block (post-last-stmt).  */
        int lower(List<Stmt> stmts, int entry, Integer exitJump) {
            int cur = entry;
            for (int i = 0; i < stmts.size(); i++) {
                Stmt s = stmts.get(i);
                TopLevelOp tl = extractTopLevelOp(s);
                boolean isLast = (i == stmts.size() - 1);
                if (tl != null) {
                    int next = newBlock();
                    if (tl.bindName() != null) {
                        liftName(tl.bindName());
                        resumeBindOf.put(next, tl.bindName());
                    }
                    String effectName = ce.effectOps.get(tl.opName());
                    finalize(cur, new Term.Perform(effectName, tl.opName(),
                            tl.args(), tl.bindName(), next));
                    cur = next;
                } else if (s instanceof Stmt.IfStmt ifs
                        && isLast && exitJump == null) {
                    // If/else at the tail of a with-body: its taken branch's
                    // value IS the with-block's result. Lower each branch
                    // with `null` exitJump so the branch's tail expression
                    // becomes a Return — works whether or not the branches
                    // perform ops (the recursive lower turns op statements
                    // into perform segments and the final expr into a
                    // Return). This MUST come before the op-bearing-if case
                    // below: routing a tail if through a merge block gives
                    // the merge `Return(null)`, discarding the branch value
                    // and making the whole with return Unit. (Pure-branch
                    // tail ifs hit this too; op-bearing tail ifs — e.g.
                    // `with default-db { row := db-query …; if (empty? row) …
                    // else { rows := db-query …; render … } }` on
                    // irij.online's seed-detail page — used to fall through
                    // to the merge case and white-screen the page.)
                    if (containsOpCallExpr(ifs.cond())) { ok = false; return cur; }
                    int thenB = newBlock();
                    int elseB = newBlock();
                    finalize(cur, new Term.Branch(ifs.cond(), thenB, elseB));
                    int thenTail = lower(scoped(ifs.thenBranch()), thenB, null);
                    List<Stmt> el = ifs.elseBranch() != null
                            ? ifs.elseBranch() : List.of();
                    int elseTail = lower(scoped(el), elseB, null);
                    // Either branch may have been the "value-producing"
                    // tail — record one whose Return carries the tail expr.
                    lastValueBlock = thenTail;
                    return cur;
                } else if (s instanceof Stmt.IfStmt ifs
                        && stmtContainsOpRecursive(s)) {
                    // Non-tail if whose branches perform ops: each branch
                    // Jumps to a shared merge block, and lowering continues
                    // from there (the if's value, if any, is discarded —
                    // a non-tail if is used for effect, not value).
                    if (containsOpCallExpr(ifs.cond())) { ok = false; return cur; }
                    int thenB = newBlock();
                    int elseB = newBlock();
                    int merge = newBlock();
                    finalize(cur, new Term.Branch(ifs.cond(), thenB, elseB));
                    lower(scoped(ifs.thenBranch()), thenB, merge);
                    List<Stmt> el = ifs.elseBranch() != null
                            ? ifs.elseBranch() : List.of();
                    lower(scoped(el), elseB, merge);
                    cur = merge;
                } else if (stmtContainsOpRecursive(s)) {
                    // Ops nested inside non-If stmt (match, with, block...): 3c
                    ok = false;
                    return cur;
                } else {
                    // Pure stmt: append. Lift Simple-Bind names unconditionally
                    // (conservative — safe if never crosses perform).
                    if (s instanceof Stmt.Bind b
                            && b.target() instanceof Stmt.BindTarget.Simple sm) {
                        liftName(sm.name());
                    }
                    pureAcc.get(cur).add(s);
                    if (isLast && exitJump == null) {
                        // Fallthrough last stmt: if it's an ExprStmt, its expr
                        // becomes the return value (emitted via Return term).
                        // Otherwise append null.
                        // For simplicity the Return terminator carries the
                        // ExprStmt's expr directly; strip from pureAcc.
                        List<Stmt> acc = pureAcc.get(cur);
                        Expr retExpr = null;
                        if (acc.get(acc.size() - 1) instanceof Stmt.ExprStmt es) {
                            retExpr = es.expr();
                            acc.remove(acc.size() - 1);
                        } else if (acc.get(acc.size() - 1) instanceof Stmt.MatchStmt ms) {
                            // A tail `match` is a value too.
                            retExpr = new Expr.MatchExpr(ms.scrutinee(), ms.arms(), ms.loc());
                            acc.remove(acc.size() - 1);
                        }
                        finalize(cur, new Term.Return(retExpr));
                        lastValueBlock = cur;
                        return cur;
                    }
                }
            }
            if (exitJump != null) {
                finalize(cur, new Term.Jump(exitJump));
            } else {
                // Empty stmts or all consumed without a fallthrough return.
                finalize(cur, new Term.Return(null));
                lastValueBlock = cur;
            }
            return cur;
        }
    }


    // ── A-normalization pre-pass (step 3c) ──────────────────────────────
    //
    // Lifts any op call appearing in a non-top-level position into a
    // preceding Simple-Bind with a fresh name. After this pass every op
    // call in the body appears either as an ExprStmt (`op args`) or as a
    // Simple-Bind RHS (`x := op args`) — the two shapes classifyWithBody
    // already recognises. Preserves Irij's strict left-to-right evaluation
    // order by lifting sub-expressions in the order they're encountered.

    final class ANormalizer {
        int counter = 0;
        String fresh() { return "$anf$" + (counter++); }

        List<Stmt> normalize(List<Stmt> stmts) {
            List<Stmt> out = new ArrayList<>();
            for (Stmt s : stmts) normalizeStmt(s, out);
            return out;
        }

        void normalizeStmt(Stmt s, List<Stmt> out) {
            switch (s) {
                case Stmt.ExprStmt es -> {
                    if (isDirectOpCall(es.expr())) {
                        out.add(new Stmt.ExprStmt(
                                normalizeOpArgs((Expr.App) es.expr(), out), es.loc()));
                    } else {
                        out.add(new Stmt.ExprStmt(normalizeExpr(es.expr(), out), es.loc()));
                    }
                }
                case Stmt.Bind b -> {
                    if (b.target() instanceof Stmt.BindTarget.Simple
                            && isDirectOpCall(b.value())) {
                        Expr rhs = normalizeOpArgs((Expr.App) b.value(), out);
                        out.add(new Stmt.Bind(b.target(), rhs, b.specAnnotation(), b.loc()));
                    } else {
                        out.add(new Stmt.Bind(b.target(),
                                normalizeExpr(b.value(), out),
                                b.specAnnotation(), b.loc()));
                    }
                }
                case Stmt.MutBind b -> out.add(new Stmt.MutBind(b.target(),
                        normalizeExpr(b.value(), out), b.loc()));
                case Stmt.Assign a -> out.add(new Stmt.Assign(a.target(),
                        normalizeExpr(a.value(), out), a.loc()));
                case Stmt.IfStmt ifs -> {
                    Expr cond = normalizeExpr(ifs.cond(), out);
                    List<Stmt> thenN = normalize(ifs.thenBranch());
                    List<Stmt> elseN = ifs.elseBranch() != null
                            ? normalize(ifs.elseBranch()) : null;
                    out.add(new Stmt.IfStmt(cond, thenN, elseN, ifs.loc()));
                }
                case Stmt.MatchStmt ms -> {
                    if (armsPerform(ms.arms())) {
                        desugarMatch(ms.scrutinee(), ms.arms(), null, ms.loc(), out);
                    } else {
                        // A guard that performs stays, and is refused later.
                        Expr scrut = normalizeExpr(ms.scrutinee(), out);
                        out.add(new Stmt.MatchStmt(scrut, ms.arms(), ms.loc()));
                    }
                }
                default -> out.add(s);
            }
        }

        // ── Places an op runs only sometimes ────────────────────────────
        //
        // A `match` arm, an `if` expression's branch and the right of
        // `&&` / `||` run only when chosen, so an op there can't be lifted
        // ahead of them like the ones above. The state machine performs in
        // `if` branches (EffIR), so each such place becomes an `if` chain,
        // its value assigned to a fresh result variable. A branch is a
        // scope (EffIRBuilder.scoped), so an arm's bindings stay its own.

        boolean armsPerform(List<Expr.MatchArm> arms) {
            for (Expr.MatchArm arm : arms) if (containsOpCallExpr(arm.body())) return true;
            return false;
        }

        /**
         * {@code match scrut} with {@code arms}, one of which performs, as:
         * <pre>
         * $m   := scrut
         * $sel := match $m                  ;; pure: picks the arm, collects
         *   p0 | g0 => #[0 a b]             ;; what its pattern bound
         *   p1 => #[1 c]
         * $k   := nth 0 $sel
         * if $k == 0                        ;; one branch per arm
         *   a := nth 1 $sel
         *   b := nth 2 $sel
         *   body0
         * else
         *   c := nth 1 $sel
         *   body1
         * </pre>
         * A scrutinee no arm matches fails in the pure match, as before.
         * With {@code result}, each arm's value is assigned to it.
         */
        void desugarMatch(Expr scrutinee, List<Expr.MatchArm> arms, String result,
                          Node.SourceLoc loc, List<Stmt> out) {
            String m = fresh();
            out.add(bind(m, normalizeExpr(scrutinee, out), loc));
            String sel = fresh();
            List<List<String>> vars = new ArrayList<>();
            List<Expr.MatchArm> pick = new ArrayList<>();
            for (int i = 0; i < arms.size(); i++) {
                Expr.MatchArm arm = arms.get(i);
                Set<String> bound = new java.util.LinkedHashSet<>();
                ce.patEm.collectPatternBinds(arm.pattern(), bound);
                List<String> vs = new ArrayList<>(bound);
                vars.add(vs);
                List<Expr> picked = new ArrayList<>();
                picked.add(new Expr.IntLit(i, loc));
                for (String v : vs) picked.add(new Expr.Var(v, loc));
                pick.add(new Expr.MatchArm(arm.pattern(), arm.guard(), new Expr.VectorLit(picked, loc)));
            }
            out.add(bind(sel, new Expr.MatchExpr(new Expr.Var(m, loc), pick, loc), loc));
            String k = fresh();
            out.add(bind(k, nth(0, sel, loc), loc));
            int last = arms.size() - 1;
            List<Stmt> chain = armStmts(arms.get(last), vars.get(last), sel, result, loc);
            for (int i = last - 1; i >= 0; i--) {
                Expr isArm = new Expr.BinaryOp("==", new Expr.Var(k, loc), new Expr.IntLit(i, loc), loc);
                chain = List.of(new Stmt.IfStmt(isArm,
                        armStmts(arms.get(i), vars.get(i), sel, result, loc), chain, loc));
            }
            // One arm still gets a branch of its own, for its scope.
            if (arms.size() == 1) {
                chain = List.of(new Stmt.IfStmt(new Expr.BoolLit(true, loc), chain, null, loc));
            }
            out.addAll(chain);
        }

        List<Stmt> armStmts(Expr.MatchArm arm, List<String> vars, String sel, String result,
                            Node.SourceLoc loc) {
            List<Stmt> s = new ArrayList<>();
            for (int j = 0; j < vars.size(); j++) s.add(bind(vars.get(j), nth(j + 1, sel, loc), loc));
            s.addAll(bodyStmts(arm.body(), loc));
            return normalize(result == null ? s : assignResult(s, result, loc));
        }

        /** An `if` or `match` expression, value block or `&&` / `||` whose
         *  conditional part performs: its value, via a result variable. */
        Expr viaResult(Expr e, List<Stmt> out) {
            Node.SourceLoc loc = e.loc();
            String r = fresh();
            switch (e) {
                case Expr.IfExpr ie -> {
                    Expr cond = normalizeExpr(ie.cond(), out);
                    out.add(bind(r, new Expr.UnitLit(loc), loc));
                    out.add(new Stmt.IfStmt(cond,
                            normalize(assignResult(bodyStmts(ie.thenBranch(), loc), r, loc)),
                            normalize(assignResult(bodyStmts(ie.elseBranch(), loc), r, loc)), loc));
                }
                case Expr.MatchExpr me -> {
                    out.add(bind(r, new Expr.UnitLit(loc), loc));
                    desugarMatch(me.scrutinee(), me.arms(), r, loc, out);
                }
                case Expr.Block blk -> {
                    out.add(bind(r, new Expr.UnitLit(loc), loc));
                    out.add(new Stmt.IfStmt(new Expr.BoolLit(true, loc),
                            normalize(assignResult(blk.stmts(), r, loc)), null, loc));
                }
                case Expr.BinaryOp bop -> {
                    // `a && b` is false unless a holds, and then b as a Bool
                    // (`true && b`); `a || b` mirrors it.
                    boolean and = bop.op().equals("&&");
                    Expr left = normalizeExpr(bop.left(), out);
                    out.add(bind(r, new Expr.UnitLit(loc), loc));
                    List<Stmt> decide = new ArrayList<>();
                    Expr right = normalizeExpr(bop.right(), decide);
                    decide.add(new Stmt.Assign(new Stmt.BindTarget.Simple(r),
                            new Expr.BinaryOp(bop.op(), new Expr.BoolLit(and, loc), right, loc), loc));
                    List<Stmt> settled = List.of(new Stmt.Assign(new Stmt.BindTarget.Simple(r),
                            new Expr.BoolLit(!and, loc), loc));
                    out.add(new Stmt.IfStmt(left, and ? decide : settled, and ? settled : decide, loc));
                }
                default -> throw new IllegalStateException("viaResult: " + e.getClass().getSimpleName());
            }
            return new Expr.Var(r, loc);
        }

        /** {@code stmts} with the value of the last one assigned to {@code r}. */
        List<Stmt> assignResult(List<Stmt> stmts, String r, Node.SourceLoc loc) {
            if (stmts.isEmpty()) return stmts;
            List<Stmt> s = new ArrayList<>(stmts.subList(0, stmts.size() - 1));
            Stmt last = stmts.get(stmts.size() - 1);
            s.add(switch (last) {
                case Stmt.ExprStmt es -> new Stmt.Assign(new Stmt.BindTarget.Simple(r), es.expr(), es.loc());
                case Stmt.IfStmt ifs -> new Stmt.IfStmt(ifs.cond(), assignResult(ifs.thenBranch(), r, loc),
                        ifs.elseBranch() == null ? null : assignResult(ifs.elseBranch(), r, loc), ifs.loc());
                case Stmt.MatchStmt ms -> {
                    List<Expr.MatchArm> arms = new ArrayList<>();
                    for (Expr.MatchArm arm : ms.arms()) {
                        arms.add(new Expr.MatchArm(arm.pattern(), arm.guard(),
                                new Expr.Block(assignResult(bodyStmts(arm.body(), loc), r, loc), loc)));
                    }
                    yield new Stmt.MatchStmt(ms.scrutinee(), arms, ms.loc());
                }
                default -> last; // a binding's value is (), which r already holds
            });
            return s;
        }

        List<Stmt> bodyStmts(Expr body, Node.SourceLoc loc) {
            return body instanceof Expr.Block blk ? blk.stmts() : List.of(new Stmt.ExprStmt(body, loc));
        }

        Stmt bind(String name, Expr value, Node.SourceLoc loc) {
            return new Stmt.Bind(new Stmt.BindTarget.Simple(name), value, loc);
        }

        Expr nth(int i, String coll, Node.SourceLoc loc) {
            return new Expr.App(new Expr.Var("nth", loc),
                    List.of(new Expr.IntLit(i, loc), new Expr.Var(coll, loc)), loc);
        }

        /** Normalize only the args of a direct op call (the call itself stays
         *  top-level); any op sub-expr in an arg gets lifted to a fresh bind. */
        Expr normalizeOpArgs(Expr.App app, List<Stmt> out) {
            List<Expr> args = new ArrayList<>();
            for (Expr a : app.args()) args.add(normalizeExpr(a, out));
            return new Expr.App(app.fn(), args, app.loc());
        }

        /** Normalize one map entry (map literal or record update),
         *  lifting ops from values and dynamic keys in source order. */
        Expr.MapEntry normalizeMapEntry(Expr.MapEntry en, List<Stmt> out) {
            return switch (en) {
                case Expr.MapEntry.Field f ->
                        new Expr.MapEntry.Field(f.key(), normalizeExpr(f.value(), out));
                case Expr.MapEntry.DynField df ->
                        new Expr.MapEntry.DynField(
                                normalizeExpr(df.keyExpr(), out),
                                normalizeExpr(df.value(), out));
                case Expr.MapEntry.Spread sp -> sp;
            };
        }

        /** Normalize an expression in a non-top-level position. Any op call
         *  encountered is lifted to a fresh Simple-Bind in {@code out}. */
        Expr normalizeExpr(Expr e, List<Stmt> out) {
            if (e == null) return null;
            if (!containsOpCallExpr(e)) return e;
            return switch (e) {
                case Expr.BinaryOp bop when (bop.op().equals("&&") || bop.op().equals("||"))
                        && containsOpCallExpr(bop.right()) -> viaResult(bop, out);
                case Expr.IfExpr ie when containsOpCallExpr(ie.thenBranch())
                        || containsOpCallExpr(ie.elseBranch()) -> viaResult(ie, out);
                case Expr.IfExpr ie -> new Expr.IfExpr(normalizeExpr(ie.cond(), out),
                        ie.thenBranch(), ie.elseBranch(), ie.loc());
                case Expr.MatchExpr me when armsPerform(me.arms()) -> viaResult(me, out);
                case Expr.MatchExpr me -> new Expr.MatchExpr(normalizeExpr(me.scrutinee(), out),
                        me.arms(), me.loc());
                // A block holding a nested `with` or `scope` is a segment of
                // its own (extractTopLevelBindWith), not a value to compute.
                case Expr.Block blk when blk.stmts().stream()
                        .noneMatch(st -> st instanceof Stmt.With || st instanceof Stmt.Scope) -> viaResult(blk, out);
                case Expr.App app -> {
                    boolean isOp = app.fn() instanceof Expr.Var v
                            && ce.effectOps.containsKey(v.name());
                    Expr fn = isOp ? app.fn() : normalizeExpr(app.fn(), out);
                    List<Expr> args = new ArrayList<>();
                    for (Expr a : app.args()) args.add(normalizeExpr(a, out));
                    Expr call = new Expr.App(fn, args, app.loc());
                    if (isOp) {
                        String name = fresh();
                        out.add(new Stmt.Bind(new Stmt.BindTarget.Simple(name),
                                call, app.loc()));
                        yield new Expr.Var(name, app.loc());
                    }
                    yield call;
                }
                case Expr.BinaryOp bop -> new Expr.BinaryOp(bop.op(),
                        normalizeExpr(bop.left(), out),
                        normalizeExpr(bop.right(), out), bop.loc());
                case Expr.UnaryOp u -> new Expr.UnaryOp(u.op(),
                        normalizeExpr(u.operand(), out), u.loc());
                case Expr.DotAccess da -> new Expr.DotAccess(
                        normalizeExpr(da.target(), out), da.field(), da.loc());
                case Expr.Pipe p -> new Expr.Pipe(
                        normalizeExpr(p.left(), out),
                        normalizeExpr(p.right(), out), p.forward(), p.loc());
                case Expr.VectorLit vl -> {
                    List<Expr> xs = new ArrayList<>();
                    for (Expr x : vl.elements()) xs.add(normalizeExpr(x, out));
                    yield new Expr.VectorLit(xs, vl.loc());
                }
                case Expr.TupleLit tl -> {
                    List<Expr> xs = new ArrayList<>();
                    for (Expr x : tl.elements()) xs.add(normalizeExpr(x, out));
                    yield new Expr.TupleLit(xs, tl.loc());
                }
                case Expr.SetLit sl -> {
                    List<Expr> xs = new ArrayList<>();
                    for (Expr x : sl.elements()) xs.add(normalizeExpr(x, out));
                    yield new Expr.SetLit(xs, sl.loc());
                }
                case Expr.Compose c -> new Expr.Compose(
                        normalizeExpr(c.left(), out),
                        normalizeExpr(c.right(), out), c.forward(), c.loc());
                case Expr.SeqOp so -> new Expr.SeqOp(so.op(),
                        normalizeExpr(so.arg(), out), so.loc());
                case Expr.Range r -> new Expr.Range(
                        normalizeExpr(r.from(), out),
                        normalizeExpr(r.to(), out), r.exclusive(), r.loc());
                case Expr.DoExpr de -> {
                    List<Expr> xs = new ArrayList<>();
                    for (Expr x : de.exprs()) xs.add(normalizeExpr(x, out));
                    yield new Expr.DoExpr(xs, de.loc());
                }
                case Expr.StringInterp si -> {
                    List<Expr.StringPart> parts = new ArrayList<>();
                    for (Expr.StringPart part : si.parts()) {
                        if (part instanceof Expr.StringPart.Interpolation ip) {
                            parts.add(new Expr.StringPart.Interpolation(
                                    normalizeExpr(ip.expr(), out)));
                        } else {
                            parts.add(part);
                        }
                    }
                    yield new Expr.StringInterp(parts, si.loc());
                }
                case Expr.MapLit ml -> {
                    List<Expr.MapEntry> entries = new ArrayList<>();
                    for (Expr.MapEntry en : ml.entries()) {
                        entries.add(normalizeMapEntry(en, out));
                    }
                    yield new Expr.MapLit(entries, ml.loc());
                }
                case Expr.RecordUpdate ru -> {
                    List<Expr.MapEntry> updates = new ArrayList<>();
                    for (Expr.MapEntry en : ru.updates()) {
                        updates.add(normalizeMapEntry(en, out));
                    }
                    yield new Expr.RecordUpdate(ru.base(), updates, ru.loc());
                }
                // A block with a nested `with`/`scope`: left as is, and refused
                // later if it isn't a top-level `x := with …` segment.
                default -> e;
            };
        }

        boolean isDirectOpCall(Expr e) {
            return e instanceof Expr.App app
                    && app.fn() instanceof Expr.Var v
                    && ce.effectOps.containsKey(v.name());
        }
    }


    boolean stmtContainsOpRecursive(Stmt s) {
        if (extractTopLevelOp(s) != null) return true;
        return switch (s) {
            case Stmt.IfStmt ifs -> {
                if (containsOpCallExpr(ifs.cond())) yield true;
                for (Stmt t : ifs.thenBranch()) if (stmtContainsOpRecursive(t)) yield true;
                if (ifs.elseBranch() != null) {
                    for (Stmt t : ifs.elseBranch()) if (stmtContainsOpRecursive(t)) yield true;
                }
                yield false;
            }
            case Stmt.ExprStmt es -> containsOpCallExpr(es.expr());
            case Stmt.Bind b -> containsOpCallExpr(b.value());
            case Stmt.MutBind b -> containsOpCallExpr(b.value());
            case Stmt.Assign a -> containsOpCallExpr(a.value());
            case Stmt.MatchStmt ms -> matchContainsOp(ms.scrutinee(), ms.arms());
            default -> true;
        };
    }

    /** A `match` performs if its scrutinee, a guard or an arm does. */
    boolean matchContainsOp(Expr scrutinee, List<Expr.MatchArm> arms) {
        if (containsOpCallExpr(scrutinee)) return true;
        for (Expr.MatchArm arm : arms) {
            if (containsOpCallExpr(arm.guard()) || containsOpCallExpr(arm.body())) return true;
        }
        return false;
    }


    boolean bodyHasBranchingOp(List<Stmt> body) {
        for (Stmt s : body) {
            if (s instanceof Stmt.IfStmt && stmtContainsOpRecursive(s)) return true;
        }
        // Pure if/else at the tail of a body that performs an op in
        // an earlier segment — the if becomes the with-block's
        // return value and only the EffIR lowering reconstructs that
        // tail correctly. The segment classifier would otherwise
        // treat the if as a pure stmt without value and the with
        // returns Unit. Found wiring the irij.online seed registry.
        if (!body.isEmpty()
                && body.get(body.size() - 1) instanceof Stmt.IfStmt) {
            for (int i = 0; i < body.size() - 1; i++) {
                if (stmtContainsOpRecursive(body.get(i))) return true;
            }
        }
        return false;
    }


    /** Pre-pass for SM lowering: rewrite destructure binds (vector or
     *  tuple patterns of simple var names) into a temp + element
     *  extractions, so the segment-collecting classifier in
     *  {@link #classifyWithBody} doesn't trip the
     *  "destructure in non-final segment" Unsupported check.
     *
     *  <p>{@code #[sql params] := pair ()} becomes:
     *  <pre>
     *  __sm$dest$N := pair ()
     *  sql    := nth 0 __sm$dest$N
     *  params := nth 1 __sm$dest$N
     *  </pre>
     *
     *  <p>Patterns with nested non-Var subpatterns, spreads, or maps
     *  are left untouched and classify the same way as before. */
    List<Stmt> expandDestructureBindsForSM(List<Stmt> stmts) {
        List<Stmt> out = new ArrayList<>(stmts.size());
        for (Stmt s : stmts) {
            if (!(s instanceof Stmt.Bind b)) { out.add(s); continue; }
            if (!(b.target() instanceof Stmt.BindTarget.Destructure d)) { out.add(s); continue; }
            Pattern pat = d.pattern();
            List<String> names = simpleVarSequenceFromPattern(pat);
            if (names == null) { out.add(s); continue; }
            // Synthesize: __sm$dest$N := value; name_i := nth i __sm$dest$N
            String tmp = "__sm$dest$" + ce.smDestCounter++;
            out.add(new Stmt.Bind(new Stmt.BindTarget.Simple(tmp),
                    b.value(), null, b.loc()));
            for (int i = 0; i < names.size(); i++) {
                Expr nth = new Expr.App(
                        new Expr.Var("nth", b.loc()),
                        java.util.List.of(
                                new Expr.IntLit(i, b.loc()),
                                new Expr.Var(tmp, b.loc())),
                        b.loc());
                out.add(new Stmt.Bind(new Stmt.BindTarget.Simple(names.get(i)),
                        nth, null, b.loc()));
            }
        }
        return out;
    }


    /** Returns the list of simple-var names if {@code pat} is a vector
     *  or tuple of plain {@link Pattern.VarPat}s (no spread, no nested
     *  patterns). Otherwise returns {@code null}. */
    static List<String> simpleVarSequenceFromPattern(Pattern pat) {
        List<Pattern> elems;
        if (pat instanceof Pattern.VectorPat vp) {
            if (vp.spread() != null) return null;
            elems = vp.elements();
        } else if (pat instanceof Pattern.TuplePat tp) {
            elems = tp.elements();
        } else {
            return null;
        }
        List<String> names = new ArrayList<>(elems.size());
        for (Pattern e : elems) {
            if (e instanceof Pattern.VarPat vp) names.add(vp.name());
            else return null;
        }
        return names;
    }


    WithBodyShape classifyWithBody(List<Stmt> body) {
        // Step 3b: if body has top-level IfStmt whose branches perform ops,
        // route to full EffIR lowering.
        if (bodyHasBranchingOp(body)) {
            EffIRBuilder b = new EffIRBuilder();
            int entry = b.newBlock();
            b.lower(body, entry, null);
            if (!b.ok) return new WithBodyShape.Unsupported();
            return new WithBodyShape.EffIR(b.blocks, b.lifted, b.resumeBindOf,
                    b.lastValueBlock);
        }

        // Partition into segments at each top-level op call OR nested `with`.
        // Nested `with` becomes its own resumable segment whose continuation
        // is persisted in k.fields[innerSlot] so its state survives across
        // outer-resume cycles. Slot indices are assigned post-hoc below.
        List<Segment> segments = new ArrayList<>();
        List<Stmt> cur = new ArrayList<>();
        int opCount = 0;
        int withCount = 0;
        int firstOpIdx = -1;
        for (int i = 0; i < body.size(); i++) {
            Stmt s = body.get(i);
            TopLevelOp tl = extractTopLevelOp(s);
            if (tl != null) {
                if (opCount == 0 && withCount == 0) firstOpIdx = i;
                segments.add(new Segment(new ArrayList<>(cur), tl.opName(), tl.args(), tl.bindName()));
                cur.clear();
                opCount++;
                continue;
            }
            // Bind whose value is `with X body` — Bind(name, Block([With])).
            // Treat the inner with as a resumable segment whose result is
            // bound to `name` (lifted into k.fields so subsequent segments
            // can read it).
            TopLevelBindWith bw = extractTopLevelBindWith(s);
            if (bw != null) {
                if (!smCanHandle(bw.with().handler())) {
                    return new WithBodyShape.Unsupported();
                }
                List<Stmt> innerBody = new ANormalizer().normalize(bw.with().body());
                WithBodyShape innerShape = classifyWithBody(innerBody);
                if (innerShape instanceof WithBodyShape.Unsupported) {
                    return new WithBodyShape.Unsupported();
                }
                segments.add(new Segment(
                        new ArrayList<>(cur), null, null, null,
                        bw.with(), -1, bw.bindName()));
                cur.clear();
                withCount++;
                continue;
            }
            if (s instanceof Stmt.With w) {
                // Inner with must itself be SM-eligible for native nesting.
                // If not, fall back so the outer goes threaded too.
                if (!smCanHandle(w.handler())) return new WithBodyShape.Unsupported();
                List<Stmt> innerBody = new ANormalizer().normalize(w.body());
                WithBodyShape innerShape = classifyWithBody(innerBody);
                if (innerShape instanceof WithBodyShape.Unsupported) {
                    return new WithBodyShape.Unsupported();
                }
                segments.add(new Segment(
                        new ArrayList<>(cur), null, null, null,
                        w, /*slot — assigned later*/ -1, /*innerBind*/ null));
                cur.clear();
                withCount++;
                continue;
            }
            if (containsOpCall(s)) return new WithBodyShape.Unsupported();
            cur.add(s);
        }
        segments.add(new Segment(cur, null, null, null));

        if (opCount == 0 && withCount == 0) return new WithBodyShape.Pure();

        // Fast path: single op, no pre-op binds, no nested-with → SingleOp.
        if (opCount == 1 && withCount == 0) {
            boolean anyPreBind = false;
            for (Stmt s : segments.get(0).pureStmts()) {
                if (s instanceof Stmt.Bind || s instanceof Stmt.MutBind) {
                    anyPreBind = true;
                    break;
                }
            }
            if (!anyPreBind) {
                Segment s0 = segments.get(0);
                return new WithBodyShape.SingleOp(
                        firstOpIdx, s0.opName(), s0.opArgs(), s0.bindName());
            }
        }

        // Sequence path: collect lifted-local names.
        //   - Every Simple-Bind in any non-final segment
        //   - Every resume-bind (Segment.bindName) of non-final segments
        //   - Destructure/MutBind in non-final segments → Unsupported (3a scope)
        // Then assign slots for inner-with continuations beyond the named
        // lifted entries (synthetic "$with$N" names so emitVarLoad never
        // resolves to them).
        List<String> lifted = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < segments.size(); i++) {
            Segment seg = segments.get(i);
            boolean nonFinal = (i < segments.size() - 1);
            for (Stmt st : seg.pureStmts()) {
                if (st instanceof Stmt.Bind b) {
                    if (b.target() instanceof Stmt.BindTarget.Simple sm) {
                        if (seen.add(sm.name())) lifted.add(sm.name());
                    } else if (nonFinal) {
                        return new WithBodyShape.Unsupported();
                    }
                } else if (st instanceof Stmt.MutBind && nonFinal) {
                    return new WithBodyShape.Unsupported();
                }
            }
            if (nonFinal && seg.opName() != null && seg.bindName() != null
                    && seen.add(seg.bindName())) {
                lifted.add(seg.bindName());
            }
            // innerWith bind name: lift unconditionally so subsequent
            // segments can read it from k.fields[].
            if (seg.innerWith() != null && seg.innerBind() != null
                    && seen.add(seg.innerBind())) {
                lifted.add(seg.innerBind());
            }
        }
        // Assign nested-with slots and rebuild segments with concrete indices.
        int withSlotCounter = 0;
        for (int i = 0; i < segments.size(); i++) {
            Segment seg = segments.get(i);
            if (seg.innerWith() != null) {
                int slot = lifted.size() + withSlotCounter++;
                segments.set(i, new Segment(
                        seg.pureStmts(), null, null, null,
                        seg.innerWith(), slot, seg.innerBind()));
                lifted.add("$with$" + slot); // synthetic — emitVarLoad never sees it
            }
        }
        return new WithBodyShape.Sequence(segments, lifted);
    }


    /** Bind whose value is a single nested `with` — e.g. `r := with X body`. */
    TopLevelBindWith extractTopLevelBindWith(Stmt s) {
        if (s instanceof Stmt.Bind b
                && b.target() instanceof Stmt.BindTarget.Simple sm
                && b.value() instanceof Expr.Block blk
                && blk.stmts().size() == 1
                && blk.stmts().get(0) instanceof Stmt.With w) {
            return new TopLevelBindWith(sm.name(), w);
        }
        return null;
    }


    TopLevelOp extractTopLevelOp(Stmt s) {
        if (s instanceof Stmt.ExprStmt es && es.expr() instanceof Expr.App app
                && app.fn() instanceof Expr.Var v && ce.effectOps.containsKey(v.name())) {
            // Confirm args have no nested op.
            for (Expr a : app.args()) if (containsOpCallExpr(a)) return null;
            return new TopLevelOp(v.name(), app.args(), null);
        }
        if (s instanceof Stmt.Bind b
                && b.target() instanceof Stmt.BindTarget.Simple simp
                && b.value() instanceof Expr.App app
                && app.fn() instanceof Expr.Var v && ce.effectOps.containsKey(v.name())) {
            for (Expr a : app.args()) if (containsOpCallExpr(a)) return null;
            return new TopLevelOp(v.name(), app.args(), simp.name());
        }
        return null;
    }


    boolean containsOpCall(Stmt s) {
        return switch (s) {
            case Stmt.ExprStmt es -> containsOpCallExpr(es.expr());
            case Stmt.Bind b -> containsOpCallExpr(b.value());
            case Stmt.MutBind b -> containsOpCallExpr(b.value());
            case Stmt.Assign a -> containsOpCallExpr(a.value());
            // Plain IfStmt — no op in its cond / branches means safe to
            // emit as a regular branch in the segment. The bodyHasBranchingOp
            // gate above already routed if-with-op-in-branches to EffIR.
            case Stmt.IfStmt ifs -> stmtContainsOpRecursive(ifs);
            case Stmt.MatchStmt ms -> matchContainsOp(ms.scrutinee(), ms.arms());
            // Step 8: nested `with` would require the outer continuation to
            // resume INSIDE the inner with rather than at its start, plus
            // bridging PerformSignal across nested dispatch loops — not
            // something this flat classification decides.
            default -> true; // includes Stmt.With — conservatively unsupported
        };
    }


    boolean containsOpCallExpr(Expr e) {
        if (e == null) return false;
        return switch (e) {
            case Expr.App app -> {
                if (app.fn() instanceof Expr.Var v && ce.effectOps.containsKey(v.name())) yield true;
                if (containsOpCallExpr(app.fn())) yield true;
                for (Expr a : app.args()) if (containsOpCallExpr(a)) yield true;
                yield false;
            }
            case Expr.BinaryOp bop -> containsOpCallExpr(bop.left()) || containsOpCallExpr(bop.right());
            case Expr.UnaryOp u -> containsOpCallExpr(u.operand());
            case Expr.IfExpr ie -> containsOpCallExpr(ie.cond())
                    || containsOpCallExpr(ie.thenBranch())
                    || containsOpCallExpr(ie.elseBranch());
            case Expr.Block blk -> {
                for (Stmt st : blk.stmts()) if (containsOpCall(st)) yield true;
                yield false;
            }
            // A lambda's body runs when it's called, not where it's built:
            // like a fn, it performs through the handler that's in scope
            // then (RtEffects' synchronous perform), not as a step state.
            case Expr.Lambda lam -> false;
            case Expr.VectorLit vl -> { for (Expr x : vl.elements()) if (containsOpCallExpr(x)) yield true; yield false; }
            case Expr.TupleLit tl -> { for (Expr x : tl.elements()) if (containsOpCallExpr(x)) yield true; yield false; }
            case Expr.SetLit sl -> { for (Expr x : sl.elements()) if (containsOpCallExpr(x)) yield true; yield false; }
            case Expr.DotAccess da -> containsOpCallExpr(da.target());
            case Expr.MatchExpr me -> {
                if (containsOpCallExpr(me.scrutinee())) yield true;
                for (Expr.MatchArm arm : me.arms()) {
                    if (containsOpCallExpr(arm.guard())) yield true;
                    if (containsOpCallExpr(arm.body())) yield true;
                }
                yield false;
            }
            // PR7: positions that previously fell through to the
            // SM_STACK fallback — now detected so bodies classify into
            // native SM shapes (SmLoweringCoverageTest pins behavior).
            case Expr.Pipe p -> containsOpCallExpr(p.left()) || containsOpCallExpr(p.right());
            case Expr.Compose c -> containsOpCallExpr(c.left()) || containsOpCallExpr(c.right());
            case Expr.SeqOp so -> so.arg() != null && containsOpCallExpr(so.arg());
            case Expr.DoExpr de -> {
                for (Expr x : de.exprs()) if (containsOpCallExpr(x)) yield true;
                yield false;
            }
            case Expr.Range r -> containsOpCallExpr(r.from()) || containsOpCallExpr(r.to());
            case Expr.StringInterp si -> {
                for (Expr.StringPart part : si.parts()) {
                    if (part instanceof Expr.StringPart.Interpolation ip
                            && containsOpCallExpr(ip.expr())) yield true;
                }
                yield false;
            }
            case Expr.MapLit ml -> {
                for (Expr.MapEntry en : ml.entries()) {
                    switch (en) {
                        case Expr.MapEntry.Field f -> {
                            if (containsOpCallExpr(f.value())) yield true;
                        }
                        case Expr.MapEntry.DynField df -> {
                            if (containsOpCallExpr(df.keyExpr())
                                    || containsOpCallExpr(df.value())) yield true;
                        }
                        case Expr.MapEntry.Spread sp -> { /* var ref, no op */ }
                    }
                }
                yield false;
            }
            case Expr.RecordUpdate ru -> {
                for (Expr.MapEntry en : ru.updates()) {
                    switch (en) {
                        case Expr.MapEntry.Field f -> {
                            if (containsOpCallExpr(f.value())) yield true;
                        }
                        case Expr.MapEntry.DynField df -> {
                            if (containsOpCallExpr(df.keyExpr())
                                    || containsOpCallExpr(df.value())) yield true;
                        }
                        case Expr.MapEntry.Spread sp -> { /* var ref, no op */ }
                    }
                }
                yield false;
            }
            default -> false;
        };
    }
}
