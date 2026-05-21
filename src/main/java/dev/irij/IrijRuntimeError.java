package dev.irij;

import dev.irij.ast.Node.SourceLoc;

/**
 * Runtime error with source location information.
 *
 * <p>Lives at the {@code dev.irij} root because both the interpreter
 * and the bytecode runtime ({@link dev.irij.compiler.RuntimeSupport})
 * throw it — naming it after either backend would be misleading. A
 * stack trace from a compiled program no longer mentions
 * {@code dev.irij.interpreter} anywhere.
 */
public class IrijRuntimeError extends RuntimeException {
    private final SourceLoc loc;

    public IrijRuntimeError(String message, SourceLoc loc) {
        super(formatMessage(message, loc));
        this.loc = loc;
    }

    public IrijRuntimeError(String message) {
        this(message, SourceLoc.UNKNOWN);
    }

    public SourceLoc getLoc() {
        return loc;
    }

    private static String formatMessage(String message, SourceLoc loc) {
        if (loc != null && loc.line() > 0) {
            return message + " at " + loc;
        }
        return message;
    }
}
