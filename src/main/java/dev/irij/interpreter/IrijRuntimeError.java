package dev.irij.interpreter;

import dev.irij.ast.Node.SourceLoc;

/**
 * Runtime error with source location information.
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
