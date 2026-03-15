package dev.irij.nrepl;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Bencode encoder/decoder for nREPL wire protocol.
 *
 * <p>Bencode supports four types:
 * <ul>
 *   <li>Integer: {@code i42e} → {@code Long}
 *   <li>Byte string: {@code 5:hello} → {@code String} (UTF-8)
 *   <li>List: {@code l...e} → {@code List<Object>}
 *   <li>Dictionary: {@code d...e} → {@code LinkedHashMap<String, Object>}
 * </ul>
 *
 * @see <a href="https://wiki.theory.org/BitTorrentSpecification#Bencoding">Bencode spec</a>
 */
public final class Bencode {

    private Bencode() {}

    // ── Decoding ────────────────────────────────────────────────────────

    /**
     * Decode one bencode value from the stream.
     *
     * @return decoded value, or {@code null} on EOF
     */
    public static Object decode(InputStream in) throws IOException {
        int ch = in.read();
        if (ch == -1) return null;

        return switch (ch) {
            case 'i' -> decodeInteger(in);
            case 'l' -> decodeList(in);
            case 'd' -> decodeDictionary(in);
            case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' ->
                    decodeString(in, ch);
            default -> throw new IOException("Invalid bencode: unexpected byte " + ch);
        };
    }

    /**
     * Convenience: decode a bencode dictionary (the standard nREPL message format).
     */
    public static Map<String, Object> decodeMap(InputStream in) throws IOException {
        Object obj = decode(in);
        if (obj == null) return null;
        if (obj instanceof Map) {
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) obj;
            return map;
        }
        throw new IOException("Expected bencode dictionary, got: " + obj.getClass().getSimpleName());
    }

    private static long decodeInteger(InputStream in) throws IOException {
        var sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) != 'e') {
            if (ch == -1) throw new IOException("Unexpected EOF in integer");
            sb.append((char) ch);
        }
        try {
            return Long.parseLong(sb.toString());
        } catch (NumberFormatException e) {
            throw new IOException("Invalid bencode integer: " + sb);
        }
    }

    private static String decodeString(InputStream in, int firstDigit) throws IOException {
        var lenStr = new StringBuilder();
        lenStr.append((char) firstDigit);
        int ch;
        while ((ch = in.read()) != ':') {
            if (ch == -1) throw new IOException("Unexpected EOF in string length");
            lenStr.append((char) ch);
        }
        int len;
        try {
            len = Integer.parseInt(lenStr.toString());
        } catch (NumberFormatException e) {
            throw new IOException("Invalid string length: " + lenStr);
        }
        byte[] data = in.readNBytes(len);
        if (data.length < len) {
            throw new IOException("Unexpected EOF reading string of length " + len);
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private static List<Object> decodeList(InputStream in) throws IOException {
        var list = new ArrayList<Object>();
        while (true) {
            in.mark(1);
            int ch = in.read();
            if (ch == 'e') break;
            if (ch == -1) throw new IOException("Unexpected EOF in list");
            // Push back the byte we peeked
            // Since mark/reset may not be supported, use PushbackInputStream in caller
            // Actually, we can handle this by passing the byte to decode
            var pushed = new PushbackInputStream(new SequenceInputStream(
                    new ByteArrayInputStream(new byte[]{(byte) ch}), in), 1);
            // This is getting complex. Simpler: use a wrapper approach.
            // Let's use a different strategy: peek and re-parse.
            list.add(decodeWithFirstByte(in, ch));
        }
        return list;
    }

    private static Map<String, Object> decodeDictionary(InputStream in) throws IOException {
        var map = new LinkedHashMap<String, Object>();
        while (true) {
            int ch = in.read();
            if (ch == 'e') break;
            if (ch == -1) throw new IOException("Unexpected EOF in dictionary");
            // Key must be a string
            if (ch < '0' || ch > '9') {
                throw new IOException("Dictionary key must be a string, got byte: " + ch);
            }
            String key = decodeString(in, ch);

            // Value
            int valCh = in.read();
            if (valCh == -1) throw new IOException("Unexpected EOF reading dictionary value");
            Object value = decodeWithFirstByte(in, valCh);
            map.put(key, value);
        }
        return map;
    }

    /** Decode a value given the first byte has already been read. */
    private static Object decodeWithFirstByte(InputStream in, int firstByte) throws IOException {
        return switch (firstByte) {
            case 'i' -> decodeInteger(in);
            case 'l' -> decodeList(in);
            case 'd' -> decodeDictionary(in);
            case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' ->
                    decodeString(in, firstByte);
            default -> throw new IOException("Invalid bencode: unexpected byte " + firstByte);
        };
    }

    // ── Encoding ────────────────────────────────────────────────────────

    /**
     * Encode a value to bencode format.
     *
     * <p>Supported types: {@code Long}/{@code Integer} → integer,
     * {@code String} → byte string, {@code List} → list,
     * {@code Map} → dictionary.
     */
    public static void encode(Object value, OutputStream out) throws IOException {
        switch (value) {
            case Long l -> {
                out.write('i');
                out.write(l.toString().getBytes(StandardCharsets.US_ASCII));
                out.write('e');
            }
            case Integer i -> {
                out.write('i');
                out.write(i.toString().getBytes(StandardCharsets.US_ASCII));
                out.write('e');
            }
            case String s -> {
                byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                out.write(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                out.write(':');
                out.write(bytes);
            }
            case List<?> list -> {
                out.write('l');
                for (var item : list) {
                    encode(item, out);
                }
                out.write('e');
            }
            case Map<?, ?> map -> {
                out.write('d');
                // Bencode dictionaries must have sorted string keys
                var sortedKeys = new ArrayList<>(map.keySet());
                sortedKeys.sort((a, b) -> a.toString().compareTo(b.toString()));
                for (var key : sortedKeys) {
                    encode(key.toString(), out);
                    encode(map.get(key), out);
                }
                out.write('e');
            }
            default -> throw new IOException("Cannot bencode: " + value.getClass().getSimpleName());
        }
    }

    /**
     * Convenience: encode a Map (standard nREPL message format).
     */
    public static void encodeMap(Map<String, Object> map, OutputStream out) throws IOException {
        encode(map, out);
    }

    /**
     * Encode a value to a byte array.
     */
    public static byte[] encodeToBytes(Object value) throws IOException {
        var baos = new ByteArrayOutputStream();
        encode(value, baos);
        return baos.toByteArray();
    }
}
