package dev.irij.runtime;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.RandomAccess;

/**
 * Persistent vector: the element store behind {@link Values.IrijVector}.
 *
 * <p>A 32-way trie with a separate tail — the structure of Clojure's
 * {@code PersistentVector}. {@link #cons} (append) is amortised O(1),
 * {@link #get} and {@link #assocN} are O(log₃₂ n), and every version shares
 * structure with the one it came from, so building a vector element by
 * element is linear rather than quadratic. Before this every {@code conj}
 * copied the whole vector ({@code List.copyOf}): a 30 000-element build loop
 * took ~0.7 s.
 *
 * <p>{@link #dropFirst} (Irij's {@code tail}) is O(1): the result shares
 * the trie and skips a prefix ({@code start}). Once the skipped prefix
 * outgrows the live part it is compacted away, so a queue that conjes at
 * the back and drops at the front doesn't keep everything it ever held.
 *
 * <p>Immutable: the {@code List} mutators throw. Equality and hashing are
 * the {@link java.util.List} contract, element-wise, like the
 * {@code List.copyOf} lists it replaces. Null elements are allowed.
 */
public final class PVec extends AbstractList<Object> implements RandomAccess {

    private static final int BITS = 5;
    private static final int WIDTH = 1 << BITS;
    private static final int MASK = WIDTH - 1;
    private static final Object[] EMPTY_NODE = new Object[WIDTH];
    private static final Object[] EMPTY_TAIL = new Object[0];

    public static final PVec EMPTY = new PVec(0, BITS, EMPTY_NODE, EMPTY_TAIL, 0);

    /** Elements in the trie + tail, counting the skipped prefix. */
    private final int cnt;
    private final int shift;
    private final Object[] root;
    private final Object[] tail;
    /** Index of the first live element (elements before it were dropped). */
    private final int start;

    private PVec(int cnt, int shift, Object[] root, Object[] tail, int start) {
        this.cnt = cnt;
        this.shift = shift;
        this.root = root;
        this.tail = tail;
        this.start = start;
    }

    /** A PVec holding {@code c}'s elements in iteration order. */
    public static PVec from(Collection<?> c) {
        if (c instanceof PVec p) return p;
        Builder b = new Builder();
        for (Object o : c) b.add(o);
        return b.build();
    }

    public static PVec of(Object... xs) {
        Builder b = new Builder();
        for (Object o : xs) b.add(o);
        return b.build();
    }

    @Override public int size() { return cnt - start; }

    private int tailoff() {
        return cnt < WIDTH ? 0 : ((cnt - 1) >>> BITS) << BITS;
    }

    private Object[] leafFor(int i) {
        if (i >= tailoff()) return tail;
        Object[] node = root;
        for (int level = shift; level > 0; level -= BITS) {
            node = (Object[]) node[(i >>> level) & MASK];
        }
        return node;
    }

