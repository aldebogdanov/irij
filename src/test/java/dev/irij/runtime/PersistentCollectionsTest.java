package dev.irij.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** PVec / PMap against ArrayList / LinkedHashMap under random operations. */
class PersistentCollectionsTest {

    @Test void pvecMatchesArrayListAcrossTrieLevels() {
        PVec v = PVec.EMPTY;
        List<Object> ref = new ArrayList<>();
        // Past 32 (tail), 32*32+32 (one trie level) and 32^3 (two levels).
        for (int i = 0; i < 40_000; i++) {
            v = v.cons((long) i);
            ref.add((long) i);
            if (i == 31 || i == 32 || i == 1055 || i == 1056 || i == 33_000) assertEquals(ref, v);
        }
        assertEquals(ref, v);
        assertEquals(ref.hashCode(), v.hashCode());
        assertEquals(ref, PVec.from(ref));
        assertEquals(v, new ArrayList<>(v)); // iterator agrees with get
    }

    @Test void pvecVersionsAreIndependent() {
        PVec base = PVec.EMPTY;
        for (int i = 0; i < 100; i++) base = base.cons(i);
        PVec a = base.cons("a");
        PVec b = base.cons("b"); // fork from the same version
        assertEquals(100, base.size());
        assertEquals("a", a.get(100));
        assertEquals("b", b.get(100));
        PVec c = a.assocN(5, "x");
        assertEquals(5, a.get(5));
        assertEquals("x", c.get(5));
    }

    @Test void pvecDropFirstIsAView() {
        Random r = new Random(1);
        PVec v = PVec.EMPTY;
        List<Object> ref = new ArrayList<>();
        // A queue: conj at the back, drop at the front, many times over.
        for (int i = 0; i < 20_000; i++) {
            v = v.cons(i);
            ref.add(i);
            if (r.nextInt(3) > 0 && !ref.isEmpty()) {
                v = v.dropFirst();
                ref.remove(0);
            }
            if (i % 997 == 0) assertEquals(ref, v);
        }
        assertEquals(ref, v);
        assertEquals(ref.subList(3, 10), v.slice(3, 10));
        assertEquals(ref.subList(3, ref.size()), v.slice(3, ref.size()));
        assertEquals(PVec.EMPTY, PVec.of(1).dropFirst());
    }

    @Test void pvecRejectsMutationAndBadIndices() {
        PVec v = PVec.of(1, 2);
        assertThrows(UnsupportedOperationException.class, () -> v.add(3));
        assertThrows(IndexOutOfBoundsException.class, () -> v.get(2));
        assertThrows(IndexOutOfBoundsException.class, () -> v.get(-1));
        assertEquals(List.of(1, 2), v);
    }

    @Test void pmapMatchesLinkedHashMapUnderRandomOps() {
        Random r = new Random(7);
        PMap m = PMap.EMPTY;
        Map<String, Object> ref = new LinkedHashMap<>();
        for (int i = 0; i < 50_000; i++) {
            String k = "k" + r.nextInt(3_000);
            int op = r.nextInt(10);
            if (op < 6) {
                m = m.assoc(k, i);
                ref.put(k, i);
            } else {
                m = m.without(k);
                ref.remove(k);
            }
            if (i % 2_503 == 0) {
                assertEquals(ref, m);
                assertEquals(new ArrayList<>(ref.keySet()), new ArrayList<>(m.keySet()), "order");
            }
        }
        assertEquals(ref, m);
        assertEquals(new ArrayList<>(ref.entrySet()), new ArrayList<>(m.entrySet()));
        assertEquals(ref.hashCode(), m.hashCode());
        assertEquals(ref.size(), m.size());
    }

    @Test void pmapSmallMapsCrossTheArrayTrieBoundary() {
        // 12 keys straddle the 8-entry array mode: maps grow into a trie
        // and shrink again, and must agree with LinkedHashMap throughout.
        Random r = new Random(11);
        PMap m = PMap.EMPTY;
        Map<String, Object> ref = new LinkedHashMap<>();
        for (int i = 0; i < 20_000; i++) {
            String k = "f" + r.nextInt(12);
            if (r.nextInt(5) < 3) { m = m.assoc(k, i); ref.put(k, i); }
            else { m = m.without(k); ref.remove(k); }
            assertEquals(new ArrayList<>(ref.entrySet()), new ArrayList<>(m.entrySet()), "step " + i);
        }
        assertEquals(ref, PMap.from(ref));
    }

    @Test void pmapKeepsPlaceOnUpdateAndMovesOnReinsert() {
        PMap m = PMap.EMPTY.assoc("a", 1).assoc("b", 2).assoc("c", 3);
        assertEquals(List.of("a", "b", "c"), new ArrayList<>(m.keySet()));
        assertEquals(List.of("a", "b", "c"), new ArrayList<>(m.assoc("a", 9).keySet()));
        assertEquals(List.of("b", "c", "a"), new ArrayList<>(m.without("a").assoc("a", 9).keySet()));
        assertEquals(3, m.size());
        assertNull(m.get("z"));
        assertNull(m.get(42));
        assertSame(m, m.without("z"));
    }

