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

    static final String RT = "dev/irij/compiler/RuntimeSupport";
    static final String VALUES = "dev/irij/runtime/Values";
    static final String OBJ = "java/lang/Object";
    static final String OBJ_DESC = "Ljava/lang/Object;";
    static final String BINOP_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
    static final String CMPOP_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Z";

    static final String IRIJ_FN = "dev/irij/compiler/RuntimeSupport$IrijFn";
    static final String IRIJ_FN_DESC = "Ldev/irij/compiler/RuntimeSupport$IrijFn;";
    static final String APPLY_DESC = "([Ljava/lang/Object;)Ljava/lang/Object;";

    final String internalName;
    final Map<String, Integer> fnArity = new HashMap<>();
    /** Each user fn's declared effect row (null = unannotated). Used
     *  to emit caller-side subsumption checks at INVOKESTATIC sites. */
    final Map<String, List<String>> fnEffectRow = new HashMap<>();
    final Map<String, List<String>> productFields = new HashMap<>();
    /** Top-level `:=` bindings get hoisted to static fields on the
     *  emitted class so user-fn methods can read them (interpreter
     *  semantics: globalEnv lookup). Maps Irij name → JVM field name.
     *  Populated lazily on first emitTopLevel BindingDecl write. */
    final Map<String, String> topLevelFields = new HashMap<>();
    /** Sum-spec variants, kept in declaration order for deterministic
     *  emission. Each entry maps {@code variantName → arity}. */
    final Map<String, LinkedHashMap<String, Integer>> sumVariants = new LinkedHashMap<>();
    /** Reverse lookup: variant/product tag → enclosing spec name. Used
     *  by {@link #emitConstructorApp} to certify Tagged values with
     *  their specName so {@link SpecValidator}'s O(1) fast-path
     *  triggers (matches Interpreter behaviour). */
    final Map<String, String> tagToSpec = new HashMap<>();
    // method name → (forType → lambda expr)
    final Map<String, Map<String, Expr.Lambda>> protoImpls = new HashMap<>();
    // method name → arity
    final Map<String, Integer> protoArity = new HashMap<>();
    // effect op name → effect name
    final Map<String, String> effectOps = new HashMap<>();
    /** Capability registry: cap-name → provider-class FQN. Populated in
     *  pass 1 from {@link Decl.CapDecl}. Consumed at {@link Expr.App} emit
     *  time: a call shaped {@code cap-name.method args} is rewritten to
     *  {@code "FQN/method"} and dispatched through the existing JavaInterop
     *  reflection path (handles static + instance methods uniformly). */
    final Map<String, String> capProvider = new HashMap<>();

    /** Phase 3 — Irij-record cap state. Maps cap-name → JVM static
     *  field that holds the evaluated record; parallel map holds the
     *  record-builder expression so clinit can materialise it. */
    final Map<String, String> recordCapField = new HashMap<>();
    final Map<String, Expr> recordCapExpr = new HashMap<>();
    // handler name → decl (14c.1 abort-only)
    final Map<String, Decl.HandlerDecl> handlers = new HashMap<>();
    // handlerName -> (stateVarName -> internal static field name)
    final Map<String, Map<String, String>> handlerStateFields = new HashMap<>();
    // state field name -> JVM descriptor (always OBJ_DESC here)
    Map<String, String> currentStateFields = Map.of();
    // 14c.3 SM lowering: name -> continuation fields[] index; kSlot holds cont.
    Map<String, Integer> currentLiftedLocals = Map.of();
    int currentKSlot = -1;
    boolean currentFnPushesEffects = false;
    /** Local-var name → handler expression it was bound to. Populated
     *  by {@link #scanLocalHandlerBindings} at fn-emit time so
     *  {@link #collectHandlerNamesInto} can resolve a {@code with
     *  combined} where {@code combined := h1 >> h2} was bound earlier
     *  in the same fn. Without this, the SM lowering's handler-shape
     *  analysis gives up on local-var aliasing of composed handlers. */
    java.util.Map<String, Expr> currentLocalHandlerBindings = java.util.Map.of();
    /** Names that have a hard-wired bytecode constant (Math.PI, Math.E,
     *  Function.identity, etc.). When a top-level binding shadows one
     *  of these, the binding's INITIALIZER must read the constant
     *  rather than the (uninitialized) static field. Lookups outside
     *  initialization fall through to topLevelFields. */
    static final java.util.Set<String> BUILTIN_CONST_NAMES = java.util.Set.of(
            "pi", "e", "identity", "const",
            "length", "head", "tail", "empty?", "to-str", "not", "type-of",
            "abs", "sqrt", "floor", "ceil", "round", "reverse", "sort",
            "println", "print");
    /** Set immediately before {@link #emitLambda} for a handler clause:
     *  the clause body should run with these effects pushed on
     *  RT.EFFECT_ROW so its perform/builtin calls succeed (mirrors the
     *  interpreter's {@code AVAILABLE_EFFECTS.push(requiredEffects)}). */
    java.util.List<String> pendingClauseEffects = null;
    ClassWriter classWriter;
    int lambdaCounter = 0;

    // Self-tail-call optimization scratch. While emitting a top-level fn
    // body, these point at the in-flight method so a self-recursive call
    // in tail position can be lowered to a GOTO back to the method entry
    // (re-binding param slots in place) instead of an INVOKESTATIC.
    String currentFnName = null;
    int currentFnArity = 0;
    Label currentFnEntry = null;
    /** Output spec for the fn currently being emitted. When non-null,
     *  every tail-return site validates against this before ARETURN.
     *  Pushed/popped around {@link #emitFn} so lambdas (which build
     *  their own methods) don't inherit the outer fn's output spec. */
    String currentOutputSpec = null;

    /** Post-condition slots (each holds a compiled post-lambda
     *  IrijFn) for the surrounding fn. Each {@link #emitTailReturn}
     *  applies them to the about-to-return value before output-spec
     *  validation. Empty list when the fn has no posts.
     *  Outer-fn slots are saved/restored around {@link #emitFn}. */
    List<Integer> currentPostSlots = List.of();
    /** Temporary slot used to stash the result while running post
     *  checks. -1 when no posts. */
    int currentPostTempSlot = -1;
    /** Fail-blame text for each post slot (so out-contracts and
     *  post-conditions distinguish in error output). Aligned with
     *  {@link #currentPostSlots}. */
    List<String> currentPostBlame = List.of();

    // For each user fn `f` referenced as a value (not at an App call
    // site), we synthesise a single IrijFn-shape wrapper method
    // `f$irijfn([Ljava/lang/Object;)Ljava/lang/Object;` that unpacks args
    // and forwards to the real `f`. Tracked here so each fn gets at
    // most one wrapper per class.
    final Set<String> emittedFnWrappers = new HashSet<>();

    final Set<String> moduleAliases;
    final CompileOptions options;

    // ── Emitter modules (split from this class, PR1 2026-07) ──
    final FnEmitter fnEm = new FnEmitter(this);
    final ExprEmitter exprEm = new ExprEmitter(this);
    final PatternEmitter patEm = new PatternEmitter(this);
    final LambdaEmitter lamEm = new LambdaEmitter(this);
    final IntrinsicsEmitter intrEm = new IntrinsicsEmitter(this);
    final EffectEmitter effEm = new EffectEmitter(this);
    final SmClassifier smCls = new SmClassifier(this);
    final SmEmitter smEm = new SmEmitter(this);
    final ProtoEmitter protoEm = new ProtoEmitter(this);

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
        this(className, moduleAliases, options, sourceFile, Map.of());
    }

    ClassEmitter(String className, Set<String> moduleAliases,
                  CompileOptions options, String sourceFile,
                  Map<String, String> fnFile) {
        this.binaryName = className;
        this.internalName = className.replace('.', '/');
        this.moduleAliases = moduleAliases;
        this.options = options;
        // Default to a synthesized name so JVM stack traces show
        // "Program.irj" instead of "Unknown Source" when the build
        // path didn't pass a real filename through.
        this.sourceFile = sourceFile != null ? sourceFile
                : (className.substring(className.lastIndexOf('.') + 1) + ".irj");
        this.fnFile = fnFile;
    }

    /** fn name → source file (from {@link ModuleInliner}). Drives the
     *  per-source-file class split: fns sharing a file land in one
     *  class whose {@code SourceFile} is that file, so JVM stack
     *  frames name the right module instead of the root program. */
    final Map<String, String> fnFile;

    /** Lazily-created per-source-file ClassWriters, keyed by the
     *  owner class's internal name. The root program's writer is the
     *  main {@code classWriter}; module files get extra writers. */
    final Map<String, ClassWriter> fileWriters = new java.util.LinkedHashMap<>();

    /** Internal name of the class that owns a given source file's fns.
     *  Root file → main class; module file → {@code <main>$<sanitized>}. */
    String classForFile(String file) {
        if (file == null || file.equals(sourceFile)) return internalName;
        return internalName + "$" + sanitizeFilePart(file);
    }

    /** Internal name of the class that owns {@code fnName}'s method.
     *  Falls back to the main class for unknown names (lambdas,
     *  builtins, fns with no recorded origin). */
    String ownerOf(String fnName) {
        String f = fnFile.get(fnName);
        return f == null ? internalName : classForFile(f);
    }

    static String sanitizeFilePart(String file) {
        String base = file.endsWith(".irj") ? file.substring(0, file.length() - 4) : file;
        return base.replaceAll("[^A-Za-z0-9]", "_");
    }

    /** The ClassWriter a named fn's method body belongs to: the root
     *  {@code classWriter} for root-program fns, or a lazily-created
     *  per-module-file writer (with its own {@code SourceFile}) for
     *  inlined module fns. Synthetic methods (lambdas, SM steps,
     *  wrappers) always stay on the root writer regardless. */
    ClassWriter writerForFn(Decl.FnDecl fn) {
        String file = fnFile.get(fn.name());
        String owner = classForFile(file);
        if (owner.equals(internalName)) return classWriter;
        return fileWriters.computeIfAbsent(owner, o -> {
            ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            w.visit(V21, ACC_PUBLIC | ACC_FINAL, o, null, OBJ, null);
            w.visitSource(file, null);
            return w;
        });
    }

    /** Dotted binary name of the main class (e.g. {@code irij.Program}).
     *  Key used in the multi-class emission map; what
     *  {@code ClassLoader.defineClass} expects. */
    final String binaryName;

    /** Multi-class program emission. Returns a map from each emitted
     *  class's dotted binary name to its bytes. The caller (a loader)
     *  must define every entry, then resolve {@code main} on the
     *  {@code binaryName} class.
     *
     *  <p>Today this returns a single entry — the whole program is one
     *  class. Stage B of the multi-class refactor splits inlined module
     *  functions into per-source-file classes so JVM stack frames carry
     *  the right {@code SourceFile}; from then on the map has N entries.
     *  Loaders that go through this method need no further change when
     *  that lands. */
    Map<String, byte[]> emitProgram(List<Decl> decls) {
        byte[] rootBytes = emit(decls);   // also populates fileWriters
        Map<String, byte[]> out = new java.util.LinkedHashMap<>();
        out.put(binaryName, rootBytes);
        // Per-module-file classes, keyed by dotted binary name.
        for (var e : fileWriters.entrySet()) {
            String dotted = e.getKey().replace('/', '.');
            out.put(dotted, e.getValue().toByteArray());
        }
        return out;
    }

    /** Irij source filename for the JVM SourceFile attribute. Set
     *  once at construction; appears in every stack frame from the
     *  emitted class as {@code at irij.Program.main(server.irj:42)}. */
    final String sourceFile;

    /** Emit a JVM LineNumber attribute mapping the next instruction
     *  to {@code loc}'s Irij source line. Skips zero/negative lines
     *  (synthetic / unknown). One attribute per Expr/Stmt entry is
     *  acceptable; ASM compacts duplicates per LineNumberTable. */
    void emitLine(org.objectweb.asm.MethodVisitor mv, Node.SourceLoc loc) {
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
    static Node.SourceLoc locOf(Object node) {
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
            if (inner instanceof Decl.CapDecl cd) {
                if (cd.isRecord()) {
                    // Phase 3: Irij-record cap. Reserve a static field
                    // for the evaluated record; populate at clinit; use
                    // sites do GETSTATIC + get(field) + callAny.
                    String fieldName = "cap$" + mangle(cd.name());
                    cw.visitField(ACC_STATIC | ACC_SYNTHETIC,
                            fieldName, OBJ_DESC, null, null).visitEnd();
                    recordCapField.put(cd.name(), fieldName);
                    recordCapExpr.put(cd.name(), cd.recordExpr());
                } else {
                    capProvider.put(cd.name(), cd.providerClass());
                }
            }
            if (inner instanceof Decl.HandlerDecl hd) {
                effEm.validateHandler14c2(hd);
                handlers.put(hd.name(), hd);
                if (!hd.stateBindings().isEmpty()) {
                    Map<String, String> fields = new LinkedHashMap<>();
                    for (Stmt sb : hd.stateBindings()) {
                        String stateName = effEm.stateBindingName(hd.name(), sb);
                        String fieldName = "handler$" + mangle(hd.name()) + "$state$" + mangle(stateName);
                        fields.put(stateName, fieldName);
                        cw.visitField(ACC_STATIC | ACC_SYNTHETIC,
                                fieldName, OBJ_DESC, null, null).visitEnd();
                    }
                    handlerStateFields.put(hd.name(), fields);
                }
            }
            if (inner instanceof Decl.ImplDecl id) {
                for (Decl.ImplBinding b : id.bindings()) {
                    Expr.Lambda lam = protoEm.liftImplBindingToLambda(b);
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
        for (Decl.FnDecl fn : uniqueFns.values()) fnEm.emitFn(fn, writerForFn(fn));

        // Pass 2b: emit impl methods + protocol dispatchers.
        for (var entry : protoImpls.entrySet()) {
            String method = entry.getKey();
            for (var impl : entry.getValue().entrySet()) {
                protoEm.emitImplMethod(method, impl.getKey(), impl.getValue(), cw);
            }
            protoEm.emitProtoDispatcher(method, entry.getValue().keySet(), protoArity.get(method), cw);
        }

        // Pass 2c: emit handler builder methods (14c.2).
        for (Decl.HandlerDecl h : handlers.values()) {
            effEm.emitHandlerBuilder(h, cw);
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
                || !recordCapField.isEmpty()
                || (options.namespaceMode() && !fnArity.isEmpty())) {
            emitClinit(cw);
        }

        cw.visitEnd();
        // Finalize any per-module-file classes spun up by writerForFn.
        for (ClassWriter w : fileWriters.values()) w.visitEnd();
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
    void emitClinit(ClassWriter cw) {
        MethodVisitor cl = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        cl.visitCode();
        if (options.namespaceMode()) {
            for (var e : fnArity.entrySet()) {
                String fnName = e.getKey();
                int arity = e.getValue();
                fnEm.ensureUserFnWrapper(fnName, arity);
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
                        fnEm.userFnWrapperName(fnName), APPLY_DESC, false);
                cl.visitInvokeDynamicInsn("apply", "()" + IRIJ_FN_DESC, bsm,
                        samType, implHandle, samType);
                cl.visitMethodInsn(INVOKESTATIC, RtOwners.of("nsPut"), "nsPut",
                        "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false);
                cl.visitInsn(POP);
            }
        }
        for (var e : productFields.entrySet()) {
            cl.visitLdcInsn(e.getKey());
            // String[] fields
            exprEm.pushIconst(cl, e.getValue().size());
            cl.visitTypeInsn(ANEWARRAY, "java/lang/String");
            int i = 0;
            for (String f : e.getValue()) {
                cl.visitInsn(DUP);
                exprEm.pushIconst(cl, i++);
                cl.visitLdcInsn(f);
                cl.visitInsn(AASTORE);
            }
            cl.visitMethodInsn(INVOKESTATIC, SPEC_VALIDATOR, "registerProduct",
                    "(Ljava/lang/String;[Ljava/lang/String;)V", false);
        }
        for (var e : sumVariants.entrySet()) {
            cl.visitLdcInsn(e.getKey());
            // Object[] {name, arity, name, arity, ...}
            exprEm.pushIconst(cl, e.getValue().size() * 2);
            cl.visitTypeInsn(ANEWARRAY, OBJ);
            int i = 0;
            for (var v : e.getValue().entrySet()) {
                cl.visitInsn(DUP);
                exprEm.pushIconst(cl, i++);
                cl.visitLdcInsn(v.getKey());
                cl.visitInsn(AASTORE);
                cl.visitInsn(DUP);
                exprEm.pushIconst(cl, i++);
                // Box arity as java.lang.Integer
                cl.visitLdcInsn(v.getValue());
                cl.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf",
                        "(I)Ljava/lang/Integer;", false);
                cl.visitInsn(AASTORE);
            }
            cl.visitMethodInsn(INVOKESTATIC, SPEC_VALIDATOR, "registerSum",
                    "(Ljava/lang/String;[Ljava/lang/Object;)V", false);
        }
        // Phase 3 — materialise every Irij-record cap once at class-load
        // time. Each cap's recordExpr (a map-literal Expr) is evaluated
        // inside clinit with a throwaway Locals frame and PUTSTATIC'd
        // into the cap's reserved field. Subsequent `cap-name.method
        // args` use-sites do GETSTATIC + getOp(field) + callAny(args).
        if (!recordCapField.isEmpty()) {
            Locals clinitLocals = new Locals();
            for (var e : recordCapField.entrySet()) {
                exprEm.emitExpr(recordCapExpr.get(e.getKey()), cl, clinitLocals);
                cl.visitFieldInsn(PUTSTATIC, internalName,
                        e.getValue(), OBJ_DESC);
            }
        }
        cl.visitInsn(RETURN);
        cl.visitMaxs(0, 0);
        cl.visitEnd();
    }

    static final String SPEC_VALIDATOR = "dev/irij/compiler/SpecValidator";

    static Decl.FnDecl asFnDecl(Decl d) {
        if (d instanceof Decl.FnDecl fn) return fn;
        if (d instanceof Decl.PubDecl pd && pd.inner() instanceof Decl.FnDecl fn) return fn;
        return null;
    }

    static List<Pattern> fnParams(Decl.FnDecl fn) {
        return switch (fn.body()) {
            case Decl.FnBody.LambdaBody lb -> lb.params();
            case Decl.FnBody.ImperativeBody ib -> ib.params();
            case Decl.FnBody.MatchArmsBody mab -> List.of(new Pattern.VarPat("$scrut", null));
            default -> List.of();
        };
    }

    /** Mangle Irij kebab-case names to JVM-safe identifiers. */
    static String mangle(String name) {
        return name.replace("-", "_").replace("?", "$q").replace("!", "$b");
    }

    // ── Top-level ──────────────────────────────────────────────────────

    void emitTopLevel(Decl d, MethodVisitor mv, Locals locals) {
        switch (d) {
            case Decl.ExprDecl ed -> exprEm.emitStmtExpr(ed.expr(), mv, locals);
            case Decl.BindingDecl bd -> {
                // Top-level binds with a simple target also get hoisted
                // to a static field so user-fns can read them (mirrors
                // the interpreter's globalEnv lookup). The original
                // local-slot store still happens (via emitStmt) so the
                // rest of main()'s code sees the binding.
                exprEm.emitStmt(bd.stmt(), mv, locals);
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
                            mv.visitMethodInsn(INVOKESTATIC, RtOwners.of("nsPut"), "nsPut",
                                    "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false);
                            mv.visitInsn(POP);
                        }
                    }
                }
            }
            case Decl.IfDecl id -> exprEm.emitStmt(id.ifStmt(), mv, locals);
            case Decl.MatchDecl md -> exprEm.emitStmt(md.match(), mv, locals);
            case Decl.SpecDecl __ -> { /* structural only; constructors resolved via TypeRef */ }
            case Decl.ProtoDecl __ -> { /* no runtime rep; methods go through dispatchers */ }
            case Decl.ImplDecl __ -> { /* bindings hoisted to static methods in pass 2b */ }
            case Decl.EffectDecl __ -> { /* ops registered in pass 1 */ }
            case Decl.CapDecl __ -> { /* compile-time only; phase 2 wires cap.method dispatch */ }
            case Decl.ModDecl __ -> { /* preserved by inliner for EffectRowChecker; emitter skips */ }
            case Decl.HandlerDecl hd -> effEm.emitHandlerStateInit(hd, mv, locals);
            case Decl.WithDecl wd -> exprEm.emitStmt(wd.with(), mv, locals);
            case Decl.ScopeDecl sd -> {
                exprEm.emitScope(sd.scope(), mv, locals);
                mv.visitInsn(POP);
            }
            default -> throw new IrijCompiler.CompileException(
                    "MVP: unsupported top-level decl: " + d.getClass().getSimpleName());
        }
    }

    /** Returns true if the call was emitted as a built-in. */
    /** Builtins that have an associated effect (mirrors {@code BuiltinFn.requiredEffects}
     *  in {@link dev.irij.runtime.Builtins}). Emitting one of these triggers a
     *  {@code RT.checkPerformEffect} so the enclosing fn's row honors the requirement. */
    static final java.util.Map<String, String> BUILTIN_EFFECT = java.util.Map.ofEntries(
        java.util.Map.entry("print",            "Console"),
        java.util.Map.entry("println",          "Console"),
        java.util.Map.entry("dbg",              "Console"),
        java.util.Map.entry("read-line",        "Console"),
        // FileIO entries removed phase 3d (FsCapability path)
        // raw-db-* entries removed phase 3a (JdbcCapability path)
        java.util.Map.entry("sleep",            "Time"),
        java.util.Map.entry("now-ms",           "Time"),
        java.util.Map.entry("random-int",       "Random"),
        java.util.Map.entry("random-float",     "Random"),
        java.util.Map.entry("random-token",     "Random")
    );

    static final String SESSIONS = "dev/irij/compiler/RuntimeSessions";

    // ── Scope { fork ... } ─────────────────────────────────────────────

    static final String SCOPE_HANDLE =
            "dev/irij/compiler/CompiledScopeHandle";

    static final String COMP_HANDLER = "dev/irij/compiler/CompiledHandler";
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

    static final String CONT = "dev/irij/compiler/IrijContinuation";
    static final String CONT_DESC = "Ldev/irij/compiler/IrijContinuation;";
    static final String PERF_SIGNAL = "dev/irij/compiler/PerformSignal";
    static final String TAIL_RESUME = "dev/irij/compiler/TailResume";

    int smDestCounter = 0;

    /** Lazily declare a static field for a top-level binding. Field
     *  name is mangled to avoid collisions; type is Object. */
    String ensureTopLevelField(String irijName) {
        return topLevelFields.computeIfAbsent(irijName, n -> {
            String field = "top$" + mangle(n);
            classWriter.visitField(ACC_STATIC | ACC_SYNTHETIC,
                    field, OBJ_DESC, null, null).visitEnd();
            return field;
        });
    }

    int partialCounter = 0;
}
