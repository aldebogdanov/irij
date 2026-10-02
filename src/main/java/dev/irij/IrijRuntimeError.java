package dev.irij;

import dev.irij.ast.Node.SourceLoc;

/**
 * Runtime error with source location information.
 *
 * <p>Lives at the {@code dev.irij} root because both the interpreter
 * and the bytecode runtime ({@link dev.irij.compiler.RuntimeSupport})
 * throw it — naming it after either backend would be misleading. A
 * stack trace from a compiled program no longer mentions
 * {@code dev.irij.runtime} anywhere.
 */
public class IrijRuntimeError extends RuntimeException {
    private final SourceLoc loc;
    /** The value a program raised with {@code error v}, or null for an
     *  error the runtime raised. {@code try} and {@code on-failure} hand
     *  this back as is, so a raised map stays a map. */
    private final Object payload;

    public IrijRuntimeError(String message, SourceLoc loc) {
        super(formatMessage(message, loc));
        this.loc = loc;
        this.payload = null;
    }

    public IrijRuntimeError(String message) {
        this(message, SourceLoc.UNKNOWN);
    }

    /** An error carrying the value raised with {@code error v}; the message
     *  is that value's printed form. */
    public IrijRuntimeError(String message, Object payload) {
        super(message);
        this.loc = SourceLoc.UNKNOWN;
        this.payload = payload;
    }

    public SourceLoc getLoc() {
        return loc;
    }

    public Object payload() {
        return payload;
    }

    private static String formatMessage(String message, SourceLoc loc) {
        if (loc != null && loc.line() > 0) {
            return message + " at " + loc;
        }
        return message;
    }
}
