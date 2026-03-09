package dev.irij.interpreter;

/**
 * Controls enforcement of purity constraints on direct IO operations.
 *
 * <p>In Irij, side effects should be expressed through algebraic effect handlers.
 * Direct IO calls like {@code io.stdout.write} are low-level primitives that
 * bypass the effect system. This enum controls how strictly that is enforced.
 */
public enum PurityMode {
    /** Direct IO outside effect handlers is an error (default for compiled code). */
    STRICT,

    /** Direct IO outside effect handlers produces a warning but still runs. */
    WARN,

    /** No purity checks — direct IO is silently allowed (default for REPL). */
    ALLOW;

    /** Parse a CLI flag string into a PurityMode. */
    public static PurityMode fromFlag(String flag) {
        return switch (flag) {
            case "--strict" -> STRICT;
            case "--warn-impure" -> WARN;
            case "--allow-impure" -> ALLOW;
            default -> throw new IllegalArgumentException("Unknown purity flag: " + flag);
        };
    }
}
