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

    // floor / ceil / round return an Int: an Int argument as is (going
    // through a double lost every Int past 2^53), a Rational exactly, a
    // Float rounded — as a big Int when it is past 64 bits.
    public static Object floor(Object x) { return RtNum.floor(x); }

    public static Object ceil(Object x)  { return RtNum.ceil(x); }

    public static Object round(Object x) { return RtNum.round(x); }

    /** {@code **}: exact for an Int or Rational base and a non-negative Int
     *  exponent (`2 ** 100` is an Int, not 1.2676506002282294E30); a Float
     *  otherwise. */
    public static Object pow(Object a, Object b) { return RtNum.pow(a, b); }

    public static Object abs(Object v) { return RtNum.abs(v); }


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


    /** {@code pbkdf2-sha256-hex password salt iterations}: PBKDF2-HMAC-SHA256
     *  (RFC 8018) of the UTF-8 password under the UTF-8 salt, 32-byte key,
     *  lower-case hex. A deliberately slow, salted key derivation — the
     *  right shape for storing passwords, unlike a single fast hash. */
    public static Object pbkdf2Sha256Hex(Object pwArg, Object saltArg, Object itersArg) {
        String pw = RtStrings.asStr(pwArg, "pbkdf2-sha256-hex");
        String salt = RtStrings.asStr(saltArg, "pbkdf2-sha256-hex");
        long iters = RtCollections.asLongArg(itersArg, "pbkdf2-sha256-hex");
        if (iters < 1 || iters > 100_000_000L) {
            throw new dev.irij.IrijRuntimeError(
                    "pbkdf2-sha256-hex: iterations must be in [1, 100000000], got " + iters);
        }
        if (salt.isEmpty()) {
            throw new dev.irij.IrijRuntimeError("pbkdf2-sha256-hex: salt must not be empty");
        }
        var spec = new javax.crypto.spec.PBEKeySpec(pw.toCharArray(),
                salt.getBytes(java.nio.charset.StandardCharsets.UTF_8), (int) iters, 256);
        try {
            byte[] dk = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return bytesToHex(dk);
        } catch (java.security.GeneralSecurityException e) {
            throw new dev.irij.IrijRuntimeError("pbkdf2-sha256-hex: " + e.getMessage());
        } finally {
            spec.clearPassword();
        }
    }


    /** {@code constant-time-eq? a b}: string equality whose running time
     *  doesn't depend on where the strings first differ — for comparing a
     *  secret (a MAC, a password hash) against an attacker's guess, where
     *  {@code ==} would leak how long a prefix was right. */
    public static Object constantTimeEq(Object aArg, Object bArg) {
        String a = RtStrings.asStr(aArg, "constant-time-eq?");
        String b = RtStrings.asStr(bArg, "constant-time-eq?");
        return java.security.MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
        try { return RtNum.parseInt(s.strip()); }
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
