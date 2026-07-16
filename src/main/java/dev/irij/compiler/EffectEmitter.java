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

final class EffectEmitter implements Opcodes {

    private final ClassEmitter ce;

    EffectEmitter(ClassEmitter ce) { this.ce = ce; }

    void emitHandlerStateInit(Decl.HandlerDecl hd, MethodVisitor mv, Locals locals) {
        Map<String, String> fields = ce.handlerStateFields.get(hd.name());
        if (fields == null) return;
        for (Stmt sb : hd.stateBindings()) {
            if (!(sb instanceof Stmt.MutBind mb)) continue;
            String stateName = stateBindingName(hd.name(), sb);
            String fieldName = fields.get(stateName);
            ce.exprEm.emitExpr(mb.value(), mv, locals);
            mv.visitFieldInsn(PUTSTATIC, ce.internalName, fieldName, ClassEmitter.OBJ_DESC);
        }
    }


    // ── Effects / handlers (14c.2 thread+channel, one-shot resume) ────

    void validateHandler14c2(Decl.HandlerDecl h) {
        for (Stmt sb : h.stateBindings()) {
            if (!(sb instanceof Stmt.MutBind mb)
                    || !(mb.target() instanceof Stmt.BindTarget.Simple)) {
                throw new IrijCompiler.CompileException(
                        "handler " + h.name() + ": state binding must be `name :! init`");
            }
        }
        // Required-effects (`::: E1 E2`) are informational at runtime: fireOp
        // dispatches via the handler STACK, not AVAILABLE_EFFECTS. Clause bodies
        // that perform outer effects resolve against the enclosing `with` stack.
    }


    static String stateBindingName(String handlerName, Stmt sb) {
        if (sb instanceof Stmt.MutBind mb
                && mb.target() instanceof Stmt.BindTarget.Simple s) {
            return s.name();
        }
        throw new IrijCompiler.CompileException(
                "handler " + handlerName + ": state binding must be `name :! init`");
    }


    static String handlerBuildName(String h) { return "handler$" + ClassEmitter.mangle(h) + "$build"; }


