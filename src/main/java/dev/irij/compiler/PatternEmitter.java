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

final class PatternEmitter implements Opcodes {

    private final ClassEmitter ce;

    PatternEmitter(ClassEmitter ce) { this.ce = ce; }

    void collectPatternBinds(Pattern p, Set<String> bound) {
        switch (p) {
            case Pattern.VarPat vp -> bound.add(vp.name());
            case Pattern.GroupedPat gp -> collectPatternBinds(gp.inner(), bound);
            case Pattern.ConstructorPat cp -> { for (Pattern a : cp.args()) collectPatternBinds(a, bound); }
            case Pattern.VectorPat vp -> {
                for (Pattern a : vp.elements()) collectPatternBinds(a, bound);
                if (vp.spread() != null) bound.add(vp.spread().name());
            }
            case Pattern.TuplePat tp -> { for (Pattern a : tp.elements()) collectPatternBinds(a, bound); }
            case Pattern.DestructurePat dp -> {
                for (Pattern.DestructureField f : dp.fields()) collectPatternBinds(f.value(), bound);
            }
            default -> {}
        }
    }


    // ── Match / pattern matching ────────────────────────────────────────

    void emitMatchExpr(Expr.MatchExpr me, MethodVisitor mv, Locals locals) {
        // Evaluate scrutinee once, store in a fresh slot.
        ce.exprEm.emitExpr(me.scrutinee(), mv, locals);
        int scrut = locals.allocateAnon();
        mv.visitVarInsn(ASTORE, scrut);

        Label endL = new Label();
        Label noMatch = new Label();

        for (int i = 0; i < me.arms().size(); i++) {
            Expr.MatchArm arm = me.arms().get(i);
            Label nextArm = (i == me.arms().size() - 1) ? noMatch : new Label();
            Locals armLocals = locals.childScope();
            emitPatternTest(arm.pattern(), scrut, mv, armLocals, nextArm);
            if (arm.guard() != null) {
                ce.exprEm.emitExpr(arm.guard(), mv, armLocals);
                mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("truthy"), "truthy", "(Ljava/lang/Object;)Z", false);
                mv.visitJumpInsn(IFEQ, nextArm);
            }
            ce.exprEm.emitExpr(arm.body(), mv, armLocals);
            mv.visitJumpInsn(GOTO, endL);
            if (nextArm != noMatch) mv.visitLabel(nextArm);
        }

