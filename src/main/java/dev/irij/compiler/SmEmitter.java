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

final class SmEmitter implements Opcodes {

    private final ClassEmitter ce;

    SmEmitter(ClassEmitter ce) { this.ce = ce; }

    /**
     * Lower a `with` via state-machine: emit a step IrijFn via invokedynamic,
     * allocate a continuation, call RuntimeSupport.runWithSM.
     */
    void emitWithSM(Stmt.With w, List<Stmt> body, WithBodyShape shape,
                             MethodVisitor mv, Locals outer) {
        int resultSlot = outer.allocateAnon();
        boolean hasOnFailure = w.onFailure() != null && !w.onFailure().isEmpty();

        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label catchL = new Label();
        Label end = new Label();
        if (hasOnFailure) {
            mv.visitTryCatchBlock(tryStart, tryEnd, catchL, "java/lang/RuntimeException");
            mv.visitLabel(tryStart);
        }

        // Push handler value.
        ce.exprEm.emitExpr(w.handler(), mv, outer);

        // Collect free variables in body that resolve to outer locals — these
        // become step captures, same mechanism as emitLambda.
        Set<String> bound = new HashSet<>();
        List<String> captures = new ArrayList<>();
        for (Stmt s : body) ce.lamEm.collectFreeVarsStmt(s, bound, outer, captures, new HashSet<>());

        // Emit step method + return IrijFn on stack.
        emitSMStep(shape, body, captures, mv, outer);

        // Push nFields: number of lifted locals (0 for Pure/SingleOp).
        int nFields = switch (shape) {
            case WithBodyShape.Sequence seq -> seq.liftedLocals().size();
            case WithBodyShape.EffIR eir -> eir.liftedLocals().size();
            default -> 0;
        };
        ce.exprEm.pushIconst(mv, nFields);

        // RuntimeSupport.runWithSM(Object, IrijFn, int) -> Object
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("runWithSM"), "runWithSM",
                "(Ljava/lang/Object;L" + ClassEmitter.IRIJ_FN + ";I)Ljava/lang/Object;", false);
        mv.visitVarInsn(ASTORE, resultSlot);

        if (hasOnFailure) {
            mv.visitLabel(tryEnd);
            mv.visitJumpInsn(GOTO, end);

            mv.visitLabel(catchL);
            int teSlot = outer.allocateAnon();
            mv.visitVarInsn(ASTORE, teSlot);
            Locals ofLocals = outer.childScope();
            int errorSlot = ofLocals.allocate("error");
            mv.visitVarInsn(ALOAD, teSlot);
            mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("errorMessage"), "errorMessage",
                    "(Ljava/lang/Throwable;)Ljava/lang/String;", false);
            mv.visitVarInsn(ASTORE, errorSlot);
            List<Stmt> of = w.onFailure();
            for (int i = 0; i < of.size() - 1; i++) ce.exprEm.emitStmt(of.get(i), mv, ofLocals);
            Stmt last = of.get(of.size() - 1);
            if (!ce.exprEm.emitTailStmtValue(last, mv, ofLocals)) {
                ce.exprEm.emitStmt(last, mv, ofLocals);
                mv.visitInsn(ACONST_NULL);
            }
            mv.visitVarInsn(ASTORE, resultSlot);

