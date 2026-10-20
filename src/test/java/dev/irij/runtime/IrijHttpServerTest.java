package dev.irij.runtime;

import dev.irij.runtime.IrijHttpServer.IrijExchange;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The virtual-thread-per-connection server that replaced
 * {@code com.sun.net.httpserver} (whose single dispatcher wedges on JDK 25
 * when an SSE client disconnects). The key regression guard is
 * {@link #sseDisconnectDoesNotWedgeOtherConnections()} — on the old
 * selector model that test hangs under JDK 25; here every connection is
 * independent so it stays green.
 */
class IrijHttpServerTest {

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    private static void waitForPort(int port) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            try (Socket s = new Socket("127.0.0.1", port)) { return; }
            catch (IOException e) { Thread.sleep(20); }
        }
        throw new IllegalStateException("server never came up on " + port);
    }

    /** Minimal one-shot client: send a GET, return the response body
     *  (Connection: close → read to EOF). */
    private static String get(int port, String path) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: t\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
            byte[] all = s.getInputStream().readAllBytes();
            String resp = new String(all, StandardCharsets.UTF_8);
            int sep = resp.indexOf("\r\n\r\n");
            return sep < 0 ? "" : resp.substring(sep + 4);
        }
    }

    private static IrijHttpServer.ConnHandler echoHandler() {
        return ex -> {
            if (ex.getRequestURI().getPath().equals("/sse")) {
                ex.getResponseHeaders().set("Content-Type", "text/event-stream");
                ex.sendResponseHeaders(200, 0); // streaming
                OutputStream out = ex.getResponseBody();
                // Stream until the client disconnects (write throws).
                try {
                    for (int i = 0; i < 10_000; i++) {
                        out.write(":\n\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        Thread.sleep(30);
                    }
                } catch (IOException gone) { /* client left — vthread ends */ }
            } else if (ex.getRequestURI().getPath().equals("/inject")) {
                // A handler copying request data into a header.
                ex.getResponseHeaders().set("Location", "/x\r\nSet-Cookie: pwned=1");
                ex.sendResponseHeaders(302, -1);
            } else if (ex.getRequestURI().getPath().equals("/boom")) {
                throw new IllegalStateException("db password is hunter2");
            } else if (ex.getRequestMethod().equals("POST")) {
                byte[] body = ex.getRequestBody().readAllBytes();
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body); // echo
            } else {
                byte[] b = "ok".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
            }
        };
    }

    private static Thread startServer(int port) {
        Thread t = new Thread(() -> {
            try { IrijHttpServer.serve(port, echoHandler()); }
            catch (IOException ignored) {}
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Test void plainGetAndPostBody() throws Exception {
        int port = freePort();
        startServer(port);
        waitForPort(port);

        assertEquals("ok", get(port, "/"));

        // POST with a Content-Length body must round-trip.
        try (Socket s = new Socket("127.0.0.1", port)) {
            String body = "hello-body-123";
            String req = "POST /echo HTTP/1.1\r\nHost: t\r\nContent-Length: "
                    + body.length() + "\r\n\r\n" + body;
            s.getOutputStream().write(req.getBytes(StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
            String resp = new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(resp.endsWith(body), "POST body should echo: " + resp);
        }
    }

    private static String raw(int port, String request) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(10_000);
            s.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
            return new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test void chunkedRequestBodyIsDecoded() throws Exception {
        int port = freePort();
        startServer(port);
        waitForPort(port);
        String resp = raw(port, "POST /echo HTTP/1.1\r\nHost: t\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "5\r\nhello\r\n7;ext=1\r\n-world!\r\n0\r\nX-Trailer: y\r\n\r\n");
        assertTrue(resp.startsWith("HTTP/1.1 200"), resp);
        assertTrue(resp.endsWith("hello-world!"), resp);
    }

    @Test void expectContinueGetsAnInterimResponse() throws Exception {
        int port = freePort();
        startServer(port);
        waitForPort(port);
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(10_000);
            s.getOutputStream().write(("POST /echo HTTP/1.1\r\nHost: t\r\nContent-Length: 3\r\n"
                    + "Expect: 100-continue\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
            byte[] interim = new byte[25];
            int n = s.getInputStream().readNBytes(interim, 0, interim.length);
            assertEquals("HTTP/1.1 100 Continue\r\n\r\n", new String(interim, 0, n, StandardCharsets.ISO_8859_1));
            s.getOutputStream().write("abc".getBytes(StandardCharsets.ISO_8859_1));
            String rest = new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(rest.endsWith("abc"), rest);
        }
    }

    @Test void malformedRequestsGetAStatusNotASilentDrop() throws Exception {
        int port = freePort();
        startServer(port);
        waitForPort(port);
        assertTrue(raw(port, "GARBAGE\r\n\r\n").startsWith("HTTP/1.1 400"));
        assertTrue(raw(port, "POST /echo HTTP/1.1\r\nContent-Length: nope\r\n\r\n").startsWith("HTTP/1.1 400"));
        assertTrue(raw(port, "POST /echo HTTP/1.1\r\nContent-Length: 999999999999\r\n\r\n").startsWith("HTTP/1.1 413"));
        assertTrue(raw(port, "GET /\r\n\r\n").startsWith("HTTP/1.1 400"));
    }

    @Test void unescapedTargetCharactersAreTolerated() throws Exception {
        // Browsers send `{`, `|`, `[` unescaped; java.net.URI rejects them.
        var ex = IrijHttpServer.parse(new java.io.ByteArrayInputStream(
                "GET /a{b}?q=[1]|x&r=%41 HTTP/1.1\r\nHost: t\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)),
                new java.io.ByteArrayOutputStream());
        assertEquals("/a{b}", ex.getRequestURI().getPath());
        assertEquals("q=[1]|x&r=A", ex.getRequestURI().getQuery());
    }

    private static IrijExchange parseRaw(byte[] request) throws IOException {
        return IrijHttpServer.parse(new java.io.ByteArrayInputStream(request),
                new java.io.ByteArrayOutputStream());
    }

    private static IrijExchange parseRaw(String request) throws IOException {
        return parseRaw(request.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static int refusedWith(String request) {
        return assertThrows(IrijHttpServer.BadRequest.class, () -> parseRaw(request), request).status;
    }

    @Test void rawUtf8InTheTargetDecodesTheSameWhateverElseIsThere() throws Exception {
        // Raw UTF-8 bytes used to decode as Latin-1 (`cafÃ©`), unless the
        // target also held a char java.net.URI refuses, when they decoded
        // as UTF-8.
        byte[] cafe = "GET /caf\u00c3\u00a9 HTTP/1.1\r\nHost: t\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] cafePipe = "GET /caf\u00c3\u00a9| HTTP/1.1\r\nHost: t\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals("/caf\u00e9", parseRaw(cafe).getPath());
        assertEquals("/caf\u00e9|", parseRaw(cafePipe).getPath());
        assertEquals("/caf\u00e9", parseRaw("GET /caf%C3%A9 HTTP/1.1\r\nHost: t\r\n\r\n").getPath());
    }

    @Test void anEscapedSlashCannotAddAPathSegment() throws Exception {
        assertEquals(400, refusedWith("GET /files/a%2Fb HTTP/1.1\r\n\r\n"));
        assertEquals(400, refusedWith("GET /files/a%2fb HTTP/1.1\r\n\r\n"));
        assertEquals(400, refusedWith("GET /a%00b HTTP/1.1\r\n\r\n"));
        assertEquals(400, refusedWith("GET /a/%2E%2E/b HTTP/1.1\r\n\r\n"));
        assertEquals(400, refusedWith("GET /a\tb HTTP/1.1\r\n\r\n"));
        // `?` and `#` escaped are data in a segment, and `+` is a plus.
        assertEquals("/a?b#c+d", parseRaw("GET /a%3Fb%23c+d HTTP/1.1\r\n\r\n").getPath());
    }

    @Test void escapesThatArentUtf8AreRefused() throws Exception {
        // %FF and %FE both decoded to U+FFFD: two paths, one string.
        assertEquals(400, refusedWith("GET /%FF HTTP/1.1\r\n\r\n"));
        assertEquals(400, refusedWith("GET /%C0%AF HTTP/1.1\r\n\r\n"));
        assertEquals(400, refusedWith("GET /%ED%A0%80 HTTP/1.1\r\n\r\n"));
        assertEquals(400, refusedWith("GET /x?q=%FE HTTP/1.1\r\n\r\n"));
    }

    @Test void framingNumbersAreDigitsOnly() throws Exception {
        assertEquals(400, refusedWith("POST / HTTP/1.1\r\nContent-Length: +5\r\n\r\nhello"));
        assertEquals(400, refusedWith("POST / HTTP/1.1\r\nContent-Length: -0\r\n\r\n"));
        assertEquals(400, refusedWith("POST / HTTP/1.1\r\nContent-Length: 5\r\nContent-Length: 6\r\n\r\nhello!"));
        assertEquals(400, refusedWith("POST / HTTP/1.1\r\nContent-Length: 5, 6\r\n\r\nhello!"));
        assertEquals("hello", new String(parseRaw(
                "POST / HTTP/1.1\r\nContent-Length: 5\r\nContent-Length: 5\r\n\r\nhello")
                .getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String chunked = "POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n";
        assertEquals(400, refusedWith(chunked + "+5\r\nhello\r\n0\r\n\r\n"));
        assertEquals(400, refusedWith(chunked + "5\r\nhello\r\n-0\r\n\r\n"));
        assertEquals(400, refusedWith(chunked + "0x5\r\nhello\r\n0\r\n\r\n"));
        assertEquals("hello", new String(parseRaw(chunked + "0000000000000005 ;x=1\r\nhello\r\n0\r\n\r\n")
                .getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test void headerValuesCannotSplitTheResponse() throws Exception {
        int port = freePort();
        startServer(port);
        waitForPort(port);
        String resp = raw(port, "GET /inject HTTP/1.1\r\nHost: t\r\n\r\n");
        assertTrue(resp.startsWith("HTTP/1.1 500"), resp);
        assertFalse(resp.contains("Set-Cookie"), resp);
    }

    @Test void handlerErrorsStayInTheServerLog() throws Exception {
        int port = freePort();
        startServer(port);
        waitForPort(port);
        var err = System.err;
        System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
        try {
            String resp = raw(port, "GET /boom HTTP/1.1\r\nHost: t\r\n\r\n");
            assertTrue(resp.startsWith("HTTP/1.1 500"), resp);
            assertFalse(resp.contains("hunter2"), resp);
        } finally {
            System.setErr(err);
        }
    }

    @Test void sseDataCannotStartItsOwnEvent() throws Exception {
        var buf = new java.io.ByteArrayOutputStream();
        var sse = new Values.SseWriter(buf);
        sse.send("msg", "a\r\revent: evil\rdata: x\r\nb");
        String wire = buf.toString(StandardCharsets.UTF_8);
        // Every CR/LF-separated piece is its own data line; no bare CR survives.
        assertEquals("event: msg\ndata: a\ndata: \ndata: event: evil\ndata: data: x\ndata: b\n\n", wire);
        assertThrows(IOException.class, () -> sse.send("msg\nid: 9", "x"));
    }

    @Test void sseAwaitClosedWakesOnClose() throws Exception {
        var sse = new Values.SseWriter(new java.io.ByteArrayOutputStream());
        assertFalse(sse.awaitClosed(10));
        Thread.ofVirtual().start(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            sse.close();
        });
        long t0 = System.nanoTime();
        assertTrue(sse.awaitClosed(10_000));
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 5_000);
    }

    @Test void sseDisconnectDoesNotWedgeOtherConnections() throws Exception {
        int port = freePort();
        startServer(port);
        waitForPort(port);

        assertEquals("ok", get(port, "/")); // baseline

        // Open an SSE stream, read a little, then abruptly RST it — the
        // exact pattern (client navigate-away from a streaming response)
        // that wedges the com.sun.net.httpserver dispatcher on JDK 25.
        // After each disconnect the server must still serve other requests.
        for (int i = 0; i < 6; i++) {
            Socket s = new Socket("127.0.0.1", port);
            s.getOutputStream().write("GET /sse HTTP/1.1\r\nHost: t\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
            InputStream in = s.getInputStream();
            in.read(new byte[64]); // read part of headers
            s.setSoLinger(true, 0); // RST on close
            s.close();
            assertEquals("ok", get(port, "/"),
                    "server wedged after SSE disconnect #" + i);
        }
    }
}
