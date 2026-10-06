package dev.irij.runtime;


import java.util.*;
import java.util.function.Function;

/**
 * Runtime value types shared by compiled Irij programs.
 *
 * Primitive values use Java boxed types:
 *   Int → Long, Float → Double, Bool → Boolean, Str → String
 *
 * Complex values use the records defined here.
 */
public final class Values {

    private Values() {} // utility class

    // ── Unit singleton ──────────────────────────────────────────────────

    public static final Object UNIT = new Object() {
        @Override public String toString() { return "()"; }
        @Override public int hashCode() { return 0; }
        @Override public boolean equals(Object o) { return o == this; }
    };

    // ── Rational number ─────────────────────────────────────────────────

    /** An exact fraction: lowest terms, positive denominator, never an
     *  integer (build with {@code RtNum.ratio}, which yields an Int for a
     *  whole number). Prints as {@code 2/3}. */
    public record Rational(java.math.BigInteger num, java.math.BigInteger den) {
        public Rational {
            if (den.signum() == 0) throw new ArithmeticException("Rational with zero denominator");
            if (den.signum() < 0) { num = num.negate(); den = den.negate(); }
            var g = num.gcd(den);
            if (g.signum() != 0 && !g.equals(java.math.BigInteger.ONE)) { num = num.divide(g); den = den.divide(g); }
        }

        public double toDouble() {
            if (num.bitLength() < 53 && den.bitLength() < 53) return num.doubleValue() / den.doubleValue();
            return new java.math.BigDecimal(num)
                    .divide(new java.math.BigDecimal(den), java.math.MathContext.DECIMAL64).doubleValue();
        }

        @Override
        public String toString() {
            return num + "/" + den;
        }
    }

    // ── Keyword atom ────────────────────────────────────────────────────

    public record Keyword(String name) {
        @Override
        public String toString() {
            return ":" + name;
        }
    }

    // ── Tagged value (ADT constructor) ──────────────────────────────────

    /**
     * A tagged value (ADT variant or product spec instance).
     * Sum specs use positional fields; product specs also carry namedFields.
     * specName is the certification tag — non-null means "validated by spec X".
     */
    public record Tagged(String tag, List<Object> fields, Map<String, Object> namedFields, String specName) {
        /** Convenience constructor for sum types (positional only, uncertified). */
        public Tagged(String tag, List<Object> fields) {
            this(tag, fields, null, null);
        }
        /** Convenience constructor for sum types (positional, with certification). */
        public Tagged(String tag, List<Object> fields, String specName) {
            this(tag, fields, null, specName);
        }
        /** Convenience constructor for product types (uncertified). */
        public Tagged(String tag, List<Object> fields, Map<String, Object> namedFields) {
            this(tag, fields, namedFields, null);
        }

        @Override
        public String toString() {
            if (namedFields != null) {
                // Product type: Person {name= "Jo" age= 42}
                if (namedFields.isEmpty()) return tag;
                var sb = new StringBuilder(tag);
                sb.append(" {");
                boolean first = true;
                for (var e : namedFields.entrySet()) {
                    if (!first) sb.append(' ');
                    first = false;
                    sb.append(e.getKey()).append("= ").append(Values.toIrijString(e.getValue()));
                }
                sb.append('}');
                return sb.toString();
            }
            // Sum type: Some 42
            if (fields.isEmpty()) return tag;
            var sb = new StringBuilder(tag);
            for (var f : fields) {
                sb.append(' ');
                if (f instanceof String s) {
                    sb.append('"').append(s).append('"');
                } else {
                    sb.append(Values.toIrijString(f));
                }
            }
            return sb.toString();
        }
    }

    // ── Collections ─────────────────────────────────────────────────────

    /** A Vector. Its elements are a {@link PVec} — persistent, so
     *  {@code conj} / {@code tail} share structure instead of copying. */
    public record IrijVector(List<Object> elements) {
        public IrijVector {
            elements = PVec.from(elements);
        }

        @Override
        public String toString() {
            var sb = new StringBuilder("#[");
            for (int i = 0; i < elements.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(Values.toIrijString(elements.get(i)));
            }
            sb.append(']');
            return sb.toString();
        }
    }

    /** A Set. Its elements are a {@link PSet} — persistent, so
     *  {@code conj} shares structure instead of copying. */
    public record IrijSet(Set<Object> elements) {
        public IrijSet {
            elements = PSet.from(elements);
        }

        @Override
        public String toString() {
            var sb = new StringBuilder("#{");
            boolean first = true;
            for (var e : elements) {
                if (!first) sb.append(' ');
                first = false;
                sb.append(Values.toIrijString(e));
            }
            sb.append('}');
            return sb.toString();
        }
    }

    /** A Map with Str keys, in insertion order. Its entries are a
     *  {@link PMap} — persistent, so {@code assoc} / {@code dissoc} share
     *  structure instead of copying. */
    public record IrijMap(Map<String, Object> entries) {
        public IrijMap {
            entries = PMap.from(entries);
        }

        @Override
        public String toString() {
            var sb = new StringBuilder("{");
            boolean first = true;
            for (var e : entries.entrySet()) {
                if (!first) sb.append(' ');
                first = false;
                sb.append(e.getKey()).append("= ").append(Values.toIrijString(e.getValue()));
            }
            sb.append('}');
            return sb.toString();
        }
    }

    /**
     * SSE (Server-Sent Events) writer — wraps the connection's output
     * stream for streaming. The stream is kept open; calling send() writes
     * SSE-formatted events. Closing it closes the underlying connection
     * (the {@code IrijHttpServer} connection is Connection: close).
     */
    public static final class SseWriter {
        private final java.io.OutputStream outputStream;
        private volatile boolean closed = false;

