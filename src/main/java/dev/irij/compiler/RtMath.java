package dev.irij.compiler;

/** Split from RuntimeSupport (PR2 2026-07): RtMath domain. */
public final class RtMath {

    private RtMath() {}


    // ── Math primitives (Phase R3) ────────────────────────────────────

    public static Object sqrt(Object x) { return Math.sqrt(RtOps.asDoubleArg(x, "sqrt")); }

    public static Object sin(Object x)  { return Math.sin(RtOps.asDoubleArg(x, "sin")); }

    public static Object cos(Object x)  { return Math.cos(RtOps.asDoubleArg(x, "cos")); }

    public static Object tan(Object x)  { return Math.tan(RtOps.asDoubleArg(x, "tan")); }

    public static Object log(Object x)  { return Math.log(RtOps.asDoubleArg(x, "log")); }

    public static Object exp(Object x)  { return Math.exp(RtOps.asDoubleArg(x, "exp")); }

    public static Object floor(Object x) { return (long) Math.floor(RtOps.asDoubleArg(x, "floor")); }

    public static Object ceil(Object x)  { return (long) Math.ceil(RtOps.asDoubleArg(x, "ceil")); }

    public static Object round(Object x) { return Math.round(RtOps.asDoubleArg(x, "round")); }

    public static Object pow(Object a, Object b) {
        double da = RtOps.asDoubleArg(a, "pow");
        double db = RtOps.asDoubleArg(b, "pow");
        double result = Math.pow(da, db);
        // If both operands were Long and result fits a long without loss,
        // return Long (matches interp's powOp narrowing).
        if (a instanceof Long && b instanceof Long
                && result == Math.floor(result)
                && !Double.isInfinite(result)
                && result >= Long.MIN_VALUE && result <= Long.MAX_VALUE) {
            return (long) result;
        }
        return result;
    }

    public static Object abs(Object v) {
        if (v instanceof Long l) return Math.abs(l);
        if (v instanceof Double d) return Math.abs(d);
        throw new dev.irij.IrijRuntimeError(
                "abs expects a number, got " + RuntimeSupport.typeTag(v));
    }


    // ── Random ────────────────────────────────────────────────────────

    public static Object randomInt(Object boundArg) {
        long bound = RtCollections.asLongArg(boundArg, "random-int");
        return java.util.concurrent.ThreadLocalRandom.current().nextLong(bound);
    }

    public static Object randomFloat() {
        return java.util.concurrent.ThreadLocalRandom.current().nextDouble();
    }


    // ── Crypto / auth primitives ──────────────────────────────────────

    private static final java.security.SecureRandom SECURE_RANDOM =
            new java.security.SecureRandom();


    /** SHA-256 of the input string (UTF-8), lower-case hex. */
    public static Object sha256Hex(Object msgArg) {
        String msg = RtStrings.asStr(msgArg, "sha256-hex");
        try {
            byte[] digest = java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(msg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return bytesToHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new dev.irij.IrijRuntimeError("sha256-hex: " + e.getMessage());
        }
    }


    /** HMAC-SHA-256 of the message under the given key (both UTF-8), lower-case hex.
     *  Rejects an empty key — Java's JCE refuses, and an empty signing key is
     *  almost always a bug in the caller anyway. */
    public static Object hmacSha256Hex(Object keyArg, Object msgArg) {
        String key = RtStrings.asStr(keyArg, "hmac-sha256-hex");
        String msg = RtStrings.asStr(msgArg, "hmac-sha256-hex");
        if (key.isEmpty()) {
            throw new dev.irij.IrijRuntimeError(
                    "hmac-sha256-hex: secret key must not be empty");
        }
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    key.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "HmacSHA256"));
            byte[] out = mac.doFinal(msg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return bytesToHex(out);
        } catch (java.security.NoSuchAlgorithmException
                | java.security.InvalidKeyException e) {
            throw new dev.irij.IrijRuntimeError("hmac-sha256-hex: " + e.getMessage());
        }
    }


    /** {@code random-token n}: n bytes from {@link java.security.SecureRandom},
     *  URL-safe base64-encoded (no padding). Suitable for session IDs. */
    public static Object randomToken(Object lenArg) {
        long n = RtCollections.asLongArg(lenArg, "random-token");
        if (n <= 0 || n > 1024) {
            throw new dev.irij.IrijRuntimeError(
                    "random-token: byte length must be in [1, 1024], got " + n);
        }
        byte[] bytes = new byte[(int) n];
        SECURE_RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }


    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }


    // ── String parsing / chars ────────────────────────────────────────

    public static Object parseInt(Object strArg) {
        String s = RtStrings.asStr(strArg, "parse-int");
        try { return Long.parseLong(s.strip()); }
        catch (NumberFormatException e) {
            throw new dev.irij.IrijRuntimeError(
                    "parse-int: cannot parse '" + s + "' as Int");
        }
    }

    public static Object parseFloat(Object strArg) {
        String s = RtStrings.asStr(strArg, "parse-float");
        try { return Double.parseDouble(s.strip()); }
        catch (NumberFormatException e) {
            throw new dev.irij.IrijRuntimeError(
                    "parse-float: cannot parse '" + s + "' as Float");
        }
    }
}