    /** Emit a static `handler$name$build() -> CompiledHandler` method. */
    void emitHandlerBuilder(Decl.HandlerDecl h, ClassWriter cw) {
        // Find effect name: handler's effectName field.
        String effectName = h.effectName();
        MethodVisitor mv = cw.visitMethod(ACC_STATIC,
                handlerBuildName(h.name()),
                "()L" + ClassEmitter.COMP_HANDLER + ";",
                null, null);
        mv.visitCode();

        // Build LinkedHashMap<String, IrijFn> of clauses.
        mv.visitTypeInsn(NEW, "java/util/LinkedHashMap");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/LinkedHashMap", "<init>", "()V", false);
        int mapSlot = 0;
        mv.visitVarInsn(ASTORE, mapSlot);

        Locals locals = new Locals();
        locals.allocate("$map"); // slot 0

        Map<String, String> savedState = ce.currentStateFields;
        Map<String, String> myState = ce.handlerStateFields.get(h.name());
        ce.currentStateFields = myState != null ? myState : Map.of();
        try {
        for (Decl.HandlerClause c : h.clauses()) {
            mv.visitVarInsn(ALOAD, mapSlot);
            mv.visitLdcInsn(c.opName());

            // Build clause params: strip lone UnitPat, then append resume VarPat.
            List<Pattern> params = c.params();
            boolean singleUnit = params.size() == 1 && params.get(0) instanceof Pattern.UnitPat;
            List<Pattern> clauseParams = new ArrayList<>();
            if (!singleUnit) {
                for (Pattern p : params) {
                    if (p instanceof Pattern.UnitPat) continue;
                    if (p instanceof Pattern.VarPat || p instanceof Pattern.WildcardPat) {
                        clauseParams.add(p);
                    } else {
                        throw new IrijCompiler.CompileException(
                                "handler " + h.name() + " clause " + c.opName()
                                        + ": only VarPat/WildcardPat/UnitPat params supported");
                    }
                }
            }
            clauseParams.add(new Pattern.VarPat("resume", null));

            // Tier-c: clause body performs a foreign effect. Compile body
            // as an SM continuation so the foreign perform throws a
            // PerformSignal that escapes to an enclosing SM frame.
            boolean isTierC = ce.smCls.exprPerformsForeignEffect(c.body(), h.effectName())
                    && ce.smCls.tierCClauseCompilable(c);

            if (isTierC) {
                ce.smEm.emitTierCClauseLambda(c, clauseParams, mv, locals);
            } else {
                Expr.Lambda clauseLam = new Expr.Lambda(clauseParams, null, c.body(), null);
                // Pass the handler's declared required effects to the
                // clause lambda so its body sees them on EFFECT_ROW.
                ce.pendingClauseEffects = h.requiredEffects();
                ce.lamEm.emitLambda(clauseLam, mv, locals);
            }

            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
            mv.visitInsn(POP);
        }

        } finally {
            ce.currentStateFields = savedState;
        }

        // new CompiledHandler(name, effectName, map)
        mv.visitTypeInsn(NEW, ClassEmitter.COMP_HANDLER);
        mv.visitInsn(DUP);
        mv.visitLdcInsn(h.name());
        mv.visitLdcInsn(effectName);
        mv.visitVarInsn(ALOAD, mapSlot);
        mv.visitMethodInsn(INVOKESPECIAL, ClassEmitter.COMP_HANDLER, "<init>",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;)V", false);
        mv.visitInsn(ARETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }


    void emitPerform(String opName, List<Expr> args, MethodVisitor mv, Locals locals) {
        // `() -> ()` effects are called `op ()` — strip single unit arg.
        if (args.size() == 1 && args.get(0) instanceof Expr.UnitLit) args = List.of();
        String effectName = ce.effectOps.get(opName);
        // Runtime effect-row check: throws if the enclosing fn doesn't
        // declare this effect (and we're not inside an ambient frame).
        mv.visitLdcInsn(effectName);
        mv.visitLdcInsn(opName);
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("checkPerformEffect"), "checkPerformEffect",
                "(Ljava/lang/String;Ljava/lang/String;)V", false);
        mv.visitLdcInsn(effectName);
        mv.visitLdcInsn(opName);
        ce.exprEm.pushObjectArray(args, mv, locals);
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("perform"), "perform",
                "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;", false);
    }


    /**
     * Emit `with handler body [on-failure block]` as an expression, leaving the
     * block's result on the stack. 14c.2: body runs on a virtual thread under
     * EffectSystem; handler clauses compiled as IrijFns receiving (args…, resume).
     */
    void emitWith(Stmt.With w, MethodVisitor mv, Locals outer) {
        // Static path: handler expression resolves to known handler
        // decls at compile time — push effects via constants, run SM
        // lowering normally.
        //
        // Opaque path (v0.8.0b): handler is a fn-param, computed
        // expression, or any value the compile-time analysis can't
        // resolve to a known handler. Evaluate the handler expression
        // at runtime, push its effects via RT.enterWithFromValue, run
        // the SM lowering with the resulting Object, pop on exit.
        boolean opaque = isOpaqueHandler(w.handler());

        if (opaque) {
            emitWithOpaque(w, mv, outer);
            return;
        }

        // ── Static path (the original v0.7.x emit) ──────────────────
        java.util.List<String> pushedEffects = new java.util.ArrayList<>();
        for (String hName : collectHandlerNames(w.handler())) {
            Decl.HandlerDecl hd = ce.handlers.get(hName);
            if (hd != null) {
                mv.visitLdcInsn(hd.effectName());
                mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("enterWith"), "enterWith",
                        "(Ljava/lang/String;)V", false);
                pushedEffects.add(hd.effectName());
            }
        }
        Label withStart = new Label();
        Label withEnd = new Label();
        Label withHandler = new Label();
        Label afterWith = new Label();
        mv.visitLabel(withStart);
        if (!ce.smCls.smCanHandle(w.handler())) {
            throw new IrijCompiler.CompileException(
                    "with: handler shape not supported by state-machine lowering at "
                            + (w.loc() != null ? w.loc().line() + ":" + w.loc().col() : "<unknown>"));
        }
        List<Stmt> body = ce.smCls.aNormalize(w.body());
        body = ce.smCls.expandDestructureBindsForSM(body);
        WithBodyShape shape = ce.smCls.classifyWithBody(body);
        if (shape instanceof WithBodyShape.Unsupported) {
            throw new IrijCompiler.CompileException(
                    "with: body shape not supported by state-machine lowering at "
                            + (w.loc() != null ? w.loc().line() + ":" + w.loc().col() : "<unknown>"));
        }
        ce.smEm.emitWithSM(w, body, shape, mv, outer);
        mv.visitLabel(withEnd);
        // Normal exit: pop each pushed frame, then GOTO afterWith.
        for (int i = 0; i < pushedEffects.size(); i++) {
            mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("exitWith"), "exitWith", "()V", false);
        }
        mv.visitJumpInsn(GOTO, afterWith);
        // Exception exit: pop, rethrow.
        mv.visitLabel(withHandler);
        for (int i = 0; i < pushedEffects.size(); i++) {
            mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("exitWith"), "exitWith", "()V", false);
        }
        mv.visitInsn(ATHROW);
        mv.visitTryCatchBlock(withStart, withEnd, withHandler, null);
        mv.visitLabel(afterWith);
    }


    /** True when the handler expression is something the compile-time
     *  analysis can't resolve to a known handler decl — fn-param,
     *  computed expression, conditional, etc. The opaque path
     *  defers the work to runtime: evaluate the expression, push
     *  effects from the resulting value, dispatch through runWithSM
     *  generically. */
    boolean isOpaqueHandler(Expr handlerExpr) {
        for (String name : collectHandlerNames(handlerExpr)) {
            if ("__unknown__".equals(name)) return true;
            if (!ce.handlers.containsKey(name)) {
                // Var pointing at a fn-param / non-decl — same opaque
                // shape (collectHandlerNames added the bare name when
                // there was no local-handler-binding to chase).
                if (ce.currentLocalHandlerBindings.containsKey(name)) continue;
                return true;
            }
        }
        return false;
    }


    /** Opaque-handler emit. Evaluates the handler expression to an
     *  Object (must be a CompiledHandler / CompiledComposedHandler at
     *  runtime — SpecValidator enforces this at fn boundaries when
     *  a `(Handler Eff)` spec is declared). Pushes effects via
     *  RT.enterWithFromValue, runs the SM body, pops via
     *  RT.exitWithCount(n) where n is the int returned by the entry
     *  helper. */
    void emitWithOpaque(Stmt.With w, MethodVisitor mv, Locals outer) {
        int handlerSlot = outer.allocateAnon();
        int countSlot = outer.allocateAnon();
        ce.exprEm.emitExpr(w.handler(), mv, outer);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, handlerSlot);
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("enterWithFromValue"), "enterWithFromValue",
                "(Ljava/lang/Object;)I", false);
        mv.visitVarInsn(ISTORE, countSlot);

        Label withStart = new Label();
        Label withEnd = new Label();
        Label withCatch = new Label();
        Label after = new Label();
        mv.visitLabel(withStart);

        // Classify body shape — same as the static path; SM lowering
        // accepts any body shape so long as the body itself is
        // representable (Pure / SingleOp / Sequence / EffIR).
        List<Stmt> body = ce.smCls.aNormalize(w.body());
        body = ce.smCls.expandDestructureBindsForSM(body);
        WithBodyShape shape = ce.smCls.classifyWithBody(body);
        if (shape instanceof WithBodyShape.Unsupported) {
            throw new IrijCompiler.CompileException(
                    "with: body shape not supported by state-machine lowering at "
                            + (w.loc() != null ? w.loc().line() + ":" + w.loc().col() : "<unknown>"));
        }

        // Emit the step + runWithSM call, but with the handler coming
        // from a JVM local instead of an `emitExpr(w.handler())`. Mirror
        // emitWithSM's structure inline rather than refactor today.
        int resultSlot = outer.allocateAnon();
        mv.visitVarInsn(ALOAD, handlerSlot);          // handlerObj

        Set<String> bound = new HashSet<>();
        List<String> captures = new ArrayList<>();
        for (Stmt s : body) ce.lamEm.collectFreeVarsStmt(s, bound, outer, captures, new HashSet<>());

        ce.smEm.emitSMStep(shape, body, captures, mv, outer);

        int nFields = switch (shape) {
            case WithBodyShape.Sequence seq -> seq.liftedLocals().size();
            case WithBodyShape.EffIR eir -> eir.liftedLocals().size();
            default -> 0;
        };
        ce.exprEm.pushIconst(mv, nFields);
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("runWithSM"), "runWithSM",
                "(Ljava/lang/Object;L" + ClassEmitter.IRIJ_FN + ";I)Ljava/lang/Object;", false);
        mv.visitVarInsn(ASTORE, resultSlot);

        mv.visitLabel(withEnd);
        // Normal exit: pop the runtime frames + push the result.
        mv.visitVarInsn(ILOAD, countSlot);
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("exitWithCount"), "exitWithCount", "(I)V", false);
        mv.visitVarInsn(ALOAD, resultSlot);
        mv.visitJumpInsn(GOTO, after);

        // Exception exit: pop, rethrow.
        mv.visitLabel(withCatch);
        int excSlot = outer.allocateAnon();
        mv.visitVarInsn(ASTORE, excSlot);
        mv.visitVarInsn(ILOAD, countSlot);
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("exitWithCount"), "exitWithCount", "(I)V", false);
        mv.visitVarInsn(ALOAD, excSlot);
        mv.visitInsn(ATHROW);
        mv.visitTryCatchBlock(withStart, withEnd, withCatch, null);

        mv.visitLabel(after);
    }


    List<String> collectHandlerNames(Expr e) {
        List<String> out = new ArrayList<>();
        collectHandlerNamesInto(e, out);
        return out;
    }


    void collectHandlerNamesInto(Expr e, List<String> out) {
        if (e instanceof Expr.Var v) {
            // If the Var refers to a local-bound handler expression
            // (`combined := h1 >> h2`), recurse into the bound RHS so
            // SM-shape analysis sees the actual handler chain rather
            // than treating the alias as an unknown handler name.
            Expr bound = ce.currentLocalHandlerBindings.get(v.name());
            if (bound != null && bound != e) {
                collectHandlerNamesInto(bound, out);
            } else {
                out.add(v.name());
            }
        } else if (e instanceof Expr.App app && app.fn() instanceof Expr.Var fv
                && (">>".equals(fv.name()) || "compose".equals(fv.name()))) {
            for (Expr a : app.args()) collectHandlerNamesInto(a, out);
        } else if (e instanceof Expr.Compose c) {
            // `h1 >> h2` parses as Expr.Compose (not App), so walk both sides.
            collectHandlerNamesInto(c.left(), out);
            collectHandlerNamesInto(c.right(), out);
        } else {
            // Unknown shape (lambda result, function call, etc.): mark unknown.
            out.add("__unknown__");
        }
    }


    /** Walk a fn body and collect `name := <handler-expr>` Simple binds
     *  where the RHS is a Var (handler ref) or Compose (`h1 >> h2`).
     *  Used by {@link #collectHandlerNamesInto} to resolve `with name`
     *  through a local alias. Walks ImperativeBody stmts, LambdaBody
     *  Block stmts, and every MatchArmsBody arm's Block stmts. */
    java.util.Map<String, Expr> scanLocalHandlerBindings(Decl.FnBody body) {
        java.util.Map<String, Expr> out = new java.util.HashMap<>();
        switch (body) {
            case Decl.FnBody.ImperativeBody ib -> scanStmtsForHandlerBinds(ib.stmts(), out);
            case Decl.FnBody.LambdaBody lb -> scanExprForHandlerBinds(lb.body(), out);
            case Decl.FnBody.MatchArmsBody mab -> {
                for (var arm : mab.arms()) scanExprForHandlerBinds(arm.body(), out);
            }
            default -> {}
        }
        return out;
    }


    void scanExprForHandlerBinds(Expr e, java.util.Map<String, Expr> out) {
        if (e instanceof Expr.Block blk) scanStmtsForHandlerBinds(blk.stmts(), out);
    }


    void scanStmtsForHandlerBinds(List<Stmt> stmts, java.util.Map<String, Expr> out) {
        for (Stmt s : stmts) {
            if (s instanceof Stmt.Bind b
                    && b.target() instanceof Stmt.BindTarget.Simple sm) {
                Expr v = b.value();
                if (v instanceof Expr.Var || v instanceof Expr.Compose
                        || (v instanceof Expr.App app
                            && app.fn() instanceof Expr.Var fv
                            && (">>".equals(fv.name()) || "compose".equals(fv.name())))) {
                    out.put(sm.name(), v);
                }
            }
        }
    }
}
