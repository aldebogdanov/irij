package dev.irij.runtime;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Strict UTF-8 and percent-encoding, shared by the builtins that turn a
 * Str into bytes (hashes, MACs, {@code url-encode}) and by the HTTP
 * server's request decoding.
 *
 * <p>The JDK's own conversions are lossy: {@code String.getBytes(UTF_8)}
 * turns an unpaired surrogate into {@code ?}, and {@code URLDecoder}
 * turns an invalid byte sequence into U+FFFD. Lossy means two different
 * strings become the same bytes: "alice" plus a lone U+DC00 and
 * {@code "alice?"} would hash, sign and compare alike. Every method here
 * refuses such input with an {@link IllegalArgumentException} instead;
 * callers add their own name to the message.
 */
public final class TextCodec {

    private TextCodec() {}

    /** The UTF-8 bytes of {@code s}; refuses an unpaired surrogate, which
     *  has no UTF-8 encoding. */
    public static byte[] utf8(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                    i++;
                    continue;
                }
                throw unpaired(c, i);
            }
            if (Character.isLowSurrogate(c)) throw unpaired(c, i);
        }
        return s.getBytes(StandardCharsets.UTF_8); // exact now: nothing to replace
    }

    private static IllegalArgumentException unpaired(char c, int at) {
        return new IllegalArgumentException(String.format(
                "unpaired surrogate U+%04X at index %d (a Str with no UTF-8 encoding)", (int) c, at));
    }

    /** {@code bytes} as UTF-8; refuses an invalid sequence (a stray
     *  continuation byte, an overlong form, an encoded surrogate). */
    public static String fromUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("not valid UTF-8");
        }
    }

    private static final String HEX = "0123456789ABCDEF";

    /**
     * Percent-encode {@code s} (as UTF-8).
     *
     * <p>{@code form = true} is {@code application/x-www-form-urlencoded},
     * the encoding of a query string or a form body: letters, digits and
     * {@code .-*_} stay, a space becomes {@code +}. {@code form = false} is
     * RFC 3986's, right for a path segment too: only the unreserved
     * characters (letters, digits, {@code -._~}) stay, and a space is
     * {@code %20} — a {@code +} in a path is a literal plus.
     */
    public static String percentEncode(String s, boolean form) {
        byte[] bytes = utf8(s);
        StringBuilder sb = new StringBuilder(bytes.length + 16);
        for (byte b : bytes) {
            int c = b & 0xFF;
            boolean alnum = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (alnum || c == '-' || c == '.' || c == '_') {
                sb.append((char) c);
            } else if (form && c == '*') {
                sb.append('*');
            } else if (!form && c == '~') {
                sb.append('~');
            } else if (form && c == ' ') {
                sb.append('+');
            } else {
                sb.append('%').append(HEX.charAt(c >> 4)).append(HEX.charAt(c & 0xF));
            }
        }
        return sb.toString();
    }

    /**
     * Decode {@code %XX} escapes (and, when {@code form}, {@code +} as a
     * space). The decoded bytes must be UTF-8: {@code %FF} and {@code %FE}
     * are refused rather than both becoming U+FFFD. A {@code %} not
     * followed by two hex digits is refused too.
     */
    public static String percentDecode(String s, boolean form) {
        if (s.indexOf('%') < 0 && !(form && s.indexOf('+') >= 0)) {
            utf8(s); // nothing to decode, but still no unpaired surrogates
            return s;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%') {
                int hi = i + 1 < s.length() ? Character.digit(s.charAt(i + 1), 16) : -1;
                int lo = i + 2 < s.length() ? Character.digit(s.charAt(i + 2), 16) : -1;
                if (hi < 0 || lo < 0 || s.charAt(i + 1) > 0x7F || s.charAt(i + 2) > 0x7F) {
                    throw new IllegalArgumentException("malformed %-escape at index " + i);
                }
                out.write((hi << 4) | lo);
                i += 3;
            } else if (form && c == '+') {
                out.write(' ');
                i++;
            } else {
                // A run of literal text: copy its UTF-8 bytes.
                int j = i;
                while (j < s.length() && s.charAt(j) != '%' && !(form && s.charAt(j) == '+')) j++;
                out.writeBytes(utf8(s.substring(i, j)));
                i = j;
            }
        }
        try {
            return fromUtf8(out.toByteArray());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("%-escapes are not valid UTF-8");
        }
    }
}
