package dev.irij.nrepl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the bencode encoder/decoder.
 */
class BencodeTest {

    // ── Helper ──────────────────────────────────────────────────────────

    private Object roundTrip(Object value) throws IOException {
        var baos = new ByteArrayOutputStream();
        Bencode.encode(value, baos);
        return Bencode.decode(new ByteArrayInputStream(baos.toByteArray()));
    }

    // ── Integers ────────────────────────────────────────────────────────

    @Nested
    class Integers {
        @Test void positiveInt() throws IOException {
            assertEquals(42L, roundTrip(42L));
        }

        @Test void zero() throws IOException {
            assertEquals(0L, roundTrip(0L));
        }

        @Test void negativeInt() throws IOException {
            assertEquals(-123L, roundTrip(-123L));
        }

        @Test void javaInteger() throws IOException {
            // Java Integer should also work
            assertEquals(7L, roundTrip(7)); // Integer encodes, decodes as Long
        }

        @Test void encodeFormat() throws IOException {
            var bytes = Bencode.encodeToBytes(42L);
            assertEquals("i42e", new String(bytes, StandardCharsets.US_ASCII));
        }
    }

    // ── Strings ─────────────────────────────────────────────────────────

    @Nested
    class Strings {
        @Test void simpleString() throws IOException {
            assertEquals("hello", roundTrip("hello"));
        }

        @Test void emptyString() throws IOException {
            assertEquals("", roundTrip(""));
        }

        @Test void utf8String() throws IOException {
            assertEquals("ℑ — Irij", roundTrip("ℑ — Irij"));
        }

        @Test void encodeFormat() throws IOException {
            var bytes = Bencode.encodeToBytes("hello");
            assertEquals("5:hello", new String(bytes, StandardCharsets.US_ASCII));
        }

        @Test void utf8ByteLength() throws IOException {
            // "ℑ" is 3 bytes in UTF-8, total "ℑ" = 3 bytes
            var bytes = Bencode.encodeToBytes("ℑ");
            // Should be "3:" followed by 3 UTF-8 bytes
            assertEquals(5, bytes.length); // "3:" = 2 bytes + 3 UTF-8 bytes
            assertTrue(new String(bytes, StandardCharsets.US_ASCII).startsWith("3:") ||
                       bytes[0] == '3' && bytes[1] == ':');
        }
    }

    // ── Lists ───────────────────────────────────────────────────────────

    @Nested
    class Lists {
        @Test void simpleList() throws IOException {
            var list = List.of("a", "b", "c");
            assertEquals(list, roundTrip(list));
        }

        @Test void emptyList() throws IOException {
            assertEquals(List.of(), roundTrip(List.of()));
        }

        @Test void mixedList() throws IOException {
            var list = List.of("hello", 42L);
            assertEquals(list, roundTrip(list));
        }

        @Test void encodeFormat() throws IOException {
            var bytes = Bencode.encodeToBytes(List.of("a", 1L));
            assertEquals("l1:ai1ee", new String(bytes, StandardCharsets.US_ASCII));
        }
    }

    // ── Dictionaries ────────────────────────────────────────────────────

    @Nested
    class Dictionaries {
        @Test void simpleDict() throws IOException {
            var map = new LinkedHashMap<String, Object>();
            map.put("name", "irij");
            map.put("version", "0.1");
            var result = roundTrip(map);
            assertInstanceOf(Map.class, result);
            @SuppressWarnings("unchecked")
            var resultMap = (Map<String, Object>) result;
            assertEquals("irij", resultMap.get("name"));
            assertEquals("0.1", resultMap.get("version"));
        }

        @Test void emptyDict() throws IOException {
            var result = roundTrip(Map.of());
            assertInstanceOf(Map.class, result);
            @SuppressWarnings("unchecked")
            var resultMap = (Map<String, Object>) result;
            assertTrue(resultMap.isEmpty());
        }

        @Test void nestedDict() throws IOException {
            var inner = Map.of("key", "value");
            var outer = Map.of("nested", inner, "num", 5L);
            var result = roundTrip(outer);
            assertInstanceOf(Map.class, result);
            @SuppressWarnings("unchecked")
            var resultMap = (Map<String, Object>) result;
            assertInstanceOf(Map.class, resultMap.get("nested"));
        }

        @Test void sortedKeys() throws IOException {
            // Bencode dicts must have sorted keys
            var map = new LinkedHashMap<String, Object>();
            map.put("z", "last");
            map.put("a", "first");
            var bytes = Bencode.encodeToBytes(map);
            var encoded = new String(bytes, StandardCharsets.US_ASCII);
            // 'a' should come before 'z' in the encoding
            assertTrue(encoded.indexOf("1:a") < encoded.indexOf("1:z"),
                    "Keys should be sorted: " + encoded);
        }
    }

    // ── Edge cases ──────────────────────────────────────────────────────

    @Nested
    class EdgeCases {
        @Test void eofReturnsNull() throws IOException {
            var result = Bencode.decode(new ByteArrayInputStream(new byte[0]));
            assertNull(result);
        }

        @Test void invalidByteThrows() {
            var in = new ByteArrayInputStream("x".getBytes());
            assertThrows(IOException.class, () -> Bencode.decode(in));
        }

        @Test void decodeMap() throws IOException {
            var bytes = Bencode.encodeToBytes(Map.of("op", "eval", "code", "1+2"));
            var map = Bencode.decodeMap(new ByteArrayInputStream(bytes));
            assertEquals("eval", map.get("op"));
            assertEquals("1+2", map.get("code"));
        }
    }
}
