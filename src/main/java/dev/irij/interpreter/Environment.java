package dev.irij.interpreter;

import dev.irij.ast.Node.SourceLoc;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Lexical scope environment with parent chain.
 * Supports immutable, mutable, and var (top-level rebindable) bindings.
 *
 * <p>Cell types:
 * <ul>
 *   <li>{@link ImmutableCell} — local let-bindings and builtins (not reassignable)
 *   <li>{@link MutableCell} — {@code :!} mutable bindings (reassignable via {@code <-})
 *   <li>{@link VarCell} — top-level definitions (Clojure-style Var indirection;
 *       redefinition updates in-place so closures see the new value)
 * </ul>
 */
public final class Environment {
    private final Environment parent;
    private final Map<String, Cell> bindings;

    // ── Cell types ──────────────────────────────────────────────────────

    public sealed interface Cell permits ImmutableCell, MutableCell, VarCell {}

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

    /**
     * A rebindable top-level binding (analogous to Clojure's Var).
     *
     * <p>When a function is redefined at the REPL or via hot-reload,
     * {@code defineVar} updates the existing VarCell in-place. Closures
     * that captured a child of this environment resolve the name through
     * the parent chain at call time and see the updated value.
     *
     * <p>The {@code volatile} qualifier on {@code value} prepares for
     * future virtual-thread concurrency (nREPL sessions on separate threads).
     */
    public static final class VarCell implements Cell {
        private final String name;
        private volatile Object value;

        public VarCell(String name, Object value) {
            this.name = name;
            this.value = value;
        }

        public Object get() {
            return value;
        }

        public void set(Object value) {
            this.value = value;
        }

        public String name() {
            return name;
        }

        @Override
        public String toString() {
            return "VarCell(" + name + " = " + value + ")";
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

    /**
     * Define a top-level rebindable binding (Var).
     *
     * <p>If a {@link VarCell} already exists for this name in <em>this</em>
     * scope's bindings map, the value is updated in-place. This is the key
     * semantic: closures holding a child of this environment will see the
     * new value through parent-chain lookup.
     *
     * <p>If no VarCell exists yet, a new one is created.
     */
    public void defineVar(String name, Object value) {
        Cell existing = bindings.get(name);
        if (existing instanceof VarCell vc) {
            vc.set(value);
        } else {
            bindings.put(name, new VarCell(name, value));
        }
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
            case VarCell vc -> vc.get();
        };
    }

    /** Look up a variable by name with no source location. */
    public Object lookup(String name) {
        return lookup(name, SourceLoc.UNKNOWN);
    }

    /**
     * Assign to a mutable binding ({@code <-}), walking the parent chain.
     *
     * <p>Only {@link MutableCell} (created by {@code :!}) supports assignment.
     * {@link VarCell} bindings are rebindable via {@code defineVar} (a new
     * {@code :=} declaration), but not via the {@code <-} assignment operator
     * — they are semantically immutable from the user's perspective.
     */
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

    /** Whether this is the root (global) environment. */
    public boolean isRoot() {
        return parent == null;
    }

    /** Read-only view of this scope's bindings (does not include parents). */
    public Map<String, Cell> getBindings() {
        return Collections.unmodifiableMap(bindings);
    }

    /**
     * Create a new root environment containing only the named bindings from this scope.
     * Used for building module exports — copies only public names into a fresh env.
     */
    public Environment exportBindings(java.util.Set<String> names) {
        var exports = new Environment();
        for (var name : names) {
            Cell cell = lookupCell(name);
            if (cell != null) {
                Object value = switch (cell) {
                    case ImmutableCell(var v) -> v;
                    case MutableCell mc -> mc.get();
                    case VarCell vc -> vc.get();
                };
                exports.define(name, value);
            }
        }
        return exports;
    }

    /**
     * Copy all bindings from another environment into this one.
     * Used for :open imports.
     */
    public void copyAllFrom(Environment other) {
        for (var entry : other.bindings.entrySet()) {
            Object value = switch (entry.getValue()) {
                case ImmutableCell(var v) -> v;
                case MutableCell mc -> mc.get();
                case VarCell vc -> vc.get();
            };
            bindings.put(entry.getKey(), new ImmutableCell(value));
        }
    }

    // ── Internal ────────────────────────────────────────────────────────

    private Cell lookupCell(String name) {
        Cell cell = bindings.get(name);
        if (cell != null) return cell;
        if (parent != null) return parent.lookupCell(name);
        return null;
    }
}
