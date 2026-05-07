package dev.irij.compiler;

import dev.irij.ast.Decl;
import dev.irij.ast.Expr;
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

/**
 * Emits a top-level Irij program as a class with `public static void main`.
 *
 * All Irij values are represented as boxed Object at runtime
 * (see {@link RuntimeSupport}).
 */
final class ClassEmitter implements Opcodes {

    private static final String RT = "dev/irij/compiler/RuntimeSupport";
    private static final String VALUES = "dev/irij/interpreter/Values";
    private static final String OBJ = "java/lang/Object";
    private static final String OBJ_DESC = "Ljava/lang/Object;";
    private static final String BINOP_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String CMPOP_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Z";

    private static final String IRIJ_FN = "dev/irij/compiler/RuntimeSupport$IrijFn";
    private static final String IRIJ_FN_DESC = "Ldev/irij/compiler/RuntimeSupport$IrijFn;";
    private static final String APPLY_DESC = "([Ljava/lang/Object;)Ljava/lang/Object;";

    private final String internalName;
    private final Map<String, Integer> fnArity = new HashMap<>();
    private final Map<String, List<String>> productFields = new HashMap<>();
    // method name → (forType → lambda expr)
    private final Map<String, Map<String, Expr.Lambda>> protoImpls = new HashMap<>();
    // method name → arity
    private final Map<String, Integer> protoArity = new HashMap<>();
    // effect op name → effect name
    private final Map<String, String> effectOps = new HashMap<>();
    // handler name → decl (14c.1 abort-only)
    private final Map<String, Decl.HandlerDecl> handlers = new HashMap<>();
    // handlerName -> (stateVarName -> internal static field name)
    private final Map<String, Map<String, String>> handlerStateFields = new HashMap<>();
    // state field name -> JVM descriptor (always OBJ_DESC here)
    private Map<String, String> currentStateFields = Map.of();
    // 14c.3 SM lowering: name -> continuation fields[] index; kSlot holds cont.
    private Map<String, Integer> currentLiftedLocals = Map.of();
    private int currentKSlot = -1;
    private ClassWriter classWriter;
    private int lambdaCounter = 0;

    private final Set<String> moduleAliases;
    private final CompileOptions options;

    ClassEmitter(String className) {
        this(className, Set.of(), CompileOptions.defaults());
    }

    ClassEmitter(String className, Set<String> moduleAliases) {
        this(className, moduleAliases, CompileOptions.defaults());
    }

    ClassEmitter(String className, Set<String> moduleAliases, CompileOptions options) {
        this.internalName = className.replace('.', '/');
        this.moduleAliases = moduleAliases;
        this.options = options;
    }

    byte[] emit(List<Decl> decls) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        this.classWriter = cw;
        cw.visit(V21, ACC_PUBLIC | ACC_FINAL, internalName, null, OBJ, null);