        public SseWriter(java.io.OutputStream os) {
            this.outputStream = os;
        }

        /** Send a single SSE event with optional event type and multi-line data. */
        public synchronized void send(String eventType, String data) throws java.io.IOException {
            if (closed) throw new java.io.IOException("SSE stream closed");
            var sb = new StringBuilder();
            if (eventType != null && !eventType.isEmpty()) {
                if (eventType.indexOf('\n') >= 0 || eventType.indexOf('\r') >= 0) {
                    throw new java.io.IOException("SSE event type contains a line break");
                }
                sb.append("event: ").append(eventType).append('\n');
            }
            // Each line of data gets its own "data: " prefix. SSE ends a
            // line at CR, LF or CRLF alike, so all three must split here —
            // a bare CR left inside a data line would let the payload end
            // the event early and start one of its own.
            for (var line : data.split("\r\n|\r|\n", -1)) {
                sb.append("data: ").append(line).append('\n');
            }
            sb.append('\n'); // blank line terminates event
            outputStream.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            outputStream.flush();
        }

        /** Write an SSE comment line (":\n\n") and flush. A no-op payload
         *  the client ignores, but the flush touches the socket — so on a
         *  disconnected peer it throws {@link java.io.IOException}, which is
         *  how a long-lived stream detects the client is gone. */
        public synchronized void heartbeat() throws java.io.IOException {
            if (closed) throw new java.io.IOException("SSE stream closed");
            outputStream.write(":\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            outputStream.flush();
        }

        public synchronized void close() {
            if (!closed) {
                closed = true;
                notifyAll();
                try { outputStream.flush(); } catch (Exception ignored) {}
                try { outputStream.close(); } catch (Exception ignored) {}
            }
        }

        public boolean isClosed() { return closed; }

        /** Block until the writer is closed or {@code ms} elapse; true
         *  once closed. Lets the stream's owner sleep between heartbeats
         *  yet wake the moment anyone closes the stream. */
        public synchronized boolean awaitClosed(long ms) throws InterruptedException {
            long deadline = System.nanoTime() + ms * 1_000_000L;
            while (!closed) {
                long left = (deadline - System.nanoTime()) / 1_000_000L;
                if (left <= 0) return false;
                wait(left);
            }
            return true;
        }

        @Override
        public String toString() { return "<SseWriter>"; }
    }

    public record IrijTuple(Object[] elements) {
        @Override
        public boolean equals(Object o) {
            return o instanceof IrijTuple t && Arrays.equals(elements, t.elements);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(elements);
        }

        @Override
        public String toString() {
            var sb = new StringBuilder("#(");
            for (int i = 0; i < elements.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(Values.toIrijString(elements[i]));
            }
            sb.append(')');
            return sb.toString();
        }
    }

    // ── Range (lazy iterable) ───────────────────────────────────────────

    public record IrijRange(long from, long to, boolean exclusive) implements Iterable<Object> {
        @Override
        public Iterator<Object> iterator() {
            long end = exclusive ? to : to + 1;
            return new Iterator<>() {
                long current = from;

                @Override
                public boolean hasNext() {
                    return current < end;
                }

                @Override
                public Object next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    return current++;
                }
            };
        }

        public int size() {
            long end = exclusive ? to : to + 1;
            return (int) Math.max(0, end - from);
        }

        @Override
        public String toString() {
            return from + (exclusive ? " ..< " : " .. ") + to;
        }
    }

    // ── Builtin function ────────────────────────────────────────────────

    public record BuiltinFn(String name, int arity, List<String> requiredEffects,
                            Function<List<Object>, Object> impl) {
        /** Convenience constructor for builtins with no effect requirements. */
        public BuiltinFn(String name, int arity, Function<List<Object>, Object> impl) {
            this(name, arity, List.of(), impl);
        }

        public Object apply(List<Object> args) {
            return impl.apply(args);
        }

        @Override
        public String toString() {
            return "<builtin " + name + ">";
        }
    }

    // ── Value utilities ─────────────────────────────────────────────────

    /** Convert a runtime value to its Irij string representation. */
    public static String toIrijString(Object value) {
        if (value == null) return "()";
        if (value == UNIT) return "()";
        if (value instanceof Long l) return l.toString();
        if (value instanceof Double d) {
            // Show integers without decimal point if possible
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.valueOf(d);
            }
            return String.valueOf(d);
        }
        if (value instanceof Boolean b) return b.toString();
        if (value instanceof String s) return s;
        if (value instanceof Thread t) return "<thread " + t.threadId() + ">";
        return value.toString();
    }

    /** Check if a value is truthy. */
    public static boolean isTruthy(Object value) {
        if (value == null || value == UNIT) return false;
        if (value instanceof Boolean b) return b;
        // Everything else is truthy
        return true;
    }

    /** Get a human-readable type name for error messages. */
    public static String typeName(Object value) {
        if (value == null || value == UNIT) return "Unit";
        if (value instanceof Long || value instanceof java.math.BigInteger) return "Int";
        if (value instanceof Double) return "Float";
        if (value instanceof Rational) return "Rational";
        if (value instanceof Boolean) return "Bool";
        if (value instanceof String) return "Str";
        if (value instanceof Keyword) return "Keyword";
        if (value instanceof IrijVector) return "Vector";
        if (value instanceof IrijMap) return "Map";
        if (value instanceof IrijSet) return "Set";
        if (value instanceof IrijTuple) return "Tuple";
        if (value instanceof IrijRange) return "Range";
        if (value instanceof Tagged t) return t.tag();
        if (value instanceof BuiltinFn) return "BuiltinFn";
        if (value instanceof Thread) return "Thread";
        return value.getClass().getSimpleName();
    }
}
