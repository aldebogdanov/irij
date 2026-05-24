package dev.irij.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.irij.IrijRuntimeError;
import dev.irij.compiler.RuntimeSupport;
import dev.irij.runtime.Values.IrijMap;
import dev.irij.runtime.Values.SseWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.Executors;

/**
 * Capability provider for the {@code Serve} effect (HTTP server +
 * Server-Sent Events).
 *
 * <p>Bound from Irij with:
 * <pre>
 *   cap server :: Serve = "dev.irij.runtime.ServeCapability"
 * </pre>
 *
 * <p>One provider holds both the long-running {@code serve} loop and
 * the per-request SSE writer operations because they share the same
 * {@code com.sun.net.httpserver.HttpExchange} object: the user-side
 * Irij handler returns an {@link SseWriter} to indicate "I'm taking
 * over this exchange via SSE", and {@code serve}'s dispatcher blocks
 * until the writer closes. Splitting these across two caps would
 * force shared HttpExchange plumbing back into Irij.
 */
public final class ServeCapability {

    private ServeCapability() {}

    // ── HTTP server ─────────────────────────────────────────────────

    /** {@code server.serve port handler} — bind a port + dispatch on
     *  each request through the user-supplied handler IrijFn. Static
     *  asset serving (classpath {@code __irij_resources/} +
     *  {@code __irij_app/}, plus script-relative {@code resources/})
     *  runs before the handler so user code never sees those paths. */
    public static Object serve(Object portArg, Object handler) {
        long port = asLong(portArg, "server.serve");
        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress((int) port), 0);

            Path scriptDir = Path.of("").toAbsolutePath();
            // `isBundled` always-on rationale: shadow JARs emit file
            // entries without directory entries, so the historic probe
            // via getResource("__irij_resources/") returned null even
            // when files were present. The probe now happens per-file
            // inside serveClasspathResource — unbundled mode returns
            // null there and falls through to the filesystem branch.
            final boolean isBundled = true;

