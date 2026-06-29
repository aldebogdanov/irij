package dev.irij.compiler;

import dev.irij.ast.AstBuilder;
import dev.irij.ast.Decl;
import dev.irij.ast.Expr;
import dev.irij.ast.Stmt;
import dev.irij.parser.IrijParseDriver;

import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stateful bytecode-eval session — successive {@link #eval} calls share
 * top-level bindings via a per-session namespace map (see
 * {@link RuntimeSupport#NS}) and a per-session {@link ClassLoader} so
 * synthesized class names don't collide across evals.
 *
 * <p>Used by every REPL-shaped consumer (interactive REPL, nREPL,
 * MCP, Playground sandboxed sessions) so they share one execution
 * path instead of each carrying their own.
 *
 * <p>The session captures the value of the last top-level expression
 * in each eval into the namespace under the synthetic key
 * {@value #LAST_VALUE_KEY}. Callers that want a REPL-style "; ⇒
 * value" display read it via {@link #lastValue()} after each
 * {@link #eval} call. Programs that produce no top-level expression
 * (e.g. just function declarations) leave the previous value
 * unchanged; check the key directly if you need to distinguish.
 *
 * <p>Thread-safety: a single session is intended to be driven from
 * one thread; concurrent {@code eval} calls on the same session are
 * unsafe (the underlying {@code RuntimeSupport.NS} ThreadLocal is set
 * for the duration of each call).
 */
public final class BytecodeSession {

    /** Namespace key under which the last top-level expression's
     *  value is stored after each {@link #eval}. */
    public static final String LAST_VALUE_KEY = "__irij$last_value";

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private final Map<String, Object> namespace = new LinkedHashMap<>();
    private final Loader loader = new Loader();
    private final String classPrefix;

    public BytecodeSession() {
        this("irij.Session");
    }

    public BytecodeSession(String classPrefix) {
        this.classPrefix = classPrefix;
    }

    /** The shared namespace map this session writes top-level binds
     *  into. Inspectable from outside (e.g. REPL :env command). */
    public Map<String, Object> namespace() {
        return namespace;
    }

    /** The value of the last top-level expression in the most recent
     *  {@link #eval}. {@code null} if no eval has produced one yet —
     *  programs whose only top-level decls are fns / handlers /
     *  bindings leave this slot at whatever the previous eval set,
     *  so prefer checking {@link #namespace()} directly for
     *  per-eval freshness. */
    public Object lastValue() {
        return namespace.get(LAST_VALUE_KEY);
    }

    /**
     * Compile {@code source} with namespace mode + state-machine handler
     * lowering and invoke its {@code main()}.
     *
     * <p>The parsed AST is rewritten so the last top-level expression
     * (if any) writes its value into the namespace under
     * {@link #LAST_VALUE_KEY}, available afterwards via
     * {@link #lastValue()}.
     *
     * @throws IrijCompiler.CompileException on compile failure
     * @throws RuntimeException wrapping any runtime failure
     */
    public void eval(String source, String fileLabel, PrintStream captureOut) {
        var parsed = IrijParseDriver.parse(source);
        if (parsed.hasErrors()) {
            throw new IrijCompiler.CompileException(
                    "Parse errors:\n" + String.join("\n", parsed.errors()));
        }
        List<Decl> decls = new AstBuilder().build(parsed.tree());
        decls = captureLastExpression(decls);

        CompileOptions opts = CompileOptions.defaults().withNamespaceMode(true);
        String className = classPrefix + "$" + COUNTER.incrementAndGet();
        java.util.Map<String, byte[]> classes = IrijCompiler.compileDeclsMulti(
                decls, className, null, opts,
                java.util.List.of(),
                fileLabel != null ? fileLabel : (className + ".irj"));
        Class<?> cls = loader.defineAll(classes, className);

        // Install the session's namespace + (optional) session
        // PrintStream on this thread. SESSION_OUT routes println /
        // print and is inherited by spawned virtual threads via
        // ParentSnapshot — so a fork's stdout still hits the session
        // buffer even after the synchronous eval returns. Restored
        // in finally so we never leak per-session state.
        var prevNS = RuntimeSupport.NS.get();
        var prevSessionOut = RuntimeSupport.SESSION_OUT.get();
        RuntimeSupport.NS.set(namespace);
        if (captureOut != null) RuntimeSupport.SESSION_OUT.set(captureOut);
        PrintStream prevOut = System.out;
        if (captureOut != null) System.setOut(captureOut);
        try {
            Method main = cls.getMethod("main", String[].class);
            main.invoke(null, (Object) new String[0]);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        } finally {
            if (captureOut != null) System.setOut(prevOut);
            RuntimeSupport.SESSION_OUT.set(prevSessionOut);
            RuntimeSupport.NS.set(prevNS);
        }
    }

    /** Rewrite the last top-level {@link Decl.ExprDecl} so its value
     *  is bound to {@link #LAST_VALUE_KEY} (which, in namespace mode,
     *  the emitter routes through {@code RT.nsPut}). Leaves the
     *  rest of the decl list untouched. Returns the input list
     *  unchanged if no trailing ExprDecl exists. */
    private static List<Decl> captureLastExpression(List<Decl> decls) {
        int lastExprIdx = -1;
        for (int i = decls.size() - 1; i >= 0; i--) {
            if (decls.get(i) instanceof Decl.ExprDecl) { lastExprIdx = i; break; }
        }
        if (lastExprIdx < 0) return decls;
        List<Decl> out = new ArrayList<>(decls);
        Decl.ExprDecl ed = (Decl.ExprDecl) out.get(lastExprIdx);
        Stmt bind = new Stmt.Bind(
                new Stmt.BindTarget.Simple(LAST_VALUE_KEY),
                ed.expr(), null, ed.loc());
        out.set(lastExprIdx, new Decl.BindingDecl(bind, ed.loc()));
        return out;
    }

    /** Reset session state: drop all top-level bindings, but keep the
     *  classloader so previously-compiled classes stay loaded (they're
     *  unreachable from the namespace anyway and the GC will reclaim
     *  them with the loader if the session is discarded). */
    public void reset() {
        namespace.clear();
    }

    private static final class Loader extends ClassLoader {
        Loader() { super(BytecodeSession.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }

        /** Define every emitted class, return the main one. See
         *  {@code BytecodeRunner.BytesLoader.defineAll}. */
        Class<?> defineAll(java.util.Map<String, byte[]> classes, String mainName) {
            Class<?> main = null;
            for (var e : classes.entrySet()) {
                Class<?> c = defineClass(e.getKey(), e.getValue(), 0, e.getValue().length);
                if (e.getKey().equals(mainName)) main = c;
            }
            return main;
        }
    }
}
