package dev.irij.runtime;

import dev.irij.compiler.RuntimeSessions;

/**
 * Capability provider for the {@code Session} effect — Playground
 * sandboxed eval. Thin shim over
 * {@link dev.irij.compiler.RuntimeSessions} (which holds the
 * session-id → BytecodeSession map + SSE wiring; that class lives
 * in the compiler package because it instantiates BytecodeSession
 * directly).
 *
 * <p>Bound from Irij with:
 * <pre>
 *   cap session-mgr :: Session = "dev.irij.runtime.SessionCapability"
 * </pre>
 */
public final class SessionCapability {

    private SessionCapability() {}

    public static Object create() {
        return RuntimeSessions.rawSessionCreate();
    }

    public static Object eval(Object idArg, Object codeArg, Object timeoutArg) {
        return RuntimeSessions.rawSessionEval(idArg, codeArg, timeoutArg);
    }

    public static Object destroy(Object idArg) {
        return RuntimeSessions.rawSessionDestroy(idArg);
    }

    public static Object subscribe(Object idArg, Object sseArg) {
        return RuntimeSessions.rawSessionSubscribe(idArg, sseArg);
    }

    public static Object unsubscribe(Object idArg) {
        return RuntimeSessions.rawSessionUnsubscribe(idArg);
    }

    public static Object cleanup(Object maxIdleArg) {
        return RuntimeSessions.rawSessionCleanup(maxIdleArg);
    }
}
