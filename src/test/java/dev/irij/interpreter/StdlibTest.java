package dev.irij.interpreter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Irij standard library ({@link Stdlib}).
 */
class StdlibTest {

    private Map<String, Builtin> builtins;

    @BeforeEach
    void setUp() {
        builtins = new LinkedHashMap<>();
        Stdlib.register(builtins);
    }

    private Object call(String name, Object... args) {
        var fn = builtins.get(name);
        assertNotNull(fn, "Builtin not found: " + name);
        return fn.call(List.of(args));
    }

    // ═════════════════════════════════════════════════════════════════════
    // std.core
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class Core {
        @Test void identity() { assertEquals(42L, call("identity", 42L)); }

        @Test void identityStr() { assertEquals("hello", call("identity", "hello")); }

        @Test void toStr() { assertEquals("42", call("to-str", 42L)); }

        @Test void showInt() { assertEquals("42", call("show", 42L)); }

        @Test void showStr() { assertEquals("hello", call("show", "hello")); }

        @Test void showList() { assertEquals("#[1 2 3]", call("show", List.of(1L, 2L, 3L))); }

        @Test void showMap() {
            var map = new LinkedHashMap<String, Object>();
            map.put("a", 1L);
            assertEquals("{a: 1}", call("show", map));
        }

        @Test void showUnit() { assertEquals("()", call("show", IrijInterpreter.UNIT)); }

        @Test void showTagged() { assertEquals("Ok 42", call("show", new Tagged("Ok", 42L))); }

        @Test void showNone() { assertEquals("None", call("show", new Tagged("None"))); }

        @Test void typeOfInt() { assertEquals("Int", call("type-of", 42L)); }
        @Test void typeOfFloat() { assertEquals("Float", call("type-of", 3.14)); }
        @Test void typeOfStr() { assertEquals("Str", call("type-of", "hi")); }
        @Test void typeOfVec() { assertEquals("Vec", call("type-of", List.of())); }
        @Test void typeOfMap() { assertEquals("Map", call("type-of", Map.of())); }
        @Test void typeOfUnit() { assertEquals("Unit", call("type-of", IrijInterpreter.UNIT)); }
        @Test void typeOfTagged() { assertEquals("Ok", call("type-of", new Tagged("Ok", 1L))); }

        @Test void panic() {
            assertThrows(IrijRuntimeError.class, () -> call("panic", "boom"));
        }

        @Test void dbg() {
            assertEquals(42L, call("dbg", 42L));
        }

        @Test void notTrue() { assertEquals(false, call("not", true)); }
        @Test void notFalse() { assertEquals(true, call("not", false)); }

        @Test void equalsTrue() { assertEquals(true, call("equals?", 1L, 1L)); }
        @Test void equalsFalse() { assertEquals(false, call("equals?", 1L, 2L)); }

        @Test void boolTrue() { assertEquals(true, call("true")); }
        @Test void boolFalse() { assertEquals(false, call("false")); }
    }

