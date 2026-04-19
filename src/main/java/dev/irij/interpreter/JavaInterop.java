package dev.irij.interpreter;

import dev.irij.interpreter.Values.BuiltinFn;
import dev.irij.interpreter.Values.IrijMap;
import dev.irij.interpreter.Values.IrijVector;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Clojure-style Java interop.
 *
 * <ul>
 *   <li>{@code System/getenv} — static method, static field, or constructor (member = "new")</li>
 *   <li>{@code java.time.Instant/now} — fully-qualified class/member</li>
 *   <li>{@code obj.methodName args} — instance method via dot-access fallthrough</li>
 * </ul>
 *
 * All JVM calls are tagged with the {@code "JVM"} effect in their BuiltinFn.
 */
public final class JavaInterop {

    /** Common {@code java.lang.*} classes auto-imported when unqualified. */
    private static final Set<String> LANG_AUTO = Set.of(
            "System", "String", "Integer", "Long", "Double", "Float", "Boolean",
            "Math", "Thread", "Object", "Character", "Byte", "Short", "Number",
            "StringBuilder", "StringBuffer", "Runtime", "Class", "Iterable",
            "Throwable", "Exception", "RuntimeException", "Error"
    );

    private static final ConcurrentHashMap<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();

    private JavaInterop() {}

    // ── Class resolution ────────────────────────────────────────────────

    public static Class<?> resolveClass(String name) {
        var cached = CLASS_CACHE.get(name);
        if (cached != null) return cached;
        try {
            var cls = Class.forName(name);
            CLASS_CACHE.put(name, cls);
            return cls;
        } catch (ClassNotFoundException e) {
            if (!name.contains(".") && LANG_AUTO.contains(name)) {
                try {
                    var cls = Class.forName("java.lang." + name);
                    CLASS_CACHE.put(name, cls);
                    return cls;
                } catch (ClassNotFoundException ignored) {}
            }
            throw new IrijRuntimeError("Unknown Java class: " + name);
        }
    }

    // ── JAVA_REF entry point: "Class/member" ────────────────────────────

    public static Object resolveStaticRef(String ref) {
        int slash = ref.indexOf('/');
        var className = ref.substring(0, slash);
        var member = ref.substring(slash + 1);
        var cls = resolveClass(className);

        // Class/new → constructor callable
        if (member.equals("new")) {
            return new BuiltinFn(ref, -1, List.of("JVM"), args -> invokeCtor(cls, normalizeArgs(args)));
        }

        // Static field lookup first
        for (var f : cls.getFields()) {
            if (f.getName().equals(member) && Modifier.isStatic(f.getModifiers())) {
                try {
                    return javaToIrij(f.get(null));
                } catch (IllegalAccessException e) {
                    throw new IrijRuntimeError("Cannot access static field: " + ref);
                }
            }
        }

        // Otherwise: static method (possibly overloaded). Variadic BuiltinFn
        // resolves at call time based on arg count + coercion.
        return new BuiltinFn(ref, -1, List.of("JVM"),
                args -> invokeStatic(cls, member, normalizeArgs(args)));
    }

    // ── Instance ref (from dot-access fallthrough) ──────────────────────

    public static Object resolveInstanceRef(Object recv, String member) {
        var cls = recv.getClass();
        // Instance field?
        for (var f : cls.getFields()) {
            if (f.getName().equals(member) && !Modifier.isStatic(f.getModifiers())) {
                try {
                    return javaToIrij(f.get(recv));
                } catch (IllegalAccessException e) {
                    throw new IrijRuntimeError("Cannot access field: " + cls.getName() + "." + member);
                }
            }
        }
        // Otherwise: bound instance method callable.
        return new BuiltinFn(cls.getSimpleName() + "." + member, -1, List.of("JVM"),
                args -> invokeInstance(recv, member, normalizeArgs(args)));
    }

    // ── Invocation (overload-resolved at call time) ─────────────────────

    private static Object invokeStatic(Class<?> cls, String name, List<Object> args) {
        var candidates = new ArrayList<Method>();
        for (var m : cls.getMethods()) {
            if (m.getName().equals(name)
                    && Modifier.isStatic(m.getModifiers())
                    && m.getParameterCount() == args.size()) {
                candidates.add(m);
            }
        }
        return dispatchMethod(candidates, null, cls, name, args);
    }

    private static Object invokeInstance(Object recv, String name, List<Object> args) {
        var cls = recv.getClass();
        var candidates = new ArrayList<Method>();
        for (var m : cls.getMethods()) {
            if (m.getName().equals(name)
                    && !Modifier.isStatic(m.getModifiers())
                    && m.getParameterCount() == args.size()) {
                candidates.add(m);
            }
        }
        return dispatchMethod(candidates, recv, cls, name, args);
    }

