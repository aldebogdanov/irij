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
    /** Each user fn's declared effect row (null = unannotated). Used
     *  to emit caller-side subsumption checks at INVOKESTATIC sites. */
    private final Map<String, List<String>> fnEffectRow = new HashMap<>();
    private final Map<String, List<String>> productFields = new HashMap<>();
    /** Top-level `:=` bindings get hoisted to static fields on the
     *  emitted class so user-fn methods can read them (interpreter
     *  semantics: globalEnv lookup). Maps Irij name → JVM field name.
     *  Populated lazily on first emitTopLevel BindingDecl write. */
    private final Map<String, String> topLevelFields = new HashMap<>();
    /** Sum-spec variants, kept in declaration order for deterministic
     *  emission. Each entry maps {@code variantName → arity}. */
    private final Map<String, LinkedHashMap<String, Integer>> sumVariants = new LinkedHashMap<>();
    /** Reverse lookup: variant/product tag → enclosing spec name. Used
     *  by {@link #emitConstructorApp} to certify Tagged values with
     *  their specName so {@link SpecValidator}'s O(1) fast-path
     *  triggers (matches Interpreter behaviour). */
    private final Map<String, String> tagToSpec = new HashMap<>();
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
    private boolean currentFnPushesEffects = false;
    /** Local-var name → handler expression it was bound to. Populated
     *  by {@link #scanLocalHandlerBindings} at fn-emit time so
     *  {@link #collectHandlerNamesInto} can resolve a {@code with
     *  combined} where {@code combined := h1 >> h2} was bound earlier
     *  in the same fn. Without this, the SM lowering's handler-shape
     *  analysis gives up on local-var aliasing of composed handlers. */
    private java.util.Map<String, Expr> currentLocalHandlerBindings = java.util.Map.of();
    /** Names that have a hard-wired bytecode constant (Math.PI, Math.E,
     *  Function.identity, etc.). When a top-level binding shadows one
     *  of these, the binding's INITIALIZER must read the constant
     *  rather than the (uninitialized) static field. Lookups outside
     *  initialization fall through to topLevelFields. */
    private static final java.util.Set<String> BUILTIN_CONST_NAMES = java.util.Set.of(
            "pi", "e", "identity", "const",
            "length", "head", "tail", "empty?", "to-str", "not", "type-of",
            "abs", "sqrt", "floor", "ceil", "round", "reverse", "sort",
            "println", "print");
    /** Set immediately before {@link #emitLambda} for a handler clause:
     *  the clause body should run with these effects pushed on
     *  RT.EFFECT_ROW so its perform/builtin calls succeed (mirrors the
     *  interpreter's {@code AVAILABLE_EFFECTS.push(requiredEffects)}). */
    private java.util.List<String> pendingClauseEffects = null;
    private ClassWriter classWriter;
    private int lambdaCounter = 0;

    // Self-tail-call optimization scratch. While emitting a top-level fn
    // body, these point at the in-flight method so a self-recursive call
    // in tail position can be lowered to a GOTO back to the method entry
    // (re-binding param slots in place) instead of an INVOKESTATIC.
    private String currentFnName = null;
    private int currentFnArity = 0;
    private Label currentFnEntry = null;
    /** Output spec for the fn currently being emitted. When non-null,
     *  every tail-return site validates against this before ARETURN.
     *  Pushed/popped around {@link #emitFn} so lambdas (which build
     *  their own methods) don't inherit the outer fn's output spec. */
    private String currentOutputSpec = null;

    /** Post-condition slots (each holds a compiled post-lambda
     *  IrijFn) for the surrounding fn. Each {@link #emitTailReturn}
     *  applies them to the about-to-return value before output-spec
     *  validation. Empty list when the fn has no posts.
     *  Outer-fn slots are saved/restored around {@link #emitFn}. */
    private List<Integer> currentPostSlots = List.of();
    /** Temporary slot used to stash the result while running post
     *  checks. -1 when no posts. */
    private int currentPostTempSlot = -1;
    /** Fail-blame text for each post slot (so out-contracts and
     *  post-conditions distinguish in error output). Aligned with
     *  {@link #currentPostSlots}. */
    private List<String> currentPostBlame = List.of();

    // For each user fn `f` referenced as a value (not at an App call
    // site), we synthesise a single IrijFn-shape wrapper method
    // `f$irijfn([Ljava/lang/Object;)Ljava/lang/Object;` that unpacks args
    // and forwards to the real `f`. Tracked here so each fn gets at
    // most one wrapper per class.
    private final Set<String> emittedFnWrappers = new HashSet<>();

    private final Set<String> moduleAliases;
    private final CompileOptions options;

    ClassEmitter(String className) {
        this(className, Set.of(), CompileOptions.defaults(), null);
    }

    ClassEmitter(String className, Set<String> moduleAliases) {
        this(className, moduleAliases, CompileOptions.defaults(), null);
    }

    ClassEmitter(String className, Set<String> moduleAliases, CompileOptions options) {
        this(className, moduleAliases, options, null);
    }

    ClassEmitter(String className, Set<String> moduleAliases,
                  CompileOptions options, String sourceFile) {
        this.internalName = className.replace('.', '/');
        this.moduleAliases = moduleAliases;
        this.options = options;
        // Default to a synthesized name so JVM stack traces show
        // "Program.irj" instead of "Unknown Source" when the build
        // path didn't pass a real filename through.
        this.sourceFile = sourceFile != null ? sourceFile
                : (className.substring(className.lastIndexOf('.') + 1) + ".irj");
    }

    /** Irij source filename for the JVM SourceFile attribute. Set
     *  once at construction; appears in every stack frame from the
     *  emitted class as {@code at irij.Program.main(server.irj:42)}. */
    private final String sourceFile;

    /** Emit a JVM LineNumber attribute mapping the next instruction
     *  to {@code loc}'s Irij source line. Skips zero/negative lines
     *  (synthetic / unknown). One attribute per Expr/Stmt entry is
     *  acceptable; ASM compacts duplicates per LineNumberTable. */
    private void emitLine(org.objectweb.asm.MethodVisitor mv, Node.SourceLoc loc) {
        if (loc == null) return;
        int line = loc.line();
        if (line <= 0) return;
        org.objectweb.asm.Label l = new org.objectweb.asm.Label();
        mv.visitLabel(l);
        mv.visitLineNumber(line, l);
    }

    /** Best-effort SourceLoc lookup on any AST node. Most Expr / Stmt
     *  records expose a {@code loc()} accessor; the few that don't
     *  return null and skip the line attribute. */
    private static Node.SourceLoc locOf(Object node) {
        return switch (node) {
            case Expr.IntLit n -> n.loc();
            case Expr.FloatLit n -> n.loc();
            case Expr.BoolLit n -> n.loc();
            case Expr.StrLit n -> n.loc();
            case Expr.UnitLit n -> n.loc();
            case Expr.KeywordLit n -> n.loc();
            case Expr.HexLit n -> n.loc();
            case Expr.RationalLit n -> n.loc();
            case Expr.Var n -> n.loc();
            case Expr.TypeRef n -> n.loc();
            case Expr.RoleRef n -> n.loc();
            case Expr.JavaRef n -> n.loc();
            case Expr.BinaryOp n -> n.loc();
            case Expr.UnaryOp n -> n.loc();
            case Expr.App n -> n.loc();
            case Expr.Lambda n -> n.loc();
            case Expr.IfExpr n -> n.loc();
            case Expr.MatchExpr n -> n.loc();
            case Expr.Block n -> n.loc();
            case Expr.DotAccess n -> n.loc();
            case Expr.VectorLit n -> n.loc();
            case Expr.SetLit n -> n.loc();
            case Expr.TupleLit n -> n.loc();
            case Expr.MapLit n -> n.loc();
            case Expr.Pipe n -> n.loc();
            case Expr.Compose n -> n.loc();
            case Expr.SeqOp n -> n.loc();
            case Expr.OpSection n -> n.loc();
            case Expr.Range n -> n.loc();
            case Stmt.ExprStmt n -> n.loc();
            case Stmt.Bind n -> n.loc();
            case Stmt.MutBind n -> n.loc();
            case Stmt.Assign n -> n.loc();
            case Stmt.IfStmt n -> n.loc();
            case Stmt.MatchStmt n -> n.loc();
            case Stmt.With n -> n.loc();
            case Stmt.Scope n -> n.loc();
            default -> null;
        };
    }

    byte[] emit(List<Decl> decls) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        this.classWriter = cw;
        cw.visit(V21, ACC_PUBLIC | ACC_FINAL, internalName, null, OBJ, null);
        // Write SourceFile attribute: JVM stack traces use it to
        // render frames as "irij.Program.main(server.irj:42)" rather
        // than "Unknown Source". Per-expression LineNumber attributes
        // (emitted in emitExpr/emitStmt) supply the line numbers.
        cw.visitSource(sourceFile, null);

        // Pass 1: register fn signatures, product-spec field names,
        // proto impls. Also declare static fields for top-level `:=`
        // binds so user-fn methods (emitted in pass 2) can resolve
        // references to them via GETSTATIC.
        for (Decl d : decls) {
            Decl.FnDecl fn = asFnDecl(d);
            if (fn != null) {
                fnArity.put(fn.name(), fnParams(fn).size());
                if (fn.effectRow() != null) {
                    fnEffectRow.put(fn.name(), fn.effectRow());
                }
            }
            if (d instanceof Decl.BindingDecl bd
                    && bd.stmt() instanceof Stmt.Bind b
                    && b.target() instanceof Stmt.BindTarget.Simple sm) {
                ensureTopLevelField(sm.name());
            }
            if (d instanceof Decl.BindingDecl bd2
                    && bd2.stmt() instanceof Stmt.MutBind mb
                    && mb.target() instanceof Stmt.BindTarget.Simple sm2) {
                ensureTopLevelField(sm2.name());
            }
            Object inner = d instanceof Decl.PubDecl pd ? pd.inner() : d;
            if (inner instanceof Decl.SpecDecl sd) {
                switch (sd.body()) {
                    case Decl.SpecBody.ProductSpec ps -> {
                        List<String> names = new ArrayList<>();
                        for (Decl.SpecField f : ps.fields()) names.add(f.name());
                        productFields.put(sd.name(), names);
                        tagToSpec.put(sd.name(), sd.name());
                    }
                    case Decl.SpecBody.SumSpec ss -> {
                        LinkedHashMap<String, Integer> vmap = new LinkedHashMap<>();
                        for (Decl.Variant v : ss.variants()) {
                            vmap.put(v.name(), v.arity());
                            tagToSpec.put(v.name(), sd.name());
                        }
                        sumVariants.put(sd.name(), vmap);
                    }
                }
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
                    Expr.Lambda lam = liftImplBindingToLambda(b);
                    protoImpls.computeIfAbsent(b.name(), __ -> new HashMap<>())
                            .put(id.forType(), lam);
                    protoArity.putIfAbsent(b.name(), lam.params().size());
                    fnArity.put(b.name(), lam.params().size());
                }
            }
        }

        // Pass 2: emit static methods for fns. Dedupe by name — when
        // a module is :opened and the opener re-declares a name with
        // an identical body (e.g. std.collection re-exports `sum`
        // from std.list), the second pub fn would emit a duplicate
        // JVM method. Last definition wins source-order (overrides
        // earlier import); we honor that by emitting the last one.
        java.util.Map<String, Decl.FnDecl> uniqueFns = new java.util.LinkedHashMap<>();
        for (Decl d : decls) {
            Decl.FnDecl fn = asFnDecl(d);
            if (fn != null) uniqueFns.put(fn.name(), fn); // overwrites: last wins
        }
        for (Decl.FnDecl fn : uniqueFns.values()) emitFn(fn, cw);

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

        // Pass 4: <clinit> for user-declared product/sum spec
        // registration (SpecValidator registry). Empty bodies are
        // skipped to avoid an unused method.
        // Emit <clinit> if we have anything to do at class load:
        // spec registry entries, or namespace-mode fn registrations
        // for cross-eval nREPL.
        if (!productFields.isEmpty() || !sumVariants.isEmpty()
                || (options.namespaceMode() && !fnArity.isEmpty())) {
            emitClinit(cw);
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Emit a {@code <clinit>} that registers every product / sum
     *  spec with {@link SpecValidator}. Runs once per class load.
     *
     *  In namespace mode (nREPL eval-bytecode), also registers each
     *  top-level fn as an IrijFn in the session's namespace map via
     *  {@code RT.nsPut}. Subsequent evals' compilations call
     *  {@code RT.nsGet(name)} to retrieve the fn — cross-eval fn
     *  definition works the same way cross-eval `:=` binds do. */
    private void emitClinit(ClassWriter cw) {
        MethodVisitor cl = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        cl.visitCode();
        if (options.namespaceMode()) {
            for (var e : fnArity.entrySet()) {
                String fnName = e.getKey();
                int arity = e.getValue();
                ensureUserFnWrapper(fnName, arity);
                // RT.nsPut(name, IrijFn) — IrijFn built via LMF
                // targeting the wrapper, mirroring the fn-as-value
                // path in emitVarLoad.
                cl.visitLdcInsn(fnName);
                Handle bsm = new Handle(H_INVOKESTATIC,
                        "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                                + "Ljava/lang/invoke/CallSite;",
                        false);
                Type samType = Type.getMethodType(APPLY_DESC);
                Handle implHandle = new Handle(H_INVOKESTATIC, internalName,
                        userFnWrapperName(fnName), APPLY_DESC, false);
                cl.visitInvokeDynamicInsn("apply", "()" + IRIJ_FN_DESC, bsm,
                        samType, implHandle, samType);
                cl.visitMethodInsn(INVOKESTATIC, RT, "nsPut",
                        "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false);
                cl.visitInsn(POP);
            }
        }
        for (var e : productFields.entrySet()) {
            cl.visitLdcInsn(e.getKey());
            // String[] fields
            pushIconst(cl, e.getValue().size());
            cl.visitTypeInsn(ANEWARRAY, "java/lang/String");
            int i = 0;
            for (String f : e.getValue()) {
                cl.visitInsn(DUP);
                pushIconst(cl, i++);
                cl.visitLdcInsn(f);
                cl.visitInsn(AASTORE);
            }
            cl.visitMethodInsn(INVOKESTATIC, SPEC_VALIDATOR, "registerProduct",
                    "(Ljava/lang/String;[Ljava/lang/String;)V", false);
        }
        for (var e : sumVariants.entrySet()) {
            cl.visitLdcInsn(e.getKey());
            // Object[] {name, arity, name, arity, ...}
            pushIconst(cl, e.getValue().size() * 2);
            cl.visitTypeInsn(ANEWARRAY, OBJ);
            int i = 0;
            for (var v : e.getValue().entrySet()) {
                cl.visitInsn(DUP);
                pushIconst(cl, i++);
                cl.visitLdcInsn(v.getKey());
                cl.visitInsn(AASTORE);
                cl.visitInsn(DUP);
                pushIconst(cl, i++);
                // Box arity as java.lang.Integer
                cl.visitLdcInsn(v.getValue());
                cl.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf",
                        "(I)Ljava/lang/Integer;", false);
                cl.visitInsn(AASTORE);
            }
            cl.visitMethodInsn(INVOKESTATIC, SPEC_VALIDATOR, "registerSum",
                    "(Ljava/lang/String;[Ljava/lang/Object;)V", false);
        }
        cl.visitInsn(RETURN);
        cl.visitMaxs(0, 0);
        cl.visitEnd();
    }

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
    private void emitInputSpecChecks(Decl.FnDecl fn, MethodVisitor mv,
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
            pushIconst(mv, i);
            mv.visitMethodInsn(INVOKESTATIC, SPEC_VALIDATOR, "validateEncoded",
                    "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/Object;",
                    false);
            mv.visitVarInsn(ASTORE, i);
        }
    }

    private static final String SPEC_VALIDATOR = "dev/irij/compiler/SpecValidator";

    /** Return the encoded output spec for {@code fn}, or null if it
     *  has no validatable output (no spec annotation, wildcard, or
     *  lowercase type variable). The output spec is the last entry
     *  in {@code specAnnotations()}. */
    private static String outputSpecEncoded(Decl.FnDecl fn) {
        List<dev.irij.ast.SpecExpr> specs = fn.specAnnotations();
        if (specs == null || specs.isEmpty()) return null;
        dev.irij.ast.SpecExpr out = specs.get(specs.size() - 1);
        if (skipSpec(out)) return null;
        return SpecValidator.encode(out);
    }

    /** Skip wildcards, lowercase type-vars, and explicit Var nodes. */
    private static boolean skipSpec(dev.irij.ast.SpecExpr spec) {
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
    private void emitTailReturn(MethodVisitor mv) {
        emitPostChecks(mv);
        if (currentOutputSpec != null) {
            mv.visitLdcInsn(currentOutputSpec);
            mv.visitLdcInsn(currentFnName);
            mv.visitInsn(ICONST_M1);
            mv.visitMethodInsn(INVOKESTATIC, SPEC_VALIDATOR, "validateEncoded",
                    "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/Object;",
                    false);
        }
        // Pop the effect-row frame this fn pushed on entry. The result
        // is already on the stack; exitFn returns void, so the stack
        // shape stays { result } for the subsequent ARETURN.
        if (currentFnPushesEffects) {
            mv.visitMethodInsn(INVOKESTATIC, RT, "exitFn", "()V", false);
        }
        mv.visitInsn(ARETURN);
    }

    /** Compile each post / out lambda once and store the resulting
     *  IrijFn in a fresh local slot. Also records a blame message per
     *  slot so {@link #emitPostChecks} can throw the right text. */
    private void installPostSlots(Decl.FnDecl fn, MethodVisitor mv, Locals locals) {
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
            currentPostSlots = List.of();
            currentPostBlame = List.of();
            currentPostTempSlot = -1;
        } else {
            currentPostSlots = List.copyOf(slots);
            currentPostBlame = List.copyOf(blame);
            currentPostTempSlot = locals.allocateAnon();
        }
    }

    /** Compile a single post/out lambda into a local slot and return
     *  the slot index, or -1 if {@code postExpr} isn't a lambda we
     *  can handle. */
    private int compilePostLambda(Expr postExpr, MethodVisitor mv, Locals locals) {
        if (!(postExpr instanceof Expr.Lambda lam)) return -1;
        emitLambda(lam, mv, locals);
        int slot = locals.allocateAnon();
        mv.visitVarInsn(ASTORE, slot);
        return slot;
    }

    /** Apply each post lambda to the stack-top value. Leaves the
     *  value on the stack unchanged after all checks pass; throws
     *  IrijRuntimeError on the first failing check. */
    private void emitPostChecks(MethodVisitor mv) {
        if (currentPostSlots.isEmpty()) return;
        // Stash result (still on stack after this).
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, currentPostTempSlot);
        for (int i = 0; i < currentPostSlots.size(); i++) {
            int slot = currentPostSlots.get(i);
            mv.visitVarInsn(ALOAD, slot);          // post fn
            // build Object[] {result}
            pushIconst(mv, 1);
            mv.visitTypeInsn(ANEWARRAY, OBJ);
            mv.visitInsn(DUP);
            pushIconst(mv, 0);
            mv.visitVarInsn(ALOAD, currentPostTempSlot);
            mv.visitInsn(AASTORE);
            mv.visitMethodInsn(INVOKEINTERFACE, IRIJ_FN, "apply",
                    "([Ljava/lang/Object;)Ljava/lang/Object;", true);
            mv.visitMethodInsn(INVOKESTATIC, RT, "truthy",
                    "(Ljava/lang/Object;)Z", false);
            Label ok = new Label();
            mv.visitJumpInsn(IFNE, ok);
            emitThrowRuntimeError(mv, currentPostBlame.get(i));
            mv.visitLabel(ok);
        }
    }

    /** Emit pre-condition + in-contract checks just before the TCO
     *  entry label. Lambda is applied to the current arg slots; on
     *  falsy result, throws IrijRuntimeError with caller-blame text. */
    private void emitPreContractChecks(Decl.FnDecl fn, MethodVisitor mv,
                                        Locals locals, List<Pattern> params) {
        emitPreList(fn.preConditions(), fn.name(), false,
                mv, locals, params);
        emitPreList(fn.inContracts(), fn.name(), true,
                mv, locals, params);
    }

    private void emitPreList(List<Expr> preList, String fnName, boolean isIn,
                              MethodVisitor mv, Locals locals, List<Pattern> params) {
        if (preList == null || preList.isEmpty()) return;
        String blame = isIn
                ? "Input contract violated in '" + fnName + "' (caller's fault)"
                : "Pre-condition violated in '" + fnName + "' (caller's fault)";
        for (Expr p : preList) {
            if (!(p instanceof Expr.Lambda lam)) continue;
            emitLambda(lam, mv, locals);  // stack: IrijFn
            // Build Object[] from param slots.
            pushIconst(mv, params.size());
            mv.visitTypeInsn(ANEWARRAY, OBJ);
            for (int i = 0; i < params.size(); i++) {
                mv.visitInsn(DUP);
                pushIconst(mv, i);
                mv.visitVarInsn(ALOAD, i);
                mv.visitInsn(AASTORE);
            }
            mv.visitMethodInsn(INVOKEINTERFACE, IRIJ_FN, "apply",
                    "([Ljava/lang/Object;)Ljava/lang/Object;", true);
            mv.visitMethodInsn(INVOKESTATIC, RT, "truthy",
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
    private void emitThrowRuntimeError(MethodVisitor mv, String message) {
        String ire = "dev/irij/IrijRuntimeError";
        mv.visitTypeInsn(NEW, ire);
        mv.visitInsn(DUP);
        mv.visitLdcInsn(message);
        mv.visitMethodInsn(INVOKESPECIAL, ire, "<init>",
                "(Ljava/lang/String;)V", false);
        mv.visitInsn(ATHROW);
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
                emitPatternTest(deferredPatterns.get(i), deferredSlots.get(i),
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
        String savedFnName = currentFnName;
        int savedFnArity = currentFnArity;
        Label savedFnEntry = currentFnEntry;
        String savedOutputSpec = currentOutputSpec;
        List<Integer> savedPostSlots = currentPostSlots;
        int savedPostTemp = currentPostTempSlot;
        List<String> savedPostBlame = currentPostBlame;

        currentFnName = fn.name();
        currentFnArity = params.size();
        currentFnEntry = new Label();
        // Capture the output spec (last entry in specAnnotations) so
        // every tail-return validates against it. Non-validatable specs
        // (wildcard / lowercase var) → null, no per-return overhead.
        currentOutputSpec = outputSpecEncoded(fn);
        installPostSlots(fn, mv, locals);

        // Runtime effect-row tracking. Push this fn's declared row onto
        // RT.EFFECT_ROW so that every `perform` inside the body honors
        // it (mirrors the interpreter's AVAILABLE_EFFECTS stack). A
        // wrap-all try/catch ensures the frame is popped on exception.
        boolean savedPushes = currentFnPushesEffects;
        currentFnPushesEffects = true;
        boolean ambient = isAmbientRow(fn.effectRow());
        if (ambient) {
            mv.visitMethodInsn(INVOKESTATIC, RT, "enterFnAmbient", "()V", false);
        } else {
            emitStringArrayConst(mv, fn.effectRow());
            mv.visitMethodInsn(INVOKESTATIC, RT, "enterFn", "([Ljava/lang/String;)V", false);
        }
        Label efTryStart = new Label();
        Label efTryEnd = new Label();
        Label efHandler = new Label();
        mv.visitLabel(efTryStart);
        mv.visitLabel(currentFnEntry);
        // Pre-scan body for `name := <handler-expr>` Simple binds so a
        // later `with name` inside the body can resolve through the
        // local alias (SM-1 fix).
        var savedHandlerBindings = currentLocalHandlerBindings;
        currentLocalHandlerBindings = scanLocalHandlerBindings(fn.body());
        try {
            switch (fn.body()) {
                case Decl.FnBody.LambdaBody lb -> emitTailExpr(lb.body(), mv, locals);
                case Decl.FnBody.MatchArmsBody mab -> {
                    Expr.MatchExpr me = new Expr.MatchExpr(
                            new Expr.Var("$scrut", null),
                            mab.arms(),
                            null);
                    emitMatchExpr(me, mv, locals);
                    emitTailReturn(mv);
                }
                case Decl.FnBody.ImperativeBody ib -> emitImperativeTail(ib.stmts(), mv, locals);
                default -> throw new IrijCompiler.CompileException(
                        "MVP: unsupported fn body: " + fn.body().getClass().getSimpleName());
            }
        } finally {
            currentLocalHandlerBindings = savedHandlerBindings;
            currentFnName = savedFnName;
            currentFnArity = savedFnArity;
            currentFnEntry = savedFnEntry;
            currentOutputSpec = savedOutputSpec;
            currentPostSlots = savedPostSlots;
            currentPostTempSlot = savedPostTemp;
            currentPostBlame = savedPostBlame;
            currentFnPushesEffects = savedPushes;
        }
        mv.visitLabel(efTryEnd);
        // Catch-all: pop the effect-row frame, then re-throw.
        mv.visitLabel(efHandler);
        mv.visitMethodInsn(INVOKESTATIC, RT, "exitFn", "()V", false);
        mv.visitInsn(ATHROW);
        mv.visitTryCatchBlock(efTryStart, efTryEnd, efHandler, null);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Emit an expression at tail position: either lowers to a self-tail-call
     * GOTO + arg rebind, or falls through to {@code emitExpr} followed by
     * {@code ARETURN}. Recurses into tail-propagating shapes (if/else) so a
     * deeply-nested self call still gets the optimisation.
     */
    private void emitTailExpr(Expr e, MethodVisitor mv, Locals locals) {
        // 1. Direct self-tail-call: `App(Var(currentFn), args)` with matching arity.
        if (e instanceof Expr.App app
                && app.fn() instanceof Expr.Var v
                && currentFnName != null
                && currentFnName.equals(v.name())
                && app.args().size() == currentFnArity) {
            // Evaluate all args onto the JVM operand stack first, THEN ASTORE
            // into param slots in reverse order. This guarantees args see the
            // pre-call param values (e.g. `loop (acc + n) (n - 1)` reads the
            // old acc + old n before either slot is overwritten).
            for (Expr a : app.args()) emitExpr(a, mv, locals);
            for (int i = currentFnArity - 1; i >= 0; i--) {
                mv.visitVarInsn(ASTORE, i);
            }
            mv.visitJumpInsn(GOTO, currentFnEntry);
            return;
        }

        // 2. Tail-propagating shape: if/else — both branches are tail.
        if (e instanceof Expr.IfExpr ie) {
            emitExpr(ie.cond(), mv, locals);
            mv.visitMethodInsn(INVOKESTATIC, RT, "truthy",
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
            emitImperativeTail(blk.stmts(), mv, locals);
            return;
        }

        // 4. Default: just compute the value and return.
        emitExpr(e, mv, locals);
        emitTailReturn(mv);
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
        } else if (last instanceof Stmt.MatchStmt ms) {
            // Tail-position match in a Block — propagate the matched
            // arm's value out as the Block's value.
            emitMatchExpr(new Expr.MatchExpr(ms.scrutinee(), ms.arms(), ms.loc()),
                    mv, inner);
        } else if (last instanceof Stmt.IfStmt ifs) {
            // Tail-position if — propagate each branch's value.
            emitImperativeIfAsExpr(ifs, mv, inner);
        } else {
            emitStmt(last, mv, inner);
            mv.visitInsn(ACONST_NULL);
        }
    }

    /** Emit an IfStmt as if it were an IfExpr — each branch becomes a
     *  Block whose value is the branch's last expression. Used when an
     *  if appears in tail position inside a Block. */
    private void emitImperativeIfAsExpr(Stmt.IfStmt ifs, MethodVisitor mv, Locals locals) {
        emitExpr(ifs.cond(), mv, locals);
        mv.visitMethodInsn(INVOKESTATIC, RT, "truthy",
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

    /** Mangle Irij kebab-case names to JVM-safe identifiers. */
    private static String mangle(String name) {
        return name.replace("-", "_").replace("?", "$q").replace("!", "$b");
    }

    // ── Top-level ──────────────────────────────────────────────────────

    private void emitTopLevel(Decl d, MethodVisitor mv, Locals locals) {
        switch (d) {
            case Decl.ExprDecl ed -> emitStmtExpr(ed.expr(), mv, locals);
            case Decl.BindingDecl bd -> {
                // Top-level binds with a simple target also get hoisted
                // to a static field so user-fns can read them (mirrors
                // the interpreter's globalEnv lookup). The original
                // local-slot store still happens (via emitStmt) so the
                // rest of main()'s code sees the binding.
                emitStmt(bd.stmt(), mv, locals);
                String topName = null;
                if (bd.stmt() instanceof Stmt.Bind b
                        && b.target() instanceof Stmt.BindTarget.Simple sm) {
                    topName = sm.name();
                } else if (bd.stmt() instanceof Stmt.MutBind mb
                        && mb.target() instanceof Stmt.BindTarget.Simple sm) {
                    topName = sm.name();
                }
                if (topName != null) {
                    Integer slot = locals.lookup(topName);
                    if (slot != null) {
                        String field = ensureTopLevelField(topName);
                        mv.visitVarInsn(ALOAD, slot);
                        mv.visitFieldInsn(PUTSTATIC, internalName, field, OBJ_DESC);
                    }
                }
                // Keep the old narrow nsPut path for namespace mode.
                if (bd.stmt() instanceof Stmt.Bind b
                        && b.target() instanceof Stmt.BindTarget.Simple sm) {
                    Integer slot = locals.lookup(sm.name());
                    if (slot != null) {
                        String field = ensureTopLevelField(sm.name());
                        mv.visitVarInsn(ALOAD, slot);
                        mv.visitFieldInsn(PUTSTATIC, internalName, field, OBJ_DESC);
                        // Namespace-mode write-through: also store into
                        // the session namespace so subsequent
                        // eval-bytecode calls see it.
                        if (options.namespaceMode()) {
                            mv.visitLdcInsn(sm.name());
                            mv.visitVarInsn(ALOAD, slot);
                            mv.visitMethodInsn(INVOKESTATIC, RT, "nsPut",
                                    "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false);
                            mv.visitInsn(POP);
                        }
                    }
                }
            }
            case Decl.IfDecl id -> emitStmt(id.ifStmt(), mv, locals);
            case Decl.MatchDecl md -> emitStmt(md.match(), mv, locals);
            case Decl.SpecDecl __ -> { /* structural only; constructors resolved via TypeRef */ }
            case Decl.ProtoDecl __ -> { /* no runtime rep; methods go through dispatchers */ }
            case Decl.ImplDecl __ -> { /* bindings hoisted to static methods in pass 2b */ }
            case Decl.EffectDecl __ -> { /* ops registered in pass 1 */ }
            case Decl.ModDecl __ -> { /* preserved by inliner for EffectRowChecker; emitter skips */ }
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
        emitLine(mv, locOf(s));
        switch (s) {
            case Stmt.ExprStmt es -> emitStmtExpr(es.expr(), mv, locals);
            case Stmt.Bind b -> emitBind(b, mv, locals);
            case Stmt.MutBind mb -> emitMutBind(mb, mv, locals);
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
        // Top-level mutable bind: write the static field FIRST so
        // captured-by-static-field readers (lambdas, other threads)
        // see the update. Falls through to local-slot update if there
        // is one, so same-method reads also see the new value.
        String topField = topLevelFields.get(s.name());
        if (topField != null) {
            mv.visitInsn(DUP);
            mv.visitFieldInsn(PUTSTATIC, internalName, topField, OBJ_DESC);
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
        String field = currentStateFields.get(s.name());
        if (field != null) {
            mv.visitFieldInsn(PUTSTATIC, internalName, field, OBJ_DESC);
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
    private void emitMutBind(Stmt.MutBind mb, MethodVisitor mv, Locals locals) {
        if (!(mb.target() instanceof Stmt.BindTarget.Simple simple)) {
            throw new IrijCompiler.CompileException(
                    "MVP: mutable bind requires a Simple target (got "
                            + mb.target().getClass().getSimpleName() + ")");
        }
        Integer liftedIdx = currentLiftedLocals.get(simple.name());
        if (liftedIdx != null && currentKSlot >= 0) {
            mv.visitVarInsn(ALOAD, currentKSlot);
            mv.visitFieldInsn(GETFIELD, CONT, "fields", "[Ljava/lang/Object;");
            pushIconst(mv, liftedIdx);
            emitExpr(mb.value(), mv, locals);
            mv.visitInsn(AASTORE);
            return;
        }
        emitExpr(mb.value(), mv, locals);
        int slot = locals.allocate(simple.name());
        mv.visitVarInsn(ASTORE, slot);
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
        emitLine(mv, locOf(e));
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
            case Expr.RecordUpdate ru -> emitRecordUpdate(ru, mv, locals);
            case Expr.StringInterp si -> emitStringInterp(si, mv, locals);
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
            case Expr.OpSection os -> emitOpSection(os.op(), mv);
            case Expr.Range r -> {
                emitExpr(r.from(), mv, locals);
                emitExpr(r.to(), mv, locals);
                mv.visitInsn(r.exclusive() ? ICONST_1 : ICONST_0);
                mv.visitMethodInsn(INVOKESTATIC, RT, "rangeOf",
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

    private void emitVarLoad(String name, MethodVisitor mv, Locals locals) {
        // Locals shadow ALL outer scopes — pattern binds, params, lets.
        // Without this, `Err e => e` returned Math.E (the `e` constant
        // shadowed by the pattern-bound `e`).
        Integer __preSlot = locals.lookup(name);
        if (__preSlot != null) {
            mv.visitVarInsn(ALOAD, __preSlot);
            return;
        }
        Integer __preLifted = currentLiftedLocals.get(name);
        if (__preLifted != null && currentKSlot >= 0) {
            mv.visitVarInsn(ALOAD, currentKSlot);
            mv.visitFieldInsn(GETFIELD, CONT, "fields", "[Ljava/lang/Object;");
            pushIconst(mv, __preLifted);
            mv.visitInsn(AALOAD);
            return;
        }
        // Top-level mut binds: read via GETSTATIC so cross-thread
        // updates (e.g. assignments inside a forked fiber) are visible.
        // The dual local slot allocated at init time is only used by
        // the initializer itself; once init completes, the static is
        // authoritative.
        if (topLevelFields.containsKey(name)
                && !BUILTIN_CONST_NAMES.contains(name)) {
            mv.visitFieldInsn(GETSTATIC, internalName,
                    topLevelFields.get(name), OBJ_DESC);
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
                mv.visitFieldInsn(GETSTATIC, RT, "PI_BOXED", OBJ_DESC);
                return;
            }
            case "e" -> {
                mv.visitFieldInsn(GETSTATIC, RT, "E_BOXED", OBJ_DESC);
                return;
            }
            case "identity" -> {
                mv.visitFieldInsn(GETSTATIC, RT, "IDENTITY", IRIJ_FN_DESC);
                return;
            }
            case "const" -> {
                mv.visitFieldInsn(GETSTATIC, RT, "CONST", IRIJ_FN_DESC);
                return;
            }
            // Builtins passed as values (sort-by length #[…], etc.)
            // Each maps to a static IrijFn in RuntimeSupport.
            case "length"   -> { mv.visitFieldInsn(GETSTATIC, RT, "LENGTH", IRIJ_FN_DESC); return; }
            case "head"     -> { mv.visitFieldInsn(GETSTATIC, RT, "HEAD", IRIJ_FN_DESC); return; }
            case "tail"     -> { mv.visitFieldInsn(GETSTATIC, RT, "TAIL", IRIJ_FN_DESC); return; }
            case "empty?"   -> { mv.visitFieldInsn(GETSTATIC, RT, "EMPTY_Q", IRIJ_FN_DESC); return; }
            case "to-str"   -> { mv.visitFieldInsn(GETSTATIC, RT, "TO_STR", IRIJ_FN_DESC); return; }
            case "not"      -> { mv.visitFieldInsn(GETSTATIC, RT, "NOT_FN", IRIJ_FN_DESC); return; }
            case "type-of"  -> { mv.visitFieldInsn(GETSTATIC, RT, "TYPE_OF", IRIJ_FN_DESC); return; }
            case "abs"      -> { mv.visitFieldInsn(GETSTATIC, RT, "ABS_FN", IRIJ_FN_DESC); return; }
            case "sqrt"     -> { mv.visitFieldInsn(GETSTATIC, RT, "SQRT_FN", IRIJ_FN_DESC); return; }
            case "floor"    -> { mv.visitFieldInsn(GETSTATIC, RT, "FLOOR_FN", IRIJ_FN_DESC); return; }
            case "ceil"     -> { mv.visitFieldInsn(GETSTATIC, RT, "CEIL_FN", IRIJ_FN_DESC); return; }
            case "round"    -> { mv.visitFieldInsn(GETSTATIC, RT, "ROUND_FN", IRIJ_FN_DESC); return; }
            case "reverse"  -> { mv.visitFieldInsn(GETSTATIC, RT, "REVERSE_FN", IRIJ_FN_DESC); return; }
            case "sort"     -> { mv.visitFieldInsn(GETSTATIC, RT, "SORT_FN", IRIJ_FN_DESC); return; }
            case "println"  -> { mv.visitFieldInsn(GETSTATIC, RT, "PRINTLN_FN", IRIJ_FN_DESC); return; }
            case "print"    -> { mv.visitFieldInsn(GETSTATIC, RT, "PRINT_FN", IRIJ_FN_DESC); return; }
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
        // Namespace-mode fallback for nREPL eval-bytecode: read the value
        // from the session's shared namespace via RT.nsGet. Lets
        // successive evals see each other's top-level `:=` bindings.
        if (options.namespaceMode()) {
            mv.visitLdcInsn(name);
            mv.visitMethodInsn(INVOKESTATIC, RT, "nsGet",
                    "(Ljava/lang/String;)Ljava/lang/Object;", false);
            return;
        }
        // Top-level binding hoisted to a static field (interpreter
        // semantics: visible from any user-fn body).
        String topField = topLevelFields.get(name);
        if (topField != null) {
            mv.visitFieldInsn(GETSTATIC, internalName, topField, OBJ_DESC);
            return;
        }
        // User fn referenced as a value (e.g. passed to a higher-order fn
        // like `fold add 0 v`). Synthesise an IrijFn wrapper once per fn
        // and push it here as an LMF-built lambda.
        if (fnArity.containsKey(name)) {
            int arity = fnArity.get(name);
            ensureUserFnWrapper(name, arity);
            Handle bsm = new Handle(H_INVOKESTATIC,
                    "java/lang/invoke/LambdaMetafactory", "metafactory",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                            + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                            + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                            + "Ljava/lang/invoke/CallSite;",
                    false);
            Type samType = Type.getMethodType(APPLY_DESC);
            Handle implHandle = new Handle(H_INVOKESTATIC, internalName,
                    userFnWrapperName(name), APPLY_DESC, false);
            mv.visitInvokeDynamicInsn("apply", "()" + IRIJ_FN_DESC, bsm,
                    samType, implHandle, samType);
            return;
        }
        // Final fallback: resolve as a builtin via the runtime
        // registry. Lets any name registered by Builtins.install
        // be used as a first-class value (`sort-by length #[…]`,
        // `@ to-str v`, etc.) without enumerating each one as a
        // static IrijFn here.
        mv.visitLdcInsn(name);
        mv.visitMethodInsn(INVOKESTATIC, RT, "builtinFn",
                "(Ljava/lang/String;)" + IRIJ_FN_DESC, false);
    }

    private static String userFnWrapperName(String fnName) {
        return mangle(fnName) + "$irijfn";
    }

    /** Emit a one-time `f$irijfn(Object[]) -> Object` adapter that
     *  unpacks the array and calls `f` via INVOKESTATIC. */
    private void ensureUserFnWrapper(String fnName, int arity) {
        if (!emittedFnWrappers.add(fnName)) return; // already emitted
        MethodVisitor w = classWriter.visitMethod(
                ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
                userFnWrapperName(fnName), APPLY_DESC, null, null);
        w.visitCode();
        int argsSlot = 0;
        for (int i = 0; i < arity; i++) {
            w.visitVarInsn(ALOAD, argsSlot);
            pushIconst(w, i);
            w.visitInsn(AALOAD);
        }
        StringBuilder desc = new StringBuilder("(");
        for (int i = 0; i < arity; i++) desc.append(OBJ_DESC);
        desc.append(")").append(OBJ_DESC);
        w.visitMethodInsn(INVOKESTATIC, internalName, mangle(fnName),
                desc.toString(), false);
        w.visitInsn(ARETURN);
        w.visitMaxs(0, 0);
        w.visitEnd();
    }

    /** Operator section `(op)` — push the pre-built RuntimeSupport.IrijFn
     *  constant so the operator can be passed as a value. */
    private void emitOpSection(String op, MethodVisitor mv) {
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
        mv.visitFieldInsn(GETSTATIC, RT, constName, IRIJ_FN_DESC);
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
            case "**" -> mv.visitMethodInsn(INVOKESTATIC, RT, "pow", BINOP_DESC, false);
            case "<"  -> cmpToBoxedBool(mv, "lt");
            case "<=" -> cmpToBoxedBool(mv, "le");
            case ">"  -> cmpToBoxedBool(mv, "gt");
            case ">=" -> cmpToBoxedBool(mv, "ge");
            case "==" -> cmpToBoxedBool(mv, "eq");
            case "!=", "/=" -> cmpToBoxedBool(mv, "neq");
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

    /** `"prefix {expr} suffix"` — build a String via StringBuilder,
     *  appending each part. Interpolated exprs go through
     *  {@code Values.toIrijString}. */
    private void emitStringInterp(Expr.StringInterp si, MethodVisitor mv, Locals locals) {
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
                mv.visitMethodInsn(INVOKESTATIC, VALUES, "toIrijString",
                        "(Ljava/lang/Object;)Ljava/lang/String;", false);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            }
        }
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false);
    }

    /** `{...base k1= v1 k2= v2}` — clone base map, overwrite keys. */
    private void emitRecordUpdate(Expr.RecordUpdate ru, MethodVisitor mv, Locals locals) {
        // Push the base value, call RT.recordUpdateBegin → LinkedHashMap.
        emitVarLoad(ru.base(), mv, locals);
        mv.visitMethodInsn(INVOKESTATIC, RT, "recordUpdateBegin",
                "(Ljava/lang/Object;)Ljava/util/LinkedHashMap;", false);
        // For each Field entry, DUP map, push key/value, put, pop result.
        for (Expr.MapEntry me : ru.updates()) {
            if (!(me instanceof Expr.MapEntry.Field f)) {
                throw new IrijCompiler.CompileException(
                        "MVP: record-update spread not supported");
            }
            mv.visitInsn(DUP);
            mv.visitLdcInsn(f.key());
            emitExpr(f.value(), mv, locals);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
            mv.visitInsn(POP);
        }
        // Wrap the LinkedHashMap in IrijMap.
        mv.visitTypeInsn(NEW, VALUES + "$IrijMap");
        mv.visitInsn(DUP_X1);
        mv.visitInsn(SWAP);
        mv.visitMethodInsn(INVOKESPECIAL, VALUES + "$IrijMap", "<init>",
                "(Ljava/util/Map;)V", false);
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
    /** Builtins that have an associated effect (mirrors {@code BuiltinFn.requiredEffects}
     *  in {@link dev.irij.interpreter.Builtins}). Emitting one of these triggers a
     *  {@code RT.checkPerformEffect} so the enclosing fn's row honors the requirement. */
    private static final java.util.Map<String, String> BUILTIN_EFFECT = java.util.Map.ofEntries(
        java.util.Map.entry("print",            "Console"),
        java.util.Map.entry("println",          "Console"),
        java.util.Map.entry("dbg",              "Console"),
        java.util.Map.entry("read-line",        "Console"),
        java.util.Map.entry("read-file",        "FileIO"),
        java.util.Map.entry("write-file",       "FileIO"),
        java.util.Map.entry("file-exists?",     "FileIO"),
        java.util.Map.entry("list-dir",         "FileIO"),
        java.util.Map.entry("delete-file",      "FileIO"),
        java.util.Map.entry("make-dir",         "FileIO"),
        java.util.Map.entry("append-file",      "FileIO"),
        java.util.Map.entry("raw-multipart-save","FileIO"),
        java.util.Map.entry("raw-db-open",      "Db"),
        java.util.Map.entry("raw-db-query",     "Db"),
        java.util.Map.entry("raw-db-exec",      "Db"),
        java.util.Map.entry("raw-db-close",     "Db"),
        java.util.Map.entry("raw-db-transaction","Db"),
        java.util.Map.entry("sleep",            "Time"),
        java.util.Map.entry("now-ms",           "Time"),
        java.util.Map.entry("random-int",       "Random"),
        java.util.Map.entry("random-float",     "Random")
    );

    private void emitBuiltinEffectCheck(String name, MethodVisitor mv) {
        String eff = BUILTIN_EFFECT.get(name);
        if (eff == null) return;
        mv.visitLdcInsn(eff);
        mv.visitLdcInsn(name);
        mv.visitMethodInsn(INVOKESTATIC, RT, "checkPerformEffect",
                "(Ljava/lang/String;Ljava/lang/String;)V", false);
    }

    private boolean emitBuiltinApp(String name, List<Expr> args, MethodVisitor mv, Locals locals) {
        emitBuiltinEffectCheck(name, mv);
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
            // ── Collection / string primitives (Phase 2) ───────────────
            // Names match the interpreter convention in Builtins.java so a
            // single .irj source compiles + interprets identically. These
            // are the raw building blocks; stdlib fns (fold/map/filter/etc.)
            // are written in Irij on top.
            case "length" -> {
                if (args.size() != 1) return false;
                emitExpr(args.get(0), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, RT, "length",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            case "nth" -> {
                if (args.size() != 2) return false;
                emitExpr(args.get(0), mv, locals);
                emitExpr(args.get(1), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, RT, "nth",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            case "conj" -> {
                if (args.size() != 2) return false;
                emitExpr(args.get(0), mv, locals);
                emitExpr(args.get(1), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, RT, "conj",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            case "empty?" -> {
                if (args.size() != 1) return false;
                emitExpr(args.get(0), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, RT, "isEmpty",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            case "head" -> {
                if (args.size() != 1) return false;
                emitExpr(args.get(0), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, RT, "head",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            case "tail" -> {
                if (args.size() != 1) return false;
                emitExpr(args.get(0), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, RT, "tail",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            // Effect-transparent higher-order builtin: callback runs in
            // the caller's effect row (matches the interpreter
            // BuiltinFn semantics).
            case "fold" -> {
                if (args.size() != 3) return false;
                emitExpr(args.get(0), mv, locals);
                emitExpr(args.get(1), mv, locals);
                emitExpr(args.get(2), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, RT, "fold",
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            // ── Phase R3 ports: strings + maps + misc ────────────────
            // Each case shape: emit args left-to-right, then a single
            // static call into RuntimeSupport. The RT method names are
            // Java-safe (containsP, getOp, etc.) since Irij names can
            // include `?` and `-`.
            case "replace"      -> { return emitRT3(args, mv, locals, "replace"); }
            case "substring"    -> { return emitRT3(args, mv, locals, "substring"); }
            case "split"        -> { return emitRT2(args, mv, locals, "split"); }
            case "join"         -> { return emitRT2(args, mv, locals, "join"); }
            case "trim"         -> { return emitRT1(args, mv, locals, "trimStr"); }
            case "upper-case"   -> { return emitRT1(args, mv, locals, "upperCase"); }
            case "lower-case"   -> { return emitRT1(args, mv, locals, "lowerCase"); }
            case "starts-with?" -> { return emitRT2(args, mv, locals, "startsWithP"); }
            case "ends-with?"   -> { return emitRT2(args, mv, locals, "endsWithP"); }
            case "index-of"     -> { return emitRT2(args, mv, locals, "indexOf"); }
            case "url-encode"   -> { return emitRT1(args, mv, locals, "urlEncode"); }
            case "url-decode"   -> { return emitRT1(args, mv, locals, "urlDecode"); }
            case "get"          -> { return emitRT2(args, mv, locals, "getOp"); }
            case "assoc"        -> { return emitRT3(args, mv, locals, "assoc"); }
            case "dissoc"       -> { return emitRT2(args, mv, locals, "dissoc"); }
            case "merge"        -> { return emitRT2(args, mv, locals, "merge"); }
            case "keys"         -> { return emitRT1(args, mv, locals, "keys"); }
            case "vals"         -> { return emitRT1(args, mv, locals, "vals"); }
            case "contains?"    -> { return emitRT2(args, mv, locals, "containsP"); }
            case "last"         -> { return emitRT1(args, mv, locals, "last"); }
            case "to-vec"       -> { return emitRT1(args, mv, locals, "toVec"); }
            case "not"          -> { return emitRT1(args, mv, locals, "notOp"); }
            case "type-of"      -> { return emitRT1(args, mv, locals, "typeOf"); }
            case "validate"     -> { return emitRT2(args, mv, locals, "validate"); }
            case "validate!"    -> { return emitRT2(args, mv, locals, "validateBang"); }
            // ── R3 batch 2: SQLite raw-db-* ──────────────────────────
            case "raw-db-open"        -> { return emitRT1(args, mv, locals, "rawDbOpen"); }
            case "raw-db-close"       -> { return emitRT1(args, mv, locals, "rawDbClose"); }
            case "raw-db-query"       -> { return emitRT3(args, mv, locals, "rawDbQuery"); }
            case "raw-db-exec"        -> { return emitRT3(args, mv, locals, "rawDbExec"); }
            case "raw-db-transaction" -> { return emitRT2(args, mv, locals, "rawDbTransaction"); }
            // ── R3 batch 3: SSE + multipart + raw-http-serve ─────────
            case "raw-sse-response"   -> { return emitRT1(args, mv, locals, "rawSseResponse"); }
            case "raw-sse-send"       -> { return emitRT3(args, mv, locals, "rawSseSend"); }
            case "raw-sse-close"      -> { return emitRT1(args, mv, locals, "rawSseClose"); }
            case "raw-sse-closed?"    -> { return emitRT1(args, mv, locals, "rawSseClosedQ"); }
            case "raw-multipart-field" -> { return emitRT2(args, mv, locals, "rawMultipartField"); }
            case "raw-multipart-save"  -> { return emitRT3(args, mv, locals, "rawMultipartSave"); }
            case "raw-http-serve"      -> { return emitRT2(args, mv, locals, "rawHttpServe"); }
            // ── R3 batch 4: JSON + FileIO + env / time ───────────────
            case "json-parse"          -> { return emitRT1(args, mv, locals, "jsonParse"); }
            case "json-encode"         -> { return emitRT1(args, mv, locals, "jsonEncode"); }
            case "json-encode-pretty"  -> { return emitRT1(args, mv, locals, "jsonEncodePretty"); }
            case "make-dir"            -> { return emitRT1(args, mv, locals, "makeDir"); }
            case "list-dir"            -> { return emitRT1(args, mv, locals, "listDir"); }
            case "delete-file"         -> { return emitRT1(args, mv, locals, "deleteFile"); }
            case "read-file"           -> { return emitRT1(args, mv, locals, "readFile"); }
            case "write-file"          -> { return emitRT2(args, mv, locals, "writeFile"); }
            case "append-file"         -> { return emitRT2(args, mv, locals, "appendFile"); }
            case "file-exists?"        -> { return emitRT1(args, mv, locals, "fileExistsQ"); }
            case "get-env"             -> { return emitRT1(args, mv, locals, "getEnv"); }
            case "now-ms"              -> {
                if (!isZeroArgCall(args)) return false;
                mv.visitMethodInsn(INVOKESTATIC, RT, "nowMs", "()Ljava/lang/Object;", false);
                return true;
            }
            case "env" -> {
                pushObjectArray(args, mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, RT, "envBuiltin",
                        "([Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            // ── R3: math primitives ──────────────────────────────────
            case "sqrt"  -> { return emitRT1(args, mv, locals, "sqrt"); }
            case "sin"   -> { return emitRT1(args, mv, locals, "sin"); }
            case "cos"   -> { return emitRT1(args, mv, locals, "cos"); }
            case "tan"   -> { return emitRT1(args, mv, locals, "tan"); }
            case "log"   -> {
                // `log` collides with a common effect op name
                // (`effect Log { log :: Str -> () }`). When the user
                // has declared such an effect, fall through to the
                // effect-op dispatch path instead of math.log.
                if (effectOps.containsKey("log")) return false;
                return emitRT1(args, mv, locals, "log");
            }
            case "exp"   -> { return emitRT1(args, mv, locals, "exp"); }
            case "floor" -> { return emitRT1(args, mv, locals, "floor"); }
            case "ceil"  -> { return emitRT1(args, mv, locals, "ceil"); }
            case "round" -> { return emitRT1(args, mv, locals, "round"); }
            case "abs"   -> { return emitRT1(args, mv, locals, "abs"); }
            case "pow"   -> { return emitRT2(args, mv, locals, "pow"); }
            case "min"   -> { return emitRT2(args, mv, locals, "min"); }
            case "max"   -> { return emitRT2(args, mv, locals, "max"); }
            case "div"   -> { return emitRT2(args, mv, locals, "divInt"); }
            case "mod"   -> { return emitRT2(args, mv, locals, "modInt"); }
            // ── R3: random ───────────────────────────────────────────
            case "random-int"   -> { return emitRT1(args, mv, locals, "randomInt"); }
            case "random-float" -> {
                if (!isZeroArgCall(args)) return false;
                mv.visitMethodInsn(INVOKESTATIC, RT, "randomFloat",
                        "()Ljava/lang/Object;", false);
                return true;
            }
            // ── R3: string parsing / chars ───────────────────────────
            case "parse-int"      -> { return emitRT1(args, mv, locals, "parseInt"); }
            case "parse-float"    -> { return emitRT1(args, mv, locals, "parseFloat"); }
            case "char-at"        -> { return emitRT2(args, mv, locals, "charAt"); }
            case "char-code"      -> { return emitRT1(args, mv, locals, "charCode"); }
            case "from-char-code" -> { return emitRT1(args, mv, locals, "fromCharCode"); }
            // ── R3: vec ops not yet emitted ──────────────────────────
            case "reverse" -> { return emitRT1(args, mv, locals, "reverseVal"); }
            case "sort"    -> { return emitRT1(args, mv, locals, "sortVal"); }
            case "take"    -> { return emitRT2(args, mv, locals, "takeVal"); }
            case "drop"    -> { return emitRT2(args, mv, locals, "dropVal"); }
            case "concat"  -> { return emitRT2(args, mv, locals, "concatTwo"); }
            // ── R3: misc ─────────────────────────────────────────────
            case "dbg" -> {
                if (args.size() != 1) return false;
                emitExpr(args.get(0), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, RT, "dbg",
                        "(Ljava/lang/Object;)V", false);
                mv.visitFieldInsn(GETSTATIC, VALUES, "UNIT", OBJ_DESC);
                return true;
            }
            case "read-line" -> {
                if (!isZeroArgCall(args)) return false;
                mv.visitMethodInsn(INVOKESTATIC, RT, "readLine",
                        "()Ljava/lang/Object;", false);
                return true;
            }
            case "identity" -> {
                if (args.size() != 1) return false;
                emitExpr(args.get(0), mv, locals);
                return true; // x → x
            }
            case "const" -> {
                // const x y → x — emit x, evaluate-and-discard y
                if (args.size() != 2) return false;
                emitExpr(args.get(0), mv, locals);
                emitExpr(args.get(1), mv, locals);
                mv.visitInsn(POP);
                return true;
            }
            // ── R3 final batch: toml-parse, println, raw-http-request,
            //    flip handled below ───────────────────────────────────
            case "toml-parse"       -> { return emitRT1(args, mv, locals, "tomlParse"); }
            case "raw-http-request" -> { return emitRT1(args, mv, locals, "rawHttpRequest"); }
            case "flip" -> {
                // flip f a b → f b a. Three-arg call form. The
                // interpreter's BuiltinFn version errors because of
                // missing partial-app support; bytecode just emits
                // the rewrite directly.
                if (args.size() != 3) return false;
                emitExpr(args.get(0), mv, locals);  // f
                emitExpr(args.get(2), mv, locals);  // b
                emitExpr(args.get(1), mv, locals);  // a
                // Stack: f, b, a — build [b, a] array, call f.apply
                pushIconst(mv, 2);
                mv.visitTypeInsn(ANEWARRAY, OBJ);
                // Array on top; need to populate from below-the-arr items.
                // Easier: push args in order, store in array via swap-y dance.
                // Simpler approach: stash into temp slots, then build.
                // Fall through: undo the above and rebuild cleanly via temps.
                mv.visitInsn(POP);
                int aSlot = locals.allocateAnon();
                int bSlot = locals.allocateAnon();
                int fSlot = locals.allocateAnon();
                mv.visitVarInsn(ASTORE, aSlot);
                mv.visitVarInsn(ASTORE, bSlot);
                mv.visitVarInsn(ASTORE, fSlot);
                mv.visitVarInsn(ALOAD, fSlot);
                pushIconst(mv, 2);
                mv.visitTypeInsn(ANEWARRAY, OBJ);
                mv.visitInsn(DUP);
                pushIconst(mv, 0);
                mv.visitVarInsn(ALOAD, bSlot);
                mv.visitInsn(AASTORE);
                mv.visitInsn(DUP);
                pushIconst(mv, 1);
                mv.visitVarInsn(ALOAD, aSlot);
                mv.visitInsn(AASTORE);
                mv.visitMethodInsn(INVOKESTATIC, RT, "callAny",
                        "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            // ── R3 batch 6: sandboxed-interpreter tier ───────────────
            case "raw-session-create" -> {
                if (!isZeroArgCall(args)) return false;
                mv.visitMethodInsn(INVOKESTATIC, SESSIONS, "rawSessionCreate",
                        "()Ljava/lang/Object;", false);
                return true;
            }
            case "raw-session-eval" -> {
                if (args.size() != 3) return false;
                emitExpr(args.get(0), mv, locals);
                emitExpr(args.get(1), mv, locals);
                emitExpr(args.get(2), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, SESSIONS, "rawSessionEval",
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                        false);
                return true;
            }
            case "raw-session-destroy" -> {
                return emitSessions1(args, mv, locals, "rawSessionDestroy");
            }
            case "raw-session-subscribe" -> {
                return emitSessions2(args, mv, locals, "rawSessionSubscribe");
            }
            case "raw-session-unsubscribe" -> {
                return emitSessions1(args, mv, locals, "rawSessionUnsubscribe");
            }
            case "raw-session-cleanup" -> {
                return emitSessions1(args, mv, locals, "rawSessionCleanup");
            }
            case "raw-nrepl-eval-sandboxed" -> {
                return emitSessions2(args, mv, locals, "rawNreplEvalSandboxed");
            }
            default -> { return false; }
        }
    }

    private static final String SESSIONS = "dev/irij/compiler/RuntimeSessions";

    /** True if the call site is a zero-arg invocation OR a one-arg
     *  call where the single arg is the Unit literal — the idiomatic
     *  Irij way to write "no args" (e.g. `now-ms ()`). */
    private static boolean isZeroArgCall(List<Expr> args) {
        if (args.isEmpty()) return true;
        return args.size() == 1 && args.get(0) instanceof Expr.UnitLit;
    }

    private boolean emitSessions1(List<Expr> args, MethodVisitor mv, Locals locals, String method) {
        if (args.size() != 1) return false;
        emitExpr(args.get(0), mv, locals);
        mv.visitMethodInsn(INVOKESTATIC, SESSIONS, method,
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        return true;
    }

    private boolean emitSessions2(List<Expr> args, MethodVisitor mv, Locals locals, String method) {
        if (args.size() != 2) return false;
        emitExpr(args.get(0), mv, locals);
        emitExpr(args.get(1), mv, locals);
        mv.visitMethodInsn(INVOKESTATIC, SESSIONS, method,
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
        return true;
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
    private void emitSeqOp(Expr.SeqOp so, MethodVisitor mv, Locals locals) {
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
                mv.visitFieldInsn(GETSTATIC, RT, field, IRIJ_FN_DESC);
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
                mv.visitMethodInsn(INVOKESTATIC, RT, method,
                        "(Ljava/lang/Object;)" + IRIJ_FN_DESC, false);
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
                mv.visitMethodInsn(INVOKESTATIC, RT, method,
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            }
            default -> throw new IrijCompiler.CompileException(
                    "MVP: unsupported seq op: " + op);
        }
    }

    /** Emit a 1-arg call to RT.<method>(Object): Object. */
    private boolean emitRT1(List<Expr> args, MethodVisitor mv, Locals locals, String method) {
        if (args.size() != 1) return false;
        emitExpr(args.get(0), mv, locals);
        mv.visitMethodInsn(INVOKESTATIC, RT, method,
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        return true;
    }

    /** Emit a 2-arg call to RT.<method>(Object, Object): Object. */
    private boolean emitRT2(List<Expr> args, MethodVisitor mv, Locals locals, String method) {
        if (args.size() != 2) return false;
        emitExpr(args.get(0), mv, locals);
        emitExpr(args.get(1), mv, locals);
        mv.visitMethodInsn(INVOKESTATIC, RT, method,
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
        return true;
    }

    /** Emit a 3-arg call to RT.<method>(Object, Object, Object): Object. */
    private boolean emitRT3(List<Expr> args, MethodVisitor mv, Locals locals, String method) {
        if (args.size() != 3) return false;
        emitExpr(args.get(0), mv, locals);
        emitExpr(args.get(1), mv, locals);
        emitExpr(args.get(2), mv, locals);
        mv.visitMethodInsn(INVOKESTATIC, RT, method,
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        return true;
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
        String specName = tagToSpec.get(tag);
        if (fieldNames != null && fieldNames.size() == args.size()) {
            // new Values$Tagged(tag, List.of(args...), Map.of(name → arg, ...), specName)
            mv.visitTypeInsn(NEW, VALUES + "$Tagged");
            mv.visitInsn(DUP);
            mv.visitLdcInsn(tag);
            pushObjectList(args, mv, locals);
            pushNamedFieldMap(fieldNames, args, mv, locals);
            if (specName != null) {
                mv.visitLdcInsn(specName);
                mv.visitMethodInsn(INVOKESPECIAL, VALUES + "$Tagged", "<init>",
                        "(Ljava/lang/String;Ljava/util/List;Ljava/util/Map;Ljava/lang/String;)V", false);
            } else {
                mv.visitMethodInsn(INVOKESPECIAL, VALUES + "$Tagged", "<init>",
                        "(Ljava/lang/String;Ljava/util/List;Ljava/util/Map;)V", false);
            }
            return;
        }
        // Sum variant or unknown: positional only (+ specName when known).
        mv.visitTypeInsn(NEW, VALUES + "$Tagged");
        mv.visitInsn(DUP);
        mv.visitLdcInsn(tag);
        pushObjectList(args, mv, locals);
        if (specName != null) {
            mv.visitInsn(ACONST_NULL);  // namedFields = null
            mv.visitLdcInsn(specName);
            mv.visitMethodInsn(INVOKESPECIAL, VALUES + "$Tagged", "<init>",
                    "(Ljava/lang/String;Ljava/util/List;Ljava/util/Map;Ljava/lang/String;)V", false);
        } else {
            mv.visitMethodInsn(INVOKESPECIAL, VALUES + "$Tagged", "<init>",
                    "(Ljava/lang/String;Ljava/util/List;)V", false);
        }
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
                // Pass the handler's declared required effects to the
                // clause lambda so its body sees them on EFFECT_ROW.
                pendingClauseEffects = h.requiredEffects();
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
        // Runtime effect-row check: throws if the enclosing fn doesn't
        // declare this effect (and we're not inside an ambient frame).
        mv.visitLdcInsn(effectName);
        mv.visitLdcInsn(opName);
        mv.visitMethodInsn(INVOKESTATIC, RT, "checkPerformEffect",
                "(Ljava/lang/String;Ljava/lang/String;)V", false);
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
        // Runtime effect-row: push each handler's effect onto the
        // RT.EFFECT_ROW stack so inner performs see them as available.
        // Wrap the body in a try/catch-rethrow that pops the frames.
        java.util.List<String> pushedEffects = new java.util.ArrayList<>();
        for (String hName : collectHandlerNames(w.handler())) {
            Decl.HandlerDecl hd = handlers.get(hName);
            if (hd != null) {
                // Push the effect the handler handles so the with-body
                // can perform it without a row violation. The handler's
                // own required effects (its `::: …` declaration) are
                // pushed inside each clause lambda on entry — see
                // {@code emitLambda} + {@code pendingClauseEffects}.
                mv.visitLdcInsn(hd.effectName());
                mv.visitMethodInsn(INVOKESTATIC, RT, "enterWith",
                        "(Ljava/lang/String;)V", false);
                pushedEffects.add(hd.effectName());
            }
        }
        Label withStart = new Label();
        Label withEnd = new Label();
        Label withHandler = new Label();
        Label afterWith = new Label();
        mv.visitLabel(withStart);
        if (!smCanHandle(w.handler())) {
            throw new IrijCompiler.CompileException(
                    "with: handler shape not supported by state-machine lowering at "
                            + (w.loc() != null ? w.loc().line() + ":" + w.loc().col() : "<unknown>"));
        }
        List<Stmt> body = new ANormalizer().normalize(w.body());
        body = expandDestructureBindsForSM(body);
        WithBodyShape shape = classifyWithBody(body);
        if (shape instanceof WithBodyShape.Unsupported) {
            throw new IrijCompiler.CompileException(
                    "with: body shape not supported by state-machine lowering at "
                            + (w.loc() != null ? w.loc().line() + ":" + w.loc().col() : "<unknown>"));
        }
        emitWithSM(w, body, shape, mv, outer);
        mv.visitLabel(withEnd);
        // Normal exit: pop each pushed frame, then GOTO afterWith.
        for (int i = 0; i < pushedEffects.size(); i++) {
            mv.visitMethodInsn(INVOKESTATIC, RT, "exitWith", "()V", false);
        }
        mv.visitJumpInsn(GOTO, afterWith);
        // Exception exit: pop, rethrow.
        mv.visitLabel(withHandler);
        for (int i = 0; i < pushedEffects.size(); i++) {
            mv.visitMethodInsn(INVOKESTATIC, RT, "exitWith", "()V", false);
        }
        mv.visitInsn(ATHROW);
        mv.visitTryCatchBlock(withStart, withEnd, withHandler, null);
        mv.visitLabel(afterWith);
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
    private WithBodyShape.Sequence singleOpToSequence(List<Stmt> body,
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

    private List<String> collectHandlerNames(Expr e) {
        List<String> out = new ArrayList<>();
        collectHandlerNamesInto(e, out);
        return out;
    }

    private void collectHandlerNamesInto(Expr e, List<String> out) {
        if (e instanceof Expr.Var v) {
            // If the Var refers to a local-bound handler expression
            // (`combined := h1 >> h2`), recurse into the bound RHS so
            // SM-shape analysis sees the actual handler chain rather
            // than treating the alias as an unknown handler name.
            Expr bound = currentLocalHandlerBindings.get(v.name());
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
    private java.util.Map<String, Expr> scanLocalHandlerBindings(Decl.FnBody body) {
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

    private void scanExprForHandlerBinds(Expr e, java.util.Map<String, Expr> out) {
        if (e instanceof Expr.Block blk) scanStmtsForHandlerBinds(blk.stmts(), out);
    }

    private void scanStmtsForHandlerBinds(List<Stmt> stmts, java.util.Map<String, Expr> out) {
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
    private List<Stmt> expandDestructureBindsForSM(List<Stmt> stmts) {
        List<Stmt> out = new ArrayList<>(stmts.size());
        for (Stmt s : stmts) {
            if (!(s instanceof Stmt.Bind b)) { out.add(s); continue; }
            if (!(b.target() instanceof Stmt.BindTarget.Destructure d)) { out.add(s); continue; }
            Pattern pat = d.pattern();
            List<String> names = simpleVarSequenceFromPattern(pat);
            if (names == null) { out.add(s); continue; }
            // Synthesize: __sm$dest$N := value; name_i := nth i __sm$dest$N
            String tmp = "__sm$dest$" + smDestCounter++;
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

    private int smDestCounter = 0;

    /** Returns the list of simple-var names if {@code pat} is a vector
     *  or tuple of plain {@link Pattern.VarPat}s (no spread, no nested
     *  patterns). Otherwise returns {@code null}. */
    private static List<String> simpleVarSequenceFromPattern(Pattern pat) {
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

    /** True if a declared effect-row should map to RT's AMBIENT frame
     *  (caller's effects flow through unchanged): contains `Any`, or
     *  contains a parametric row-variable (lowercase first char). */
    private static boolean isAmbientRow(java.util.List<String> row) {
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
    private void emitStringArrayConst(MethodVisitor mv, java.util.List<String> row) {
        int n = row == null ? 0 : row.size();
        pushIconst(mv, n);
        mv.visitTypeInsn(ANEWARRAY, "java/lang/String");
        if (row != null) {
            for (int i = 0; i < row.size(); i++) {
                mv.visitInsn(DUP);
                pushIconst(mv, i);
                mv.visitLdcInsn(row.get(i));
                mv.visitInsn(AASTORE);
            }
        }
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
    private Expr.Lambda liftImplBindingToLambda(Decl.ImplBinding b) {
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
            Integer arity = fnArity.get(vr.name());
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
        WithBodyShape.Sequence seq;
        if (shape instanceof WithBodyShape.Sequence s) {
            seq = s;
        } else if (shape instanceof WithBodyShape.SingleOp so) {
            seq = singleOpToSequence(stmts, so);
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

    /** Emit a sequence of statements where the last one supplies the
     *  fn body's return value. Used by ImperativeBody fns and any
     *  if-branch in tail position. Mirrors the interpreter's
     *  execStmtListReturn: the last ExprStmt or With's value bubbles
     *  out; a trailing IfStmt is treated as an if-expression so its
     *  branches' values bubble out too. Anything else (Bind, Assign,
     *  MatchStmt without a value) returns Unit, matching interp. */
    private void emitImperativeTail(List<Stmt> stmts, MethodVisitor mv, Locals locals) {
        if (stmts.isEmpty()) {
            mv.visitInsn(ACONST_NULL);
            emitTailReturn(mv);
            return;
        }
        for (int i = 0; i < stmts.size() - 1; i++) emitStmt(stmts.get(i), mv, locals);
        Stmt last = stmts.get(stmts.size() - 1);
        if (last instanceof Stmt.ExprStmt es) {
            emitTailExpr(es.expr(), mv, locals);
        } else if (last instanceof Stmt.With w) {
            emitWith(w, mv, locals);
            emitTailReturn(mv);
        } else if (last instanceof Stmt.IfStmt ifs) {
            emitTailIfStmt(ifs, mv, locals);
        } else if (last instanceof Stmt.MatchStmt ms) {
            // Match in tail position: emit as expression so each
            // arm's last expression bubbles out.
            emitMatchExpr(new Expr.MatchExpr(ms.scrutinee(), ms.arms(), ms.loc()),
                    mv, locals);
            emitTailReturn(mv);
        } else {
            emitStmt(last, mv, locals);
            mv.visitInsn(ACONST_NULL);
            emitTailReturn(mv);
        }
    }

    /** Emit an IfStmt at tail position. Each branch's last statement
     *  supplies the fn's return value via {@link #emitImperativeTail}. */
    private void emitTailIfStmt(Stmt.IfStmt ifs, MethodVisitor mv, Locals locals) {
        emitExpr(ifs.cond(), mv, locals);
        mv.visitMethodInsn(INVOKESTATIC, RT, "truthy",
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

    /** Lazily declare a static field for a top-level binding. Field
     *  name is mangled to avoid collisions; type is Object. */
    private String ensureTopLevelField(String irijName) {
        return topLevelFields.computeIfAbsent(irijName, n -> {
            String field = "top$" + mangle(n);
            classWriter.visitField(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
                    field, OBJ_DESC, null, null).visitEnd();
            return field;
        });
    }

    /** Build an IrijFn that captures the supplied {@code partialArgs}
     *  and delegates to {@code fnName} once the remaining arity is
     *  filled. Evaluates each partial arg eagerly into a fresh local
     *  (so side-effects fire at the partial-application site, matching
     *  the interpreter's evalApp semantics).
     */
    private void emitPartialApp(String fnName, int arity, List<Expr> partialArgs,
                                 Expr.SourceLoc loc, MethodVisitor mv, Locals outerLocals) {
        // Stash each partial arg in a fresh outer local.
        List<String> captureNames = new ArrayList<>();
        for (int i = 0; i < partialArgs.size(); i++) {
            emitExpr(partialArgs.get(i), mv, outerLocals);
            String name = "$partial$" + (partialCounter++) + "$" + i;
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
            String pn = "$pp$" + partialCounter + "$" + j;
            params.add(new Pattern.VarPat(pn, loc));
            bodyArgs.add(new Expr.Var(pn, loc));
        }
        Expr body = new Expr.App(new Expr.Var(fnName, loc), bodyArgs, loc);
        Expr.Lambda lam = new Expr.Lambda(params, null, body, loc);
        emitLambda(lam, mv, outerLocals);
    }

    private int partialCounter = 0;

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
        // If a clause-required-effects context is pending, push it on
        // RT.EFFECT_ROW for the body. The frame is popped before
        // ARETURN (normal path) and via a catch-all (exception path)
        // so EFFECT_ROW stays balanced across throws.
        java.util.List<String> clauseEffects = pendingClauseEffects;
        pendingClauseEffects = null;
        Label clauseTryStart = null, clauseTryEnd = null, clauseHandler = null;
        if (clauseEffects != null) {
            emitStringArrayConst(lm, clauseEffects);
            lm.visitMethodInsn(INVOKESTATIC, RT, "enterFn", "([Ljava/lang/String;)V", false);
            clauseTryStart = new Label();
            clauseTryEnd = new Label();
            clauseHandler = new Label();
            lm.visitLabel(clauseTryStart);
        }
        emitExpr(lam.body(), lm, inner);
        if (clauseEffects != null) {
            // Normal exit: pop, then return the value left on stack.
            lm.visitMethodInsn(INVOKESTATIC, RT, "exitFn", "()V", false);
        }
        lm.visitInsn(ARETURN);
        if (clauseEffects != null) {
            lm.visitLabel(clauseTryEnd);
            lm.visitLabel(clauseHandler);
            lm.visitMethodInsn(INVOKESTATIC, RT, "exitFn", "()V", false);
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
        // Wrap the raw IrijFn in a CurriedFn that remembers the lambda's
        // arity. Enables partial application (`add 5` returns a function
        // awaiting one more arg) and over-application (curried lambda
        // applied to too many args dispatches the tail). Rest-param
        // lambdas are variadic (arity = -1) — every arg goes straight
        // to the impl, no currying.
        int curryArity = (restName != null) ? -1 : paramNames.size();
        pushIconst(mv, curryArity);
        mv.visitMethodInsn(INVOKESTATIC, RT, "curry",
                "(" + IRIJ_FN_DESC + "I)" + IRIJ_FN_DESC, false);
    }

    /** Walk Expr, collecting names referenced but not bound, that resolve to outer locals. */
    private void collectFreeVars(Expr e, Set<String> bound, Locals outer,
                                  List<String> out, Set<String> seen) {
        if (e == null) return;
        switch (e) {
            case Expr.Var v -> {
                String n = v.name();
                // Top-level (mut) bindings live in static fields. Don't
                // capture them by value — reads and writes inside a
                // lambda must hit the static field directly so updates
                // are visible across threads / scope boundaries.
                if (topLevelFields.containsKey(n)) break;
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
            // Name-resolution priority: user fn > effect op > builtin.
            // A user-declared fn or effect op of the same name as a
            // stdlib builtin (e.g. `div` is vrata.html's element
            // builder; `log` is a typical effect op name) wins. The
            // builtin is only reached when neither shadows it.
            // The fully-qualified Java form (`java.lang.Math/log`)
            // is the escape hatch for raw access to the JVM method.
            boolean shadowed = fnArity.containsKey(fnName)
                    || effectOps.containsKey(fnName);
            if (!shadowed
                    && emitBuiltinApp(fnName, app.args(), mv, locals)) {
                return;
            }
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
            // Top-level mut/let binding hoisted to a static field —
            // e.g. `add5 := add-positive 5` where add5 holds a curried
            // IrijFn. The Var-load inside emitIrijFnCall picks it up
            // via topLevelFields → GETSTATIC.
            if (topLevelFields.containsKey(fnName)) {
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
            // Namespace-mode (nREPL eval-bytecode): unknown user-fn
            // names may refer to fns defined in a previous eval. Fall
            // through to nsGet(name) → IrijFn → callAny. The IrijFn
            // wrapper was registered in that earlier eval's clinit.
            if (options.namespaceMode()) {
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
