package dev.irij.compiler;

import java.util.HashMap;
import java.util.Map;

/**
 * Internal-name lookup for runtime-support statics referenced from
 * emitted bytecode. RuntimeSupport was split into domain classes
 * (RtOps, RtCollections, …); emit sites resolve each method/field's
 * owning class here instead of hard-coding one owner, so the split
 * classes themselves stay the single source of truth.
 *
 * Built once by reflection at class load; fails fast on a name that
 * is missing or declared by two classes (routing drift guard).
 */
final class RtOwners {

    private static final Class<?>[] CLASSES = {
            RuntimeSupport.class, RtOps.class, RtMath.class, RtStrings.class,
            RtCollections.class, RtEffects.class, RtConcurrency.class,
            RtInterop.class, RtIo.class,
    };

    private static final Map<String, String> OWNER = new HashMap<>();

    static {
        for (Class<?> c : CLASSES) {
            String internal = c.getName().replace('.', '/');
            for (var m : c.getDeclaredMethods()) {
                if (m.isSynthetic()) continue;
                put(m.getName(), internal);
            }
            for (var f : c.getDeclaredFields()) {
                if (f.isSynthetic()) continue;
                put(f.getName(), internal);
            }
        }
    }

    private static void put(String name, String internal) {
        String prev = OWNER.putIfAbsent(name, internal);
        if (prev != null && !prev.equals(internal)) {
            throw new IllegalStateException(
                    "ambiguous runtime symbol '" + name + "': " + prev + " vs " + internal);
        }
    }

    /** Internal name (e.g. {@code dev/irij/compiler/RtOps}) of the class
     *  declaring the runtime static {@code name}. */
    static String of(String name) {
        String o = OWNER.get(name);
        if (o == null) {
            throw new IllegalStateException("no runtime owner for symbol: " + name);
        }
        return o;
    }

    private RtOwners() {}
}
