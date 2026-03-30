package dev.irij.interpreter;

import dev.irij.ast.Node.SourceLoc;
import dev.irij.interpreter.Values.*;

import com.google.gson.*;
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

    /** Install all builtins into the given environment. */
    public static void install(Environment env, PrintStream out) {
        // Boolean constants
        env.define("true", Boolean.TRUE);
        env.define("false", Boolean.FALSE);

        // ── I/O (requires Console effect) ──────────────────────────────
        env.define("print", new BuiltinFn("print", 1, List.of("Console"), args -> {
            out.print(Values.toIrijString(args.get(0)));
            return Values.UNIT;
        }));

        env.define("println", new BuiltinFn("println", 1, List.of("Console"), args -> {
            out.println(Values.toIrijString(args.get(0)));
            return Values.UNIT;
        }));

        env.define("dbg", new BuiltinFn("dbg", 1, List.of("Console"), args -> {
            var v = args.get(0);
            out.println("[dbg] " + Values.typeName(v) + ": " + Values.toIrijString(v));
            return v;
        }));

        env.define("read-line", new BuiltinFn("read-line", 0, List.of("Console"), args -> {
            try {
                var reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
                var line = reader.readLine();
                return line != null ? line : Values.UNIT;
            } catch (java.io.IOException e) {
                throw new IrijRuntimeError("read-line: " + e.getMessage(), null);
            }
        }));

        env.define("to-str", new BuiltinFn("to-str", 1, args -> {
            return Values.toIrijString(args.get(0));
        }));

        // ── Concurrency primitives ─────────────────────────────────────
        env.define("sleep", new BuiltinFn("sleep", 1, args -> {
            var ms = args.get(0);
            long millis;
            if (ms instanceof Long l)  millis = l;                      // sleep 1000  → 1 second
            else if (ms instanceof Double d) millis = (long)(d * 1000); // sleep 1.5   → 1500 ms
            else throw new IrijRuntimeError(
                    "sleep expects Int (milliseconds) or Float (seconds), got " + Values.typeName(ms));
            try { Thread.sleep(millis); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IrijRuntimeError("interrupted");
            }
            return Values.UNIT;
        }));

        // ── Arithmetic ──────────────────────────────────────────────────
        env.define("div", new BuiltinFn("div", 2, args -> {
            long a = asLong(args.get(0), "div");
            long b = asLong(args.get(1), "div");
            if (b == 0) throw new IrijRuntimeError("Division by zero");
            return a / b;
        }));

        env.define("mod", new BuiltinFn("mod", 2, args -> {
            long a = asLong(args.get(0), "mod");
            long b = asLong(args.get(1), "mod");
            if (b == 0) throw new IrijRuntimeError("Division by zero");
            return a % b;
        }));

        env.define("abs", new BuiltinFn("abs", 1, args -> {
            var v = args.get(0);
            if (v instanceof Long l) return Math.abs(l);
            if (v instanceof Double d) return Math.abs(d);
            throw new IrijRuntimeError("abs expects a number, got " + Values.typeName(v));
        }));

        env.define("min", new BuiltinFn("min", 2, args -> {
            return compare(args.get(0), args.get(1)) <= 0 ? args.get(0) : args.get(1);
        }));

        env.define("max", new BuiltinFn("max", 2, args -> {
            return compare(args.get(0), args.get(1)) >= 0 ? args.get(0) : args.get(1);
        }));

        env.define("pi", Math.PI);
        env.define("e", Math.E);

        // ── Collection ──────────────────────────────────────────────────
        env.define("head", new BuiltinFn("head", 1, args -> {
            var v = args.get(0);
            if (v instanceof IrijVector vec) {
                if (vec.elements().isEmpty()) throw new IrijRuntimeError("head of empty vector");
                return vec.elements().get(0);
            }
            throw new IrijRuntimeError("head expects a Vector, got " + Values.typeName(v));
        }));

        env.define("tail", new BuiltinFn("tail", 1, args -> {
            var v = args.get(0);
            if (v instanceof IrijVector vec) {
                if (vec.elements().isEmpty()) throw new IrijRuntimeError("tail of empty vector");
                return new IrijVector(vec.elements().subList(1, vec.elements().size()));
            }
            throw new IrijRuntimeError("tail expects a Vector, got " + Values.typeName(v));
        }));

        env.define("length", new BuiltinFn("length", 1, args -> {
            var v = args.get(0);
            if (v instanceof IrijVector vec) return (long) vec.elements().size();
            if (v instanceof String s) return (long) s.length();
            if (v instanceof IrijMap m) return (long) m.entries().size();
            if (v instanceof IrijSet s) return (long) s.elements().size();
            if (v instanceof IrijTuple t) return (long) t.elements().length;
            if (v instanceof IrijRange r) return (long) r.size();
            throw new IrijRuntimeError("length expects a collection, got " + Values.typeName(v));
        }));

        env.define("reverse", new BuiltinFn("reverse", 1, args -> {
            var v = args.get(0);
            if (v instanceof IrijVector vec) {
                var rev = new ArrayList<>(vec.elements());
                Collections.reverse(rev);
                return new IrijVector(rev);
            }
            if (v instanceof String s) return new StringBuilder(s).reverse().toString();
            throw new IrijRuntimeError("reverse expects a Vector or Str, got " + Values.typeName(v));
        }));

        env.define("sort", new BuiltinFn("sort", 1, args -> {
            var v = args.get(0);
            if (v instanceof IrijVector vec) {
                var sorted = new ArrayList<>(vec.elements());
                sorted.sort((a, b) -> compare(a, b));
                return new IrijVector(sorted);
            }
            throw new IrijRuntimeError("sort expects a Vector, got " + Values.typeName(v));
        }));

        env.define("concat", new BuiltinFn("concat", 2, args -> {
            return concatValues(args.get(0), args.get(1));
        }));

        env.define("take", new BuiltinFn("take", 2, args -> {
            long n = asLong(args.get(0), "take");
            var v = args.get(1);
            var list = toList(v);
            return new IrijVector(list.subList(0, (int) Math.min(n, list.size())));
        }));

        env.define("drop", new BuiltinFn("drop", 2, args -> {
            long n = asLong(args.get(0), "drop");
            var v = args.get(1);
            var list = toList(v);
            return new IrijVector(list.subList((int) Math.min(n, list.size()), list.size()));
        }));

        env.define("to-vec", new BuiltinFn("to-vec", 1, args -> {
            return new IrijVector(toList(args.get(0)));
        }));

        env.define("contains?", new BuiltinFn("contains?", 2, args -> {
            var coll = args.get(0);
            var elem = args.get(1);
            if (coll instanceof IrijVector vec) return vec.elements().contains(elem);
            if (coll instanceof IrijSet set) return set.elements().contains(elem);
            if (coll instanceof IrijMap map) return map.entries().containsKey(Values.toIrijString(elem));
            throw new IrijRuntimeError("contains? expects a collection, got " + Values.typeName(coll));
        }));

        env.define("keys", new BuiltinFn("keys", 1, args -> {
            if (args.get(0) instanceof IrijMap map) {
                return new IrijVector(new ArrayList<>(map.entries().keySet()));
            }
            throw new IrijRuntimeError("keys expects a Map");
        }));

        env.define("vals", new BuiltinFn("vals", 1, args -> {
            if (args.get(0) instanceof IrijMap map) {
                return new IrijVector(new ArrayList<>(map.entries().values()));
            }
            throw new IrijRuntimeError("vals expects a Map");
        }));

        env.define("get", new BuiltinFn("get", 2, args -> {
            var key = args.get(0);
            var coll = args.get(1);
            if (coll instanceof IrijMap map) {
                var v = map.entries().get(Values.toIrijString(key));
                return v != null ? v : Values.UNIT;
            }
            if (coll instanceof IrijVector vec) {
                long idx = asLong(key, "get");
                if (idx < 0 || idx >= vec.elements().size()) return Values.UNIT;
                return vec.elements().get((int) idx);
            }
            if (coll instanceof IrijTuple tup) {
                long idx = asLong(key, "get");
                if (idx < 0 || idx >= tup.elements().length) return Values.UNIT;
                return tup.elements()[(int) idx];
            }
            throw new IrijRuntimeError("get expects a Map, Vector, or Tuple as second argument");
        }));

        env.define("nth", new BuiltinFn("nth", 2, args -> {
            long idx = asLong(args.get(0), "nth");
            var coll = args.get(1);
            if (coll instanceof IrijVector vec) {
                if (idx < 0 || idx >= vec.elements().size())
                    throw new IrijRuntimeError("nth: index " + idx + " out of bounds (size " + vec.elements().size() + ")");
                return vec.elements().get((int) idx);
            }
            if (coll instanceof IrijTuple tup) {
                if (idx < 0 || idx >= tup.elements().length)
                    throw new IrijRuntimeError("nth: index " + idx + " out of bounds (size " + tup.elements().length + ")");
                return tup.elements()[(int) idx];
            }
            throw new IrijRuntimeError("nth expects a Vector or Tuple, got " + Values.typeName(coll));
        }));

        env.define("last", new BuiltinFn("last", 1, args -> {
            var v = args.get(0);
            if (v instanceof IrijVector vec) {
                if (vec.elements().isEmpty()) throw new IrijRuntimeError("last of empty vector");
                return vec.elements().get(vec.elements().size() - 1);
            }
            throw new IrijRuntimeError("last expects a Vector, got " + Values.typeName(v));
        }));


        // ── Functional ──────────────────────────────────────────────────
        env.define("identity", new BuiltinFn("identity", 1, args -> args.get(0)));
        env.define("const", new BuiltinFn("const", 2, args -> args.get(0)));
        env.define("flip", new BuiltinFn("flip", 3, args -> {
            // flip f a b = f b a
            // This is tricky — we need to return a partially applied version
            // For now: flip f returns a function that takes (a, b) and calls f(b, a)
            throw new IrijRuntimeError("flip requires partial application support");
        }));

        env.define("not", new BuiltinFn("not", 1, args -> {
            return !Values.isTruthy(args.get(0));
        }));

        env.define("empty?", new BuiltinFn("empty?", 1, args -> {
            var v = args.get(0);
            if (v instanceof IrijVector vec) return vec.elements().isEmpty();
            if (v instanceof IrijSet set) return set.elements().isEmpty();
            if (v instanceof IrijMap map) return map.entries().isEmpty();
            if (v instanceof String s) return s.isEmpty();
            return false;
        }));

        // ── Error handling ─────────────────────────────────────────────
        env.define("error", new BuiltinFn("error", 1, args -> {
            throw new IrijRuntimeError(Values.toIrijString(args.get(0)));
        }));

        // ── Type introspection ─────────────────────────────────────────
        env.define("type-of", new BuiltinFn("type-of", 1, args -> {
            return Values.typeName(args.get(0));
        }));

        // ── Dynamic map operations ─────────────────────────────────────
        env.define("assoc", new BuiltinFn("assoc", 3, args -> {
            var m = args.get(0);
            var key = Values.toIrijString(args.get(1));
            var val = args.get(2);
            if (m instanceof IrijMap map) {
                var entries = new LinkedHashMap<>(map.entries());
                entries.put(key, val);
                return new IrijMap(entries);
            }
            throw new IrijRuntimeError("assoc expects a Map as first argument, got " + Values.typeName(m));
        }));

        env.define("dissoc", new BuiltinFn("dissoc", 2, args -> {
            var m = args.get(0);
            var key = Values.toIrijString(args.get(1));
            if (m instanceof IrijMap map) {
                var entries = new LinkedHashMap<>(map.entries());
                entries.remove(key);
                return new IrijMap(entries);
            }
            throw new IrijRuntimeError("dissoc expects a Map as first argument, got " + Values.typeName(m));
        }));

        env.define("merge", new BuiltinFn("merge", 2, args -> {
            var m1 = args.get(0);
            var m2 = args.get(1);
            if (m1 instanceof IrijMap map1 && m2 instanceof IrijMap map2) {
                var entries = new LinkedHashMap<>(map1.entries());
                entries.putAll(map2.entries());
                return new IrijMap(entries);
            }
            throw new IrijRuntimeError("merge expects two Maps, got " + Values.typeName(m1) + " and " + Values.typeName(m2));
        }));

        // ── String operations ──────────────────────────────────────────
        env.define("split", new BuiltinFn("split", 2, args -> {
            var str = asString(args.get(0), "split");
            var sep = asString(args.get(1), "split");
            var parts = sep.isEmpty()
                ? str.chars().mapToObj(c -> String.valueOf((char) c)).toList()
                : Arrays.asList(str.split(java.util.regex.Pattern.quote(sep), -1));
            return new IrijVector(new ArrayList<>(parts));
        }));

        env.define("join", new BuiltinFn("join", 2, args -> {
            var sep = asString(args.get(0), "join");
            var coll = args.get(1);
            var list = toList(coll);
            return list.stream().map(Values::toIrijString).collect(Collectors.joining(sep));
        }));

        env.define("trim", new BuiltinFn("trim", 1, args -> {
            return asString(args.get(0), "trim").strip();
        }));

        env.define("upper-case", new BuiltinFn("upper-case", 1, args -> {
            return asString(args.get(0), "upper-case").toUpperCase();
        }));

        env.define("lower-case", new BuiltinFn("lower-case", 1, args -> {
            return asString(args.get(0), "lower-case").toLowerCase();
        }));

        env.define("starts-with?", new BuiltinFn("starts-with?", 2, args -> {
            return asString(args.get(0), "starts-with?").startsWith(asString(args.get(1), "starts-with?"));
        }));

        env.define("ends-with?", new BuiltinFn("ends-with?", 2, args -> {
            return asString(args.get(0), "ends-with?").endsWith(asString(args.get(1), "ends-with?"));
        }));

        env.define("replace", new BuiltinFn("replace", 3, args -> {
            var str = asString(args.get(0), "replace");
            var from = asString(args.get(1), "replace");
            var to = asString(args.get(2), "replace");
            return str.replace(from, to);
        }));

        env.define("substring", new BuiltinFn("substring", 3, args -> {
            var str = asString(args.get(0), "substring");
            int start = (int) asLong(args.get(1), "substring");
            int end = (int) asLong(args.get(2), "substring");
            if (start < 0 || end > str.length() || start > end)
                throw new IrijRuntimeError("substring: index out of bounds (start=" + start + ", end=" + end + ", length=" + str.length() + ")");
            return str.substring(start, end);
        }));

        env.define("char-at", new BuiltinFn("char-at", 2, args -> {
            var str = asString(args.get(0), "char-at");
            int idx = (int) asLong(args.get(1), "char-at");
            if (idx < 0 || idx >= str.length())
                throw new IrijRuntimeError("char-at: index " + idx + " out of bounds (length " + str.length() + ")");
            return String.valueOf(str.charAt(idx));
        }));

        env.define("index-of", new BuiltinFn("index-of", 2, args -> {
            var str = asString(args.get(0), "index-of");
            var sub = asString(args.get(1), "index-of");
            return (long) str.indexOf(sub);
        }));

        // ── Math operations ────────────────────────────────────────────
        env.define("sqrt", new BuiltinFn("sqrt", 1, args -> {
            return Math.sqrt(asDouble(args.get(0), "sqrt"));
        }));

        env.define("floor", new BuiltinFn("floor", 1, args -> {
            return (long) Math.floor(asDouble(args.get(0), "floor"));
        }));

        env.define("ceil", new BuiltinFn("ceil", 1, args -> {
            return (long) Math.ceil(asDouble(args.get(0), "ceil"));
        }));

        env.define("round", new BuiltinFn("round", 1, args -> {
            return Math.round(asDouble(args.get(0), "round"));
        }));

        env.define("sin", new BuiltinFn("sin", 1, args -> {
            return Math.sin(asDouble(args.get(0), "sin"));
        }));

        env.define("cos", new BuiltinFn("cos", 1, args -> {
            return Math.cos(asDouble(args.get(0), "cos"));
        }));

        env.define("tan", new BuiltinFn("tan", 1, args -> {
            return Math.tan(asDouble(args.get(0), "tan"));
        }));

        env.define("log", new BuiltinFn("log", 1, args -> {
            return Math.log(asDouble(args.get(0), "log"));
        }));

        env.define("exp", new BuiltinFn("exp", 1, args -> {
            return Math.exp(asDouble(args.get(0), "exp"));
        }));

        env.define("pow", new BuiltinFn("pow", 2, args -> {
            return Math.pow(asDouble(args.get(0), "pow"), asDouble(args.get(1), "pow"));
        }));

        env.define("random-int", new BuiltinFn("random-int", 1, args -> {
            long bound = asLong(args.get(0), "random-int");
            return ThreadLocalRandom.current().nextLong(bound);
        }));

        env.define("random-float", new BuiltinFn("random-float", 0, args -> {
            return ThreadLocalRandom.current().nextDouble();
        }));

        // ── Conversion primitives ──────────────────────────────────────
        env.define("parse-int", new BuiltinFn("parse-int", 1, args -> {
            var str = asString(args.get(0), "parse-int");
            try { return Long.parseLong(str.strip()); }
            catch (NumberFormatException e) {
                throw new IrijRuntimeError("parse-int: cannot parse '" + str + "' as Int");
            }
        }));

        env.define("parse-float", new BuiltinFn("parse-float", 1, args -> {
            var str = asString(args.get(0), "parse-float");
            try { return Double.parseDouble(str.strip()); }
            catch (NumberFormatException e) {
                throw new IrijRuntimeError("parse-float: cannot parse '" + str + "' as Float");
            }
        }));

        env.define("char-code", new BuiltinFn("char-code", 1, args -> {
            var str = asString(args.get(0), "char-code");
            if (str.isEmpty()) throw new IrijRuntimeError("char-code: empty string");
            return (long) str.codePointAt(0);
        }));

        env.define("from-char-code", new BuiltinFn("from-char-code", 1, args -> {
            int cp = (int) asLong(args.get(0), "from-char-code");
            return String.valueOf(Character.toChars(cp));
        }));

        // ── IO primitives ──────────────────────────────────────────────
        env.define("read-file", new BuiltinFn("read-file", 1, args -> {
            var path = asString(args.get(0), "read-file");
            try { return Files.readString(Path.of(path)); }
            catch (IOException e) {
                throw new IrijRuntimeError("read-file: " + e.getMessage());
            }
        }));

        env.define("write-file", new BuiltinFn("write-file", 2, args -> {
            var path = asString(args.get(0), "write-file");
            var content = asString(args.get(1), "write-file");
            try { Files.writeString(Path.of(path), content); }
            catch (IOException e) {
                throw new IrijRuntimeError("write-file: " + e.getMessage());
            }
            return Values.UNIT;
        }));

        env.define("file-exists?", new BuiltinFn("file-exists?", 1, args -> {
            return Files.exists(Path.of(asString(args.get(0), "file-exists?")));
        }));

        env.define("get-env", new BuiltinFn("get-env", 1, args -> {
            var name = asString(args.get(0), "get-env");
            var val = System.getenv(name);
            return val != null ? val : Values.UNIT;
        }));

        env.define("now-ms", new BuiltinFn("now-ms", 0, args -> {
            return System.currentTimeMillis();
        }));

        // ── JSON (pure transforms) ──────────────────────────────────────
        env.define("json-parse", new BuiltinFn("json-parse", 1, args -> {
            var str = asString(args.get(0), "json-parse");
            try {
                return jsonToIrij(JsonParser.parseString(str));
            } catch (JsonSyntaxException e) {
                throw new IrijRuntimeError("json-parse: " + e.getMessage());
            }
        }));

        env.define("json-encode", new BuiltinFn("json-encode", 1, args -> {
            return irijToJson(args.get(0)).toString();
        }));

        env.define("json-encode-pretty", new BuiltinFn("json-encode-pretty", 1, args -> {
            var gson = new GsonBuilder().setPrettyPrinting().create();
            return gson.toJson(irijToJson(args.get(0)));
        }));

        // ── Additional FS primitives ────────────────────────────────────
        env.define("list-dir", new BuiltinFn("list-dir", 1, args -> {
            var path = asString(args.get(0), "list-dir");
            try (var stream = Files.list(Path.of(path))) {
                return new IrijVector(stream.map(p -> (Object) p.getFileName().toString()).toList());
            } catch (IOException e) {
                throw new IrijRuntimeError("list-dir: " + e.getMessage());
            }
        }));

        env.define("delete-file", new BuiltinFn("delete-file", 1, args -> {
            var path = asString(args.get(0), "delete-file");
            try { Files.deleteIfExists(Path.of(path)); }
            catch (IOException e) {
                throw new IrijRuntimeError("delete-file: " + e.getMessage());
            }
            return Values.UNIT;
        }));

        env.define("make-dir", new BuiltinFn("make-dir", 1, args -> {
            var path = asString(args.get(0), "make-dir");
            try { Files.createDirectories(Path.of(path)); }
            catch (IOException e) {
                throw new IrijRuntimeError("make-dir: " + e.getMessage());
            }
            return Values.UNIT;
        }));

        env.define("append-file", new BuiltinFn("append-file", 2, args -> {
            var path = asString(args.get(0), "append-file");
            var content = asString(args.get(1), "append-file");
            try {
                Files.writeString(Path.of(path), content,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new IrijRuntimeError("append-file: " + e.getMessage());
            }
            return Values.UNIT;
        }));

        // ── HTTP primitives ─────────────────────────────────────────────
        env.define("_http-request", new BuiltinFn("_http-request", 1, args -> {
            if (!(args.get(0) instanceof IrijMap opts))
                throw new IrijRuntimeError("_http-request: expects Map argument");
            var entries = opts.entries();
            var url = entries.get("url");
            if (!(url instanceof String urlStr))
                throw new IrijRuntimeError("_http-request: missing or invalid 'url' field");
            var method = entries.getOrDefault("method", "GET").toString();
            var body = entries.get("body");
            var headers = entries.get("headers");
            try {
                var client = java.net.http.HttpClient.newHttpClient();
                var reqBuilder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(urlStr));
                if (headers instanceof IrijMap hm) {
                    for (var e : hm.entries().entrySet()) {
                        reqBuilder.header(e.getKey(), Values.toIrijString(e.getValue()));
                    }
                }
                if (body instanceof String bodyStr) {
                    reqBuilder.method(method, java.net.http.HttpRequest.BodyPublishers.ofString(bodyStr));
                } else {
                    reqBuilder.method(method, java.net.http.HttpRequest.BodyPublishers.noBody());
                }
                var resp = client.send(reqBuilder.build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
                var respHeaders = new LinkedHashMap<String, Object>();
                resp.headers().map().forEach((k, v) ->
                    respHeaders.put(k, v.size() == 1 ? v.get(0) : String.join(", ", v)));
                var result = new LinkedHashMap<String, Object>();
                result.put("status", (long) resp.statusCode());
                result.put("body", resp.body());
                result.put("headers", new IrijMap(respHeaders));
                return new IrijMap(result);
            } catch (Exception e) {
                throw new IrijRuntimeError("_http-request: " + e.getMessage());
            }
        }));
    }

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

    static Object jsonToIrij(JsonElement el) {
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

    static JsonElement irijToJson(Object value) {
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

    static int compare(Object a, Object b) {
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
