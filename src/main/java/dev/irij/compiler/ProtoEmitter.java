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

final class ProtoEmitter implements Opcodes {

    private final ClassEmitter ce;

    ProtoEmitter(ClassEmitter ce) { this.ce = ce; }

    // ── Protocols / impls ───────────────────────────────────────────────

    static String implMethodName(String method, String forType) {
        return "impl$" + ClassEmitter.mangle(method) + "$" + forType;
    }


    /** Lower an impl binding's RHS into a lambda the emitter can handle.
     *  Already-lambda bindings pass through. Common non-lambda shapes
     *  get wrapped:
     *    - Operator section `(+)` → `(a b -> a + b)` (arity 2)
     *    - Var naming a top-level fn → `(args... -> fn args...)` with
     *      that fn's arity (so `describe := fmt-pt` dispatches into
     *      fmt-pt's body with the witness arg threaded through).
     *    - Other (literals, etc.) → `(_w -> value)` (arity 1; the
     *      witness arg is consumed by the proto dispatcher, the value
     *      itself is returned, matching the interpreter's "if impl
     *      binding is non-callable, return it directly" path). */
    Expr.Lambda liftImplBindingToLambda(Decl.ImplBinding b) {
        Expr v = b.value();
        if (v instanceof Expr.Lambda lam) return lam;
        if (v instanceof Expr.OpSection os) {
            // (op) → (a b -> a op b)
            String op = os.op();
            Pattern.VarPat pa = new Pattern.VarPat("_a", os.loc());
            Pattern.VarPat pb = new Pattern.VarPat("_b", os.loc());
            Expr body = new Expr.BinaryOp(
                    op,
                    new Expr.Var("_a", os.loc()),
                    new Expr.Var("_b", os.loc()),
                    os.loc());
            return new Expr.Lambda(
                    java.util.List.of(pa, pb), null, body, os.loc());
        }
        if (v instanceof Expr.Var vr) {
            // `describe := fmt-pt` — wrap into `(a0 a1 ... -> fmt-pt a0 a1 ...)`
            // using fmt-pt's known arity. For builtins (no fnArity
            // entry), default to arity 1 — most value-method bindings
            // (`size := length`, `to-show := to-str`) are unary, and
            // the proto dispatcher only needs the first arg's type to
            // pick the impl anyway.
            Integer arity = ce.fnArity.get(vr.name());
            int n = arity != null ? arity : 1;
            if (n > 0) {
                java.util.List<Pattern> params = new java.util.ArrayList<>();
                java.util.List<Expr> argExprs = new java.util.ArrayList<>();
                for (int i = 0; i < n; i++) {
                    String pn = "_a" + i;
                    params.add(new Pattern.VarPat(pn, vr.loc()));
                    argExprs.add(new Expr.Var(pn, vr.loc()));
                }
                Expr body = new Expr.App(
                        new Expr.Var(vr.name(), vr.loc()), argExprs, vr.loc());
                return new Expr.Lambda(params, null, body, vr.loc());
            }
        }
        // Default: arity-1 thunk returning the value verbatim. The
        // witness arg `_w` is ignored. Works for IntLit / StrLit /
        // FloatLit / BoolLit / Keyword / UnitLit — value-as-method
        // bindings like `empty := 0`.
        Pattern.VarPat pw = new Pattern.VarPat("_w", null);
        return new Expr.Lambda(java.util.List.of(pw), null, v, null);
    }


    void emitImplMethod(String method, String forType, Expr.Lambda lam, ClassWriter cw) {
        List<String> paramNames = new ArrayList<>();
        for (Pattern p : lam.params()) {
            paramNames.add(p instanceof Pattern.VarPat v ? v.name() : "_");
        }
        if (lam.restParam() != null) {
            throw new IrijCompiler.CompileException(
                    "MVP impl: rest params in impl bindings not supported");
        }
        int arity = paramNames.size();
        StringBuilder desc = new StringBuilder("(");
        for (int i = 0; i < arity; i++) desc.append(ClassEmitter.OBJ_DESC);
        desc.append(")").append(ClassEmitter.OBJ_DESC);
        MethodVisitor mv = cw.visitMethod(ACC_STATIC,
                implMethodName(method, forType), desc.toString(), null, null);
        mv.visitCode();
        Locals locals = new Locals();
        for (String pn : paramNames) locals.allocate(pn);
        ce.exprEm.emitExpr(lam.body(), mv, locals);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }


    void emitProtoDispatcher(String method, java.util.Set<String> forTypes,
                                      int arity, ClassWriter cw) {
        StringBuilder desc = new StringBuilder("(");
        for (int i = 0; i < arity; i++) desc.append(ClassEmitter.OBJ_DESC);
        desc.append(")").append(ClassEmitter.OBJ_DESC);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
                ClassEmitter.mangle(method), desc.toString(), null, null);
        mv.visitCode();
        // tag := typeTag(arg0)
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, "typeTag",
                "(Ljava/lang/Object;)Ljava/lang/String;", false);
        int tagSlot = arity;
        mv.visitVarInsn(ASTORE, tagSlot);
        Label end = new Label();
        for (String ft : forTypes) {
            Label next = new Label();
            mv.visitVarInsn(ALOAD, tagSlot);
            mv.visitLdcInsn(ft);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals",
                    "(Ljava/lang/Object;)Z", false);
            mv.visitJumpInsn(IFEQ, next);
            // dispatch to impl
            for (int i = 0; i < arity; i++) mv.visitVarInsn(ALOAD, i);
            mv.visitMethodInsn(INVOKESTATIC, ce.internalName,
                    implMethodName(method, ft), desc.toString(), false);
            mv.visitInsn(ARETURN);
            mv.visitLabel(next);
        }
        // no impl → throw
        mv.visitLdcInsn(method);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, "noImpl",
                "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/IllegalStateException;", false);
        mv.visitInsn(ATHROW);
        mv.visitLabel(end);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
