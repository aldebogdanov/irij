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

final class ExprEmitter implements Opcodes {

    private final ClassEmitter ce;

    ExprEmitter(ClassEmitter ce) { this.ce = ce; }

    /**
     * Emit an expression at tail position: either lowers to a self-tail-call
     * GOTO + arg rebind, or falls through to {@code emitExpr} followed by
     * {@code ARETURN}. Recurses into tail-propagating shapes (if/else) so a
     * deeply-nested self call still gets the optimisation.
     */
    void emitTailExpr(Expr e, MethodVisitor mv, Locals locals) {
        // 1. Direct self-tail-call: `App(Var(currentFn), args)` with matching arity.
        if (e instanceof Expr.App app
                && app.fn() instanceof Expr.Var v
                && ce.currentFnName != null
                && ce.currentFnName.equals(v.name())
                && app.args().size() == ce.currentFnArity) {
            // Evaluate all args onto the JVM operand stack first, THEN ASTORE
            // into param slots in reverse order. This guarantees args see the
            // pre-call param values (e.g. `loop (acc + n) (n - 1)` reads the
            // old acc + old n before either slot is overwritten).
            for (Expr a : app.args()) emitExpr(a, mv, locals);
            for (int i = ce.currentFnArity - 1; i >= 0; i--) {
                mv.visitVarInsn(ASTORE, i);
            }
            mv.visitJumpInsn(GOTO, ce.currentFnEntry);
            return;
        }

        // 2a. Tail-propagating shape: `do e1 ... en` — the last
        // expression is in tail position (self-calls there become GOTO).
        if (e instanceof Expr.DoExpr de && !de.exprs().isEmpty()) {
            for (int i = 0; i < de.exprs().size() - 1; i++) {
                emitExpr(de.exprs().get(i), mv, locals);
                mv.visitInsn(POP);
            }
            emitTailExpr(de.exprs().get(de.exprs().size() - 1), mv, locals);
            return;
        }

        // 2. Tail-propagating shape: if/else — both branches are tail.
        if (e instanceof Expr.IfExpr ie) {
            emitExpr(ie.cond(), mv, locals);
            mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("truthy"), "truthy",
                    "(Ljava/lang/Object;)Z", false);
            Label elseL = new Label();
            mv.visitJumpInsn(IFEQ, elseL);
            emitTailExpr(ie.thenBranch(), mv, locals);
            mv.visitLabel(elseL);
            emitTailExpr(ie.elseBranch(), mv, locals);
            return;
        }

        // 3. Block: earlier stmts non-tail, last expr tail.
        if (e instanceof Expr.Block blk) {
            ce.fnEm.emitImperativeTail(blk.stmts(), mv, locals);
            return;
        }