    private static Object dispatchMethod(List<Method> candidates, Object recv,
                                         Class<?> cls, String name, List<Object> args) {
        if (candidates.isEmpty()) {
            throw new IrijRuntimeError("No method matching " + cls.getName() + "/" + name
                    + " with " + args.size() + " arg(s)");
        }
        // Rank candidates by coercion specificity (lower = closer match).
        candidates.sort(java.util.Comparator.comparingInt(m -> overloadScore(m.getParameterTypes(), args)));
        Throwable last = null;
        for (var m : candidates) {
            Object[] coerced;
            try {
                coerced = coerceArgs(m.getParameterTypes(), args);
            } catch (CoercionError e) {
                last = e;
                continue;
            }
            try {
                return javaToIrij(m.invoke(recv, coerced));
            } catch (InvocationTargetException e) {
                var cause = e.getCause();
                throw new IrijRuntimeError("Java call " + cls.getName() + "/" + name
                        + " threw: " + (cause != null ? cause.toString() : e.toString()));
            } catch (IllegalAccessException | IllegalArgumentException e) {
                last = e;
            }
        }
        throw new IrijRuntimeError("No matching overload for " + cls.getName() + "/" + name
                + " with args " + describeArgs(args)
                + (last != null ? " (" + last.getMessage() + ")" : ""));
    }

    /** Overload preference: 0 = perfect, higher = worse. Large penalty = incompatible. */
    static int overloadScore(Class<?>[] params, List<Object> args) {
        int total = 0;
        for (int i = 0; i < params.length; i++) total += paramScore(params[i], args.get(i));
        return total;
    }

    private static int paramScore(Class<?> target, Object v) {
        if (v == Values.UNIT || v == null) return target.isPrimitive() ? 1000 : 0;
        if (!target.isPrimitive() && target.isInstance(v)) return 0;
        if (v instanceof Long) {
            if (target == long.class    || target == Long.class)    return 0;
            if (target == int.class     || target == Integer.class) return 1;
            if (target == short.class   || target == Short.class)   return 2;
            if (target == byte.class    || target == Byte.class)    return 2;
            if (target == double.class  || target == Double.class)  return 5;
            if (target == float.class   || target == Float.class)   return 6;
        }
        if (v instanceof Double) {
            if (target == double.class || target == Double.class) return 0;
            if (target == float.class  || target == Float.class)  return 1;
        }
        if (v instanceof String) {
            if (target == String.class) return 0;
            if (target == CharSequence.class) return 1;
            if (target == Object.class) return 3;
            if (target == char.class && ((String) v).length() == 1) return 2;
        }
        if (v instanceof Boolean) {
            if (target == boolean.class || target == Boolean.class) return 0;
            if (target == Object.class) return 3;
        }
        if (v instanceof IrijVector) {
            if (List.class.isAssignableFrom(target)) return 0;
            if (target.isArray()) return 1;
        }
        if (v instanceof IrijMap && Map.class.isAssignableFrom(target)) return 0;
        if (target == Object.class) return 3;
        return 1000;
    }

