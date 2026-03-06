package dev.irij.interpreter;

import java.util.List;
import java.util.Objects;

/**
 * A tagged value — runtime representation of algebraic data type variants.
 *
 * <p>Examples: {@code Ok 42}, {@code Err "not found"}, {@code Some "hello"}, {@code None}
 */
public record Tagged(String tag, List<Object> fields) {

    public Tagged(String tag) {
        this(tag, List.of());
    }

    public Tagged(String tag, Object field) {
        this(tag, List.of(field));
    }

    public Object field(int i) {
        return i < fields.size() ? fields.get(i) : IrijInterpreter.UNIT;
    }

    public boolean is(String name) {
        return tag.equals(name);
    }

    @Override
    public String toString() {
        if (fields.isEmpty()) return tag;
        if (fields.size() == 1) return tag + " " + Stdlib.show(fields.get(0));
        var sb = new StringBuilder(tag);
        for (var f : fields) {
            sb.append(' ').append(Stdlib.show(f));
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Tagged t)) return false;
        return tag.equals(t.tag) && fields.equals(t.fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tag, fields);
    }
}