        // 4. Default: just compute the value and return.
        emitExpr(e, mv, locals);
        ce.fnEm.emitTailReturn(mv);
    }


    void emitBlock(Expr.Block blk, MethodVisitor mv, Locals outer) {
        Locals inner = outer.childScope();
        List<Stmt> stmts = blk.stmts();
        if (stmts.isEmpty()) {
            mv.visitInsn(ACONST_NULL);
            return;
        }
        for (int i = 0; i < stmts.size() - 1; i++) emitStmt(stmts.get(i), mv, inner);
        Stmt last = stmts.get(stmts.size() - 1);
        if (!emitTailStmtValue(last, mv, inner)) {
            emitStmt(last, mv, inner);
            mv.visitInsn(ACONST_NULL);
        }
    }


    /** Emit an IfStmt as if it were an IfExpr — each branch becomes a
     *  Block whose value is the branch's last expression. Used when an
     *  if appears in tail position inside a Block. */
    void emitImperativeIfAsExpr(Stmt.IfStmt ifs, MethodVisitor mv, Locals locals) {
        emitExpr(ifs.cond(), mv, locals);
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("truthy"), "truthy",
                "(Ljava/lang/Object;)Z", false);
        Label elseL = new Label();
        Label endL = new Label();
        mv.visitJumpInsn(IFEQ, elseL);
        emitBlock(new Expr.Block(ifs.thenBranch(), ifs.loc()), mv, locals);
        mv.visitJumpInsn(GOTO, endL);
        mv.visitLabel(elseL);
        if (ifs.elseBranch() != null) {
            emitBlock(new Expr.Block(ifs.elseBranch(), ifs.loc()), mv, locals);
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        mv.visitLabel(endL);
    }


    /** Emit {@code last} in value/tail position, leaving its value on the
     *  operand stack. Handles every value-producing tail-statement shape:
     *  a bare expression, or a block-form tail {@code if} / {@code match} /
     *  {@code with} whose taken branch / arm / body supplies the value.
     *  Returns {@code true} if a value was pushed; {@code false} when
     *  {@code last} is a non-value statement (e.g. a {@code :=} bind) — the
     *  caller then emits it as a statement and pushes its own Unit.
     *
     *  <p>Block-form {@code if}/{@code match} parse to {@link Stmt.IfStmt} /
     *  {@link Stmt.MatchStmt} (not the inline {@code IfExpr}/{@code MatchExpr}),
     *  so without this a {@code with}-body or handler block ending in a
     *  multi-line if/match silently returned Unit. */
    boolean emitTailStmtValue(Stmt last, MethodVisitor mv, Locals locals) {
        if (last instanceof Stmt.ExprStmt es) {
            emitExpr(es.expr(), mv, locals);
        } else if (last instanceof Stmt.With w) {
            ce.effEm.emitWith(w, mv, locals);
        } else if (last instanceof Stmt.MatchStmt ms) {
            ce.patEm.emitMatchExpr(new Expr.MatchExpr(ms.scrutinee(), ms.arms(), ms.loc()),
                    mv, locals);
        } else if (last instanceof Stmt.IfStmt ifs) {
            emitImperativeIfAsExpr(ifs, mv, locals);
        } else {
            return false;
        }
        return true;
    }


    void emitStmt(Stmt s, MethodVisitor mv, Locals locals) {
        ce.emitLine(mv, ClassEmitter.locOf(s));
        switch (s) {
            case Stmt.ExprStmt es -> emitStmtExpr(es.expr(), mv, locals);
            case Stmt.Bind b -> emitBind(b, mv, locals);
            case Stmt.MutBind mb -> emitMutBind(mb, mv, locals);
            case Stmt.IfStmt ifs -> emitIfStmt(ifs, mv, locals);
            case Stmt.MatchStmt ms -> {
                // Match as statement: emit as expression, discard result.
                ce.patEm.emitMatchExpr(new Expr.MatchExpr(ms.scrutinee(), ms.arms(), ms.loc()), mv, locals);
                mv.visitInsn(POP);
            }
            case Stmt.With w -> {
                ce.effEm.emitWith(w, mv, locals);
                mv.visitInsn(POP);
            }
            case Stmt.Assign a -> emitAssign(a, mv, locals);
            case Stmt.Scope sc -> {
                emitScope(sc, mv, locals);
                mv.visitInsn(POP);
            }
            default -> throw new IrijCompiler.CompileException(
                    "MVP: unsupported statement: " + s.getClass().getSimpleName());
        }
    }


    void emitAssign(Stmt.Assign a, MethodVisitor mv, Locals locals) {
        if (!(a.target() instanceof Stmt.BindTarget.Simple s)) {
            throw new IrijCompiler.CompileException(
                    "MVP: assignment must target a simple name");
        }
        Integer liftedIdx = ce.currentLiftedLocals.get(s.name());
        if (liftedIdx != null && ce.currentKSlot >= 0) {
            mv.visitVarInsn(ALOAD, ce.currentKSlot);
            mv.visitFieldInsn(GETFIELD, ClassEmitter.CONT, "fields", "[Ljava/lang/Object;");
            pushIconst(mv, liftedIdx);
            emitExpr(a.value(), mv, locals);
            mv.visitInsn(AASTORE);
            return;
        }
        emitExpr(a.value(), mv, locals);
        // Top-level mutable bind: write the static field FIRST so
        // captured-by-static-field readers (lambdas, other threads)
        // see the update. Falls through to local-slot update if there
        // is one, so same-method reads also see the new value.
        String topField = ce.topLevelFields.get(s.name());
        if (topField != null) {
            mv.visitInsn(DUP);
            mv.visitFieldInsn(PUTSTATIC, ce.internalName, topField, ClassEmitter.OBJ_DESC);
            Integer slotMaybe = locals.lookup(s.name());
            if (slotMaybe != null) {
                mv.visitVarInsn(ASTORE, slotMaybe);
            } else {
                mv.visitInsn(POP);
            }
            return;
        }
        Integer slot = locals.lookup(s.name());
        if (slot != null) {
            mv.visitVarInsn(ASTORE, slot);
            return;
        }
        String field = ce.currentStateFields.get(s.name());
        if (field != null) {
            mv.visitFieldInsn(PUTSTATIC, ce.internalName, field, ClassEmitter.OBJ_DESC);
            return;
        }
        throw new IrijCompiler.CompileException(
                "MVP: assignment to unknown target: " + s.name());
    }


    /** Emit a mutable bind `x :! v`. For locals, semantically
     *  identical to immutable bind — the value lives in a local
     *  slot and subsequent {@code Stmt.Assign} writes via ASTORE.
     *  The mutability distinction is enforced by the parser/AST,
     *  not by the JVM storage. Top-level MutBinds get hoisted to
     *  static fields by {@link #emitTopLevel} the same way Bind
     *  does. */
    void emitMutBind(Stmt.MutBind mb, MethodVisitor mv, Locals locals) {
        if (!(mb.target() instanceof Stmt.BindTarget.Simple simple)) {
            throw new IrijCompiler.CompileException(
                    "MVP: mutable bind requires a Simple target (got "
                            + mb.target().getClass().getSimpleName() + ")");
        }
        Integer liftedIdx = ce.currentLiftedLocals.get(simple.name());
        if (liftedIdx != null && ce.currentKSlot >= 0) {
            mv.visitVarInsn(ALOAD, ce.currentKSlot);
            mv.visitFieldInsn(GETFIELD, ClassEmitter.CONT, "fields", "[Ljava/lang/Object;");
            pushIconst(mv, liftedIdx);
            emitExpr(mb.value(), mv, locals);
            mv.visitInsn(AASTORE);
            return;
        }
        emitExpr(mb.value(), mv, locals);
        int slot = locals.allocate(simple.name());
        mv.visitVarInsn(ASTORE, slot);
    }


    void emitBind(Stmt.Bind b, MethodVisitor mv, Locals locals) {
        switch (b.target()) {
            case Stmt.BindTarget.Simple simple -> {
                Integer liftedIdx = ce.currentLiftedLocals.get(simple.name());
                if (liftedIdx != null && ce.currentKSlot >= 0) {
                    mv.visitVarInsn(ALOAD, ce.currentKSlot);
                    mv.visitFieldInsn(GETFIELD, ClassEmitter.CONT, "fields", "[Ljava/lang/Object;");
                    pushIconst(mv, liftedIdx);
                    emitExpr(b.value(), mv, locals);
                    mv.visitInsn(AASTORE);
                    return;
                }
                emitExpr(b.value(), mv, locals);
                int slot = locals.allocate(simple.name());
                mv.visitVarInsn(ASTORE, slot);
            }
            case Stmt.BindTarget.Destructure dt -> {
                emitExpr(b.value(), mv, locals);
                int scrut = locals.allocateAnon();
                mv.visitVarInsn(ASTORE, scrut);
                Label failL = new Label();
                Label okL = new Label();
                ce.patEm.emitPatternTest(dt.pattern(), scrut, mv, locals, failL);
                mv.visitJumpInsn(GOTO, okL);
                mv.visitLabel(failL);
                mv.visitVarInsn(ALOAD, scrut);
                mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("noMatch"), "noMatch",
                        "(Ljava/lang/Object;)Ljava/lang/IllegalStateException;", false);
                mv.visitInsn(ATHROW);
                mv.visitLabel(okL);
            }
        }
    }


    void emitIfStmt(Stmt.IfStmt ifs, MethodVisitor mv, Locals locals) {
        emitExpr(ifs.cond(), mv, locals);
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("truthy"), "truthy", "(Ljava/lang/Object;)Z", false);
        Label elseL = new Label();
        Label endL = new Label();
        mv.visitJumpInsn(IFEQ, elseL);
        for (Stmt t : ifs.thenBranch()) emitStmt(t, mv, locals);
        mv.visitJumpInsn(GOTO, endL);
        mv.visitLabel(elseL);
        if (ifs.elseBranch() != null) {
            for (Stmt t : ifs.elseBranch()) emitStmt(t, mv, locals);
        }
        mv.visitLabel(endL);
    }


    /** Emits an expression used as a statement (result discarded). */
    void emitStmtExpr(Expr e, MethodVisitor mv, Locals locals) {
        emitExpr(e, mv, locals);
        mv.visitInsn(POP);
    }


    // ── Expressions (produce one Object on stack) ───────────────────────

    void emitExpr(Expr e, MethodVisitor mv, Locals locals) {
        ce.emitLine(mv, ClassEmitter.locOf(e));
        switch (e) {
            case Expr.IntLit i -> {
                pushLong(mv, i.value());
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf",
                        "(J)Ljava/lang/Long;", false);
            }
            case Expr.FloatLit f -> {
                mv.visitLdcInsn(f.value());
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf",
                        "(D)Ljava/lang/Double;", false);
            }
            case Expr.BoolLit b -> {
                mv.visitFieldInsn(GETSTATIC, "java/lang/Boolean",
                        b.value() ? "TRUE" : "FALSE", "Ljava/lang/Boolean;");
            }
            case Expr.StrLit s -> mv.visitLdcInsn(s.value());
            case Expr.UnitLit __ -> mv.visitFieldInsn(GETSTATIC, ClassEmitter.VALUES, "UNIT", ClassEmitter.OBJ_DESC);
            case Expr.KeywordLit k -> {
                mv.visitTypeInsn(NEW, ClassEmitter.VALUES + "$Keyword");
                mv.visitInsn(DUP);
                mv.visitLdcInsn(k.name());
                mv.visitMethodInsn(INVOKESPECIAL, ClassEmitter.VALUES + "$Keyword", "<init>",
                        "(Ljava/lang/String;)V", false);
            }
            case Expr.VectorLit vl -> emitListLiteral(vl.elements(), mv, locals, "IrijVector");
            case Expr.TupleLit tl -> emitTupleLiteral(tl.elements(), mv, locals);
            case Expr.SetLit sl -> emitListLiteral(sl.elements(), mv, locals, "IrijSet");
            case Expr.MapLit ml -> emitMapLiteral(ml, mv, locals);
            case Expr.RecordUpdate ru -> emitRecordUpdate(ru, mv, locals);
            case Expr.StringInterp si -> emitStringInterp(si, mv, locals);
            case Expr.Var v -> emitVarLoad(v.name(), mv, locals);
            case Expr.TypeRef tr -> emitConstructorApp(tr.name(), List.of(), mv, locals);
            case Expr.BinaryOp bop -> emitBinaryOp(bop, mv, locals);
            case Expr.UnaryOp uop -> emitUnaryOp(uop, mv, locals);
            case Expr.IfExpr ie -> emitIfExpr(ie, mv, locals);
            case Expr.App app -> emitApp(app, mv, locals);
            case Expr.MatchExpr me -> ce.patEm.emitMatchExpr(me, mv, locals);
            case Expr.Lambda lam -> ce.lamEm.emitLambda(lam, mv, locals);
            case Expr.Block blk -> emitBlock(blk, mv, locals);
            case Expr.DoExpr de -> {
                // `do e1 e2 ... en` — evaluate in order, keep only the
                // last value (spec §1.3.2: sequence effects).
                if (de.exprs().isEmpty()) {
                    mv.visitFieldInsn(GETSTATIC, ClassEmitter.VALUES, "UNIT", ClassEmitter.OBJ_DESC);
                } else {
                    for (int i = 0; i < de.exprs().size() - 1; i++) {
                        emitExpr(de.exprs().get(i), mv, locals);
                        mv.visitInsn(POP);
                    }
                    emitExpr(de.exprs().get(de.exprs().size() - 1), mv, locals);
                }
            }
            case Expr.DotAccess da -> emitDotAccess(da, mv, locals);
            case Expr.JavaRef jr -> {
                mv.visitLdcInsn(jr.ref());
                mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("javaStaticRef"), "javaStaticRef",
                        "(Ljava/lang/String;)Ljava/lang/Object;", false);
            }
            case Expr.Compose c -> emitCompose(c, mv, locals);
            case Expr.OpSection os -> emitOpSection(os.op(), mv);
            case Expr.Range r -> {
                emitExpr(r.from(), mv, locals);
                emitExpr(r.to(), mv, locals);
                mv.visitInsn(r.exclusive() ? ICONST_1 : ICONST_0);
                mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("rangeOf"), "rangeOf",
                        "(Ljava/lang/Object;Ljava/lang/Object;Z)Ljava/lang/Object;",
                        false);
            }
            // Pipe: `a |> f` ≡ `f a`, `a <| f` ≡ `f a` (reversed at parse).
            // Lower to an App so the existing apply-path handles arity,
            // effect rows, callable kinds (IrijFn / user fn / builtin).
            //
            // Special case: if the function side is itself an App, splice
            // the piped value as the final positional arg instead of
            // currying. So `x |> get k` becomes `get k x` (2-arg call)
            // rather than `(get k)(x)`. This matches the canonical pipe
            // idiom and avoids partial-application paths that builtin
            // dispatch doesn't natively support.
            case Expr.Pipe p -> {
                Expr fn = p.forward() ? p.right() : p.left();
                Expr arg = p.forward() ? p.left() : p.right();
                if (fn instanceof Expr.App innerApp) {
                    java.util.List<Expr> spliced = new java.util.ArrayList<>(innerApp.args());
                    spliced.add(arg);
                    emitExpr(new Expr.App(innerApp.fn(), spliced, p.loc()), mv, locals);
                } else {
                    emitExpr(new Expr.App(fn, java.util.List.of(arg), p.loc()), mv, locals);
                }
            }
            case Expr.SeqOp so -> emitSeqOp(so, mv, locals);
            default -> throw new IrijCompiler.CompileException(
                    "MVP: unsupported expression: " + e.getClass().getSimpleName());
        }
    }


    void emitVarLoad(String name, MethodVisitor mv, Locals locals) {
        // Locals shadow ALL outer scopes — pattern binds, params, lets.
        // Without this, `Err e => e` returned Math.E (the `e` constant
        // shadowed by the pattern-bound `e`).
        Integer __preSlot = locals.lookup(name);
        if (__preSlot != null) {
            mv.visitVarInsn(ALOAD, __preSlot);
            return;
        }
        Integer __preLifted = ce.currentLiftedLocals.get(name);
        if (__preLifted != null && ce.currentKSlot >= 0) {
            mv.visitVarInsn(ALOAD, ce.currentKSlot);
            mv.visitFieldInsn(GETFIELD, ClassEmitter.CONT, "fields", "[Ljava/lang/Object;");
            pushIconst(mv, __preLifted);
            mv.visitInsn(AALOAD);
            return;
        }
        // Top-level mut binds: read via GETSTATIC so cross-thread
        // updates (e.g. assignments inside a forked fiber) are visible.
        // The dual local slot allocated at init time is only used by
        // the initializer itself; once init completes, the static is
        // authoritative.
        if (ce.topLevelFields.containsKey(name)
                && !ClassEmitter.BUILTIN_CONST_NAMES.contains(name)) {
            mv.visitFieldInsn(GETSTATIC, ce.internalName,
                    ce.topLevelFields.get(name), ClassEmitter.OBJ_DESC);
            return;
        }
        switch (name) {
            case "true" -> {
                mv.visitFieldInsn(GETSTATIC, "java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;");
                return;
            }
            case "false" -> {
                mv.visitFieldInsn(GETSTATIC, "java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;");
                return;
            }
            case "pi" -> {
                mv.visitFieldInsn(GETSTATIC, RtOwners.of("PI_BOXED"), "PI_BOXED", ClassEmitter.OBJ_DESC);
                return;
            }
            case "e" -> {
                mv.visitFieldInsn(GETSTATIC, RtOwners.of("E_BOXED"), "E_BOXED", ClassEmitter.OBJ_DESC);
                return;
            }
            case "identity" -> {
                mv.visitFieldInsn(GETSTATIC, RtOwners.of("IDENTITY"), "IDENTITY", ClassEmitter.IRIJ_FN_DESC);
                return;
            }
            case "const" -> {
                mv.visitFieldInsn(GETSTATIC, RtOwners.of("CONST"), "CONST", ClassEmitter.IRIJ_FN_DESC);
                return;
            }
            // Builtins passed as values (sort-by length #[…], etc.)
            // Each maps to a static IrijFn in RuntimeSupport.
            case "length"   -> { mv.visitFieldInsn(GETSTATIC, RtOwners.of("LENGTH"), "LENGTH", ClassEmitter.IRIJ_FN_DESC); return; }
            case "head"     -> { mv.visitFieldInsn(GETSTATIC, RtOwners.of("HEAD"), "HEAD", ClassEmitter.IRIJ_FN_DESC); return; }
            case "tail"     -> { mv.visitFieldInsn(GETSTATIC, RtOwners.of("TAIL"), "TAIL", ClassEmitter.IRIJ_FN_DESC); return; }
            case "empty?"   -> { mv.visitFieldInsn(GETSTATIC, RtOwners.of("EMPTY_Q"), "EMPTY_Q", ClassEmitter.IRIJ_FN_DESC); return; }
            case "to-str"   -> { mv.visitFieldInsn(GETSTATIC, RtOwners.of("TO_STR"), "TO_STR", ClassEmitter.IRIJ_FN_DESC); return; }
            case "not"      -> { mv.visitFieldInsn(GETSTATIC, RtOwners.of("NOT_FN"), "NOT_FN", ClassEmitter.IRIJ_FN_DESC); return; }
            case "type-of"  -> { mv.visitFieldInsn(GETSTATIC, RtOwners.of("TYPE_OF"), "TYPE_OF", ClassEmitter.IRIJ_FN_DESC); return; }
            case "abs"      -> { mv.visitFieldInsn(GETSTATIC, RtOwners.of("ABS_FN"), "ABS_FN", ClassEmitter.IRIJ_FN_DESC); return; }
            case "sqrt"     -> { mv.visitFieldInsn(GETSTATIC, RtOwners.of("SQRT_FN"), "SQRT_FN", ClassEmitter.IRIJ_FN_DESC); return; }
            case "floor"    -> { mv.visitFieldInsn(GETSTATIC, RtOwners.of("FLOOR_FN"), "FLOOR_FN", ClassEmitter.IRIJ_FN_DESC); return; }
            case "ceil"     -> { mv.visitFieldInsn(GETSTATIC, RtOwners.of("CEIL_FN"), "CEIL_FN", ClassEmitter.IRIJ_FN_DESC); return; }
            case "round"    -> { mv.visitFieldInsn(GETSTATIC, RtOwners.of("ROUND_FN"), "ROUND_FN", ClassEmitter.IRIJ_FN_DESC); return; }
            case "reverse"  -> { mv.visitFieldInsn(GETSTATIC, RtOwners.of("REVERSE_FN"), "REVERSE_FN", ClassEmitter.IRIJ_FN_DESC); return; }
            case "sort"     -> { mv.visitFieldInsn(GETSTATIC, RtOwners.of("SORT_FN"), "SORT_FN", ClassEmitter.IRIJ_FN_DESC); return; }
            case "println"  -> { mv.visitFieldInsn(GETSTATIC, RtOwners.of("PRINTLN_FN"), "PRINTLN_FN", ClassEmitter.IRIJ_FN_DESC); return; }
            case "print"    -> { mv.visitFieldInsn(GETSTATIC, RtOwners.of("PRINT_FN"), "PRINT_FN", ClassEmitter.IRIJ_FN_DESC); return; }
            default -> {}
        }
        Integer slot = locals.lookup(name);
        if (slot != null) {
            mv.visitVarInsn(ALOAD, slot);
            return;
        }
        Integer liftedIdx = ce.currentLiftedLocals.get(name);
        if (liftedIdx != null && ce.currentKSlot >= 0) {
            mv.visitVarInsn(ALOAD, ce.currentKSlot);
            mv.visitFieldInsn(GETFIELD, ClassEmitter.CONT, "fields", "[Ljava/lang/Object;");
            pushIconst(mv, liftedIdx);
            mv.visitInsn(AALOAD);
            return;
        }
        String stateField = ce.currentStateFields.get(name);
        if (stateField != null) {
            mv.visitFieldInsn(GETSTATIC, ce.internalName, stateField, ClassEmitter.OBJ_DESC);
            return;
        }
        if (ce.handlers.containsKey(name)) {
            mv.visitMethodInsn(INVOKESTATIC, ce.internalName, ce.effEm.handlerBuildName(name),
                    "()L" + ClassEmitter.COMP_HANDLER + ";", false);
            return;
        }
        // Namespace-mode fallback for nREPL eval-bytecode: read the value
        // from the session's shared namespace via RT.nsGet. Lets
        // successive evals see each other's top-level `:=` bindings.
        if (ce.options.namespaceMode()) {
            mv.visitLdcInsn(name);
            mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("nsGet"), "nsGet",
                    "(Ljava/lang/String;)Ljava/lang/Object;", false);
            return;
        }
        // Top-level binding hoisted to a static field (interpreter
        // semantics: visible from any user-fn body).
        String topField = ce.topLevelFields.get(name);
        if (topField != null) {
            mv.visitFieldInsn(GETSTATIC, ce.internalName, topField, ClassEmitter.OBJ_DESC);
            return;
        }
        // User fn referenced as a value (e.g. passed to a higher-order fn
        // like `fold add 0 v`). Synthesise an IrijFn wrapper once per fn
        // and push it here as an LMF-built lambda.
        if (ce.fnArity.containsKey(name)) {
            int arity = ce.fnArity.get(name);
            ce.fnEm.ensureUserFnWrapper(name, arity);
            Handle bsm = new Handle(H_INVOKESTATIC,
                    "java/lang/invoke/LambdaMetafactory", "metafactory",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                            + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                            + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                            + "Ljava/lang/invoke/CallSite;",
                    false);
            Type samType = Type.getMethodType(ClassEmitter.APPLY_DESC);
            Handle implHandle = new Handle(H_INVOKESTATIC, ce.internalName,
                    ce.fnEm.userFnWrapperName(name), ClassEmitter.APPLY_DESC, false);
            mv.visitInvokeDynamicInsn("apply", "()" + ClassEmitter.IRIJ_FN_DESC, bsm,
                    samType, implHandle, samType);
            return;
        }
        // Final fallback: resolve as a builtin via the runtime
        // registry. Lets any name registered by Builtins.install
        // be used as a first-class value (`sort-by length #[…]`,
        // `@ to-str v`, etc.) without enumerating each one as a
        // static IrijFn here.
        mv.visitLdcInsn(name);
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("builtinFn"), "builtinFn",
                "(Ljava/lang/String;)" + ClassEmitter.IRIJ_FN_DESC, false);
    }


    /** Operator section `(op)` — push the pre-built RuntimeSupport.IrijFn
     *  constant so the operator can be passed as a value. */
    void emitOpSection(String op, MethodVisitor mv) {
        String constName = switch (op) {
            case "+"  -> "OP_ADD";
            case "-"  -> "OP_SUB";
            case "*"  -> "OP_MUL";
            case "/"  -> "OP_DIV";
            case "%"  -> "OP_MOD";
            case "++" -> "OP_CONCAT";
            case "<"  -> "OP_LT";
            case "<=" -> "OP_LE";
            case ">"  -> "OP_GT";
            case ">=" -> "OP_GE";
            case "==" -> "OP_EQ";
            case "!=" -> "OP_NEQ";
            default -> throw new IrijCompiler.CompileException(
                    "MVP: operator section not yet supported: (" + op + ")");
        };
        mv.visitFieldInsn(GETSTATIC, RtOwners.of(constName), constName, ClassEmitter.IRIJ_FN_DESC);
    }


    void emitCompose(Expr.Compose c, MethodVisitor mv, Locals locals) {
        // Assume handler composition — emit both as Object and call RuntimeSupport.compose.
        // (Function composition for non-handler values isn't supported in MVP.)
        emitExpr(c.left(), mv, locals);
        emitExpr(c.right(), mv, locals);
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("compose"), "compose",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
    }


    void emitDotAccess(Expr.DotAccess da, MethodVisitor mv, Locals locals) {
        if (da.target() instanceof Expr.Var v) {
            Map<String, String> fields = ce.handlerStateFields.get(v.name());
            if (fields != null) {
                String field = fields.get(da.field());
                if (field != null) {
                    mv.visitFieldInsn(GETSTATIC, ce.internalName, field, ClassEmitter.OBJ_DESC);
                    return;
                }
            }
            // `mod.name` where mod is a `use` alias: resolve as unqualified name.
            if (ce.moduleAliases.contains(v.name())) {
                emitVarLoad(da.field(), mv, locals);
                return;
            }
        }
        // Interop fallthrough: evaluate target, delegate to JavaInterop.
        emitExpr(da.target(), mv, locals);
        mv.visitLdcInsn(da.field());
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("javaInstanceRef"), "javaInstanceRef",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
    }


    void emitBinaryOp(Expr.BinaryOp bop, MethodVisitor mv, Locals locals) {
        emitExpr(bop.left(), mv, locals);
        emitExpr(bop.right(), mv, locals);
        switch (bop.op()) {
            case "+"  -> mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("add"), "add", ClassEmitter.BINOP_DESC, false);
            case "-"  -> mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("sub"), "sub", ClassEmitter.BINOP_DESC, false);
            case "*"  -> mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("mul"), "mul", ClassEmitter.BINOP_DESC, false);
            case "/"  -> mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("div"), "div", ClassEmitter.BINOP_DESC, false);
            case "%"  -> mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("mod"), "mod", ClassEmitter.BINOP_DESC, false);
            case "**" -> mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("pow"), "pow", ClassEmitter.BINOP_DESC, false);
            case "<"  -> cmpToBoxedBool(mv, "lt");
            case "<=" -> cmpToBoxedBool(mv, "le");
            case ">"  -> cmpToBoxedBool(mv, "gt");
            case ">=" -> cmpToBoxedBool(mv, "ge");
            case "==" -> cmpToBoxedBool(mv, "eq");
            case "!=", "/=" -> cmpToBoxedBool(mv, "neq");
            case "++" -> mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("concat"), "concat", ClassEmitter.BINOP_DESC, false);
            case "&&" -> {
                mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("and"), "and", ClassEmitter.CMPOP_DESC, false);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf",
                        "(Z)Ljava/lang/Boolean;", false);
            }
            case "||" -> {
                mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("or"), "or", ClassEmitter.CMPOP_DESC, false);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf",
                        "(Z)Ljava/lang/Boolean;", false);
            }
            default -> throw new IrijCompiler.CompileException("MVP: unsupported binary op: " + bop.op());
        }
    }


    void emitListLiteral(List<Expr> elems, MethodVisitor mv, Locals locals, String innerName) {
        // NEW dev/irij/runtime/Values$IrijVector   (or IrijSet)
        // DUP
        // build List.of(e0, e1, ...) or ArrayList for >10; use Arrays.asList for simplicity
        mv.visitTypeInsn(NEW, ClassEmitter.VALUES + "$" + innerName);
        mv.visitInsn(DUP);
        pushObjectList(elems, mv, locals);
        String param = innerName.equals("IrijVector") ? "Ljava/util/List;" :
                       innerName.equals("IrijSet")    ? "Ljava/util/Set;"  : "Ljava/util/List;";
        if (innerName.equals("IrijSet")) {
            // Wrap the List into a LinkedHashSet
            mv.visitTypeInsn(NEW, "java/util/LinkedHashSet");
            mv.visitInsn(DUP_X1);
            mv.visitInsn(SWAP);
            mv.visitMethodInsn(INVOKESPECIAL, "java/util/LinkedHashSet", "<init>",
                    "(Ljava/util/Collection;)V", false);
        }
        mv.visitMethodInsn(INVOKESPECIAL, ClassEmitter.VALUES + "$" + innerName, "<init>",
                "(" + param + ")V", false);
    }


    void emitTupleLiteral(List<Expr> elems, MethodVisitor mv, Locals locals) {
        mv.visitTypeInsn(NEW, ClassEmitter.VALUES + "$IrijTuple");
        mv.visitInsn(DUP);
        pushIconst(mv, elems.size());
        mv.visitTypeInsn(ANEWARRAY, ClassEmitter.OBJ);
        for (int i = 0; i < elems.size(); i++) {
            mv.visitInsn(DUP);
            pushIconst(mv, i);
            emitExpr(elems.get(i), mv, locals);
            mv.visitInsn(AASTORE);
        }
        mv.visitMethodInsn(INVOKESPECIAL, ClassEmitter.VALUES + "$IrijTuple", "<init>",
                "([Ljava/lang/Object;)V", false);
    }


    /** `"prefix {expr} suffix"` — build a String via StringBuilder,
     *  appending each part. Interpolated exprs go through
     *  {@code Values.toIrijString}. */
    void emitStringInterp(Expr.StringInterp si, MethodVisitor mv, Locals locals) {
        mv.visitTypeInsn(NEW, "java/lang/StringBuilder");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        for (Expr.StringPart part : si.parts()) {
            if (part instanceof Expr.StringPart.Literal lit) {
                mv.visitLdcInsn(lit.text());
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            } else if (part instanceof Expr.StringPart.Interpolation interp) {
                emitExpr(interp.expr(), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.VALUES, "toIrijString",
                        "(Ljava/lang/Object;)Ljava/lang/String;", false);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            }
        }
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false);
    }


    /** `{...base k1= v1 k2= v2}` — clone base map, overwrite keys. */
    void emitRecordUpdate(Expr.RecordUpdate ru, MethodVisitor mv, Locals locals) {
        // Push the base value, call RT.recordUpdateBegin → LinkedHashMap.
        emitVarLoad(ru.base(), mv, locals);
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("recordUpdateBegin"), "recordUpdateBegin",
                "(Ljava/lang/Object;)Ljava/util/LinkedHashMap;", false);
        // For each Field entry, DUP map, push key/value, put, pop result.
        for (Expr.MapEntry me : ru.updates()) {
            mv.visitInsn(DUP);
            switch (me) {
                case Expr.MapEntry.Field f -> {
                    mv.visitLdcInsn(f.key());
                    emitExpr(f.value(), mv, locals);
                }
                case Expr.MapEntry.DynField df -> {
                    emitExpr(df.keyExpr(), mv, locals);
                    mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("asMapKey"), "asMapKey",
                            "(Ljava/lang/Object;)Ljava/lang/String;", false);
                    emitExpr(df.value(), mv, locals);
                }
                case Expr.MapEntry.Spread sp ->
                        throw new IrijCompiler.CompileException(
                                "MVP: record-update spread not supported");
            }
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
            mv.visitInsn(POP);
        }
        // Wrap the LinkedHashMap in IrijMap.
        mv.visitTypeInsn(NEW, ClassEmitter.VALUES + "$IrijMap");
        mv.visitInsn(DUP_X1);
        mv.visitInsn(SWAP);
        mv.visitMethodInsn(INVOKESPECIAL, ClassEmitter.VALUES + "$IrijMap", "<init>",
                "(Ljava/util/Map;)V", false);
    }


    void emitMapLiteral(Expr.MapLit ml, MethodVisitor mv, Locals locals) {
        // new java.util.LinkedHashMap
        mv.visitTypeInsn(NEW, "java/util/LinkedHashMap");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/LinkedHashMap", "<init>", "()V", false);
        for (Expr.MapEntry me : ml.entries()) {
            mv.visitInsn(DUP);
            switch (me) {
                case Expr.MapEntry.Field f -> {
                    mv.visitLdcInsn(f.key());
                    emitExpr(f.value(), mv, locals);
                }
                case Expr.MapEntry.DynField df -> {
                    emitExpr(df.keyExpr(), mv, locals);
                    mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("asMapKey"), "asMapKey",
                            "(Ljava/lang/Object;)Ljava/lang/String;", false);
                    emitExpr(df.value(), mv, locals);
                }
                case Expr.MapEntry.Spread sp ->
                        throw new IrijCompiler.CompileException("MVP: map spread not supported");
            }
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
            mv.visitInsn(POP);
        }
        // Wrap in IrijMap
        mv.visitTypeInsn(NEW, ClassEmitter.VALUES + "$IrijMap");
        mv.visitInsn(DUP_X1);
        mv.visitInsn(SWAP);
        mv.visitMethodInsn(INVOKESPECIAL, ClassEmitter.VALUES + "$IrijMap", "<init>", "(Ljava/util/Map;)V", false);
    }


    void pushObjectList(List<Expr> elems, MethodVisitor mv, Locals locals) {
        // Build via Arrays.asList(new Object[]{...}) so we handle any size.
        pushIconst(mv, elems.size());
        mv.visitTypeInsn(ANEWARRAY, ClassEmitter.OBJ);
        for (int i = 0; i < elems.size(); i++) {
            mv.visitInsn(DUP);
            pushIconst(mv, i);
            emitExpr(elems.get(i), mv, locals);
            mv.visitInsn(AASTORE);
        }
        mv.visitMethodInsn(INVOKESTATIC, "java/util/Arrays", "asList",
                "([Ljava/lang/Object;)Ljava/util/List;", false);
    }


    void pushIconst(MethodVisitor mv, int v) {
        if (v >= -1 && v <= 5) mv.visitInsn(ICONST_0 + v);
        else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) mv.visitIntInsn(BIPUSH, v);
        else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) mv.visitIntInsn(SIPUSH, v);
        else mv.visitLdcInsn(v);
    }


    /** Emit an Expr.SeqOp. Two cases:
     *
     *   1. {@code arg == null} — the operator appears in value position
     *      (e.g. `(fold (+) 0 v)` passing `+` as a value, or
     *      `/+` standalone). Push the shared IrijFn instance from
     *      RuntimeSupport.
     *
     *   2. {@code arg != null} — the operator was applied to one
     *      operand. For HOF ops (`@`, `/?`, `/!`, `@i`, `/^`, `/$`)
     *      the operand is the function to map/filter; the SeqOp itself
     *      evaluates to a curried IrijFn awaiting the collection. For
     *      reduce ops (`/+`, `/*`, `/#`, `/&`, `/|`) the operand is
     *      the collection and we compute directly.
     */
    void emitSeqOp(Expr.SeqOp so, MethodVisitor mv, Locals locals) {
        String op = so.op();
        Expr arg = so.arg();
        if (arg == null) {
            // Value position: push the shared IrijFn for this op.
            String field = switch (op) {
                case "/+" -> "SEQ_SUM";
                case "/*" -> "SEQ_PRODUCT";
                case "/#" -> "SEQ_COUNT";
                case "/&" -> "SEQ_ALL";
                case "/|" -> "SEQ_ANY";
                default -> null;
            };
            if (field != null) {
                mv.visitFieldInsn(GETSTATIC, RtOwners.of(field), field, ClassEmitter.IRIJ_FN_DESC);
                return;
            }
            // HOF ops standalone aren't useful as bare values; throw.
            throw new IrijCompiler.CompileException(
                    "MVP: seq op '" + op + "' requires an operand at " + so.loc());
        }
        // With operand — either curry (HOF) or compute (reduce).
        switch (op) {
            case "@", "/?", "/!", "@i", "/^", "/$" -> {
                // Partial application: emit RT.seq<X>Partial(arg) → IrijFn
                emitExpr(arg, mv, locals);
                String method = switch (op) {
                    case "@"  -> "seqMapPartial";
                    case "@i" -> "seqMapIndexedPartial";
                    case "/?" -> "seqFilterPartial";
                    case "/!" -> "seqFindFirstPartial";
                    case "/^" -> "seqReducePartial";
                    case "/$" -> "seqScanPartial";
                    default -> throw new IllegalStateException();
                };
                mv.visitMethodInsn(INVOKESTATIC, RtOwners.of(method), method,
                        "(Ljava/lang/Object;)" + ClassEmitter.IRIJ_FN_DESC, false);
            }
            case "/+", "/*", "/#", "/&", "/|" -> {
                // Reduce in place.
                emitExpr(arg, mv, locals);
                String method = switch (op) {
                    case "/+" -> "seqSum";
                    case "/*" -> "seqProduct";
                    case "/#" -> "seqCount";
                    case "/&" -> "seqAll";
                    case "/|" -> "seqAny";
                    default -> throw new IllegalStateException();
                };
                mv.visitMethodInsn(INVOKESTATIC, RtOwners.of(method), method,
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            }
            default -> throw new IrijCompiler.CompileException(
                    "MVP: unsupported seq op: " + op);
        }
    }


    /** Emit a scope block. Leaves the body result (after join) on the stack. */
    void emitScope(Stmt.Scope s, MethodVisitor mv, Locals outer) {
        // new CompiledScopeHandle(modifier)
        mv.visitTypeInsn(NEW, ClassEmitter.SCOPE_HANDLE);
        mv.visitInsn(DUP);
        if (s.modifier() == null) mv.visitInsn(ACONST_NULL);
        else mv.visitLdcInsn(s.modifier());
        mv.visitMethodInsn(INVOKESPECIAL, ClassEmitter.SCOPE_HANDLE, "<init>",
                "(Ljava/lang/String;)V", false);

        Locals inner = outer.childScope();
        int handleSlot;
        if (s.name() != null) {
            handleSlot = inner.allocate(s.name());
        } else {
            handleSlot = inner.allocateAnon();
        }
        mv.visitVarInsn(ASTORE, handleSlot);

        // Emit body statements. Last stmt's value is the body result.
        List<Stmt> stmts = s.body();
        if (stmts.isEmpty()) {
            mv.visitFieldInsn(GETSTATIC, ClassEmitter.VALUES, "UNIT", ClassEmitter.OBJ_DESC);
        } else {
            for (int i = 0; i < stmts.size() - 1; i++) emitStmt(stmts.get(i), mv, inner);
            Stmt last = stmts.get(stmts.size() - 1);
            if (!emitTailStmtValue(last, mv, inner)) {
                emitStmt(last, mv, inner);
                mv.visitFieldInsn(GETSTATIC, ClassEmitter.VALUES, "UNIT", ClassEmitter.OBJ_DESC);
            }
        }

        // handle.joinByModifier(bodyResult)
        int resultSlot = inner.allocateAnon();
        mv.visitVarInsn(ASTORE, resultSlot);
        mv.visitVarInsn(ALOAD, handleSlot);
        mv.visitVarInsn(ALOAD, resultSlot);
        mv.visitMethodInsn(INVOKEVIRTUAL, ClassEmitter.SCOPE_HANDLE, "joinByModifier",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
    }


    void emitConstructorApp(String tag, List<Expr> args, MethodVisitor mv, Locals locals) {
        List<String> fieldNames = ce.productFields.get(tag);
        String specName = ce.tagToSpec.get(tag);
        if (fieldNames != null && fieldNames.size() == args.size()) {
            // new Values$Tagged(tag, List.of(args...), Map.of(name → arg, ...), specName)
            mv.visitTypeInsn(NEW, ClassEmitter.VALUES + "$Tagged");
            mv.visitInsn(DUP);
            mv.visitLdcInsn(tag);
            pushObjectList(args, mv, locals);
            pushNamedFieldMap(fieldNames, args, mv, locals);
            if (specName != null) {
                mv.visitLdcInsn(specName);
                mv.visitMethodInsn(INVOKESPECIAL, ClassEmitter.VALUES + "$Tagged", "<init>",
                        "(Ljava/lang/String;Ljava/util/List;Ljava/util/Map;Ljava/lang/String;)V", false);
                // Certify here, at the one place a product value can be
                // built. Validation downstream fast-paths on a matching
                // specName, so without this a constructor call is a hole
                // straight through every later check: `R "str" 2` would
                // satisfy `x :: Int` forever after.
                mv.visitLdcInsn(specName);
                mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.SPEC_VALIDATOR, "certifyProduct",
                        "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            } else {
                mv.visitMethodInsn(INVOKESPECIAL, ClassEmitter.VALUES + "$Tagged", "<init>",
                        "(Ljava/lang/String;Ljava/util/List;Ljava/util/Map;)V", false);
            }
            return;
        }
        // Sum variant or unknown: positional only (+ specName when known).
        mv.visitTypeInsn(NEW, ClassEmitter.VALUES + "$Tagged");
        mv.visitInsn(DUP);
        mv.visitLdcInsn(tag);
        pushObjectList(args, mv, locals);
        if (specName != null) {
            mv.visitInsn(ACONST_NULL);  // namedFields = null
            mv.visitLdcInsn(specName);
            mv.visitMethodInsn(INVOKESPECIAL, ClassEmitter.VALUES + "$Tagged", "<init>",
                    "(Ljava/lang/String;Ljava/util/List;Ljava/util/Map;Ljava/lang/String;)V", false);
        } else {
            mv.visitMethodInsn(INVOKESPECIAL, ClassEmitter.VALUES + "$Tagged", "<init>",
                    "(Ljava/lang/String;Ljava/util/List;)V", false);
        }
    }


    void pushNamedFieldMap(List<String> names, List<Expr> args,
                                    MethodVisitor mv, Locals locals) {
        mv.visitTypeInsn(NEW, "java/util/LinkedHashMap");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/LinkedHashMap", "<init>", "()V", false);
        for (int i = 0; i < names.size(); i++) {
            mv.visitInsn(DUP);
            mv.visitLdcInsn(names.get(i));
            emitExpr(args.get(i), mv, locals);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
            mv.visitInsn(POP);
        }
    }


    // ── Lambda values (first-class functions) ───────────────────────────

    /** Call any runtime callable (IrijFn or interop BuiltinFn): evaluate callee,
     *  pack args into Object[], dispatch via RuntimeSupport.callAny. */
    void emitIrijFnCall(Expr fnExpr, List<Expr> args, MethodVisitor mv, Locals locals) {
        emitExpr(fnExpr, mv, locals);
        pushObjectArray(args, mv, locals);
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("callAny"), "callAny",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
    }


    void pushObjectArray(List<Expr> args, MethodVisitor mv, Locals locals) {
        pushIconst(mv, args.size());
        mv.visitTypeInsn(ANEWARRAY, ClassEmitter.OBJ);
        for (int i = 0; i < args.size(); i++) {
            mv.visitInsn(DUP);
            pushIconst(mv, i);
            emitExpr(args.get(i), mv, locals);
            mv.visitInsn(AASTORE);
        }
    }


    /** Build an IrijFn that captures the supplied {@code partialArgs}
     *  and delegates to {@code fnName} once the remaining arity is
     *  filled. Evaluates each partial arg eagerly into a fresh local
     *  (so side-effects fire at the partial-application site, matching
     *  the interpreter's evalApp semantics).
     */
    void emitPartialApp(String fnName, int arity, List<Expr> partialArgs,
                                 Expr.SourceLoc loc, MethodVisitor mv, Locals outerLocals) {
        // Stash each partial arg in a fresh outer local.
        List<String> captureNames = new ArrayList<>();
        for (int i = 0; i < partialArgs.size(); i++) {
            emitExpr(partialArgs.get(i), mv, outerLocals);
            String name = "$partial$" + (ce.partialCounter++) + "$" + i;
            int slot = outerLocals.allocate(name);
            mv.visitVarInsn(ASTORE, slot);
            captureNames.add(name);
        }
        // Build the synthetic lambda.
        int missing = arity - partialArgs.size();
        List<Pattern> params = new ArrayList<>();
        List<Expr> bodyArgs = new ArrayList<>();
        for (String cn : captureNames) bodyArgs.add(new Expr.Var(cn, loc));
        for (int j = 0; j < missing; j++) {
            String pn = "$pp$" + ce.partialCounter + "$" + j;
            params.add(new Pattern.VarPat(pn, loc));
            bodyArgs.add(new Expr.Var(pn, loc));
        }
        Expr body = new Expr.App(new Expr.Var(fnName, loc), bodyArgs, loc);
        Expr.Lambda lam = new Expr.Lambda(params, null, body, loc);
        ce.lamEm.emitLambda(lam, mv, outerLocals);
    }


    void emitApp(Expr.App app, MethodVisitor mv, Locals locals) {
        if (app.fn() instanceof Expr.TypeRef tr) {
            emitConstructorApp(tr.name(), app.args(), mv, locals);
            return;
        }
        // `mod.fn x` where mod is a `use` alias → call unqualified fn.
        if (app.fn() instanceof Expr.DotAccess da
                && da.target() instanceof Expr.Var modVar
                && ce.moduleAliases.contains(modVar.name())) {
            emitApp(new Expr.App(new Expr.Var(da.field(), null), app.args(), null),
                    mv, locals);
            return;
        }
        // `cap-name.method args` where cap-name is registered → dispatch
        // through JavaRef on the bound provider class. Phase-2 contract:
        // provider methods are static (capability providers are utility
        // classes whose methods take + return Object). Future phase-2.5
        // can layer instance/singleton support without changing this
        // call site.
        if (app.fn() instanceof Expr.DotAccess da
                && da.target() instanceof Expr.Var capVar
                && ce.capProvider.containsKey(capVar.name())) {
            String providerClass = ce.capProvider.get(capVar.name());
            Expr.JavaRef ref = new Expr.JavaRef(
                    providerClass + "/" + da.field(), da.loc());
            emitApp(new Expr.App(ref, app.args(), app.loc()), mv, locals);
            return;
        }
        // Phase 3 — Irij-record cap call: `cap-name.method args` where
        // cap-name is bound to a map literal. Load the method-name key,
        // then the static field holding the materialised record, look
        // up via RT.getOp(key, coll), and dispatch via RT.callAny with
        // the boxed arg array. RT.getOp is (key, coll) — order matters.
        if (app.fn() instanceof Expr.DotAccess da
                && da.target() instanceof Expr.Var capVar
                && ce.recordCapField.containsKey(capVar.name())) {
            String field = ce.recordCapField.get(capVar.name());
            mv.visitLdcInsn(da.field());
            mv.visitFieldInsn(GETSTATIC, ce.internalName, field, ClassEmitter.OBJ_DESC);
            mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("getOp"), "getOp",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    false);
            pushObjectArray(app.args(), mv, locals);
            mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("callAny"), "callAny",
                    "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
                    false);
            return;
        }
        String fnName = null;
        if (app.fn() instanceof Expr.Var v) fnName = v.name();
        if (fnName != null) {
            // Name-resolution priority: user fn > effect op > builtin.
            // A user-declared fn or effect op of the same name as a
            // stdlib builtin (e.g. `div` is vrata.html's element
            // builder; `log` is a typical effect op name) wins. The
            // builtin is only reached when neither shadows it.
            // The fully-qualified Java form (`java.lang.Math/log`)
            // is the escape hatch for raw access to the JVM method.
            boolean shadowed = ce.fnArity.containsKey(fnName)
                    || ce.effectOps.containsKey(fnName);
            if (!shadowed
                    && ce.intrEm.emitBuiltinApp(fnName, app.args(), mv, locals)) {
                return;
            }
            // Effect op → perform (throws EffectException)
            if (ce.effectOps.containsKey(fnName)) {
                ce.effEm.emitPerform(fnName, app.args(), mv, locals);
                return;
            }
            // Constructor application (e.g. `Some x`) — Var with uppercase
            if (Character.isUpperCase(fnName.charAt(0))) {
                emitConstructorApp(fnName, app.args(), mv, locals);
                return;
            }
            // Local lambda value? invoke as IrijFn.
            if (locals.lookup(fnName) != null) {
                emitIrijFnCall(app.fn(), app.args(), mv, locals);
                return;
            }
            // Lifted (k.fields[]) lambda value — same call shape, the
            // Var-load inside emitIrijFnCall resolves via lifted lookup.
            if (ce.currentLiftedLocals.containsKey(fnName)) {
                emitIrijFnCall(app.fn(), app.args(), mv, locals);
                return;
            }
            // Top-level mut/let binding hoisted to a static field —
            // e.g. `add5 := add-positive 5` where add5 holds a curried
            // IrijFn. The Var-load inside emitIrijFnCall picks it up
            // via topLevelFields → GETSTATIC.
            if (ce.topLevelFields.containsKey(fnName)) {
                emitIrijFnCall(app.fn(), app.args(), mv, locals);
                return;
            }
        } else {
            // Non-Var callee (Lambda expr, App result, etc.): call as IrijFn.
            emitIrijFnCall(app.fn(), app.args(), mv, locals);
            return;
        }
        Integer arity = ce.fnArity.get(fnName);
        if (arity == null) {
            // Namespace-mode (nREPL eval-bytecode): unknown user-fn
            // names may refer to fns defined in a previous eval. Fall
            // through to nsGet(name) → IrijFn → callAny. The IrijFn
            // wrapper was registered in that earlier eval's clinit.
            if (ce.options.namespaceMode()) {
                emitIrijFnCall(app.fn(), app.args(), mv, locals);
                return;
            }
            throw new IrijCompiler.CompileException("Unknown function: " + fnName);
        }
        List<Expr> args = app.args();
        // Unit-only arg → zero-arg call.
        if (arity == 0 && args.size() == 1 && args.get(0) instanceof Expr.UnitLit) {
            args = List.of();
        }
        if (args.size() < arity) {
            // Partial application — synthesize a Lambda that captures
            // the supplied args (via fresh locals) and takes the
            // remaining params. emitLambda's free-var discovery picks
            // up the captures and the resulting IrijFn delegates to
            // the underlying static method when fully applied.
            emitPartialApp(fnName, arity, args, app.loc(), mv, locals);
            return;
        }
        if (args.size() != arity) {
            throw new IrijCompiler.CompileException(
                    "Arity mismatch for " + fnName + ": expected " + arity + ", got " + args.size());
        }
        for (Expr a : args) emitExpr(a, mv, locals);
        StringBuilder desc = new StringBuilder("(");
        for (int i = 0; i < arity; i++) desc.append(ClassEmitter.OBJ_DESC);
        desc.append(")").append(ClassEmitter.OBJ_DESC);
        if (ce.options.directLinking()) {
            // Direct-linked deploy build: max JIT inlinability, no hot-redef.
            mv.visitMethodInsn(INVOKESTATIC, ce.ownerOf(fnName), ClassEmitter.mangle(fnName),
                    desc.toString(), false);
        } else {
            // Dev/REPL build: indy + MutableCallSite so REPL can swap impls
            // without restarting the JVM. Bootstrap registers the site in
            // RuntimeSupport.REDEF_SITES; redefine() updates it later.
            // The owner class (multi-class emission may place the fn in a
            // per-module-file class) is passed as a static bootstrap arg
            // so the target resolves on the right class, not the caller.
            Handle bootstrap = new Handle(
                    H_INVOKESTATIC,
                    RtOwners.of("redefBootstrap"),
                    "redefBootstrap",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;"
                            + "Ljava/lang/String;"
                            + "Ljava/lang/invoke/MethodType;"
                            + "Ljava/lang/String;)"
                            + "Ljava/lang/invoke/CallSite;",
                    false);
            mv.visitInvokeDynamicInsn(ClassEmitter.mangle(fnName), desc.toString(), bootstrap,
                    ce.ownerOf(fnName));
        }
    }


    void emitIfExpr(Expr.IfExpr ie, MethodVisitor mv, Locals locals) {
        emitExpr(ie.cond(), mv, locals);
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("truthy"), "truthy", "(Ljava/lang/Object;)Z", false);
        Label elseL = new Label();
        Label endL = new Label();
        mv.visitJumpInsn(IFEQ, elseL);
        emitExpr(ie.thenBranch(), mv, locals);
        mv.visitJumpInsn(GOTO, endL);
        mv.visitLabel(elseL);
        if (ie.elseBranch() != null) {
            emitExpr(ie.elseBranch(), mv, locals);
        } else {
            mv.visitInsn(ACONST_NULL);
        }
        mv.visitLabel(endL);
    }


    void emitUnaryOp(Expr.UnaryOp uop, MethodVisitor mv, Locals locals) {
        switch (uop.op()) {
            case "-" -> {
                pushLong(mv, 0L);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf",
                        "(J)Ljava/lang/Long;", false);
                emitExpr(uop.operand(), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("sub"), "sub", ClassEmitter.BINOP_DESC, false);
            }
            case "!" -> {
                emitExpr(uop.operand(), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("truthy"), "truthy",
                        "(Ljava/lang/Object;)Z", false);
                // flip: XOR with 1
                mv.visitInsn(ICONST_1);
                mv.visitInsn(IXOR);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf",
                        "(Z)Ljava/lang/Boolean;", false);
            }
            default -> throw new IrijCompiler.CompileException("MVP: unsupported unary op: " + uop.op());
        }
    }


    void cmpToBoxedBool(MethodVisitor mv, String fn) {
        mv.visitMethodInsn(INVOKESTATIC, RtOwners.of(fn), fn, ClassEmitter.CMPOP_DESC, false);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf",
                "(Z)Ljava/lang/Boolean;", false);
    }


    void pushLong(MethodVisitor mv, long v) {
        if (v == 0L) mv.visitInsn(LCONST_0);
        else if (v == 1L) mv.visitInsn(LCONST_1);
        else mv.visitLdcInsn(v);
    }
}
