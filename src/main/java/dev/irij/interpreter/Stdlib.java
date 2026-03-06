package dev.irij.interpreter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static dev.irij.interpreter.IrijInterpreter.UNIT;

/**
 * Irij standard library — built-in functions implemented in Java.
 *
 * <p>Modules:
 * <ul>
 *   <li>{@code std.core}       — identity, const, flip, compose, to-str, show, type-of
 *   <li>{@code std.math}       — abs, min, max, floor, ceil, round, sqrt, pow, pi, e, random
 *   <li>{@code std.text}       — length, split, join, trim, contains?, starts-with?, ends-with?,
 *                                 to-upper, to-lower, replace, substring, chars, repeat
 *   <li>{@code std.collection} — head, tail, take, drop, reverse, sort, concat, flatten, zip,
 *                                 get, put, remove, keys, values, size, empty?, range, map,
 *                                 filter, reduce, each
 *   <li>{@code std.io}         — print, println, read-line, read-file, write-file
 *   <li>Constructors           — Ok, Err, Some, None
 * </ul>
 */
public final class Stdlib {

    private Stdlib() {}

    /**
     * Register all built-in functions into the given map.
     */
    public static void register(Map<String, Builtin> builtins) {
        registerCore(builtins);
        registerMath(builtins);
        registerText(builtins);
        registerCollection(builtins);
        registerIo(builtins);
        registerConstructors(builtins);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // std.core
    // ═══════════════════════════════════════════════════════════════════════

    private static void registerCore(Map<String, Builtin> b) {
        b.put("identity", args -> arg(args, 0));
        b.put("to-str", args -> show(arg(args, 0)));
        b.put("show", args -> show(arg(args, 0)));
        b.put("type-of", args -> typeOf(arg(args, 0)));
        b.put("panic", args -> { throw new IrijRuntimeError(str(args, 0)); });
        b.put("dbg", args -> {
            var val = arg(args, 0);
            System.err.println("[dbg] " + show(val));
            return val;
        });
        b.put("not", args -> !isTruthy(arg(args, 0)));
        b.put("equals?", args -> Objects.equals(arg(args, 0), arg(args, 1)));

        // Boolean constants (Irij has no boolean keywords — true/false are identifiers)
        b.put("true", args -> true);
        b.put("false", args -> false);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // std.math
    // ═══════════════════════════════════════════════════════════════════════

    private static void registerMath(Map<String, Builtin> b) {
        b.put("abs", args -> {
            var v = arg(args, 0);
            if (v instanceof Long l) return Math.abs(l);
            return Math.abs(toDouble(v));
        });
        b.put("min", args -> {
            var a = arg(args, 0); var bv = arg(args, 1);
            if (a instanceof Long la && bv instanceof Long lb) return Math.min(la, lb);
            return Math.min(toDouble(a), toDouble(bv));
        });
        b.put("max", args -> {
            var a = arg(args, 0); var bv = arg(args, 1);
            if (a instanceof Long la && bv instanceof Long lb) return Math.max(la, lb);
            return Math.max(toDouble(a), toDouble(bv));
        });
        b.put("floor", args -> (long) Math.floor(toDouble(arg(args, 0))));
        b.put("ceil", args -> (long) Math.ceil(toDouble(arg(args, 0))));
        b.put("round", args -> Math.round(toDouble(arg(args, 0))));
        b.put("sqrt", args -> Math.sqrt(toDouble(arg(args, 0))));
        b.put("pow", args -> {
            double base = toDouble(arg(args, 0));
            double exp = toDouble(arg(args, 1));
            return Math.pow(base, exp);
        });
        b.put("log", args -> Math.log(toDouble(arg(args, 0))));
        b.put("log10", args -> Math.log10(toDouble(arg(args, 0))));
        b.put("sin", args -> Math.sin(toDouble(arg(args, 0))));
        b.put("cos", args -> Math.cos(toDouble(arg(args, 0))));
        b.put("tan", args -> Math.tan(toDouble(arg(args, 0))));
        b.put("pi", args -> Math.PI);
        b.put("e", args -> Math.E);
        b.put("random", args -> Math.random());
        b.put("to-int", args -> {
            var v = arg(args, 0);
            if (v instanceof Long l) return l;
            if (v instanceof Double d) return (long) d.doubleValue();
            if (v instanceof String s) return Long.parseLong(s);
            return 0L;
        });
        b.put("to-float", args -> {
            var v = arg(args, 0);
            if (v instanceof Double d) return d;
            if (v instanceof Long l) return (double) l;
            if (v instanceof String s) return Double.parseDouble(s);
            return 0.0;
        });
        b.put("int?", args -> arg(args, 0) instanceof Long);
        b.put("float?", args -> arg(args, 0) instanceof Double);
        b.put("nan?", args -> {
            var v = arg(args, 0);
            return v instanceof Double d && Double.isNaN(d);
        });
        b.put("infinite?", args -> {
            var v = arg(args, 0);
            return v instanceof Double d && Double.isInfinite(d);
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // std.text
    // ═══════════════════════════════════════════════════════════════════════

    private static void registerText(Map<String, Builtin> b) {
        b.put("length", args -> {
            var v = arg(args, 0);
            if (v instanceof String s) return (long) s.length();
            if (v instanceof List<?> l) return (long) l.size();
            if (v instanceof Map<?, ?> m) return (long) m.size();
            return 0L;
        });
        b.put("split", args -> {
            String s = str(args, 0);
            String delim = str(args, 1);
            return new ArrayList<>(Arrays.asList(s.split(java.util.regex.Pattern.quote(delim))));
        });
        b.put("join", args -> {
            String sep = str(args, 0);
            var list = list(args, 1);
            return list.stream().map(Stdlib::show).collect(Collectors.joining(sep));
        });
        b.put("trim", args -> str(args, 0).trim());
        b.put("contains?", args -> str(args, 0).contains(str(args, 1)));
        b.put("starts-with?", args -> str(args, 0).startsWith(str(args, 1)));
        b.put("ends-with?", args -> str(args, 0).endsWith(str(args, 1)));
        b.put("to-upper", args -> str(args, 0).toUpperCase());
        b.put("to-lower", args -> str(args, 0).toLowerCase());
        b.put("replace", args -> str(args, 0).replace(str(args, 1), str(args, 2)));
        b.put("substring", args -> {
            String s = str(args, 0);
            int start = (int) toLong(arg(args, 1));
            if (args.size() > 2) {
                int end = (int) toLong(arg(args, 2));
                return s.substring(start, Math.min(end, s.length()));
            }
            return s.substring(start);
        });
        b.put("chars", args -> {
            String s = str(args, 0);
            var result = new ArrayList<Object>();
            for (char c : s.toCharArray()) result.add(String.valueOf(c));
            return result;
        });
        b.put("str-repeat", args -> str(args, 0).repeat((int) toLong(arg(args, 1))));
        b.put("str?", args -> arg(args, 0) instanceof String);
        b.put("index-of", args -> (long) str(args, 0).indexOf(str(args, 1)));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // std.collection
    // ═══════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private static void registerCollection(Map<String, Builtin> b) {
        // ── Vector operations ──────────────────────────────────────────
        b.put("head", args -> {
            var l = list(args, 0);
            if (l.isEmpty()) return new Tagged("None");
            return new Tagged("Some", l.get(0));
        });
        b.put("tail", args -> {
            var l = list(args, 0);
            if (l.isEmpty()) return List.of();
            return new ArrayList<>(l.subList(1, l.size()));
        });
        b.put("last", args -> {
            var l = list(args, 0);
            if (l.isEmpty()) return new Tagged("None");
            return new Tagged("Some", l.get(l.size() - 1));
        });
        b.put("init", args -> {
            var l = list(args, 0);
            if (l.isEmpty()) return List.of();
            return new ArrayList<>(l.subList(0, l.size() - 1));
        });
        b.put("take", args -> {
            int n = (int) toLong(arg(args, 0));
            var l = list(args, 1);
            return new ArrayList<>(l.subList(0, Math.min(n, l.size())));
        });
        b.put("drop", args -> {
            int n = (int) toLong(arg(args, 0));
            var l = list(args, 1);
            return new ArrayList<>(l.subList(Math.min(n, l.size()), l.size()));
        });
        b.put("reverse", args -> {
            var l = new ArrayList<>(list(args, 0));
            Collections.reverse(l);
            return l;
        });
        b.put("sort", args -> {
            var l = new ArrayList<>(list(args, 0));
            l.sort((a, av) -> compare(a, av));
            return l;
        });
        b.put("sort-by", args -> {
            // sort-by is handled specially in the interpreter (needs lambda call)
            throw new IrijRuntimeError("sort-by requires lambda — use as: sort-by (x -> x.key) list");
        });
        b.put("concat", args -> {
            var result = new ArrayList<>();
            for (var a : args) {
                if (a instanceof List<?> l) result.addAll(l);
                else result.add(a);
            }
            return result;
        });
        b.put("flatten", args -> {
            var result = new ArrayList<>();
            flatten(list(args, 0), result);
            return result;
        });
        b.put("zip", args -> {
            var a = list(args, 0);
            var blist = list(args, 1);
            int len = Math.min(a.size(), blist.size());
            var result = new ArrayList<Object>();
            for (int i = 0; i < len; i++) {
                result.add(List.of(a.get(i), blist.get(i)));
            }
            return result;
        });
        b.put("enumerate", args -> {
            var l = list(args, 0);
            var result = new ArrayList<Object>();
            for (int i = 0; i < l.size(); i++) {
                result.add(List.of((long) i, l.get(i)));
            }
            return result;
        });
        b.put("distinct", args -> {
            var seen = new LinkedHashSet<>(list(args, 0));
            return new ArrayList<>(seen);
        });
        b.put("append", args -> {
            var l = new ArrayList<>(list(args, 0));
            l.add(arg(args, 1));
            return l;
        });
        b.put("prepend", args -> {
            var l = new ArrayList<>();
            l.add(arg(args, 0));
            l.addAll(list(args, 1));
            return l;
        });
        b.put("nth", args -> {
            int i = (int) toLong(arg(args, 0));
            var l = list(args, 1);
            if (i < 0 || i >= l.size()) return new Tagged("None");
            return new Tagged("Some", l.get(i));
        });
        b.put("list?", args -> arg(args, 0) instanceof List);
        b.put("empty?", args -> {
            var v = arg(args, 0);
            if (v instanceof List<?> l) return l.isEmpty();
            if (v instanceof Map<?, ?> m) return m.isEmpty();
            if (v instanceof String s) return s.isEmpty();
            return v == UNIT;
        });
        b.put("size", args -> {
            var v = arg(args, 0);
            if (v instanceof List<?> l) return (long) l.size();
            if (v instanceof Map<?, ?> m) return (long) m.size();
            if (v instanceof String s) return (long) s.length();
            return 0L;
        });
        b.put("range", args -> {
            long start = toLong(arg(args, 0));
            long end = toLong(arg(args, 1));
            var result = new ArrayList<Object>();
            if (start <= end) {
                for (long i = start; i <= end; i++) result.add(i);
            } else {
                for (long i = start; i >= end; i--) result.add(i);
            }
            return result;
        });

        // ── Map operations ─────────────────────────────────────────────
        b.put("get", args -> {
            var key = str(args, 0);
            var map = map(args, 1);
            var val = map.get(key);
            if (val == null) return new Tagged("None");
            return new Tagged("Some", val);
        });
        b.put("put", args -> {
            var key = str(args, 0);
            var val = arg(args, 1);
            var map = new LinkedHashMap<>(map(args, 2));
            map.put(key, val);
            return map;
        });
        b.put("remove", args -> {
            var key = str(args, 0);
            var map = new LinkedHashMap<>(map(args, 1));
            map.remove(key);
            return map;
        });
        b.put("keys", args -> {
            var map = map(args, 0);
            return new ArrayList<>(map.keySet());
        });
        b.put("values", args -> {
            var map = map(args, 0);
            return new ArrayList<>(map.values());
        });
        b.put("merge", args -> {
            var a = new LinkedHashMap<>(map(args, 0));
            a.putAll(map(args, 1));
            return a;
        });
        b.put("has-key?", args -> {
            var key = str(args, 0);
            var map = map(args, 1);
            return map.containsKey(key);
        });
        b.put("map?", args -> arg(args, 0) instanceof Map);

        // ── Set operations ─────────────────────────────────────────────
        b.put("to-set", args -> new LinkedHashSet<>(list(args, 0)));
        b.put("to-list", args -> {
            var v = arg(args, 0);
            if (v instanceof Set<?> s) return new ArrayList<>(s);
            if (v instanceof List<?> l) return new ArrayList<>(l);
            if (v instanceof String s) {
                var result = new ArrayList<Object>();
                for (char c : s.toCharArray()) result.add(String.valueOf(c));
                return result;
            }
            return List.of(v);
        });
        b.put("union", args -> {
            var a = new LinkedHashSet<>(asCollection(arg(args, 0)));
            a.addAll(asCollection(arg(args, 1)));
            return a;
        });
        b.put("intersection", args -> {
            var a = new LinkedHashSet<>(asCollection(arg(args, 0)));
            a.retainAll(asCollection(arg(args, 1)));
            return a;
        });
        b.put("difference", args -> {
            var a = new LinkedHashSet<>(asCollection(arg(args, 0)));
            a.removeAll(asCollection(arg(args, 1)));
            return a;
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // std.io
    // ═══════════════════════════════════════════════════════════════════════

    private static void registerIo(Map<String, Builtin> b) {
        b.put("println", args -> {
            System.out.println(args.isEmpty() ? "" : show(arg(args, 0)));
            return UNIT;
        });
        b.put("print-str", args -> {
            System.out.print(show(arg(args, 0)));
            return UNIT;
        });
        b.put("read-line", args -> {
            var scanner = new Scanner(System.in);
            return scanner.hasNextLine() ? scanner.nextLine() : "";
        });
        b.put("read-file", args -> {
            try {
                return Files.readString(Path.of(str(args, 0)));
            } catch (IOException e) {
                return new Tagged("Err", e.getMessage());
            }
        });
        b.put("write-file", args -> {
            try {
                Files.writeString(Path.of(str(args, 0)), str(args, 1));
                return new Tagged("Ok", UNIT);
            } catch (IOException e) {
                return new Tagged("Err", e.getMessage());
            }
        });
        b.put("file-exists?", args -> Files.exists(Path.of(str(args, 0))));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Type Constructors
    // ═══════════════════════════════════════════════════════════════════════

    private static void registerConstructors(Map<String, Builtin> b) {
        // Result constructors
        b.put("Ok", args -> new Tagged("Ok", args.isEmpty() ? UNIT : arg(args, 0)));
        b.put("Err", args -> new Tagged("Err", args.isEmpty() ? UNIT : arg(args, 0)));

        // Option constructors
        b.put("Some", args -> new Tagged("Some", arg(args, 0)));
        b.put("None", args -> new Tagged("None"));

        // Result/Option operations
        b.put("ok?", args -> arg(args, 0) instanceof Tagged t && t.is("Ok"));
        b.put("err?", args -> arg(args, 0) instanceof Tagged t && t.is("Err"));
        b.put("some?", args -> arg(args, 0) instanceof Tagged t && t.is("Some"));
        b.put("none?", args -> arg(args, 0) instanceof Tagged t && t.is("None"));

        b.put("unwrap", args -> {
            var v = arg(args, 0);
            if (v instanceof Tagged t) {
                if (t.is("Ok") || t.is("Some")) return t.field(0);
                throw new IrijRuntimeError("unwrap called on " + t.tag());
            }
            return v;
        });
        b.put("unwrap-or", args -> {
            var v = arg(args, 0);
            var def = arg(args, 1);
            if (v instanceof Tagged t) {
                if (t.is("Ok") || t.is("Some")) return t.field(0);
                return def;
            }
            return v;
        });

        // Tuple constructor
        b.put("pair", args -> List.of(arg(args, 0), arg(args, 1)));
        b.put("fst", args -> {
            var l = list(args, 0);
            return l.isEmpty() ? UNIT : l.get(0);
        });
        b.put("snd", args -> {
            var l = list(args, 0);
            return l.size() < 2 ? UNIT : l.get(1);
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // J-inspired sequence operators
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Evaluate a J-inspired sequence operator applied to a vector.
     *
     * @param op   The operator token text ("/+", "/*", "/#", "/&", "/|")
     * @param data The vector operand
     * @return The result
     */
    public static Object evalSeqOp(String op, Object data) {
        var list = toList(data);
        return switch (op) {
            case "/+" -> reduceSum(list);
            case "/*" -> reduceProduct(list);
            case "/#" -> (long) list.size();
            case "/&" -> list.stream().allMatch(Stdlib::isTruthy);
            case "/|" -> list.stream().anyMatch(Stdlib::isTruthy);
            default -> throw new IrijRuntimeError("Unknown sequence operator: " + op);
        };
    }

    private static Object reduceSum(List<Object> list) {
        if (list.isEmpty()) return 0L;
        boolean hasDouble = list.stream().anyMatch(v -> v instanceof Double);
        if (hasDouble) {
            return list.stream().mapToDouble(Stdlib::toDouble).sum();
        }
        return list.stream().mapToLong(Stdlib::toLong).sum();
    }

    private static Object reduceProduct(List<Object> list) {
        if (list.isEmpty()) return 1L;
        boolean hasDouble = list.stream().anyMatch(v -> v instanceof Double);
        if (hasDouble) {
            return list.stream().mapToDouble(Stdlib::toDouble).reduce(1.0, (a, b) -> a * b);
        }
        return list.stream().mapToLong(Stdlib::toLong).reduce(1L, (a, b) -> a * b);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // show — convert any Irij value to its display string
    // ═══════════════════════════════════════════════════════════════════════

    public static String show(Object val) {
        if (val == null || val == UNIT) return "()";
        if (val instanceof String s) return s;
        if (val instanceof Long l) return l.toString();
        if (val instanceof Double d) {
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.format("%.1f", d);
            }
            return d.toString();
        }
        if (val instanceof Boolean b) return b.toString();
        if (val instanceof Tagged t) return t.toString();
        if (val instanceof List<?> list) {
            var sb = new StringBuilder("#[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(showQuoted(list.get(i)));
            }
            sb.append(']');
            return sb.toString();
        }
        if (val instanceof Map<?, ?> map) {
            var sb = new StringBuilder("{");
            boolean first = true;
            for (var entry : map.entrySet()) {
                if (!first) sb.append(' ');
                sb.append(entry.getKey()).append(": ").append(showQuoted(entry.getValue()));
                first = false;
            }
            sb.append('}');
            return sb.toString();
        }
        if (val instanceof Set<?> set) {
            var sb = new StringBuilder("#{");
            boolean first = true;
            for (var item : set) {
                if (!first) sb.append(' ');
                sb.append(showQuoted(item));
                first = false;
            }
            sb.append('}');
            return sb.toString();
        }
        return val.toString();
    }

    /** Show with quotes around strings (for use inside collections) */
    private static String showQuoted(Object val) {
        if (val instanceof String s) return "\"" + s + "\"";
        return show(val);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    static Object arg(List<Object> args, int i) {
        if (i >= args.size()) return UNIT;
        return args.get(i);
    }

    static String str(List<Object> args, int i) {
        var v = arg(args, i);
        if (v instanceof String s) return s;
        return show(v);
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(List<Object> args, int i) {
        return toList(arg(args, i));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(List<Object> args, int i) {
        var v = arg(args, i);
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Object> toList(Object v) {
        if (v instanceof List<?> l) return (List<Object>) l;
        if (v instanceof Set<?> s) return new ArrayList<>(s);
        return List.of();
    }

    static long toLong(Object v) {
        if (v instanceof Long l) return l;
        if (v instanceof Double d) return (long) d.doubleValue();
        if (v instanceof Boolean b) return b ? 1L : 0L;
        if (v instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; }
        }
        return 0L;
    }

    static double toDouble(Object v) {
        if (v instanceof Double d) return d;
        if (v instanceof Long l) return l.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0.0; }
        }
        return 0.0;
    }

    static boolean isTruthy(Object val) {
        if (val == null || val == UNIT) return false;
        if (val instanceof Boolean b) return b;
        if (val instanceof Long l) return l != 0;
        if (val instanceof Double d) return d != 0;
        if (val instanceof String s) return !s.isEmpty();
        if (val instanceof Tagged t) return !t.is("None") && !t.is("Err");
        if (val instanceof List<?> l) return !l.isEmpty();
        if (val instanceof Map<?, ?> m) return !m.isEmpty();
        return true;
    }

    static String typeOf(Object val) {
        if (val == null || val == UNIT) return "Unit";
        if (val instanceof Long) return "Int";
        if (val instanceof Double) return "Float";
        if (val instanceof String) return "Str";
        if (val instanceof Boolean) return "Bool";
        if (val instanceof List) return "Vec";
        if (val instanceof Map) return "Map";
        if (val instanceof Set) return "Set";
        if (val instanceof Tagged t) return t.tag();
        return "Unknown";
    }

    @SuppressWarnings("unchecked")
    private static int compare(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) return Long.compare(la, lb);
        if (a instanceof Number && b instanceof Number) return Double.compare(toDouble(a), toDouble(b));
        if (a instanceof String sa && b instanceof String sb) return sa.compareTo(sb);
        return show(a).compareTo(show(b));
    }

    private static void flatten(List<Object> list, List<Object> out) {
        for (var item : list) {
            if (item instanceof List<?> nested) {
                flatten(toList(item), out);
            } else {
                out.add(item);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> asCollection(Object v) {
        if (v instanceof Set<?> s) return (Set<Object>) s;
        if (v instanceof List<?> l) return (List<Object>) l;
        return List.of(v);
    }
}