            server.createContext("/", exchange -> {
                try {
                    if (httpServeStatic(exchange, scriptDir, isBundled)) return;

                    IrijMap req = buildRequestMap(exchange);
                    Object resp = RuntimeSupport.callAny(handler, new Object[]{req});

                    if (resp instanceof SseWriter sse) {
                        // Handler took over via SSE — block until writer
                        // closes so the connection stays open.
                        while (!sse.isClosed()) {
                            try { Thread.sleep(500); }
                            catch (InterruptedException ie) { sse.close(); break; }
                        }
                        return;
                    }

                    writeResponse(exchange, resp);
                } catch (Exception e) {
                    System.err.println("HTTP 500 " + exchange.getRequestMethod()
                            + " " + exchange.getRequestURI() + ": " + e.getMessage());
                    e.printStackTrace(System.err);
                    try {
                        String errMsg = "Internal Server Error: " + e.getMessage();
                        byte[] errBytes = errMsg.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(500, errBytes.length);
                        try (var os = exchange.getResponseBody()) { os.write(errBytes); }
                    } catch (Exception ignored) {}
                }
            });

            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            System.out.println("Irij HTTP server listening on http://localhost:" + port);
            try { Thread.currentThread().join(); }
            catch (InterruptedException e) { server.stop(0); }
            return Values.UNIT;
        } catch (IOException e) {
            throw new IrijRuntimeError("server.serve: " + e.getMessage());
        }
    }

    // ── SSE writer ──────────────────────────────────────────────────

    /** {@code server.sse-response request} — promote the request's
     *  exchange to an SSE stream and return a writer. Only valid when
     *  called from a request handler (the {@code __exchange} sentinel
     *  must be on the request map). */
    public static Object sseResponse(Object reqArg) {
        if (!(reqArg instanceof IrijMap reqMap)) {
            throw new IrijRuntimeError("server.sse-response: expected request map");
        }
        Object exchange = reqMap.entries().get("__exchange");
        if (!(exchange instanceof HttpExchange ex)) {
            throw new IrijRuntimeError(
                    "server.sse-response: no __exchange in request "
                            + "(only works inside a serve handler)");
        }
        try {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.getResponseHeaders().set("Connection", "keep-alive");
            ex.getResponseHeaders().set("X-Accel-Buffering", "no");
            ex.sendResponseHeaders(200, 0);
            return new SseWriter(ex, ex.getResponseBody());
        } catch (IOException e) {
            throw new IrijRuntimeError("server.sse-response: " + e.getMessage());
        }
    }

    public static Object sseSend(Object sseArg, Object evtArg, Object dataArg) {
        SseWriter sse = asSse(sseArg, "server.sse-send");
        String evt = asStr(evtArg, "server.sse-send");
        String data = asStr(dataArg, "server.sse-send");
        try { sse.send(evt, data); }
        catch (IOException e) {
            throw new IrijRuntimeError("server.sse-send: " + e.getMessage());
        }
        return Values.UNIT;
    }

    public static Object sseClose(Object sseArg) {
        SseWriter sse = asSse(sseArg, "server.sse-close");
        sse.close();
        return Values.UNIT;
    }

    public static Object sseClosedQ(Object sseArg) {
        SseWriter sse = asSse(sseArg, "server.sse-closed?");
        return sse.isClosed();
    }

    // ── Helpers (file-private — production providers can't share) ───

    private static long asLong(Object v, String op) {
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i.longValue();
        if (v instanceof Number n) return n.longValue();
        throw new IrijRuntimeError(op + ": expected Int, got " + v);
    }

    private static String asStr(Object v, String op) {
        if (v instanceof String s) return s;
        throw new IrijRuntimeError(op + ": expected Str, got " + v);
    }

    private static SseWriter asSse(Object v, String op) {
        if (v instanceof SseWriter sse) return sse;
        throw new IrijRuntimeError(op + ": first arg must be SseWriter");
    }

    private static boolean httpServeStatic(HttpExchange exchange,
                                           Path scriptDir,
                                           boolean isBundled) throws IOException {
        String reqPath = exchange.getRequestURI().getPath();
        if (reqPath.length() <= 1 || reqPath.contains("..")) return false;

        if (isBundled) {
            if (serveClasspathResource(exchange, "__irij_resources/" + reqPath.substring(1), reqPath)) return true;
            if (serveClasspathResource(exchange, "__irij_app/" + reqPath.substring(1), reqPath)) return true;
        }
        Path resourcesPath = scriptDir.resolve("resources").resolve(reqPath.substring(1)).normalize();
        Path resourcesRoot = scriptDir.resolve("resources").normalize();
        if (resourcesPath.startsWith(resourcesRoot)
                && Files.isRegularFile(resourcesPath)) {
            return sendFile(exchange, resourcesPath, reqPath);
        }
        Path filePath = scriptDir.resolve(reqPath.substring(1)).normalize();
        if (filePath.startsWith(scriptDir) && Files.isRegularFile(filePath)) {
            return sendFile(exchange, filePath, reqPath);
        }
        return false;
    }

    private static boolean serveClasspathResource(HttpExchange exchange,
                                                  String resourcePath,
                                                  String reqPath) throws IOException {
        var is = ServeCapability.class.getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) return false;
        try (is) {
            byte[] bytes = is.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", httpGuessMime(reqPath));
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            return true;
        }
    }

    private static boolean sendFile(HttpExchange exchange, Path path, String reqPath) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String mime = Files.probeContentType(path);
        if (mime == null) mime = httpGuessMime(reqPath);
        exchange.getResponseHeaders().set("Content-Type", mime);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        return true;
    }

    private static IrijMap buildRequestMap(HttpExchange exchange) throws IOException {
        LinkedHashMap<String, Object> reqMap = new LinkedHashMap<>();
        reqMap.put("method", exchange.getRequestMethod());
        var uri = exchange.getRequestURI();
        reqMap.put("path", uri.getPath());
        String rawQuery = uri.getQuery() != null ? uri.getQuery() : "";
        reqMap.put("query", rawQuery);
        reqMap.put("params", new IrijMap(parseQueryParams(rawQuery)));
        LinkedHashMap<String, Object> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((k, v) ->
                headers.put(k.toLowerCase(),
                        v.size() == 1 ? v.get(0) : String.join(", ", v)));
        reqMap.put("headers", new IrijMap(headers));
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        reqMap.put("body", new String(bodyBytes, StandardCharsets.UTF_8));
        reqMap.put("__body_bytes", bodyBytes);
        reqMap.put("__exchange", exchange);
        return new IrijMap(reqMap);
    }

    private static java.util.Map<String, Object> parseQueryParams(String query) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return out;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) out.put(pair, "");
            else out.put(pair.substring(0, eq),
                    java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static void writeResponse(HttpExchange exchange, Object resp) throws IOException {
        long status = 200;
        String respBody = "";
        String filePath = null;
        java.util.Map<String, Object> respHeaders = java.util.Map.of();
        if (resp instanceof IrijMap rm) {
            java.util.Map<String, Object> e = rm.entries();
            if (e.get("status") instanceof Long s) status = s;
            if (e.get("body") instanceof String b) respBody = b;
            if (e.get("file") instanceof String f) filePath = f;
            if (e.get("headers") instanceof IrijMap hm) {
                respHeaders = hm.entries();
            }
        } else if (resp instanceof String s) {
            respBody = s;
        }
        for (var h : respHeaders.entrySet()) {
            exchange.getResponseHeaders().set(h.getKey(), Values.toIrijString(h.getValue()));
        }
        if (!respHeaders.containsKey("content-type") && !respHeaders.containsKey("Content-Type")) {
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        }
        if (filePath != null) {
            byte[] bytes = Files.readAllBytes(Path.of(filePath));
            exchange.sendResponseHeaders((int) status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        } else {
            byte[] bytes = respBody.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 0) {
                exchange.sendResponseHeaders((int) status, bytes.length);
            }
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        }
    }

    private static String httpGuessMime(String path) {
        if (path.endsWith(".html") || path.endsWith(".htm")) return "text/html; charset=utf-8";
        if (path.endsWith(".css"))  return "text/css; charset=utf-8";
        if (path.endsWith(".js"))   return "application/javascript; charset=utf-8";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".png"))  return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".gif"))  return "image/gif";
        if (path.endsWith(".svg"))  return "image/svg+xml";
        if (path.endsWith(".ico"))  return "image/x-icon";
        if (path.endsWith(".txt"))  return "text/plain; charset=utf-8";
        if (path.endsWith(".pdf"))  return "application/pdf";
        return "application/octet-stream";
    }
}
