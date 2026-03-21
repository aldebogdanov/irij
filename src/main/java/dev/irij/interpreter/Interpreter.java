package dev.irij.interpreter;

import dev.irij.ast.*;
import dev.irij.ast.Node.SourceLoc;
import dev.irij.interpreter.Values.*;

import dev.irij.module.ModuleRegistry;
import dev.irij.module.StdModules;
import dev.irij.parser.IrijParseDriver;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.*;

/**
 * Tree-walk interpreter for Irij AST.
 * Evaluates expressions, executes statements, and processes declarations.
 */
public final class Interpreter {

    private final Environment globalEnv;
    private final PrintStream out;
    private final ModuleRegistry moduleRegistry;

    // Module loading state
    private String currentModuleName;
    private Set<String> pubNames; // non-null only during module file loading

    public Interpreter(PrintStream out) {
        this.out = out;
        this.globalEnv = new Environment();
        Builtins.install(globalEnv, out);
        installInterpreterBuiltins();
        this.moduleRegistry = new ModuleRegistry(this::loadModuleSource);
        StdModules.registerAll(moduleRegistry, out);
    }

    /** Builtins that need access to the interpreter's apply(). */
    private void installInterpreterBuiltins() {
        // ── spawn — run a thunk in a virtual thread ──────────────────
        globalEnv.define("spawn", new BuiltinFn("spawn", 1, args -> {
            var thunk = args.get(0);
            var thread = Thread.startVirtualThread(() -> {
                try {
                    apply(thunk, List.of(), SourceLoc.UNKNOWN);
                } catch (Exception e) {
                    out.println("[spawn] error: " + e.getMessage());
                }
            });
            return thread;
        }));

        // ── try — run a thunk, catch errors ────────────────────────────
        globalEnv.define("try", new BuiltinFn("try", 1, args -> {
            var thunk = args.get(0);
            try {
                var result = apply(thunk, List.of(), SourceLoc.UNKNOWN);
                return new Tagged("Ok", List.of(result));
            } catch (IrijRuntimeError e) {
                return new Tagged("Err", List.of(e.getMessage()));
            }
        }));

        // ── fold — left fold over a collection ────────────────────────
        globalEnv.define("fold", new BuiltinFn("fold", 3, args -> {
            var fn = args.get(0);
            var init = args.get(1);
            var coll = args.get(2);
            var list = Builtins.toList(coll);
            Object acc = init;
            for (var elem : list) {
                acc = apply(fn, List.of(acc, elem), SourceLoc.UNKNOWN);
            }
            return acc;
        }));
    }

    public Interpreter() {
        this(System.out);
    }

    /** Set the source root for file-based module resolution. */
    public void setSourcePath(Path sourcePath) {
        moduleRegistry.setSourcePath(sourcePath);
    }

