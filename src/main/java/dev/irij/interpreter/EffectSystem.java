package dev.irij.interpreter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.SynchronousQueue;

/**
 * Algebraic effect system runtime.
 *
 * <p>Each thread maintains a stack of {@link HandlerContext}s via a {@code ThreadLocal}.
 * When an effect operation is performed (e.g., {@code print "hello"}), the stack is
 * scanned top-down for a handler matching the effect. Communication between the body
 * thread and handler thread uses {@link SynchronousQueue}s.
 *
 * <p>Continuation model: one-shot, explicit resume, deep handlers.
 * Body runs in a VirtualThread; handler loop runs on the calling thread.
 */
public final class EffectSystem {

    private EffectSystem() {} // utility class

    // ── Handler stack (per-thread) ───────────────────────────────────────

    /**
     * Thread-local stack of active handler contexts.
     * Pushed when entering a {@code with} block's body thread.
     * Scanned top-down (LIFO) when an effect op fires.
     */
    public static final ThreadLocal<Deque<HandlerContext>> STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    // ── Handler context ──────────────────────────────────────────────────

    /**
     * A handler context installed on the effect stack.
     *
     * @param effectName  the effect being handled (e.g., "Console")
     * @param handler     the handler value with clause implementations
     * @param opChannel   body→handler communication channel
     */
    public record HandlerContext(
            String effectName,
            Object handler,  // HandlerValue from Values.java
            SynchronousQueue<EffectMessage> opChannel
    ) {}

    // ── Messages between body thread and handler thread ──────────────────

    /**
     * Messages sent from the body thread to the handler thread via opChannel.
     */
    public sealed interface EffectMessage {
        /** Body completed successfully with a value. */
        record Done(Object value) implements EffectMessage {}

        /** Body threw an error. */
        record Err(Throwable error) implements EffectMessage {}

        /** Body performed an effect operation and is waiting for resume. */
        record Op(String opName, List<Object> args,
                  SynchronousQueue<Object> resumeChannel) implements EffectMessage {}
    }

    // ── Fire an effect operation ─────────────────────────────────────────

    /**
     * Called by effect op functions when invoked inside a {@code with} block.
     * Scans the thread-local handler stack for a matching effect, sends an
     * {@link EffectMessage.Op} to the handler, and blocks until resumed.
     *
     * @param effectName  the effect to look for (e.g., "Console")
     * @param opName      the operation name (e.g., "print")
     * @param args        the operation arguments
     * @return the value provided by {@code resume v} in the handler arm
     * @throws IrijRuntimeError if no handler is found on the stack
     */
    public static Object fireOp(String effectName, String opName, List<Object> args) {
        Deque<HandlerContext> stack = STACK.get();

        // Scan stack top-down for matching effect
        for (HandlerContext ctx : stack) {
            if (ctx.effectName().equals(effectName)) {
                try {
                    var resumeChannel = new SynchronousQueue<Object>();
                    ctx.opChannel().put(new EffectMessage.Op(opName, args, resumeChannel));
                    return resumeChannel.take(); // block until handler resumes
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IrijRuntimeError("Effect operation interrupted: " + opName);
                }
            }
        }

        throw new IrijRuntimeError("Unhandled effect: " + effectName + "." + opName
                + " (no handler on stack)");
    }
}
