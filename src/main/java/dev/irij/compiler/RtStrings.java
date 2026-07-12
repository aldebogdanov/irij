package dev.irij.compiler;

/** Split from RuntimeSupport (PR2 2026-07): RtStrings domain. */
public final class RtStrings {

    private RtStrings() {}


    // ── Heavy-hitter builtins ported from Builtins.java (Phase R3) ──────
    //
    // These exist so `irij build --mode=bytecode-sm` can compile real
    // programs without falling through to an interpreter-installed
    // BuiltinFn. Semantics match Interpreter exactly (same coercions,
    // same error messages). Names are Java-friendly; the emitter maps
    // Irij names like `replace` / `index-of` / `contains?` to these.

    public static String asStr(Object v, String op) {
        if (v instanceof String s) return s;
        throw new dev.irij.IrijRuntimeError(
                op + " expects a String, got " + RuntimeSupport.typeTag(v));
    }


    // ── Strings ────────────────────────────────────────────────────────

    public static Object replace(Object str, Object from, Object to) {
        return asStr(str, "replace").replace(
                asStr(from, "replace"), asStr(to, "replace"));
    }


    public static Object substring(Object str, Object startArg, Object endArg) {
        String s = asStr(str, "substring");
        int start = (int) RtCollections.asLongArg(startArg, "substring");
        int end = (int) RtCollections.asLongArg(endArg, "substring");
        if (start < 0 || end > s.length() || start > end) {
            throw new dev.irij.IrijRuntimeError(
                    "substring: index out of bounds (start=" + start
                            + ", end=" + end + ", length=" + s.length() + ")");
        }
        return s.substring(start, end);
    }


    public static Object split(Object str, Object sep) {
        String s = asStr(str, "split");
        String sp = asStr(sep, "split");
        java.util.List<Object> parts = new java.util.ArrayList<>();
        if (sp.isEmpty()) {
            for (int i = 0; i < s.length(); i++) parts.add(String.valueOf(s.charAt(i)));
        } else {
            for (String p : s.split(java.util.regex.Pattern.quote(sp), -1)) parts.add(p);
        }
        return new dev.irij.runtime.Values.IrijVector(parts);
    }


    public static Object join(Object sep, Object coll) {
        String sp = asStr(sep, "join");
        java.util.List<Object> list = RtCollections.asListAny(coll);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sp);
            sb.append(dev.irij.runtime.Values.toIrijString(list.get(i)));
        }
        return sb.toString();
    }


    public static Object trimStr(Object v) {
        return asStr(v, "trim").strip();
    }


    public static Object upperCase(Object v) {
        return asStr(v, "upper-case").toUpperCase();
    }


    public static Object lowerCase(Object v) {
        return asStr(v, "lower-case").toLowerCase();
    }


    public static Object startsWithP(Object str, Object prefix) {
        return asStr(str, "starts-with?").startsWith(asStr(prefix, "starts-with?"));
    }


    public static Object endsWithP(Object str, Object suffix) {
        return asStr(str, "ends-with?").endsWith(asStr(suffix, "ends-with?"));
    }


    public static Object indexOf(Object str, Object sub) {
        return (long) asStr(str, "index-of").indexOf(asStr(sub, "index-of"));
    }


    public static Object urlEncode(Object s) {
        return java.net.URLEncoder.encode(asStr(s, "url-encode"),
                java.nio.charset.StandardCharsets.UTF_8);
    }


    public static Object urlDecode(Object s) {
        return java.net.URLDecoder.decode(asStr(s, "url-decode"),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    public static Object charAt(Object strArg, Object idxArg) {
        String s = asStr(strArg, "char-at");
        int i = (int) RtCollections.asLongArg(idxArg, "char-at");
        if (i < 0 || i >= s.length()) {
            throw new dev.irij.IrijRuntimeError(
                    "char-at: index " + i + " out of bounds (length " + s.length() + ")");
        }
        return String.valueOf(s.charAt(i));
    }

    public static Object charCode(Object strArg) {
        String s = asStr(strArg, "char-code");
        if (s.isEmpty()) {
            throw new dev.irij.IrijRuntimeError("char-code: empty string");
        }
        return (long) s.codePointAt(0);
    }

    public static Object fromCharCode(Object cpArg) {
        int cp = (int) RtCollections.asLongArg(cpArg, "from-char-code");
        return String.valueOf(Character.toChars(cp));
    }
}
