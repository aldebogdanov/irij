package dev.irij.nrepl;

import java.io.IOException;
import java.io.OutputStream;

/**
 * An OutputStream wrapper whose target can be swapped at runtime.
 *
 * <p>This solves the "captured PrintStream" problem: builtins like
 * {@code println} close over a {@code PrintStream} at interpreter
 * construction time. By wrapping the underlying stream in an
 * IndirectOutputStream, we can redirect output per-evaluation
 * (e.g., to capture stdout in an nREPL session) without
 * reconstructing the interpreter.
 *
 * <p>Thread-safe: the {@code target} field is volatile, so swaps
 * are visible across virtual threads.
 */
public final class IndirectOutputStream extends OutputStream {
    private volatile OutputStream target;

    public IndirectOutputStream(OutputStream target) {
        this.target = target;
    }

    /** Swap the underlying output target. Returns the previous target. */
    public OutputStream setTarget(OutputStream newTarget) {
        var old = this.target;
        this.target = newTarget;
        return old;
    }

    public OutputStream getTarget() {
        return target;
    }

    @Override
    public void write(int b) throws IOException {
        target.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        target.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        target.flush();
    }

    @Override
    public void close() throws IOException {
        target.close();
    }
}