    // ═════════════════════════════════════════════════════════════════════
    // std.math
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class MathFunctions {
        @Test void absPositive() { assertEquals(42L, call("abs", 42L)); }
        @Test void absNegative() { assertEquals(42L, call("abs", -42L)); }
        @Test void absDouble() { assertEquals(3.14, call("abs", -3.14)); }

        @Test void minInts() { assertEquals(3L, call("min", 3L, 7L)); }
        @Test void maxInts() { assertEquals(7L, call("max", 3L, 7L)); }
        @Test void minDoubles() { assertEquals(1.5, call("min", 1.5, 2.5)); }

        @Test void floor() { assertEquals(3L, call("floor", 3.7)); }
        @Test void ceil() { assertEquals(4L, call("ceil", 3.2)); }
        @Test void round() { assertEquals(4L, call("round", 3.5)); }

        @Test void sqrt() { assertEquals(12.0, call("sqrt", 144.0)); }

        @Test void pow() { assertEquals(1024.0, call("pow", 2L, 10L)); }

        @Test void logE() { assertEquals(1.0, (double) call("log", Math.E), 0.0001); }
        @Test void log10Of100() { assertEquals(2.0, (double) call("log10", 100.0), 0.0001); }

        @Test void sinZero() { assertEquals(0.0, (double) call("sin", 0.0), 0.0001); }
        @Test void cosZero() { assertEquals(1.0, (double) call("cos", 0.0), 0.0001); }
        @Test void tanZero() { assertEquals(0.0, (double) call("tan", 0.0), 0.0001); }

        @Test void pi() { assertEquals(Math.PI, call("pi")); }
        @Test void e() { assertEquals(Math.E, call("e")); }

        @Test void random() {
            double r = (double) call("random");
            assertTrue(r >= 0.0 && r < 1.0);
        }

        @Test void toInt() { assertEquals(3L, call("to-int", 3.14)); }
        @Test void toIntFromStr() { assertEquals(42L, call("to-int", "42")); }
        @Test void toFloat() { assertEquals(42.0, call("to-float", 42L)); }
        @Test void toFloatFromStr() { assertEquals(3.14, call("to-float", "3.14")); }

        @Test void isInt() { assertEquals(true, call("int?", 42L)); }
        @Test void isNotInt() { assertEquals(false, call("int?", 3.14)); }
        @Test void isFloat() { assertEquals(true, call("float?", 3.14)); }
        @Test void isNotFloat() { assertEquals(false, call("float?", 42L)); }

        @Test void isNan() { assertEquals(true, call("nan?", Double.NaN)); }
        @Test void isNotNan() { assertEquals(false, call("nan?", 3.14)); }
        @Test void isInfinite() { assertEquals(true, call("infinite?", Double.POSITIVE_INFINITY)); }
        @Test void isNotInfinite() { assertEquals(false, call("infinite?", 3.14)); }
    }

    // ═════════════════════════════════════════════════════════════════════
    // std.text
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class TextFunctions {
        @Test void lengthStr() { assertEquals(5L, call("length", "hello")); }
        @Test void lengthList() { assertEquals(3L, call("length", List.of(1L, 2L, 3L))); }
        @Test void lengthMap() {
            assertEquals(2L, call("length", Map.of("a", 1L, "b", 2L)));
        }

        @Test void split() {
            assertEquals(List.of("a", "b", "c"), call("split", "a,b,c", ","));
        }

        @Test void join() {
            assertEquals("a-b-c", call("join", "-", List.of("a", "b", "c")));
        }

        @Test void trim() { assertEquals("hello", call("trim", "  hello  ")); }

        @Test void containsTrue() { assertEquals(true, call("contains?", "hello", "ell")); }
        @Test void containsFalse() { assertEquals(false, call("contains?", "hello", "xyz")); }

        @Test void startsWith() { assertEquals(true, call("starts-with?", "hello", "hel")); }
        @Test void startsWithFalse() { assertEquals(false, call("starts-with?", "hello", "xyz")); }

        @Test void endsWith() { assertEquals(true, call("ends-with?", "hello", "llo")); }
        @Test void endsWithFalse() { assertEquals(false, call("ends-with?", "hello", "xyz")); }

        @Test void toUpper() { assertEquals("HELLO", call("to-upper", "hello")); }
        @Test void toLower() { assertEquals("hello", call("to-lower", "HELLO")); }

        @Test void replace() { assertEquals("hi world", call("replace", "hello world", "hello", "hi")); }

        @Test void substringFrom() { assertEquals("world", call("substring", "hello world", 6L)); }
        @Test void substringRange() { assertEquals("ello", call("substring", "hello", 1L, 5L)); }

        @Test void chars() { assertEquals(List.of("a", "b", "c"), call("chars", "abc")); }

        @Test void strRepeat() { assertEquals("hahaha", call("str-repeat", "ha", 3L)); }

        @Test void isStr() { assertEquals(true, call("str?", "hi")); }
        @Test void isNotStr() { assertEquals(false, call("str?", 42L)); }

        @Test void indexOf() { assertEquals(2L, call("index-of", "hello", "llo")); }
        @Test void indexOfNotFound() { assertEquals(-1L, call("index-of", "hello", "xyz")); }
    }

