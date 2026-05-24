package dev.irij.compiler;

/**
 * Test-only capability provider for {@link CapDispatchTest}. Exposes
 * two static methods reachable via {@code test-counter.bump ()} and
 * {@code test-counter.add x y} from Irij code, plus a state reset
 * hook used by the test's {@code @BeforeEach}.
 *
 * <p>Production capability providers will live in
 * {@code dev.irij.runtime} (e.g. {@code JdbcCapability},
 * {@code FsCapability}). Phase 2's emitter contract: methods are
 * {@code public static}, take + return {@link Object} (or an
 * autoboxable primitive), and live on a class with a stable FQN that
 * a {@code cap} decl can reference as a string literal.
 */
public final class TestCounterCapability {

    private TestCounterCapability() {}

    private static long counter = 0;

    /** Reset between tests so independent test runs don't share state. */
    public static void reset() { counter = 0; }

    public static long currentValue() { return counter; }

    /** {@code test-counter.bump ()} → next counter value. */
    public static Object bump() {
        counter += 1;
        return counter;
    }

    /** {@code test-counter.add x y} → x + y (boxed Long arithmetic). */
    public static Object add(Object x, Object y) {
        long a = ((Number) x).longValue();
        long b = ((Number) y).longValue();
        return a + b;
    }
}
