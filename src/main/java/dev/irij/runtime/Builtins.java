package dev.irij.runtime;

import dev.irij.IrijRuntimeError;

import dev.irij.ast.Node.SourceLoc;
import dev.irij.runtime.Values.*;

import com.google.gson.*;
import com.moandjiezana.toml.Toml;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * All built-in functions and global bindings.
 */
public final class Builtins {

    private Builtins() {}

    /** Single stdin reader shared across all read-line calls — re-wrapping
     *  System.in per call would fragment the underlying buffer and drop input. */
    private static final java.io.BufferedReader STDIN_READER =
        new java.io.BufferedReader(new java.io.InputStreamReader(System.in));

    /** Forbidden builtins in sandbox mode. After phase 3d the raw FS
     *  + multipart entries are gone too (FileIO + Serve effects route
     *  through cap providers that aren't on the sandbox classpath; the
     *  sandbox handlers reject those effect ops directly). */
    private static final List<String> SANDBOX_FORBIDDEN = List.of();

    /**
     * Install sandboxed builtins — all standard builtins, but I/O, file, DB,
     * and HTTP operations are replaced with error stubs.
     */
    public static void installSandboxed(Environment env, PrintStream out) {
        install(env, out, null);
        for (var name : SANDBOX_FORBIDDEN) {
            String msg = name + ": not available in sandbox mode";
            int arity = env.isDefined(name)
                ? (env.lookup(name) instanceof BuiltinFn fn ? fn.arity() : 1)
                : 1;
            env.define(name, new BuiltinFn(name, arity, args -> {
                throw new IrijRuntimeError(msg);
            }));
        }
    }

