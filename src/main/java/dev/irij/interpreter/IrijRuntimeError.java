package dev.irij.interpreter;

/**
 * Runtime error during Irij program execution.
 */
public final class IrijRuntimeError extends RuntimeException {
    public IrijRuntimeError(String message) {
        super(message);
    }

    public IrijRuntimeError(String message, Throwable cause) {
        super(message, cause);
    }
}
