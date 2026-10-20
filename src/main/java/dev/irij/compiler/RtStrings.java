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
        long start = RtCollections.asLongArg(startArg, "substring");
        long end = RtCollections.asLongArg(endArg, "substring");
        if (start < 0 || end > s.length() || start > end) {
            throw new dev.irij.IrijRuntimeError(
                    "substring: index out of bounds (start=" + start
                            + ", end=" + end + ", length=" + s.length() + ")");
        }
        return s.substring((int) start, (int) end);
    }


    public static Object split(Object str, Object sep) {
        String s = asStr(str, "split");
        String sp = asStr(sep, "split");
        java.util.List<Object> parts = new java.util.ArrayList<>();
        if (sp.isEmpty()) {
            // One part per code point: per UTF-16 char would cut every
            // emoji and other non-BMP character into two broken halves.
            s.codePoints().forEach(cp -> parts.add(new String(Character.toChars(cp))));
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
        return asStr(v, "upper-case").toUpperCase(java.util.Locale.ROOT);
    }


    public static Object lowerCase(Object v) {
        return asStr(v, "lower-case").toLowerCase(java.util.Locale.ROOT);
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


    // url-encode / url-decode are the form encoding of a query string or
    // form body (a space is `+`); percent-encode is RFC 3986's, for a path
    // segment or anywhere a `+` must stay a plus. All three refuse what
    // has no exact encoding (an unpaired surrogate, %-escapes that aren't
    // UTF-8) instead of substituting `?` or U+FFFD — which made different
    // strings encode, or decode, alike.

    public static Object urlEncode(Object s) {
        String str = asStr(s, "url-encode");
        try {
            return dev.irij.runtime.TextCodec.percentEncode(str, true);
        } catch (IllegalArgumentException e) {
            throw new dev.irij.IrijRuntimeError("url-encode: " + e.getMessage());
        }
    }


    public static Object urlDecode(Object s) {
        String str = asStr(s, "url-decode");
        try {
            return dev.irij.runtime.TextCodec.percentDecode(str, true);
        } catch (IllegalArgumentException e) {
            throw new dev.irij.IrijRuntimeError("url-decode: " + e.getMessage() + " in '" + str + "'");
        }
    }


    public static Object percentEncode(Object s) {
        String str = asStr(s, "percent-encode");
        try {
            return dev.irij.runtime.TextCodec.percentEncode(str, false);
        } catch (IllegalArgumentException e) {
            throw new dev.irij.IrijRuntimeError("percent-encode: " + e.getMessage());
        }
    }

    public static Object charAt(Object strArg, Object idxArg) {
        String s = asStr(strArg, "char-at");
        long i = RtCollections.asLongArg(idxArg, "char-at");
        if (i < 0 || i >= s.length()) {
            throw new dev.irij.IrijRuntimeError(
                    "char-at: index " + i + " out of bounds (length " + s.length() + ")");
        }
        return String.valueOf(s.charAt((int) i));
    }

    public static Object charCode(Object strArg) {
        String s = asStr(strArg, "char-code");
        if (s.isEmpty()) {
            throw new dev.irij.IrijRuntimeError("char-code: empty string");
        }
        return (long) s.codePointAt(0);
    }

    public static Object fromCharCode(Object cpArg) {
        long cp = RtCollections.asLongArg(cpArg, "from-char-code");
        if (!Character.isValidCodePoint((int) cp) || cp != (int) cp) {
            throw new dev.irij.IrijRuntimeError("from-char-code: " + cp + " is not a Unicode code point");
        }
        return String.valueOf(Character.toChars((int) cp));
    }
}
