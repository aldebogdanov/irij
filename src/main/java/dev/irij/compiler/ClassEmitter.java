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
    private ClassWriter classWriter;
    private int lambdaCounter = 0;

    private final Set<String> moduleAliases;

    ClassEmitter(String className) {
        this(className, Set.of());
    }

    ClassEmitter(String className, Set<String> moduleAliases) {
        this.internalName = className.replace('.', '/');
        this.moduleAliases = moduleAliases;
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
            default -> throw new IrijCompiler.CompileException(
                    "MVP: unsupported statement: " + s.getClass().getSimpleName());
        }
    }

    private void emitAssign(Stmt.Assign a, MethodVisitor mv, Locals locals) {
        if (!(a.target() instanceof Stmt.BindTarget.Simple s)) {
            throw new IrijCompiler.CompileException(
                    "MVP: assignment must target a simple name");
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
            default -> { return false; }
        }
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

            Expr.Lambda clauseLam = new Expr.Lambda(clauseParams, null, c.body(), null);
            emitLambda(clauseLam, mv, locals);

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
        mv.visitMethodInsn(INVOKESTATIC, internalName, mangle(fnName), desc.toString(), false);
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
