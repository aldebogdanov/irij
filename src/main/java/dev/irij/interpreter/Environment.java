package dev.irij.interpreter;

import dev.irij.ast.Node.SourceLoc;

import java.util.HashMap;
import java.util.Map;

/**
 * Lexical scope environment with parent chain.
 * Supports immutable and mutable bindings.
 * Closures capture mutables by reference through shared MutableCell instances.
 */
public final class Environment {
    private final Environment parent;
    private final Map<String, Cell> bindings;

    // ── Cell types ──────────────────────────────────────────────────────

    public sealed interface Cell permits ImmutableCell, MutableCell {}

    public record ImmutableCell(Object value) implements Cell {}

    public static final class MutableCell implements Cell {
        private Object value;

        public MutableCell(Object value) {
            this.value = value;
        }

        public Object get() {
            return value;
        }

        public void set(Object value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return "MutableCell(" + value + ")";
        }
    }

    // ── Constructors ────────────────────────────────────────────────────

    public Environment() {
        this(null);
    }

    public Environment(Environment parent) {
        this.parent = parent;
        this.bindings = new HashMap<>();
    }

    // ── Operations ──────────────────────────────────────────────────────

    /** Create a child scope. */
    public Environment child() {
        return new Environment(this);
    }

    /** Define an immutable binding in this scope. */
    public void define(String name, Object value) {
        bindings.put(name, new ImmutableCell(value));
    }

    /** Define a mutable binding in this scope. */
    public void defineMut(String name, Object value) {
        bindings.put(name, new MutableCell(value));
    }

    /** Look up a variable by name, walking the parent chain. */
    public Object lookup(String name, SourceLoc loc) {
        Cell cell = lookupCell(name);
        if (cell == null) {
            throw new IrijRuntimeError("Undefined variable: " + name, loc);
        }
        return switch (cell) {
            case ImmutableCell(var v) -> v;
            case MutableCell mc -> mc.get();
        };
    }

    /** Look up a variable by name with no source location. */
    public Object lookup(String name) {
        return lookup(name, SourceLoc.UNKNOWN);
    }

    /** Assign to a mutable variable, walking the parent chain. */
    public void assign(String name, Object value, SourceLoc loc) {
        Cell cell = lookupCell(name);
        if (cell == null) {
            throw new IrijRuntimeError("Undefined variable: " + name, loc);
        }
        if (cell instanceof MutableCell mc) {
            mc.set(value);
        } else {
            throw new IrijRuntimeError("Cannot assign to immutable binding: " + name, loc);
        }
    }

    /** Check if a name is defined in this scope or any parent. */
    public boolean isDefined(String name) {
        return lookupCell(name) != null;
    }

    // ── Internal ────────────────────────────────────────────────────────

    private Cell lookupCell(String name) {
        Cell cell = bindings.get(name);
        if (cell != null) return cell;
        if (parent != null) return parent.lookupCell(name);
        return null;
    }
}