    @Override public Object get(int index) {
        if (index < 0 || index >= cnt - start) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + size());
        }
        int i = start + index;
        return leafFor(i)[i & MASK];
    }

    /** This vector with {@code x} appended. */
    public PVec cons(Object x) {
        if (cnt - tailoff() < WIDTH) {
            Object[] newTail = Arrays.copyOf(tail, tail.length + 1);
            newTail[tail.length] = x;
            return new PVec(cnt + 1, shift, root, newTail, start);
        }
        // Tail full: push it into the trie, start a new tail.
        Object[] newRoot;
        int newShift = shift;
        if ((cnt >>> BITS) > (1 << shift)) { // root overflow: grow a level
            newRoot = new Object[WIDTH];
            newRoot[0] = root;
            newRoot[1] = newPath(shift, tail);
            newShift += BITS;
        } else {
            newRoot = pushTail(cnt, shift, root, tail);
        }
        return new PVec(cnt + 1, newShift, newRoot, new Object[]{x}, start);
    }

    private static Object[] pushTail(int cnt, int level, Object[] parent, Object[] tailNode) {
        int subidx = ((cnt - 1) >>> level) & MASK;
        Object[] ret = parent.clone();
        Object[] toInsert;
        if (level == BITS) {
            toInsert = tailNode;
        } else {
            Object[] child = (Object[]) parent[subidx];
            toInsert = child != null
                    ? pushTail(cnt, level - BITS, child, tailNode)
                    : newPath(level - BITS, tailNode);
        }
        ret[subidx] = toInsert;
        return ret;
    }

    private static Object[] newPath(int level, Object[] node) {
        if (level == 0) return node;
        Object[] ret = new Object[WIDTH];
        ret[0] = newPath(level - BITS, node);
        return ret;
    }

    /** This vector with element {@code index} replaced by {@code x}. */
    public PVec assocN(int index, Object x) {
        if (index < 0 || index >= cnt - start) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + size());
        }
        int i = start + index;
        if (i >= tailoff()) {
            Object[] newTail = tail.clone();
            newTail[i & MASK] = x;
            return new PVec(cnt, shift, root, newTail, start);
        }
        return new PVec(cnt, shift, doAssoc(shift, root, i, x), tail, start);
    }

    private static Object[] doAssoc(int level, Object[] node, int i, Object x) {
        Object[] ret = node.clone();
        if (level == 0) {
            ret[i & MASK] = x;
        } else {
            int subidx = (i >>> level) & MASK;
            ret[subidx] = doAssoc(level - BITS, (Object[]) node[subidx], i, x);
        }
        return ret;
    }

    /** This vector without its first element (Irij's {@code tail}); empty
     *  stays empty. O(1) until the dropped prefix outweighs the rest, then
     *  one compacting copy. */
    public PVec dropFirst() {
        int n = size();
        if (n <= 1) return EMPTY;
        int newStart = start + 1;
        if (newStart >= WIDTH && newStart > (cnt - newStart)) {
            Builder b = new Builder();
            for (int i = newStart; i < cnt; i++) b.add(leafFor(i)[i & MASK]);
            return b.build();
        }
        return new PVec(cnt, shift, root, tail, newStart);
    }

    /** Elements {@code [from, to)} as a new vector (copied). */
    public PVec slice(int from, int to) {
        if (from < 0 || to > size() || from > to) {
            throw new IndexOutOfBoundsException("slice " + from + ".." + to + " of " + size());
        }
        if (to == size()) {
            PVec v = this;
            for (int k = 0; k < from && v.size() > 0; k++) v = v.dropFirst();
            return v;
        }
        Builder b = new Builder();
        for (int i = from; i < to; i++) b.add(get(i));
        return b.build();
    }

    @Override public Iterator<Object> iterator() {
        return new Iterator<>() {
            int i = start;
            Object[] leaf = cnt > start ? leafFor(start) : null;

            @Override public boolean hasNext() { return i < cnt; }

            @Override public Object next() {
                if (i >= cnt) throw new NoSuchElementException();
                if ((i & MASK) == 0 || leaf == null) leaf = leafFor(i);
                return leaf[i++ & MASK];
            }
        };
    }

    /** Appends without the per-element tail copy {@link #cons} makes: the
     *  partial tail is private to the builder until {@link #build}. */
    public static final class Builder {
        private int cnt;
        private int shift = BITS;
        private Object[] root = EMPTY_NODE;
        private Object[] tail = new Object[WIDTH];
        private int tailLen;

        public Builder add(Object x) {
            if (tailLen == WIDTH) {
                if ((cnt >>> BITS) > (1 << shift)) {
                    Object[] newRoot = new Object[WIDTH];
                    newRoot[0] = root;
                    newRoot[1] = newPath(shift, tail);
                    root = newRoot;
                    shift += BITS;
                } else {
                    root = pushTail(cnt, shift, root, tail);
                }
                tail = new Object[WIDTH];
                tailLen = 0;
            }
            tail[tailLen++] = x;
            cnt++;
            return this;
        }

        public PVec build() {
            if (cnt == 0) return EMPTY;
            Object[] t = tailLen == WIDTH ? tail : Arrays.copyOf(tail, tailLen);
            PVec v = new PVec(cnt, shift, root, t, 0);
            tail = null; // the builder is done
            return v;
        }
    }
}
