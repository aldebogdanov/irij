package dev.irij.nrepl;

import java.io.IOException;
import java.io.OutputStream;

/**
 * A thread-safe {@link OutputStream} that buffers all writes for later retrieval.
 *
 * <p>Used as the "idle" target of {@link IndirectOutputStream} between nREPL
 * evaluations. When a spawned virtual thread calls {@code println} after its
 * parent eval has returned, the output lands here instead of going to
 * {@code System.out}. The Emacs client polls for buffered output via the
 * {@code "background-out"} nREPL op.
 *
 * <p>All methods are synchronized so that multiple virtual threads can write
 * concurrently without interleaving bytes mid-line.
 */
public final class BackgroundOutputStream extends OutputStream {

    private final StringBuilder buffer = new StringBuilder();

    @Override
    public synchronized void write(int b) throws IOException {
        buffer.append((char) b);
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) throws IOException {
        buffer.append(new String(b, off, len));
    }

    @Override
    public synchronized void flush() {
        // Nothing to flush — data is in-memory.
    }

    /**
     * Drain all buffered output, resetting the buffer.
     *
     * @return accumulated output (may be empty, never null)
     */
    public synchronized String drain() {
        if (buffer.isEmpty()) return "";
        var result = buffer.toString();
        buffer.setLength(0);
        return result;
    }

    /**
     * @return true if there is buffered output waiting.
     */
    public synchronized boolean hasOutput() {
        return !buffer.isEmpty();
    }
}
