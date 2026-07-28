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

final class LambdaEmitter implements Opcodes {

    private final ClassEmitter ce;

    LambdaEmitter(ClassEmitter ce) { this.ce = ce; }

    void emitLambda(Expr.Lambda lam, MethodVisitor mv, Locals outerLocals) {
        // 1. Collect parameter names (VarPat/WildcardPat only for MVP).
        List<String> paramNames = new ArrayList<>();
        for (Pattern p : lam.params()) {
            paramNames.add(switch (p) {
                case Pattern.VarPat v -> v.name();
                case Pattern.WildcardPat __ -> "_";
                default -> throw new IrijCompiler.CompileException(
                        "MVP lambda: param must be VarPat/WildcardPat");
            });
        }
        String restName = lam.restParam();

        // 2. Determine free vars (any Var referring to an outer local slot).
        Set<String> bound = new HashSet<>(paramNames);
        if (restName != null) bound.add(restName);
        List<String> captures = new ArrayList<>();
        collectFreeVars(lam.body(), bound, outerLocals, captures, new HashSet<>());

        // 3. Generate private static lambda method: (cap0..capK, Object[] args) -> Object.
        int id = ce.lambdaCounter++;
        String methodName = "lambda$" + id;
        StringBuilder desc = new StringBuilder("(");
        for (int i = 0; i < captures.size(); i++) desc.append(ClassEmitter.OBJ_DESC);
        desc.append("[Ljava/lang/Object;)Ljava/lang/Object;");
        MethodVisitor lm = ce.classWriter.visitMethod(
                ACC_STATIC | ACC_SYNTHETIC,
                methodName, desc.toString(), null, null);
        lm.visitCode();

        Locals inner = new Locals();
        // Captures bound first.
        for (String cap : captures) inner.allocate(cap);
        // Args array in next slot.
        int argsSlot = inner.allocateAnon();
        // Unpack args[i] into param slots.
        for (int i = 0; i < paramNames.size(); i++) {
            String pn = paramNames.get(i);
            int pslot = inner.allocate(pn);
            lm.visitVarInsn(ALOAD, argsSlot);
            ce.exprEm.pushIconst(lm, i);
            lm.visitInsn(AALOAD);
            lm.visitVarInsn(ASTORE, pslot);
        }
        // Rest param: build IrijVector from args[paramNames.size()..].
        if (restName != null) {
            int restSlot = inner.allocate(restName);
            lm.visitVarInsn(ALOAD, argsSlot);
            ce.exprEm.pushIconst(lm, paramNames.size());
            lm.visitMethodInsn(INVOKESTATIC, RtOwners.of("restVector"), "restVector",
                    "([Ljava/lang/Object;I)Ljava/lang/Object;", false);
            lm.visitVarInsn(ASTORE, restSlot);
        }
        // If a clause-required-effects context is pending, push it on
        // RT.EFFECT_ROW for the body. The frame is popped before
        // ARETURN (normal path) and via a catch-all (exception path)
        // so EFFECT_ROW stays balanced across throws.
        java.util.List<String> clauseEffects = ce.pendingClauseEffects;
        ce.pendingClauseEffects = null;
        Label clauseTryStart = null, clauseTryEnd = null, clauseHandler = null;
        if (clauseEffects != null) {
            ce.smEm.emitStringArrayConst(lm, clauseEffects);
            lm.visitMethodInsn(INVOKESTATIC, RtOwners.of("enterFn"), "enterFn", "([Ljava/lang/String;)V", false);
            clauseTryStart = new Label();
            clauseTryEnd = new Label();
            clauseHandler = new Label();
            lm.visitLabel(clauseTryStart);
        }
        ce.exprEm.emitExpr(lam.body(), lm, inner);
        if (clauseEffects != null) {
            // Normal exit: pop, then return the value left on stack.
            lm.visitMethodInsn(INVOKESTATIC, RtOwners.of("exitFn"), "exitFn", "()V", false);
        }
        lm.visitInsn(ARETURN);
        if (clauseEffects != null) {
            lm.visitLabel(clauseTryEnd);
            lm.visitLabel(clauseHandler);
            lm.visitMethodInsn(INVOKESTATIC, RtOwners.of("exitFn"), "exitFn", "()V", false);
            lm.visitInsn(ATHROW);
            lm.visitTryCatchBlock(clauseTryStart, clauseTryEnd, clauseHandler, null);
        }
        lm.visitMaxs(0, 0);
        lm.visitEnd();

        // 4. At the call site: push captures, then invokedynamic → IrijFn.
        for (String cap : captures) {
            mv.visitVarInsn(ALOAD, outerLocals.lookup(cap));
        }
        StringBuilder indyDesc = new StringBuilder("(");
        for (int i = 0; i < captures.size(); i++) indyDesc.append(ClassEmitter.OBJ_DESC);
        indyDesc.append(")").append(ClassEmitter.IRIJ_FN_DESC);

        Handle bsm = new Handle(H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;",
                false);
        Type samType = Type.getMethodType(ClassEmitter.APPLY_DESC);
        Handle implHandle = new Handle(H_INVOKESTATIC, ce.internalName, methodName, desc.toString(), false);
        mv.visitInvokeDynamicInsn("apply", indyDesc.toString(), bsm,
                samType, implHandle, samType);
        // Wrap the raw IrijFn in a CurriedFn that remembers the lambda's
        // arity. Enables partial application (`add 5` returns a function
        // awaiting one more arg) and over-application (curried lambda
        // applied to too many args dispatches the tail). Rest-param
        // lambdas are variadic (arity = -1) — every arg goes straight
        // to the impl, no currying.
        int curryArity = (restName != null) ? -1 : paramNames.size();
        ce.exprEm.pushIconst(mv, curryArity);
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("curry"), "curry",
                "(" + ClassEmitter.IRIJ_FN_DESC + "I)" + ClassEmitter.IRIJ_FN_DESC, false);
    }


    /** Walk Expr, collecting names referenced but not bound, that resolve to outer locals. */
    void collectFreeVars(Expr e, Set<String> bound, Locals outer,
                                  List<String> out, Set<String> seen) {
        if (e == null) return;
        switch (e) {
            case Expr.Var v -> {
                String n = v.name();
                // Top-level (mut) bindings live in static fields. Don't
                // capture them by value — reads and writes inside a
                // lambda must hit the static field directly so updates
                // are visible across threads / scope boundaries.
                //
                // "Top-level binding" has to mean the slot in main's
                // Locals, not merely a name that also exists as a
                // top-level field. A parameter or let-binding of an
                // enclosing scope shadows a same-named global, so it
                // must still be captured: skipping it made the lambda
                // read the caller's global instead of the argument its
                // fn was given. Modules are inlined into one namespace,
                // so this fired whenever a program's top-level binding
                // happened to share a name with a parameter inside any
                // library fn it called — silently, with a wrong value.
                if (ce.topLevelFields.containsKey(n) && outer == ce.topLevelLocals) break;
                if (!bound.contains(n) && outer.lookup(n) != null && !seen.contains(n)) {
                    seen.add(n);
                    out.add(n);
                }
            }
            case Expr.Lambda l2 -> {
                Set<String> bound2 = new HashSet<>(bound);
                for (Pattern p : l2.params()) {
                    if (p instanceof Pattern.VarPat vp) bound2.add(vp.name());
                }
                collectFreeVars(l2.body(), bound2, outer, out, seen);
            }
            case Expr.App app -> {
                collectFreeVars(app.fn(), bound, outer, out, seen);
                for (Expr a : app.args()) collectFreeVars(a, bound, outer, out, seen);
            }
            case Expr.BinaryOp bop -> {
                collectFreeVars(bop.left(), bound, outer, out, seen);
                collectFreeVars(bop.right(), bound, outer, out, seen);
            }
            case Expr.UnaryOp uop -> collectFreeVars(uop.operand(), bound, outer, out, seen);
            case Expr.IfExpr ie -> {
                collectFreeVars(ie.cond(), bound, outer, out, seen);
                collectFreeVars(ie.thenBranch(), bound, outer, out, seen);
                collectFreeVars(ie.elseBranch(), bound, outer, out, seen);
            }
            case Expr.MatchExpr me -> {
                collectFreeVars(me.scrutinee(), bound, outer, out, seen);
                for (Expr.MatchArm arm : me.arms()) {
                    Set<String> bound2 = new HashSet<>(bound);
                    ce.patEm.collectPatternBinds(arm.pattern(), bound2);
                    collectFreeVars(arm.guard(), bound2, outer, out, seen);
                    collectFreeVars(arm.body(), bound2, outer, out, seen);
                }
            }
            case Expr.VectorLit vl -> { for (Expr x : vl.elements()) collectFreeVars(x, bound, outer, out, seen); }
            case Expr.TupleLit tl -> { for (Expr x : tl.elements()) collectFreeVars(x, bound, outer, out, seen); }
            case Expr.SetLit sl -> { for (Expr x : sl.elements()) collectFreeVars(x, bound, outer, out, seen); }
            case Expr.DotAccess da -> collectFreeVars(da.target(), bound, outer, out, seen);
            case Expr.Block blk -> {
                Set<String> bound2 = new HashSet<>(bound);
                for (Stmt st : blk.stmts()) collectFreeVarsStmt(st, bound2, outer, out, seen);
            }
            case Expr.MapLit ml -> {
                for (Expr.MapEntry en : ml.entries()) {
                    switch (en) {
                        case Expr.MapEntry.Field f ->
                                collectFreeVars(f.value(), bound, outer, out, seen);
                        case Expr.MapEntry.DynField df -> {
                            collectFreeVars(df.keyExpr(), bound, outer, out, seen);
                            collectFreeVars(df.value(), bound, outer, out, seen);
                        }
                        case Expr.MapEntry.Spread sp ->
                                collectFreeVars(new Expr.Var(sp.name(), null), bound, outer, out, seen);
                    }
                }
            }
            case Expr.RecordUpdate ru -> {
                collectFreeVars(new Expr.Var(ru.base(), null), bound, outer, out, seen);
                for (Expr.MapEntry en : ru.updates()) {
                    if (en instanceof Expr.MapEntry.Field f) {
                        collectFreeVars(f.value(), bound, outer, out, seen);
                    }
                }
            }
            case Expr.Pipe p -> {
                collectFreeVars(p.left(), bound, outer, out, seen);
                collectFreeVars(p.right(), bound, outer, out, seen);
            }
            case Expr.Compose c -> {
                collectFreeVars(c.left(), bound, outer, out, seen);
                collectFreeVars(c.right(), bound, outer, out, seen);
            }
            case Expr.SeqOp so -> {
                if (so.arg() != null) collectFreeVars(so.arg(), bound, outer, out, seen);
            }
            case Expr.StringInterp si -> {
                for (Expr.StringPart part : si.parts()) {
                    if (part instanceof Expr.StringPart.Interpolation ip) {
                        collectFreeVars(ip.expr(), bound, outer, out, seen);
                    }
                }
            }
            case Expr.Range r -> {
                collectFreeVars(r.from(), bound, outer, out, seen);
                collectFreeVars(r.to(), bound, outer, out, seen);
            }
            case Expr.DoExpr de -> {
                for (Expr x : de.exprs()) collectFreeVars(x, bound, outer, out, seen);
            }
            default -> { /* literals, TypeRef, Keyword — no free vars */ }
        }
    }


    void collectFreeVarsStmt(Stmt s, Set<String> bound, Locals outer,
                                       List<String> out, Set<String> seen) {
        switch (s) {
            case Stmt.ExprStmt es -> collectFreeVars(es.expr(), bound, outer, out, seen);
            case Stmt.Bind b -> {
                collectFreeVars(b.value(), bound, outer, out, seen);
                if (b.target() instanceof Stmt.BindTarget.Simple si) bound.add(si.name());
                else if (b.target() instanceof Stmt.BindTarget.Destructure dp) ce.patEm.collectPatternBinds(dp.pattern(), bound);
            }
            case Stmt.MutBind mb -> {
                collectFreeVars(mb.value(), bound, outer, out, seen);
                if (mb.target() instanceof Stmt.BindTarget.Simple si) bound.add(si.name());
            }
            case Stmt.Assign a -> collectFreeVars(a.value(), bound, outer, out, seen);
            case Stmt.IfStmt ifs -> {
                collectFreeVars(ifs.cond(), bound, outer, out, seen);
                Set<String> bThen = new HashSet<>(bound);
                for (Stmt t : ifs.thenBranch()) collectFreeVarsStmt(t, bThen, outer, out, seen);
                if (ifs.elseBranch() != null) {
                    Set<String> bElse = new HashSet<>(bound);
                    for (Stmt t : ifs.elseBranch()) collectFreeVarsStmt(t, bElse, outer, out, seen);
                }
            }
            case Stmt.With w -> {
                // The handler expression + body can both reference outer
                // locals. Without this case, nested `with` blocks in a fn
                // body would drop fn-param captures from the SM step's
                // capture list — the inner step then can't see them and
                // names fall through to the "Unbound variable" runtime
                // lookup. Each `with` body opens its own scope; new binds
                // inside the body don't leak out, so use a fresh bound-set
                // copy (same shape as the IfStmt case above).
                collectFreeVars(w.handler(), bound, outer, out, seen);
                Set<String> bBody = new HashSet<>(bound);
                for (Stmt t : w.body()) collectFreeVarsStmt(t, bBody, outer, out, seen);
                if (w.onFailure() != null) {
                    Set<String> bOf = new HashSet<>(bound);
                    for (Stmt t : w.onFailure()) collectFreeVarsStmt(t, bOf, outer, out, seen);
                }
            }
            case Stmt.Scope sc -> {
                Set<String> bBody = new HashSet<>(bound);
                bBody.add(sc.name());                  // scope handle name
                for (Stmt t : sc.body()) collectFreeVarsStmt(t, bBody, outer, out, seen);
            }
            case Stmt.MatchStmt ms -> {
                collectFreeVars(ms.scrutinee(), bound, outer, out, seen);
                for (Expr.MatchArm arm : ms.arms()) {
                    Set<String> b2 = new HashSet<>(bound);
                    ce.patEm.collectPatternBinds(arm.pattern(), b2);
                    collectFreeVars(arm.guard(), b2, outer, out, seen);
                    collectFreeVars(arm.body(), b2, outer, out, seen);
                }
            }
            default -> {}
        }
    }
}
