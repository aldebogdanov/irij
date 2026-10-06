package dev.irij.runtime;

import dev.irij.IrijRuntimeError;
import dev.irij.runtime.Values.IrijMap;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;

/**
 * Capability provider for the {@code Http} effect (client side).
 *
 * <p>Bound from Irij with:
 * <pre>
 *   cap http-client :: Http = "dev.irij.runtime.HttpClientCapability"
 * </pre>
 *
 * <p>Wraps {@link java.net.http.HttpClient}. The single public
 * entry point is {@link #request(Object)}; std.http's clauses dot-
 * access into it. Helpers ({@code get}, {@code post}, etc.) are
 * intentionally not on the cap surface — they live in std.http as
 * sugar over the single underlying call, which keeps the provider
 * minimal and avoids duplicating the URL/headers/body assembly on
 * both sides of the boundary.
 */
public final class HttpClientCapability {

    private HttpClientCapability() {}

    /** One client for the process: each {@link HttpClient} owns a
     *  selector thread and a connection pool, so building one per call
     *  leaks threads until GC and never reuses a connection. */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .build();

    /** {@code http-client.request opts} — opts is a Map with url +
     *  optional method (default GET), body, headers and timeout-ms
     *  (whole-request deadline; absent waits as long as the server
     *  keeps the connection alive). Returns a Map with status (Long),
     *  body (Str), headers (Map). */
    public static Object request(Object optsArg) {
        if (!(optsArg instanceof IrijMap opts)) {
            throw new IrijRuntimeError("http-client.request: expects Map argument");
        }
        var entries = opts.entries();
        Object url = entries.get("url");
        if (!(url instanceof String urlStr)) {
            throw new IrijRuntimeError(
                    "http-client.request: missing or invalid 'url' field");
        }
        String method = entries.getOrDefault("method", "GET").toString();
        Object body = entries.get("body");
        Object headers = entries.get("headers");
        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(URI.create(urlStr));
            if (entries.get("timeout-ms") instanceof Long ms && ms > 0) {
                reqBuilder.timeout(java.time.Duration.ofMillis(ms));
            }
            if (headers instanceof IrijMap hm) {
                for (var e : hm.entries().entrySet()) {
                    reqBuilder.header(e.getKey(), Values.toIrijString(e.getValue()));
                }
            }
            if (body instanceof String bodyStr) {
                reqBuilder.method(method, HttpRequest.BodyPublishers.ofString(bodyStr));
            } else {
                reqBuilder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> resp = CLIENT.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());
            LinkedHashMap<String, Object> respHeaders = new LinkedHashMap<>();
            resp.headers().map().forEach((k, v) ->
                    respHeaders.put(k, v.size() == 1 ? v.get(0) : String.join(", ", v)));
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("status", (long) resp.statusCode());
            result.put("body", resp.body());
            result.put("headers", new IrijMap(respHeaders));
            return new IrijMap(result);
        } catch (InterruptedException e) {
            // Keep the cancellation visible to the caller (a cancelled
            // fork, a timed-out eval) rather than swallowing it.
            Thread.currentThread().interrupt();
            throw new IrijRuntimeError("http-client.request: interrupted");
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new IrijRuntimeError("http-client.request: " + msg);
        }
    }
}
