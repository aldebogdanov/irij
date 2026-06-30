package dev.irij.runtime;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal virtual-thread-per-connection HTTP/1.1 server — the backing for
 * the {@code Serve} effect.
 *
 * <p>Replaces {@code com.sun.net.httpserver.HttpServer}, whose single
 * selector dispatcher thread <b>wedges on JDK 25</b> when a client
 * disconnects from an SSE / streaming response: it stops accepting any new
 * connection server-wide until restart (reproduced; fixed in JDK 26 but
 * 26 isn't packaged for the deploy). Here each accepted connection runs on
 * its <i>own</i> virtual thread doing plain blocking I/O, so a dead peer
 * only ends that one thread — nothing global can stall.
 *
 * <p>Responses are {@code Connection: close} (one request per connection).
 * Behind a pooling reverse proxy (Caddy) the cost is negligible, and it
 * removes the whole class of keep-alive framing / request-smuggling
 * hazards a hand-rolled server would otherwise have to get exactly right.
 */
public final class IrijHttpServer {

    private IrijHttpServer() {}

    private static final int MAX_LINE = 16 * 1024;          // request line / header line
    private static final int MAX_HEADER_BYTES = 64 * 1024;  // total header section
    private static final long MAX_BODY = 256L * 1024 * 1024; // body cap (tarball uploads)

    /** Per-connection handler. Allowed to throw — the server turns any
     *  throwable into a 500 (if the response isn't committed yet) and
     *  closes the connection. */
    @FunctionalInterface
    public interface ConnHandler {
        void handle(IrijExchange exchange) throws Exception;
    }

    /** Bind {@code port} and serve forever (blocks the calling thread on
     *  the accept loop), one virtual thread per connection. */
    public static void serve(int port, ConnHandler handler) throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(port));
        System.out.println("Irij HTTP server listening on http://localhost:" + port);
        while (true) {
            final Socket sock;
            try {
                sock = ss.accept();
            } catch (IOException e) {
                if (ss.isClosed()) break;
                continue;
            }
            Thread.ofVirtual().name("irij-http-conn").start(() -> handleConnection(sock, handler));
        }
    }

    private static void handleConnection(Socket sock, ConnHandler handler) {
        try {
            sock.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(sock.getInputStream(), 16 * 1024);
            OutputStream out = new BufferedOutputStream(sock.getOutputStream(), 16 * 1024);
            IrijExchange ex = parse(in, out);
            if (ex == null) return; // malformed / empty — just drop the connection
            try {
                handler.handle(ex);
                ex.finish();
            } catch (Exception e) {
                ex.fail(e);
            }
        } catch (IOException ignored) {
            // peer reset / I/O error on this connection only
        } finally {
            try { sock.close(); } catch (IOException ignored) {}
        }
    }

    private static IrijExchange parse(InputStream in, OutputStream out) throws IOException {
        String requestLine = readLine(in, MAX_LINE);
        if (requestLine == null || requestLine.isEmpty()) return null;
        String[] parts = requestLine.split(" ", 3);
        if (parts.length < 3) return null;
        String method = parts[0];
        String target = parts[1];

        IrijHeaders headers = new IrijHeaders();
        int headerBytes = 0;
        String line;
        while ((line = readLine(in, MAX_LINE)) != null && !line.isEmpty()) {
            headerBytes += line.length() + 2;
            if (headerBytes > MAX_HEADER_BYTES) return null;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            headers.add(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }

        byte[] body = new byte[0];
        String cl = headers.getFirst("Content-Length");
        if (cl != null) {
            long len;
            try { len = Long.parseLong(cl.trim()); }
            catch (NumberFormatException e) { return null; }
            if (len < 0 || len > MAX_BODY) return null;
            body = in.readNBytes((int) len);
            if (body.length != len) return null; // truncated request
        }

        URI uri;
        try { uri = new URI(target); }
        catch (URISyntaxException e) { uri = URI.create("/"); }
        return new IrijExchange(method, uri, headers, body, out);
    }

    /** Read one CRLF- (or LF-) terminated line as ISO-8859-1, sans
     *  terminator. Returns null at EOF with no bytes, or if it exceeds
     *  {@code max}. */
    private static String readLine(InputStream in, int max) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int c;
        int n = 0;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            buf.write(c);
            if (++n > max) return null;
        }
        if (c == -1 && buf.size() == 0) return null;
        byte[] b = buf.toByteArray();
        int len = b.length;
        if (len > 0 && b[len - 1] == '\r') len--; // strip trailing CR
        return new String(b, 0, len, StandardCharsets.ISO_8859_1);
    }

    // ── Headers (case-insensitive, com.sun-style canonical keys) ────────

    /** Multi-valued, case-insensitive header map. Keys are canonicalised
     *  to {@code Title-Case-Dash} (like {@code com.sun.net.httpserver
     *  .Headers}) so request-handler code that lowercases, and our own
     *  {@code getFirst("Content-Length")}, both work. */
    public static final class IrijHeaders extends LinkedHashMap<String, List<String>> {
        public void add(String key, String value) {
            computeIfAbsent(canonical(key), k -> new ArrayList<>()).add(value);
        }
        public void set(String key, String value) {
            List<String> l = new ArrayList<>(1);
            l.add(value);
            put(canonical(key), l);
        }
        public String getFirst(String key) {
            List<String> l = get(canonical(key));
            return (l == null || l.isEmpty()) ? null : l.get(0);
        }
        public boolean has(String key) { return containsKey(canonical(key)); }

        private static String canonical(String key) {
            if (key.isEmpty()) return key;
            char[] c = key.toLowerCase().toCharArray();
            boolean up = true;
            for (int i = 0; i < c.length; i++) {
                if (up && Character.isLetter(c[i])) { c[i] = Character.toUpperCase(c[i]); up = false; }
                if (c[i] == '-') up = true;
            }
            return new String(c);
        }
    }

    // ── Exchange (mirrors the small HttpExchange surface we use) ────────

    public static final class IrijExchange {
        private final String method;
        private final URI uri;
        private final IrijHeaders reqHeaders;
        private final byte[] body;
        private final OutputStream out;
        private final IrijHeaders respHeaders = new IrijHeaders();
        private final Map<String, Object> attrs = new HashMap<>();
        private boolean committed = false;

        IrijExchange(String method, URI uri, IrijHeaders reqHeaders, byte[] body, OutputStream out) {
            this.method = method;
            this.uri = uri;
            this.reqHeaders = reqHeaders;
            this.body = body;
            this.out = out;
        }

        public String getRequestMethod() { return method; }
        public URI getRequestURI() { return uri; }
        public IrijHeaders getRequestHeaders() { return reqHeaders; }
        public InputStream getRequestBody() { return new ByteArrayInputStream(body); }
        public IrijHeaders getResponseHeaders() { return respHeaders; }
        public OutputStream getResponseBody() { return out; }
        public Object getAttribute(String k) { return attrs.get(k); }
        public void setAttribute(String k, Object v) { attrs.put(k, v); }
        public boolean isCommitted() { return committed; }

        /**
         * Write the status line + headers. Body framing by {@code len}:
         * {@code len > 0} → fixed {@code Content-Length}; {@code len == 0}
         * → streaming (SSE: no Content-Length, caller writes raw and the
         * connection close is the terminator); {@code len < 0} → no body
         * ({@code Content-Length: 0}). Idempotent — a second call is a
         * no-op, so SSE-promoted exchanges never double-send headers.
         */
        public void sendResponseHeaders(int status, long len) throws IOException {
            if (committed) return;
            committed = true;
            boolean streaming = (len == 0);
            if (!streaming) {
                respHeaders.set("Content-Length", Long.toString(len < 0 ? 0 : len));
            }
            respHeaders.set("Connection", "close");
            StringBuilder sb = new StringBuilder(256);
            sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
            for (Map.Entry<String, List<String>> e : respHeaders.entrySet()) {
                for (String v : e.getValue()) {
                    sb.append(e.getKey()).append(": ").append(v).append("\r\n");
                }
            }
            sb.append("\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (streaming) out.flush(); // SSE clients need headers immediately
        }

        /** Normal completion: ensure headers were sent, then flush. */
        void finish() throws IOException {
            if (!committed) sendResponseHeaders(204, -1);
            out.flush();
        }

        /** Turn an uncaught handler error into a 500 if nothing's committed. */
        void fail(Exception e) {
            try {
                if (!committed) {
                    byte[] msg = ("Internal Server Error: " + e.getMessage())
                            .getBytes(StandardCharsets.UTF_8);
                    sendResponseHeaders(500, msg.length);
                    out.write(msg);
                }
                out.flush();
            } catch (IOException ignored) {
            }
            System.err.println("HTTP 500 " + method + " " + uri + ": " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    private static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 303 -> "See Other";
            case 304 -> "Not Modified";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 500 -> "Internal Server Error";
            default -> "Status";
        };
    }
}
