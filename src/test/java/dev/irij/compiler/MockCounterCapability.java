package dev.irij.compiler;

/**
 * Companion to {@link TestCounterCapability} — second provider used
 * by {@link CapDispatchTest#multipleCapsPerEffectPickSeparateProviders}
 * to verify that two caps for the same effect dispatch to different
 * classes. Always returns 9999 from {@code bump} so the test can tell
 * the mock and real caps apart in stdout.
 */
public final class MockCounterCapability {

    private MockCounterCapability() {}

    public static Object bump() {
        return 9999L;
    }
}
