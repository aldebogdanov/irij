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

    SmClassifier(ClassEmitter ce) { this.ce = ce; }

    /** A-normalize a with-body (bridge for sibling emitters). */
    java.util.List<Stmt> aNormalize(java.util.List<Stmt> body) { return new ANormalizer().normalize(body); }

    /**
     * Step 7 gate: SM lowering only handles tier (a)+(b) handlers — clauses
     * that don't themselves perform effects beyond their own resume. Any
     * referenced handler with non-empty {@code requiredEffects} (declared via
     * {@code ::: Other}) or whose clause body contains a perform of a
     * different effect falls back to the threaded path (which natively
     * supports the EffectSystem stack walk for clause-internal performs).
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
                    int thenTail = lower(ifs.thenBranch(), thenB, null);
                    List<Stmt> el = ifs.elseBranch() != null
                            ? ifs.elseBranch() : List.of();
                    int elseTail = lower(el, elseB, null);
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
                    lower(ifs.thenBranch(), thenB, merge);
                    List<Stmt> el = ifs.elseBranch() != null
                            ? ifs.elseBranch() : List.of();
                    lower(el, elseB, merge);
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
                    Expr scrut = normalizeExpr(ms.scrutinee(), out);
                    // Arms kept as-is: guards/bodies with ops are rejected later.
                    out.add(new Stmt.MatchStmt(scrut, ms.arms(), ms.loc()));
                }
                default -> out.add(s);
            }
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
                // IfExpr / MatchExpr / Lambda / Block: can't easily A-normalize
                // inline — leave untouched; EffIRBuilder will reject if ops
                // remain in non-top-level positions.
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
            default -> true;
        };
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
            // Step 8: nested `with` would require the outer continuation to
            // resume INSIDE the inner with rather than at its start, plus
            // bridging PerformSignal across SM/threaded boundaries. Both
            // are out of scope for 14c.3 — fall back to threaded for the
            // outer (and inner) so EffectSystem dispatch handles it.
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
            case Expr.Lambda lam -> containsOpCallExpr(lam.body()); // conservative
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