        mv.visitLabel(noMatch);
        mv.visitVarInsn(ALOAD, scrut);
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("noMatch"), "noMatch",
                "(Ljava/lang/Object;)Ljava/lang/IllegalStateException;", false);
        mv.visitInsn(ATHROW);

        mv.visitLabel(endL);
    }


    /**
     * Emit code that tests whether `scrutSlot` matches `pattern`, binding any
     * variables into `locals`. On failure, jump to `failL`. On success, falls
     * through with no stack effect.
     */
    void emitPatternTest(Pattern pattern, int scrutSlot, MethodVisitor mv,
                                 Locals locals, Label failL) {
        switch (pattern) {
            case Pattern.WildcardPat __ -> { /* always matches */ }
            case Pattern.VarPat vp -> {
                // Bind scrutinee to variable
                int slot = locals.allocate(vp.name());
                mv.visitVarInsn(ALOAD, scrutSlot);
                mv.visitVarInsn(ASTORE, slot);
            }
            case Pattern.GroupedPat gp -> emitPatternTest(gp.inner(), scrutSlot, mv, locals, failL);
            case Pattern.UnitPat __ -> {
                mv.visitVarInsn(ALOAD, scrutSlot);
                mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("isUnit"), "isUnit", "(Ljava/lang/Object;)Z", false);
                mv.visitJumpInsn(IFEQ, failL);
            }
            case Pattern.LitPat lp -> {
                mv.visitVarInsn(ALOAD, scrutSlot);
                ce.exprEm.emitExpr(lp.literal(), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("eq"), "eq", ClassEmitter.CMPOP_DESC, false);
                mv.visitJumpInsn(IFEQ, failL);
            }
            case Pattern.KeywordPat kp -> {
                mv.visitVarInsn(ALOAD, scrutSlot);
                mv.visitLdcInsn(kp.name());
                mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("isKeyword"), "isKeyword",
                        "(Ljava/lang/Object;Ljava/lang/String;)Z", false);
                mv.visitJumpInsn(IFEQ, failL);
                if (kp.arg() != null) {
                    throw new IrijCompiler.CompileException(
                            "MVP: keyword patterns with arguments not supported");
                }
            }
            case Pattern.ConstructorPat cp -> emitConstructorPatternTest(cp, scrutSlot, mv, locals, failL);
            case Pattern.VectorPat vp -> emitVectorPatternTest(vp, scrutSlot, mv, locals, failL);
            case Pattern.TuplePat tp -> emitTuplePatternTest(tp, scrutSlot, mv, locals, failL);
            case Pattern.DestructurePat dp -> emitDestructurePatternTest(dp, scrutSlot, mv, locals, failL);
            default -> throw new IrijCompiler.CompileException(
                    "MVP: unsupported pattern: " + pattern.getClass().getSimpleName());
        }
    }


    void emitConstructorPatternTest(Pattern.ConstructorPat cp, int scrutSlot,
                                             MethodVisitor mv, Locals locals, Label failL) {
        mv.visitVarInsn(ALOAD, scrutSlot);
        mv.visitLdcInsn(cp.name());
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("isTag"), "isTag",
                "(Ljava/lang/Object;Ljava/lang/String;)Z", false);
        mv.visitJumpInsn(IFEQ, failL);

        // Arity check
        mv.visitVarInsn(ALOAD, scrutSlot);
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("taggedArity"), "taggedArity", "(Ljava/lang/Object;)I", false);
        ce.exprEm.pushIconst(mv, cp.args().size());
        mv.visitJumpInsn(IF_ICMPNE, failL);

        for (int i = 0; i < cp.args().size(); i++) {
            int fieldSlot = locals.allocateAnon();
            mv.visitVarInsn(ALOAD, scrutSlot);
            ce.exprEm.pushIconst(mv, i);
            mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("taggedField"), "taggedField",
                    "(Ljava/lang/Object;I)Ljava/lang/Object;", false);
            mv.visitVarInsn(ASTORE, fieldSlot);
            emitPatternTest(cp.args().get(i), fieldSlot, mv, locals, failL);
        }
    }


    void emitVectorPatternTest(Pattern.VectorPat vp, int scrutSlot,
                                        MethodVisitor mv, Locals locals, Label failL) {
        mv.visitVarInsn(ALOAD, scrutSlot);
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("isVector"), "isVector", "(Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(IFEQ, failL);

        int fixed = vp.elements().size();
        Pattern.SpreadPat spread = vp.spread();

        // Size check
        mv.visitVarInsn(ALOAD, scrutSlot);
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("vecSize"), "vecSize", "(Ljava/lang/Object;)I", false);
        int sizeSlot = locals.allocateAnon();
        mv.visitInsn(DUP);
        mv.visitVarInsn(ISTORE, sizeSlot);
        ce.exprEm.pushIconst(mv, fixed);
        if (spread == null) {
            mv.visitJumpInsn(IF_ICMPNE, failL);
        } else {
            mv.visitJumpInsn(IF_ICMPLT, failL);
        }

        for (int i = 0; i < fixed; i++) {
            int elSlot = locals.allocateAnon();
            mv.visitVarInsn(ALOAD, scrutSlot);
            ce.exprEm.pushIconst(mv, i);
            mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("vecGet"), "vecGet",
                    "(Ljava/lang/Object;I)Ljava/lang/Object;", false);
            mv.visitVarInsn(ASTORE, elSlot);
            emitPatternTest(vp.elements().get(i), elSlot, mv, locals, failL);
        }

        if (spread != null && !spread.name().equals("_")) {
            int restSlot = locals.allocate(spread.name());
            mv.visitVarInsn(ALOAD, scrutSlot);
            ce.exprEm.pushIconst(mv, fixed);
            mv.visitVarInsn(ILOAD, sizeSlot);
            mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("vecSlice"), "vecSlice",
                    "(Ljava/lang/Object;II)Ljava/lang/Object;", false);
            mv.visitVarInsn(ASTORE, restSlot);
        }
    }


    void emitTuplePatternTest(Pattern.TuplePat tp, int scrutSlot,
                                       MethodVisitor mv, Locals locals, Label failL) {
        mv.visitVarInsn(ALOAD, scrutSlot);
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("isTuple"), "isTuple", "(Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(IFEQ, failL);

        mv.visitVarInsn(ALOAD, scrutSlot);
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("tupleSize"), "tupleSize", "(Ljava/lang/Object;)I", false);
        ce.exprEm.pushIconst(mv, tp.elements().size());
        mv.visitJumpInsn(IF_ICMPNE, failL);

        for (int i = 0; i < tp.elements().size(); i++) {
            int elSlot = locals.allocateAnon();
            mv.visitVarInsn(ALOAD, scrutSlot);
            ce.exprEm.pushIconst(mv, i);
            mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("tupleGet"), "tupleGet",
                    "(Ljava/lang/Object;I)Ljava/lang/Object;", false);
            mv.visitVarInsn(ASTORE, elSlot);
            emitPatternTest(tp.elements().get(i), elSlot, mv, locals, failL);
        }
    }


    void emitDestructurePatternTest(Pattern.DestructurePat dp, int scrutSlot,
                                             MethodVisitor mv, Locals locals, Label failL) {
        mv.visitVarInsn(ALOAD, scrutSlot);
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("isRecord"), "isRecord", "(Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(IFEQ, failL);

        for (Pattern.DestructureField f : dp.fields()) {
            mv.visitVarInsn(ALOAD, scrutSlot);
            mv.visitLdcInsn(f.key());
            mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("recordHas"), "recordHas",
                    "(Ljava/lang/Object;Ljava/lang/String;)Z", false);
            mv.visitJumpInsn(IFEQ, failL);

            int fieldSlot = locals.allocateAnon();
            mv.visitVarInsn(ALOAD, scrutSlot);
            mv.visitLdcInsn(f.key());
            mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("recordGet"), "recordGet",
                    "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            mv.visitVarInsn(ASTORE, fieldSlot);
            emitPatternTest(f.value(), fieldSlot, mv, locals, failL);
        }
    }
}
