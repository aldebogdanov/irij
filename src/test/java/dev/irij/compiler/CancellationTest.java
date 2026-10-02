package dev.irij.compiler;

import dev.irij.IrijRuntimeError;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cooperative cancellation: an interrupted fiber stops at its next
 * cancellation point (a loop back-edge, a sleep) instead of running on
 * after `timeout` / `race` / a scope has given up on it.
 */
class CancellationTest {

    @Test void timeoutStopsALoopingFiber() throws Exception {
        CountDownLatch stopped = new CountDownLatch(1);
        RuntimeSupport.IrijFn spin = args -> {
            try {
                while (true) RtConcurrency.checkCancelled();
            } finally {
                stopped.countDown();
            }
        };
        assertThrows(IrijRuntimeError.class, () -> RtConcurrency.timeout(50L, spin));
        assertTrue(stopped.await(5, TimeUnit.SECONDS), "the timed-out fiber kept running");
    }

    @Test void interruptedSleepEndsTheFiberInsteadOfReturning() throws Exception {
        CountDownLatch stopped = new CountDownLatch(1);
        RuntimeSupport.IrijFn sleeper = args -> {
            try {
                // Before the fix every sleep after the interrupt returned
                // at once and this loop spun forever.
                while (true) RtConcurrency.sleep(20L);
            } finally {
                stopped.countDown();
            }
        };
        RuntimeSupport.IrijFn quick = args -> "fast";
        assertEquals("fast", RtConcurrency.race(new Object[]{sleeper, quick}));
        assertTrue(stopped.await(5, TimeUnit.SECONDS), "the losing fiber kept sleeping");
    }

    @Test void cancellationIsSticky() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(IrijRuntimeError.class, RtConcurrency::checkCancelled);
            // A handler that swallowed the first error is stopped again.
            assertThrows(IrijRuntimeError.class, RtConcurrency::checkCancelled);
        } finally {
            Thread.interrupted();
        }
    }

    @Test void uninterruptedCodeIsUnaffected() {
        assertDoesNotThrow(RtConcurrency::checkCancelled);
    }
}
