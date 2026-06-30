package dev.irij.runtime;

import dev.irij.runtime.IrijHttpServer.IrijExchange;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
