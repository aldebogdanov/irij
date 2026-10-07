package dev.irij.compiler;

import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A discarded session's classes can be collected: nothing static (the
 * hot-redef call-site registry above all) may keep its classloader alive,
 * or a long-running Playground / nREPL leaks metaspace with every eval.
 */
class SessionClassUnloadingTest {

    private static WeakReference<ClassLoader> evalAndDrop() throws Exception {
        BytecodeSession s = new BytecodeSession("irij.UnloadProbe");
        // A top-level fn call bootstraps a hot-redef call site (dev
        // linking), which is what used to pin the loader.
        s.eval("fn twice\n  (x -> x * 2)\nprobe := (y -> twice y)\nprobe 21", "probe", null);
        var f = BytecodeSession.class.getDeclaredField("loader");
        f.setAccessible(true);
        return new WeakReference<>((ClassLoader) f.get(s));
    }

    @Test void discardedSessionLoaderIsCollected() throws Exception {
        WeakReference<ClassLoader> loader = evalAndDrop();
        for (int i = 0; i < 50 && loader.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertNull(loader.get(), "the session's classloader was never collected");
    }
}