    private static Object invokeCtor(Class<?> cls, List<Object> args) {
        var candidates = new ArrayList<Constructor<?>>();
        for (var c : cls.getConstructors()) {
            if (c.getParameterCount() == args.size()) candidates.add(c);
        }
        if (candidates.isEmpty()) {
            throw new IrijRuntimeError("No constructor for " + cls.getName()
                    + " with " + args.size() + " arg(s)");
        }
        Throwable last = null;
        for (var c : candidates) {
            Object[] coerced;
            try {
                coerced = coerceArgs(c.getParameterTypes(), args);
            } catch (CoercionError e) {
                last = e;
                continue;
            }
            try {
                return javaToIrij(c.newInstance(coerced));
            } catch (InvocationTargetException e) {
                var cause = e.getCause();
                throw new IrijRuntimeError("Constructor " + cls.getName()
                        + " threw: " + (cause != null ? cause.toString() : e.toString()));
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IrijRuntimeError("No matching constructor for " + cls.getName()
                + " with args " + describeArgs(args)
                + (last != null ? " (" + last.getMessage() + ")" : ""));
    }

    // ── Coercion ────────────────────────────────────────────────────────

    private static final class CoercionError extends RuntimeException {
        CoercionError(String m) { super(m); }
    }

    static Object[] coerceArgs(Class<?>[] params, List<Object> args) {
        var out = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            out[i] = irijToJava(args.get(i), params[i]);
        }
        return out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object irijToJava(Object v, Class<?> target) {
        if (v == Values.UNIT || v == null) {
            if (target.isPrimitive()) throw new CoercionError("null -> primitive " + target);
            return null;
        }
        // Exact/assignable
        if (!target.isPrimitive() && target.isInstance(v)) return v;

        if (target == String.class) {
            if (v instanceof String s) return s;
            throw new CoercionError("not a String: " + v);
        }
        if (target == long.class || target == Long.class) {
            if (v instanceof Long l) return l;
            if (v instanceof Integer i) return (long) i;
            throw new CoercionError("not a long: " + v);
        }
        if (target == int.class || target == Integer.class) {
            if (v instanceof Long l) return l.intValue();
            if (v instanceof Integer i) return i;
            throw new CoercionError("not an int: " + v);
        }
        if (target == short.class || target == Short.class) {
            if (v instanceof Long l) return l.shortValue();
            throw new CoercionError("not a short: " + v);
        }
        if (target == byte.class || target == Byte.class) {
            if (v instanceof Long l) return l.byteValue();
            throw new CoercionError("not a byte: " + v);
        }
        if (target == double.class || target == Double.class) {
            if (v instanceof Double d) return d;
            if (v instanceof Long l) return (double) l;
            throw new CoercionError("not a double: " + v);
        }
        if (target == float.class || target == Float.class) {
            if (v instanceof Double d) return d.floatValue();
            if (v instanceof Long l) return (float) l;
            throw new CoercionError("not a float: " + v);
        }
        if (target == boolean.class || target == Boolean.class) {
            if (v instanceof Boolean b) return b;
            throw new CoercionError("not a boolean: " + v);
        }
        if (target == char.class || target == Character.class) {
            if (v instanceof String s && s.length() == 1) return s.charAt(0);
            if (v instanceof Character c) return c;
            throw new CoercionError("not a char: " + v);
        }
        if (target == byte[].class) {
            if (v instanceof byte[] b) return b;
            throw new CoercionError("not a byte[]: " + v);
        }
        if (target == Object.class) {
            return irijToJavaAny(v);
        }
        if (List.class.isAssignableFrom(target)) {
            if (v instanceof IrijVector iv) {
                var out = new ArrayList<Object>(iv.elements().size());
                for (var e : iv.elements()) out.add(irijToJavaAny(e));
                return out;
            }
            throw new CoercionError("not a vector: " + v);
        }
        if (Map.class.isAssignableFrom(target)) {
            if (v instanceof IrijMap im) {
                var out = new LinkedHashMap<String, Object>();
                for (var e : im.entries().entrySet()) out.put(e.getKey(), irijToJavaAny(e.getValue()));
                return out;
            }
            throw new CoercionError("not a map: " + v);
        }
        if (target.isArray()) {
            if (v instanceof IrijVector iv) {
                var comp = target.getComponentType();
                var arr = java.lang.reflect.Array.newInstance(comp, iv.elements().size());
                for (int i = 0; i < iv.elements().size(); i++) {
                    java.lang.reflect.Array.set(arr, i, irijToJava(iv.elements().get(i), comp));
                }
                return arr;
            }
            throw new CoercionError("not an array-compatible value: " + v);
        }
        throw new CoercionError("cannot coerce " + v.getClass().getSimpleName() + " -> " + target.getName());
    }

    /** Loose Irij→Java conversion when target is Object (no specific type constraint). */
    static Object irijToJavaAny(Object v) {
        if (v == Values.UNIT || v == null) return null;
        if (v instanceof IrijVector iv) {
            var out = new ArrayList<Object>(iv.elements().size());
            for (var e : iv.elements()) out.add(irijToJavaAny(e));
            return out;
        }
        if (v instanceof IrijMap im) {
            var out = new LinkedHashMap<String, Object>();
            for (var e : im.entries().entrySet()) out.put(e.getKey(), irijToJavaAny(e.getValue()));
            return out;
        }
        return v;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object javaToIrij(Object v) {
        if (v == null) return Values.UNIT;
        if (v instanceof String || v instanceof Boolean) return v;
        if (v instanceof Integer i) return (long) i;
        if (v instanceof Long l)    return l;
        if (v instanceof Short s)   return (long) s;
        if (v instanceof Byte b)    return (long) b;
        if (v instanceof Character c) return String.valueOf(c);
        if (v instanceof Float f)   return (double) f;
        if (v instanceof Double d)  return d;
        if (v.getClass().isArray()) {
            int n = java.lang.reflect.Array.getLength(v);
            var list = new ArrayList<Object>(n);
            for (int i = 0; i < n; i++) list.add(javaToIrij(java.lang.reflect.Array.get(v, i)));
            return new IrijVector(list);
        }
        if (v instanceof List<?> lst) {
            var out = new ArrayList<Object>(lst.size());
            for (var e : lst) out.add(javaToIrij(e));
            return new IrijVector(out);
        }
        if (v instanceof Map<?,?> m) {
            var out = new LinkedHashMap<String, Object>();
            for (var e : ((Map<Object,Object>) m).entrySet()) {
                out.put(String.valueOf(e.getKey()), javaToIrij(e.getValue()));
            }
            return new IrijMap(out);
        }
        // Opaque Java object — dot-access/instance methods still work via reflection.
        return v;
    }

    /** Irij convention: {@code f ()} is a zero-arg call. A single UNIT argument is stripped. */
    private static List<Object> normalizeArgs(List<Object> args) {
        if (args.size() == 1 && args.get(0) == Values.UNIT) return List.of();
        return args;
    }

    private static String describeArgs(List<Object> args) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(' ');
            var a = args.get(i);
            sb.append(a == null ? "nil" : a.getClass().getSimpleName());
        }
        sb.append(']');
        return sb.toString();
    }
}
