package dev.irij.interpreter;

import dev.irij.ast.*;
import dev.irij.ast.Node.SourceLoc;
import dev.irij.interpreter.Values.*;

import dev.irij.module.DependencyResolver;
import dev.irij.module.ProjectFile;
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

    // Source directory for static file serving and relative path resolution
    private Path sourcePath;

    /** Resolve relative file paths against sourcePath. Shared by all threads using this interpreter. */
    private final java.util.function.Function<String, Path> pathResolver = path -> {
        var p = Path.of(path);
        if (p.isAbsolute()) return p;
        var dir = sourcePath;
        return dir != null ? dir.toAbsolutePath().resolve(p) : p;
    };

    // Bundled JAR mode: load deps/resources from classpath
    private boolean bundledMode = false;

    // Module loading state
    private String currentModuleName;
    private Set<String> pubNames; // non-null only during module file loading

    // Protocol registry: proto name → descriptor (shared across all envs, thread-safe for spawn)
    private final Map<String, ProtocolDescriptor> protocols = new java.util.concurrent.ConcurrentHashMap<>();

    // Spec registry: spec name → descriptor (shared, hot-reloadable via nREPL)
    private final Map<String, Values.SpecDescriptor> specRegistry = new java.util.concurrent.ConcurrentHashMap<>();

    /** When true, automatically verify protocol laws on each `impl` declaration. */
    private boolean autoVerifyLaws = false;

    /** When true, warn if pub fn lacks spec annotations (Phase 8c). On by default. */
    private boolean specLintEnabled = true;

    public void setAutoVerifyLaws(boolean on) { this.autoVerifyLaws = on; }

    public void setSpecLintEnabled(boolean on) { this.specLintEnabled = on; }

    // ── Effect row checking ─────────────────────────────────────────────
    // Stack tracks what effects are available in the current execution context.
    // AMBIENT_EFFECTS at top = all effects available (top-level or unannotated fn)
    // Any other Set<String> = only those effects are available
    private static final Set<String> AMBIENT_EFFECTS = Collections.unmodifiableSet(new HashSet<>() {
        @Override public boolean contains(Object o) { return true; } // everything is available
    });

    static final ThreadLocal<Deque<Set<String>>> AVAILABLE_EFFECTS =
        ThreadLocal.withInitial(() -> {
            var d = new ArrayDeque<Set<String>>();
            d.push(AMBIENT_EFFECTS); // top-level = all effects available
            return d;
        });

    /** Check that required effects are available in the current context. */
    private void checkEffectsAvailable(List<String> required, String opName, SourceLoc loc) {
        Set<String> available = AVAILABLE_EFFECTS.get().peek();
        for (String eff : required) {
            if (!available.contains(eff)) {
                throw new IrijRuntimeError(
                    "Effect '" + eff + "' not declared: '" + opName
                    + "' requires ::: " + eff + " in enclosing function's effect row", loc);
            }
        }
    }

    /** Get the effect row from a function value, or null if not annotated. */
    private static List<String> getEffectRow(Object fn) {
        return switch (fn) {
            case Lambda lam -> lam.effectRow();
            case MatchFn mf -> mf.effectRow();
            case ImperativeFn imf -> imf.effectRow();
            case ContractedFn cf -> getEffectRow(cf.fn());
            case SpecContractFn scf -> getEffectRow(scf.fn());
            default -> null;
        };
    }

    public Interpreter(PrintStream out) {
        this(out, false);
    }

    public Interpreter(PrintStream out, boolean sandboxed) {
        this.out = out;
        this.globalEnv = new Environment();
        if (sandboxed) {
            Builtins.installSandboxed(globalEnv, out);
        } else {
            Builtins.install(globalEnv, out, pathResolver);
        }
        installInterpreterBuiltins();
        if (sandboxed) {
            // Replace interpreter-level dangerous builtins with stubs
            for (var name : List.of("raw-http-serve", "raw-db-transaction",
                    "raw-sse-response", "raw-sse-send", "raw-sse-close", "raw-sse-closed?",
                    "raw-session-create", "raw-session-eval", "raw-session-destroy", "raw-session-cleanup",
                    "raw-session-subscribe", "raw-session-unsubscribe")) {
                int arity = globalEnv.isDefined(name)
                    ? (globalEnv.lookup(name) instanceof BuiltinFn fn ? fn.arity() : 1)
                    : 1;
                globalEnv.define(name, new BuiltinFn(name, arity, args -> {
                    throw new IrijRuntimeError(name + ": not available in sandbox mode");
                }));
            }
        }
        this.moduleRegistry = new ModuleRegistry(this::loadModuleSource);
        StdModules.registerAll(moduleRegistry, out);
    }

    /**
     * Builtins that live in Interpreter.java (not Builtins.java).
     *
     * <p>Split rationale:
     * <ul>
     *   <li>{@link Builtins} — pure / stateless functions (arithmetic,
     *       strings, collections, json, etc.). They take {@code List<Object>}
     *       and return {@code Object}, no interpreter coupling.
     *   <li>Here — builtins that need interpreter internals:
     *     <ul>
     *       <li>invoke user code: {@code apply(fn, args)} for {@code spawn},
     *           {@code try}, {@code fold}, {@code await}, {@code timeout},
     *           {@code par}, {@code race}, {@code apply}, {@code verify-laws},
     *           {@code validate}, {@code raw-http-serve} handler, SSE send,
     *           {@code raw-db-transaction} thunk.
     *       <li>read/push fiber state: {@link #AVAILABLE_EFFECTS},
     *           {@link EffectSystem#STACK}.
     *       <li>hold interpreter-lifetime resources: nREPL sessions.
     *     </ul>
     * </ul>
     *
     * <p>If a builtin can be written as {@code (List&lt;Object&gt;) ->
     * Object} without referencing {@code this}, it belongs in
     * {@link Builtins}. Otherwise it lives here so its lambda can capture
     * {@code this}.
     */
    private void installInterpreterBuiltins() {
        // ── spawn — run a thunk in a virtual thread ──────────────────
        globalEnv.define("spawn", new BuiltinFn("spawn", 1, args -> {
            var thunk = args.get(0);
            var parentEffects = AVAILABLE_EFFECTS.get().peek();
            var thread = Thread.startVirtualThread(() -> {
                AVAILABLE_EFFECTS.get().push(parentEffects); // propagate
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

        // ── await — block until a fiber completes ─────────────────────
        globalEnv.define("await", new BuiltinFn("await", 1, args -> {
            if (!(args.get(0) instanceof Fiber f)) {
                throw new IrijRuntimeError(
                    "await expects a Fiber, got " + Values.typeName(args.get(0)), null);
            }
            try {
                return f.result().join();
            } catch (java.util.concurrent.CompletionException ce) {
                if (ce.getCause() instanceof IrijRuntimeError ire) throw ire;
                throw new IrijRuntimeError("Fiber failed: " + ce.getCause().getMessage(), null);
            }
        }));

        // ── timeout — run a thunk with a deadline ─────────────────────
        globalEnv.define("timeout", new BuiltinFn("timeout", 2, args -> {
            long ms = Builtins.toMillis(args.get(0));
            var thunk = args.get(1);
            var future = new java.util.concurrent.CompletableFuture<Object>();
            var parentStack = EffectSystem.STACK.get();
            var parentEffects = AVAILABLE_EFFECTS.get().peek();
            var thread = Thread.startVirtualThread(() -> {
                var fiberStack = EffectSystem.STACK.get();
                fiberStack.addAll(parentStack);
                AVAILABLE_EFFECTS.get().push(parentEffects);
                try {
                    future.complete(apply(thunk, List.of(), SourceLoc.UNKNOWN));
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
            try {
                return future.get(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                thread.interrupt();
                throw new IrijRuntimeError("timeout: operation exceeded " + ms + "ms", null);
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof IrijRuntimeError ire) throw ire;
                throw new IrijRuntimeError("timeout: " + e.getCause().getMessage(), null);
            } catch (InterruptedException e) {
                thread.interrupt();
                Thread.currentThread().interrupt();
                throw new IrijRuntimeError("timeout: interrupted", null);
            }
        }));

        // ── par — run thunks concurrently, combine results ────────────
        // par f (-> a) (-> b) ... → f a-result b-result ...
        globalEnv.define("par", new BuiltinFn("par", -1, args -> {
            if (args.size() < 2) {
                throw new IrijRuntimeError("par requires a combiner function and at least one thunk", null);
            }
            var combiner = args.get(0);
            var thunks = args.subList(1, args.size());
            var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Object>>();
            var threads = new java.util.ArrayList<Thread>();
            var parentStack = EffectSystem.STACK.get();
            var parentEffects = AVAILABLE_EFFECTS.get().peek();

            for (var thunk : thunks) {
                var future = new java.util.concurrent.CompletableFuture<Object>();
                var t = Thread.startVirtualThread(() -> {
                    var fiberStack = EffectSystem.STACK.get();
                    fiberStack.addAll(parentStack);
                    AVAILABLE_EFFECTS.get().push(parentEffects);
                    try {
                        future.complete(apply(thunk, List.of(), SourceLoc.UNKNOWN));
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                });
                futures.add(future);
                threads.add(t);
            }

            // Wait for all — if any fails, cancel the rest
            var results = new java.util.ArrayList<Object>();
            try {
                for (var f : futures) {
                    try {
                        results.add(f.join());
                    } catch (java.util.concurrent.CompletionException ce) {
                        // Cancel remaining
                        for (var t : threads) t.interrupt();
                        if (ce.getCause() instanceof IrijRuntimeError ire) throw ire;
                        throw new IrijRuntimeError("par: " + ce.getCause().getMessage(), null);
                    }
                }
            } catch (IrijRuntimeError e) {
                throw e;
            }
            return apply(combiner, results, SourceLoc.UNKNOWN);
        }));

        // ── race — first thunk to succeed wins, cancel others ─────────
        globalEnv.define("race", new BuiltinFn("race", -1, args -> {
            if (args.isEmpty()) {
                throw new IrijRuntimeError("race requires at least one thunk", null);
            }
            var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Object>>();
            var threads = new java.util.ArrayList<Thread>();
            var parentStack = EffectSystem.STACK.get();
            var parentEffects = AVAILABLE_EFFECTS.get().peek();

            for (var thunk : args) {
                var future = new java.util.concurrent.CompletableFuture<Object>();
                var t = Thread.startVirtualThread(() -> {
                    var fiberStack = EffectSystem.STACK.get();
                    fiberStack.addAll(parentStack);
                    AVAILABLE_EFFECTS.get().push(parentEffects);
                    try {
                        future.complete(apply(thunk, List.of(), SourceLoc.UNKNOWN));
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                });
                futures.add(future);
                threads.add(t);
            }

            // Wait for first success; interrupt losers the instant a winner appears.
            var result = new java.util.concurrent.CompletableFuture<Object>();
            var errors = java.util.Collections.synchronizedList(new java.util.ArrayList<Throwable>());
            for (int i = 0; i < futures.size(); i++) {
                futures.get(i).whenComplete((value, ex) -> {
                    if (ex != null) {
                        errors.add(ex);
                        if (errors.size() == futures.size()) {
                            result.completeExceptionally(errors.get(0));
                        }
                    } else {
                        if (result.complete(value)) {
                            // We are the winner — interrupt losers now.
                            for (var t : threads) {
                                if (t.isAlive()) t.interrupt();
                            }
                        }
                    }
                });
            }

            try {
                return result.join();
            } catch (java.util.concurrent.CompletionException ce) {
                for (var t : threads) t.interrupt();
                if (ce.getCause() instanceof IrijRuntimeError ire) throw ire;
                throw new IrijRuntimeError("race: all thunks failed", null);
            }
        }));

        // ── apply — spread last arg (vector) into function call ─────────
        globalEnv.define("apply", new BuiltinFn("apply", -1, args -> {
            if (args.size() < 2) {
                throw new IrijRuntimeError("apply requires a function and at least one argument", null);
            }
            var fn = args.get(0);
            var lastArg = args.get(args.size() - 1);
            var spreadElems = Builtins.toList(lastArg);
            var allArgs = new ArrayList<Object>();
            for (int i = 1; i < args.size() - 1; i++) {
                allArgs.add(args.get(i));
            }
            allArgs.addAll(spreadElems);
            return apply(fn, allArgs, SourceLoc.UNKNOWN);
        }));

        // ── verify-laws — QuickCheck-style law verification ─────────────
        globalEnv.define("verify-laws", new BuiltinFn("verify-laws", -1, args -> {
            if (args.isEmpty()) {
                throw new IrijRuntimeError("verify-laws requires a protocol name or function name", null);
            }
            var target = args.get(0);
            int trials = args.size() > 1 && args.get(1) instanceof Long n ? n.intValue() : 100;
            if (target instanceof ProtocolDescriptor pd) {
                return verifyProtoLaws(pd, trials);
            }
            if (target instanceof String s) {
                // Look up protocol by name
                var pd = protocols.get(s);
                if (pd != null) return verifyProtoLaws(pd, trials);
                // Look up fn-level laws by name
                var laws = fnLaws.get(s);
                if (laws != null) return verifyFnLaws(s, laws, fnLawEnvs.get(s), trials);
            }
            throw new IrijRuntimeError("verify-laws: expected a protocol or fn name, got " + Values.typeName(target), null);
        }));

        // ── validate — check value against spec name, return Ok/Err ────
        globalEnv.define("validate", new BuiltinFn("validate", 2, args -> {
            var specName = args.get(0);
            var value = args.get(1);
            if (!(specName instanceof String name)) {
                throw new IrijRuntimeError("validate: first argument must be a spec name (string)", null);
            }
            try {
                var result = validateByName(value, name, SourceLoc.UNKNOWN);
                return new Tagged("Ok", List.of(result));
            } catch (IrijRuntimeError e) {
                return new Tagged("Err", List.of(e.getMessage()));
            }
        }));

        // ── validate! — check value against spec name, return or throw ──
        globalEnv.define("validate!", new BuiltinFn("validate!", 2, args -> {
            var specName = args.get(0);
            var value = args.get(1);
            if (!(specName instanceof String name)) {
                throw new IrijRuntimeError("validate!: first argument must be a spec name (string)", null);
            }
            return validateByName(value, name, SourceLoc.UNKNOWN);
        }));

        // ── raw-db-transaction — run a thunk in a SQL transaction ───────
        globalEnv.define("raw-db-transaction", new BuiltinFn("raw-db-transaction", 2, List.of("Db"), args -> {
            var conn = Builtins.extractConnection(args.get(0), "raw-db-transaction");
            var thunk = args.get(1);
            try {
                synchronized (conn) {
                    conn.setAutoCommit(false);
                    try {
                        var result = apply(thunk, List.of(), null);
                        conn.commit();
                        conn.setAutoCommit(true);
                        return result;
                    } catch (Exception e) {
                        conn.rollback();
                        conn.setAutoCommit(true);
                        throw e;
                    }
                }
            } catch (IrijRuntimeError e) {
                throw e;
            } catch (java.sql.SQLException e) {
                throw new IrijRuntimeError("raw-db-transaction: " + e.getMessage(), null);
            }
        }));

        // ── raw-nrepl-eval-sandboxed — evaluate code in a sandboxed interpreter ──
        // raw-nrepl-eval-sandboxed code timeout-ms
        //   Returns: {value= stdout= error= ok=}
        globalEnv.define("raw-nrepl-eval-sandboxed", new BuiltinFn("raw-nrepl-eval-sandboxed", 2, args -> {
            var code = Builtins.asString(args.get(0), "raw-nrepl-eval-sandboxed");
            var timeoutMs = Builtins.asLong(args.get(1), "raw-nrepl-eval-sandboxed");

            var baos = new java.io.ByteArrayOutputStream();
            var evalOut = new PrintStream(baos);
            var sandboxedInterp = new Interpreter(evalOut, true);
            sandboxedInterp.setSpecLintEnabled(false);

            var resultMap = new LinkedHashMap<String, Object>();
            var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                var parseResult = IrijParseDriver.parse(code + "\n");
                if (parseResult.hasErrors()) {
                    throw new IrijRuntimeError("Parse error: " + parseResult.errors());
                }
                var ast = new AstBuilder().build(parseResult.tree());
                return sandboxedInterp.run(ast);
            });

            try {
                var value = future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                resultMap.put("value", Values.toIrijString(value));
                resultMap.put("stdout", baos.toString());
                resultMap.put("error", Values.UNIT);
                resultMap.put("ok", Boolean.TRUE);
            } catch (java.util.concurrent.TimeoutException e) {
                future.cancel(true);
                resultMap.put("value", Values.UNIT);
                resultMap.put("stdout", baos.toString());
                resultMap.put("error", "Evaluation timed out (" + timeoutMs + "ms)");
                resultMap.put("ok", Boolean.FALSE);
            } catch (java.util.concurrent.ExecutionException e) {
                var cause = e.getCause();
                var errorMsg = cause != null ? cause.getMessage() : e.getMessage();
                resultMap.put("value", Values.UNIT);
                resultMap.put("stdout", baos.toString());
                resultMap.put("error", errorMsg != null ? errorMsg : "Unknown error");
                resultMap.put("ok", Boolean.FALSE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                resultMap.put("value", Values.UNIT);
                resultMap.put("stdout", baos.toString());
                resultMap.put("error", "Evaluation interrupted");
                resultMap.put("ok", Boolean.FALSE);
            }

            return new IrijMap(resultMap);
        }));

        // ── raw-http-serve — start an HTTP server ────────────────────────
        // raw-http-serve port handler-fn
        //   handler-fn receives: {method= path= headers= body= query=}
        //   handler-fn returns:  {status= body= headers=} (or just {body=})
        globalEnv.define("raw-http-serve", new BuiltinFn("raw-http-serve", 2, List.of("Serve"), args -> {
            if (!(args.get(0) instanceof Long port))
                throw new IrijRuntimeError("raw-http-serve: port must be Int", null);
            var handler = args.get(1);

            try {
                var server = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress(port.intValue()), 0);

                // Effect propagation for request handler threads
                var parentEffects = AVAILABLE_EFFECTS.get().peek();
                var parentStack = EffectSystem.STACK.get();

                // Resolve static files relative to script directory
                var scriptDir = sourcePath != null
                    ? sourcePath.toAbsolutePath()
                    : java.nio.file.Path.of("").toAbsolutePath();
                final boolean isBundled = bundledMode;

                server.createContext("/", exchange -> {
                    AVAILABLE_EFFECTS.get().push(parentEffects);
                    var fiberStack = EffectSystem.STACK.get();
                    fiberStack.addAll(parentStack);
                    try {
                        // Try static file first
                        var reqPath = exchange.getRequestURI().getPath();
                        if (reqPath.length() > 1 && !reqPath.contains("..")) {
                            // In bundled mode, check __irij_resources/ on classpath first
                            if (isBundled) {
                                var resourcePath = "__irij_resources/" + reqPath.substring(1);
                                var resStream = getClass().getClassLoader().getResourceAsStream(resourcePath);
                                if (resStream != null) {
                                    try (resStream) {
                                        var fileBytes = resStream.readAllBytes();
                                        var mime = guessMimeType(reqPath);
                                        exchange.getResponseHeaders().set("Content-Type", mime);
                                        exchange.sendResponseHeaders(200, fileBytes.length);
                                        try (var os = exchange.getResponseBody()) { os.write(fileBytes); }
                                        return;
                                    }
                                }
                                // Also check __irij_app/ for co-located static files
                                var appPath = "__irij_app/" + reqPath.substring(1);
                                var appStream = getClass().getClassLoader().getResourceAsStream(appPath);
                                if (appStream != null) {
                                    try (appStream) {
                                        var fileBytes = appStream.readAllBytes();
                                        var mime = guessMimeType(reqPath);
                                        exchange.getResponseHeaders().set("Content-Type", mime);
                                        exchange.sendResponseHeaders(200, fileBytes.length);
                                        try (var os = exchange.getResponseBody()) { os.write(fileBytes); }
                                        return;
                                    }
                                }
                            }
                            // Local mode: probe scriptDir/resources/ first (matches bundled layout)
                            var resourcesPath = scriptDir.resolve("resources").resolve(reqPath.substring(1)).normalize();
                            var resourcesRoot = scriptDir.resolve("resources").normalize();
                            if (resourcesPath.startsWith(resourcesRoot) && java.nio.file.Files.isRegularFile(resourcesPath)) {
                                var fileBytes = java.nio.file.Files.readAllBytes(resourcesPath);
                                var mime = java.nio.file.Files.probeContentType(resourcesPath);
                                if (mime == null) mime = "application/octet-stream";
                                exchange.getResponseHeaders().set("Content-Type", mime);
                                exchange.sendResponseHeaders(200, fileBytes.length);
                                try (var os = exchange.getResponseBody()) { os.write(fileBytes); }
                                return;
                            }
                            var filePath = scriptDir.resolve(reqPath.substring(1)).normalize();
                            if (filePath.startsWith(scriptDir) && java.nio.file.Files.isRegularFile(filePath)) {
                                var fileBytes = java.nio.file.Files.readAllBytes(filePath);
                                var mime = java.nio.file.Files.probeContentType(filePath);
                                if (mime == null) mime = "application/octet-stream";
                                exchange.getResponseHeaders().set("Content-Type", mime);
                                exchange.sendResponseHeaders(200, fileBytes.length);
                                try (var os = exchange.getResponseBody()) { os.write(fileBytes); }
                                return;
                            }
                        }

                        // Build request map
                        var reqMap = new LinkedHashMap<String, Object>();
                        reqMap.put("method", exchange.getRequestMethod());
                        var uri = exchange.getRequestURI();
                        reqMap.put("path", uri.getPath());
                        var rawQuery = uri.getQuery() != null ? uri.getQuery() : "";
                        reqMap.put("query", rawQuery);
                        reqMap.put("params", new IrijMap(parseQueryParams(rawQuery)));
                        var reqHeaders = new LinkedHashMap<String, Object>();
                        exchange.getRequestHeaders().forEach((k, v) ->
                            reqHeaders.put(k.toLowerCase(), v.size() == 1 ? v.get(0) : String.join(", ", v)));
                        reqMap.put("headers", new IrijMap(reqHeaders));
                        var bodyBytes = exchange.getRequestBody().readAllBytes();
                        reqMap.put("body", new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8));
                        reqMap.put("__body_bytes", bodyBytes);
                        var req = new IrijMap(reqMap);

                        // Inject the raw exchange into the request map so SSE can use it
                        reqMap.put("__exchange", exchange);
                        req = new IrijMap(reqMap);

                        // Call Irij handler
                        var resp = apply(handler, List.of(req), SourceLoc.UNKNOWN);

                        // If handler returned an SseWriter, the stream is being
                        // managed by user code — do NOT close the exchange here.
                        if (resp instanceof SseWriter) {
                            // SSE stream — handler is responsible for the lifecycle.
                            // Block this handler thread until the SSE writer is closed
                            // so the HTTP exchange stays alive.
                            var sse = (SseWriter) resp;
                            while (!sse.isClosed()) {
                                try { Thread.sleep(500); }
                                catch (InterruptedException ie) {
                                    sse.close();
                                    break;
                                }
                            }
                            return; // exchange already closed by SseWriter
                        }

                        // Extract response
                        long status = 200;
                        String respBody = "";
                        String filePath = null;
                        Map<String, Object> respHeaders = Map.of();
                        if (resp instanceof IrijMap rm) {
                            var e = rm.entries();
                            if (e.get("status") instanceof Long s) status = s;
                            if (e.get("body") instanceof String b) respBody = b;
                            if (e.get("file") instanceof String f) filePath = f;
                            if (e.get("headers") instanceof IrijMap hm) respHeaders = hm.entries();
                        } else if (resp instanceof String s) {
                            respBody = s;
                        }

                        // Set response headers
                        for (var h : respHeaders.entrySet()) {
                            exchange.getResponseHeaders().set(h.getKey(),
                                Values.toIrijString(h.getValue()));
                        }
                        if (!respHeaders.containsKey("content-type")
                                && !respHeaders.containsKey("Content-Type")) {
                            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                        }

                        // If response has a "file" key, send binary file directly
                        if (filePath != null) {
                            var fileBytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(filePath));
                            exchange.sendResponseHeaders((int) status, fileBytes.length);
                            try (var os = exchange.getResponseBody()) {
                                os.write(fileBytes);
                            }
                        } else {
			    var respBytes = respBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		            if (respBytes.length > 0) { // TODO: Hack for SSE. Need to discuss...
			        exchange.sendResponseHeaders((int) status, respBytes.length);
			    }
                            try (var os = exchange.getResponseBody()) {
                                os.write(respBytes);
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("HTTP 500 " + exchange.getRequestMethod() + " " + exchange.getRequestURI() + ": " + e.getMessage());
                        e.printStackTrace(System.err);
                        try {
                            var errMsg = "Internal Server Error: " + e.getMessage();
                            var errBytes = errMsg.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            exchange.sendResponseHeaders(500, errBytes.length);
                            try (var os = exchange.getResponseBody()) { os.write(errBytes); }
                        } catch (Exception ignored) {}
                    }
                });

                server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
                server.start();
                out.println("Irij HTTP server listening on http://localhost:" + port);

                // Block main thread until interrupted
                try { Thread.currentThread().join(); }
                catch (InterruptedException e) { server.stop(0); }

                return Values.UNIT;
            } catch (java.io.IOException e) {
                throw new IrijRuntimeError("raw-http-serve: " + e.getMessage(), null);
            }
        }));

        // ── raw-sse-response — upgrade an HTTP exchange to SSE stream ────
        // raw-sse-response request
        //   Sets SSE headers and returns an SseWriter for streaming events.
        //   The request map must contain the __exchange key (injected by raw-http-serve).
        globalEnv.define("raw-sse-response", new BuiltinFn("raw-sse-response", 1, List.of("Serve"), args -> {
            if (!(args.get(0) instanceof IrijMap reqMap))
                throw new IrijRuntimeError("raw-sse-response: expected request map", null);
            var exchange = reqMap.entries().get("__exchange");
            if (!(exchange instanceof com.sun.net.httpserver.HttpExchange httpExchange))
                throw new IrijRuntimeError("raw-sse-response: no __exchange in request (only works inside raw-http-serve handler)", null);
            try {
                httpExchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                httpExchange.getResponseHeaders().set("Cache-Control", "no-cache");
                httpExchange.getResponseHeaders().set("Connection", "keep-alive");
                httpExchange.getResponseHeaders().set("X-Accel-Buffering", "no");
                // 0 = chunked transfer encoding
                httpExchange.sendResponseHeaders(200, 0);
                var os = httpExchange.getResponseBody();
                return new SseWriter(httpExchange, os);
            } catch (java.io.IOException e) {
                throw new IrijRuntimeError("raw-sse-response: " + e.getMessage(), null);
            }
        }));

        // ── raw-sse-send — write an SSE event ────────────────────────────
        // raw-sse-send sse-writer event-type data-string
        globalEnv.define("raw-sse-send", new BuiltinFn("raw-sse-send", 3, List.of("Serve"), args -> {
            if (!(args.get(0) instanceof SseWriter sse))
                throw new IrijRuntimeError("raw-sse-send: first arg must be SseWriter", null);
            var eventType = Builtins.asString(args.get(1), "raw-sse-send");
            var data = Builtins.asString(args.get(2), "raw-sse-send");
            try {
                sse.send(eventType, data);
            } catch (java.io.IOException e) {
                throw new IrijRuntimeError("raw-sse-send: " + e.getMessage(), null);
            }
            return Values.UNIT;
        }));

        // ── raw-sse-close — close an SSE stream ─────────────────────────
        // raw-sse-close sse-writer
        globalEnv.define("raw-sse-close", new BuiltinFn("raw-sse-close", 1, List.of("Serve"), args -> {
            if (!(args.get(0) instanceof SseWriter sse))
                throw new IrijRuntimeError("raw-sse-close: first arg must be SseWriter", null);
            sse.close();
            return Values.UNIT;
        }));

        // ── raw-sse-closed? — check if SSE stream is closed ─────────────
        globalEnv.define("raw-sse-closed?", new BuiltinFn("raw-sse-closed?", 1, List.of("Serve"), args -> {
            if (!(args.get(0) instanceof SseWriter sse))
                throw new IrijRuntimeError("raw-sse-closed?: first arg must be SseWriter", null);
            return sse.isClosed();
        }));

        // ══════════════════════════════════════════════════════════════════
        // Session manager — persistent sandboxed interpreters by UUID
        // ══════════════════════════════════════════════════════════════════

        var sessions = new java.util.concurrent.ConcurrentHashMap<String, Object[]>();
        // sessions: UUID -> [Interpreter, PrintStream, ByteArrayOutputStream, lastAccessMs, SseWriter?]

        // Auto-evict sessions idle > SESSION_TTL_MS every SESSION_SWEEP_MS.
        // Prevents memory leaks when callers forget raw-session-cleanup.
        final long SESSION_TTL_MS   = Long.getLong("irij.session.ttl.ms",   30L * 60_000L); // 30 min
        final long SESSION_SWEEP_MS = Long.getLong("irij.session.sweep.ms", 60_000L);       // 60 s
        var sweeper = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "irij-session-sweeper");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleAtFixedRate(() -> {
            var now = System.currentTimeMillis();
            sessions.entrySet().removeIf(e -> {
                var lastAccess = (long) ((Object[]) e.getValue())[3];
                return now - lastAccess > SESSION_TTL_MS;
            });
        }, SESSION_SWEEP_MS, SESSION_SWEEP_MS, java.util.concurrent.TimeUnit.MILLISECONDS);

        // ── raw-session-create — create a new sandboxed interpreter session
        globalEnv.define("raw-session-create", new BuiltinFn("raw-session-create", 1, args -> {
            var id = java.util.UUID.randomUUID().toString();
            var baos = new java.io.ByteArrayOutputStream();
            // SseWriter holder — when set, output also streams via SSE
            final SseWriter[] sseHolder = { null };
            var sessionOut = new PrintStream(new java.io.OutputStream() {
                @Override public void write(int b) throws java.io.IOException {
                    baos.write(b);
                }
                @Override public void write(byte[] buf, int off, int len) throws java.io.IOException {
                    baos.write(buf, off, len);
                    // Forward to SSE if subscribed
                    var sse = sseHolder[0];
                    if (sse != null && !sse.isClosed()) {
                        // Send complete lines to SSE
                        var text = new String(buf, off, len, java.nio.charset.StandardCharsets.UTF_8);
                        try { sse.send("message", "{\"type\":\"stdout\",\"text\":" + jsonEscape(text) + "}"); }
                        catch (Exception ignored) { sseHolder[0] = null; }
                    }
                }
                @Override public void flush() throws java.io.IOException {
                    baos.flush();
                }
            }, true);
            var interp = new Interpreter(sessionOut, true);
            interp.setSpecLintEnabled(false);
            sessions.put(id, new Object[]{ interp, sessionOut, baos, System.currentTimeMillis(), sseHolder });
            return id;
        }));

        // ── raw-session-eval — evaluate code in an existing session
        // raw-session-eval session-id code timeout-ms
        //   Returns: {value= stdout= error= ok=}
        globalEnv.define("raw-session-eval", new BuiltinFn("raw-session-eval", 3, args -> {
            var sessionId = Builtins.asString(args.get(0), "raw-session-eval");
            var code = Builtins.asString(args.get(1), "raw-session-eval");
            var timeoutMs = Builtins.asLong(args.get(2), "raw-session-eval");

            var session = sessions.get(sessionId);
            if (session == null)
                throw new IrijRuntimeError("raw-session-eval: no session with id " + sessionId, null);

            var interp = (Interpreter) session[0];
            var baos = (java.io.ByteArrayOutputStream) session[2];
            session[3] = System.currentTimeMillis(); // touch

            // Clear stdout buffer before eval
            baos.reset();

            var resultMap = new LinkedHashMap<String, Object>();
            var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                var parseResult = IrijParseDriver.parse(code + "\n");
                if (parseResult.hasErrors()) {
                    throw new IrijRuntimeError("Parse error: " + parseResult.errors());
                }
                var ast = new AstBuilder().build(parseResult.tree());
                return interp.run(ast);
            });

            try {
                var value = future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                resultMap.put("value", Values.toIrijString(value));
                resultMap.put("stdout", baos.toString());
                resultMap.put("error", Values.UNIT);
                resultMap.put("ok", Boolean.TRUE);
            } catch (java.util.concurrent.TimeoutException e) {
                future.cancel(true);
                resultMap.put("value", Values.UNIT);
                resultMap.put("stdout", baos.toString());
                resultMap.put("error", "Evaluation timed out (" + timeoutMs + "ms)");
                resultMap.put("ok", Boolean.FALSE);
            } catch (java.util.concurrent.ExecutionException e) {
                var cause = e.getCause();
                var errorMsg = cause != null ? cause.getMessage() : e.getMessage();
                resultMap.put("value", Values.UNIT);
                resultMap.put("stdout", baos.toString());
                resultMap.put("error", errorMsg != null ? errorMsg : "Unknown error");
                resultMap.put("ok", Boolean.FALSE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                resultMap.put("value", Values.UNIT);
                resultMap.put("stdout", baos.toString());
                resultMap.put("error", "Evaluation interrupted");
                resultMap.put("ok", Boolean.FALSE);
            }

            return new IrijMap(resultMap);
        }));

        // ── raw-session-destroy — destroy a session
        globalEnv.define("raw-session-destroy", new BuiltinFn("raw-session-destroy", 1, args -> {
            var sessionId = Builtins.asString(args.get(0), "raw-session-destroy");
            sessions.remove(sessionId);
            return Values.UNIT;
        }));

        // ── raw-session-subscribe — connect SSE writer to session output stream
        // raw-session-subscribe session-id sse-writer
        globalEnv.define("raw-session-subscribe", new BuiltinFn("raw-session-subscribe", 2, args -> {
            var sessionId = Builtins.asString(args.get(0), "raw-session-subscribe");
            if (!(args.get(1) instanceof SseWriter sse))
                throw new IrijRuntimeError("raw-session-subscribe: second arg must be SseWriter", null);
            var session = sessions.get(sessionId);
            if (session == null)
                throw new IrijRuntimeError("raw-session-subscribe: no session with id " + sessionId, null);
            @SuppressWarnings("unchecked")
            var sseHolder = (SseWriter[]) session[4];
            sseHolder[0] = sse;
            return Values.UNIT;
        }));

        // ── raw-session-unsubscribe — disconnect SSE writer from session
        globalEnv.define("raw-session-unsubscribe", new BuiltinFn("raw-session-unsubscribe", 1, args -> {
            var sessionId = Builtins.asString(args.get(0), "raw-session-unsubscribe");
            var session = sessions.get(sessionId);
            if (session != null) {
                @SuppressWarnings("unchecked")
                var sseHolder = (SseWriter[]) session[4];
                sseHolder[0] = null;
            }
            return Values.UNIT;
        }));

        // ── raw-session-cleanup — remove sessions idle for > N ms
        globalEnv.define("raw-session-cleanup", new BuiltinFn("raw-session-cleanup", 1, args -> {
            var maxIdleMs = Builtins.asLong(args.get(0), "raw-session-cleanup");
            var now = System.currentTimeMillis();
            var removed = new java.util.concurrent.atomic.AtomicLong(0);
            sessions.entrySet().removeIf(e -> {
                var lastAccess = (long) ((Object[]) e.getValue())[3];
                if (now - lastAccess > maxIdleMs) {
                    removed.incrementAndGet();
                    return true;
                }
                return false;
            });
            return removed.get();
        }));
    }

    /** Registry of fn-level laws, populated during fn declaration. */
    private final Map<String, List<Decl.FnLaw>> fnLaws = new LinkedHashMap<>();
    private final Map<String, Environment> fnLawEnvs = new LinkedHashMap<>();

    /** Random value generator for forall-bound variables. */
    private final java.util.Random lawRandom = new java.util.Random(42);

    private Object generateRandomValue() {
        return switch (lawRandom.nextInt(5)) {
            case 0 -> (long) (lawRandom.nextInt(201) - 100);   // Int: -100..100
            case 1 -> lawRandom.nextDouble() * 200.0 - 100.0;  // Float
            case 2 -> lawRandom.nextBoolean();                  // Bool
            case 3 -> {                                          // Str
                int len = lawRandom.nextInt(10);
                var sb = new StringBuilder(len);
                for (int i = 0; i < len; i++) sb.append((char)('a' + lawRandom.nextInt(26)));
                yield sb.toString();
            }
            case 4 -> {                                          // small Vector
                int len = lawRandom.nextInt(6);
                var elems = new ArrayList<Object>(len);
                for (int i = 0; i < len; i++) elems.add((long) lawRandom.nextInt(21) - 10);
                yield new IrijVector(elems);
            }
            default -> 0L;
        };
    }

    /** Generate random values of a specific type (for typed protocol impls).
     *  Now spec-aware: if the type name matches a user-declared spec, generates
     *  valid instances of that spec (random variant for sum, random fields for product). */
    private Object generateRandomForType(String typeName) {
        return switch (typeName) {
            case "Int" -> (long) (lawRandom.nextInt(201) - 100);
            case "Float" -> lawRandom.nextDouble() * 200.0 - 100.0;
            case "Bool" -> lawRandom.nextBoolean();
            case "Keyword" -> new Keyword(List.of("ok", "err", "foo", "bar", "baz")
                .get(lawRandom.nextInt(5)));
            case "Str" -> {
                int len = lawRandom.nextInt(10);
                var sb = new StringBuilder(len);
                for (int i = 0; i < len; i++) sb.append((char)('a' + lawRandom.nextInt(26)));
                yield sb.toString();
            }
            case "Vector" -> {
                int len = lawRandom.nextInt(6);
                var elems = new ArrayList<Object>(len);
                for (int i = 0; i < len; i++) elems.add((long) lawRandom.nextInt(21) - 10);
                yield new IrijVector(elems);
            }
            default -> {
                // Check if it's a user-declared spec — generate a random valid instance
                var descriptor = specRegistry.get(typeName);
                if (descriptor != null) {
                    yield generateRandomForSpec(descriptor);
                }
                yield generateRandomValue();
            }
        };
    }

    /**
     * Generate a random valid instance of a user-declared spec.
     * Sum specs: pick a random variant, generate random fields.
     * Product specs: generate random values for all fields.
     */
    private Object generateRandomForSpec(Values.SpecDescriptor spec) {
        return switch (spec.body()) {
            case Decl.SpecBody.SumSpec ss -> {
                // Pick a random variant
                var variant = ss.variants().get(lawRandom.nextInt(ss.variants().size()));
                var fields = new ArrayList<Object>();
                for (int i = 0; i < variant.arity(); i++) {
                    fields.add(generateRandomValue());
                }
                yield new Tagged(variant.name(), fields, null, spec.name());
            }
            case Decl.SpecBody.ProductSpec ps -> {
                // Generate random values for all fields
                var fields = new ArrayList<Object>();
                var named = new java.util.LinkedHashMap<String, Object>();
                for (var field : ps.fields()) {
                    var val = generateRandomValue();
                    fields.add(val);
                    named.put(field.name(), val);
                }
                yield new Tagged(spec.name(), fields, named, spec.name());
            }
        };
    }

    private Object verifyProtoLaws(ProtocolDescriptor pd, int trials) {
        if (pd.laws().isEmpty()) {
            out.println("Protocol '" + pd.name() + "' has no laws to verify.");
            return Values.UNIT;
        }
        var results = new ArrayList<Object>();
        // Verify laws for each registered type implementation
        for (var entry : pd.impls().entrySet()) {
            String typeName = entry.getKey();
            Map<String, Object> typeImpls = entry.getValue();
            for (var law : pd.laws()) {
                boolean passed = true;
                String failInfo = null;
                for (int i = 0; i < trials; i++) {
                    var childEnv = globalEnv.child();
                    // Pre-bind protocol methods to their concrete implementations for this type.
                    // This allows laws like `append empty x == x` to work — `empty` resolves
                    // to the concrete value (e.g., 0 for Int) instead of the dispatch function.
                    for (var methodName : pd.methodNames()) {
                        Object implBinding = typeImpls.get(methodName);
                        if (implBinding != null) {
                            childEnv.define(methodName, implBinding);
                        }
                    }
                    // Bind forall vars with random values of the impl type
                    for (var varName : law.forallVars()) {
                        childEnv.define(varName, generateRandomForType(typeName));
                    }
                    try {
                        var result = eval(law.body(), childEnv);
                        if (!Values.isTruthy(result)) {
                            passed = false;
                            var bindings = new StringBuilder();
                            for (var varName : law.forallVars()) {
                                if (!bindings.isEmpty()) bindings.append(", ");
                                bindings.append(varName).append("=")
                                    .append(Values.toIrijString(childEnv.lookup(varName)));
                            }
                            failInfo = "with " + bindings;
                            break;
                        }
                    } catch (IrijRuntimeError e) {
                        passed = false;
                        failInfo = e.getMessage();
                        break;
                    }
                }
                var desc = pd.name() + "/" + typeName + ": " + law.name();
                if (passed) {
                    results.add(new Tagged("Pass", List.of(desc)));
                    out.println("  PASS  " + desc + " (" + trials + " trials)");
                } else {
                    results.add(new Tagged("Fail", List.of(desc, failInfo)));
                    out.println("  FAIL  " + desc + " — " + failInfo);
                }
            }
        }
        return new IrijVector(results);
    }

    private Object verifyFnLaws(String fnName, List<Decl.FnLaw> laws, Environment env, int trials) {
        var results = new ArrayList<Object>();
        for (var law : laws) {
            boolean passed = true;
            String failInfo = null;
            int validTrials = 0;
            int maxAttempts = trials * 5; // try harder to find valid inputs
            for (int i = 0; i < maxAttempts && validTrials < trials; i++) {
                var childEnv = env.child();
                for (var varName : law.forallVars()) {
                    childEnv.define(varName, generateRandomValue());
                }
                try {
                    var result = eval(law.body(), childEnv);
                    validTrials++;
                    if (!Values.isTruthy(result)) {
                        passed = false;
                        var bindings = new StringBuilder();
                        for (var varName : law.forallVars()) {
                            if (!bindings.isEmpty()) bindings.append(", ");
                            bindings.append(varName).append("=")
                                .append(Values.toIrijString(childEnv.lookup(varName)));
                        }
                        failInfo = "with " + bindings;
                        break;
                    }
                } catch (IrijRuntimeError e) {
                    // Type errors → skip (input didn't satisfy implicit preconditions)
                    // Genuine assertion errors → fail
                    if (e.getMessage() != null && (e.getMessage().contains("expects a")
                        || e.getMessage().contains("Cannot apply")
                        || e.getMessage().contains("Cannot concat")
                        || e.getMessage().contains("Cannot compare"))) {
                        continue; // skip invalid input
                    }
                    passed = false;
                    failInfo = e.getMessage();
                    break;
                }
            }
            var desc = fnName + ": " + law.name();
            if (validTrials == 0) {
                results.add(new Tagged("Skip", List.of(desc)));
                out.println("  SKIP  " + desc + " (no valid inputs found)");
            } else if (passed) {
                results.add(new Tagged("Pass", List.of(desc)));
                out.println("  PASS  " + desc + " (" + validTrials + " trials)");
            } else {
                results.add(new Tagged("Fail", List.of(desc, failInfo)));
                out.println("  FAIL  " + desc + " — " + failInfo);
            }
        }
        return new IrijVector(results);
    }

    public Interpreter() {
        this(System.out);
    }

    /** Set the source root for file-based module resolution and static file serving. */
    public void setSourcePath(Path sourcePath) {
        this.sourcePath = sourcePath;
        moduleRegistry.setSourcePath(sourcePath);
    }

    /**
     * Enable bundled JAR mode. In this mode, deps and resources are loaded
     * from the classpath (__irij_deps/, __irij_resources/, __irij_app/) instead
     * of the file system.
     */
    public void setBundledMode(boolean bundled) {
        this.bundledMode = bundled;
        moduleRegistry.setBundledMode(bundled);
    }

    /**
     * Load seeds from irij.toml in the given project root and register their paths.
     * Call this after setSourcePath() and before run().
     */
    public void loadDeps(Path projectRoot) {
        var tomlFile = projectRoot.resolve("irij.toml");
        try {
            var deps = ProjectFile.parseDeps(tomlFile);
            if (deps.isEmpty()) return;
            var resolver = new DependencyResolver(projectRoot, out);
            var resolved = resolver.resolveAll(deps);
            for (var entry : resolved.entrySet()) {
                moduleRegistry.addDepPath(entry.getKey(), entry.getValue());
            }
        } catch (ProjectFile.ParseError e) {
            throw new IrijRuntimeError("Error in irij.toml: " + e.getMessage(),
                Node.SourceLoc.UNKNOWN);
        } catch (java.io.IOException e) {
            throw new IrijRuntimeError("Error loading irij.toml: " + e.getMessage(),
                Node.SourceLoc.UNKNOWN);
        }
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
            case Decl.SpecDecl sd -> {
                registerSpecConstructors(sd, env);
                yield "<spec " + sd.name() + ">";
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
            case Decl.ScopeDecl sd -> execScope(sd.scope(), env);
            case Decl.ModDecl md -> evalModDecl(md);
            case Decl.UseDecl ud -> evalUseDecl(ud, env);
            case Decl.PubDecl pd -> evalPubDecl(pd, env);
            case Decl.EffectDecl ed -> evalEffectDecl(ed, env);
            case Decl.HandlerDecl hd -> evalHandlerDecl(hd, env);
            case Decl.ProtoDecl pd -> evalProtoDecl(pd, env);
            case Decl.ImplDecl id -> evalImplDecl(id, env);
            case Decl.RoleDecl rd -> Values.UNIT; // stub
            case Decl.StubDecl sd -> {
                out.println("[warn] " + sd.kind() + " '" + sd.name()
                    + "' is a not-yet-implemented language feature; declaration ignored");
                yield Values.UNIT;
            }
        };
    }

    private Object makeFnValue(Decl.FnDecl fn, Environment env) {
        // Unannotated fn = pure (empty effect row). Only anonymous lambdas get null (inherit context).
        var effectRow = fn.effectRow() != null ? fn.effectRow() : List.<String>of();
        var specs = fn.specAnnotations();
        Object baseFn = switch (fn.body()) {
            case Decl.FnBody.LambdaBody lb ->
                new Lambda(lb.params(), lb.restParam(), lb.body(), env, fn.name(), effectRow, specs);
            case Decl.FnBody.MatchArmsBody mab ->
                new MatchFn(fn.name(), mab.arms(), env, effectRow, specs);
            case Decl.FnBody.ImperativeBody ib ->
                new ImperativeFn(fn.name(), ib.params(), ib.restParam(), ib.stmts(), env, effectRow, specs);
            case Decl.FnBody.NoBody() ->
                Values.UNIT; // type-only declaration
        };
        // Wrap with contracts if any conditions exist
        boolean hasContracts = !fn.preConditions().isEmpty() || !fn.postConditions().isEmpty()
            || !fn.inContracts().isEmpty() || !fn.outContracts().isEmpty();
        if (hasContracts) {
            var evalPres = fn.preConditions().stream()
                .map(e -> eval(e, env)).toList();
            var evalPosts = fn.postConditions().stream()
                .map(e -> eval(e, env)).toList();
            var evalIns = fn.inContracts().stream()
                .map(e -> eval(e, env)).toList();
            var evalOuts = fn.outContracts().stream()
                .map(e -> eval(e, env)).toList();
            return new ContractedFn(baseFn, evalPres, evalPosts,
                evalIns, evalOuts, fn.name(), currentModuleName, fn.loc());
        }
        // Store fn-level laws for later verification
        if (!fn.fnLaws().isEmpty()) {
            fnLaws.put(fn.name(), fn.fnLaws());
            fnLawEnvs.put(fn.name(), env);
        }
        return baseFn;
    }

    /** A match-arm function: tries each arm against the argument. */
    record MatchFn(String name, List<Expr.MatchArm> arms, Environment closure,
                   List<String> effectRow, List<SpecExpr> specAnnotations) {
        /** Convenience constructor without effect row or specs. */
        MatchFn(String name, List<Expr.MatchArm> arms, Environment closure) {
            this(name, arms, closure, null, null);
        }
        /** Convenience constructor without specs. */
        MatchFn(String name, List<Expr.MatchArm> arms, Environment closure, List<String> effectRow) {
            this(name, arms, closure, effectRow, null);
        }
        @Override
        public String toString() { return "<fn " + name + ">"; }
    }

    /** An imperative-block function: binds params then executes statements. */
    record ImperativeFn(String name, List<Pattern> params, String restParam, List<Stmt> body,
                        Environment closure, List<String> effectRow, List<SpecExpr> specAnnotations) {
        /** Convenience constructor without rest param, effects, or specs. */
        ImperativeFn(String name, List<Pattern> params, List<Stmt> body, Environment closure) {
            this(name, params, null, body, closure, null, null);
        }
        /** Convenience constructor without effects or specs. */
        ImperativeFn(String name, List<Pattern> params, String restParam, List<Stmt> body, Environment closure) {
            this(name, params, restParam, body, closure, null, null);
        }
        /** Convenience constructor without specs. */
        ImperativeFn(String name, List<Pattern> params, String restParam, List<Stmt> body,
                     Environment closure, List<String> effectRow) {
            this(name, params, restParam, body, closure, effectRow, null);
        }

        /** Whether this function accepts extra args via ...rest. */
        boolean isVariadic() { return restParam != null; }

        @Override
        public String toString() { return "<fn " + name + ">"; }
    }

    /** A function wrapped with pre/post/in/out contracts. */
    record ContractedFn(Object fn, List<Object> pres, List<Object> posts,
                        List<Object> ins, List<Object> outs,
                        String name, String moduleName, SourceLoc loc) {
        /** Convenience constructor for pre/post only (no module contracts). */
        ContractedFn(Object fn, List<Object> pres, List<Object> posts,
                     String name, SourceLoc loc) {
            this(fn, pres, posts, List.of(), List.of(), name, null, loc);
        }
        @Override
        public String toString() {
            return fn.toString();
        }
    }

    /**
     * A function wrapped with spec-based validation from an arrow spec.
     * When called, validates each argument against the corresponding input spec,
     * calls the underlying function, then validates the result against the output spec.
     * Created when a function argument is annotated with a concrete arrow spec like (Int -> Str).
     */
    record SpecContractFn(Object fn, SpecExpr.Arrow arrowSpec, SourceLoc loc) {
        @Override
        public String toString() {
            return fn.toString();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Type Constructors
    // ═══════════════════════════════════════════════════════════════════

    private void registerSpecConstructors(Decl.SpecDecl sd, Environment env) {
        // Register spec descriptor for validation lookups
        registerSpecDescriptor(sd);

        switch (sd.body()) {
            case Decl.SpecBody.SumSpec ss -> {
                for (var variant : ss.variants()) {
                    if (variant.arity() == 0) {
                        // Zero-arg constructor: a value, not a function — certified by spec
                        defineInScope(env, variant.name(), new Tagged(variant.name(), List.of(), null, sd.name()));
                    } else {
                        defineInScope(env, variant.name(), new Constructor(variant.name(), variant.arity(), null, sd.name()));
                    }
                }
            }
            case Decl.SpecBody.ProductSpec ps -> {
                // Product spec: constructor takes all fields as positional args,
                // but also records field names for named access & destructuring
                var fieldNames = ps.fields().stream().map(Decl.SpecField::name).toList();
                defineInScope(env, sd.name(), new Constructor(sd.name(), fieldNames.size(), fieldNames, sd.name()));
            }
        }
    }

    private void registerSpecDescriptor(Decl.SpecDecl sd) {
        var descriptor = new Values.SpecDescriptor(sd.name(), sd.specParams(), sd.body());
        specRegistry.put(sd.name(), descriptor);
    }

    // ── Spec validation ─────────────────────────────────────────────

    /**
     * Validate a value against a named spec. Returns the value with certification.
     * If already certified for this spec, returns as-is (O(1) tag check).
     * Otherwise, force-validates the value's structure.
     */
    private Object validateAgainstSpec(Object value, String specName, Node.SourceLoc loc) {
        // O(1) tag check: already certified?
        if (value instanceof Tagged t && specName.equals(t.specName())) {
            return value;
        }

        var descriptor = specRegistry.get(specName);
        if (descriptor == null) {
            throw new IrijRuntimeError("Unknown spec: " + specName, loc);
        }

        return switch (descriptor.body()) {
            case Decl.SpecBody.ProductSpec ps -> validateProduct(value, specName, ps, loc);
            case Decl.SpecBody.SumSpec ss -> validateSum(value, specName, ss, loc);
        };
    }

    private Object validateProduct(Object value, String specName,
                                    Decl.SpecBody.ProductSpec ps, Node.SourceLoc loc) {
        var requiredFields = ps.fields().stream().map(Decl.SpecField::name).toList();

        // Accept Tagged with matching namedFields
        if (value instanceof Tagged t && t.namedFields() != null) {
            for (var field : requiredFields) {
                if (!t.namedFields().containsKey(field)) {
                    throw new IrijRuntimeError(
                        "Spec validation failed: " + specName + " requires field '" + field + "'", loc);
                }
            }
            // Re-certify with the spec name
            return new Tagged(t.tag(), t.fields(), t.namedFields(), specName);
        }

        // Accept IrijMap (untagged data) — validate and certify
        if (value instanceof Values.IrijMap m) {
            for (var field : requiredFields) {
                if (!m.entries().containsKey(field)) {
                    throw new IrijRuntimeError(
                        "Spec validation failed: " + specName + " requires field '" + field + "'", loc);
                }
            }
            // Certify: convert map to Tagged
            var named = new java.util.LinkedHashMap<>(m.entries());
            var fields = requiredFields.stream().map(named::get).toList();
            return new Tagged(specName, fields, named, specName);
        }

        throw new IrijRuntimeError(
            "Spec validation failed: cannot validate " + Values.typeName(value)
                + " as " + specName, loc);
    }

    private Object validateSum(Object value, String specName,
                                Decl.SpecBody.SumSpec ss, Node.SourceLoc loc) {
        if (!(value instanceof Tagged t)) {
            throw new IrijRuntimeError(
                "Spec validation failed: expected " + specName + " variant, got "
                    + Values.typeName(value), loc);
        }
        // Check that the tag matches one of the spec's variants
        var validTags = ss.variants().stream().map(Decl.Variant::name).toList();
        if (!validTags.contains(t.tag())) {
            throw new IrijRuntimeError(
                "Spec validation failed: '" + t.tag() + "' is not a variant of " + specName
                    + " (expected one of: " + validTags + ")", loc);
        }
        // Check arity
        var variant = ss.variants().stream().filter(v -> v.name().equals(t.tag())).findFirst().get();
        if (t.fields().size() != variant.arity()) {
            throw new IrijRuntimeError(
                "Spec validation failed: " + t.tag() + " expects " + variant.arity()
                    + " fields, got " + t.fields().size(), loc);
        }
        // Re-certify
        return new Tagged(t.tag(), t.fields(), t.namedFields(), specName);
    }

    /**
     * Validate a value against a spec by name (string). Used by validate/validate! builtins.
     * Handles both primitive specs and user-declared specs.
     */
    private Object validateByName(Object value, String name, Node.SourceLoc loc) {
        return validateAgainstSpecExpr(value, new SpecExpr.Name(name), loc);
    }

    // ── SpecExpr-based validation (Phase 8b) ──────────────────────────

    /** Primitive spec names → Values.typeName() mapping. */
    private static final Set<String> PRIMITIVE_SPEC_NAMES = Set.of(
        "Int", "Str", "Float", "Bool", "Keyword", "Unit", "Rational"
    );

    /**
     * Validate a value against a SpecExpr.
     * Dispatches on the SpecExpr variant:
     *   Name → primitive check or registry lookup
     *   Wildcard/Var → pass through (no validation)
     *   App → composite validation (Vec Int, Map Str Int, Fn 2)
     *   Arrow → wrap function in validating contract
     *   Enum → keyword membership check
     *   VecSpec/SetSpec/TupleSpec → element-wise validation
     */
    private Object validateAgainstSpecExpr(Object value, SpecExpr spec, Node.SourceLoc loc) {
        if (spec == null) return value;
        return switch (spec) {
            case SpecExpr.Wildcard w -> value;
            case SpecExpr.Var v -> value;  // Type variable — documentation only, no validation
            case SpecExpr.Unit u -> {
                if (value == Values.UNIT || value == null) yield value;
                throw new IrijRuntimeError(
                    "Spec validation failed: expected Unit, got " + Values.typeName(value), loc);
            }
            case SpecExpr.Name n -> validateNamedSpec(value, n.name(), loc);
            case SpecExpr.App a -> validateAppSpec(value, a, loc);
            case SpecExpr.Arrow a -> validateArrowSpec(value, a, loc);
            case SpecExpr.Enum e -> validateEnumSpec(value, e, loc);
            case SpecExpr.VecSpec v -> validateVecSpec(value, v, loc);
            case SpecExpr.SetSpec s -> validateSetSpec(value, s, loc);
            case SpecExpr.TupleSpec t -> validateTupleSpec(value, t, loc);
        };
    }

    /** Validate against a named spec — either a primitive or a user-declared spec. */
    private Object validateNamedSpec(Object value, String name, Node.SourceLoc loc) {
        // Check primitive specs first
        if (PRIMITIVE_SPEC_NAMES.contains(name)) {
            String actualType = Values.typeName(value);
            // Map some type names: Vector→Vec etc. would need mapping
            // But our primitives use the same names as typeName() returns
            if (actualType.equals(name)) return value;
            // Handle Int matching Long typeName
            throw new IrijRuntimeError(
                "Spec validation failed: expected " + name + ", got " + actualType, loc);
        }
        // "Fn" without args — just check it's callable
        if ("Fn".equals(name)) {
            if (isCallable(value)) return value;
            throw new IrijRuntimeError(
                "Spec validation failed: expected Fn, got " + Values.typeName(value), loc);
        }
        // Collection type names without params — just check the type
        if ("Vec".equals(name) || "Vector".equals(name)) {
            if (value instanceof IrijVector) return value;
            throw new IrijRuntimeError(
                "Spec validation failed: expected Vec, got " + Values.typeName(value), loc);
        }
        if ("Map".equals(name)) {
            if (value instanceof IrijMap) return value;
            throw new IrijRuntimeError(
                "Spec validation failed: expected Map, got " + Values.typeName(value), loc);
        }
        if ("Set".equals(name)) {
            if (value instanceof IrijSet) return value;
            throw new IrijRuntimeError(
                "Spec validation failed: expected Set, got " + Values.typeName(value), loc);
        }
        if ("Tuple".equals(name)) {
            if (value instanceof IrijTuple) return value;
            throw new IrijRuntimeError(
                "Spec validation failed: expected Tuple, got " + Values.typeName(value), loc);
        }
        // Fall back to user-declared spec in registry
        if (specRegistry.containsKey(name)) {
            return validateAgainstSpec(value, name, loc);
        }
        // Unknown spec name — error
        throw new IrijRuntimeError("Unknown spec: " + name, loc);
    }

    /** Validate against App spec: Vec Int, Map Str Int, Fn 2, etc. */
    private Object validateAppSpec(Object value, SpecExpr.App app, Node.SourceLoc loc) {
        return switch (app.head()) {
            case "Vec" -> {
                if (!(value instanceof IrijVector vec)) {
                    throw new IrijRuntimeError(
                        "Spec validation failed: expected Vec, got " + Values.typeName(value), loc);
                }
                if (!app.args().isEmpty()) {
                    var elemSpec = app.args().get(0);
                    for (var elem : vec.elements()) {
                        validateAgainstSpecExpr(elem, elemSpec, loc);
                    }
                }
                yield value;
            }
            case "Set" -> {
                if (!(value instanceof IrijSet set)) {
                    throw new IrijRuntimeError(
                        "Spec validation failed: expected Set, got " + Values.typeName(value), loc);
                }
                if (!app.args().isEmpty()) {
                    var elemSpec = app.args().get(0);
                    for (var elem : set.elements()) {
                        validateAgainstSpecExpr(elem, elemSpec, loc);
                    }
                }
                yield value;
            }
            case "Map" -> {
                if (!(value instanceof IrijMap map)) {
                    throw new IrijRuntimeError(
                        "Spec validation failed: expected Map, got " + Values.typeName(value), loc);
                }
                if (app.args().size() >= 2) {
                    var keySpec = app.args().get(0);
                    var valSpec = app.args().get(1);
                    for (var entry : map.entries().entrySet()) {
                        validateAgainstSpecExpr(entry.getKey(), keySpec, loc);
                        validateAgainstSpecExpr(entry.getValue(), valSpec, loc);
                    }
                }
                yield value;
            }
            case "Tuple" -> {
                if (!(value instanceof IrijTuple tup)) {
                    throw new IrijRuntimeError(
                        "Spec validation failed: expected Tuple, got " + Values.typeName(value), loc);
                }
                for (int i = 0; i < app.args().size() && i < tup.elements().length; i++) {
                    validateAgainstSpecExpr(tup.elements()[i], app.args().get(i), loc);
                }
                yield value;
            }
            case "Fn" -> {
                if (!isCallable(value)) {
                    throw new IrijRuntimeError(
                        "Spec validation failed: expected Fn, got " + Values.typeName(value), loc);
                }
                // Fn with arity check: (Fn 2) means arity=2
                if (!app.args().isEmpty() && app.args().get(0) instanceof SpecExpr.Name n) {
                    try {
                        int expectedArity = Integer.parseInt(n.name());
                        int actualArity = getCallableArity(value);
                        if (actualArity >= 0 && actualArity != expectedArity) {
                            throw new IrijRuntimeError(
                                "Spec validation failed: expected Fn with arity " + expectedArity
                                    + ", got arity " + actualArity, loc);
                        }
                    } catch (NumberFormatException ignored) {
                        // Not a number — treat as documentation
                    }
                }
                yield value;
            }
            default -> {
                // User-declared parametric spec: Result Ok Err, Maybe a, etc.
                // For user-declared specs, just validate the head spec name
                if (specRegistry.containsKey(app.head())) {
                    yield validateAgainstSpec(value, app.head(), loc);
                }
                // Unknown — no-op
                yield value;
            }
        };
    }

    /** Get arity of a callable, or -1 if unknown. */
    private int getCallableArity(Object value) {
        return switch (value) {
            case Lambda l -> l.arity();
            case ImperativeFn imf -> imf.params().size();
            case BuiltinFn bf -> bf.arity();
            case Constructor c -> c.arity();
            case ContractedFn cf -> getCallableArity(cf.fn());
            case SpecContractFn scf -> getCallableArity(scf.fn());
            default -> -1;
        };
    }

    /** Validate a value is a function matching an arrow spec, wrap in contract. */
    private Object validateArrowSpec(Object value, SpecExpr.Arrow arrow, Node.SourceLoc loc) {
        if (!isCallable(value)) {
            throw new IrijRuntimeError(
                "Spec validation failed: expected function " + arrow + ", got " + Values.typeName(value), loc);
        }
        // Only wrap with contract if the arrow is fully concrete (no type variables)
        if (!arrow.isConcrete()) {
            return value;  // Treat as documentation
        }
        // Build pre/post contract lambdas that validate the arrow's input/output specs
        // We create a SpecContractFn wrapper that validates args and return value
        return new SpecContractFn(value, arrow, loc);
    }

    /** Validate a keyword value against an Enum spec. */
    private Object validateEnumSpec(Object value, SpecExpr.Enum enumSpec, Node.SourceLoc loc) {
        if (!(value instanceof Keyword kw)) {
            throw new IrijRuntimeError(
                "Spec validation failed: expected Keyword (one of " + enumSpec.values() + "), got "
                    + Values.typeName(value), loc);
        }
        if (!enumSpec.values().contains(kw.name())) {
            throw new IrijRuntimeError(
                "Spec validation failed: :" + kw.name() + " is not in " + enumSpec, loc);
        }
        return value;
    }

    /** Validate a vector with element spec: #[Int]. */
    private Object validateVecSpec(Object value, SpecExpr.VecSpec vecSpec, Node.SourceLoc loc) {
        if (!(value instanceof IrijVector vec)) {
            throw new IrijRuntimeError(
                "Spec validation failed: expected Vec, got " + Values.typeName(value), loc);
        }
        for (var elem : vec.elements()) {
            validateAgainstSpecExpr(elem, vecSpec.elemSpec(), loc);
        }
        return value;
    }

    /** Validate a set with element spec: #{Str}. */
    private Object validateSetSpec(Object value, SpecExpr.SetSpec setSpec, Node.SourceLoc loc) {
        if (!(value instanceof IrijSet set)) {
            throw new IrijRuntimeError(
                "Spec validation failed: expected Set, got " + Values.typeName(value), loc);
        }
        for (var elem : set.elements()) {
            validateAgainstSpecExpr(elem, setSpec.elemSpec(), loc);
        }
        return value;
    }

    /** Validate a tuple with positional specs: #(Int Str). */
    private Object validateTupleSpec(Object value, SpecExpr.TupleSpec tupleSpec, Node.SourceLoc loc) {
        if (!(value instanceof IrijTuple tup)) {
            throw new IrijRuntimeError(
                "Spec validation failed: expected Tuple, got " + Values.typeName(value), loc);
        }
        if (tup.elements().length != tupleSpec.elemSpecs().size()) {
            throw new IrijRuntimeError(
                "Spec validation failed: expected Tuple with " + tupleSpec.elemSpecs().size()
                    + " elements, got " + tup.elements().length, loc);
        }
        for (int i = 0; i < tupleSpec.elemSpecs().size(); i++) {
            validateAgainstSpecExpr(tup.elements()[i], tupleSpec.elemSpecs().get(i), loc);
        }
        return value;
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
            // For spec declarations, also export constructor names
            if (pd.inner() instanceof Decl.SpecDecl sd) {
                if (sd.body() instanceof Decl.SpecBody.SumSpec sum) {
                    for (var variant : sum.variants()) {
                        pubNames.add(variant.name());
                    }
                }
            }
            // For effect declarations, also export op names
            if (pd.inner() instanceof Decl.EffectDecl ed) {
                for (var op : ed.ops()) {
                    pubNames.add(op.name());
                }
            }
            // Phase 8c: warn if pub fn lacks spec annotations
            if (specLintEnabled && pd.inner() instanceof Decl.FnDecl fn
                    && !(fn.body() instanceof Decl.FnBody.NoBody)) {
                var specs = fn.specAnnotations();
                if (specs == null || specs.isEmpty()) {
                    out.println("⚠ warning: pub fn '" + fn.name() + "' in module '"
                        + currentModuleName + "' has no spec annotations"
                        + (fn.loc() != null ? " (" + fn.loc() + ")" : ""));
                }
            }
        }
        return result;
    }

    /** Extract the primary name from a declaration node. */
    private String extractDeclName(Node node) {
        if (node instanceof Decl.FnDecl fn) return fn.name();
        if (node instanceof Decl.SpecDecl sd) return sd.name();
        if (node instanceof Decl.BindingDecl bd) {
            if (bd.stmt() instanceof Stmt.Bind b &&
                b.target() instanceof Stmt.BindTarget.Simple s) return s.name();
        }
        if (node instanceof Decl.EffectDecl ed) return ed.name();
        if (node instanceof Decl.HandlerDecl hd) return hd.name();
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
        Builtins.install(moduleEnv, out, pathResolver);
        // Install interpreter builtins (fold, spawn) in module env too
        // Forward interpreter-level builtins to module env
        for (var name : List.of("fold", "spawn", "try", "raw-http-serve",
                "raw-db-open", "raw-db-query", "raw-db-exec", "raw-db-close", "raw-db-transaction",
                "raw-nrepl-eval-sandboxed",
                "raw-sse-response", "raw-sse-send", "raw-sse-close", "raw-sse-closed?",
                "raw-session-create", "raw-session-eval", "raw-session-destroy", "raw-session-cleanup",
                "raw-session-subscribe", "raw-session-unsubscribe")) {
            if (globalEnv.isDefined(name))
                moduleEnv.define(name, globalEnv.lookup(name));
        }

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
    // Structured Concurrency (scope / fork / await)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Execute a {@code scope} block with structured concurrency guarantees.
     * All fibers forked within the scope must complete before the scope exits.
     *
     * <p>Modifiers:
     * <ul>
     *   <li>{@code scope s} — join-all: waits for all fibers, propagates first error</li>
     *   <li>{@code scope.race s} — first success wins, cancel others</li>
     *   <li>{@code scope.supervised s} — failures isolated per fiber, no sibling cancellation</li>
     * </ul>
     */
    private Object execScope(Stmt.Scope s, Environment env) {
        var fibers = java.util.Collections.synchronizedList(
            new java.util.ArrayList<Fiber>());
        var parentStack = EffectSystem.STACK.get();
        var parentEffects = AVAILABLE_EFFECTS.get().peek();

        // Create the fork function that captures this scope's fiber list
        var forkFn = new BuiltinFn("fork", 1, args -> {
            var thunk = args.get(0);
            var future = new java.util.concurrent.CompletableFuture<Object>();
            var thread = Thread.startVirtualThread(() -> {
                // Inherit parent's effect handler stack and available effects
                var fiberStack = EffectSystem.STACK.get();
                fiberStack.addAll(parentStack);
                AVAILABLE_EFFECTS.get().push(parentEffects);
                try {
                    future.complete(apply(thunk, List.of(), s.loc()));
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
            var fiber = new Fiber(future, thread);
            fibers.add(fiber);
            return fiber;
        });

        var handle = new ScopeHandle(s.modifier(), fibers, forkFn);

        // Set up scope environment with handle binding
        var scopeEnv = env.child();
        if (s.name() != null) {
            scopeEnv.define(s.name(), handle);
        }

        Object bodyResult;
        try {
            bodyResult = execStmtListReturn(s.body(), scopeEnv);
        } catch (Exception e) {
            // Body failed — cancel all fibers and propagate
            for (var fiber : fibers) fiber.thread().interrupt();
            joinAllQuietly(fibers);
            throw e;
        }

        // After body completes, join fibers based on modifier
        return switch (s.modifier()) {
            case null -> joinAll(fibers, bodyResult, s.loc());
            case "race" -> raceAll(fibers, bodyResult, s.loc());
            case "supervised" -> supervisedJoinAll(fibers, bodyResult, s.loc());
            default -> throw new IrijRuntimeError(
                "Unknown scope modifier: " + s.modifier(), s.loc());
        };
    }

    /** scope s: wait for all fibers. Propagate first error. Return body result. */
    private Object joinAll(java.util.List<Fiber> fibers, Object bodyResult, SourceLoc loc) {
        IrijRuntimeError firstError = null;
        for (var fiber : fibers) {
            try {
                fiber.result().join();
            } catch (java.util.concurrent.CompletionException ce) {
                if (firstError == null) {
                    // Cancel remaining fibers on first error
                    for (var f : fibers) f.thread().interrupt();
                    if (ce.getCause() instanceof IrijRuntimeError ire) {
                        firstError = ire;
                    } else {
                        firstError = new IrijRuntimeError(
                            "Fiber failed: " + ce.getCause().getMessage(), loc);
                    }
                }
            }
        }
        if (firstError != null) throw firstError;
        return bodyResult;
    }

    /** scope.race s: first fiber success wins. Cancel losers. */
    private Object raceAll(java.util.List<Fiber> fibers, Object bodyResult, SourceLoc loc) {
        if (fibers.isEmpty()) return bodyResult;

        var winner = new java.util.concurrent.CompletableFuture<Object>();
        var errorCount = new java.util.concurrent.atomic.AtomicInteger(0);
        int total = fibers.size();

        for (var fiber : fibers) {
            fiber.result().whenComplete((value, ex) -> {
                if (ex != null) {
                    if (errorCount.incrementAndGet() == total) {
                        winner.completeExceptionally(ex);
                    }
                } else {
                    winner.complete(value);
                }
            });
        }

        try {
            var result = winner.join();
            for (var fiber : fibers) fiber.thread().interrupt();
            return result;
        } catch (java.util.concurrent.CompletionException ce) {
            for (var fiber : fibers) fiber.thread().interrupt();
            if (ce.getCause() instanceof IrijRuntimeError ire) throw ire;
            throw new IrijRuntimeError("scope.race: all fibers failed", loc);
        }
    }

    /** scope.supervised s: fibers run independently. Failures don't cancel siblings. */
    private Object supervisedJoinAll(java.util.List<Fiber> fibers, Object bodyResult, SourceLoc loc) {
        for (var fiber : fibers) {
            try {
                fiber.result().join();
            } catch (java.util.concurrent.CompletionException ce) {
                // Log error but don't propagate or cancel siblings
                out.println("[supervised] fiber error: " + ce.getCause().getMessage());
            }
        }
        return bodyResult;
    }

    /** Interrupt and wait for all fibers to terminate (used in cleanup). */
    private void joinAllQuietly(java.util.List<Fiber> fibers) {
        for (var fiber : fibers) {
            try { fiber.result().join(); }
            catch (Exception ignored) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Protocol system
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Evaluate a protocol declaration: register the protocol descriptor and
     * install dispatch functions for each method.
     *
     * <p>Each method becomes a global function that dispatches on the runtime
     * type of the first argument (Clojure-style). E.g., {@code proto Show a}
     * with method {@code show :: a -> Str} installs a function {@code show}
     * that checks {@code Values.typeName(arg)} and delegates to the matching
     * impl binding.</p>
     */
    private Object evalProtoDecl(Decl.ProtoDecl pd, Environment env) {
        var methodNames = pd.methods().stream().map(Decl.ProtoMethod::name).toList();
        var descriptor = new ProtocolDescriptor(pd.name(), methodNames, pd.laws());
        protocols.put(pd.name(), descriptor);
        defineInScope(env, pd.name(), descriptor);

        // Install dispatch functions for each method
        for (var method : pd.methods()) {
            String methodName = method.name();
            String protoName = pd.name();
            var protoLoc = pd.loc();
            // Variadic dispatch function: checks first arg type, finds impl, calls binding
            defineInScope(env, methodName, new BuiltinFn(methodName, -1, args -> {
                if (args.isEmpty()) {
                    throw new IrijRuntimeError(
                        "Protocol method '" + methodName + "' requires at least one argument", protoLoc);
                }
                var firstArg = args.get(0);
                String typeName = Values.typeName(firstArg);
                Object implFn = descriptor.dispatch(methodName, typeName);
                if (implFn == null) {
                    throw new IrijRuntimeError(
                        "No implementation of protocol '" + protoName
                            + "' for type '" + typeName + "'", protoLoc);
                }
                // If the impl binding is a callable, apply it to all arguments.
                // If it's a plain value (e.g., empty := 0), return it directly.
                if (isCallable(implFn)) {
                    return apply(implFn, args, protoLoc);
                } else {
                    return implFn;
                }
            }));
        }

        return descriptor;
    }

    /**
     * Evaluate an impl declaration: register method bindings in the protocol's
     * dispatch table for the given type.
     * Validates that all binding names match declared protocol methods.
     */
    private Object evalImplDecl(Decl.ImplDecl id, Environment env) {
        var descriptor = protocols.get(id.protoName());
        if (descriptor == null) {
            throw new IrijRuntimeError(
                "Cannot implement unknown protocol '" + id.protoName() + "'", id.loc());
        }

        // Validate that impl methods match protocol declaration
        var protoMethods = descriptor.methodNames();
        for (var binding : id.bindings()) {
            if (!protoMethods.contains(binding.name())) {
                throw new IrijRuntimeError(
                    "Method '" + binding.name() + "' is not declared in protocol '"
                        + id.protoName() + "' (available: " + protoMethods + ")", id.loc());
            }
        }

        // Evaluate each binding and register in the dispatch table
        var bindingMap = new LinkedHashMap<String, Object>();
        for (var binding : id.bindings()) {
            var value = eval(binding.value(), env);
            bindingMap.put(binding.name(), value);
        }

        descriptor.registerImpl(id.forType(), bindingMap);

        // Auto-verify laws if --verify-laws flag is active
        if (autoVerifyLaws && !descriptor.laws().isEmpty()) {
            verifyImplLaws(descriptor, id.forType(), bindingMap, 100);
        }

        return Values.UNIT;
    }

    /** Verify laws for a single type implementation (used by --verify-laws). */
    private void verifyImplLaws(ProtocolDescriptor pd, String typeName,
                                Map<String, Object> typeImpls, int trials) {
        for (var law : pd.laws()) {
            boolean passed = true;
            String failInfo = null;
            for (int i = 0; i < trials; i++) {
                var childEnv = globalEnv.child();
                for (var methodName : pd.methodNames()) {
                    Object implBinding = typeImpls.get(methodName);
                    if (implBinding != null) childEnv.define(methodName, implBinding);
                }
                for (var varName : law.forallVars()) {
                    childEnv.define(varName, generateRandomForType(typeName));
                }
                try {
                    var result = eval(law.body(), childEnv);
                    if (!Values.isTruthy(result)) {
                        passed = false;
                        var bindings = new StringBuilder();
                        for (var varName : law.forallVars()) {
                            if (!bindings.isEmpty()) bindings.append(", ");
                            bindings.append(varName).append("=")
                                .append(Values.toIrijString(childEnv.lookup(varName)));
                        }
                        failInfo = "with " + bindings;
                        break;
                    }
                } catch (IrijRuntimeError e) {
                    passed = false;
                    failInfo = e.getMessage();
                    break;
                }
            }
            var desc = pd.name() + "/" + typeName + ": " + law.name();
            if (passed) {
                out.println("  PASS  " + desc + " (" + trials + " trials)");
            } else {
                out.println("  FAIL  " + desc + " — " + failInfo);
                throw new IrijRuntimeError(
                    "Law '" + law.name() + "' failed for " + pd.name() + "/" + typeName
                        + " — " + failInfo, null);
            }
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
        // Tagged with their effect name for effect row checking
        for (var op : ed.ops()) {
            String effectName = ed.name();
            String opName = op.name();
            // Arity -1 = variadic (type signatures not checked yet)
            defineInScope(env, opName, new BuiltinFn(opName, -1, List.of(effectName), args ->
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

        // Unannotated handler = pure (empty effect row, same as functions)
        var requiredEffects = hd.requiredEffects() != null ? hd.requiredEffects() : List.<String>of();
        var handler = new HandlerValue(hd.name(), hd.effectName(), requiredEffects, clauseMap, closureEnv);
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
            case Stmt.Scope s -> execScope(s, env);
        }
    }

    private void execBind(Stmt.Bind bind, Environment env) {
        var value = eval(bind.value(), env);
        // Spec annotation: validate and certify if needed
        if (bind.specAnnotation() != null) {
            value = validateAgainstSpecExpr(value, bind.specAnnotation(), bind.loc());
        }
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

        // Snapshot the current thread's handler stack and available effects for the body thread
        var parentStack = EffectSystem.STACK.get();
        var parentEffects = AVAILABLE_EFFECTS.get().peek();

        Thread bodyThread = Thread.startVirtualThread(() -> {
            // Copy parent handler stack and push our context
            var bodyStack = EffectSystem.STACK.get();
            bodyStack.addAll(parentStack);
            bodyStack.push(ctx);
            // Propagate available effects and ADD the handler's effect
            if (parentEffects == AMBIENT_EFFECTS) {
                AVAILABLE_EFFECTS.get().push(AMBIENT_EFFECTS);
            } else {
                var expanded = new HashSet<>(parentEffects);
                expanded.add(handler.effectName());
                AVAILABLE_EFFECTS.get().push(expanded);
            }
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
        var parentEffects = AVAILABLE_EFFECTS.get().peek();

        Thread bodyThread = Thread.startVirtualThread(() -> {
            var bodyStack = EffectSystem.STACK.get();
            bodyStack.addAll(parentStack);
            bodyStack.push(ctx);
            // Propagate available effects and ADD the handler's effect
            if (parentEffects == AMBIENT_EFFECTS) {
                AVAILABLE_EFFECTS.get().push(AMBIENT_EFFECTS);
            } else {
                var expanded = new HashSet<>(parentEffects);
                expanded.add(handler.effectName());
                AVAILABLE_EFFECTS.get().push(expanded);
            }
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

                    // Evaluate handler arm body with the handler's declared effects.
                    // Handler clauses must declare what effects they need, e.g.:
                    //   handler console-log :: Logger ::: Console
                    AVAILABLE_EFFECTS.get().push(new HashSet<>(handler.requiredEffects()));
                    Object armResult;
                    try {
                        armResult = eval(clause.body(), armEnv);
                    } finally {
                        AVAILABLE_EFFECTS.get().pop();
                    }

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
                case Stmt.Scope sc -> last = execScope(sc, env);
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
        return switch (expr) {
            // ── Literals ────────────────────────────────────────────────
            case Expr.IntLit(var v, var loc__) -> v;
            case Expr.FloatLit(var v, var loc__) -> v;
            case Expr.RationalLit(var n, var d, var loc__) -> new Rational(n, d);
            case Expr.HexLit(var v, var loc__) -> v;
            case Expr.StrLit(var v, var loc__) -> v;
            case Expr.BoolLit(var v, var loc__) -> v;
            case Expr.KeywordLit(var name, var loc__) -> new Keyword(name);
            case Expr.UnitLit(var loc__) -> Values.UNIT;
            case Expr.Wildcard(var loc__) -> Values.UNIT;

            // ── References ──────────────────────────────────────────────
            case Expr.Var(var name, var loc) -> env.lookup(name, loc);
            case Expr.TypeRef(var name, var loc) -> env.lookup(name, loc);
            case Expr.RoleRef(var name, var loc__) -> name; // just pass through as string

            // ── Operators ───────────────────────────────────────────────
            case Expr.BinaryOp bo -> evalBinaryOp(bo, env);
            case Expr.UnaryOp uo -> evalUnaryOp(uo, env);

            // ── Application ─────────────────────────────────────────────
            case Expr.App app -> evalApp(app, env);
            case Expr.Lambda lam ->
                new Lambda(lam.params(), lam.restParam(), lam.body(), env, null);

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
                // Scope handle dot-access: fork method
                if (target instanceof ScopeHandle sh) {
                    if ("fork".equals(da.field())) {
                        yield sh.forkFn();
                    }
                    throw new IrijRuntimeError("No field '" + da.field() + "' on scope handle", da.loc());
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
        String msg = "Cannot apply arithmetic to " + Values.typeName(left) + " and " + Values.typeName(right);
        if (left instanceof MatchFn || left instanceof Values.BuiltinFn
            || left instanceof ContractedFn || left instanceof SpecContractFn
            || left instanceof Values.ComposedFn) {
            msg += ". Hint: to pass a negative number, use parentheses: f (-" + Values.toIrijString(right) + ")";
        }
        throw new IrijRuntimeError(msg, loc);
    }

    /** Guess MIME type from path extension (for bundled mode where probeContentType isn't available). */
    /** Escape a string for JSON embedding. */
    private static String jsonEscape(String s) {
        var sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }

    private static String guessMimeType(String path) {
        if (path.endsWith(".html") || path.endsWith(".htm")) return "text/html; charset=utf-8";
        if (path.endsWith(".css"))  return "text/css; charset=utf-8";
        if (path.endsWith(".js"))   return "application/javascript; charset=utf-8";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        if (path.endsWith(".png"))  return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".gif"))  return "image/gif";
        if (path.endsWith(".svg"))  return "image/svg+xml";
        if (path.endsWith(".ico"))  return "image/x-icon";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".woff")) return "font/woff";
        if (path.endsWith(".ttf"))  return "font/ttf";
        if (path.endsWith(".txt"))  return "text/plain; charset=utf-8";
        if (path.endsWith(".xml"))  return "application/xml";
        return "application/octet-stream";
    }

    private static LinkedHashMap<String, Object> parseQueryParams(String query) {
        var result = new LinkedHashMap<String, Object>();
        if (query == null || query.isEmpty()) return result;
        for (var pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            var eq = pair.indexOf('=');
            String k, v;
            if (eq < 0) { k = pair; v = ""; }
            else { k = pair.substring(0, eq); v = pair.substring(eq + 1); }
            try {
                k = java.net.URLDecoder.decode(k, java.nio.charset.StandardCharsets.UTF_8);
                v = java.net.URLDecoder.decode(v, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
            result.put(k, v);
        }
        return result;
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

    /** Check if a value is callable (can be passed to apply). */
    private boolean isCallable(Object value) {
        return value instanceof Lambda
            || value instanceof MatchFn
            || value instanceof ImperativeFn
            || value instanceof BuiltinFn
            || value instanceof Constructor
            || value instanceof PartialApp
            || value instanceof ComposedFn
            || value instanceof ContractedFn
            || value instanceof SpecContractFn
            || value instanceof Tagged;
    }

    /**
     * Validate function arguments against spec annotations.
     * specAnnotations: [Input1, Input2, ..., Output]. Last = output, rest = inputs.
     * Wildcard and Var entries skip validation.
     */
    private List<Object> validateFnArgs(List<Object> args, List<SpecExpr> specAnnotations,
                                         String fnName, SourceLoc loc) {
        if (specAnnotations == null || specAnnotations.size() < 2) return args;
        // Input specs: all but last
        int inputCount = specAnnotations.size() - 1;
        var result = new ArrayList<>(args);
        for (int i = 0; i < inputCount && i < result.size(); i++) {
            var spec = specAnnotations.get(i);
            result.set(i, validateAgainstSpecExpr(result.get(i), spec, loc));
        }
        return result;
    }

    private Object validateFnReturn(Object result, List<SpecExpr> specAnnotations,
                                     String fnName, SourceLoc loc) {
        if (specAnnotations == null || specAnnotations.isEmpty()) return result;
        var outputSpec = specAnnotations.get(specAnnotations.size() - 1);
        return validateAgainstSpecExpr(result, outputSpec, loc);
    }

    Object apply(Object fn, List<Object> args, SourceLoc loc) {
        return switch (fn) {
            case Lambda lam -> {
                // Check arity for partial application (variadic never partial-applies)
                if (args.size() < lam.arity() && !lam.isVariadic()) {
                    yield new PartialApp(lam, List.copyOf(args));
                }
                // Validate input args against spec annotations
                var validatedArgs = validateFnArgs(args, lam.specAnnotations(), lam.name(), loc);
                var callEnv = lam.closure().child();
                // Bind params via pattern matching
                for (int i = 0; i < lam.params().size() && i < validatedArgs.size(); i++) {
                    if (!matchPattern(lam.params().get(i), validatedArgs.get(i), callEnv)) {
                        throw new IrijRuntimeError("Pattern match failed in function call", loc);
                    }
                }
                // Bind rest param if present
                if (lam.restParam() != null) {
                    var restArgs = validatedArgs.size() > lam.params().size()
                        ? validatedArgs.subList(lam.params().size(), validatedArgs.size())
                        : List.<Object>of();
                    callEnv.define(lam.restParam(), new IrijVector(restArgs));
                }
                // Push effect row context (null = unchecked/unannotated)
                var effRow = lam.effectRow();
                if (effRow != null) {
                    AVAILABLE_EFFECTS.get().push(new HashSet<>(effRow));
                }
                try {
                    var result = eval(lam.body(), callEnv);
                    yield validateFnReturn(result, lam.specAnnotations(), lam.name(), loc);
                } finally {
                    if (effRow != null) {
                        AVAILABLE_EFFECTS.get().pop();
                    }
                }
            }
            case MatchFn mf -> {
                // Match-arm function: first arg is the scrutinee
                if (args.isEmpty()) {
                    throw new IrijRuntimeError("Match function requires at least one argument", loc);
                }
                // Validate input args against spec annotations
                var validatedArgs = validateFnArgs(args, mf.specAnnotations(), mf.name(), loc);
                // Push effect row context
                var effRow = mf.effectRow();
                if (effRow != null) {
                    AVAILABLE_EFFECTS.get().push(new HashSet<>(effRow));
                }
                try {
                    var scrutinee = validatedArgs.get(0);
                    for (var arm : mf.arms()) {
                        var matchEnv = mf.closure().child();
                        if (matchPattern(arm.pattern(), scrutinee, matchEnv)) {
                            if (arm.guard() != null) {
                                var guardVal = eval(arm.guard(), matchEnv);
                                if (!Values.isTruthy(guardVal)) continue;
                            }
                            var result = eval(arm.body(), matchEnv);
                            yield validateFnReturn(result, mf.specAnnotations(), mf.name(), loc);
                        }
                    }
                    throw new IrijRuntimeError("Non-exhaustive match in function " + mf.name(), loc);
                } finally {
                    if (effRow != null) {
                        AVAILABLE_EFFECTS.get().pop();
                    }
                }
            }
            case ImperativeFn imf -> {
                // Validate input args against spec annotations
                var validatedArgs = validateFnArgs(args, imf.specAnnotations(), imf.name(), loc);
                var callEnv = imf.closure().child();
                // Bind params
                for (int i = 0; i < imf.params().size() && i < validatedArgs.size(); i++) {
                    if (!matchPattern(imf.params().get(i), validatedArgs.get(i), callEnv)) {
                        throw new IrijRuntimeError("Pattern match failed in function call", loc);
                    }
                }
                // Bind rest param if present
                if (imf.restParam() != null) {
                    var restArgs = validatedArgs.size() > imf.params().size()
                        ? validatedArgs.subList(imf.params().size(), validatedArgs.size())
                        : List.<Object>of();
                    callEnv.define(imf.restParam(), new IrijVector(restArgs));
                }
                // Push effect row context
                var effRow = imf.effectRow();
                if (effRow != null) {
                    AVAILABLE_EFFECTS.get().push(new HashSet<>(effRow));
                }
                try {
                    var result = execStmtListReturn(imf.body(), callEnv);
                    yield validateFnReturn(result, imf.specAnnotations(), imf.name(), loc);
                } finally {
                    if (effRow != null) {
                        AVAILABLE_EFFECTS.get().pop();
                    }
                }
            }
            case BuiltinFn bf -> {
                if (args.size() < bf.arity()) {
                    yield new PartialApp(bf, List.copyOf(args));
                }
                // Check effect requirements before executing
                if (!bf.requiredEffects().isEmpty()) {
                    checkEffectsAvailable(bf.requiredEffects(), bf.name(), loc);
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
            case ContractedFn cf -> {
                // Partial application: defer contracts until all args are provided
                if (cf.fn() instanceof Lambda lam && args.size() < lam.arity() && !lam.isVariadic()) {
                    yield new PartialApp(cf, List.copyOf(args));
                }
                // Check pre-conditions (caller's responsibility)
                for (var pre : cf.pres()) {
                    var check = apply(pre, args, cf.loc());
                    if (!Values.isTruthy(check)) {
                        throw new IrijRuntimeError(
                            "Pre-condition violated in '" + cf.name()
                                + "' (caller's fault)", cf.loc());
                    }
                }
                // Check in-contracts (caller's responsibility, module-boundary blame)
                for (var inC : cf.ins()) {
                    var check = apply(inC, args, cf.loc());
                    if (!Values.isTruthy(check)) {
                        String blame = cf.moduleName() != null
                            ? "caller violated " + cf.name() + "'s input contract (module " + cf.moduleName() + ")"
                            : "Input contract violated in '" + cf.name() + "' (caller's fault)";
                        throw new IrijRuntimeError(blame, cf.loc());
                    }
                }
                // Execute the actual function
                var result = apply(cf.fn(), args, loc);
                // Check post-conditions (implementation's responsibility)
                for (var post : cf.posts()) {
                    var check = apply(post, List.of(result), cf.loc());
                    if (!Values.isTruthy(check)) {
                        throw new IrijRuntimeError(
                            "Post-condition violated in '" + cf.name()
                                + "' (implementation's fault)", cf.loc());
                    }
                }
                // Check out-contracts (implementation's responsibility, module-boundary blame)
                for (var outC : cf.outs()) {
                    var check = apply(outC, List.of(result), cf.loc());
                    if (!Values.isTruthy(check)) {
                        String blame = cf.moduleName() != null
                            ? cf.name() + " violated its output contract (module " + cf.moduleName() + ")"
                            : "Output contract violated in '" + cf.name() + "' (implementation's fault)";
                        throw new IrijRuntimeError(blame, cf.loc());
                    }
                }
                yield result;
            }
            case SpecContractFn scf -> {
                // Validate each argument against the arrow's input specs
                var validatedArgs = new ArrayList<>(args);
                for (int i = 0; i < scf.arrowSpec().inputs().size() && i < validatedArgs.size(); i++) {
                    validatedArgs.set(i, validateAgainstSpecExpr(
                        validatedArgs.get(i), scf.arrowSpec().inputs().get(i), scf.loc()));
                }
                // Call the underlying function
                var result = apply(scf.fn(), validatedArgs, loc);
                // Validate the return value
                yield validateAgainstSpecExpr(result, scf.arrowSpec().output(), scf.loc());
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
            case Pattern.VarPat(var name, var loc__) -> {
                env.define(name, value);
                yield true;
            }
            case Pattern.WildcardPat wp -> true;
            case Pattern.UnitPat up -> value == Values.UNIT;
            case Pattern.LitPat(var litExpr, var loc__) -> {
                // Evaluate the literal and compare
                var litVal = eval(litExpr, env);
                yield equalityOp(litVal, value).equals(Boolean.TRUE);
            }
            case Pattern.ConstructorPat(var name, var args, var loc__) -> {
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
            case Pattern.KeywordPat(var name, var arg, var loc__) -> {
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
            case Pattern.GroupedPat(var inner, var loc__) -> matchPattern(inner, value, env);
            case Pattern.VectorPat(var elements, var spread, var loc__) -> {
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
            case Pattern.TuplePat(var elements, var loc__) -> {
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
            case Pattern.DestructurePat(var fields, var loc__) -> {
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
            case Pattern.SpreadPat(var name, var loc__) -> {
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