    /** Get the module registry (for testing). */
    public ModuleRegistry getModuleRegistry() {
        return moduleRegistry;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Scope-aware define
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Define a binding using VarCell at the global scope (supports hot
     * redefinition) or ImmutableCell in local scopes.
     */
    private void defineInScope(Environment env, String name, Object value) {
        if (env == globalEnv) {
            env.defineVar(name, value);
        } else {
            env.define(name, value);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Entry Point
    // ═══════════════════════════════════════════════════════════════════

    /** Run a program (list of top-level declarations). */
    public Object run(List<Decl> program) {
        return run(program, globalEnv);
    }

    /** Run a program in a specific environment (used for module loading). */
    Object run(List<Decl> program, Environment env) {
        Object lastValue = Values.UNIT;
        for (var decl : program) {
            lastValue = execDecl(decl, env);
        }
        return lastValue;
    }

    /** Get the global environment (for testing). */
    public Environment getGlobalEnv() {
        return globalEnv;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Declarations
    // ═══════════════════════════════════════════════════════════════════

    private Object execDecl(Decl decl, Environment env) {
        return switch (decl) {
            case Decl.FnDecl fn -> {
                var value = makeFnValue(fn, env);
                defineInScope(env, fn.name(), value);
                yield value;
            }
            case Decl.TypeDecl td -> {
                registerTypeConstructors(td, env);
                yield "<type " + td.name() + ">";
            }
            case Decl.NewtypeDecl nt -> {
                var ctor = new Constructor(nt.name(), 1);
                defineInScope(env, nt.name(), ctor);
                yield ctor;
            }
            case Decl.BindingDecl bd -> {
                exec(bd.stmt(), env);
                // Return the bound value so nREPL/REPL can display it
                yield switch (bd.stmt()) {
                    case Stmt.Bind b when b.target() instanceof Stmt.BindTarget.Simple(String name) ->
                        env.lookup(name);
                    case Stmt.MutBind mb when mb.target() instanceof Stmt.BindTarget.Simple(String name) ->
                        env.lookup(name);
                    default -> Values.UNIT;
                };
            }
            case Decl.ExprDecl ed -> eval(ed.expr(), env);
            case Decl.MatchDecl md -> {
                exec(md.match(), env);
                yield Values.UNIT;
            }
            case Decl.IfDecl id -> {
                exec(id.ifStmt(), env);
                yield Values.UNIT;
            }
            case Decl.WithDecl wd -> execWith(wd.with(), env);
            case Decl.ScopeDecl sd -> {
                exec(sd.scope(), env);
                yield Values.UNIT;
            }
            case Decl.ModDecl md -> evalModDecl(md);
            case Decl.UseDecl ud -> evalUseDecl(ud, env);
            case Decl.PubDecl pd -> evalPubDecl(pd, env);
            case Decl.EffectDecl ed -> evalEffectDecl(ed, env);
            case Decl.HandlerDecl hd -> evalHandlerDecl(hd, env);
            case Decl.RoleDecl rd -> Values.UNIT; // stub
            case Decl.StubDecl sd -> Values.UNIT; // stub
        };
    }

    private Object makeFnValue(Decl.FnDecl fn, Environment env) {
        return switch (fn.body()) {
            case Decl.FnBody.LambdaBody lb ->
                new Lambda(lb.params(), lb.body(), env, fn.name());
            case Decl.FnBody.MatchArmsBody mab ->
                new MatchFn(fn.name(), mab.arms(), env);
            case Decl.FnBody.ImperativeBody ib ->
                new ImperativeFn(fn.name(), ib.params(), ib.stmts(), env);
            case Decl.FnBody.NoBody() ->
                Values.UNIT; // type-only declaration
        };
    }

    /** A match-arm function: tries each arm against the argument. */
    record MatchFn(String name, List<Expr.MatchArm> arms, Environment closure) {
        @Override
        public String toString() { return "<fn " + name + ">"; }
    }

    /** An imperative-block function: binds params then executes statements. */
    record ImperativeFn(String name, List<Pattern> params, List<Stmt> body, Environment closure) {
        @Override
        public String toString() { return "<fn " + name + ">"; }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Type Constructors
    // ═══════════════════════════════════════════════════════════════════

    private void registerTypeConstructors(Decl.TypeDecl td, Environment env) {
        switch (td.body()) {
            case Decl.TypeBody.SumType st -> {
                for (var variant : st.variants()) {
                    if (variant.arity() == 0) {
                        // Zero-arg constructor: a value, not a function
                        defineInScope(env, variant.name(), new Tagged(variant.name(), List.of()));
                    } else {
                        defineInScope(env, variant.name(), new Constructor(variant.name(), variant.arity()));
                    }
                }
            }
            case Decl.TypeBody.ProductType pt -> {
                // Product type: constructor takes all fields as positional args,
                // but also records field names for named access & destructuring
                var fieldNames = pt.fields().stream().map(Decl.Field::name).toList();
                defineInScope(env, td.name(), new Constructor(td.name(), fieldNames.size(), fieldNames));
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Effects & Handlers
    // ═══════════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════════
    // Module system
    // ═══════════════════════════════════════════════════════════════════

    private Object evalModDecl(Decl.ModDecl md) {
        currentModuleName = md.qualifiedName();
        return Values.UNIT;
    }

    private Object evalUseDecl(Decl.UseDecl ud, Environment env) {
        var mod = moduleRegistry.resolve(ud.qualifiedName(), ud.loc());
        // Extract short name (last segment after final dot)
        var parts = ud.qualifiedName().split("\\.");
        var shortName = parts[parts.length - 1];

        if (ud.modifier() == null) {
            // Qualified: bind short name → ModuleValue (dot-access only)
            defineInScope(env, shortName, mod);
        } else if (ud.modifier() instanceof Decl.UseModifier.Open) {
            // :open — bind short name AND copy all exports
            defineInScope(env, shortName, mod);
            env.copyAllFrom(mod.exports());
        } else if (ud.modifier() instanceof Decl.UseModifier.Selective sel) {
            // {name1 name2} — bind short name AND copy only named exports
            defineInScope(env, shortName, mod);
            for (var name : sel.names()) {
                if (!mod.exports().isDefined(name)) {
                    throw new IrijRuntimeError(
                        "Module '" + ud.qualifiedName() + "' does not export '" + name + "'", ud.loc());
                }
                defineInScope(env, name, mod.exports().lookup(name));
            }
        }
        return Values.UNIT;
    }

    private Object evalPubDecl(Decl.PubDecl pd, Environment env) {
        // Execute the inner declaration
        var result = execDecl((Decl) pd.inner(), env);
        // If we're loading a module, track the public name
        if (pubNames != null) {
            var name = extractDeclName(pd.inner());
            if (name != null) pubNames.add(name);
            // For type declarations, also export constructor names
            if (pd.inner() instanceof Decl.TypeDecl td) {
                if (td.body() instanceof Decl.TypeBody.SumType sum) {
                    for (var variant : sum.variants()) {
                        pubNames.add(variant.name());
                    }
                }
            }
        }
        return result;
    }

    /** Extract the primary name from a declaration node. */
    private String extractDeclName(Node node) {
        if (node instanceof Decl.FnDecl fn) return fn.name();
        if (node instanceof Decl.TypeDecl td) return td.name();
        if (node instanceof Decl.BindingDecl bd) {
            if (bd.stmt() instanceof Stmt.Bind b &&
                b.target() instanceof Stmt.BindTarget.Simple s) return s.name();
        }
        if (node instanceof Decl.UseDecl ud) {
            var parts = ud.qualifiedName().split("\\.");
            return parts[parts.length - 1];
        }
        return null;
    }

    /**
     * Load a module from Irij source code.
     * Called by {@link ModuleRegistry} for both classpath and file-based modules.
     */
    private ModuleValue loadModuleSource(String source, String qualifiedName, SourceLoc loc) {
        // Parse
        var parseResult = IrijParseDriver.parse(source);
        if (parseResult.hasErrors()) {
            throw new IrijRuntimeError(
                "Parse errors in module '" + qualifiedName + "': " + parseResult.errors(), loc);
        }
        var ast = new AstBuilder().build(parseResult.tree());

        // Execute in a fresh environment with builtins
        var moduleEnv = new Environment();
        Builtins.install(moduleEnv, out);
        // Install interpreter builtins (fold, spawn) in module env too
        moduleEnv.define("fold", globalEnv.lookup("fold"));
        moduleEnv.define("spawn", globalEnv.lookup("spawn"));
        moduleEnv.define("try", globalEnv.lookup("try"));

        // Track pub names
        var savedPubNames = this.pubNames;
        var savedModuleName = this.currentModuleName;
        this.pubNames = new HashSet<>();
        this.currentModuleName = qualifiedName;
        try {
            run(ast, moduleEnv);
            // Build exports from tracked pub names
            var exports = moduleEnv.exportBindings(this.pubNames);
            return new ModuleValue(qualifiedName, exports);
        } finally {
            this.pubNames = savedPubNames;
            this.currentModuleName = savedModuleName;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Effect system
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Evaluate an effect declaration: register the effect descriptor and
     * define each operation as a function that fires via the effect system.
     */
    private Object evalEffectDecl(Decl.EffectDecl ed, Environment env) {
        var opNames = ed.ops().stream().map(Decl.EffectOp::name).toList();
        var descriptor = new EffectDescriptor(ed.name(), opNames);
        defineInScope(env, ed.name(), descriptor);

        // Register each effect op as a function in the environment
        for (var op : ed.ops()) {
            String effectName = ed.name();
            String opName = op.name();
            // Arity -1 = variadic (type signatures not checked yet)
            defineInScope(env, opName, new BuiltinFn(opName, -1, args ->
                    EffectSystem.fireOp(effectName, opName, args)));
        }

        return descriptor;
    }

    /**
     * Evaluate a handler declaration: create a HandlerValue and bind it.
     * State bindings (`:!` lines) are executed into the handler's closure env.
     */
    private Object evalHandlerDecl(Decl.HandlerDecl hd, Environment env) {
        // Create a closure environment for the handler (captures lexical scope)
        var closureEnv = env.child();

        // Execute state bindings into the closure env
        for (var stmt : hd.stateBindings()) {
            exec(stmt, closureEnv);
        }

        // Build clause map: opName → HandlerClause
        var clauseMap = new LinkedHashMap<String, Decl.HandlerClause>();
        for (var clause : hd.clauses()) {
            clauseMap.put(clause.opName(), clause);
        }

        var handler = new HandlerValue(hd.name(), hd.effectName(), clauseMap, closureEnv);
        defineInScope(env, hd.name(), handler);
        return handler;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Statements
    // ═══════════════════════════════════════════════════════════════════

    private void exec(Stmt stmt, Environment env) {
        switch (stmt) {
            case Stmt.ExprStmt es -> eval(es.expr(), env);
            case Stmt.Bind b -> execBind(b, env);
            case Stmt.MutBind mb -> execMutBind(mb, env);
            case Stmt.Assign a -> execAssign(a, env);
            case Stmt.MatchStmt ms -> execMatch(ms, env);
            case Stmt.IfStmt is -> execIf(is, env);
            case Stmt.With w -> execWith(w, env);
            case Stmt.Scope s -> throw new IrijRuntimeError("Structured concurrency not yet implemented", s.loc());
        }
    }

    private void execBind(Stmt.Bind bind, Environment env) {
        var value = eval(bind.value(), env);
        switch (bind.target()) {
            case Stmt.BindTarget.Simple(var name) -> defineInScope(env, name, value);
            case Stmt.BindTarget.Destructure(var pat) -> {
                if (!matchPattern(pat, value, env)) {
                    throw new IrijRuntimeError("Destructuring bind failed", bind.loc());
                }
            }
        }
    }

    private void execMutBind(Stmt.MutBind bind, Environment env) {
        var value = eval(bind.value(), env);
        switch (bind.target()) {
            case Stmt.BindTarget.Simple(var name) -> env.defineMut(name, value);
            case Stmt.BindTarget.Destructure(var pat) ->
                throw new IrijRuntimeError("Mutable destructuring not supported", bind.loc());
        }
    }

    private void execAssign(Stmt.Assign assign, Environment env) {
        var value = eval(assign.value(), env);
        switch (assign.target()) {
            case Stmt.BindTarget.Simple(var name) -> env.assign(name, value, assign.loc());
            case Stmt.BindTarget.Destructure(var pat) ->
                throw new IrijRuntimeError("Destructuring assignment not supported", assign.loc());
        }
    }

    private void execMatch(Stmt.MatchStmt ms, Environment env) {
        var scrutinee = eval(ms.scrutinee(), env);
        for (var arm : ms.arms()) {
            var matchEnv = env.child();
            if (matchPattern(arm.pattern(), scrutinee, matchEnv)) {
                if (arm.guard() != null) {
                    var guardVal = eval(arm.guard(), matchEnv);
                    if (!Values.isTruthy(guardVal)) continue;
                }
                eval(arm.body(), matchEnv);
                return;
            }
        }
        throw new IrijRuntimeError("Non-exhaustive match", ms.loc());
    }

    private void execIf(Stmt.IfStmt is, Environment env) {
        var condVal = eval(is.cond(), env);
        if (Values.isTruthy(condVal)) {
            execStmtList(is.thenBranch(), env.child());
        } else if (!is.elseBranch().isEmpty()) {
            execStmtList(is.elseBranch(), env.child());
        }
    }

    private Object execWith(Stmt.With w, Environment env) {
        var handlerVal = eval(w.handler(), env);

        // ComposedHandler: decompose into nested with blocks
        // with (h1 >> h2 >> h3) body  ≡  with h1 (with h2 (with h3 body))
        if (handlerVal instanceof ComposedHandler ch) {
            return execComposedWith(ch.handlers(), 0, w.body(), w.onFailure(), env, w.loc());
        }

        if (!(handlerVal instanceof HandlerValue handler)) {
            throw new IrijRuntimeError("with requires a handler, got " + Values.typeName(handlerVal), w.loc());
        }

        var opChannel = new java.util.concurrent.SynchronousQueue<EffectSystem.EffectMessage>();
        var ctx = new EffectSystem.HandlerContext(handler.effectName(), handler, opChannel);

        // Snapshot the current thread's handler stack for the body thread
        var parentStack = EffectSystem.STACK.get();

        Thread bodyThread = Thread.startVirtualThread(() -> {
            // Copy parent handler stack and push our context
            var bodyStack = EffectSystem.STACK.get();
            bodyStack.addAll(parentStack);
            bodyStack.push(ctx);
            try {
                Object result = execStmtListReturn(w.body(), env.child());
                opChannel.put(new EffectSystem.EffectMessage.Done(result));
            } catch (IrijRuntimeError e) {
                try { opChannel.put(new EffectSystem.EffectMessage.Err(e)); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            } catch (InterruptedException e) {
                // Aborted by handler (no resume) — exit quietly
            } catch (Exception e) {
                try { opChannel.put(new EffectSystem.EffectMessage.Err(e)); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        });

        try {
            return runHandlerLoop(handler, opChannel);
        } catch (IrijRuntimeError e) {
            // on-failure clause: runs when body raises an unhandled error
            if (!w.onFailure().isEmpty()) {
                var failEnv = env.child();
                failEnv.define("error", e.getMessage());
                return execStmtListReturn(w.onFailure(), failEnv);
            }
            throw e;
        } finally {
            // Ensure body thread is cleaned up if handler throws
            if (bodyThread.isAlive()) {
                bodyThread.interrupt();
            }
        }
    }

    /**
     * Execute composed handlers as nested with blocks.
     * with (h1 >> h2 >> h3) body  ≡  with h1 (with h2 (with h3 body))
     */
    private Object execComposedWith(List<Object> handlers, int index,
                                     List<Stmt> body, List<Stmt> onFailure,
                                     Environment env, Node.SourceLoc loc) {
        if (index >= handlers.size()) {
            // All handlers installed — execute the actual body
            return execStmtListReturn(body, env);
        }

        var handlerVal = handlers.get(index);
        if (!(handlerVal instanceof HandlerValue handler)) {
            throw new IrijRuntimeError("Composed handler element is not a handler: "
                    + Values.typeName(handlerVal), loc);
        }

        var opChannel = new java.util.concurrent.SynchronousQueue<EffectSystem.EffectMessage>();
        var ctx = new EffectSystem.HandlerContext(handler.effectName(), handler, opChannel);
        var parentStack = EffectSystem.STACK.get();

        Thread bodyThread = Thread.startVirtualThread(() -> {
            var bodyStack = EffectSystem.STACK.get();
            bodyStack.addAll(parentStack);
            bodyStack.push(ctx);
            try {
                // Recurse: install next handler, eventually runs the body
                Object result = execComposedWith(handlers, index + 1, body, onFailure,
                        env.child(), loc);
                opChannel.put(new EffectSystem.EffectMessage.Done(result));
            } catch (IrijRuntimeError e) {
                try { opChannel.put(new EffectSystem.EffectMessage.Err(e)); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            } catch (InterruptedException e) {
                // Aborted — exit quietly
            } catch (Exception e) {
                try { opChannel.put(new EffectSystem.EffectMessage.Err(e)); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        });

        try {
            return runHandlerLoop(handler, opChannel);
        } catch (IrijRuntimeError e) {
            if (!onFailure.isEmpty() && index == 0) {
                // on-failure only runs for the outermost handler
                var failEnv = env.child();
                failEnv.define("error", e.getMessage());
                return execStmtListReturn(onFailure, failEnv);
            }
            throw e;
        } finally {
            if (bodyThread.isAlive()) {
                bodyThread.interrupt();
            }
        }
    }

    /**
     * Handler loop: reads messages from the body thread and dispatches to handler arms.
     * Called initially from execWith, and recursively from resume.
     */
    private Object runHandlerLoop(HandlerValue handler,
                                  java.util.concurrent.SynchronousQueue<EffectSystem.EffectMessage> opChannel) {
        while (true) {
            EffectSystem.EffectMessage msg;
            try {
                msg = opChannel.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IrijRuntimeError("Handler loop interrupted");
            }

            switch (msg) {
                case EffectSystem.EffectMessage.Done(var value) -> {
                    return value;
                }
                case EffectSystem.EffectMessage.Err(var error) -> {
                    if (error instanceof IrijRuntimeError ire) throw ire;
                    throw new IrijRuntimeError("Effect body error: " + error.getMessage());
                }
                case EffectSystem.EffectMessage.Op(var opName, var args, var resumeChannel) -> {
                    var clause = handler.clauses().get(opName);
                    if (clause == null) {
                        throw new IrijRuntimeError("Handler " + handler.name()
                                + " has no clause for operation: " + opName);
                    }

                    // Create arm environment as child of handler's closure env
                    var armEnv = handler.closureEnv().child();

                    // Bind params
                    var params = clause.params();
                    for (int i = 0; i < params.size() && i < args.size(); i++) {
                        if (!matchPattern(params.get(i), args.get(i), armEnv)) {
                            throw new IrijRuntimeError("Pattern match failed in handler arm: " + opName);
                        }
                    }

                    // Inject one-shot resume function
                    var resumed = new java.util.concurrent.atomic.AtomicBoolean(false);
                    var resumeFn = new BuiltinFn("resume", 1, resumeArgs -> {
                        if (!resumed.compareAndSet(false, true)) {
                            throw new IrijRuntimeError("resume called twice (one-shot continuation)");
                        }
                        try {
                            resumeChannel.put(resumeArgs.get(0)); // unblock body
                            return runHandlerLoop(handler, opChannel); // handle further ops
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IrijRuntimeError("Interrupted during resume");
                        }
                    });
                    armEnv.define("resume", resumeFn);

                    // Evaluate handler arm body
                    Object armResult = eval(clause.body(), armEnv);

                    if (resumed.get()) {
                        // resume was called — recursive runHandlerLoop already consumed
                        // the Done/further ops. armResult is the final result.
                        return armResult;
                    } else {
                        // resume NOT called — abort semantics
                        // Body thread is still blocked on resumeChannel.take();
                        // it will be interrupted by the finally block in execWith.
                        return armResult;
                    }
                }
            }
        }
    }

    /**
     * Execute a statement list and return the last expression's value.
     * Non-expression statements yield UNIT.
     */
    private Object execStmtListReturn(List<Stmt> stmts, Environment env) {
        Object last = Values.UNIT;
        for (var stmt : stmts) {
            switch (stmt) {
                case Stmt.ExprStmt es -> last = eval(es.expr(), env);
                case Stmt.MatchStmt ms -> last = execMatchReturn(ms, env);
                case Stmt.IfStmt is -> last = execIfReturn(is, env);
                case Stmt.With w -> last = execWith(w, env);
                default -> { exec(stmt, env); last = Values.UNIT; }
            }
        }
        return last;
    }

    private Object execMatchReturn(Stmt.MatchStmt ms, Environment env) {
        var scrutinee = eval(ms.scrutinee(), env);
        for (var arm : ms.arms()) {
            var matchEnv = env.child();
            if (matchPattern(arm.pattern(), scrutinee, matchEnv)) {
                if (arm.guard() != null) {
                    var guardVal = eval(arm.guard(), matchEnv);
                    if (!Values.isTruthy(guardVal)) continue;
                }
                return eval(arm.body(), matchEnv);
            }
        }
        throw new IrijRuntimeError("Non-exhaustive match", ms.loc());
    }

    private Object execIfReturn(Stmt.IfStmt is, Environment env) {
        var condVal = eval(is.cond(), env);
        if (Values.isTruthy(condVal)) {
            return execStmtListReturn(is.thenBranch(), env.child());
        } else if (!is.elseBranch().isEmpty()) {
            return execStmtListReturn(is.elseBranch(), env.child());
        }
        return Values.UNIT;
    }

    private void execStmtList(List<Stmt> stmts, Environment env) {
        for (var stmt : stmts) {
            exec(stmt, env);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Expressions
    // ═══════════════════════════════════════════════════════════════════

    Object eval(Expr expr, Environment env) {
	System.out.println("PID=" + ProcessHandle.current().pid());
        return switch (expr) {
            // ── Literals ────────────────────────────────────────────────
            case Expr.IntLit(var v, _) -> v;
            case Expr.FloatLit(var v, _) -> v;
            case Expr.RationalLit(var n, var d, _) -> new Rational(n, d);
            case Expr.HexLit(var v, _) -> v;
            case Expr.StrLit(var v, _) -> v;
            case Expr.BoolLit(var v, _) -> v;
            case Expr.KeywordLit(var name, _) -> new Keyword(name);
            case Expr.UnitLit(_) -> Values.UNIT;
            case Expr.Wildcard(_) -> Values.UNIT;

            // ── References ──────────────────────────────────────────────
            case Expr.Var(var name, var loc) -> env.lookup(name, loc);
            case Expr.TypeRef(var name, var loc) -> env.lookup(name, loc);
            case Expr.RoleRef(var name, _) -> name; // just pass through as string

            // ── Operators ───────────────────────────────────────────────
            case Expr.BinaryOp bo -> evalBinaryOp(bo, env);
            case Expr.UnaryOp uo -> evalUnaryOp(uo, env);

            // ── Application ─────────────────────────────────────────────
            case Expr.App app -> evalApp(app, env);
            case Expr.Lambda lam ->
                new Lambda(lam.params(), lam.body(), env);

            // ── Pipeline & Composition ──────────────────────────────────
            case Expr.Pipe p -> evalPipe(p, env);
            case Expr.Compose c -> evalCompose(c, env);

            // ── Seq Ops ─────────────────────────────────────────────────
            case Expr.SeqOp so -> evalSeqOp(so, env);

            // ── Operator Section ─────────────────────────────────────────
            case Expr.OpSection os -> evalOpSection(os);

            // ── Control Flow ────────────────────────────────────────────
            case Expr.IfExpr ie -> evalIf(ie, env);
            case Expr.MatchExpr me -> evalMatch(me, env);

            // ── Collections ─────────────────────────────────────────────
            case Expr.VectorLit vl -> {
                var elements = new ArrayList<Object>();
                for (var e : vl.elements()) {
                    elements.add(eval(e, env));
                }
                yield new IrijVector(elements);
            }
            case Expr.SetLit sl -> {
                var elements = new LinkedHashSet<Object>();
                for (var e : sl.elements()) {
                    elements.add(eval(e, env));
                }
                yield new IrijSet(elements);
            }
            case Expr.TupleLit tl -> {
                var elements = new Object[tl.elements().size()];
                for (int i = 0; i < tl.elements().size(); i++) {
                    elements[i] = eval(tl.elements().get(i), env);
                }
                yield new IrijTuple(elements);
            }
            case Expr.MapLit ml -> evalMapLit(ml, env);
            case Expr.RecordUpdate ru -> evalRecordUpdate(ru, env);

            // ── Range ───────────────────────────────────────────────────
            case Expr.Range r -> {
                var from = eval(r.from(), env);
                var to = eval(r.to(), env);
                if (from instanceof Long lf && to instanceof Long lt) {
                    yield new IrijRange(lf, lt, r.exclusive());
                }
                throw new IrijRuntimeError("Range requires Int endpoints", r.loc());
            }

            // ── String Interpolation ────────────────────────────────────
            case Expr.StringInterp si -> {
                var sb = new StringBuilder();
                for (var part : si.parts()) {
                    switch (part) {
                        case Expr.StringPart.Literal(var text) -> sb.append(text);
                        case Expr.StringPart.Interpolation(var e) ->
                            sb.append(Values.toIrijString(eval(e, env)));
                    }
                }
                yield sb.toString();
            }

            // ── Dot Access ──────────────────────────────────────────────
            case Expr.DotAccess da -> {
                var target = eval(da.target(), env);
                if (target instanceof IrijMap map) {
                    var v = map.entries().get(da.field());
                    yield v != null ? v : Values.UNIT;
                }
                if (target instanceof Tagged tagged && tagged.namedFields() != null) {
                    var v = tagged.namedFields().get(da.field());
                    if (v != null) yield v;
                    throw new IrijRuntimeError("No field '" + da.field() + "' on " + tagged.tag(), da.loc());
                }
                // Handler dot-access: read from handler's closure environment
                if (target instanceof HandlerValue hv) {
                    if (hv.closureEnv().isDefined(da.field())) {
                        yield hv.closureEnv().lookup(da.field());
                    }
                    throw new IrijRuntimeError("No field '" + da.field() + "' on handler " + hv.name(), da.loc());
                }
                // Module dot-access: read from module's exports environment
                if (target instanceof ModuleValue mod) {
                    if (mod.exports().isDefined(da.field())) {
                        yield mod.exports().lookup(da.field());
                    }
                    throw new IrijRuntimeError("Module '" + mod.qualifiedName() + "' has no export '" + da.field() + "'", da.loc());
                }
                throw new IrijRuntimeError("Cannot access field '" + da.field() + "' on " + Values.typeName(target), da.loc());
            }

            // ── Do Expression ───────────────────────────────────────────
            case Expr.DoExpr de -> {
                Object last = Values.UNIT;
                for (var e : de.exprs()) {
                    last = eval(e, env);
                }
                yield last;
            }

            // ── Block ───────────────────────────────────────────────────
            case Expr.Block bl -> {
                yield execStmtListReturn(bl.stmts(), env.child());
            }

            // ── Choreography (stub) ─────────────────────────────────────
            case Expr.ChoreoExpr ce ->
                throw new IrijRuntimeError("Choreographic programming not yet implemented", ce.loc());
        };
    }

    // ═══════════════════════════════════════════════════════════════════
    // Binary Operators
    // ═══════════════════════════════════════════════════════════════════

    private Object evalBinaryOp(Expr.BinaryOp bo, Environment env) {
        // Short-circuit for && and ||
        if (bo.op().equals("&&")) {
            var left = eval(bo.left(), env);
            if (!Values.isTruthy(left)) return Boolean.FALSE;
            return Values.isTruthy(eval(bo.right(), env));
        }
        if (bo.op().equals("||")) {
            var left = eval(bo.left(), env);
            if (Values.isTruthy(left)) return Boolean.TRUE;
            return Values.isTruthy(eval(bo.right(), env));
        }

        var left = eval(bo.left(), env);
        var right = eval(bo.right(), env);
        var loc = bo.loc();

        return switch (bo.op()) {
            // Arithmetic
            case "+" -> numericOp(left, right, Long::sum, Double::sum, Builtins::addRational, loc);
            case "-" -> numericOp(left, right, (a, b) -> a - b, (a, b) -> a - b, Builtins::subRational, loc);
            case "*" -> numericOp(left, right, (a, b) -> a * b, (a, b) -> a * b, Builtins::mulRational, loc);
            case "/" -> divOp(left, right, loc);
            case "%" -> numericOp(left, right, (a, b) -> a % b, (a, b) -> a % b, null, loc);
            case "**" -> powOp(left, right, loc);

            // Comparison
            case "==" -> equalityOp(left, right);
            case "/=" -> !((Boolean) equalityOp(left, right));
            case "<" -> Builtins.compare(left, right) < 0;
            case ">" -> Builtins.compare(left, right) > 0;
            case "<=" -> Builtins.compare(left, right) <= 0;
            case ">=" -> Builtins.compare(left, right) >= 0;

            // Concat
            case "++" -> Builtins.concatValues(left, right);

            default -> throw new IrijRuntimeError("Unknown operator: " + bo.op(), loc);
        };
    }

    @FunctionalInterface
    interface LongBinOp { long apply(long a, long b); }
    @FunctionalInterface
    interface DoubleBinOp { double apply(double a, double b); }
    @FunctionalInterface
    interface RationalBinOp { Rational apply(Rational a, Rational b); }

    private Object numericOp(Object left, Object right,
                             LongBinOp longOp, DoubleBinOp doubleOp,
                             RationalBinOp rationalOp, SourceLoc loc) {
        // Widening rules: Float wins over everything. Rational wins over Int.
        if (left instanceof Double || right instanceof Double) {
            double a = toDouble(left, loc);
            double b = toDouble(right, loc);
            return doubleOp.apply(a, b);
        }
        if (left instanceof Rational || right instanceof Rational) {
            if (rationalOp != null) {
                Rational a = toRational(left, loc);
                Rational b = toRational(right, loc);
                return rationalOp.apply(a, b);
            }
        }
        if (left instanceof Long la && right instanceof Long lb) {
            return longOp.apply(la, lb);
        }
        throw new IrijRuntimeError(
            "Cannot apply arithmetic to " + Values.typeName(left) + " and " + Values.typeName(right), loc);
    }

    private Object divOp(Object left, Object right, SourceLoc loc) {
        // / always returns Float (or Rational if both are Rational)
        if (left instanceof Rational lr && right instanceof Rational rr) {
            if (rr.num() == 0) throw new IrijRuntimeError("Division by zero", loc);
            return Builtins.divRational(lr, rr);
        }
        double a = toDouble(left, loc);
        double b = toDouble(right, loc);
        if (b == 0.0) throw new IrijRuntimeError("Division by zero", loc);
        return a / b;
    }

    private Object powOp(Object left, Object right, SourceLoc loc) {
        double a = toDouble(left, loc);
        double b = toDouble(right, loc);
        double result = Math.pow(a, b);
        // If both operands were ints and result is a whole number, return Long
        if (left instanceof Long && right instanceof Long && result == Math.floor(result)
                && !Double.isInfinite(result) && result >= Long.MIN_VALUE && result <= Long.MAX_VALUE) {
            return (long) result;
        }
        return result;
    }

    private Object equalityOp(Object left, Object right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        if (left == Values.UNIT) return right == Values.UNIT;
        // Cross-type numeric comparison
        if (left instanceof Long la && right instanceof Double rb) return la.doubleValue() == rb;
        if (left instanceof Double la && right instanceof Long rb) return la == rb.doubleValue();
        return left.equals(right);
    }

    private double toDouble(Object value, SourceLoc loc) {
        if (value instanceof Long l) return l.doubleValue();
        if (value instanceof Double d) return d;
        if (value instanceof Rational r) return r.toDouble();
        throw new IrijRuntimeError("Expected number, got " + Values.typeName(value), loc);
    }

    private Rational toRational(Object value, SourceLoc loc) {
        if (value instanceof Rational r) return r;
        if (value instanceof Long l) return new Rational(l, 1);
        throw new IrijRuntimeError("Expected number, got " + Values.typeName(value), loc);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Unary Operators
    // ═══════════════════════════════════════════════════════════════════

    private Object evalUnaryOp(Expr.UnaryOp uo, Environment env) {
        var operand = eval(uo.operand(), env);
        return switch (uo.op()) {
            case "!" -> !Values.isTruthy(operand);
            case "-" -> {
                if (operand instanceof Long l) yield -l;
                if (operand instanceof Double d) yield -d;
                if (operand instanceof Rational r) yield new Rational(-r.num(), r.den());
                throw new IrijRuntimeError("Cannot negate " + Values.typeName(operand), uo.loc());
            }
            default -> throw new IrijRuntimeError("Unknown unary operator: " + uo.op(), uo.loc());
        };
    }

    // ═══════════════════════════════════════════════════════════════════
    // Application
    // ═══════════════════════════════════════════════════════════════════

    Object apply(Object fn, List<Object> args, SourceLoc loc) {
        return switch (fn) {
            case Lambda lam -> {
                // Check arity for partial application
                if (args.size() < lam.arity()) {
                    yield new PartialApp(lam, List.copyOf(args));
                }
                var callEnv = lam.closure().child();
                // Bind params via pattern matching
                for (int i = 0; i < lam.params().size(); i++) {
                    if (!matchPattern(lam.params().get(i), args.get(i), callEnv)) {
                        throw new IrijRuntimeError("Pattern match failed in function call", loc);
                    }
                }
                yield eval(lam.body(), callEnv);
            }
            case MatchFn mf -> {
                // Match-arm function: first arg is the scrutinee
                if (args.isEmpty()) {
                    throw new IrijRuntimeError("Match function requires at least one argument", loc);
                }
                var scrutinee = args.get(0);
                for (var arm : mf.arms()) {
                    var matchEnv = mf.closure().child();
                    if (matchPattern(arm.pattern(), scrutinee, matchEnv)) {
                        if (arm.guard() != null) {
                            var guardVal = eval(arm.guard(), matchEnv);
                            if (!Values.isTruthy(guardVal)) continue;
                        }
                        yield eval(arm.body(), matchEnv);
                    }
                }
                throw new IrijRuntimeError("Non-exhaustive match in function " + mf.name(), loc);
            }
            case ImperativeFn imf -> {
                var callEnv = imf.closure().child();
                // Bind params
                for (int i = 0; i < imf.params().size() && i < args.size(); i++) {
                    if (!matchPattern(imf.params().get(i), args.get(i), callEnv)) {
                        throw new IrijRuntimeError("Pattern match failed in function call", loc);
                    }
                }
                // Execute body — returns last expression/match/if value
                yield execStmtListReturn(imf.body(), callEnv);
            }
            case BuiltinFn bf -> {
                if (args.size() < bf.arity()) {
                    yield new PartialApp(bf, List.copyOf(args));
                }
                yield bf.apply(args);
            }
            case Constructor ctor -> {
                if (args.size() < ctor.arity()) {
                    yield new PartialApp(ctor, List.copyOf(args));
                }
                yield ctor.apply(args);
            }
            case PartialApp pa -> {
                var allArgs = new ArrayList<>(pa.appliedArgs());
                allArgs.addAll(args);
                yield apply(pa.fn(), allArgs, loc);
            }
            case ComposedFn cf -> {
                var intermediate = apply(cf.first(), args, loc);
                yield apply(cf.second(), List.of(intermediate), loc);
            }
            case Tagged t -> {
                // Tagged value used as a function (zero-arg constructor called again)
                if (args.isEmpty()) yield t;
                throw new IrijRuntimeError("Cannot apply " + t.tag() + " as a function", loc);
            }
            default -> throw new IrijRuntimeError(
                "Cannot call " + Values.typeName(fn) + " as a function", loc);
        };
    }

    private Object evalApp(Expr.App app, Environment env) {
        var fn = eval(app.fn(), env);
        var args = new ArrayList<Object>();
        for (var a : app.args()) {
            args.add(eval(a, env));
        }
        return apply(fn, args, app.loc());
    }

    // ═══════════════════════════════════════════════════════════════════
    // Pipeline & Composition
    // ═══════════════════════════════════════════════════════════════════

    private Object evalPipe(Expr.Pipe p, Environment env) {
        if (p.forward()) {
            // a |> f → apply(f, [a])
            var left = eval(p.left(), env);
            var right = eval(p.right(), env);
            return apply(right, List.of(left), p.loc());
        } else {
            // f <| a → apply(f, [a])
            var left = eval(p.left(), env);
            var right = eval(p.right(), env);
            return apply(left, List.of(right), p.loc());
        }
    }

    private Object evalCompose(Expr.Compose c, Environment env) {
        var left = eval(c.left(), env);
        var right = eval(c.right(), env);

        // Handler composition: h1 >> h2 creates a ComposedHandler
        if (isHandler(left) && isHandler(right)) {
            var handlers = new ArrayList<Object>();
            // Flatten nested compositions
            if (left instanceof ComposedHandler ch) handlers.addAll(ch.handlers());
            else handlers.add(left);
            if (right instanceof ComposedHandler ch) handlers.addAll(ch.handlers());
            else handlers.add(right);
            return new ComposedHandler(List.copyOf(handlers));
        }

        if (c.forward()) {
            // f >> g: apply g after f
            return new ComposedFn(left, right);
        } else {
            // g << f: apply f first, then g
            return new ComposedFn(right, left);
        }
    }

    private boolean isHandler(Object v) {
        return v instanceof HandlerValue || v instanceof ComposedHandler;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Operator Sections
    // ═══════════════════════════════════════════════════════════════════

    private Object evalOpSection(Expr.OpSection os) {
        return new BuiltinFn("(" + os.op() + ")", 2, args -> {
            var left = args.get(0);
            var right = args.get(1);
            return switch (os.op()) {
                case "+" -> numericOp(left, right, Long::sum, Double::sum, Builtins::addRational, os.loc());
                case "-" -> numericOp(left, right, (a, b) -> a - b, (a, b) -> a - b, Builtins::subRational, os.loc());
                case "*" -> numericOp(left, right, (a, b) -> a * b, (a, b) -> a * b, Builtins::mulRational, os.loc());
                case "/" -> divOp(left, right, os.loc());
                case "%" -> numericOp(left, right, (a, b) -> a % b, (a, b) -> a % b, null, os.loc());
                case "**" -> powOp(left, right, os.loc());
                case "++" -> Builtins.concatValues(left, right);
                case "==" -> equalityOp(left, right);
                case "/=" -> !((Boolean) equalityOp(left, right));
                case "<" -> Builtins.compare(left, right) < 0;
                case ">" -> Builtins.compare(left, right) > 0;
                case "<=" -> Builtins.compare(left, right) <= 0;
                case ">=" -> Builtins.compare(left, right) >= 0;
                case "&&" -> Values.isTruthy(left) && Values.isTruthy(right);
                case "||" -> Values.isTruthy(left) || Values.isTruthy(right);
                default -> throw new IrijRuntimeError("Unknown operator section: " + os.op(), os.loc());
            };
        });
    }

    // ═══════════════════════════════════════════════════════════════════
    // Seq Ops
    // ═══════════════════════════════════════════════════════════════════

    private Object evalSeqOp(Expr.SeqOp so, Environment env) {
        if (so.arg() == null) {
            // Standalone seq op: return as function value
            return seqOpAsFunction(so.op(), so.loc());
        }
        var arg = eval(so.arg(), env);
        // For @ /? /! @i /^ /$ — the arg is a function to use, return a partially applied seq op
        return switch (so.op()) {
            case "@" -> new BuiltinFn("@-partial", 1, args -> mapOver(arg, args.get(0), so.loc()));
            case "/?" -> new BuiltinFn("/?-partial", 1, args -> filterBy(arg, args.get(0), so.loc()));
            case "/!" -> new BuiltinFn("/!-partial", 1, args -> findFirst(arg, args.get(0), so.loc()));
            case "@i" -> new BuiltinFn("@i-partial", 1, args -> mapIndexed(arg, args.get(0), so.loc()));
            case "/^" -> new BuiltinFn("/^-partial", 1, args -> reduceGeneric(arg, args.get(0), so.loc()));
            case "/$" -> new BuiltinFn("/$-partial", 1, args -> scanGeneric(arg, args.get(0), so.loc()));
            // For reduce ops, the arg IS the collection
            default -> applySeqOp(so.op(), arg, env, so.loc());
        };
    }

    private Object seqOpAsFunction(String op, SourceLoc loc) {
        return switch (op) {
            case "/+" -> new BuiltinFn("/+", 1, args -> applySeqOp("/+", args.get(0), null, loc));
            case "/*" -> new BuiltinFn("/*", 1, args -> applySeqOp("/*", args.get(0), null, loc));
            case "/#" -> new BuiltinFn("/#", 1, args -> applySeqOp("/#", args.get(0), null, loc));
            case "/&" -> new BuiltinFn("/&", 1, args -> applySeqOp("/&", args.get(0), null, loc));
            case "/|" -> new BuiltinFn("/|", 1, args -> applySeqOp("/|", args.get(0), null, loc));
            case "@" -> new BuiltinFn("@", 1, args -> {
                var f = args.get(0);
                return new BuiltinFn("@-partial", 1, args2 -> mapOver(f, args2.get(0), loc));
            });
            case "/?" -> new BuiltinFn("/?", 1, args -> {
                var pred = args.get(0);
                return new BuiltinFn("/?-partial", 1, args2 -> filterBy(pred, args2.get(0), loc));
            });
            case "/!" -> new BuiltinFn("/!", 1, args -> {
                var pred = args.get(0);
                return new BuiltinFn("/!-partial", 1, args2 -> findFirst(pred, args2.get(0), loc));
            });
            case "@i" -> new BuiltinFn("@i", 1, args -> {
                var f = args.get(0);
                return new BuiltinFn("@i-partial", 1, args2 -> mapIndexed(f, args2.get(0), loc));
            });
            case "/^" -> new BuiltinFn("/^", 1, args -> {
                var f = args.get(0);
                return new BuiltinFn("/^-partial", 1, args2 -> reduceGeneric(f, args2.get(0), loc));
            });
            case "/$" -> new BuiltinFn("/$", 1, args -> {
                var f = args.get(0);
                return new BuiltinFn("/$-partial", 1, args2 -> scanGeneric(f, args2.get(0), loc));
            });
            default -> throw new IrijRuntimeError("Unknown seq op: " + op, loc);
        };
    }

    private Object applySeqOp(String op, Object value, Environment env, SourceLoc loc) {
        return switch (op) {
            case "/+" -> reduceWith(value, "+", loc);
            case "/*" -> reduceWith(value, "*", loc);
            case "/#" -> {
                var list = Builtins.toList(value);
                yield (long) list.size();
            }
            case "/&" -> {
                for (var e : Builtins.toIterable(value)) {
                    if (!Values.isTruthy(e)) yield false;
                }
                yield true;
            }
            case "/|" -> {
                for (var e : Builtins.toIterable(value)) {
                    if (Values.isTruthy(e)) yield true;
                }
                yield false;
            }
            default -> throw new IrijRuntimeError("Cannot apply seq op " + op + " to " + Values.typeName(value), loc);
        };
    }

    private Object reduceWith(Object collection, String op, SourceLoc loc) {
        var list = Builtins.toList(collection);
        if (list.isEmpty()) throw new IrijRuntimeError("Cannot reduce empty collection", loc);
        Object acc = list.get(0);
        for (int i = 1; i < list.size(); i++) {
            acc = switch (op) {
                case "+" -> numericOp(acc, list.get(i), Long::sum, Double::sum, Builtins::addRational, loc);
                case "*" -> numericOp(acc, list.get(i), (a, b) -> a * b, (a, b) -> a * b, Builtins::mulRational, loc);
                default -> throw new IrijRuntimeError("Unknown reduce operator: " + op, loc);
            };
        }
        return acc;
    }

    /** Generic reduce (/^): apply fn to accumulate, init = first element. */
    private Object reduceGeneric(Object fn, Object collection, SourceLoc loc) {
        var list = Builtins.toList(collection);
        if (list.isEmpty()) throw new IrijRuntimeError("Cannot reduce empty collection", loc);
        Object acc = list.get(0);
        for (int i = 1; i < list.size(); i++) {
            acc = apply(fn, List.of(acc, list.get(i)), loc);
        }
        return acc;
    }

    /** Generic scan (/$): running accumulation, init = first element. */
    private Object scanGeneric(Object fn, Object collection, SourceLoc loc) {
        var list = Builtins.toList(collection);
        if (list.isEmpty()) return new IrijVector(List.of());
        var result = new ArrayList<Object>();
        Object acc = list.get(0);
        result.add(acc);
        for (int i = 1; i < list.size(); i++) {
            acc = apply(fn, List.of(acc, list.get(i)), loc);
            result.add(acc);
        }
        return new IrijVector(result);
    }

    private Object mapOver(Object fn, Object coll, SourceLoc loc) {
        var iterable = Builtins.toIterable(coll);
        // For lazy iterables and ranges, return lazy
        if (coll instanceof IrijRange || coll instanceof Builtins.LazyIterable) {
            return new Builtins.LazyIterable(iterable, elem -> apply(fn, List.of(elem), loc));
        }
        // For concrete collections, materialize
        var result = new ArrayList<Object>();
        for (var elem : iterable) {
            result.add(apply(fn, List.of(elem), loc));
        }
        return new IrijVector(result);
    }

    private Object filterBy(Object pred, Object coll, SourceLoc loc) {
        var iterable = Builtins.toIterable(coll);
        if (coll instanceof IrijRange || coll instanceof Builtins.LazyIterable) {
            return new Builtins.LazyIterable(iterable,
                elem -> Values.isTruthy(apply(pred, List.of(elem), loc)), false);
        }
        var result = new ArrayList<Object>();
        for (var elem : iterable) {
            if (Values.isTruthy(apply(pred, List.of(elem), loc))) {
                result.add(elem);
            }
        }
        return new IrijVector(result);
    }

    private Object findFirst(Object pred, Object coll, SourceLoc loc) {
        for (var elem : Builtins.toIterable(coll)) {
            if (Values.isTruthy(apply(pred, List.of(elem), loc))) {
                return elem;
            }
        }
        return Values.UNIT; // not found
    }

    private Object mapIndexed(Object fn, Object coll, SourceLoc loc) {
        var result = new ArrayList<Object>();
        long index = 0;
        for (var elem : Builtins.toIterable(coll)) {
            result.add(apply(fn, List.of(index, elem), loc));
            index++;
        }
        return new IrijVector(result);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Control Flow
    // ═══════════════════════════════════════════════════════════════════

    private Object evalIf(Expr.IfExpr ie, Environment env) {
        var cond = eval(ie.cond(), env);
        if (Values.isTruthy(cond)) {
            return eval(ie.thenBranch(), env);
        }
        if (ie.elseBranch() != null) {
            return eval(ie.elseBranch(), env);
        }
        return Values.UNIT;
    }

    private Object evalMatch(Expr.MatchExpr me, Environment env) {
        var scrutinee = eval(me.scrutinee(), env);
        for (var arm : me.arms()) {
            var matchEnv = env.child();
            if (matchPattern(arm.pattern(), scrutinee, matchEnv)) {
                if (arm.guard() != null) {
                    var guardVal = eval(arm.guard(), matchEnv);
                    if (!Values.isTruthy(guardVal)) continue;
                }
                return eval(arm.body(), matchEnv);
            }
        }
        throw new IrijRuntimeError("Non-exhaustive match", me.loc());
    }

    // ═══════════════════════════════════════════════════════════════════
    // Map / Record
    // ═══════════════════════════════════════════════════════════════════

    private Object evalMapLit(Expr.MapLit ml, Environment env) {
        var map = new LinkedHashMap<String, Object>();
        for (var entry : ml.entries()) {
            switch (entry) {
                case Expr.MapEntry.Field f -> map.put(f.key(), eval(f.value(), env));
                case Expr.MapEntry.Spread s -> {
                    var base = env.lookup(s.name());
                    if (base instanceof IrijMap bm) {
                        map.putAll(bm.entries());
                    }
                }
            }
        }
        return new IrijMap(map);
    }

    private Object evalRecordUpdate(Expr.RecordUpdate ru, Environment env) {
        var base = env.lookup(ru.base(), ru.loc());
        if (!(base instanceof IrijMap bm)) {
            throw new IrijRuntimeError("Record update requires a Map, got " + Values.typeName(base), ru.loc());
        }
        var map = new LinkedHashMap<>(bm.entries());
        for (var entry : ru.updates()) {
            if (entry instanceof Expr.MapEntry.Field f) {
                map.put(f.key(), eval(f.value(), env));
            }
        }
        return new IrijMap(map);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Pattern Matching
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Attempt to match a value against a pattern.
     * On success, binds variables into the given environment and returns true.
     * On failure, returns false (environment may have partial bindings — caller
     * should use a child scope if rollback is needed).
     */
    boolean matchPattern(Pattern pat, Object value, Environment env) {
        return switch (pat) {
            case Pattern.VarPat(var name, _) -> {
                env.define(name, value);
                yield true;
            }
            case Pattern.WildcardPat _ -> true;
            case Pattern.UnitPat _ -> value == Values.UNIT;
            case Pattern.LitPat(var litExpr, _) -> {
                // Evaluate the literal and compare
                var litVal = eval(litExpr, env);
                yield equalityOp(litVal, value).equals(Boolean.TRUE);
            }
            case Pattern.ConstructorPat(var name, var args, _) -> {
                if (!(value instanceof Tagged t)) yield false;
                if (!t.tag().equals(name)) yield false;
                if (args.size() != t.fields().size()) yield false;
                boolean allMatch = true;
                for (int i = 0; i < args.size(); i++) {
                    if (!matchPattern(args.get(i), t.fields().get(i), env)) {
                        allMatch = false;
                        break;
                    }
                }
                yield allMatch;
            }
            case Pattern.KeywordPat(var name, var arg, _) -> {
                if (value instanceof Keyword kw && kw.name().equals(name)) {
                    if (arg == null) yield true;
                    // keyword with value — shouldn't happen for bare keywords
                    yield false;
                }
                // Also match Tagged with keyword name
                if (value instanceof Tagged t && t.tag().equals(":" + name)) {
                    if (arg == null) yield true;
                    if (!t.fields().isEmpty()) {
                        yield matchPattern(arg, t.fields().get(0), env);
                    }
                }
                yield false;
            }
            case Pattern.GroupedPat(var inner, _) -> matchPattern(inner, value, env);
            case Pattern.VectorPat(var elements, var spread, _) -> {
                List<Object> list;
                if (value instanceof IrijVector vec) list = vec.elements();
                else yield false;

                if (spread == null) {
                    // Exact match
                    if (list.size() != elements.size()) yield false;
                    boolean allMatch = true;
                    for (int i = 0; i < elements.size(); i++) {
                        if (!matchPattern(elements.get(i), list.get(i), env)) {
                            allMatch = false;
                            break;
                        }
                    }
                    yield allMatch;
                } else {
                    // With spread: match prefix, bind rest
                    if (list.size() < elements.size()) yield false;
                    boolean allMatch = true;
                    for (int i = 0; i < elements.size(); i++) {
                        if (!matchPattern(elements.get(i), list.get(i), env)) {
                            allMatch = false;
                            break;
                        }
                    }
                    if (allMatch && !spread.name().equals("_")) {
                        env.define(spread.name(),
                            new IrijVector(list.subList(elements.size(), list.size())));
                    }
                    yield allMatch;
                }
            }
            case Pattern.TuplePat(var elements, _) -> {
                if (!(value instanceof IrijTuple tuple)) yield false;
                if (tuple.elements().length != elements.size()) yield false;
                boolean allMatch = true;
                for (int i = 0; i < elements.size(); i++) {
                    if (!matchPattern(elements.get(i), tuple.elements()[i], env)) {
                        allMatch = false;
                        break;
                    }
                }
                yield allMatch;
            }
            case Pattern.DestructurePat(var fields, _) -> {
                // Destructure works on both IrijMap and Tagged with named fields
                Map<String, Object> entries;
                if (value instanceof IrijMap map) {
                    entries = map.entries();
                } else if (value instanceof Tagged t && t.namedFields() != null) {
                    entries = t.namedFields();
                } else {
                    yield false;
                }
                boolean allMatch = true;
                for (var field : fields) {
                    var v = entries.get(field.key());
                    if (v == null) { allMatch = false; break; }
                    if (!matchPattern(field.value(), v, env)) {
                        allMatch = false;
                        break;
                    }
                }
                yield allMatch;
            }
            case Pattern.SpreadPat(var name, _) -> {
                // Standalone spread — binds whatever is left
                if (!name.equals("_")) env.define(name, value);
                yield true;
            }
        };
    }

    // ═══════════════════════════════════════════════════════════════════
    // Rational helpers (delegated to Builtins)
    // ═══════════════════════════════════════════════════════════════════

    // These are in Builtins for use from both Interpreter and Builtins itself.
}