            mv.visitLabel(end);
        }
        mv.visitVarInsn(ALOAD, resultSlot);
    }


    /**
     * Emit the step function for a `with` body as a private static method,
     * then push an IrijFn view via invokedynamic (captures become bound args).
     *
     * Descriptor: (captures..., Object[] args) -> Object
     * where args = [IrijContinuation, resumeValue].
     */
    void emitSMStep(WithBodyShape shape, List<Stmt> body,
                             List<String> captures, MethodVisitor mv, Locals outerLocals) {
        int id = ce.lambdaCounter++;
        String methodName = "smstep$" + id;
        StringBuilder desc = new StringBuilder("(");
        for (int i = 0; i < captures.size(); i++) desc.append(ClassEmitter.OBJ_DESC);
        desc.append("[Ljava/lang/Object;)Ljava/lang/Object;");
        MethodVisitor sm = ce.classWriter.visitMethod(
                ACC_STATIC | ACC_SYNTHETIC,
                methodName, desc.toString(), null, null);
        sm.visitCode();

        Locals inner = new Locals();
        for (String cap : captures) inner.allocate(cap);
        int argsSlot = inner.allocateAnon();
        int kSlot = inner.allocateAnon();    // continuation
        int vSlot = inner.allocateAnon();    // resume value

        // kSlot = (IrijContinuation) args[0]
        sm.visitVarInsn(ALOAD, argsSlot);
        ce.exprEm.pushIconst(sm, 0);
        sm.visitInsn(AALOAD);
        sm.visitTypeInsn(CHECKCAST, ClassEmitter.CONT);
        sm.visitVarInsn(ASTORE, kSlot);
        // vSlot = args[1]
        sm.visitVarInsn(ALOAD, argsSlot);
        ce.exprEm.pushIconst(sm, 1);
        sm.visitInsn(AALOAD);
        sm.visitVarInsn(ASTORE, vSlot);

        switch (shape) {
            case WithBodyShape.Pure p -> emitSMStateBody(body, sm, inner);
            case WithBodyShape.SingleOp so -> emitSMSingleOp(so, body, sm, inner, kSlot, vSlot);
            case WithBodyShape.Sequence seq -> emitSMSequence(seq, sm, inner, kSlot, vSlot);
            case WithBodyShape.EffIR eir -> emitSMEffIR(eir, sm, inner, kSlot, vSlot);
            case WithBodyShape.Unsupported ignored ->
                    throw new IrijCompiler.CompileException("internal: Unsupported in emitSMStep");
        }

        sm.visitMaxs(0, 0);
        sm.visitEnd();

        // Call site: push captures, invokedynamic → IrijFn.
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
    }


    /** Pure body: single-state step. Just emit statements; return last value. */
    void emitSMStateBody(List<Stmt> body, MethodVisitor sm, Locals inner) {
        emitBlockStmtsReturning(body, sm, inner);
    }


    /** Single-op body: 2-state switch. */
    void emitSMSingleOp(WithBodyShape.SingleOp so, List<Stmt> body,
                                 MethodVisitor sm, Locals inner, int kSlot, int vSlot) {
        List<Stmt> preStmts = body.subList(0, so.idx());
        List<Stmt> postStmts = body.subList(so.idx() + 1, body.size());

        Label state0 = new Label();
        Label state1 = new Label();
        Label errLabel = new Label();
        Label end = new Label();

        // switch (k.state)
        sm.visitVarInsn(ALOAD, kSlot);
        sm.visitFieldInsn(GETFIELD, ClassEmitter.CONT, "state", "I");
        sm.visitTableSwitchInsn(0, 1, errLabel, state0, state1);

        // state 0: pre-stmts (as statements, no return); set state=1; throw signal.
        sm.visitLabel(state0);
        emitBlockStmtsAsStatements(preStmts, sm, inner);
        sm.visitVarInsn(ALOAD, kSlot);
        ce.exprEm.pushIconst(sm, 1);
        sm.visitFieldInsn(PUTFIELD, ClassEmitter.CONT, "state", "I");
        // PerformSignal.of(effectName, opName, argsArray, k)
        String effectName = ce.effectOps.get(so.opName());
        sm.visitLdcInsn(effectName);
        sm.visitLdcInsn(so.opName());
        // args array — strip single-unit arg ( `op ()` )
        List<Expr> callArgs = so.opArgs();
        if (callArgs.size() == 1 && callArgs.get(0) instanceof Expr.UnitLit) callArgs = List.of();
        ce.exprEm.pushObjectArray(callArgs, sm, inner);
        sm.visitVarInsn(ALOAD, kSlot);
        sm.visitMethodInsn(INVOKESTATIC, ClassEmitter.PERF_SIGNAL, "of",
                "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;"
                        + ClassEmitter.CONT_DESC + ")L" + ClassEmitter.PERF_SIGNAL + ";",
                false);
        sm.visitInsn(ATHROW);

        // state 1: bind resume value if `x := op args`, emit post-stmts, return.
        sm.visitLabel(state1);
        if (so.bindName() != null) {
            int bindSlot = inner.allocate(so.bindName());
            sm.visitVarInsn(ALOAD, vSlot);
            sm.visitVarInsn(ASTORE, bindSlot);
        }
        // Bare-op-tail: body is just `op args` with no postStmts and no
        // bind — the resume value IS the body's final value. Return it.
        if (postStmts.isEmpty() && so.bindName() == null) {
            sm.visitVarInsn(ALOAD, vSlot);
            sm.visitInsn(ARETURN);
        } else {
            emitBlockStmtsReturning(postStmts, sm, inner);
        }
        sm.visitJumpInsn(GOTO, end);

        // default: IllegalStateException
        sm.visitLabel(errLabel);
        sm.visitTypeInsn(NEW, "java/lang/IllegalStateException");
        sm.visitInsn(DUP);
        sm.visitLdcInsn("bad state");
        sm.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException",
                "<init>", "(Ljava/lang/String;)V", false);
        sm.visitInsn(ATHROW);

        sm.visitLabel(end);
    }


    /** Multi-op body with local lifting: N-state switch, all Binds/resume-binds
     *  routed through k.fields[]. */
    void emitSMSequence(WithBodyShape.Sequence seq, MethodVisitor sm,
                                 Locals inner, int kSlot, int vSlot) {
        Map<String, Integer> lifted = new java.util.LinkedHashMap<>();
        for (int i = 0; i < seq.liftedLocals().size(); i++) {
            lifted.put(seq.liftedLocals().get(i), i);
        }
        Map<String, Integer> savedLifted = ce.currentLiftedLocals;
        int savedKSlot = ce.currentKSlot;
        ce.currentLiftedLocals = lifted;
        ce.currentKSlot = kSlot;
        try {
            int nStates = seq.segments().size();
            Label[] stateLabels = new Label[nStates];
            for (int i = 0; i < nStates; i++) stateLabels[i] = new Label();
            Label errLabel = new Label();
            Label end = new Label();

            // switch (k.state)
            sm.visitVarInsn(ALOAD, kSlot);
            sm.visitFieldInsn(GETFIELD, ClassEmitter.CONT, "state", "I");
            sm.visitTableSwitchInsn(0, nStates - 1, errLabel, stateLabels);

            for (int i = 0; i < nStates; i++) {
                sm.visitLabel(stateLabels[i]);
                Segment seg = seq.segments().get(i);

                // On re-entry (i>0): if previous segment was an op-with-bind,
                // store vSlot into k.fields[bindIdx]. Inner-with segments
                // don't yield a resume-bind here — their value is consumed
                // inline by the runWithSM call site, not via vSlot.
                if (i > 0) {
                    Segment prev = seq.segments().get(i - 1);
                    if (prev.opName() != null && prev.bindName() != null) {
                        Integer idx = lifted.get(prev.bindName());
                        if (idx != null) {
                            sm.visitVarInsn(ALOAD, kSlot);
                            sm.visitFieldInsn(GETFIELD, ClassEmitter.CONT, "fields", "[Ljava/lang/Object;");
                            ce.exprEm.pushIconst(sm, idx);
                            sm.visitVarInsn(ALOAD, vSlot);
                            sm.visitInsn(AASTORE);
                        }
                    }
                }

                if (seg.innerWith() != null) {
                    // Nested-with segment: run the inner `with` as a state of
                    // the outer SM. The inner continuation is persisted in
                    // the outer's k.fields[innerSlot]; on outer-resume after
                    // an inner-leaked PerformSignal, this state re-executes
                    // and runWithSM detects kInner.state != 0 to resume the
                    // inner body where it left off (with vSlot threaded down).
                    emitBlockStmtsAsStatements(seg.pureStmts(), sm, inner);
                    emitInnerWithCall(seg, sm, inner, kSlot, vSlot);
                    // Stash the inner-with's value in vSlot so a trailing
                    // empty segment can ARETURN it (analogous to bare-op
                    // tail). Subsequent op states overwrite vSlot anyway.
                    sm.visitVarInsn(ASTORE, vSlot);
                    // If `r := with X body`, also store value into k.fields[bindIdx]
                    // so subsequent segments reading `r` find it lifted.
                    if (seg.innerBind() != null) {
                        Integer bindIdx = lifted.get(seg.innerBind());
                        if (bindIdx != null) {
                            sm.visitVarInsn(ALOAD, kSlot);
                            sm.visitFieldInsn(GETFIELD, ClassEmitter.CONT, "fields", "[Ljava/lang/Object;");
                            ce.exprEm.pushIconst(sm, bindIdx);
                            sm.visitVarInsn(ALOAD, vSlot);
                            sm.visitInsn(AASTORE);
                        }
                    }
                    // Bump state and fall through to the next state's body.
                    sm.visitVarInsn(ALOAD, kSlot);
                    ce.exprEm.pushIconst(sm, i + 1);
                    sm.visitFieldInsn(PUTFIELD, ClassEmitter.CONT, "state", "I");
                    sm.visitJumpInsn(GOTO, stateLabels[i + 1]);
                } else if (seg.opName() == null) {
                    // Final segment: emit pure stmts with last as return value.
                    // Special case: body ends with a bare op call OR a nested
                    // with — vSlot holds the resume / inner value, return it.
                    boolean prevYieldsValue = i > 0 && (
                            seq.segments().get(i - 1).opName() != null
                            || seq.segments().get(i - 1).innerWith() != null);
                    if (seg.pureStmts().isEmpty() && prevYieldsValue) {
                        sm.visitVarInsn(ALOAD, vSlot);
                        sm.visitInsn(ARETURN);
                    } else {
                        emitBlockStmtsReturning(seg.pureStmts(), sm, inner);
                    }
                } else {
                    // Intermediate op: pure stmts as stmts, bump state, throw.
                    emitBlockStmtsAsStatements(seg.pureStmts(), sm, inner);
                    sm.visitVarInsn(ALOAD, kSlot);
                    ce.exprEm.pushIconst(sm, i + 1);
                    sm.visitFieldInsn(PUTFIELD, ClassEmitter.CONT, "state", "I");
                    String effectName = ce.effectOps.get(seg.opName());
                    sm.visitLdcInsn(effectName);
                    sm.visitLdcInsn(seg.opName());
                    List<Expr> callArgs = seg.opArgs();
                    if (callArgs.size() == 1 && callArgs.get(0) instanceof Expr.UnitLit) {
                        callArgs = List.of();
                    }
                    ce.exprEm.pushObjectArray(callArgs, sm, inner);
                    sm.visitVarInsn(ALOAD, kSlot);
                    sm.visitMethodInsn(INVOKESTATIC, ClassEmitter.PERF_SIGNAL, "of",
                            "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;"
                                    + ClassEmitter.CONT_DESC + ")L" + ClassEmitter.PERF_SIGNAL + ";",
                            false);
                    sm.visitInsn(ATHROW);
                }
            }

            // default: bad state
            sm.visitLabel(errLabel);
            sm.visitTypeInsn(NEW, "java/lang/IllegalStateException");
            sm.visitInsn(DUP);
            sm.visitLdcInsn("bad state");
            sm.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException",
                    "<init>", "(Ljava/lang/String;)V", false);
            sm.visitInsn(ATHROW);

            sm.visitLabel(end);
        } finally {
            ce.currentLiftedLocals = savedLifted;
            ce.currentKSlot = savedKSlot;
        }
    }


    /**
     * Emit a nested `with` call as a state of the outer SM: alloc-or-fetch
     * the inner continuation from k.fields[innerSlot], compile the inner
     * step, and call the 3-arg {@code runWithSM(handler, kInner, vSlot)}
     * which thread the outer's resume value down on re-entry.
     *
     * <p>Result: inner-with's value left on the JVM operand stack. Caller
     * is responsible for either consuming or POP'ing it.
     */
    void emitInnerWithCall(Segment seg, MethodVisitor sm, Locals inner,
                                    int kSlot, int vSlot) {
        Stmt.With w = seg.innerWith();
        boolean hasOnFailure = w.onFailure() != null && !w.onFailure().isEmpty();

        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label catchL = new Label();
        Label end = new Label();
        if (hasOnFailure) {
            // RuntimeException catch — but PerformSignal/TailResume must
            // propagate to the outer trampoline. Filtered inside the
            // catch block by re-throwing the SM-control exceptions.
            sm.visitTryCatchBlock(tryStart, tryEnd, catchL, "java/lang/RuntimeException");
            sm.visitLabel(tryStart);
        }

        emitInnerWithCallCore(w, seg, sm, inner, kSlot, vSlot);

        if (hasOnFailure) {
            sm.visitLabel(tryEnd);
            sm.visitJumpInsn(GOTO, end);

            sm.visitLabel(catchL);
            int teSlot = inner.allocateAnon();
            sm.visitVarInsn(ASTORE, teSlot);

            // Re-throw PerformSignal — it's not a failure, it's a yield.
            sm.visitVarInsn(ALOAD, teSlot);
            sm.visitTypeInsn(INSTANCEOF, ClassEmitter.PERF_SIGNAL);
            Label notPerform = new Label();
            sm.visitJumpInsn(IFEQ, notPerform);
            sm.visitVarInsn(ALOAD, teSlot);
            sm.visitInsn(ATHROW);
            sm.visitLabel(notPerform);

            // Re-throw TailResume — it targets a specific dispatch loop.
            sm.visitVarInsn(ALOAD, teSlot);
            sm.visitTypeInsn(INSTANCEOF, ClassEmitter.TAIL_RESUME);
            Label notTailResume = new Label();
            sm.visitJumpInsn(IFEQ, notTailResume);
            sm.visitVarInsn(ALOAD, teSlot);
            sm.visitInsn(ATHROW);
            sm.visitLabel(notTailResume);

            // Genuine failure → run on-failure block.
            Locals ofLocals = inner.childScope();
            int errorSlot = ofLocals.allocate("error");
            sm.visitVarInsn(ALOAD, teSlot);
            sm.visitMethodInsn(INVOKESTATIC, RtOwners.of("errorMessage"), "errorMessage",
                    "(Ljava/lang/Throwable;)Ljava/lang/String;", false);
            sm.visitVarInsn(ASTORE, errorSlot);
            List<Stmt> of = w.onFailure();
            for (int i = 0; i < of.size() - 1; i++) ce.exprEm.emitStmt(of.get(i), sm, ofLocals);
            Stmt last = of.get(of.size() - 1);
            if (!ce.exprEm.emitTailStmtValue(last, sm, ofLocals)) {
                ce.exprEm.emitStmt(last, sm, ofLocals);
                sm.visitInsn(ACONST_NULL);
            }

            sm.visitLabel(end);
        }
    }


    /** Core emit of the inner runWithSM call (no on-failure wrap). */
    void emitInnerWithCallCore(Stmt.With w, Segment seg, MethodVisitor sm,
                                        Locals inner, int kSlot, int vSlot) {
        List<Stmt> innerBody = ce.smCls.aNormalize(w.body());
        WithBodyShape innerShape = ce.smCls.classifyWithBody(innerBody);
        int innerNFields = switch (innerShape) {
            case WithBodyShape.Sequence s -> s.liftedLocals().size();
            case WithBodyShape.EffIR e -> e.liftedLocals().size();
            default -> 0;
        };

        // Push: handler, kInner, resumeValue → runWithSM(Object, IrijContinuation, Object)
        ce.exprEm.emitExpr(w.handler(), sm, inner);

        // RT.getOrAllocInnerCont(kOuter, slot, step, nFields) → kInner
        sm.visitVarInsn(ALOAD, kSlot);
        ce.exprEm.pushIconst(sm, seg.innerSlot());
        // Free vars of inner body that resolve to outer locals = step captures.
        Set<String> bound = new HashSet<>();
        List<String> captures = new ArrayList<>();
        for (Stmt s : innerBody) {
            ce.lamEm.collectFreeVarsStmt(s, bound, inner, captures, new HashSet<>());
        }
        emitSMStep(innerShape, innerBody, captures, sm, inner);
        ce.exprEm.pushIconst(sm, innerNFields);
        sm.visitMethodInsn(INVOKESTATIC, RtOwners.of("getOrAllocInnerCont"), "getOrAllocInnerCont",
                "(" + ClassEmitter.CONT_DESC + "IL" + ClassEmitter.IRIJ_FN + ";I)" + ClassEmitter.CONT_DESC, false);

        sm.visitVarInsn(ALOAD, vSlot);
        sm.visitMethodInsn(INVOKESTATIC, RtOwners.of("runWithSM"), "runWithSM",
                "(Ljava/lang/Object;" + ClassEmitter.CONT_DESC + "Ljava/lang/Object;)Ljava/lang/Object;",
                false);
    }


    /** Emit an EffIR CFG: one tableswitch entry per block (for resumption),
     *  plus intra-step JVM GOTOs for Jump/Branch. Each block has two labels:
     *  {@code hdrLabels[id]} is the resumption target (does the resume-bind
     *  store if any); {@code bodyLabels[id]} is the intra-step entry used
     *  by Jump/Branch (skips the resume-bind store). */
    void emitSMEffIR(WithBodyShape.EffIR eir, MethodVisitor sm,
                              Locals inner, int kSlot, int vSlot) {
        Map<String, Integer> lifted = new LinkedHashMap<>();
        for (int i = 0; i < eir.liftedLocals().size(); i++) {
            lifted.put(eir.liftedLocals().get(i), i);
        }
        Map<String, Integer> savedLifted = ce.currentLiftedLocals;
        int savedKSlot = ce.currentKSlot;
        ce.currentLiftedLocals = lifted;
        ce.currentKSlot = kSlot;
        try {
            int n = eir.blocks().size();
            Label[] hdr = new Label[n];
            Label[] body = new Label[n];
            for (int i = 0; i < n; i++) { hdr[i] = new Label(); body[i] = new Label(); }
            Label errLabel = new Label();

            sm.visitVarInsn(ALOAD, kSlot);
            sm.visitFieldInsn(GETFIELD, ClassEmitter.CONT, "state", "I");
            sm.visitTableSwitchInsn(0, n - 1, errLabel, hdr);

            for (int i = 0; i < n; i++) {
                BB b = eir.blocks().get(i);
                sm.visitLabel(hdr[i]);
                // Resume-bind: store vSlot into k.fields[idx]
                String rb = eir.resumeBindOf().get(i);
                if (rb != null) {
                    Integer idx = lifted.get(rb);
                    if (idx != null) {
                        sm.visitVarInsn(ALOAD, kSlot);
                        sm.visitFieldInsn(GETFIELD, ClassEmitter.CONT, "fields", "[Ljava/lang/Object;");
                        ce.exprEm.pushIconst(sm, idx);
                        sm.visitVarInsn(ALOAD, vSlot);
                        sm.visitInsn(AASTORE);
                    }
                }
                sm.visitLabel(body[i]);
                // Emit pure statements.
                for (Stmt s : b.pure()) ce.exprEm.emitStmt(s, sm, inner);
                // Terminator.
                switch (b.term()) {
                    case Term.Return r -> {
                        if (r.expr() == null) {
                            sm.visitInsn(ACONST_NULL);
                        } else {
                            ce.exprEm.emitExpr(r.expr(), sm, inner);
                        }
                        sm.visitInsn(ARETURN);
                    }
                    case Term.Perform p -> {
                        sm.visitVarInsn(ALOAD, kSlot);
                        ce.exprEm.pushIconst(sm, p.next());
                        sm.visitFieldInsn(PUTFIELD, ClassEmitter.CONT, "state", "I");
                        sm.visitLdcInsn(p.effectName());
                        sm.visitLdcInsn(p.opName());
                        List<Expr> callArgs = p.args();
                        if (callArgs.size() == 1
                                && callArgs.get(0) instanceof Expr.UnitLit) {
                            callArgs = List.of();
                        }
                        ce.exprEm.pushObjectArray(callArgs, sm, inner);
                        sm.visitVarInsn(ALOAD, kSlot);
                        sm.visitMethodInsn(INVOKESTATIC, ClassEmitter.PERF_SIGNAL, "of",
                                "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;"
                                        + ClassEmitter.CONT_DESC + ")L" + ClassEmitter.PERF_SIGNAL + ";",
                                false);
                        sm.visitInsn(ATHROW);
                    }
                    case Term.Branch br -> {
                        ce.exprEm.emitExpr(br.cond(), sm, inner);
                        sm.visitMethodInsn(INVOKESTATIC, RtOwners.of("truthy"), "truthy",
                                "(Ljava/lang/Object;)Z", false);
                        sm.visitJumpInsn(IFEQ, body[br.elseId()]);
                        sm.visitJumpInsn(GOTO, body[br.thenId()]);
                    }
                    case Term.Jump j -> sm.visitJumpInsn(GOTO, body[j.target()]);
                }
            }

            sm.visitLabel(errLabel);
            sm.visitTypeInsn(NEW, "java/lang/IllegalStateException");
            sm.visitInsn(DUP);
            sm.visitLdcInsn("bad state");
            sm.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalStateException",
                    "<init>", "(Ljava/lang/String;)V", false);
            sm.visitInsn(ATHROW);
        } finally {
            ce.currentLiftedLocals = savedLifted;
            ce.currentKSlot = savedKSlot;
        }
    }


    /** Emit stmts discarding non-last values; last stmt's value returned via ARETURN. */
    void emitBlockStmtsReturning(List<Stmt> stmts, MethodVisitor sm, Locals inner) {
        if (stmts.isEmpty()) {
            sm.visitInsn(ACONST_NULL);
            sm.visitInsn(ARETURN);
            return;
        }
        for (int i = 0; i < stmts.size() - 1; i++) ce.exprEm.emitStmt(stmts.get(i), sm, inner);
        Stmt last = stmts.get(stmts.size() - 1);
        if (!ce.exprEm.emitTailStmtValue(last, sm, inner)) {
            ce.exprEm.emitStmt(last, sm, inner);
            sm.visitInsn(ACONST_NULL);
        }
        sm.visitInsn(ARETURN);
    }


    /** Emit stmts as pure statements (no value left on stack afterwards). */
    void emitBlockStmtsAsStatements(List<Stmt> stmts, MethodVisitor sm, Locals inner) {
        for (Stmt s : stmts) ce.exprEm.emitStmt(s, sm, inner);
    }


    /** True if a declared effect-row should map to RT's AMBIENT frame
     *  (caller's effects flow through unchanged): contains `Any`, or
     *  contains a parametric row-variable (lowercase first char). */
    static boolean isAmbientRow(java.util.List<String> row) {
        if (row == null) return false; // null = unannotated → pure
        if (row.contains("Any")) return true;
        for (String e : row) {
            if (e == null || e.isEmpty()) continue;
            if (Character.isLowerCase(e.charAt(0))) return true;
        }
        return false;
    }


    /** Push a {@code String[]} constant onto the operand stack. Used to
     *  pass a fn's declared effect row to {@code RT.enterFn}. */
    void emitStringArrayConst(MethodVisitor mv, java.util.List<String> row) {
        int n = row == null ? 0 : row.size();
        ce.exprEm.pushIconst(mv, n);
        mv.visitTypeInsn(ANEWARRAY, "java/lang/String");
        if (row != null) {
            for (int i = 0; i < row.size(); i++) {
                mv.visitInsn(DUP);
                ce.exprEm.pushIconst(mv, i);
                mv.visitLdcInsn(row.get(i));
                mv.visitInsn(AASTORE);
            }
        }
    }


    /** Emit a lambda literal: synthesize a static method + invokedynamic creating an IrijFn. */
    /**
     * Tier-c clause emit: clause body compiled as an SM step function so its
     * own performs throw {@link RuntimeSupport#PerformSignal} (resumable),
     * caught by the next-outer SM frame via {@code SM_STACK} fallback.
     *
     * <p>The wrapper IrijFn we push onto the operand stack receives
     * {@code [opArgs..., resume]} when invoked, allocates a fresh
     * {@code IrijContinuation} whose fields are pre-populated with the args
     * and resume fn, and runs it via {@link RuntimeSupport#runWithSMNoHs}.
     */
    void emitTierCClauseLambda(Decl.HandlerClause c,
                                        List<Pattern> clauseParams,
                                        MethodVisitor mv,
                                        Locals outerLocals) {
        // Param names from clauseParams (last is "resume"). Strip wildcards.
        List<String> paramNames = new ArrayList<>();
        for (Pattern p : clauseParams) {
            paramNames.add(switch (p) {
                case Pattern.VarPat v -> v.name();
                case Pattern.WildcardPat __ -> "_";
                default -> throw new IrijCompiler.CompileException(
                        "tier-c clause: unsupported param pattern");
            });
        }
        // paramNames.size() = nOpArgs + 1 (resume).
        int nFieldsForArgs = paramNames.size();

        // Body → stmts list
        List<Stmt> stmts;
        if (c.body() instanceof Expr.Block blk) {
            stmts = new ArrayList<>(blk.stmts());
        } else {
            stmts = new ArrayList<>(List.of(new Stmt.ExprStmt(c.body(), null)));
        }
        stmts = ce.smCls.aNormalize(stmts);
        WithBodyShape shape = ce.smCls.classifyWithBody(stmts);
        WithBodyShape.Sequence seq;
        if (shape instanceof WithBodyShape.Sequence s) {
            seq = s;
        } else if (shape instanceof WithBodyShape.SingleOp so) {
            seq = ce.smCls.singleOpToSequence(stmts, so);
        } else {
            throw new IrijCompiler.CompileException(
                    "tier-c clause: only Sequence/SingleOp shape supported (v1)");
        }

        // Augmented lifted: paramNames + classifier-lifted. emitSMSequence
        // uses currentLiftedLocals to route Var loads through k.fields[].
        List<String> augLifted = new ArrayList<>(paramNames);
        for (String n : seq.liftedLocals()) {
            // Avoid clashes (shouldn't happen for fresh Bind names).
            if (!augLifted.contains(n)) augLifted.add(n);
        }
        int totalNFields = augLifted.size();
        WithBodyShape.Sequence augShape = new WithBodyShape.Sequence(
                seq.segments(), augLifted);

        // 1. Emit the step method.
        int id = ce.lambdaCounter++;
        String stepName = "clauseStep$tierC$" + id;
        String stepDesc = "([Ljava/lang/Object;)Ljava/lang/Object;";
        MethodVisitor sm = ce.classWriter.visitMethod(
                ACC_STATIC | ACC_SYNTHETIC,
                stepName, stepDesc, null, null);
        sm.visitCode();
        Locals stepLocals = new Locals();
        int argsSlot = stepLocals.allocateAnon();
        int kSlot = stepLocals.allocateAnon();
        int vSlot = stepLocals.allocateAnon();
        sm.visitVarInsn(ALOAD, argsSlot); ce.exprEm.pushIconst(sm, 0); sm.visitInsn(AALOAD);
        sm.visitTypeInsn(CHECKCAST, ClassEmitter.CONT); sm.visitVarInsn(ASTORE, kSlot);
        sm.visitVarInsn(ALOAD, argsSlot); ce.exprEm.pushIconst(sm, 1); sm.visitInsn(AALOAD);
        sm.visitVarInsn(ASTORE, vSlot);
        emitSMSequence(augShape, sm, stepLocals, kSlot, vSlot);
        sm.visitMaxs(0, 0); sm.visitEnd();

        // 2. Emit wrapper IrijFn method.
        int wrapperId = ce.lambdaCounter++;
        String wrapperName = "clauseWrap$tierC$" + wrapperId;
        String wrapperDesc = "([Ljava/lang/Object;)Ljava/lang/Object;";
        MethodVisitor w = ce.classWriter.visitMethod(
                ACC_STATIC | ACC_SYNTHETIC,
                wrapperName, wrapperDesc, null, null);
        w.visitCode();
        int wArgsSlot = 0;
        int wKSlot = 1;

        Handle bsm = new Handle(H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;",
                false);
        Type samType = Type.getMethodType(ClassEmitter.APPLY_DESC);
        Handle stepHandle = new Handle(H_INVOKESTATIC, ce.internalName,
                stepName, stepDesc, false);

        // step IrijFn = LambdaMetafactory.metafactory(...) → IrijFn
        w.visitInvokeDynamicInsn("apply", "()" + ClassEmitter.IRIJ_FN_DESC, bsm,
                samType, stepHandle, samType);
        // newCont(stepFn, totalNFields) → kClause
        ce.exprEm.pushIconst(w, totalNFields);
        w.visitMethodInsn(INVOKESTATIC, RtOwners.of("newCont"), "newCont",
                "(L" + ClassEmitter.IRIJ_FN + ";I)" + ClassEmitter.CONT_DESC, false);
        w.visitVarInsn(ASTORE, wKSlot);

        // For each i in 0..nFieldsForArgs-1: k.fields[i] = args[i]
        for (int i = 0; i < nFieldsForArgs; i++) {
            w.visitVarInsn(ALOAD, wKSlot);
            w.visitFieldInsn(GETFIELD, ClassEmitter.CONT, "fields", "[Ljava/lang/Object;");
            ce.exprEm.pushIconst(w, i);
            w.visitVarInsn(ALOAD, wArgsSlot);
            ce.exprEm.pushIconst(w, i);
            w.visitInsn(AALOAD);
            w.visitInsn(AASTORE);
        }

        // RT.runWithSMNoHs(kClause)
        w.visitVarInsn(ALOAD, wKSlot);
        w.visitMethodInsn(INVOKESTATIC, RtOwners.of("runWithSMNoHs"), "runWithSMNoHs",
                "(" + ClassEmitter.CONT_DESC + ")Ljava/lang/Object;", false);
        w.visitInsn(ARETURN);
        w.visitMaxs(0, 0); w.visitEnd();

        // 3. Push wrapper as IrijFn at the call site.
        Handle wrapperHandle = new Handle(H_INVOKESTATIC, ce.internalName,
                wrapperName, wrapperDesc, false);
        mv.visitInvokeDynamicInsn("apply", "()" + ClassEmitter.IRIJ_FN_DESC, bsm,
                samType, wrapperHandle, samType);
    }
}
