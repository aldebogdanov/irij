package dev.irij.runtime;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Persistent insertion-ordered map with String keys: the entry store
 * behind {@link Values.IrijMap}.
 *
 * <p>A hash array mapped trie (the structure of Clojure's
 * {@code PersistentHashMap}) maps each key to its value and its insertion
 * position; a {@link PVec} of keys records the order. {@link #assoc},
 * {@link #without} and {@link #get} are O(log₃₂ n) and share structure
 * with the previous version. Before this every {@code assoc} copied the
 * whole map twice ({@code LinkedHashMap} + an unmodifiable copy): building
 * a 20 000-entry map took ~6 s.
 *
 * <p>Iteration follows insertion order, like the {@code LinkedHashMap} it
 * replaces: replacing a value keeps the key's place; removing a key and
 * adding it again moves it to the end. A removed key leaves a tombstone in
 * the order vector until tombstones outnumber live keys, when the map is
 * rebuilt. Equality and hashing are the {@link java.util.Map} contract
 * (order-insensitive). Immutable: the {@code Map} mutators throw.
 */
public final class PMap extends AbstractMap<String, Object> {

    private static final int BITS = 5;
    private static final int MASK = (1 << BITS) - 1;
    private static final Object TOMBSTONE = new Object();

    /** Up to this many entries a map is a flat array, scanned linearly —
     *  faster to build and read than a trie for record-sized maps. */
    private static final int ARRAY_MAX = 8;
    private static final Object[] NO_PAIRS = new Object[0];

    public static final PMap EMPTY = new PMap(NO_PAIRS);

    /** Array mode: [k0, v0, k1, v1, …] in insertion order; null in trie mode. */
    private final Object[] pairs;
    /** Trie root (trie mode). */
    private final Node root;
    private final int size;
    /** Keys in insertion order; removed keys are {@link #TOMBSTONE}. */
    private final PVec order;
    private final int dead;

    private PMap(Object[] pairs) {
        this.pairs = pairs;
        this.root = null;
        this.size = pairs.length / 2;
        this.order = null;
        this.dead = 0;
    }

    private PMap(Node root, int size, PVec order, int dead) {
        this.pairs = null;
        this.root = root;
        this.size = size;
        this.order = order;
        this.dead = dead;
    }

    /** A PMap with {@code m}'s entries, in {@code m}'s iteration order. */
    public static PMap from(Map<String, ?> m) {
        if (m instanceof PMap p) return p;
        if (m.size() <= ARRAY_MAX) {
            if (m.isEmpty()) return EMPTY;
            Object[] a = new Object[m.size() * 2];
            int i = 0;
            for (var e : m.entrySet()) {
                if (i == a.length) break; // a concurrently-grown source
                a[i++] = (String) e.getKey();
                a[i++] = e.getValue();
            }
            if (i == a.length) return new PMap(a);
        }
        PMap out = EMPTY;
        for (var e : m.entrySet()) out = out.assoc(e.getKey(), e.getValue());
        return out;
    }

    /** The value slot of a key: its insertion position and value. */
    private record Slot(int seq, Object value) {}

    private static int hash(String k) {
        int h = k.hashCode();
        return h ^ (h >>> 16);
    }

    @Override public int size() { return size; }

    @Override public boolean isEmpty() { return size == 0; }

    private Slot slot(Object key) {
        if (root == null || !(key instanceof String k)) return null;
        return root.find(0, hash(k), k);
    }

    /** Array mode: the index of {@code key}'s pair, or -1. */
    private int pairIndex(Object key) {
        Object[] a = pairs;
        for (int i = 0; i < a.length; i += 2) {
            Object k = a[i];
            if (k == key || k.equals(key)) return i;
        }
        return -1;
    }

    @Override public Object get(Object key) {
        if (pairs != null) {
            int i = pairIndex(key);
            return i < 0 ? null : pairs[i + 1];
        }
        Slot s = slot(key);
        return s == null ? null : s.value();
    }

    @Override public boolean containsKey(Object key) {
        if (pairs != null) return pairIndex(key) >= 0;
        return slot(key) != null;
    }

    /** This map with {@code key} bound to {@code value}. */
    public PMap assoc(String key, Object value) {
        if (pairs != null) {
            int i = pairIndex(key);
            if (i >= 0) {
                if (pairs[i + 1] == value) return this;
                Object[] a = pairs.clone();
                a[i + 1] = value;
                return new PMap(a);
            }
            if (size < ARRAY_MAX) {
                Object[] a = java.util.Arrays.copyOf(pairs, pairs.length + 2);
                a[pairs.length] = key;
                a[pairs.length + 1] = value;
                return new PMap(a);
            }
            return toTrie().assoc(key, value);
        }
        int h = hash(key);
        Slot old = root == null ? null : root.find(0, h, key);
        if (old != null) {
            if (old.value() == value) return this;
            Node r = root.assoc(0, h, key, new Slot(old.seq(), value));
            return new PMap(r, size, order, dead);
        }
        Slot s = new Slot(order.size(), value);
        Node r = root == null ? BitmapNode.EMPTY.assoc(0, h, key, s) : root.assoc(0, h, key, s);
        return new PMap(r, size + 1, order.cons(key), dead);
    }

    /** This map without {@code key}. */
    public PMap without(String key) {
        if (pairs != null) {
            int i = pairIndex(key);
            if (i < 0) return this;
            if (size == 1) return EMPTY;
            Object[] a = new Object[pairs.length - 2];
            System.arraycopy(pairs, 0, a, 0, i);
            System.arraycopy(pairs, i + 2, a, i, pairs.length - i - 2);
            return new PMap(a);
        }
        int h = hash(key);
        Slot old = root == null ? null : root.find(0, h, key);
        if (old == null) return this;
        if (size == 1) return EMPTY;
        Node r = root.without(0, h, key);
        PMap out = new PMap(r, size - 1, order.assocN(old.seq(), TOMBSTONE), dead + 1);
        return out.dead > 32 && out.dead > out.size ? out.compacted() : out;
    }

    /** The same entries in trie mode (for growing past {@link #ARRAY_MAX}). */
    private PMap toTrie() {
        Node r = BitmapNode.EMPTY;
        PVec.Builder keys = new PVec.Builder();
        for (int i = 0; i < pairs.length; i += 2) {
            String k = (String) pairs[i];
            r = r.assoc(0, hash(k), k, new Slot(i / 2, pairs[i + 1]));
            keys.add(k);
        }
        return new PMap(r, size, keys.build(), 0);
    }

    private PMap compacted() {
        PMap out = EMPTY;
        for (var e : entrySet()) out = out.assoc(e.getKey(), e.getValue());
        return out;
    }

    @Override public Set<Entry<String, Object>> entrySet() {
        return new AbstractSet<>() {
            @Override public int size() { return size; }

            @Override public Iterator<Entry<String, Object>> iterator() {
                if (pairs != null) {
                    Object[] a = pairs;
                    return new Iterator<>() {
                        int i = 0;
                        @Override public boolean hasNext() { return i < a.length; }
                        @Override public Entry<String, Object> next() {
                            if (i >= a.length) throw new NoSuchElementException();
                            var e = new SimpleImmutableEntry<>((String) a[i], a[i + 1]);
                            i += 2;
                            return e;
                        }
                    };
                }
                Iterator<Object> keys = order.iterator();
                return new Iterator<>() {
                    String nextKey = advance();

                    private String advance() {
                        while (keys.hasNext()) {
                            Object k = keys.next();
                            if (k != TOMBSTONE) return (String) k;
                        }
                        return null;
                    }

                    @Override public boolean hasNext() { return nextKey != null; }

                    @Override public Entry<String, Object> next() {
                        if (nextKey == null) throw new NoSuchElementException();
                        String k = nextKey;
                        nextKey = advance();
                        return new SimpleImmutableEntry<>(k, root.find(0, hash(k), k).value());
                    }
                };
            }
        };
    }

    // ── Trie nodes ─────────────────────────────────────────────────────

    private interface Node {
        Slot find(int shift, int hash, String key);
        Node assoc(int shift, int hash, String key, Slot slot);
        /** Null when the node is left empty. */
        Node without(int shift, int hash, String key);
    }

    /** Up to 32 children, present ones packed in bitmap order. Each
     *  present position holds either a key + slot or (key null) a child. */
    private static final class BitmapNode implements Node {
        static final BitmapNode EMPTY = new BitmapNode(0, new Object[0]);

        final int bitmap;
        /** Pairs: [key, slot] for an entry, [null, Node] for a subtree. */
        final Object[] array;

        BitmapNode(int bitmap, Object[] array) {
            this.bitmap = bitmap;
            this.array = array;
        }

        private static int bit(int hash, int shift) { return 1 << ((hash >>> shift) & MASK); }

        private int index(int bit) { return Integer.bitCount(bitmap & (bit - 1)); }

        @Override public Slot find(int shift, int hash, String key) {
            int bit = bit(hash, shift);
            if ((bitmap & bit) == 0) return null;
            int i = 2 * index(bit);
            Object k = array[i];
            Object v = array[i + 1];
            if (k == null) return ((Node) v).find(shift + BITS, hash, key);
            return key.equals(k) ? (Slot) v : null;
        }

        @Override public Node assoc(int shift, int hash, String key, Slot slot) {
            int bit = bit(hash, shift);
            int idx = index(bit);
            if ((bitmap & bit) != 0) {
                int i = 2 * idx;
                Object k = array[i];
                Object v = array[i + 1];
                if (k == null) {
                    Node child = ((Node) v).assoc(shift + BITS, hash, key, slot);
                    if (child == v) return this;
                    return withPair(i, null, child);
                }
                if (key.equals(k)) return withPair(i, k, slot);
                // Two keys share this position: push both one level down.
                String other = (String) k;
                Node sub = pair(shift + BITS, PMap.hash(other), other, (Slot) v, hash, key, slot);
                return withPair(i, null, sub);
            }
            Object[] a = new Object[array.length + 2];
            System.arraycopy(array, 0, a, 0, 2 * idx);
            a[2 * idx] = key;
            a[2 * idx + 1] = slot;
            System.arraycopy(array, 2 * idx, a, 2 * idx + 2, array.length - 2 * idx);
            return new BitmapNode(bitmap | bit, a);
        }

        @Override public Node without(int shift, int hash, String key) {
            int bit = bit(hash, shift);
            if ((bitmap & bit) == 0) return this;
            int i = 2 * index(bit);
            Object k = array[i];
            Object v = array[i + 1];
            if (k == null) {
                Node child = ((Node) v).without(shift + BITS, hash, key);
                if (child == v) return this;
                if (child != null) return withPair(i, null, child);
            } else if (!key.equals(k)) {
                return this;
            }
            if (bitmap == bit) return null;
            Object[] a = new Object[array.length - 2];
            System.arraycopy(array, 0, a, 0, i);
            System.arraycopy(array, i + 2, a, i, array.length - i - 2);
            return new BitmapNode(bitmap ^ bit, a);
        }

        private BitmapNode withPair(int i, Object k, Object v) {
            Object[] a = array.clone();
            a[i] = k;
            a[i + 1] = v;
            return new BitmapNode(bitmap, a);
        }

        /** A node holding two entries whose hashes agree below {@code shift}. */
        private static Node pair(int shift, int h1, String k1, Slot s1, int h2, String k2, Slot s2) {
            if (h1 == h2) return new CollisionNode(h1, new Object[]{k1, s1, k2, s2});
            return EMPTY.assoc(shift, h1, k1, s1).assoc(shift, h2, k2, s2);
        }
    }

    /** Keys whose full 32-bit hashes collide: a flat list of pairs. */
    private static final class CollisionNode implements Node {
        final int hash;
        final Object[] array;

        CollisionNode(int hash, Object[] array) {
            this.hash = hash;
            this.array = array;
        }

        private int indexOf(String key) {
            for (int i = 0; i < array.length; i += 2) if (key.equals(array[i])) return i;
            return -1;
        }

        @Override public Slot find(int shift, int h, String key) {
            int i = indexOf(key);
            return i < 0 ? null : (Slot) array[i + 1];
        }

        @Override public Node assoc(int shift, int h, String key, Slot slot) {
            if (h != hash) {
                // Different hash: nest this collision node under a bitmap node.
                Node n = new BitmapNode(1 << ((hash >>> shift) & MASK), new Object[]{null, this});
                return n.assoc(shift, h, key, slot);
            }
            int i = indexOf(key);
            Object[] a;
            if (i >= 0) {
                a = array.clone();
                a[i + 1] = slot;
            } else {
                a = java.util.Arrays.copyOf(array, array.length + 2);
                a[array.length] = key;
                a[array.length + 1] = slot;
            }
            return new CollisionNode(hash, a);
        }

        @Override public Node without(int shift, int h, String key) {
            int i = indexOf(key);
            if (i < 0) return this;
            if (array.length == 2) return null;
            Object[] a = new Object[array.length - 2];
            System.arraycopy(array, 0, a, 0, i);
            System.arraycopy(array, i + 2, a, i, array.length - i - 2);
            return new CollisionNode(hash, a);
        }
    }
}