    /** Install all builtins into the given environment.
     *  @param pathResolver resolves relative file paths (null = use CWD) */
    public static void install(Environment env, PrintStream out, java.util.function.Function<String, Path> pathResolver) {
        // Boolean constants
        env.define("true", Boolean.TRUE);
        env.define("false", Boolean.FALSE);

        // ── I/O (requires Console effect) ──────────────────────────────
        env.define("print", new BuiltinFn("print", 1, List.of("Console"), args -> { dev.irij.compiler.RuntimeSupport.print(args.get(0)); return Values.UNIT; }));

        env.define("println", new BuiltinFn("println", 1, List.of("Console"), args -> { dev.irij.compiler.RuntimeSupport.println(args.get(0)); return Values.UNIT; }));

        env.define("dbg", new BuiltinFn("dbg", 1, List.of("Console"), args -> { dev.irij.compiler.RuntimeSupport.dbg(args.get(0)); return Values.UNIT; }));

        env.define("read-line", new BuiltinFn("read-line", 0, List.of("Console"), args -> dev.irij.compiler.RuntimeSupport.readLine()));

        env.define("to-str", new BuiltinFn("to-str", 1, args -> dev.irij.compiler.RuntimeSupport.toStr(args.get(0))));

        // ── Concurrency primitives ─────────────────────────────────────
        env.define("sleep", new BuiltinFn("sleep", 1, List.of("Time"), args -> dev.irij.compiler.RtConcurrency.sleep(args.get(0))));

        // ── Arithmetic ──────────────────────────────────────────────────
        env.define("quo", new BuiltinFn("quo", 2, args -> dev.irij.compiler.RtOps.div(args.get(0), args.get(1))));

        env.define("rem", new BuiltinFn("rem", 2, args -> dev.irij.compiler.RtOps.mod(args.get(0), args.get(1))));

        env.define("abs", new BuiltinFn("abs", 1, args -> dev.irij.compiler.RtMath.abs(args.get(0))));

        env.define("min", new BuiltinFn("min", 2, args -> dev.irij.compiler.RtOps.min(args.get(0), args.get(1))));

        env.define("max", new BuiltinFn("max", 2, args -> dev.irij.compiler.RtOps.max(args.get(0), args.get(1))));

        env.define("pi", Math.PI);
        env.define("e", Math.E);

        // ── Collection ──────────────────────────────────────────────────
        env.define("head", new BuiltinFn("head", 1, args -> dev.irij.compiler.RtCollections.head(args.get(0))));

        env.define("tail", new BuiltinFn("tail", 1, args -> dev.irij.compiler.RtCollections.tail(args.get(0))));

        env.define("length", new BuiltinFn("length", 1, args -> dev.irij.compiler.RtCollections.length(args.get(0))));

        // `empty? x` — true if a collection is empty (or null).
        env.define("empty?", new BuiltinFn("empty?", 1, args -> {
            var v = args.get(0);
            if (v == null) return Boolean.TRUE;
            if (v instanceof String s) return s.isEmpty();
            if (v instanceof IrijVector vec) return vec.elements().isEmpty();
            if (v instanceof IrijMap m) return m.entries().isEmpty();
            if (v instanceof IrijSet s) return s.elements().isEmpty();
            if (v instanceof IrijTuple t) return t.elements().length == 0;
            if (v instanceof IrijRange r) return r.size() == 0;
            throw new IrijRuntimeError("empty? expects a collection, got " + Values.typeName(v));
        }));

        // `conj v x` — append x to vector, returning a new vector.
        env.define("conj", new BuiltinFn("conj", 2, args -> dev.irij.compiler.RtCollections.conj(args.get(0), args.get(1))));

        env.define("reverse", new BuiltinFn("reverse", 1, args -> dev.irij.compiler.RtCollections.reverseVal(args.get(0))));

        env.define("sort", new BuiltinFn("sort", 1, args -> dev.irij.compiler.RtCollections.sortVal(args.get(0))));

        env.define("concat", new BuiltinFn("concat", 2, args -> dev.irij.compiler.RtOps.concat(args.get(0), args.get(1))));

        env.define("take", new BuiltinFn("take", 2, args -> dev.irij.compiler.RtCollections.takeVal(args.get(0), args.get(1))));

        env.define("drop", new BuiltinFn("drop", 2, args -> dev.irij.compiler.RtCollections.dropVal(args.get(0), args.get(1))));

        env.define("to-vec", new BuiltinFn("to-vec", 1, args -> dev.irij.compiler.RtCollections.toVec(args.get(0))));

        env.define("contains?", new BuiltinFn("contains?", 2, args -> dev.irij.compiler.RtCollections.containsP(args.get(0), args.get(1))));

        env.define("keys", new BuiltinFn("keys", 1, args -> dev.irij.compiler.RtCollections.keys(args.get(0))));

        env.define("vals", new BuiltinFn("vals", 1, args -> dev.irij.compiler.RtCollections.vals(args.get(0))));

        env.define("get", new BuiltinFn("get", 2, args -> dev.irij.compiler.RtCollections.getOp(args.get(0), args.get(1))));

        env.define("nth", new BuiltinFn("nth", 2, args -> dev.irij.compiler.RtCollections.nth(args.get(0), args.get(1))));

        env.define("last", new BuiltinFn("last", 1, args -> dev.irij.compiler.RtCollections.last(args.get(0))));


        // ── Functional ──────────────────────────────────────────────────
        env.define("identity", new BuiltinFn("identity", 1, args -> args.get(0)));
        env.define("const", new BuiltinFn("const", 2, args -> args.get(0)));
        env.define("flip", new BuiltinFn("flip", 3, args -> {
            // flip f a b = f b a
            // This is tricky — we need to return a partially applied version
            // For now: flip f returns a function that takes (a, b) and calls f(b, a)
            throw new IrijRuntimeError("flip requires partial application support");
        }));

        env.define("not", new BuiltinFn("not", 1, args -> dev.irij.compiler.RtOps.notOp(args.get(0))));

        // (`empty?` defined earlier with full collection coverage —
        //  Vector / Map / Set / Tuple / Range / String / null.)

        // ── Error handling ─────────────────────────────────────────────
        env.define("error", new BuiltinFn("error", 1, args -> dev.irij.compiler.RuntimeSupport.errorBuiltin(args.get(0))));

        // ── Type introspection ─────────────────────────────────────────
        env.define("type-of", new BuiltinFn("type-of", 1, args -> dev.irij.compiler.RuntimeSupport.typeOf(args.get(0))));

        // ── Dynamic map operations ─────────────────────────────────────
        env.define("assoc", new BuiltinFn("assoc", 3, args -> dev.irij.compiler.RtCollections.assoc(args.get(0), args.get(1), args.get(2))));

        env.define("dissoc", new BuiltinFn("dissoc", 2, args -> dev.irij.compiler.RtCollections.dissoc(args.get(0), args.get(1))));

        env.define("merge", new BuiltinFn("merge", 2, args -> dev.irij.compiler.RtCollections.merge(args.get(0), args.get(1))));

        // ── String operations ──────────────────────────────────────────
        env.define("split", new BuiltinFn("split", 2, args -> dev.irij.compiler.RtStrings.split(args.get(0), args.get(1))));

        env.define("join", new BuiltinFn("join", 2, args -> dev.irij.compiler.RtStrings.join(args.get(0), args.get(1))));

        env.define("trim", new BuiltinFn("trim", 1, args -> dev.irij.compiler.RtStrings.trimStr(args.get(0))));

        env.define("upper-case", new BuiltinFn("upper-case", 1, args -> dev.irij.compiler.RtStrings.upperCase(args.get(0))));

        env.define("lower-case", new BuiltinFn("lower-case", 1, args -> dev.irij.compiler.RtStrings.lowerCase(args.get(0))));

        env.define("starts-with?", new BuiltinFn("starts-with?", 2, args -> dev.irij.compiler.RtStrings.startsWithP(args.get(0), args.get(1))));

        env.define("ends-with?", new BuiltinFn("ends-with?", 2, args -> dev.irij.compiler.RtStrings.endsWithP(args.get(0), args.get(1))));

        env.define("replace", new BuiltinFn("replace", 3, args -> dev.irij.compiler.RtStrings.replace(args.get(0), args.get(1), args.get(2))));

        env.define("url-encode", new BuiltinFn("url-encode", 1, args -> dev.irij.compiler.RtStrings.urlEncode(args.get(0))));

        env.define("url-decode", new BuiltinFn("url-decode", 1, args -> dev.irij.compiler.RtStrings.urlDecode(args.get(0))));

        env.define("substring", new BuiltinFn("substring", 3, args -> dev.irij.compiler.RtStrings.substring(args.get(0), args.get(1), args.get(2))));

        env.define("char-at", new BuiltinFn("char-at", 2, args -> dev.irij.compiler.RtStrings.charAt(args.get(0), args.get(1))));

        env.define("index-of", new BuiltinFn("index-of", 2, args -> dev.irij.compiler.RtStrings.indexOf(args.get(0), args.get(1))));

        // ── Math operations ────────────────────────────────────────────
        env.define("sqrt", new BuiltinFn("sqrt", 1, args -> dev.irij.compiler.RtMath.sqrt(args.get(0))));

        env.define("floor", new BuiltinFn("floor", 1, args -> dev.irij.compiler.RtMath.floor(args.get(0))));

        env.define("ceil", new BuiltinFn("ceil", 1, args -> dev.irij.compiler.RtMath.ceil(args.get(0))));

        env.define("round", new BuiltinFn("round", 1, args -> dev.irij.compiler.RtMath.round(args.get(0))));

        env.define("sin", new BuiltinFn("sin", 1, args -> dev.irij.compiler.RtMath.sin(args.get(0))));

        env.define("cos", new BuiltinFn("cos", 1, args -> dev.irij.compiler.RtMath.cos(args.get(0))));

        env.define("tan", new BuiltinFn("tan", 1, args -> dev.irij.compiler.RtMath.tan(args.get(0))));

        env.define("log", new BuiltinFn("log", 1, args -> dev.irij.compiler.RtMath.log(args.get(0))));

        env.define("exp", new BuiltinFn("exp", 1, args -> dev.irij.compiler.RtMath.exp(args.get(0))));

        env.define("pow", new BuiltinFn("pow", 2, args -> dev.irij.compiler.RtMath.pow(args.get(0), args.get(1))));

        env.define("random-int", new BuiltinFn("random-int", 1, List.of("Random"), args -> dev.irij.compiler.RtMath.randomInt(args.get(0))));

        env.define("random-float", new BuiltinFn("random-float", 0, List.of("Random"), args -> dev.irij.compiler.RtMath.randomFloat()));

        // ── Crypto / auth primitives ───────────────────────────────────
        // Hashing is deterministic and pure; no effect gate. random-token
        // is non-deterministic and gated under Random.

        env.define("sha256-hex", new BuiltinFn("sha256-hex", 1, args -> dev.irij.compiler.RtMath.sha256Hex(args.get(0))));

        env.define("hmac-sha256-hex", new BuiltinFn("hmac-sha256-hex", 2, args -> dev.irij.compiler.RtMath.hmacSha256Hex(args.get(0), args.get(1))));

        env.define("random-token", new BuiltinFn("random-token", 1, List.of("Random"), args -> dev.irij.compiler.RtMath.randomToken(args.get(0))));

        // ── Conversion primitives ──────────────────────────────────────
        env.define("parse-int", new BuiltinFn("parse-int", 1, args -> dev.irij.compiler.RtMath.parseInt(args.get(0))));

        env.define("parse-float", new BuiltinFn("parse-float", 1, args -> dev.irij.compiler.RtMath.parseFloat(args.get(0))));

        env.define("char-code", new BuiltinFn("char-code", 1, args -> dev.irij.compiler.RtStrings.charCode(args.get(0))));

        env.define("from-char-code", new BuiltinFn("from-char-code", 1, args -> dev.irij.compiler.RtStrings.fromCharCode(args.get(0))));

        // FileIO primitives (read-file / write-file / file-exists?) —
        // removed phase 3d. Reach via the FileIO effect (std.fs) +
        // FsCapability.

        env.define("get-env", new BuiltinFn("get-env", 1, List.of("Env"), args -> dev.irij.compiler.RtIo.getEnv(args.get(0))));

        env.define("now-ms", new BuiltinFn("now-ms", 0, List.of("Time"), args -> dev.irij.compiler.RtIo.nowMs()));

        // ── JSON (pure transforms) ──────────────────────────────────────
        env.define("json-parse", new BuiltinFn("json-parse", 1, args -> dev.irij.compiler.RtIo.jsonParse(args.get(0))));

        env.define("json-encode", new BuiltinFn("json-encode", 1, args -> dev.irij.compiler.RtIo.jsonEncode(args.get(0))));

        env.define("json-encode-pretty", new BuiltinFn("json-encode-pretty", 1, args -> dev.irij.compiler.RtIo.jsonEncodePretty(args.get(0))));

        env.define("toml-parse", new BuiltinFn("toml-parse", 1, args -> dev.irij.compiler.RtIo.tomlParse(args.get(0))));

        // list-dir / delete-file / make-dir / append-file — removed
        // phase 3d. Reach via std.fs effect ops + FsCapability.

        // ── Database primitives — removed phase 3a ────────────────────────
        // The Db effect surface is now reached through the std.db handler
        // + JdbcCapability provider. Direct raw-db-* names are gone.

        // ── HTTP client primitives — removed phase 3b ───────────────────
        // Http effect routes through HttpClientCapability + std.http now.

        // Multipart raw-multipart-* — removed phase 3d. Reach via the
        // multipart-field / multipart-save effect ops on Serve, which
        // route through ServeCapability.

        // ── Miscellaneous ──────────────────────────────────────────────
        env.define("env", new BuiltinFn("env", -1, List.of("Env"), args -> {
            int argsCount = args.size();
            String envDefault = null;
            if (argsCount > 1) {
                envDefault = asString(args.get(argsCount - 1), "env");
            }
            int argNum = 0;
            String envValue = null;
            do {
                var envName = asString(args.get(argNum), "env");
                envValue = System.getenv(envName);
            } while (envValue == null && ++argNum < argsCount - 1);
            if (envValue != null) {
                return envValue;
            }
            if (envDefault != null) {
                return envDefault;
            }
            throw new IrijRuntimeError("env: environment variable does not exists and no default defined");
        }));
    }

