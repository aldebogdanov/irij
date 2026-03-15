package dev.irij.nrepl;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * nREPL-compatible TCP server for Irij.
 *
 * <p>Implements a subset of the nREPL protocol using bencode as the
 * wire format. Editors that speak nREPL (CIDER, vim-fireplace, Conjure,
 * etc.) can connect and evaluate Irij code interactively.
 *
 * <p>Each connection is handled on a virtual thread (Java 21+).
 * Sessions are independent — each has its own {@link NReplSession}
 * with a separate interpreter and environment.
 *
 * <p>On startup, writes a {@code .nrepl-port} file in the current
 * directory (editor convention for auto-discovery). The file is
 * deleted on shutdown.
 *
 * <h3>Usage</h3>
 * <pre>
 *   var server = new NReplServer(7888);
 *   server.start(); // blocks
 * </pre>
 *
 * <h3>Supported ops</h3>
 * <ul>
 *   <li>{@code clone} — create a new session
 *   <li>{@code eval} — evaluate code in a session
 *   <li>{@code describe} — server capabilities
 *   <li>{@code close} — close a session
 * </ul>
 */
public final class NReplServer {

    private final int port;
    private final Map<String, NReplSession> sessions = new ConcurrentHashMap<>();
    private volatile boolean running;
    private ServerSocket serverSocket;

    /**
     * @param port TCP port to listen on. Use 0 for a random available port.
     */
    public NReplServer(int port) {
        this.port = port;
    }

    /**
     * Start the server. Blocks until {@link #stop()} is called.
     */
    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;

        int actualPort = serverSocket.getLocalPort();

        // Write .nrepl-port file (editor convention)
        try {
            Files.writeString(Path.of(".nrepl-port"), String.valueOf(actualPort));
        } catch (IOException e) {
            System.err.println("Warning: could not write .nrepl-port: " + e.getMessage());
        }

        System.out.println("nREPL server started on port " + actualPort);
        System.out.println("  Connect with: telnet localhost " + actualPort);
        System.out.println("  Or configure your editor to connect to port " + actualPort);

        try {
            while (running) {
                try {
                    var socket = serverSocket.accept();
                    Thread.startVirtualThread(() -> handleConnection(socket));
                } catch (IOException e) {
                    if (running) {
                        System.err.println("Accept error: " + e.getMessage());
                    }
                    // If !running, the socket was closed by stop() — normal shutdown
                }
            }
        } finally {
            cleanup();
        }
    }

    /**
     * Stop the server.
     */
    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Get the actual port the server is listening on.
     * Useful when constructed with port 0 (random).
     */
    public int getPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : port;
    }

    // ── Connection handling ─────────────────────────────────────────────

    private void handleConnection(Socket socket) {
        try (socket;
             var in = new BufferedInputStream(socket.getInputStream());
             var out = new BufferedOutputStream(socket.getOutputStream())) {

            while (!socket.isClosed() && running) {
                var msg = Bencode.decodeMap(in);
                if (msg == null) break; // EOF

                var response = processMessage(msg);
                Bencode.encodeMap(response, out);
                out.flush();
            }
        } catch (IOException e) {
            // Connection closed or read error — normal
        }
    }

    private Map<String, Object> processMessage(Map<String, Object> msg) {
        String op = (String) msg.get("op");
        String msgId = objectToString(msg.get("id"));
        String sessionId = objectToString(msg.get("session"));

        // Clone: create a new session
        if ("clone".equals(op)) {
            var newSession = new NReplSession();
            sessions.put(newSession.id(), newSession);
            var resp = new LinkedHashMap<String, Object>();
            if (msgId != null) resp.put("id", msgId);
            resp.put("new-session", newSession.id());
            resp.put("status", List.of("done"));
            return resp;
        }

        // Find or auto-create session
        NReplSession session = sessionId != null ? sessions.get(sessionId) : null;
        if (session == null) {
            session = new NReplSession();
            sessions.put(session.id(), session);
        }

        // Dispatch to session
        var resp = new LinkedHashMap<>(session.handleOp(msg));
        if (msgId != null) resp.put("id", msgId);
        resp.put("session", session.id());

        // Clean up closed sessions
        if (session.isClosed()) {
            sessions.remove(session.id());
        }

        return resp;
    }

    private static String objectToString(Object obj) {
        return obj != null ? obj.toString() : null;
    }

    // ── Cleanup ─────────────────────────────────────────────────────────

    private void cleanup() {
        try {
            Files.deleteIfExists(Path.of(".nrepl-port"));
        } catch (IOException ignored) {
        }
    }
}