    @Test void pmapHandlesFullHashCollisions() {
        // "Aa" and "BB" share a String.hashCode, as do their concatenations.
        String[] keys = {"AaAa", "AaBB", "BBAa", "BBBB"};
        PMap m = PMap.EMPTY;
        for (int i = 0; i < keys.length; i++) m = m.assoc(keys[i], i);
        for (int i = 0; i < keys.length; i++) assertEquals(i, m.get(keys[i]));
        m = m.without("AaBB");
        assertNull(m.get("AaBB"));
        assertEquals(3, m.size());
        assertEquals(List.of("AaAa", "BBAa", "BBBB"), new ArrayList<>(m.keySet()));
        m = m.assoc("x", 1).assoc("AaBB", 5);
        assertEquals(5, m.get("AaBB"));
    }

    @Test void pmapStaysFastUnderAHashFlood() {
        // 2^15 keys built from "Aa"/"BB" blocks all share one
        // String.hashCode. A linear collision bucket makes this
        // quadratic (~5e8 comparisons); the keyed second trie doesn't.
        List<String> keys = new ArrayList<>();
        keys.add("");
        for (int round = 0; round < 15; round++) {
            List<String> next = new ArrayList<>(keys.size() * 2);
            for (String k : keys) { next.add(k + "Aa"); next.add(k + "BB"); }
            keys = next;
        }
        int h = keys.get(0).hashCode();
        assertTrue(keys.stream().allMatch(k -> k.hashCode() == h));
        long t0 = System.nanoTime();
        PMap m = PMap.EMPTY;
        for (int i = 0; i < keys.size(); i++) m = m.assoc(keys.get(i), i);
        for (int i = 0; i < keys.size(); i++) assertEquals(i, m.get(keys.get(i)));
        for (int i = 0; i < keys.size(); i += 2) m = m.without(keys.get(i));
        assertEquals(keys.size() / 2, m.size());
        assertNull(m.get(keys.get(0)));
        assertEquals(1, m.get(keys.get(1)));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 5_000, "hash flood took " + ms + " ms");
        assertEquals(keys.get(1), m.keySet().iterator().next(), "insertion order survives");
    }

    @Test void psetMatchesHashSetUnderRandomOps() {
        Random r = new Random(3);
        PSet s = PSet.EMPTY;
        java.util.Set<Object> ref = new java.util.HashSet<>();
        for (int i = 0; i < 50_000; i++) {
            Object x = switch (r.nextInt(4)) {
                case 0 -> (long) r.nextInt(2_000);
                case 1 -> "s" + r.nextInt(2_000);
                case 2 -> (double) r.nextInt(50);
                default -> r.nextInt(100) == 0 ? null : (Object) PVec.of((long) r.nextInt(30));
            };
            if (r.nextInt(3) < 2) { s = s.cons(x); ref.add(x); }
            else { s = s.without(x); ref.remove(x); }
            if (i % 4_999 == 0) assertEquals(ref, s);
        }
        assertEquals(ref, s);
        assertEquals(ref.hashCode(), s.hashCode());
        assertEquals(ref.size(), new ArrayList<>(s).size(), "iterator yields each element once");
        assertEquals(ref, PSet.from(ref));
    }

    @Test void psetStaysFastUnderAHashFlood() {
        // Strings from "Aa"/"BB" blocks share a hashCode; so do longs of
        // the form (k << 32) | k (hashCode 0 for every k).
        List<Object> xs = new ArrayList<>();
        List<String> keys = new ArrayList<>(List.of(""));
        for (int round = 0; round < 14; round++) {
            List<String> next = new ArrayList<>();
            for (String k : keys) { next.add(k + "Aa"); next.add(k + "BB"); }
            keys = next;
        }
        xs.addAll(keys);
        for (long k = 0; k < 16_384; k++) xs.add((k << 32) | k);
        long t0 = System.nanoTime();
        PSet s = PSet.EMPTY;
        for (Object x : xs) s = s.cons(x);
        for (Object x : xs) assertTrue(s.contains(x));
        for (int i = 0; i < xs.size(); i += 2) s = s.without(xs.get(i));
        assertEquals(xs.size() / 2, s.size());
        assertFalse(s.contains(xs.get(0)));
        assertTrue(s.contains(xs.get(1)));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 5_000, "hash flood took " + ms + " ms");
    }

    @Test void pmapVersionsAreIndependent() {
        PMap base = PMap.EMPTY;
        for (int i = 0; i < 200; i++) base = base.assoc("k" + i, i);
        PMap a = base.assoc("k5", "a");
        PMap b = base.without("k5");
        assertEquals(5, base.get("k5"));
        assertEquals("a", a.get("k5"));
        assertFalse(b.containsKey("k5"));
        assertEquals(200, base.size());
        assertEquals(199, b.size());
    }
}
