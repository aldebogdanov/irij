package dev.irij.compiler;

/** Split from RuntimeSupport (PR2 2026-07): RtInterop domain. */
public final class RtInterop {

    private RtInterop() {}


    /** Resolve a Java static ref like "System/getenv" → BuiltinFn. */
    public static Object javaStaticRef(String ref) {
        return dev.irij.runtime.JavaInterop.resolveStaticRef(ref);
    }


    /**
     * `.field` reaching run time: a field of a map or a record, or — on a
     * scope handle — its fork method. `.` is Irij's own access; a Java
     * object's members are reached with `#` (spec §9.1), so `.` on one is
     * an error that names the `#` spelling.
     */
    public static Object javaInstanceRef(Object recv, String member) {
        if (recv == null || recv == dev.irij.runtime.Values.UNIT) {
            throw new dev.irij.IrijRuntimeError("Cannot access ." + member + " on ()");
        }
        if (recv instanceof dev.irij.runtime.Values.IrijMap m) {
            Object v = m.entries().get(member);
            return v != null ? v : dev.irij.runtime.Values.UNIT;
        }
        if (recv instanceof dev.irij.runtime.Values.Tagged t
                && t.namedFields() != null) {
            Object v = t.namedFields().get(member);
            if (v != null) return v;
            throw new dev.irij.IrijRuntimeError(
                    "No field '" + member + "' on " + t.tag());
        }
        if (recv.getClass().getName().startsWith("dev.irij.")) {
            return dev.irij.runtime.JavaInterop.resolveInstanceRef(recv, member);
        }
        throw new dev.irij.IrijRuntimeError("`." + member + "` on a "
                + recv.getClass().getName() + ": `.` reads an Irij field; reach a Java"
                + " object's method or field with `#" + member + "`");
    }

    /**
     * `x#member` — a method (bound, for the enclosing App to call) or a
     * field of the Java object {@code x}. Always the JVM effect: the
     * compile-time checker requires it at the site; this is the runtime
     * backstop for a lambda written in one row and called in another.
     */
    public static Object javaMember(Object recv, String member) {
        checkJavaReceiver(recv, member);
        RtEffects.checkPerformEffect("JVM", "java-interop:#" + member);
        return dev.irij.runtime.JavaInterop.resolveInstanceRef(recv, member);
    }

    /**
     * `#member args…` with no receiver — the method reference
     * {@code x -> x#member args…}, its arguments already evaluated. With no
     * arguments a field reads its value and a method is called with none.
     */
    public static Object javaMemberRef(String member, java.util.List<Object> args) {
        return new dev.irij.runtime.Values.BuiltinFn("#" + member, 1, java.util.List.of("JVM"), a -> {
            Object recv = a.get(0);
            checkJavaReceiver(recv, member);
            RtEffects.checkPerformEffect("JVM", "java-interop:#" + member);
            return dev.irij.runtime.JavaInterop.invokeMember(recv, member, args);
        });
    }

    /**
     * An Irij value whose class is the runtime's own (a map, vector,
     * record, rational…) isn't a Java object to the program, so `#` on it
     * is an error rather than reflection into Irij's internals.
     */
    private static void checkJavaReceiver(Object recv, String member) {
        if (recv == null || recv == dev.irij.runtime.Values.UNIT) {
            throw new dev.irij.IrijRuntimeError("Cannot call #" + member + " on ()");
        }
        if (recv.getClass().getName().startsWith("dev.irij.")) {
            throw new dev.irij.IrijRuntimeError("`#" + member + "` reaches a Java object's member, "
                    + "but this is an Irij " + RuntimeSupport.typeTag(recv)
                    + ": use a builtin, or `." + member + "` for a record or map field");
        }
    }
}