    // ═════════════════════════════════════════════════════════════════════
    // std.collection — vectors
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class VectorFunctions {
        List<Object> vec = new ArrayList<>(List.of(10L, 20L, 30L, 40L, 50L));

        @Test void head() { assertEquals(new Tagged("Some", 10L), call("head", vec)); }
        @Test void headEmpty() { assertEquals(new Tagged("None"), call("head", List.of())); }

        @Test void tail() { assertEquals(List.of(20L, 30L, 40L, 50L), call("tail", vec)); }
        @Test void tailEmpty() { assertEquals(List.of(), call("tail", List.of())); }

        @Test void last() { assertEquals(new Tagged("Some", 50L), call("last", vec)); }
        @Test void lastEmpty() { assertEquals(new Tagged("None"), call("last", List.of())); }

        @Test void init() { assertEquals(List.of(10L, 20L, 30L, 40L), call("init", vec)); }
        @Test void initEmpty() { assertEquals(List.of(), call("init", List.of())); }

        @Test void take() { assertEquals(List.of(10L, 20L, 30L), call("take", 3L, vec)); }
        @Test void takeMore() { assertEquals(vec, call("take", 100L, vec)); }

        @Test void drop() { assertEquals(List.of(40L, 50L), call("drop", 3L, vec)); }
        @Test void dropAll() { assertEquals(List.of(), call("drop", 100L, vec)); }

        @Test void reverse() {
            assertEquals(List.of(50L, 40L, 30L, 20L, 10L), call("reverse", vec));
        }

        @Test void sort() {
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L),
                    call("sort", new ArrayList<>(List.of(5L, 3L, 1L, 4L, 2L))));
        }

        @Test void concat() {
            assertEquals(List.of(1L, 2L, 3L, 4L),
                    call("concat", List.of(1L, 2L), List.of(3L, 4L)));
        }

        @Test void flatten() {
            var nested = new ArrayList<>(List.of(
                    new ArrayList<>(List.of(1L, 2L)),
                    new ArrayList<>(List.of(3L, 4L)),
                    new ArrayList<>(List.of(5L))
            ));
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L), call("flatten", nested));
        }

        @Test void zip() {
            var result = call("zip", List.of(1L, 2L, 3L), List.of("a", "b", "c"));
            assertEquals(List.of(List.of(1L, "a"), List.of(2L, "b"), List.of(3L, "c")), result);
        }

        @Test void enumerate() {
            var result = call("enumerate", List.of("x", "y"));
            assertEquals(List.of(List.of(0L, "x"), List.of(1L, "y")), result);
        }

        @Test void distinct() {
            assertEquals(List.of(1L, 2L, 3L),
                    call("distinct", new ArrayList<>(List.of(1L, 2L, 2L, 3L, 3L, 3L))));
        }

        @Test void append() {
            assertEquals(List.of(1L, 2L, 3L), call("append", List.of(1L, 2L), 3L));
        }

        @Test void prepend() {
            assertEquals(List.of(0L, 1L, 2L), call("prepend", 0L, List.of(1L, 2L)));
        }

        @Test void nth() {
            assertEquals(new Tagged("Some", 30L), call("nth", 2L, vec));
        }
        @Test void nthOutOfBounds() {
            assertEquals(new Tagged("None"), call("nth", 99L, vec));
        }

        @Test void isList() { assertEquals(true, call("list?", List.of())); }
        @Test void isNotList() { assertEquals(false, call("list?", "hi")); }

        @Test void emptyTrue() { assertEquals(true, call("empty?", List.of())); }
        @Test void emptyFalse() { assertEquals(false, call("empty?", vec)); }
        @Test void emptyStr() { assertEquals(true, call("empty?", "")); }
        @Test void emptyMap() { assertEquals(true, call("empty?", Map.of())); }

        @Test void size() { assertEquals(5L, call("size", vec)); }
        @Test void sizeStr() { assertEquals(3L, call("size", "abc")); }
        @Test void sizeMap() { assertEquals(2L, call("size", Map.of("a", 1L, "b", 2L))); }

        @Test void range() {
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L), call("range", 1L, 5L));
        }
        @Test void rangeReverse() {
            assertEquals(List.of(5L, 4L, 3L, 2L, 1L), call("range", 5L, 1L));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // std.collection — maps
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class MapFunctions {
        LinkedHashMap<String, Object> map;

        @BeforeEach
        void setUp() {
            map = new LinkedHashMap<>();
            map.put("name", "Ada");
            map.put("age", 30L);
        }

        @Test void get() { assertEquals(new Tagged("Some", "Ada"), call("get", "name", map)); }
        @Test void getNotFound() { assertEquals(new Tagged("None"), call("get", "xyz", map)); }

        @Test void put() {
            @SuppressWarnings("unchecked")
            var result = (Map<String, Object>) call("put", "email", "ada@test.com", map);
            assertEquals("ada@test.com", result.get("email"));
            assertEquals("Ada", result.get("name"));
            // Original unchanged
            assertNull(map.get("email"));
        }

        @Test void remove() {
            @SuppressWarnings("unchecked")
            var result = (Map<String, Object>) call("remove", "age", map);
            assertFalse(result.containsKey("age"));
            assertEquals("Ada", result.get("name"));
            // Original unchanged
            assertTrue(map.containsKey("age"));
        }

        @Test void keys() {
            var result = call("keys", map);
            assertEquals(List.of("name", "age"), result);
        }

        @Test void values() {
            var result = call("values", map);
            assertEquals(List.of("Ada", 30L), result);
        }

        @Test void merge() {
            var other = new LinkedHashMap<String, Object>();
            other.put("role", "admin");
            @SuppressWarnings("unchecked")
            var result = (Map<String, Object>) call("merge", map, other);
            assertEquals("Ada", result.get("name"));
            assertEquals("admin", result.get("role"));
        }

        @Test void hasKeyTrue() { assertEquals(true, call("has-key?", "name", map)); }
        @Test void hasKeyFalse() { assertEquals(false, call("has-key?", "xyz", map)); }

        @Test void isMap() { assertEquals(true, call("map?", map)); }
        @Test void isNotMap() { assertEquals(false, call("map?", "hi")); }
    }

    // ═════════════════════════════════════════════════════════════════════
    // std.collection — sets
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class SetFunctions {
        @Test void toSet() {
            var result = call("to-set", List.of(1L, 2L, 2L, 3L));
            assertTrue(result instanceof Set);
            assertEquals(Set.of(1L, 2L, 3L), result);
        }

        @Test void toListFromSet() {
            var set = new LinkedHashSet<>(List.of(1L, 2L, 3L));
            var result = call("to-list", set);
            assertTrue(result instanceof List);
            assertEquals(3, ((List<?>) result).size());
        }

        @Test void union() {
            var result = call("union", List.of(1L, 2L), List.of(2L, 3L));
            assertEquals(Set.of(1L, 2L, 3L), result);
        }

        @Test void intersection() {
            var result = call("intersection", List.of(1L, 2L, 3L), List.of(2L, 3L, 4L));
            assertEquals(Set.of(2L, 3L), result);
        }

        @Test void difference() {
            var result = call("difference", List.of(1L, 2L, 3L), List.of(2L, 3L, 4L));
            assertEquals(Set.of(1L), result);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // std.io
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class IoFunctions {
        @Test void println() {
            // Just verify it doesn't throw
            assertEquals(IrijInterpreter.UNIT, call("println", "test"));
        }

        @Test void printStr() {
            assertEquals(IrijInterpreter.UNIT, call("print-str", "test"));
        }

        @Test void readFile() throws IOException {
            var tmp = Files.createTempFile("irij-test-", ".txt");
            Files.writeString(tmp, "hello from file");
            try {
                assertEquals("hello from file", call("read-file", tmp.toString()));
            } finally {
                Files.deleteIfExists(tmp);
            }
        }

        @Test void writeAndReadFile() throws IOException {
            var tmp = Files.createTempFile("irij-test-", ".txt");
            try {
                call("write-file", tmp.toString(), "written by irij");
                assertEquals("written by irij", Files.readString(tmp));
            } finally {
                Files.deleteIfExists(tmp);
            }
        }

        @Test void readFileNotFound() {
            var result = call("read-file", "/nonexistent/path/file.txt");
            assertTrue(result instanceof Tagged t && t.is("Err"));
        }

        @Test void fileExistsTrue() throws IOException {
            var tmp = Files.createTempFile("irij-test-", ".txt");
            try {
                assertEquals(true, call("file-exists?", tmp.toString()));
            } finally {
                Files.deleteIfExists(tmp);
            }
        }

        @Test void fileExistsFalse() {
            assertEquals(false, call("file-exists?", "/nonexistent/file.txt"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Constructors & Tagged values
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class Constructors {
        @Test void ok() {
            var result = call("Ok", 42L);
            assertEquals(new Tagged("Ok", 42L), result);
        }

        @Test void err() {
            var result = call("Err", "fail");
            assertEquals(new Tagged("Err", "fail"), result);
        }

        @Test void some() {
            var result = call("Some", "hello");
            assertEquals(new Tagged("Some", "hello"), result);
        }

        @Test void none() {
            var result = call("None");
            assertEquals(new Tagged("None"), result);
        }

        @Test void isOk() { assertEquals(true, call("ok?", new Tagged("Ok", 1L))); }
        @Test void isOkFalse() { assertEquals(false, call("ok?", new Tagged("Err", "x"))); }
        @Test void isErr() { assertEquals(true, call("err?", new Tagged("Err", "x"))); }

        @Test void isSome() { assertEquals(true, call("some?", new Tagged("Some", 1L))); }
        @Test void isNone() { assertEquals(true, call("none?", new Tagged("None"))); }
        @Test void isNoneFalse() { assertEquals(false, call("none?", new Tagged("Some", 1L))); }

        @Test void unwrapOk() { assertEquals(42L, call("unwrap", new Tagged("Ok", 42L))); }
        @Test void unwrapSome() { assertEquals("hi", call("unwrap", new Tagged("Some", "hi"))); }
        @Test void unwrapErr() {
            assertThrows(IrijRuntimeError.class, () -> call("unwrap", new Tagged("Err", "fail")));
        }
        @Test void unwrapNone() {
            assertThrows(IrijRuntimeError.class, () -> call("unwrap", new Tagged("None")));
        }

        @Test void unwrapOr() {
            assertEquals(42L, call("unwrap-or", new Tagged("Ok", 42L), 0L));
        }
        @Test void unwrapOrDefault() {
            assertEquals(0L, call("unwrap-or", new Tagged("Err", "fail"), 0L));
        }
        @Test void unwrapOrNone() {
            assertEquals("default", call("unwrap-or", new Tagged("None"), "default"));
        }

        @Test void pair() { assertEquals(List.of(1L, 2L), call("pair", 1L, 2L)); }
        @Test void fst() { assertEquals(1L, call("fst", List.of(1L, 2L))); }
        @Test void snd() { assertEquals(2L, call("snd", List.of(1L, 2L))); }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Sequence operators
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class SeqOps {
        @Test void reduceSum() {
            assertEquals(15L, Stdlib.evalSeqOp("/+", List.of(1L, 2L, 3L, 4L, 5L)));
        }

        @Test void reduceSumDoubles() {
            assertEquals(6.0, Stdlib.evalSeqOp("/+", List.of(1.0, 2.0, 3.0)));
        }

        @Test void reduceSumEmpty() {
            assertEquals(0L, Stdlib.evalSeqOp("/+", List.of()));
        }

        @Test void reduceProduct() {
            assertEquals(120L, Stdlib.evalSeqOp("/*", List.of(1L, 2L, 3L, 4L, 5L)));
        }

        @Test void reduceProductEmpty() {
            assertEquals(1L, Stdlib.evalSeqOp("/*", List.of()));
        }

        @Test void count() {
            assertEquals(5L, Stdlib.evalSeqOp("/#", List.of(1L, 2L, 3L, 4L, 5L)));
        }

        @Test void countEmpty() {
            assertEquals(0L, Stdlib.evalSeqOp("/#", List.of()));
        }

        @Test void reduceAnd() {
            assertEquals(true, Stdlib.evalSeqOp("/&", List.of(true, true, true)));
        }
        @Test void reduceAndFalse() {
            assertEquals(false, Stdlib.evalSeqOp("/&", List.of(true, false, true)));
        }

        @Test void reduceOr() {
            assertEquals(true, Stdlib.evalSeqOp("/|", List.of(false, true, false)));
        }
        @Test void reduceOrFalse() {
            assertEquals(false, Stdlib.evalSeqOp("/|", List.of(false, false, false)));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // show() formatting
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class Show {
        @Test void showNull() { assertEquals("()", Stdlib.show(null)); }
        @Test void showLong() { assertEquals("42", Stdlib.show(42L)); }
        @Test void showDouble() { assertEquals("3.14", Stdlib.show(3.14)); }
        @Test void showDoubleWhole() { assertEquals("3.0", Stdlib.show(3.0)); }
        @Test void showBoolTrue() { assertEquals("true", Stdlib.show(true)); }
        @Test void showBoolFalse() { assertEquals("false", Stdlib.show(false)); }
        @Test void showString() { assertEquals("hello", Stdlib.show("hello")); }

        @Test void showEmptyList() { assertEquals("#[]", Stdlib.show(List.of())); }
        @Test void showListWithStrings() {
            assertEquals("#[\"a\" \"b\"]", Stdlib.show(List.of("a", "b")));
        }
        @Test void showListWithInts() {
            assertEquals("#[1 2 3]", Stdlib.show(List.of(1L, 2L, 3L)));
        }

        @Test void showEmptyMap() { assertEquals("{}", Stdlib.show(Map.of())); }
        @Test void showMapEntry() {
            var m = new LinkedHashMap<String, Object>();
            m.put("x", 1L);
            assertEquals("{x: 1}", Stdlib.show(m));
        }

        @Test void showSet() {
            var s = new LinkedHashSet<>(List.of(1L, 2L));
            assertEquals("#{1 2}", Stdlib.show(s));
        }

        @Test void showTaggedNoFields() { assertEquals("None", Stdlib.show(new Tagged("None"))); }
        @Test void showTaggedOneField() {
            assertEquals("Ok 42", Stdlib.show(new Tagged("Ok", 42L)));
        }
        @Test void showTaggedStringField() {
            assertEquals("Err oops", Stdlib.show(new Tagged("Err", "oops")));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Tagged record
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class TaggedTests {
        @Test void equalsSame() {
            assertEquals(new Tagged("Ok", 1L), new Tagged("Ok", 1L));
        }

        @Test void notEqualsDifferentTag() {
            assertNotEquals(new Tagged("Ok", 1L), new Tagged("Err", 1L));
        }

        @Test void notEqualsDifferentValue() {
            assertNotEquals(new Tagged("Ok", 1L), new Tagged("Ok", 2L));
        }

        @Test void hashCodeConsistent() {
            assertEquals(new Tagged("Ok", 1L).hashCode(), new Tagged("Ok", 1L).hashCode());
        }

        @Test void isMethod() {
            assertTrue(new Tagged("Ok", 1L).is("Ok"));
            assertFalse(new Tagged("Ok", 1L).is("Err"));
        }

        @Test void fieldAccess() {
            assertEquals(42L, new Tagged("Ok", 42L).field(0));
        }

        @Test void fieldOutOfBounds() {
            assertEquals(IrijInterpreter.UNIT, new Tagged("None").field(0));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // isTruthy
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    class Truthiness {
        @Test void nullIsFalsy() { assertFalse(Stdlib.isTruthy(null)); }
        @Test void unitIsFalsy() { assertFalse(Stdlib.isTruthy(IrijInterpreter.UNIT)); }
        @Test void falseIsFalsy() { assertFalse(Stdlib.isTruthy(false)); }
        @Test void zeroIsFalsy() { assertFalse(Stdlib.isTruthy(0L)); }
        @Test void emptyStringIsFalsy() { assertFalse(Stdlib.isTruthy("")); }
        @Test void noneIsFalsy() { assertFalse(Stdlib.isTruthy(new Tagged("None"))); }
        @Test void errIsFalsy() { assertFalse(Stdlib.isTruthy(new Tagged("Err", "x"))); }
        @Test void emptyListIsFalsy() { assertFalse(Stdlib.isTruthy(List.of())); }
        @Test void emptyMapIsFalsy() { assertFalse(Stdlib.isTruthy(Map.of())); }

        @Test void trueIsTruthy() { assertTrue(Stdlib.isTruthy(true)); }
        @Test void oneIsTruthy() { assertTrue(Stdlib.isTruthy(1L)); }
        @Test void nonEmptyStringIsTruthy() { assertTrue(Stdlib.isTruthy("hi")); }
        @Test void someIsTruthy() { assertTrue(Stdlib.isTruthy(new Tagged("Some", 1L))); }
        @Test void okIsTruthy() { assertTrue(Stdlib.isTruthy(new Tagged("Ok", 1L))); }
        @Test void nonEmptyListIsTruthy() { assertTrue(Stdlib.isTruthy(List.of(1L))); }
    }
}