    // (Multipart parsing helpers removed phase 3d — they live in
    // ServeCapability now, beside the rest of the request-shaped ops.)

    // ═══════════════════════════════════════════════════════════════════
    // Utilities
    // ═══════════════════════════════════════════════════════════════════

    /** Convert a duration argument to milliseconds (Int=ms, Float=seconds). */
    static long toMillis(Object value) {
        if (value instanceof Long l) return l;
        if (value instanceof Double d) return (long)(d * 1000);
        throw new IrijRuntimeError(
            "Duration expects Int (milliseconds) or Float (seconds), got " + Values.typeName(value));
    }

    // ── JSON conversion helpers ────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public static Object tomlValueToIrij(Object val) {
        if (val == null) return Values.UNIT;
        if (val instanceof String s) return s;
        if (val instanceof Boolean b) return b;
        if (val instanceof Long l) return l;
        if (val instanceof Integer i) return (long) i;
        if (val instanceof Double d) return d;
        if (val instanceof Float f) return (double) f;
        if (val instanceof java.util.Date date) return date.getTime();
        if (val instanceof List<?> list) {
            var out = new ArrayList<Object>(list.size());
            for (var e : list) out.add(tomlValueToIrij(e));
            return new IrijVector(out);
        }
        if (val instanceof Map<?, ?> m) {
            var out = new LinkedHashMap<String, Object>();
            for (var e : ((Map<String, Object>) m).entrySet())
                out.put(e.getKey(), tomlValueToIrij(e.getValue()));
            return new IrijMap(out);
        }
        return val.toString();
    }

    // rawHttpRequestImpl removed phase 3b — lives in HttpClientCapability.

    public static Object jsonToIrij(JsonElement el) {
        if (el == null || el.isJsonNull()) return Values.UNIT;
        if (el.isJsonPrimitive()) {
            var p = el.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isString()) return p.getAsString();
            if (p.isNumber()) {
                // Try long first (exact integers)
                try {
                    long l = p.getAsLong();
                    if (String.valueOf(l).equals(p.getAsString())
                        || p.getAsBigDecimal().stripTrailingZeros().scale() <= 0) {
                        return l;
                    }
                } catch (NumberFormatException ignored) {}
                return p.getAsDouble();
            }
        }
        if (el.isJsonArray()) {
            var arr = el.getAsJsonArray();
            var list = new ArrayList<Object>(arr.size());
            for (var e : arr) list.add(jsonToIrij(e));
            return new IrijVector(list);
        }
        if (el.isJsonObject()) {
            var obj = el.getAsJsonObject();
            var map = new LinkedHashMap<String, Object>();
            for (var e : obj.entrySet()) map.put(e.getKey(), jsonToIrij(e.getValue()));
            return new IrijMap(map);
        }
        return Values.UNIT;
    }

    public static JsonElement irijToJson(Object value) {
        if (value == null || value == Values.UNIT) return JsonNull.INSTANCE;
        if (value instanceof String s) return new JsonPrimitive(s);
        if (value instanceof Long l) return new JsonPrimitive(l);
        if (value instanceof Double d) return new JsonPrimitive(d);
        if (value instanceof Boolean b) return new JsonPrimitive(b);
        if (value instanceof Keyword kw) return new JsonPrimitive(":" + kw.name());
        if (value instanceof Rational r) return new JsonPrimitive(r.toDouble());
        if (value instanceof IrijMap m) {
            var obj = new JsonObject();
            for (var e : m.entries().entrySet()) obj.add(e.getKey(), irijToJson(e.getValue()));
            return obj;
        }
        if (value instanceof IrijVector v) {
            var arr = new JsonArray();
            for (var e : v.elements()) arr.add(irijToJson(e));
            return arr;
        }
        if (value instanceof IrijTuple t) {
            var arr = new JsonArray();
            for (var e : t.elements()) arr.add(irijToJson(e));
            return arr;
        }
        if (value instanceof IrijSet s) {
            var arr = new JsonArray();
            for (var e : s.elements()) arr.add(irijToJson(e));
            return arr;
        }
        if (value instanceof Tagged tagged) {
            var obj = new JsonObject();
            obj.addProperty("_tag", tagged.tag());
            if (tagged.namedFields() != null) {
                for (var e : tagged.namedFields().entrySet())
                    obj.add(e.getKey(), irijToJson(e.getValue()));
            } else if (!tagged.fields().isEmpty()) {
                var arr = new JsonArray();
                for (var f : tagged.fields()) arr.add(irijToJson(f));
                obj.add("_fields", arr);
            }
            return obj;
        }
        // Fallback: convert to string
        return new JsonPrimitive(Values.toIrijString(value));
    }

    // (DB extract/bind/conversion helpers removed in phase 3a; they live
    // in JdbcCapability now.)

    static long asLong(Object value, String context) {
        if (value instanceof Long l) return l;
        throw new IrijRuntimeError(context + " expects Int, got " + Values.typeName(value));
    }

    static double asDouble(Object value, String context) {
        if (value instanceof Double d) return d;
        if (value instanceof Long l) return l.doubleValue();
        throw new IrijRuntimeError(context + " expects a number, got " + Values.typeName(value));
    }

    static String asString(Object value, String context) {
        if (value instanceof String s) return s;
        throw new IrijRuntimeError(context + " expects Str, got " + Values.typeName(value));
    }

    /**
     * Resolve a file path using the given resolver function.
     * If no resolver is provided, paths resolve against CWD (Path.of behavior).
     */
    static Path resolvePath(String path, java.util.function.Function<String, Path> resolver) {
        if (resolver != null) return resolver.apply(path);
        return Path.of(path);
    }

    public static int compare(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) return Long.compare(la, lb);
        if (a instanceof Double da && b instanceof Double db) return Double.compare(da, db);
        if (a instanceof Long la && b instanceof Double db) return Double.compare(la, db);
        if (a instanceof Double da && b instanceof Long lb) return Double.compare(da, lb);
        if (a instanceof String sa && b instanceof String sb) return sa.compareTo(sb);
        if (a instanceof Keyword ka && b instanceof Keyword kb) return ka.name().compareTo(kb.name());
        // Tuple comparison: lexicographic
        if (a instanceof IrijTuple ta && b instanceof IrijTuple tb) {
            int len = Math.min(ta.elements().length, tb.elements().length);
            for (int i = 0; i < len; i++) {
                int cmp = compare(ta.elements()[i], tb.elements()[i]);
                if (cmp != 0) return cmp;
            }
            return Integer.compare(ta.elements().length, tb.elements().length);
        }
        // Vector comparison: lexicographic
        if (a instanceof IrijVector va && b instanceof IrijVector vb) {
            int len = Math.min(va.elements().size(), vb.elements().size());
            for (int i = 0; i < len; i++) {
                int cmp = compare(va.elements().get(i), vb.elements().get(i));
                if (cmp != 0) return cmp;
            }
            return Integer.compare(va.elements().size(), vb.elements().size());
        }
        throw new IrijRuntimeError("Cannot compare " + Values.typeName(a) + " and " + Values.typeName(b));
    }

    static Object concatValues(Object a, Object b) {
        if (a instanceof IrijVector va && b instanceof IrijVector vb) {
            var combined = new ArrayList<>(va.elements());
            combined.addAll(vb.elements());
            return new IrijVector(combined);
        }
        if (a instanceof String sa && b instanceof String sb) {
            return sa + sb;
        }
        if (a instanceof IrijVector va) {
            var combined = new ArrayList<>(va.elements());
            combined.add(b);
            return new IrijVector(combined);
        }
        throw new IrijRuntimeError("Cannot concat " + Values.typeName(a) + " and " + Values.typeName(b));
    }

    /** Convert any iterable value to a List<Object>. */
    static List<Object> toList(Object value) {
        if (value instanceof IrijVector vec) return new ArrayList<>(vec.elements());
        if (value instanceof IrijSet set) return new ArrayList<>(set.elements());
        if (value instanceof IrijRange range) {
            var list = new ArrayList<Object>();
            for (var e : range) list.add(e);
            return list;
        }
        if (value instanceof LazyIterable li) {
            var list = new ArrayList<Object>();
            for (var e : li) list.add(e);
            return list;
        }
        throw new IrijRuntimeError("Cannot iterate over " + Values.typeName(value));
    }

    /** Get an iterable view of any collection-like value. */
    static Iterable<Object> toIterable(Object value) {
        if (value instanceof IrijVector vec) return vec.elements();
        if (value instanceof IrijSet set) return set.elements();
        if (value instanceof IrijRange range) return range;
        if (value instanceof LazyIterable li) return li;
        throw new IrijRuntimeError("Cannot iterate over " + Values.typeName(value));
    }

    // ── Rational arithmetic ───────────────────────────────────────────────

    static Rational addRational(Rational a, Rational b) {
        return new Rational(a.num() * b.den() + b.num() * a.den(), a.den() * b.den());
    }

    static Rational subRational(Rational a, Rational b) {
        return new Rational(a.num() * b.den() - b.num() * a.den(), a.den() * b.den());
    }

    static Rational mulRational(Rational a, Rational b) {
        return new Rational(a.num() * b.num(), a.den() * b.den());
    }

    static Rational divRational(Rational a, Rational b) {
        return new Rational(a.num() * b.den(), a.den() * b.num());
    }

    // ── Lazy iterable wrappers ──────────────────────────────────────────

    /** A lazy mapped iterable. */
    public record LazyIterable(Iterable<Object> source, java.util.function.Function<Object, Object> transform,
                               java.util.function.Predicate<Object> filter) implements Iterable<Object> {
        /** Map-only constructor. */
        public LazyIterable(Iterable<Object> source, java.util.function.Function<Object, Object> transform) {
            this(source, transform, null);
        }

        /** Filter-only constructor. */
        public LazyIterable(Iterable<Object> source, java.util.function.Predicate<Object> filter, boolean dummy) {
            this(source, null, filter);
        }

        @Override
        public Iterator<Object> iterator() {
            if (transform != null && filter == null) {
                return new Iterator<>() {
                    final Iterator<Object> it = source.iterator();
                    @Override public boolean hasNext() { return it.hasNext(); }
                    @Override public Object next() { return transform.apply(it.next()); }
                };
            }
            if (filter != null && transform == null) {
                return new Iterator<>() {
                    final Iterator<Object> it = source.iterator();
                    Object nextVal;
                    boolean hasNext;
                    { advance(); }
                    private void advance() {
                        while (it.hasNext()) {
                            nextVal = it.next();
                            if (filter.test(nextVal)) { hasNext = true; return; }
                        }
                        hasNext = false;
                    }
                    @Override public boolean hasNext() { return hasNext; }
                    @Override public Object next() {
                        var v = nextVal;
                        advance();
                        return v;
                    }
                };
            }
            // Both map and filter
            return new Iterator<>() {
                final Iterator<Object> it = source.iterator();
                Object nextVal;
                boolean hasNext;
                { advance(); }
                private void advance() {
                    while (it.hasNext()) {
                        var raw = it.next();
                        var mapped = transform != null ? transform.apply(raw) : raw;
                        if (filter == null || filter.test(mapped)) {
                            nextVal = mapped;
                            hasNext = true;
                            return;
                        }
                    }
                    hasNext = false;
                }
                @Override public boolean hasNext() { return hasNext; }
                @Override public Object next() {
                    var v = nextVal;
                    advance();
                    return v;
                }
            };
        }
    }
}
