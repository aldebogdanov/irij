package dev.irij.compiler;

/** Split from RuntimeSupport (PR2 2026-07): RtInterop domain. */
public final class RtInterop {

    private RtInterop() {}


    /** Resolve a Java static ref like "System/getenv" → BuiltinFn. */
    public static Object javaStaticRef(String ref) {
        return dev.irij.runtime.JavaInterop.resolveStaticRef(ref);
    }


    /**
     * Dot-access fallthrough at runtime: dispatch over compile-time unknown
     * targets. Returns handler-state/closure fields if applicable, else
     * defers to JavaInterop.resolveInstanceRef.
     */
    public static Object javaInstanceRef(Object recv, String member) {
        if (recv == null) {
            throw new IllegalArgumentException("Cannot access ." + member + " on null");
        }
        // Match Interpreter.evalDotAccess: check Irij record-shaped
        // values first so `map.key` and `tagged.field` work in
        // bytecode mode without falling through to JavaInterop.
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
        return dev.irij.runtime.JavaInterop.resolveInstanceRef(recv, member);
    }
}
