package dev.irij.runtime;

import java.util.AbstractSet;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Persistent hash set: the element store behind {@link Values.IrijSet}.
 *
 * <p>A hash array mapped trie over the elements' {@code hashCode}s, like
 * {@link PMap}'s (without values or order — a Set keeps none). {@link #cons}
 * and {@link #without} are O(log₃₂ n) and share structure with the previous
 * version; before, every {@code conj} on a Set copied it twice
 * ({@code HashSet} + {@code Set.copyOf}).
 *
 * <p>Elements whose {@code hashCode}s collide entirely — easy to craft for
 * strings and numbers — go into a second trie keyed by a per-JVM-seeded
 * SipHash of their value ({@link PMap#hash2} for strings), so a set built
 * from hostile input stays fast; other colliding values share a flat list.
 * Equality and hashing follow the {@link java.util.Set} contract.
 */
public final class PSet extends AbstractSet<Object> {

    private static final int BITS = 5;
    private static final int MASK = (1 << BITS) - 1;

    public static final PSet EMPTY = new PSet(null, 0);

    private final Node root;
    private final int size;

    private PSet(Node root, int size) {
        this.root = root;
        this.size = size;
    }

    public static PSet from(Collection<?> c) {
        if (c instanceof PSet p) return p;
        PSet out = EMPTY;
        for (Object o : c) out = out.cons(o);
        return out;
    }

    private static int hash(Object o) {
        int h = o == null ? 0 : o.hashCode();
        return h ^ (h >>> 16);
    }

    /** Keyed hash of an element's value, for colliding elements; 0 when
     *  the element has no canonical form to hash (they then share a list). */
    private static int hash2(Object o) {
        if (o instanceof String s) return PMap.hash2(s);
        if (o instanceof Long l) return PMap.hash2(Long.toString(l));
        if (o instanceof Double d) return PMap.hash2("d" + Double.doubleToLongBits(d));
        if (o instanceof Values.Keyword k) return PMap.hash2(":" + k.name());
        if (o instanceof Boolean b) return PMap.hash2(b ? "true" : "false");
        // Composite values have no cheap canonical form consistent with
        // equals (maps and sets iterate in arbitrary order); colliding
        // ones share a flat list.
        return 0;
    }

    @Override public int size() { return size; }

    @Override public boolean contains(Object o) {
        return root != null && root.find(0, hash(o), o);
    }

    /** This set with {@code x} added. */
    public PSet cons(Object x) {
        int h = hash(x);
        if (root != null && root.find(0, h, x)) return this;
        Node r = (root == null ? BitmapNode.PRIMARY : root).assoc(0, h, x);
        return new PSet(r, size + 1);
    }

    /** This set without {@code x}. */
    public PSet without(Object x) {
        int h = hash(x);
        if (root == null || !root.find(0, h, x)) return this;
        if (size == 1) return EMPTY;
        return new PSet(root.without(0, h, x), size - 1);
    }

    @Override public Iterator<Object> iterator() {
        // Depth-first over the trie; each frame is a node's array and a cursor.
        ArrayDeque<Object[]> arrays = new ArrayDeque<>();
        ArrayDeque<int[]> cursors = new ArrayDeque<>();
        if (root != null) { arrays.push(root.items()); cursors.push(new int[]{0}); }
        return new Iterator<>() {
            Object next = NONE;

            private Object advance() {
                while (!arrays.isEmpty()) {
                    Object[] a = arrays.peek();
                    int[] c = cursors.peek();
                    if (c[0] >= a.length) { arrays.pop(); cursors.pop(); continue; }
                    Object o = a[c[0]++];
                    if (o instanceof Node n) { arrays.push(n.items()); cursors.push(new int[]{0}); continue; }
                    return o == NULL_ELEM ? null : o;
                }
                return NONE;
            }

            @Override public boolean hasNext() {
                if (next == NONE) next = advance();
                return next != NONE;
            }

            @Override public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                Object o = next;
                next = NONE;
                return o;
            }
        };
    }

    private static final Object NONE = new Object();
    /** Stands in for a null element inside node arrays. */
    private static final Object NULL_ELEM = new Object();

    private static Object box(Object x) { return x == null ? NULL_ELEM : x; }

    private static boolean same(Object stored, Object x) {
        return stored == NULL_ELEM ? x == null : stored.equals(x);
    }

    // ── Trie nodes ─────────────────────────────────────────────────────

    private interface Node {
        boolean find(int shift, int hash, Object x);
        Node assoc(int shift, int hash, Object x);
        Node without(int shift, int hash, Object x);
        /** Elements and child nodes, for iteration. */
        Object[] items();
    }

    private static final class BitmapNode implements Node {
        static final BitmapNode PRIMARY = new BitmapNode(false, 0, new Object[0]);
        static final BitmapNode SECONDARY = new BitmapNode(true, 0, new Object[0]);

        final boolean secondary;
        final int bitmap;
        /** One slot per present position: an element or a child Node. */
        final Object[] array;

        BitmapNode(boolean secondary, int bitmap, Object[] array) {
            this.secondary = secondary;
            this.bitmap = bitmap;
            this.array = array;
        }

        private static int bit(int hash, int shift) { return 1 << ((hash >>> shift) & MASK); }
        private int index(int bit) { return Integer.bitCount(bitmap & (bit - 1)); }
        private int hashOf(Object stored) {
            Object x = stored == NULL_ELEM ? null : stored;
            return secondary ? hash2(x) : hash(x);
        }

        @Override public Object[] items() { return array; }

        @Override public boolean find(int shift, int hash, Object x) {
            int bit = bit(hash, shift);
            if ((bitmap & bit) == 0) return false;
            Object o = array[index(bit)];
            if (o instanceof Node n) return n.find(shift + BITS, hash, x);
            return same(o, x);
        }

        @Override public Node assoc(int shift, int hash, Object x) {
            int bit = bit(hash, shift);
            int i = index(bit);
            if ((bitmap & bit) != 0) {
                Object o = array[i];
                Node sub;
                if (o instanceof Node n) {
                    sub = n.assoc(shift + BITS, hash, x);
                    if (sub == n) return this;
                } else {
                    if (same(o, x)) return this;
                    sub = pair(shift + BITS, hashOf(o), o, hash, box(x));
                }
                Object[] a = array.clone();
                a[i] = sub;
                return new BitmapNode(secondary, bitmap, a);
            }
            Object[] a = new Object[array.length + 1];
            System.arraycopy(array, 0, a, 0, i);
            a[i] = box(x);
            System.arraycopy(array, i, a, i + 1, array.length - i);
            return new BitmapNode(secondary, bitmap | bit, a);
        }

        @Override public Node without(int shift, int hash, Object x) {
            int bit = bit(hash, shift);
            if ((bitmap & bit) == 0) return this;
            int i = index(bit);
            Object o = array[i];
            if (o instanceof Node n) {
                Node sub = n.without(shift + BITS, hash, x);
                if (sub == n) return this;
                if (sub != null) {
                    Object[] a = array.clone();
                    a[i] = sub;
                    return new BitmapNode(secondary, bitmap, a);
                }
            } else if (!same(o, x)) {
                return this;
            }
            if (bitmap == bit) return null;
            Object[] a = new Object[array.length - 1];
            System.arraycopy(array, 0, a, 0, i);
            System.arraycopy(array, i + 1, a, i, array.length - i - 1);
            return new BitmapNode(secondary, bitmap ^ bit, a);
        }

        /** A node holding two boxed elements whose hashes agree below {@code shift}. */
        private Node pair(int shift, int h1, Object e1, int h2, Object e2) {
            if (h1 == h2) {
                Object x1 = e1 == NULL_ELEM ? null : e1, x2 = e2 == NULL_ELEM ? null : e2;
                if (!secondary && hash2(x1) != 0 && hash2(x2) != 0) {
                    return new CollisionNode(h1, SECONDARY.assoc(0, hash2(x1), x1).assoc(0, hash2(x2), x2));
                }
                return new ListNode(secondary, h1, new Object[]{e1, e2});
            }
            BitmapNode empty = secondary ? SECONDARY : PRIMARY;
            Object x1 = e1 == NULL_ELEM ? null : e1, x2 = e2 == NULL_ELEM ? null : e2;
            return empty.assoc(shift, h1, x1).assoc(shift, h2, x2);
        }
    }

    /** Elements with fully colliding {@code hashCode}s and a keyed hash:
     *  a second trie indexed by {@link #hash2}. */
    private static final class CollisionNode implements Node {
        final int hash;
        final Node inner;

        CollisionNode(int hash, Node inner) {
            this.hash = hash;
            this.inner = inner;
        }

        @Override public Object[] items() { return new Object[]{inner}; }

        @Override public boolean find(int shift, int h, Object x) {
            return h == hash && hash2(x) != 0 ? inner.find(0, hash2(x), x) : false;
        }

        @Override public Node assoc(int shift, int h, Object x) {
            if (h != hash || hash2(x) == 0) {
                // A different hash, or a value with no keyed hash that
                // collides with keyed ones: nest under a bitmap node / list.
                if (h != hash) {
                    Node n = new BitmapNode(false, 1 << ((hash >>> shift) & MASK), new Object[]{this});
                    return n.assoc(shift, h, x);
                }
                return new ListNode(false, hash, new Object[]{this, box(x)});
            }
            Node in = inner.assoc(0, hash2(x), x);
            return in == inner ? this : new CollisionNode(hash, in);
        }

        @Override public Node without(int shift, int h, Object x) {
            if (h != hash || hash2(x) == 0) return this;
            Node in = inner.without(0, hash2(x), x);
            if (in == inner) return this;
            return in == null ? null : new CollisionNode(hash, in);
        }
    }

    /** Colliding elements in a flat list (may hold one CollisionNode). */
    private static final class ListNode implements Node {
        /** Whether this list lives in a {@link CollisionNode}'s keyed trie. */
        final boolean secondary;
        final int hash;
        final Object[] array;

        ListNode(boolean secondary, int hash, Object[] array) {
            this.secondary = secondary;
            this.hash = hash;
            this.array = array;
        }

        @Override public Object[] items() { return array; }

        private int indexOf(Object x) {
            for (int i = 0; i < array.length; i++) {
                if (!(array[i] instanceof Node) && same(array[i], x)) return i;
            }
            return -1;
        }

        @Override public boolean find(int shift, int h, Object x) {
            if (indexOf(x) >= 0) return true;
            for (Object o : array) if (o instanceof Node n && n.find(shift, h, x)) return true;
            return false;
        }

        @Override public Node assoc(int shift, int h, Object x) {
            if (h != hash) {
                Node n = new BitmapNode(secondary, 1 << ((hash >>> shift) & MASK), new Object[]{this});
                return n.assoc(shift, h, x);
            }
            if (find(shift, h, x)) return this;
            Object[] a = java.util.Arrays.copyOf(array, array.length + 1);
            a[array.length] = box(x);
            return new ListNode(secondary, hash, a);
        }

        @Override public Node without(int shift, int h, Object x) {
            int i = indexOf(x);
            if (i < 0) {
                for (int j = 0; j < array.length; j++) {
                    if (array[j] instanceof Node n) {
                        Node sub = n.without(shift, h, x);
                        if (sub == n) continue;
                        Object[] a = array.clone();
                        if (sub == null) {
                            a = new Object[array.length - 1];
                            System.arraycopy(array, 0, a, 0, j);
                            System.arraycopy(array, j + 1, a, j, array.length - j - 1);
                        } else {
                            a[j] = sub;
                        }
                        return a.length == 0 ? null : new ListNode(secondary, hash, a);
                    }
                }
                return this;
            }
            if (array.length == 1) return null;
            Object[] a = new Object[array.length - 1];
            System.arraycopy(array, 0, a, 0, i);
            System.arraycopy(array, i + 1, a, i, array.length - i - 1);
            return new ListNode(secondary, hash, a);
        }
    }
}
