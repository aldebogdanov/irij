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

final class FnEmitter implements Opcodes {

    private final ClassEmitter ce;

    FnEmitter(ClassEmitter ce) { this.ce = ce; }

    /** Emit full spec validation for a fn's declared input specs.
     *
     *  For each non-wildcard, non-typevar input position emit:
     *  <pre>
     *    ALOAD param_i; LDC encodedSpec; LDC fnName; ICONST i;
     *    INVOKESTATIC SpecValidator.validateEncoded; ASTORE param_i;
     *  </pre>
     *
     *  The encoded spec is the {@link SpecValidator#encode} string for
     *  the {@code SpecExpr} — parsed + cached at runtime. Covers every
     *  SpecExpr variant the interpreter validates (Name, App, Arrow,
     *  Enum, VecSpec, SetSpec, TupleSpec, Unit). User-declared
     *  product/sum specs fall through as accepted at runtime since
     *  no specRegistry exists outside the interpreter — interp-mode
     *  remains the full-coverage path for those.
     */
    void emitInputSpecChecks(Decl.FnDecl fn, MethodVisitor mv,
                                      List<Pattern> params) {
        List<dev.irij.ast.SpecExpr> specs = fn.specAnnotations();
        if (specs == null || specs.size() < 2) return;
        int inputCount = specs.size() - 1; // last is output
        for (int i = 0; i < inputCount && i < params.size(); i++) {
            dev.irij.ast.SpecExpr spec = specs.get(i);
            if (skipSpec(spec)) continue;
            String encoded = SpecValidator.encode(spec);
            mv.visitVarInsn(ALOAD, i);
            mv.visitLdcInsn(encoded);
            mv.visitLdcInsn(fn.name());
            ce.exprEm.pushIconst(mv, i);
            mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.SPEC_VALIDATOR, "validateEncoded",
                    "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/Object;",
                    false);
            mv.visitVarInsn(ASTORE, i);
        }
    }


    /** Return the encoded output spec for {@code fn}, or null if it
     *  has no validatable output (no spec annotation, wildcard, or
     *  lowercase type variable). The output spec is the last entry
     *  in {@code specAnnotations()}. */
    static String outputSpecEncoded(Decl.FnDecl fn) {
        List<dev.irij.ast.SpecExpr> specs = fn.specAnnotations();
        if (specs == null || specs.isEmpty()) return null;
        dev.irij.ast.SpecExpr out = specs.get(specs.size() - 1);
        if (skipSpec(out)) return null;
        return SpecValidator.encode(out);
    }


    /** Skip wildcards, lowercase type-vars, and explicit Var nodes. */
    static boolean skipSpec(dev.irij.ast.SpecExpr spec) {
        if (spec == null) return true;
        if (spec instanceof dev.irij.ast.SpecExpr.Wildcard) return true;
        if (spec instanceof dev.irij.ast.SpecExpr.Var) return true;
        if (spec instanceof dev.irij.ast.SpecExpr.Name n) {
            String nm = n.name();
            if (nm.equals("_")) return true;
            if (!nm.isEmpty() && Character.isLowerCase(nm.charAt(0))) return true;
        }
        return false;
    }


    /** Emit a tail-position return. Runs post-condition + out-contract
     *  checks (if any), validates the output spec (if any), then
     *  ARETURNs the value left on top of the operand stack. */
    void emitTailReturn(MethodVisitor mv) {
        emitPostChecks(mv);
        if (ce.currentOutputSpec != null) {
            mv.visitLdcInsn(ce.currentOutputSpec);
            mv.visitLdcInsn(ce.currentFnName);
            mv.visitInsn(ICONST_M1);
            mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.SPEC_VALIDATOR, "validateEncoded",
                    "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/Object;",
                    false);
        }
        // Pop the effect-row frame this fn pushed on entry. The result
        // is already on the stack; exitFn returns void, so the stack
        // shape stays { result } for the subsequent ARETURN.
        if (ce.currentFnPushesEffects) {
            mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("exitFn"), "exitFn", "()V", false);
        }
        mv.visitInsn(ARETURN);
    }


    /** Compile each post / out lambda once and store the resulting
     *  IrijFn in a fresh local slot. Also records a blame message per
     *  slot so {@link #emitPostChecks} can throw the right text. */
    void installPostSlots(Decl.FnDecl fn, MethodVisitor mv, Locals locals) {
        List<Integer> slots = new ArrayList<>();
        List<String> blame = new ArrayList<>();
        for (Expr p : fn.postConditions()) {
            int slot = compilePostLambda(p, mv, locals);
            if (slot >= 0) {
                slots.add(slot);
                blame.add("Post-condition violated in '" + fn.name()
                        + "' (implementation's fault)");
            }
        }
        for (Expr p : fn.outContracts()) {
            int slot = compilePostLambda(p, mv, locals);
            if (slot >= 0) {
                slots.add(slot);
                blame.add("Output contract violated in '" + fn.name()
                        + "' (implementation's fault)");
            }
        }
        if (slots.isEmpty()) {
            ce.currentPostSlots = List.of();
            ce.currentPostBlame = List.of();
            ce.currentPostTempSlot = -1;
        } else {
            ce.currentPostSlots = List.copyOf(slots);
            ce.currentPostBlame = List.copyOf(blame);
            ce.currentPostTempSlot = locals.allocateAnon();
        }
    }


    /** Compile a single post/out lambda into a local slot and return
     *  the slot index, or -1 if {@code postExpr} isn't a lambda we
     *  can handle. */
    int compilePostLambda(Expr postExpr, MethodVisitor mv, Locals locals) {
        if (!(postExpr instanceof Expr.Lambda lam)) return -1;
        ce.lamEm.emitLambda(lam, mv, locals);
        int slot = locals.allocateAnon();
        mv.visitVarInsn(ASTORE, slot);
        return slot;
    }


    /** Apply each post lambda to the stack-top value. Leaves the
     *  value on the stack unchanged after all checks pass; throws
     *  IrijRuntimeError on the first failing check. */
    void emitPostChecks(MethodVisitor mv) {
        if (ce.currentPostSlots.isEmpty()) return;
        // Stash result (still on stack after this).
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, ce.currentPostTempSlot);
        for (int i = 0; i < ce.currentPostSlots.size(); i++) {
            int slot = ce.currentPostSlots.get(i);
            mv.visitVarInsn(ALOAD, slot);          // post fn
            // build Object[] {result}
            ce.exprEm.pushIconst(mv, 1);
            mv.visitTypeInsn(ANEWARRAY, ClassEmitter.OBJ);
            mv.visitInsn(DUP);
            ce.exprEm.pushIconst(mv, 0);
            mv.visitVarInsn(ALOAD, ce.currentPostTempSlot);
            mv.visitInsn(AASTORE);
            mv.visitMethodInsn(INVOKEINTERFACE, ClassEmitter.IRIJ_FN, "apply",
                    "([Ljava/lang/Object;)Ljava/lang/Object;", true);
            mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("truthy"), "truthy",
                    "(Ljava/lang/Object;)Z", false);
            Label ok = new Label();
            mv.visitJumpInsn(IFNE, ok);
            emitThrowRuntimeError(mv, ce.currentPostBlame.get(i));
            mv.visitLabel(ok);
        }
    }


    /** Emit pre-condition + in-contract checks just before the TCO
     *  entry label. Lambda is applied to the current arg slots; on
     *  falsy result, throws IrijRuntimeError with caller-blame text. */
    void emitPreContractChecks(Decl.FnDecl fn, MethodVisitor mv,
                                        Locals locals, List<Pattern> params) {
        emitPreList(fn.preConditions(), fn.name(), false,
                mv, locals, params);
        emitPreList(fn.inContracts(), fn.name(), true,
                mv, locals, params);
    }


    void emitPreList(List<Expr> preList, String fnName, boolean isIn,
                              MethodVisitor mv, Locals locals, List<Pattern> params) {
        if (preList == null || preList.isEmpty()) return;
        String blame = isIn
                ? "Input contract violated in '" + fnName + "' (caller's fault)"
                : "Pre-condition violated in '" + fnName + "' (caller's fault)";
        for (Expr p : preList) {
            if (!(p instanceof Expr.Lambda lam)) continue;
            ce.lamEm.emitLambda(lam, mv, locals);  // stack: IrijFn
            // Build Object[] from param slots.
            ce.exprEm.pushIconst(mv, params.size());
            mv.visitTypeInsn(ANEWARRAY, ClassEmitter.OBJ);
            for (int i = 0; i < params.size(); i++) {
                mv.visitInsn(DUP);
                ce.exprEm.pushIconst(mv, i);
                mv.visitVarInsn(ALOAD, i);
                mv.visitInsn(AASTORE);
            }
            mv.visitMethodInsn(INVOKEINTERFACE, ClassEmitter.IRIJ_FN, "apply",
                    "([Ljava/lang/Object;)Ljava/lang/Object;", true);
            mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("truthy"), "truthy",
                    "(Ljava/lang/Object;)Z", false);
            Label ok = new Label();
            mv.visitJumpInsn(IFNE, ok);
            emitThrowRuntimeError(mv, blame);
            mv.visitLabel(ok);
        }
    }


    /** Throw {@code new IrijRuntimeError(message)}. Leaves no value
     *  on the stack — the verifier needs the next opcode to be
     *  unreachable or a Label start. Callers place a Label after. */
    void emitThrowRuntimeError(MethodVisitor mv, String message) {
        String ire = "dev/irij/IrijRuntimeError";
        mv.visitTypeInsn(NEW, ire);
        mv.visitInsn(DUP);
        mv.visitLdcInsn(message);
        mv.visitMethodInsn(INVOKESPECIAL, ire, "<init>",
                "(Ljava/lang/String;)V", false);
        mv.visitInsn(ATHROW);
    }


    void emitFn(Decl.FnDecl fn, ClassWriter cw) {
        List<Pattern> params = ClassEmitter.fnParams(fn);
        StringBuilder desc = new StringBuilder("(");
        for (int i = 0; i < params.size(); i++) desc.append(ClassEmitter.OBJ_DESC);
        desc.append(")").append(ClassEmitter.OBJ_DESC);

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
                ClassEmitter.mangle(fn.name()), desc.toString(), null, null);
        mv.visitCode();

        Locals locals = new Locals();
        // First pass: every JVM param needs its slot reserved at the
        // expected index (Locals allocates sequentially). For simple
        // patterns (VarPat / WildcardPat) we use the source name so
        // body lookups resolve directly. For destructuring patterns
        // (DestructurePat, ConstructorPat, etc.) we assign a synthetic
        // `__paramN` slot and remember the pattern for the post-pass.
        java.util.List<Pattern> deferredPatterns = new java.util.ArrayList<>();
        java.util.List<Integer> deferredSlots = new java.util.ArrayList<>();
        for (int i = 0; i < params.size(); i++) {
            Pattern p = params.get(i);
            String name;
            boolean defer = false;
            switch (p) {
                case Pattern.VarPat v -> name = v.name();
                case Pattern.WildcardPat w -> name = "_";
                default -> { name = "__param" + i; defer = true; }
            }
            int slot = locals.allocate(name);
            if (defer) {
                deferredPatterns.add(p);
                deferredSlots.add(slot);
            }
        }
        // Second pass: bind sub-vars by running pattern tests against
        // each deferred slot. On mismatch we throw a runtime error;
        // fn-param patterns are total (the spec validator already
        // rejected obviously bad inputs).
        if (!deferredPatterns.isEmpty()) {
            Label paramFailL = new Label();
            Label paramOkL = new Label();
            for (int i = 0; i < deferredPatterns.size(); i++) {
                ce.patEm.emitPatternTest(deferredPatterns.get(i), deferredSlots.get(i),
                        mv, locals, paramFailL);
            }
            mv.visitJumpInsn(GOTO, paramOkL);
            mv.visitLabel(paramFailL);
            mv.visitTypeInsn(NEW, "dev/irij/IrijRuntimeError");
            mv.visitInsn(DUP);
            mv.visitLdcInsn("Pattern match failure in fn '" + fn.name() + "' parameter");
            mv.visitMethodInsn(INVOKESPECIAL, "dev/irij/IrijRuntimeError",
                    "<init>", "(Ljava/lang/String;)V", false);
            mv.visitInsn(ATHROW);
            mv.visitLabel(paramOkL);
        }

        // Bytecode spec validation (input args, full SpecExpr coverage
        // via SpecValidator). Mirrors Interpreter.validateFnArgs.
        emitInputSpecChecks(fn, mv, params);

        // Pre-condition + in-contract checks. Run once per outer call
        // (placed BEFORE the TCO entry label, so self-tail recursion
        // doesn't re-check — same as Interpreter's TCO bypass).
        emitPreContractChecks(fn, mv, locals, params);

        // Compile each post-condition / out-contract lambda once into
        // a local slot. emitTailReturn applies them at every fn-body
        // tail-return before validating the output spec.
        String savedFnName = ce.currentFnName;
        int savedFnArity = ce.currentFnArity;
        Label savedFnEntry = ce.currentFnEntry;
        String savedOutputSpec = ce.currentOutputSpec;
        List<Integer> savedPostSlots = ce.currentPostSlots;
        int savedPostTemp = ce.currentPostTempSlot;
        List<String> savedPostBlame = ce.currentPostBlame;

        ce.currentFnName = fn.name();
        ce.currentFnArity = params.size();
        ce.currentFnEntry = new Label();
        // Capture the output spec (last entry in specAnnotations) so
        // every tail-return validates against it. Non-validatable specs
        // (wildcard / lowercase var) → null, no per-return overhead.
        ce.currentOutputSpec = outputSpecEncoded(fn);
        installPostSlots(fn, mv, locals);

        // Runtime effect-row tracking. Push this fn's declared row onto
        // RT.EFFECT_ROW so that every `perform` inside the body honors
        // it (mirrors the interpreter's AVAILABLE_EFFECTS stack). A
        // wrap-all try/catch ensures the frame is popped on exception.
        boolean savedPushes = ce.currentFnPushesEffects;
        ce.currentFnPushesEffects = true;
        boolean ambient = ce.smEm.isAmbientRow(fn.effectRow());
        if (ambient) {
            mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("enterFnAmbient"), "enterFnAmbient", "()V", false);
        } else {
            ce.smEm.emitStringArrayConst(mv, fn.effectRow());
            mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("enterFn"), "enterFn", "([Ljava/lang/String;)V", false);
        }
        Label efTryStart = new Label();
        Label efTryEnd = new Label();
        Label efHandler = new Label();
        mv.visitLabel(efTryStart);
        mv.visitLabel(ce.currentFnEntry);
        // Pre-scan body for `name := <handler-expr>` Simple binds so a
        // later `with name` inside the body can resolve through the
        // local alias (SM-1 fix).
        var savedHandlerBindings = ce.currentLocalHandlerBindings;
        ce.currentLocalHandlerBindings = ce.effEm.scanLocalHandlerBindings(fn.body());
        try {
            switch (fn.body()) {
                case Decl.FnBody.LambdaBody lb -> ce.exprEm.emitTailExpr(lb.body(), mv, locals);
                case Decl.FnBody.MatchArmsBody mab -> {
                    Expr.MatchExpr me = new Expr.MatchExpr(
                            new Expr.Var("$scrut", null),
                            mab.arms(),
                            null);
                    ce.patEm.emitMatchExpr(me, mv, locals);
                    emitTailReturn(mv);
                }
                case Decl.FnBody.ImperativeBody ib -> emitImperativeTail(ib.stmts(), mv, locals);
                default -> throw new IrijCompiler.CompileException(
                        "MVP: unsupported fn body: " + fn.body().getClass().getSimpleName());
            }
        } finally {
            ce.currentLocalHandlerBindings = savedHandlerBindings;
            ce.currentFnName = savedFnName;
            ce.currentFnArity = savedFnArity;
            ce.currentFnEntry = savedFnEntry;
            ce.currentOutputSpec = savedOutputSpec;
            ce.currentPostSlots = savedPostSlots;
            ce.currentPostTempSlot = savedPostTemp;
            ce.currentPostBlame = savedPostBlame;
            ce.currentFnPushesEffects = savedPushes;
        }
        mv.visitLabel(efTryEnd);
        // Catch-all: pop the effect-row frame, then re-throw.
        mv.visitLabel(efHandler);
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("exitFn"), "exitFn", "()V", false);
        mv.visitInsn(ATHROW);
        mv.visitTryCatchBlock(efTryStart, efTryEnd, efHandler, null);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }


    static String userFnWrapperName(String fnName) {
        return ClassEmitter.mangle(fnName) + "$irijfn";
    }


    /** Emit a one-time `f$irijfn(Object[]) -> Object` adapter that
     *  unpacks the array and calls `f` via INVOKESTATIC. */
    void ensureUserFnWrapper(String fnName, int arity) {
        if (!ce.emittedFnWrappers.add(fnName)) return; // already emitted
        MethodVisitor w = ce.classWriter.visitMethod(
                ACC_STATIC | ACC_SYNTHETIC,
                userFnWrapperName(fnName), ClassEmitter.APPLY_DESC, null, null);
        w.visitCode();
        int argsSlot = 0;
        for (int i = 0; i < arity; i++) {
            w.visitVarInsn(ALOAD, argsSlot);
            ce.exprEm.pushIconst(w, i);
            w.visitInsn(AALOAD);
        }
        StringBuilder desc = new StringBuilder("(");
        for (int i = 0; i < arity; i++) desc.append(ClassEmitter.OBJ_DESC);
        desc.append(")").append(ClassEmitter.OBJ_DESC);
        w.visitMethodInsn(INVOKESTATIC, ce.ownerOf(fnName), ClassEmitter.mangle(fnName),
                desc.toString(), false);
        w.visitInsn(ARETURN);
        w.visitMaxs(0, 0);
        w.visitEnd();
    }


    /** Emit a sequence of statements where the last one supplies the
     *  fn body's return value. Used by ImperativeBody fns and any
     *  if-branch in tail position. Mirrors the interpreter's
     *  execStmtListReturn: the last ExprStmt or With's value bubbles
     *  out; a trailing IfStmt is treated as an if-expression so its
     *  branches' values bubble out too. Anything else (Bind, Assign,
     *  MatchStmt without a value) returns Unit, matching interp. */
    void emitImperativeTail(List<Stmt> stmts, MethodVisitor mv, Locals locals) {
        if (stmts.isEmpty()) {
            mv.visitInsn(ACONST_NULL);
            emitTailReturn(mv);
            return;
        }
        for (int i = 0; i < stmts.size() - 1; i++) ce.exprEm.emitStmt(stmts.get(i), mv, locals);
        Stmt last = stmts.get(stmts.size() - 1);
        if (last instanceof Stmt.ExprStmt es) {
            ce.exprEm.emitTailExpr(es.expr(), mv, locals);
        } else if (last instanceof Stmt.With w) {
            ce.effEm.emitWith(w, mv, locals);
            emitTailReturn(mv);
        } else if (last instanceof Stmt.IfStmt ifs) {
            emitTailIfStmt(ifs, mv, locals);
        } else if (last instanceof Stmt.MatchStmt ms) {
            // Match in tail position: emit as expression so each
            // arm's last expression bubbles out.
            ce.patEm.emitMatchExpr(new Expr.MatchExpr(ms.scrutinee(), ms.arms(), ms.loc()),
                    mv, locals);
            emitTailReturn(mv);
        } else {
            ce.exprEm.emitStmt(last, mv, locals);
            mv.visitInsn(ACONST_NULL);
            emitTailReturn(mv);
        }
    }


    /** Emit an IfStmt at tail position. Each branch's last statement
     *  supplies the fn's return value via {@link #emitImperativeTail}. */
    void emitTailIfStmt(Stmt.IfStmt ifs, MethodVisitor mv, Locals locals) {
        ce.exprEm.emitExpr(ifs.cond(), mv, locals);
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("truthy"), "truthy",
                "(Ljava/lang/Object;)Z", false);
        Label elseL = new Label();
        mv.visitJumpInsn(IFEQ, elseL);
        emitImperativeTail(ifs.thenBranch(), mv, locals);
        mv.visitLabel(elseL);
        if (ifs.elseBranch() != null) {
            emitImperativeTail(ifs.elseBranch(), mv, locals);
        } else {
            mv.visitInsn(ACONST_NULL);
            emitTailReturn(mv);
        }
    }
}