        // Pass 1: register fn signatures, product-spec field names, proto impls.
        for (Decl d : decls) {
            Decl.FnDecl fn = asFnDecl(d);
            if (fn != null) fnArity.put(fn.name(), fnParams(fn).size());
            Object inner = d instanceof Decl.PubDecl pd ? pd.inner() : d;
            if (inner instanceof Decl.SpecDecl sd
                    && sd.body() instanceof Decl.SpecBody.ProductSpec ps) {
                List<String> names = new ArrayList<>();
                for (Decl.SpecField f : ps.fields()) names.add(f.name());
                productFields.put(sd.name(), names);
            }
            if (inner instanceof Decl.EffectDecl ed) {
                for (Decl.EffectOp op : ed.ops()) {
                    effectOps.put(op.name(), ed.name());
                }
            }
            if (inner instanceof Decl.HandlerDecl hd) {
                validateHandler14c2(hd);
                handlers.put(hd.name(), hd);
                if (!hd.stateBindings().isEmpty()) {
                    Map<String, String> fields = new LinkedHashMap<>();
                    for (Stmt sb : hd.stateBindings()) {
                        String stateName = stateBindingName(hd.name(), sb);
                        String fieldName = "handler$" + mangle(hd.name()) + "$state$" + mangle(stateName);
                        fields.put(stateName, fieldName);
                        cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
                                fieldName, OBJ_DESC, null, null).visitEnd();
                    }
                    handlerStateFields.put(hd.name(), fields);
                }
            }
            if (inner instanceof Decl.ImplDecl id) {
                for (Decl.ImplBinding b : id.bindings()) {
                    if (!(b.value() instanceof Expr.Lambda lam)) {
                        throw new IrijCompiler.CompileException(
                                "MVP impl: binding for " + b.name() + " must be a lambda");
                    }
                    protoImpls.computeIfAbsent(b.name(), __ -> new HashMap<>())
                            .put(id.forType(), lam);
                    protoArity.putIfAbsent(b.name(), lam.params().size());
                    fnArity.put(b.name(), lam.params().size());
                }
            }
        }

        // Pass 2: emit static methods for fns.
        for (Decl d : decls) {
            Decl.FnDecl fn = asFnDecl(d);
            if (fn != null) emitFn(fn, cw);
        }

        // Pass 2b: emit impl methods + protocol dispatchers.
        for (var entry : protoImpls.entrySet()) {
            String method = entry.getKey();
            for (var impl : entry.getValue().entrySet()) {
                emitImplMethod(method, impl.getKey(), impl.getValue(), cw);
            }
            emitProtoDispatcher(method, entry.getValue().keySet(), protoArity.get(method), cw);
        }

        // Pass 2c: emit handler builder methods (14c.2).
        for (Decl.HandlerDecl h : handlers.values()) {
            emitHandlerBuilder(h, cw);
        }

        // Pass 3: main method with all non-fn top-level decls.
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "main",
                "([Ljava/lang/String;)V", null, null);
        mv.visitCode();
        Locals locals = new Locals();
        locals.reserveArgsArray();
        for (Decl d : decls) {
            if (asFnDecl(d) != null) continue;
            emitTopLevel(d, mv, locals);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Decl.FnDecl asFnDecl(Decl d) {
        if (d instanceof Decl.FnDecl fn) return fn;
        if (d instanceof Decl.PubDecl pd && pd.inner() instanceof Decl.FnDecl fn) return fn;
        return null;
    }

    private static List<Pattern> fnParams(Decl.FnDecl fn) {
        return switch (fn.body()) {
            case Decl.FnBody.LambdaBody lb -> lb.params();
            case Decl.FnBody.ImperativeBody ib -> ib.params();
            case Decl.FnBody.MatchArmsBody mab -> List.of(new Pattern.VarPat("$scrut", null));
            default -> List.of();
        };
    }

    private void emitFn(Decl.FnDecl fn, ClassWriter cw) {
        List<Pattern> params = fnParams(fn);
        StringBuilder desc = new StringBuilder("(");
        for (int i = 0; i < params.size(); i++) desc.append(OBJ_DESC);
        desc.append(")").append(OBJ_DESC);

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
                mangle(fn.name()), desc.toString(), null, null);
        mv.visitCode();

        Locals locals = new Locals();
        for (Pattern p : params) {
            String name = switch (p) {
                case Pattern.VarPat v -> v.name();
                case Pattern.WildcardPat w -> "_";
                default -> throw new IrijCompiler.CompileException(
                        "MVP: fn param must be VarPat/WildcardPat, got " + p.getClass().getSimpleName());
            };
            locals.allocate(name);
        }

        switch (fn.body()) {
            case Decl.FnBody.LambdaBody lb -> {
                emitExpr(lb.body(), mv, locals);
                mv.visitInsn(ARETURN);
            }
            case Decl.FnBody.MatchArmsBody mab -> {
                Expr.MatchExpr me = new Expr.MatchExpr(
                        new Expr.Var("$scrut", null),
                        mab.arms(),
                        null);
                emitMatchExpr(me, mv, locals);
                mv.visitInsn(ARETURN);
            }
            case Decl.FnBody.ImperativeBody ib -> {
                List<Stmt> stmts = ib.stmts();
                if (stmts.isEmpty()) {
                    mv.visitInsn(ACONST_NULL);
                    mv.visitInsn(ARETURN);
                    break;
                }
                for (int i = 0; i < stmts.size() - 1; i++) emitStmt(stmts.get(i), mv, locals);
                Stmt last = stmts.get(stmts.size() - 1);
                if (last instanceof Stmt.ExprStmt es) {
                    emitExpr(es.expr(), mv, locals);
                } else if (last instanceof Stmt.With w) {
                    emitWith(w, mv, locals);
                } else {
                    emitStmt(last, mv, locals);
                    mv.visitInsn(ACONST_NULL);
                }
                mv.visitInsn(ARETURN);
            }
            default -> throw new IrijCompiler.CompileException(
                    "MVP: unsupported fn body: " + fn.body().getClass().getSimpleName());
        }

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void emitBlock(Expr.Block blk, MethodVisitor mv, Locals outer) {
        Locals inner = outer.childScope();
        List<Stmt> stmts = blk.stmts();
        if (stmts.isEmpty()) {
            mv.visitInsn(ACONST_NULL);
            return;
        }
        for (int i = 0; i < stmts.size() - 1; i++) emitStmt(stmts.get(i), mv, inner);
        Stmt last = stmts.get(stmts.size() - 1);
        if (last instanceof Stmt.ExprStmt es) {
            emitExpr(es.expr(), mv, inner);
        } else if (last instanceof Stmt.With w) {
            emitWith(w, mv, inner);
        } else {
            emitStmt(last, mv, inner);
            mv.visitInsn(ACONST_NULL);
        }
    }

    /** Mangle Irij kebab-case names to JVM-safe identifiers. */
    private static String mangle(String name) {
        return name.replace("-", "_").replace("?", "$q").replace("!", "$b");
    }

    // ── Top-level ──────────────────────────────────────────────────────

    private void emitTopLevel(Decl d, MethodVisitor mv, Locals locals) {
        switch (d) {
            case Decl.ExprDecl ed -> emitStmtExpr(ed.expr(), mv, locals);
            case Decl.BindingDecl bd -> emitStmt(bd.stmt(), mv, locals);
            case Decl.IfDecl id -> emitStmt(id.ifStmt(), mv, locals);
            case Decl.SpecDecl __ -> { /* structural only; constructors resolved via TypeRef */ }
            case Decl.ProtoDecl __ -> { /* no runtime rep; methods go through dispatchers */ }
            case Decl.ImplDecl __ -> { /* bindings hoisted to static methods in pass 2b */ }
            case Decl.EffectDecl __ -> { /* ops registered in pass 1 */ }
            case Decl.HandlerDecl hd -> emitHandlerStateInit(hd, mv, locals);
            case Decl.WithDecl wd -> emitStmt(wd.with(), mv, locals);
            case Decl.ScopeDecl sd -> {
                emitScope(sd.scope(), mv, locals);
                mv.visitInsn(POP);
            }
            default -> throw new IrijCompiler.CompileException(
                    "MVP: unsupported top-level decl: " + d.getClass().getSimpleName());
        }
    }

    private void emitStmt(Stmt s, MethodVisitor mv, Locals locals) {
        switch (s) {
            case Stmt.ExprStmt es -> emitStmtExpr(es.expr(), mv, locals);
            case Stmt.Bind b -> emitBind(b, mv, locals);
            case Stmt.IfStmt ifs -> emitIfStmt(ifs, mv, locals);
            case Stmt.MatchStmt ms -> {
                // Match as statement: emit as expression, discard result.
                emitMatchExpr(new Expr.MatchExpr(ms.scrutinee(), ms.arms(), ms.loc()), mv, locals);
                mv.visitInsn(POP);
            }
            case Stmt.With w -> {
                emitWith(w, mv, locals);
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

    private void emitAssign(Stmt.Assign a, MethodVisitor mv, Locals locals) {
        if (!(a.target() instanceof Stmt.BindTarget.Simple s)) {
            throw new IrijCompiler.CompileException(
                    "MVP: assignment must target a simple name");
        }
        Integer liftedIdx = currentLiftedLocals.get(s.name());
        if (liftedIdx != null && currentKSlot >= 0) {
            mv.visitVarInsn(ALOAD, currentKSlot);
            mv.visitFieldInsn(GETFIELD, CONT, "fields", "[Ljava/lang/Object;");
            pushIconst(mv, liftedIdx);
            emitExpr(a.value(), mv, locals);
            mv.visitInsn(AASTORE);
            return;
        }
        emitExpr(a.value(), mv, locals);
        Integer slot = locals.lookup(s.name());
        if (slot != null) {
            mv.visitVarInsn(ASTORE, slot);
            return;
        }
        String field = currentStateFields.get(s.name());
        if (field != null) {
            mv.visitFieldInsn(PUTSTATIC, internalName, field, OBJ_DESC);
            return;
        }
        throw new IrijCompiler.CompileException(
                "MVP: assignment to unknown target: " + s.name());
    }

    private void emitBind(Stmt.Bind b, MethodVisitor mv, Locals locals) {
        switch (b.target()) {
            case Stmt.BindTarget.Simple simple -> {
                Integer liftedIdx = currentLiftedLocals.get(simple.name());
                if (liftedIdx != null && currentKSlot >= 0) {
                    mv.visitVarInsn(ALOAD, currentKSlot);
                    mv.visitFieldInsn(GETFIELD, CONT, "fields", "[Ljava/lang/Object;");
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
                emitPatternTest(dt.pattern(), scrut, mv, locals, failL);
                mv.visitJumpInsn(GOTO, okL);
                mv.visitLabel(failL);
                mv.visitVarInsn(ALOAD, scrut);
                mv.visitMethodInsn(INVOKESTATIC, RT, "noMatch",
                        "(Ljava/lang/Object;)Ljava/lang/IllegalStateException;", false);
                mv.visitInsn(ATHROW);
                mv.visitLabel(okL);
            }
        }
    }

    private void emitIfStmt(Stmt.IfStmt ifs, MethodVisitor mv, Locals locals) {
        emitExpr(ifs.cond(), mv, locals);
        mv.visitMethodInsn(INVOKESTATIC, RT, "truthy", "(Ljava/lang/Object;)Z", false);
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
    private void emitStmtExpr(Expr e, MethodVisitor mv, Locals locals) {
        emitExpr(e, mv, locals);
        mv.visitInsn(POP);
    }

    // ── Expressions (produce one Object on stack) ───────────────────────

    private void emitExpr(Expr e, MethodVisitor mv, Locals locals) {
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
            case Expr.UnitLit __ -> mv.visitFieldInsn(GETSTATIC, VALUES, "UNIT", OBJ_DESC);
            case Expr.KeywordLit k -> {
                mv.visitTypeInsn(NEW, VALUES + "$Keyword");
                mv.visitInsn(DUP);
                mv.visitLdcInsn(k.name());
                mv.visitMethodInsn(INVOKESPECIAL, VALUES + "$Keyword", "<init>",
                        "(Ljava/lang/String;)V", false);
            }
            case Expr.VectorLit vl -> emitListLiteral(vl.elements(), mv, locals, "IrijVector");
            case Expr.TupleLit tl -> emitTupleLiteral(tl.elements(), mv, locals);
            case Expr.SetLit sl -> emitListLiteral(sl.elements(), mv, locals, "IrijSet");
            case Expr.MapLit ml -> emitMapLiteral(ml, mv, locals);
            case Expr.Var v -> emitVarLoad(v.name(), mv, locals);
            case Expr.TypeRef tr -> emitConstructorApp(tr.name(), List.of(), mv, locals);
            case Expr.BinaryOp bop -> emitBinaryOp(bop, mv, locals);
            case Expr.UnaryOp uop -> emitUnaryOp(uop, mv, locals);
            case Expr.IfExpr ie -> emitIfExpr(ie, mv, locals);
            case Expr.App app -> emitApp(app, mv, locals);
            case Expr.MatchExpr me -> emitMatchExpr(me, mv, locals);
            case Expr.Lambda lam -> emitLambda(lam, mv, locals);
            case Expr.Block blk -> emitBlock(blk, mv, locals);
            case Expr.DotAccess da -> emitDotAccess(da, mv, locals);
            case Expr.JavaRef jr -> {
                mv.visitLdcInsn(jr.ref());
                mv.visitMethodInsn(INVOKESTATIC, RT, "javaStaticRef",
                        "(Ljava/lang/String;)Ljava/lang/Object;", false);
            }
            case Expr.Compose c -> emitCompose(c, mv, locals);
            default -> throw new IrijCompiler.CompileException(
                    "MVP: unsupported expression: " + e.getClass().getSimpleName());
        }
    }

    private void emitVarLoad(String name, MethodVisitor mv, Locals locals) {
        switch (name) {
            case "true" -> {
                mv.visitFieldInsn(GETSTATIC, "java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;");
                return;
            }
            case "false" -> {
                mv.visitFieldInsn(GETSTATIC, "java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;");
                return;
            }
            default -> {}
        }
        Integer slot = locals.lookup(name);
        if (slot != null) {
            mv.visitVarInsn(ALOAD, slot);
            return;
        }
        Integer liftedIdx = currentLiftedLocals.get(name);
        if (liftedIdx != null && currentKSlot >= 0) {
            mv.visitVarInsn(ALOAD, currentKSlot);
            mv.visitFieldInsn(GETFIELD, CONT, "fields", "[Ljava/lang/Object;");
            pushIconst(mv, liftedIdx);
            mv.visitInsn(AALOAD);
            return;
        }
        String stateField = currentStateFields.get(name);
        if (stateField != null) {
            mv.visitFieldInsn(GETSTATIC, internalName, stateField, OBJ_DESC);
            return;
        }
        if (handlers.containsKey(name)) {
            mv.visitMethodInsn(INVOKESTATIC, internalName, handlerBuildName(name),
                    "()L" + COMP_HANDLER + ";", false);
            return;
        }
        throw new IrijCompiler.CompileException("Unbound variable: " + name);
    }

    private void emitCompose(Expr.Compose c, MethodVisitor mv, Locals locals) {
        // Assume handler composition — emit both as Object and call RuntimeSupport.compose.
        // (Function composition for non-handler values isn't supported in MVP.)
        emitExpr(c.left(), mv, locals);
        emitExpr(c.right(), mv, locals);
        mv.visitMethodInsn(INVOKESTATIC, RT, "compose",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
    }

    private void emitDotAccess(Expr.DotAccess da, MethodVisitor mv, Locals locals) {
        if (da.target() instanceof Expr.Var v) {
            Map<String, String> fields = handlerStateFields.get(v.name());
            if (fields != null) {
                String field = fields.get(da.field());
                if (field != null) {
                    mv.visitFieldInsn(GETSTATIC, internalName, field, OBJ_DESC);
                    return;
                }
            }
            // `mod.name` where mod is a `use` alias: resolve as unqualified name.
            if (moduleAliases.contains(v.name())) {
                emitVarLoad(da.field(), mv, locals);
                return;
            }
        }
        // Interop fallthrough: evaluate target, delegate to JavaInterop.
        emitExpr(da.target(), mv, locals);
        mv.visitLdcInsn(da.field());
        mv.visitMethodInsn(INVOKESTATIC, RT, "javaInstanceRef",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
    }

    private void emitHandlerStateInit(Decl.HandlerDecl hd, MethodVisitor mv, Locals locals) {
        Map<String, String> fields = handlerStateFields.get(hd.name());
        if (fields == null) return;
        for (Stmt sb : hd.stateBindings()) {
            if (!(sb instanceof Stmt.MutBind mb)) continue;
            String stateName = stateBindingName(hd.name(), sb);
            String fieldName = fields.get(stateName);
            emitExpr(mb.value(), mv, locals);
            mv.visitFieldInsn(PUTSTATIC, internalName, fieldName, OBJ_DESC);
        }
    }

    private void emitBinaryOp(Expr.BinaryOp bop, MethodVisitor mv, Locals locals) {
        emitExpr(bop.left(), mv, locals);
        emitExpr(bop.right(), mv, locals);
        switch (bop.op()) {
            case "+"  -> mv.visitMethodInsn(INVOKESTATIC, RT, "add", BINOP_DESC, false);
            case "-"  -> mv.visitMethodInsn(INVOKESTATIC, RT, "sub", BINOP_DESC, false);
            case "*"  -> mv.visitMethodInsn(INVOKESTATIC, RT, "mul", BINOP_DESC, false);
            case "/"  -> mv.visitMethodInsn(INVOKESTATIC, RT, "div", BINOP_DESC, false);
            case "%"  -> mv.visitMethodInsn(INVOKESTATIC, RT, "mod", BINOP_DESC, false);
            case "<"  -> cmpToBoxedBool(mv, "lt");
            case "<=" -> cmpToBoxedBool(mv, "le");
            case ">"  -> cmpToBoxedBool(mv, "gt");
            case ">=" -> cmpToBoxedBool(mv, "ge");
            case "==" -> cmpToBoxedBool(mv, "eq");
            case "!=" -> cmpToBoxedBool(mv, "neq");
            case "++" -> mv.visitMethodInsn(INVOKESTATIC, RT, "concat", BINOP_DESC, false);
            case "&&" -> {
                mv.visitMethodInsn(INVOKESTATIC, RT, "and", CMPOP_DESC, false);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf",
                        "(Z)Ljava/lang/Boolean;", false);
            }
            case "||" -> {
                mv.visitMethodInsn(INVOKESTATIC, RT, "or", CMPOP_DESC, false);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf",
                        "(Z)Ljava/lang/Boolean;", false);
            }
            default -> throw new IrijCompiler.CompileException("MVP: unsupported binary op: " + bop.op());
        }
    }

    private void emitListLiteral(List<Expr> elems, MethodVisitor mv, Locals locals, String innerName) {
        // NEW dev/irij/interpreter/Values$IrijVector   (or IrijSet)
        // DUP
        // build List.of(e0, e1, ...) or ArrayList for >10; use Arrays.asList for simplicity
        mv.visitTypeInsn(NEW, VALUES + "$" + innerName);
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
        mv.visitMethodInsn(INVOKESPECIAL, VALUES + "$" + innerName, "<init>",
                "(" + param + ")V", false);
    }

    private void emitTupleLiteral(List<Expr> elems, MethodVisitor mv, Locals locals) {
        mv.visitTypeInsn(NEW, VALUES + "$IrijTuple");
        mv.visitInsn(DUP);
        pushIconst(mv, elems.size());
        mv.visitTypeInsn(ANEWARRAY, OBJ);
        for (int i = 0; i < elems.size(); i++) {
            mv.visitInsn(DUP);
            pushIconst(mv, i);
            emitExpr(elems.get(i), mv, locals);
            mv.visitInsn(AASTORE);
        }
        mv.visitMethodInsn(INVOKESPECIAL, VALUES + "$IrijTuple", "<init>",
                "([Ljava/lang/Object;)V", false);
    }

    private void emitMapLiteral(Expr.MapLit ml, MethodVisitor mv, Locals locals) {
        // new java.util.LinkedHashMap
        mv.visitTypeInsn(NEW, "java/util/LinkedHashMap");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/LinkedHashMap", "<init>", "()V", false);
        for (Expr.MapEntry me : ml.entries()) {
            if (!(me instanceof Expr.MapEntry.Field f)) {
                throw new IrijCompiler.CompileException("MVP: map spread not supported");
            }
            mv.visitInsn(DUP);
            mv.visitLdcInsn(f.key());
            emitExpr(f.value(), mv, locals);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
            mv.visitInsn(POP);
        }
        // Wrap in IrijMap
        mv.visitTypeInsn(NEW, VALUES + "$IrijMap");
        mv.visitInsn(DUP_X1);
        mv.visitInsn(SWAP);
        mv.visitMethodInsn(INVOKESPECIAL, VALUES + "$IrijMap", "<init>", "(Ljava/util/Map;)V", false);
    }

    private void pushObjectList(List<Expr> elems, MethodVisitor mv, Locals locals) {
        // Build via Arrays.asList(new Object[]{...}) so we handle any size.
        pushIconst(mv, elems.size());
        mv.visitTypeInsn(ANEWARRAY, OBJ);
        for (int i = 0; i < elems.size(); i++) {
            mv.visitInsn(DUP);
            pushIconst(mv, i);
            emitExpr(elems.get(i), mv, locals);
            mv.visitInsn(AASTORE);
        }
        mv.visitMethodInsn(INVOKESTATIC, "java/util/Arrays", "asList",
                "([Ljava/lang/Object;)Ljava/util/List;", false);
    }

    private void pushIconst(MethodVisitor mv, int v) {
        if (v >= -1 && v <= 5) mv.visitInsn(ICONST_0 + v);
        else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) mv.visitIntInsn(BIPUSH, v);
        else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) mv.visitIntInsn(SIPUSH, v);
        else mv.visitLdcInsn(v);
    }

    /** Returns true if the call was emitted as a built-in. */
    private boolean emitBuiltinApp(String name, List<Expr> args, MethodVisitor mv, Locals locals) {
        switch (name) {
            case "print", "println" -> {
                if (args.size() != 1) return false;
                emitExpr(args.get(0), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, RT, name, "(Ljava/lang/Object;)V", false);
                // print/println return unit — push UNIT for expression-position use
                mv.visitFieldInsn(GETSTATIC, VALUES, "UNIT", OBJ_DESC);
                return true;
            }
            case "to-str" -> {
                if (args.size() != 1) return false;
                emitExpr(args.get(0), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, RT, "toStr",
                        "(Ljava/lang/Object;)Ljava/lang/String;", false);
                return true;
            }
            case "error" -> {
                if (args.size() != 1) return false;
                emitExpr(args.get(0), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, RT, "errorBuiltin",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            case "spawn" -> {
                if (args.size() != 1) return false;
                emitExpr(args.get(0), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, RT, "spawn",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            case "sleep" -> {
                if (args.size() != 1) return false;
                emitExpr(args.get(0), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, RT, "sleep",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            case "await" -> {
                if (args.size() != 1) return false;
                emitExpr(args.get(0), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, RT, "await",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            case "par" -> {
                pushObjectArray(args, mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, RT, "par",
                        "([Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            case "race" -> {
                pushObjectArray(args, mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, RT, "race",
                        "([Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            case "timeout" -> {
                if (args.size() != 2) return false;
                emitExpr(args.get(0), mv, locals);
                emitExpr(args.get(1), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, RT, "timeout",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            case "try" -> {
                if (args.size() != 1) return false;
                emitExpr(args.get(0), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, RT, "tryFn",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            default -> { return false; }
        }
    }

    // ── Scope { fork ... } ─────────────────────────────────────────────

    private static final String SCOPE_HANDLE =
            "dev/irij/compiler/RuntimeSupport$CompiledScopeHandle";

    /** Emit a scope block. Leaves the body result (after join) on the stack. */
    private void emitScope(Stmt.Scope s, MethodVisitor mv, Locals outer) {
        // new CompiledScopeHandle(modifier)
        mv.visitTypeInsn(NEW, SCOPE_HANDLE);
        mv.visitInsn(DUP);
        if (s.modifier() == null) mv.visitInsn(ACONST_NULL);
        else mv.visitLdcInsn(s.modifier());
        mv.visitMethodInsn(INVOKESPECIAL, SCOPE_HANDLE, "<init>",
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
            mv.visitFieldInsn(GETSTATIC, VALUES, "UNIT", OBJ_DESC);
        } else {
            for (int i = 0; i < stmts.size() - 1; i++) emitStmt(stmts.get(i), mv, inner);
            Stmt last = stmts.get(stmts.size() - 1);
            if (last instanceof Stmt.ExprStmt es) {
                emitExpr(es.expr(), mv, inner);
            } else if (last instanceof Stmt.With w) {
                emitWith(w, mv, inner);
            } else {
                emitStmt(last, mv, inner);
                mv.visitFieldInsn(GETSTATIC, VALUES, "UNIT", OBJ_DESC);
            }
        }

        // handle.joinByModifier(bodyResult)
        int resultSlot = inner.allocateAnon();
        mv.visitVarInsn(ASTORE, resultSlot);
        mv.visitVarInsn(ALOAD, handleSlot);
        mv.visitVarInsn(ALOAD, resultSlot);
        mv.visitMethodInsn(INVOKEVIRTUAL, SCOPE_HANDLE, "joinByModifier",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
    }

    private void emitConstructorApp(String tag, List<Expr> args, MethodVisitor mv, Locals locals) {
        List<String> fieldNames = productFields.get(tag);
        if (fieldNames != null && fieldNames.size() == args.size()) {
            // new Values$Tagged(tag, List.of(args...), Map.of(name -> arg, ...))
            mv.visitTypeInsn(NEW, VALUES + "$Tagged");
            mv.visitInsn(DUP);
            mv.visitLdcInsn(tag);
            pushObjectList(args, mv, locals);
            pushNamedFieldMap(fieldNames, args, mv, locals);
            mv.visitMethodInsn(INVOKESPECIAL, VALUES + "$Tagged", "<init>",
                    "(Ljava/lang/String;Ljava/util/List;Ljava/util/Map;)V", false);
            return;
        }
        // Sum variant or unknown: positional only.
        mv.visitTypeInsn(NEW, VALUES + "$Tagged");
        mv.visitInsn(DUP);
        mv.visitLdcInsn(tag);
        pushObjectList(args, mv, locals);
        mv.visitMethodInsn(INVOKESPECIAL, VALUES + "$Tagged", "<init>",
                "(Ljava/lang/String;Ljava/util/List;)V", false);
    }

    private void pushNamedFieldMap(List<String> names, List<Expr> args,
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

    // ── Effects / handlers (14c.2 thread+channel, one-shot resume) ────

    private void validateHandler14c2(Decl.HandlerDecl h) {
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

    private static String stateBindingName(String handlerName, Stmt sb) {
        if (sb instanceof Stmt.MutBind mb
                && mb.target() instanceof Stmt.BindTarget.Simple s) {
            return s.name();
        }
        throw new IrijCompiler.CompileException(
                "handler " + handlerName + ": state binding must be `name :! init`");
    }

    private static final String COMP_HANDLER = "dev/irij/compiler/RuntimeSupport$CompiledHandler";

    private static String handlerBuildName(String h) { return "handler$" + mangle(h) + "$build"; }

    /** Emit a static `handler$name$build() -> CompiledHandler` method. */
    private void emitHandlerBuilder(Decl.HandlerDecl h, ClassWriter cw) {
        // Find effect name: handler's effectName field.
        String effectName = h.effectName();
        MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC,
                handlerBuildName(h.name()),
                "()L" + COMP_HANDLER + ";",
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

        Map<String, String> savedState = currentStateFields;
        Map<String, String> myState = handlerStateFields.get(h.name());
        currentStateFields = myState != null ? myState : Map.of();
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
            boolean isTierC = exprPerformsForeignEffect(c.body(), h.effectName())
                    && tierCClauseCompilable(c);

            if (isTierC) {
                emitTierCClauseLambda(c, clauseParams, mv, locals);
            } else {
                Expr.Lambda clauseLam = new Expr.Lambda(clauseParams, null, c.body(), null);
                emitLambda(clauseLam, mv, locals);
            }

            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
            mv.visitInsn(POP);
        }

        } finally {
            currentStateFields = savedState;
        }

        // new CompiledHandler(name, effectName, map)
        mv.visitTypeInsn(NEW, COMP_HANDLER);
        mv.visitInsn(DUP);
        mv.visitLdcInsn(h.name());
        mv.visitLdcInsn(effectName);
        mv.visitVarInsn(ALOAD, mapSlot);
        mv.visitMethodInsn(INVOKESPECIAL, COMP_HANDLER, "<init>",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;)V", false);
        mv.visitInsn(ARETURN);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void emitPerform(String opName, List<Expr> args, MethodVisitor mv, Locals locals) {
        // `() -> ()` effects are called `op ()` — strip single unit arg.
        if (args.size() == 1 && args.get(0) instanceof Expr.UnitLit) args = List.of();
        String effectName = effectOps.get(opName);
        mv.visitLdcInsn(effectName);
        mv.visitLdcInsn(opName);
        pushObjectArray(args, mv, locals);
        mv.visitMethodInsn(INVOKESTATIC, RT, "perform",
                "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;", false);
    }

    /**
     * Emit `with handler body [on-failure block]` as an expression, leaving the
     * block's result on the stack. 14c.2: body runs on a virtual thread under
     * EffectSystem; handler clauses compiled as IrijFns receiving (args…, resume).
     */
    private void emitWith(Stmt.With w, MethodVisitor mv, Locals outer) {
        // 14c.3: try the state-machine lowering when selected + body shape
        // fits what we support so far. Otherwise fall back to 14c.2 (threaded).
        if (options.handlerStrategy() == CompileOptions.HandlerStrategy.STATE_MACHINE
                && smCanHandle(w.handler())) {
            // Step 3c: A-normalize first so nested op calls become top-level
            // Simple-Binds before classification.
            List<Stmt> body = new ANormalizer().normalize(w.body());
            WithBodyShape shape = classifyWithBody(body);
            if (!(shape instanceof WithBodyShape.Unsupported)) {
                emitWithSM(w, body, shape, mv, outer);
                return;
            }
        }
        emitWithThreaded(w, mv, outer);
    }

    /**
     * Step 7 gate: SM lowering only handles tier (a)+(b) handlers — clauses
     * that don't themselves perform effects beyond their own resume. Any
     * referenced handler with non-empty {@code requiredEffects} (declared via
     * {@code ::: Other}) or whose clause body contains a perform of a
     * different effect falls back to the threaded path (which natively
     * supports the EffectSystem stack walk for clause-internal performs).
     */
    private boolean smCanHandle(Expr handlerExpr) {
        for (String name : collectHandlerNames(handlerExpr)) {
            Decl.HandlerDecl hd = handlers.get(name);
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
    private boolean tierCClauseCompilable(Decl.HandlerClause c) {
        List<Stmt> stmts;
        if (c.body() instanceof Expr.Block blk) {
            stmts = new ArrayList<>(blk.stmts());
        } else {
            stmts = new ArrayList<>(List.of(new Stmt.ExprStmt(c.body(), null)));
        }
        try {
            stmts = new ANormalizer().normalize(stmts);
            WithBodyShape shape = classifyWithBody(stmts);
            if (!(shape instanceof WithBodyShape.Sequence seq)) return false;
            for (Segment s : seq.segments()) {
                if (s.innerWith() != null) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> collectHandlerNames(Expr e) {
        List<String> out = new ArrayList<>();
        collectHandlerNamesInto(e, out);
        return out;
    }

    private void collectHandlerNamesInto(Expr e, List<String> out) {
        if (e instanceof Expr.Var v) {
            out.add(v.name());
        } else if (e instanceof Expr.App app && app.fn() instanceof Expr.Var fv
                && (">>".equals(fv.name()) || "compose".equals(fv.name()))) {
            for (Expr a : app.args()) collectHandlerNamesInto(a, out);
        } else {
            // Unknown shape (lambda result, function call, etc.): mark unknown.
            out.add("__unknown__");
        }
    }

    private boolean exprPerformsForeignEffect(Object node, String selfEffect) {
        if (node == null) return false;
        if (node instanceof Expr.App app && app.fn() instanceof Expr.Var v
                && effectOps.containsKey(v.name())
                && !selfEffect.equals(effectOps.get(v.name()))) {
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

    private boolean stmtPerformsForeignEffect(Stmt s, String selfEffect) {
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

    private void emitWithThreaded(Stmt.With w, MethodVisitor mv, Locals outer) {
        String runEx = "java/lang/RuntimeException";
        int resultSlot = outer.allocateAnon();

        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label runCatch = new Label();
        Label end = new Label();

        if (w.onFailure() != null && !w.onFailure().isEmpty()) {
            mv.visitTryCatchBlock(tryStart, tryEnd, runCatch, runEx);
        }

        mv.visitLabel(tryStart);
        // Emit handler expression as Object (CompiledHandler or CompiledComposedHandler).
        emitExpr(w.handler(), mv, outer);
        // Body: IrijFn of zero params — synthesize Expr.Lambda wrapping a Block.
        Expr bodyExpr = new Expr.Block(w.body(), null);
        Expr.Lambda bodyLam = new Expr.Lambda(List.of(), null, bodyExpr, null);
        emitLambda(bodyLam, mv, outer);
        // RuntimeSupport.runWith(CompiledHandler, IrijFn) → Object
        mv.visitMethodInsn(INVOKESTATIC, RT, "runWith",
                "(Ljava/lang/Object;L" + IRIJ_FN + ";)Ljava/lang/Object;", false);
        mv.visitVarInsn(ASTORE, resultSlot);
        mv.visitLabel(tryEnd);
        mv.visitJumpInsn(GOTO, end);

        if (w.onFailure() != null && !w.onFailure().isEmpty()) {
            mv.visitLabel(runCatch);
            int teSlot = outer.allocateAnon();
            mv.visitVarInsn(ASTORE, teSlot);
            Locals ofLocals = outer.childScope();
            int errorSlot = ofLocals.allocate("error");
            mv.visitVarInsn(ALOAD, teSlot);
            mv.visitMethodInsn(INVOKESTATIC, RT, "errorMessage",
                    "(Ljava/lang/Throwable;)Ljava/lang/String;", false);
            mv.visitVarInsn(ASTORE, errorSlot);
            List<Stmt> of = w.onFailure();
            for (int i = 0; i < of.size() - 1; i++) emitStmt(of.get(i), mv, ofLocals);
            Stmt last = of.get(of.size() - 1);
            if (last instanceof Stmt.ExprStmt es) {
                emitExpr(es.expr(), mv, ofLocals);
            } else {
                emitStmt(last, mv, ofLocals);
                mv.visitInsn(ACONST_NULL);
            }
            mv.visitVarInsn(ASTORE, resultSlot);
        }

        mv.visitLabel(end);
        mv.visitVarInsn(ALOAD, resultSlot);
    }

    // ── 14c.3 state-machine lowering (step 2: pure + single-op bodies) ──
    //
    // Design: docs/phase-14c3-state-machine.md
    //
    // Each `with H body` in SM mode emits a step IrijFn whose signature is
    //   (Object[]) -> Object
    // where args = [IrijContinuation, resumeValue]. The step switches on
    // the continuation's state field and either returns or throws a
    // PerformSignal. The call site allocates a fresh continuation and calls
    // RuntimeSupport.runWithSM(handler, step, nFields).
    //
    // STEP 2 SCOPE: body contains zero or one op call, top-level only
    // (either `op args` as an ExprStmt or `x := op args` as a Bind).
    // Bodies with bindings in pre-op position fall back to threaded lowering
    // (no local-lifting yet). Multi-perform + branching arrive in step 3.

    private static final String CONT = "dev/irij/compiler/RuntimeSupport$IrijContinuation";
    private static final String CONT_DESC = "Ldev/irij/compiler/RuntimeSupport$IrijContinuation;";
    private static final String PERF_SIGNAL = "dev/irij/compiler/RuntimeSupport$PerformSignal";
    private static final String TAIL_RESUME = "dev/irij/compiler/RuntimeSupport$TailResume";

    /** One chunk of a SM body. Three flavours:
     *  - Trailing pure: opName == null && innerWith == null
     *  - Op terminator: opName != null (perform yields to enclosing handler)
     *  - Nested-with terminator: innerWith != null (run a nested `with` whose
     *    own performs may escape and be caught by the OUTER trampoline; the
     *    inner continuation lives in the outer's k.fields[innerSlot] so
     *    state survives across the outer resume cycle).
     */
    private record Segment(
            List<Stmt> pureStmts,
            String opName,
            List<Expr> opArgs,
            String bindName,
            Stmt.With innerWith,
            int innerSlot,
            String innerBind) {

        /** Pure / op-terminated segment (no nested with). */
        public Segment(List<Stmt> pureStmts, String opName,
                       List<Expr> opArgs, String bindName) {
            this(pureStmts, opName, opArgs, bindName, null, -1, null);
        }
    }

    private sealed interface WithBodyShape {
        record Sequence(List<Segment> segments, List<String> liftedLocals)
                implements WithBodyShape {}
        record Pure() implements WithBodyShape {}
        /** Op appears at body[idx] either as an ExprStmt or `bindName := op args`. */
        record SingleOp(int idx, String opName, List<Expr> opArgs, String bindName)
                implements WithBodyShape {}
        /** Full CFG: blocks with branch/jump/perform/return terminators.
         *  Used when the body contains an IfStmt/MatchStmt whose branches
         *  contain op calls (step 3b). */
        record EffIR(List<BB> blocks,
                     List<String> liftedLocals,
                     Map<Integer, String> resumeBindOf,
                     int lastValueBlock) implements WithBodyShape {}
        record Unsupported() implements WithBodyShape {}
    }

    // ── EffIR terminators + blocks (step 3b) ────────────────────────────

    private sealed interface Term {
        /** Return a value (expr may be null → return Unit/null). */
        record Return(Expr expr) implements Term {}
        /** Throw PerformSignal; on resume, continue at block `next`.
         *  If bindName != null, the resume value is stored into
         *  k.fields[lifted[bindName]] on re-entry to `next`. */
        record Perform(String effectName, String opName, List<Expr> args,
                       String bindName, int next) implements Term {}
        /** Pure branch (cond has no op). */
        record Branch(Expr cond, int thenId, int elseId) implements Term {}
        /** Unconditional jump inside the same step invocation. */
        record Jump(int target) implements Term {}
    }

    private record BB(int id, List<Stmt> pure, Term term) {}

    private final class EffIRBuilder {
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
                    if (tl.bindName != null) {
                        liftName(tl.bindName);
                        resumeBindOf.put(next, tl.bindName);
                    }
                    String effectName = effectOps.get(tl.opName);
                    finalize(cur, new Term.Perform(effectName, tl.opName,
                            tl.args, tl.bindName, next));
                    cur = next;
                } else if (s instanceof Stmt.IfStmt ifs
                        && stmtContainsOpRecursive(s)) {
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

    private final class ANormalizer {
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

        /** Normalize an expression in a non-top-level position. Any op call
         *  encountered is lifted to a fresh Simple-Bind in {@code out}. */
        Expr normalizeExpr(Expr e, List<Stmt> out) {
            if (e == null) return null;
            if (!containsOpCallExpr(e)) return e;
            return switch (e) {
                case Expr.App app -> {
                    boolean isOp = app.fn() instanceof Expr.Var v
                            && effectOps.containsKey(v.name());
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
                // IfExpr / MatchExpr / Lambda / Block: can't easily A-normalize
                // inline — leave untouched; EffIRBuilder will reject if ops
                // remain in non-top-level positions.
                default -> e;
            };
        }

        boolean isDirectOpCall(Expr e) {
            return e instanceof Expr.App app
                    && app.fn() instanceof Expr.Var v
                    && effectOps.containsKey(v.name());
        }
    }

    private boolean stmtContainsOpRecursive(Stmt s) {
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

    private boolean bodyHasBranchingOp(List<Stmt> body) {
        for (Stmt s : body) {
            if (s instanceof Stmt.IfStmt && stmtContainsOpRecursive(s)) return true;
        }
        return false;
    }

    private WithBodyShape classifyWithBody(List<Stmt> body) {
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
                segments.add(new Segment(new ArrayList<>(cur), tl.opName, tl.args, tl.bindName));
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

    private record TopLevelBindWith(String bindName, Stmt.With with) {}

    /** Bind whose value is a single nested `with` — e.g. `r := with X body`. */
    private TopLevelBindWith extractTopLevelBindWith(Stmt s) {
        if (s instanceof Stmt.Bind b
                && b.target() instanceof Stmt.BindTarget.Simple sm
                && b.value() instanceof Expr.Block blk
                && blk.stmts().size() == 1
                && blk.stmts().get(0) instanceof Stmt.With w) {
            return new TopLevelBindWith(sm.name(), w);
        }
        return null;
    }

    private record TopLevelOp(String opName, List<Expr> args, String bindName) {}

    private TopLevelOp extractTopLevelOp(Stmt s) {
        if (s instanceof Stmt.ExprStmt es && es.expr() instanceof Expr.App app
                && app.fn() instanceof Expr.Var v && effectOps.containsKey(v.name())) {
            // Confirm args have no nested op.
            for (Expr a : app.args()) if (containsOpCallExpr(a)) return null;
            return new TopLevelOp(v.name(), app.args(), null);
        }
        if (s instanceof Stmt.Bind b
                && b.target() instanceof Stmt.BindTarget.Simple simp
                && b.value() instanceof Expr.App app
                && app.fn() instanceof Expr.Var v && effectOps.containsKey(v.name())) {
            for (Expr a : app.args()) if (containsOpCallExpr(a)) return null;
            return new TopLevelOp(v.name(), app.args(), simp.name());
        }
        return null;
    }

    private boolean containsOpCall(Stmt s) {
        return switch (s) {
            case Stmt.ExprStmt es -> containsOpCallExpr(es.expr());
            case Stmt.Bind b -> containsOpCallExpr(b.value());
            case Stmt.MutBind b -> containsOpCallExpr(b.value());
            case Stmt.Assign a -> containsOpCallExpr(a.value());
            // Step 8: nested `with` would require the outer continuation to
            // resume INSIDE the inner with rather than at its start, plus
            // bridging PerformSignal across SM/threaded boundaries. Both
            // are out of scope for 14c.3 — fall back to threaded for the
            // outer (and inner) so EffectSystem dispatch handles it.
            default -> true; // includes Stmt.With — conservatively unsupported
        };
    }

    private boolean containsOpCallExpr(Expr e) {
        if (e == null) return false;
        return switch (e) {
            case Expr.App app -> {
                if (app.fn() instanceof Expr.Var v && effectOps.containsKey(v.name())) yield true;
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
            default -> false;
        };
    }

    /**
     * Lower a `with` via state-machine: emit a step IrijFn via invokedynamic,
     * allocate a continuation, call RuntimeSupport.runWithSM.
     */
    private void emitWithSM(Stmt.With w, List<Stmt> body, WithBodyShape shape,
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
        emitExpr(w.handler(), mv, outer);

        // Collect free variables in body that resolve to outer locals — these
        // become step captures, same mechanism as emitLambda.
        Set<String> bound = new HashSet<>();
        List<String> captures = new ArrayList<>();
        for (Stmt s : body) collectFreeVarsStmt(s, bound, outer, captures, new HashSet<>());

        // Emit step method + return IrijFn on stack.
        emitSMStep(shape, body, captures, mv, outer);

        // Push nFields: number of lifted locals (0 for Pure/SingleOp).
        int nFields = switch (shape) {
            case WithBodyShape.Sequence seq -> seq.liftedLocals().size();
            case WithBodyShape.EffIR eir -> eir.liftedLocals().size();
            default -> 0;
        };
        pushIconst(mv, nFields);

        // RuntimeSupport.runWithSM(Object, IrijFn, int) -> Object
        mv.visitMethodInsn(INVOKESTATIC, RT, "runWithSM",
                "(Ljava/lang/Object;L" + IRIJ_FN + ";I)Ljava/lang/Object;", false);
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
            mv.visitMethodInsn(INVOKESTATIC, RT, "errorMessage",
                    "(Ljava/lang/Throwable;)Ljava/lang/String;", false);
            mv.visitVarInsn(ASTORE, errorSlot);
            List<Stmt> of = w.onFailure();
            for (int i = 0; i < of.size() - 1; i++) emitStmt(of.get(i), mv, ofLocals);
            Stmt last = of.get(of.size() - 1);
            if (last instanceof Stmt.ExprStmt es) {
                emitExpr(es.expr(), mv, ofLocals);
            } else {
                emitStmt(last, mv, ofLocals);
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
    private void emitSMStep(WithBodyShape shape, List<Stmt> body,
                             List<String> captures, MethodVisitor mv, Locals outerLocals) {
        int id = lambdaCounter++;
        String methodName = "smstep$" + id;
        StringBuilder desc = new StringBuilder("(");
        for (int i = 0; i < captures.size(); i++) desc.append(OBJ_DESC);
        desc.append("[Ljava/lang/Object;)Ljava/lang/Object;");
        MethodVisitor sm = classWriter.visitMethod(
                ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
                methodName, desc.toString(), null, null);
        sm.visitCode();

        Locals inner = new Locals();
        for (String cap : captures) inner.allocate(cap);
        int argsSlot = inner.allocateAnon();
        int kSlot = inner.allocateAnon();    // continuation
        int vSlot = inner.allocateAnon();    // resume value

        // kSlot = (IrijContinuation) args[0]
        sm.visitVarInsn(ALOAD, argsSlot);
        pushIconst(sm, 0);
        sm.visitInsn(AALOAD);
        sm.visitTypeInsn(CHECKCAST, CONT);
        sm.visitVarInsn(ASTORE, kSlot);
        // vSlot = args[1]
        sm.visitVarInsn(ALOAD, argsSlot);
        pushIconst(sm, 1);
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
        for (int i = 0; i < captures.size(); i++) indyDesc.append(OBJ_DESC);
        indyDesc.append(")").append(IRIJ_FN_DESC);
        Handle bsm = new Handle(H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;",
                false);
        Type samType = Type.getMethodType(APPLY_DESC);
        Handle implHandle = new Handle(H_INVOKESTATIC, internalName, methodName, desc.toString(), false);
        mv.visitInvokeDynamicInsn("apply", indyDesc.toString(), bsm,
                samType, implHandle, samType);
    }

    /** Pure body: single-state step. Just emit statements; return last value. */
    private void emitSMStateBody(List<Stmt> body, MethodVisitor sm, Locals inner) {
        emitBlockStmtsReturning(body, sm, inner);
    }

    /** Single-op body: 2-state switch. */
    private void emitSMSingleOp(WithBodyShape.SingleOp so, List<Stmt> body,
                                 MethodVisitor sm, Locals inner, int kSlot, int vSlot) {
        List<Stmt> preStmts = body.subList(0, so.idx());
        List<Stmt> postStmts = body.subList(so.idx() + 1, body.size());

        Label state0 = new Label();
        Label state1 = new Label();
        Label errLabel = new Label();
        Label end = new Label();

        // switch (k.state)
        sm.visitVarInsn(ALOAD, kSlot);
        sm.visitFieldInsn(GETFIELD, CONT, "state", "I");
        sm.visitTableSwitchInsn(0, 1, errLabel, state0, state1);

        // state 0: pre-stmts (as statements, no return); set state=1; throw signal.
        sm.visitLabel(state0);
        emitBlockStmtsAsStatements(preStmts, sm, inner);
        sm.visitVarInsn(ALOAD, kSlot);
        pushIconst(sm, 1);
        sm.visitFieldInsn(PUTFIELD, CONT, "state", "I");
        // PerformSignal.of(effectName, opName, argsArray, k)
        String effectName = effectOps.get(so.opName());
        sm.visitLdcInsn(effectName);
        sm.visitLdcInsn(so.opName());
        // args array — strip single-unit arg ( `op ()` )
        List<Expr> callArgs = so.opArgs();
        if (callArgs.size() == 1 && callArgs.get(0) instanceof Expr.UnitLit) callArgs = List.of();
        pushObjectArray(callArgs, sm, inner);
        sm.visitVarInsn(ALOAD, kSlot);
        sm.visitMethodInsn(INVOKESTATIC, PERF_SIGNAL, "of",
                "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;"
                        + CONT_DESC + ")L" + PERF_SIGNAL + ";",
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
    private void emitSMSequence(WithBodyShape.Sequence seq, MethodVisitor sm,
                                 Locals inner, int kSlot, int vSlot) {
        Map<String, Integer> lifted = new java.util.LinkedHashMap<>();
        for (int i = 0; i < seq.liftedLocals().size(); i++) {
            lifted.put(seq.liftedLocals().get(i), i);
        }
        Map<String, Integer> savedLifted = currentLiftedLocals;
        int savedKSlot = currentKSlot;
        currentLiftedLocals = lifted;
        currentKSlot = kSlot;
        try {
            int nStates = seq.segments().size();
            Label[] stateLabels = new Label[nStates];
            for (int i = 0; i < nStates; i++) stateLabels[i] = new Label();
            Label errLabel = new Label();
            Label end = new Label();

            // switch (k.state)
            sm.visitVarInsn(ALOAD, kSlot);
            sm.visitFieldInsn(GETFIELD, CONT, "state", "I");
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
                            sm.visitFieldInsn(GETFIELD, CONT, "fields", "[Ljava/lang/Object;");
                            pushIconst(sm, idx);
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
                            sm.visitFieldInsn(GETFIELD, CONT, "fields", "[Ljava/lang/Object;");
                            pushIconst(sm, bindIdx);
                            sm.visitVarInsn(ALOAD, vSlot);
                            sm.visitInsn(AASTORE);
                        }
                    }
                    // Bump state and fall through to the next state's body.
                    sm.visitVarInsn(ALOAD, kSlot);
                    pushIconst(sm, i + 1);
                    sm.visitFieldInsn(PUTFIELD, CONT, "state", "I");
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
                    pushIconst(sm, i + 1);
                    sm.visitFieldInsn(PUTFIELD, CONT, "state", "I");
                    String effectName = effectOps.get(seg.opName());
                    sm.visitLdcInsn(effectName);
                    sm.visitLdcInsn(seg.opName());
                    List<Expr> callArgs = seg.opArgs();
                    if (callArgs.size() == 1 && callArgs.get(0) instanceof Expr.UnitLit) {
                        callArgs = List.of();
                    }
                    pushObjectArray(callArgs, sm, inner);
                    sm.visitVarInsn(ALOAD, kSlot);
                    sm.visitMethodInsn(INVOKESTATIC, PERF_SIGNAL, "of",
                            "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;"
                                    + CONT_DESC + ")L" + PERF_SIGNAL + ";",
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
            currentLiftedLocals = savedLifted;
            currentKSlot = savedKSlot;
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
    private void emitInnerWithCall(Segment seg, MethodVisitor sm, Locals inner,
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
            sm.visitTypeInsn(INSTANCEOF, PERF_SIGNAL);
            Label notPerform = new Label();
            sm.visitJumpInsn(IFEQ, notPerform);
            sm.visitVarInsn(ALOAD, teSlot);
            sm.visitInsn(ATHROW);
            sm.visitLabel(notPerform);

            // Re-throw TailResume — it targets a specific dispatch loop.
            sm.visitVarInsn(ALOAD, teSlot);
            sm.visitTypeInsn(INSTANCEOF, TAIL_RESUME);
            Label notTailResume = new Label();
            sm.visitJumpInsn(IFEQ, notTailResume);
            sm.visitVarInsn(ALOAD, teSlot);
            sm.visitInsn(ATHROW);
            sm.visitLabel(notTailResume);

            // Genuine failure → run on-failure block.
            Locals ofLocals = inner.childScope();
            int errorSlot = ofLocals.allocate("error");
            sm.visitVarInsn(ALOAD, teSlot);
            sm.visitMethodInsn(INVOKESTATIC, RT, "errorMessage",
                    "(Ljava/lang/Throwable;)Ljava/lang/String;", false);
            sm.visitVarInsn(ASTORE, errorSlot);
            List<Stmt> of = w.onFailure();
            for (int i = 0; i < of.size() - 1; i++) emitStmt(of.get(i), sm, ofLocals);
            Stmt last = of.get(of.size() - 1);
            if (last instanceof Stmt.ExprStmt es) {
                emitExpr(es.expr(), sm, ofLocals);
            } else {
                emitStmt(last, sm, ofLocals);
                sm.visitInsn(ACONST_NULL);
            }

            sm.visitLabel(end);
        }
    }

    /** Core emit of the inner runWithSM call (no on-failure wrap). */
    private void emitInnerWithCallCore(Stmt.With w, Segment seg, MethodVisitor sm,
                                        Locals inner, int kSlot, int vSlot) {
        List<Stmt> innerBody = new ANormalizer().normalize(w.body());
        WithBodyShape innerShape = classifyWithBody(innerBody);
        int innerNFields = switch (innerShape) {
            case WithBodyShape.Sequence s -> s.liftedLocals().size();
            case WithBodyShape.EffIR e -> e.liftedLocals().size();
            default -> 0;
        };

        // Push: handler, kInner, resumeValue → runWithSM(Object, IrijContinuation, Object)
        emitExpr(w.handler(), sm, inner);

        // RT.getOrAllocInnerCont(kOuter, slot, step, nFields) → kInner
        sm.visitVarInsn(ALOAD, kSlot);
        pushIconst(sm, seg.innerSlot());
        // Free vars of inner body that resolve to outer locals = step captures.
        Set<String> bound = new HashSet<>();
        List<String> captures = new ArrayList<>();
        for (Stmt s : innerBody) {
            collectFreeVarsStmt(s, bound, inner, captures, new HashSet<>());
        }
        emitSMStep(innerShape, innerBody, captures, sm, inner);
        pushIconst(sm, innerNFields);
        sm.visitMethodInsn(INVOKESTATIC, RT, "getOrAllocInnerCont",
                "(" + CONT_DESC + "IL" + IRIJ_FN + ";I)" + CONT_DESC, false);

        sm.visitVarInsn(ALOAD, vSlot);
        sm.visitMethodInsn(INVOKESTATIC, RT, "runWithSM",
                "(Ljava/lang/Object;" + CONT_DESC + "Ljava/lang/Object;)Ljava/lang/Object;",
                false);
    }

    /** Emit an EffIR CFG: one tableswitch entry per block (for resumption),
     *  plus intra-step JVM GOTOs for Jump/Branch. Each block has two labels:
     *  {@code hdrLabels[id]} is the resumption target (does the resume-bind
     *  store if any); {@code bodyLabels[id]} is the intra-step entry used
     *  by Jump/Branch (skips the resume-bind store). */
    private void emitSMEffIR(WithBodyShape.EffIR eir, MethodVisitor sm,
                              Locals inner, int kSlot, int vSlot) {
        Map<String, Integer> lifted = new LinkedHashMap<>();
        for (int i = 0; i < eir.liftedLocals().size(); i++) {
            lifted.put(eir.liftedLocals().get(i), i);
        }
        Map<String, Integer> savedLifted = currentLiftedLocals;
        int savedKSlot = currentKSlot;
        currentLiftedLocals = lifted;
        currentKSlot = kSlot;
        try {
            int n = eir.blocks().size();
            Label[] hdr = new Label[n];
            Label[] body = new Label[n];
            for (int i = 0; i < n; i++) { hdr[i] = new Label(); body[i] = new Label(); }
            Label errLabel = new Label();

            sm.visitVarInsn(ALOAD, kSlot);
            sm.visitFieldInsn(GETFIELD, CONT, "state", "I");
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
                        sm.visitFieldInsn(GETFIELD, CONT, "fields", "[Ljava/lang/Object;");
                        pushIconst(sm, idx);
                        sm.visitVarInsn(ALOAD, vSlot);
                        sm.visitInsn(AASTORE);
                    }
                }
                sm.visitLabel(body[i]);
                // Emit pure statements.
                for (Stmt s : b.pure()) emitStmt(s, sm, inner);
                // Terminator.
                switch (b.term()) {
                    case Term.Return r -> {
                        if (r.expr() == null) {
                            sm.visitInsn(ACONST_NULL);
                        } else {
                            emitExpr(r.expr(), sm, inner);
                        }
                        sm.visitInsn(ARETURN);
                    }
                    case Term.Perform p -> {
                        sm.visitVarInsn(ALOAD, kSlot);
                        pushIconst(sm, p.next());
                        sm.visitFieldInsn(PUTFIELD, CONT, "state", "I");
                        sm.visitLdcInsn(p.effectName());
                        sm.visitLdcInsn(p.opName());
                        List<Expr> callArgs = p.args();
                        if (callArgs.size() == 1
                                && callArgs.get(0) instanceof Expr.UnitLit) {
                            callArgs = List.of();
                        }
                        pushObjectArray(callArgs, sm, inner);
                        sm.visitVarInsn(ALOAD, kSlot);
                        sm.visitMethodInsn(INVOKESTATIC, PERF_SIGNAL, "of",
                                "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;"
                                        + CONT_DESC + ")L" + PERF_SIGNAL + ";",
                                false);
                        sm.visitInsn(ATHROW);
                    }
                    case Term.Branch br -> {
                        emitExpr(br.cond(), sm, inner);
                        sm.visitMethodInsn(INVOKESTATIC, RT, "truthy",
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
            currentLiftedLocals = savedLifted;
            currentKSlot = savedKSlot;
        }
    }

    /** Emit stmts discarding non-last values; last stmt's value returned via ARETURN. */
    private void emitBlockStmtsReturning(List<Stmt> stmts, MethodVisitor sm, Locals inner) {
        if (stmts.isEmpty()) {
            sm.visitInsn(ACONST_NULL);
            sm.visitInsn(ARETURN);
            return;
        }
        for (int i = 0; i < stmts.size() - 1; i++) emitStmt(stmts.get(i), sm, inner);
        Stmt last = stmts.get(stmts.size() - 1);
        if (last instanceof Stmt.ExprStmt es) {
            emitExpr(es.expr(), sm, inner);
        } else {
            emitStmt(last, sm, inner);
            sm.visitInsn(ACONST_NULL);
        }
        sm.visitInsn(ARETURN);
    }

    /** Emit stmts as pure statements (no value left on stack afterwards). */
    private void emitBlockStmtsAsStatements(List<Stmt> stmts, MethodVisitor sm, Locals inner) {
        for (Stmt s : stmts) emitStmt(s, sm, inner);
    }

    // ── Protocols / impls ───────────────────────────────────────────────

    private static String implMethodName(String method, String forType) {
        return "impl$" + mangle(method) + "$" + forType;
    }

    private void emitImplMethod(String method, String forType, Expr.Lambda lam, ClassWriter cw) {
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
        for (int i = 0; i < arity; i++) desc.append(OBJ_DESC);
        desc.append(")").append(OBJ_DESC);
        MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC,
                implMethodName(method, forType), desc.toString(), null, null);
        mv.visitCode();
        Locals locals = new Locals();
        for (String pn : paramNames) locals.allocate(pn);
        emitExpr(lam.body(), mv, locals);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void emitProtoDispatcher(String method, java.util.Set<String> forTypes,
                                      int arity, ClassWriter cw) {
        StringBuilder desc = new StringBuilder("(");
        for (int i = 0; i < arity; i++) desc.append(OBJ_DESC);
        desc.append(")").append(OBJ_DESC);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
                mangle(method), desc.toString(), null, null);
        mv.visitCode();
        // tag := typeTag(arg0)
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, RT, "typeTag",
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
            mv.visitMethodInsn(INVOKESTATIC, internalName,
                    implMethodName(method, ft), desc.toString(), false);
            mv.visitInsn(ARETURN);
            mv.visitLabel(next);
        }
        // no impl → throw
        mv.visitLdcInsn(method);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, RT, "noImpl",
                "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/IllegalStateException;", false);
        mv.visitInsn(ATHROW);
        mv.visitLabel(end);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    // ── Lambda values (first-class functions) ───────────────────────────

    /** Call any runtime callable (IrijFn or interop BuiltinFn): evaluate callee,
     *  pack args into Object[], dispatch via RuntimeSupport.callAny. */
    private void emitIrijFnCall(Expr fnExpr, List<Expr> args, MethodVisitor mv, Locals locals) {
        emitExpr(fnExpr, mv, locals);
        pushObjectArray(args, mv, locals);
        mv.visitMethodInsn(INVOKESTATIC, RT, "callAny",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
    }

    private void pushObjectArray(List<Expr> args, MethodVisitor mv, Locals locals) {
        pushIconst(mv, args.size());
        mv.visitTypeInsn(ANEWARRAY, OBJ);
        for (int i = 0; i < args.size(); i++) {
            mv.visitInsn(DUP);
            pushIconst(mv, i);
            emitExpr(args.get(i), mv, locals);
            mv.visitInsn(AASTORE);
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
    private void emitTierCClauseLambda(Decl.HandlerClause c,
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
        stmts = new ANormalizer().normalize(stmts);
        WithBodyShape shape = classifyWithBody(stmts);
        if (!(shape instanceof WithBodyShape.Sequence seq)) {
            throw new IrijCompiler.CompileException(
                    "tier-c clause: only Sequence shape supported (v1)");
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
        int id = lambdaCounter++;
        String stepName = "clauseStep$tierC$" + id;
        String stepDesc = "([Ljava/lang/Object;)Ljava/lang/Object;";
        MethodVisitor sm = classWriter.visitMethod(
                ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
                stepName, stepDesc, null, null);
        sm.visitCode();
        Locals stepLocals = new Locals();
        int argsSlot = stepLocals.allocateAnon();
        int kSlot = stepLocals.allocateAnon();
        int vSlot = stepLocals.allocateAnon();
        sm.visitVarInsn(ALOAD, argsSlot); pushIconst(sm, 0); sm.visitInsn(AALOAD);
        sm.visitTypeInsn(CHECKCAST, CONT); sm.visitVarInsn(ASTORE, kSlot);
        sm.visitVarInsn(ALOAD, argsSlot); pushIconst(sm, 1); sm.visitInsn(AALOAD);
        sm.visitVarInsn(ASTORE, vSlot);
        emitSMSequence(augShape, sm, stepLocals, kSlot, vSlot);
        sm.visitMaxs(0, 0); sm.visitEnd();

        // 2. Emit wrapper IrijFn method.
        int wrapperId = lambdaCounter++;
        String wrapperName = "clauseWrap$tierC$" + wrapperId;
        String wrapperDesc = "([Ljava/lang/Object;)Ljava/lang/Object;";
        MethodVisitor w = classWriter.visitMethod(
                ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
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
        Type samType = Type.getMethodType(APPLY_DESC);
        Handle stepHandle = new Handle(H_INVOKESTATIC, internalName,
                stepName, stepDesc, false);

        // step IrijFn = LambdaMetafactory.metafactory(...) → IrijFn
        w.visitInvokeDynamicInsn("apply", "()" + IRIJ_FN_DESC, bsm,
                samType, stepHandle, samType);
        // newCont(stepFn, totalNFields) → kClause
        pushIconst(w, totalNFields);
        w.visitMethodInsn(INVOKESTATIC, RT, "newCont",
                "(L" + IRIJ_FN + ";I)" + CONT_DESC, false);
        w.visitVarInsn(ASTORE, wKSlot);

        // For each i in 0..nFieldsForArgs-1: k.fields[i] = args[i]
        for (int i = 0; i < nFieldsForArgs; i++) {
            w.visitVarInsn(ALOAD, wKSlot);
            w.visitFieldInsn(GETFIELD, CONT, "fields", "[Ljava/lang/Object;");
            pushIconst(w, i);
            w.visitVarInsn(ALOAD, wArgsSlot);
            pushIconst(w, i);
            w.visitInsn(AALOAD);
            w.visitInsn(AASTORE);
        }

        // RT.runWithSMNoHs(kClause)
        w.visitVarInsn(ALOAD, wKSlot);
        w.visitMethodInsn(INVOKESTATIC, RT, "runWithSMNoHs",
                "(" + CONT_DESC + ")Ljava/lang/Object;", false);
        w.visitInsn(ARETURN);
        w.visitMaxs(0, 0); w.visitEnd();

        // 3. Push wrapper as IrijFn at the call site.
        Handle wrapperHandle = new Handle(H_INVOKESTATIC, internalName,
                wrapperName, wrapperDesc, false);
        mv.visitInvokeDynamicInsn("apply", "()" + IRIJ_FN_DESC, bsm,
                samType, wrapperHandle, samType);
    }

    private void emitLambda(Expr.Lambda lam, MethodVisitor mv, Locals outerLocals) {
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
        int id = lambdaCounter++;
        String methodName = "lambda$" + id;
        StringBuilder desc = new StringBuilder("(");
        for (int i = 0; i < captures.size(); i++) desc.append(OBJ_DESC);
        desc.append("[Ljava/lang/Object;)Ljava/lang/Object;");
        MethodVisitor lm = classWriter.visitMethod(
                ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
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
            pushIconst(lm, i);
            lm.visitInsn(AALOAD);
            lm.visitVarInsn(ASTORE, pslot);
        }
        // Rest param: build IrijVector from args[paramNames.size()..].
        if (restName != null) {
            int restSlot = inner.allocate(restName);
            lm.visitVarInsn(ALOAD, argsSlot);
            pushIconst(lm, paramNames.size());
            lm.visitMethodInsn(INVOKESTATIC, RT, "restVector",
                    "([Ljava/lang/Object;I)Ljava/lang/Object;", false);
            lm.visitVarInsn(ASTORE, restSlot);
        }
        emitExpr(lam.body(), lm, inner);
        lm.visitInsn(ARETURN);
        lm.visitMaxs(0, 0);
        lm.visitEnd();

        // 4. At the call site: push captures, then invokedynamic → IrijFn.
        for (String cap : captures) {
            mv.visitVarInsn(ALOAD, outerLocals.lookup(cap));
        }
        StringBuilder indyDesc = new StringBuilder("(");
        for (int i = 0; i < captures.size(); i++) indyDesc.append(OBJ_DESC);
        indyDesc.append(")").append(IRIJ_FN_DESC);

        Handle bsm = new Handle(H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;",
                false);
        Type samType = Type.getMethodType(APPLY_DESC);
        Handle implHandle = new Handle(H_INVOKESTATIC, internalName, methodName, desc.toString(), false);
        mv.visitInvokeDynamicInsn("apply", indyDesc.toString(), bsm,
                samType, implHandle, samType);
    }

    /** Walk Expr, collecting names referenced but not bound, that resolve to outer locals. */
    private void collectFreeVars(Expr e, Set<String> bound, Locals outer,
                                  List<String> out, Set<String> seen) {
        if (e == null) return;
        switch (e) {
            case Expr.Var v -> {
                String n = v.name();
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
                    collectPatternBinds(arm.pattern(), bound2);
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
            default -> { /* literals, TypeRef, Keyword — no free vars */ }
        }
    }

    private void collectFreeVarsStmt(Stmt s, Set<String> bound, Locals outer,
                                       List<String> out, Set<String> seen) {
        switch (s) {
            case Stmt.ExprStmt es -> collectFreeVars(es.expr(), bound, outer, out, seen);
            case Stmt.Bind b -> {
                collectFreeVars(b.value(), bound, outer, out, seen);
                if (b.target() instanceof Stmt.BindTarget.Simple si) bound.add(si.name());
                else if (b.target() instanceof Stmt.BindTarget.Destructure dp) collectPatternBinds(dp.pattern(), bound);
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
            default -> { /* others rare inside clause bodies */ }
        }
    }

    private void collectPatternBinds(Pattern p, Set<String> bound) {
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

    private void emitApp(Expr.App app, MethodVisitor mv, Locals locals) {
        if (app.fn() instanceof Expr.TypeRef tr) {
            emitConstructorApp(tr.name(), app.args(), mv, locals);
            return;
        }
        // `mod.fn x` where mod is a `use` alias → call unqualified fn.
        if (app.fn() instanceof Expr.DotAccess da
                && da.target() instanceof Expr.Var modVar
                && moduleAliases.contains(modVar.name())) {
            emitApp(new Expr.App(new Expr.Var(da.field(), null), app.args(), null),
                    mv, locals);
            return;
        }
        String fnName = null;
        if (app.fn() instanceof Expr.Var v) fnName = v.name();
        if (fnName != null) {
            // Built-in intercepts
            if (emitBuiltinApp(fnName, app.args(), mv, locals)) return;
            // Effect op → perform (throws EffectException)
            if (effectOps.containsKey(fnName)) {
                emitPerform(fnName, app.args(), mv, locals);
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
            if (currentLiftedLocals.containsKey(fnName)) {
                emitIrijFnCall(app.fn(), app.args(), mv, locals);
                return;
            }
        } else {
            // Non-Var callee (Lambda expr, App result, etc.): call as IrijFn.
            emitIrijFnCall(app.fn(), app.args(), mv, locals);
            return;
        }
        Integer arity = fnArity.get(fnName);
        if (arity == null) {
            throw new IrijCompiler.CompileException("Unknown function: " + fnName);
        }
        List<Expr> args = app.args();
        // Unit-only arg → zero-arg call.
        if (arity == 0 && args.size() == 1 && args.get(0) instanceof Expr.UnitLit) {
            args = List.of();
        }
        if (args.size() != arity) {
            throw new IrijCompiler.CompileException(
                    "Arity mismatch for " + fnName + ": expected " + arity + ", got " + args.size());
        }
        for (Expr a : args) emitExpr(a, mv, locals);
        StringBuilder desc = new StringBuilder("(");
        for (int i = 0; i < arity; i++) desc.append(OBJ_DESC);
        desc.append(")").append(OBJ_DESC);
        if (options.directLinking()) {
            // Direct-linked deploy build: max JIT inlinability, no hot-redef.
            mv.visitMethodInsn(INVOKESTATIC, internalName, mangle(fnName),
                    desc.toString(), false);
        } else {
            // Dev/REPL build: indy + MutableCallSite so REPL can swap impls
            // without restarting the JVM. Bootstrap registers the site in
            // RuntimeSupport.REDEF_SITES; redefine() updates it later.
            Handle bootstrap = new Handle(
                    H_INVOKESTATIC,
                    RT,
                    "redefBootstrap",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;"
                            + "Ljava/lang/String;"
                            + "Ljava/lang/invoke/MethodType;)"
                            + "Ljava/lang/invoke/CallSite;",
                    false);
            mv.visitInvokeDynamicInsn(mangle(fnName), desc.toString(), bootstrap);
        }
    }

    // ── Match / pattern matching ────────────────────────────────────────

    private void emitMatchExpr(Expr.MatchExpr me, MethodVisitor mv, Locals locals) {
        // Evaluate scrutinee once, store in a fresh slot.
        emitExpr(me.scrutinee(), mv, locals);
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
                emitExpr(arm.guard(), mv, armLocals);
                mv.visitMethodInsn(INVOKESTATIC, RT, "truthy", "(Ljava/lang/Object;)Z", false);
                mv.visitJumpInsn(IFEQ, nextArm);
            }
            emitExpr(arm.body(), mv, armLocals);
            mv.visitJumpInsn(GOTO, endL);
            if (nextArm != noMatch) mv.visitLabel(nextArm);
        }

        mv.visitLabel(noMatch);
        mv.visitVarInsn(ALOAD, scrut);
        mv.visitMethodInsn(INVOKESTATIC, RT, "noMatch",
                "(Ljava/lang/Object;)Ljava/lang/IllegalStateException;", false);
        mv.visitInsn(ATHROW);

        mv.visitLabel(endL);
    }

    /**
     * Emit code that tests whether `scrutSlot` matches `pattern`, binding any
     * variables into `locals`. On failure, jump to `failL`. On success, falls
     * through with no stack effect.
     */
    private void emitPatternTest(Pattern pattern, int scrutSlot, MethodVisitor mv,
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
                mv.visitMethodInsn(INVOKESTATIC, RT, "isUnit", "(Ljava/lang/Object;)Z", false);
                mv.visitJumpInsn(IFEQ, failL);
            }
            case Pattern.LitPat lp -> {
                mv.visitVarInsn(ALOAD, scrutSlot);
                emitExpr(lp.literal(), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, RT, "eq", CMPOP_DESC, false);
                mv.visitJumpInsn(IFEQ, failL);
            }
            case Pattern.KeywordPat kp -> {
                mv.visitVarInsn(ALOAD, scrutSlot);
                mv.visitLdcInsn(kp.name());
                mv.visitMethodInsn(INVOKESTATIC, RT, "isKeyword",
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

    private void emitConstructorPatternTest(Pattern.ConstructorPat cp, int scrutSlot,
                                             MethodVisitor mv, Locals locals, Label failL) {
        mv.visitVarInsn(ALOAD, scrutSlot);
        mv.visitLdcInsn(cp.name());
        mv.visitMethodInsn(INVOKESTATIC, RT, "isTag",
                "(Ljava/lang/Object;Ljava/lang/String;)Z", false);
        mv.visitJumpInsn(IFEQ, failL);

        // Arity check
        mv.visitVarInsn(ALOAD, scrutSlot);
        mv.visitMethodInsn(INVOKESTATIC, RT, "taggedArity", "(Ljava/lang/Object;)I", false);
        pushIconst(mv, cp.args().size());
        mv.visitJumpInsn(IF_ICMPNE, failL);

        for (int i = 0; i < cp.args().size(); i++) {
            int fieldSlot = locals.allocateAnon();
            mv.visitVarInsn(ALOAD, scrutSlot);
            pushIconst(mv, i);
            mv.visitMethodInsn(INVOKESTATIC, RT, "taggedField",
                    "(Ljava/lang/Object;I)Ljava/lang/Object;", false);
            mv.visitVarInsn(ASTORE, fieldSlot);
            emitPatternTest(cp.args().get(i), fieldSlot, mv, locals, failL);
        }
    }

    private void emitVectorPatternTest(Pattern.VectorPat vp, int scrutSlot,
                                        MethodVisitor mv, Locals locals, Label failL) {
        mv.visitVarInsn(ALOAD, scrutSlot);
        mv.visitMethodInsn(INVOKESTATIC, RT, "isVector", "(Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(IFEQ, failL);

        int fixed = vp.elements().size();
        Pattern.SpreadPat spread = vp.spread();

        // Size check
        mv.visitVarInsn(ALOAD, scrutSlot);
        mv.visitMethodInsn(INVOKESTATIC, RT, "vecSize", "(Ljava/lang/Object;)I", false);
        int sizeSlot = locals.allocateAnon();
        mv.visitInsn(DUP);
        mv.visitVarInsn(ISTORE, sizeSlot);
        pushIconst(mv, fixed);
        if (spread == null) {
            mv.visitJumpInsn(IF_ICMPNE, failL);
        } else {
            mv.visitJumpInsn(IF_ICMPLT, failL);
        }

        for (int i = 0; i < fixed; i++) {
            int elSlot = locals.allocateAnon();
            mv.visitVarInsn(ALOAD, scrutSlot);
            pushIconst(mv, i);
            mv.visitMethodInsn(INVOKESTATIC, RT, "vecGet",
                    "(Ljava/lang/Object;I)Ljava/lang/Object;", false);
            mv.visitVarInsn(ASTORE, elSlot);
            emitPatternTest(vp.elements().get(i), elSlot, mv, locals, failL);
        }

        if (spread != null && !spread.name().equals("_")) {
            int restSlot = locals.allocate(spread.name());
            mv.visitVarInsn(ALOAD, scrutSlot);
            pushIconst(mv, fixed);
            mv.visitVarInsn(ILOAD, sizeSlot);
            mv.visitMethodInsn(INVOKESTATIC, RT, "vecSlice",
                    "(Ljava/lang/Object;II)Ljava/lang/Object;", false);
            mv.visitVarInsn(ASTORE, restSlot);
        }
    }

    private void emitTuplePatternTest(Pattern.TuplePat tp, int scrutSlot,
                                       MethodVisitor mv, Locals locals, Label failL) {
        mv.visitVarInsn(ALOAD, scrutSlot);
        mv.visitMethodInsn(INVOKESTATIC, RT, "isTuple", "(Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(IFEQ, failL);

        mv.visitVarInsn(ALOAD, scrutSlot);
        mv.visitMethodInsn(INVOKESTATIC, RT, "tupleSize", "(Ljava/lang/Object;)I", false);
        pushIconst(mv, tp.elements().size());
        mv.visitJumpInsn(IF_ICMPNE, failL);

        for (int i = 0; i < tp.elements().size(); i++) {
            int elSlot = locals.allocateAnon();
            mv.visitVarInsn(ALOAD, scrutSlot);
            pushIconst(mv, i);
            mv.visitMethodInsn(INVOKESTATIC, RT, "tupleGet",
                    "(Ljava/lang/Object;I)Ljava/lang/Object;", false);
            mv.visitVarInsn(ASTORE, elSlot);
            emitPatternTest(tp.elements().get(i), elSlot, mv, locals, failL);
        }
    }

    private void emitDestructurePatternTest(Pattern.DestructurePat dp, int scrutSlot,
                                             MethodVisitor mv, Locals locals, Label failL) {
        mv.visitVarInsn(ALOAD, scrutSlot);
        mv.visitMethodInsn(INVOKESTATIC, RT, "isRecord", "(Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(IFEQ, failL);

        for (Pattern.DestructureField f : dp.fields()) {
            mv.visitVarInsn(ALOAD, scrutSlot);
            mv.visitLdcInsn(f.key());
            mv.visitMethodInsn(INVOKESTATIC, RT, "recordHas",
                    "(Ljava/lang/Object;Ljava/lang/String;)Z", false);
            mv.visitJumpInsn(IFEQ, failL);

            int fieldSlot = locals.allocateAnon();
            mv.visitVarInsn(ALOAD, scrutSlot);
            mv.visitLdcInsn(f.key());
            mv.visitMethodInsn(INVOKESTATIC, RT, "recordGet",
                    "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
            mv.visitVarInsn(ASTORE, fieldSlot);
            emitPatternTest(f.value(), fieldSlot, mv, locals, failL);
        }
    }

    private void emitIfExpr(Expr.IfExpr ie, MethodVisitor mv, Locals locals) {
        emitExpr(ie.cond(), mv, locals);
        mv.visitMethodInsn(INVOKESTATIC, RT, "truthy", "(Ljava/lang/Object;)Z", false);
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

    private void emitUnaryOp(Expr.UnaryOp uop, MethodVisitor mv, Locals locals) {
        switch (uop.op()) {
            case "-" -> {
                pushLong(mv, 0L);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf",
                        "(J)Ljava/lang/Long;", false);
                emitExpr(uop.operand(), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, RT, "sub", BINOP_DESC, false);
            }
            case "!" -> {
                emitExpr(uop.operand(), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, RT, "truthy",
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

    private void cmpToBoxedBool(MethodVisitor mv, String fn) {
        mv.visitMethodInsn(INVOKESTATIC, RT, fn, CMPOP_DESC, false);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf",
                "(Z)Ljava/lang/Boolean;", false);
    }

    private void pushLong(MethodVisitor mv, long v) {
        if (v == 0L) mv.visitInsn(LCONST_0);
        else if (v == 1L) mv.visitInsn(LCONST_1);
        else mv.visitLdcInsn(v);
    }

    // ── Local slots ────────────────────────────────────────────────────

    static final class Locals {
        private final Map<String, Integer> slots;
        private final int[] counter;
        private final Locals parent;

        Locals() {
            this.slots = new HashMap<>();
            this.counter = new int[]{0};
            this.parent = null;
        }

        private Locals(Locals parent) {
            this.slots = new HashMap<>();
            this.counter = parent.counter;
            this.parent = parent;
        }

        void reserveArgsArray() { counter[0] = 1; }

        int allocate(String name) {
            int s = counter[0]++;
            slots.put(name, s);
            return s;
        }

        int allocateAnon() { return counter[0]++; }

        Locals childScope() { return new Locals(this); }

        Integer lookup(String name) {
            Integer s = slots.get(name);
            if (s != null) return s;
            return parent == null ? null : parent.lookup(name);
        }
    }
}
