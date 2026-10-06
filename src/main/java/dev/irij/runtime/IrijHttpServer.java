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
    /** Request body cap (tarball uploads). Bodies are buffered whole, so
     *  this times the number of concurrent uploads bounds their memory;
     *  {@code -Dirij.http.max.body=<bytes>} lowers it for a small heap. */
    private static final long MAX_BODY = Long.getLong("irij.http.max.body", 256L * 1024 * 1024);
    /** How long a client may take to send its whole request. Without a
     *  read timeout a client that connects and never sends (slowloris)
     *  holds a socket forever; enough of them exhaust file descriptors. */
    private static final int REQUEST_READ_TIMEOUT_MS =
            Integer.getInteger("irij.http.read.timeout.ms", 30_000);

    /** A request the server refuses before any handler runs; carries
     *  the status the client is told. */
    private static final class BadRequest extends IOException {
        final int status;
        BadRequest(int status, String msg) { super(msg); this.status = status; }
    }

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
                // EMFILE and friends fail every accept until a socket is
                // freed; back off instead of spinning a core on it.
                try { Thread.sleep(50); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                continue;
            }
            Thread.ofVirtual().name("irij-http-conn").start(() -> handleConnection(sock, handler));
        }
    }

    private static void handleConnection(Socket sock, ConnHandler handler) {
        try {
            sock.setTcpNoDelay(true);
            sock.setSoTimeout(REQUEST_READ_TIMEOUT_MS);
            InputStream in = new BufferedInputStream(sock.getInputStream(), 16 * 1024);
            OutputStream out = new BufferedOutputStream(sock.getOutputStream(), 16 * 1024);
            IrijExchange ex;
            try {
                ex = parse(in, out);
            } catch (BadRequest bad) {
                rejectRequest(out, bad.status);
                return;
            }
            if (ex == null) return; // empty connection — just drop it
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

    /** Parse one request. Returns null for a connection that closed
     *  before sending anything; throws {@link BadRequest} for one the
     *  server refuses. */
    static IrijExchange parse(InputStream in, OutputStream out) throws IOException {
        String requestLine = readLine(in, MAX_LINE);
        if (requestLine == null || requestLine.isEmpty()) return null;
        String[] parts = requestLine.split(" ", 3);
        if (parts.length < 3) throw new BadRequest(400, "malformed request line");
        String method = parts[0];
        String target = parts[1];

        IrijHeaders headers = new IrijHeaders();
        int headerBytes = 0;
        String line;
        while ((line = readLine(in, MAX_LINE)) != null && !line.isEmpty()) {
            headerBytes += line.length() + 2;
            if (headerBytes > MAX_HEADER_BYTES) throw new BadRequest(431, "headers too large");
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            headers.add(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        if (line == null) throw new BadRequest(400, "truncated header section");

        URI uri;
        try { uri = new URI(target); }
        catch (URISyntaxException e) {
            // Browsers leave `{ } | [ ] ^` and the like unescaped, which
            // java.net.URI refuses; escape them rather than refuse the page.
            try { uri = new URI(escapeIllegal(target)); }
            catch (URISyntaxException e2) { throw new BadRequest(400, "malformed request target"); }
        }

        String te = headers.getFirst("Transfer-Encoding");
        String cl = headers.getFirst("Content-Length");
        boolean chunked = te != null && !te.equalsIgnoreCase("identity");
        if (chunked && !te.equalsIgnoreCase("chunked")) {
            throw new BadRequest(501, "unsupported transfer-encoding");
        }
        long len = 0;
        if (!chunked && cl != null) {
            try { len = Long.parseLong(cl.trim()); }
            catch (NumberFormatException e) { throw new BadRequest(400, "bad content-length"); }
            if (len < 0) throw new BadRequest(400, "bad content-length");
            if (len > MAX_BODY) throw new BadRequest(413, "body too large");
        }
        // curl and most clients hold large bodies back until the server
        // agrees; without the interim response every upload stalls ~1s.
        if ((chunked || len > 0) && "100-continue".equalsIgnoreCase(headers.getFirst("Expect"))) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }

        byte[] body;
        if (chunked) {
            body = readChunked(in);
        } else {
            body = in.readNBytes((int) len);
            if (body.length != len) throw new BadRequest(400, "truncated body");
        }
        return new IrijExchange(method, uri, headers, body, out);
    }

    private static final String URI_SAFE =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~:/?#@!$&'()*+,;=";

    /** Percent-escape every character java.net.URI won't take, keeping
     *  existing {@code %XX} escapes. {@code target} holds raw bytes as
     *  ISO-8859-1 chars, so each escapes as its own byte. */
    private static String escapeIllegal(String target) {
        StringBuilder sb = new StringBuilder(target.length() + 16);
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (URI_SAFE.indexOf(c) >= 0
                    || (c == '%' && i + 2 < target.length()
                        && Character.digit(target.charAt(i + 1), 16) >= 0
                        && Character.digit(target.charAt(i + 2), 16) >= 0)) {
                sb.append(c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)))
                  .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    /** Decode a {@code Transfer-Encoding: chunked} body (trailers are
     *  read and dropped). Reverse proxies send one whenever the client's
     *  body length is unknown up front. */
    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in, MAX_LINE);
            if (sizeLine == null) throw new BadRequest(400, "truncated chunked body");
            int semi = sizeLine.indexOf(';'); // chunk extensions
            String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
            long size;
            try { size = Long.parseLong(hex, 16); }
            catch (NumberFormatException e) { throw new BadRequest(400, "bad chunk size"); }
            if (size < 0) throw new BadRequest(400, "bad chunk size");
            if (size == 0) break;
            if (body.size() + size > MAX_BODY) throw new BadRequest(413, "body too large");
            byte[] chunk = in.readNBytes((int) size);
            if (chunk.length != size) throw new BadRequest(400, "truncated chunk");
            body.write(chunk);
            String crlf = readLine(in, MAX_LINE);
            if (crlf == null || !crlf.isEmpty()) throw new BadRequest(400, "malformed chunk");
        }
        int trailerBytes = 0;
        String trailer;
        while ((trailer = readLine(in, MAX_LINE)) != null && !trailer.isEmpty()) {
            trailerBytes += trailer.length() + 2;
            if (trailerBytes > MAX_HEADER_BYTES) throw new BadRequest(431, "trailers too large");
        }
        return body.toByteArray();
    }

    /** Answer a refused request with a bare status and close. */
    private static void rejectRequest(OutputStream out, int status) {
        try {
            out.write(("HTTP/1.1 " + status + " " + reason(status)
                    + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        } catch (IOException ignored) {
            // the client is already gone
        }
    }

    /** Read one CRLF- (or LF-) terminated line as ISO-8859-1, sans
     *  terminator. Returns null at EOF with no bytes; throws
     *  {@link BadRequest} if it exceeds {@code max}. */
    private static String readLine(InputStream in, int max) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int c;
        int n = 0;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            buf.write(c);
            if (++n > max) throw new BadRequest(431, "line too long");
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
            char[] c = key.toLowerCase(java.util.Locale.ROOT).toCharArray();
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
            // Header names/values often carry request data (a redirect's
            // Location, a filename). A CR or LF in one would let the
            // client write its own headers or a whole second response.
            for (Map.Entry<String, List<String>> e : respHeaders.entrySet()) {
                requireHeaderSafe(e.getKey());
                for (String v : e.getValue()) requireHeaderSafe(v);
            }
            if (status < 100 || status > 999) {
                throw new IllegalArgumentException("invalid HTTP status " + status);
            }
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

        /** Turn an uncaught handler error into a 500 if nothing's
         *  committed. The client gets a bare status; the error text,
         *  which can name files, SQL or secrets, goes to the server log
         *  only. */
        void fail(Exception e) {
            try {
                if (!committed) {
                    respHeaders.clear(); // whatever the handler set may be the cause
                    byte[] msg = "Internal Server Error".getBytes(StandardCharsets.UTF_8);
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

    private static void requireHeaderSafe(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r' || c == '\n' || c == 0) {
                throw new IllegalArgumentException(
                        "HTTP header contains a line break or NUL: " + s.replace("\r", "\\r").replace("\n", "\\n"));
            }
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
            case 413 -> "Content Too Large";
            case 431 -> "Request Header Fields Too Large";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            default -> "Status";
        };
    }
}
