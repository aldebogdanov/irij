package dev.irij.runtime;

import dev.irij.runtime.IrijHttpServer.IrijExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What `serve` hands out on its own, before the app's handler runs:
 * the app's resources/ directory and nothing else.
 */
class ServeStaticTest {

    @TempDir Path work;

    private static IrijExchange request(String rawTarget, ByteArrayOutputStream out) throws Exception {
        byte[] req = ("GET " + rawTarget + " HTTP/1.1\r\nHost: t\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        return IrijHttpServer.parse(new ByteArrayInputStream(req), out);
    }

    private boolean served(String target) throws Exception {
        var out = new ByteArrayOutputStream();
        return ServeCapability.httpServeStatic(request(target, out), work, false);
    }

    @Test void servesFilesUnderResources() throws Exception {
        Files.createDirectories(work.resolve("resources/css"));
        Files.writeString(work.resolve("resources/app.js"), "js");
        Files.writeString(work.resolve("resources/css/site.css"), "css");
        var out = new ByteArrayOutputStream();
        assertTrue(ServeCapability.httpServeStatic(request("/app.js", out), work, false));
        assertTrue(out.toString(StandardCharsets.UTF_8).endsWith("js"));
        assertTrue(served("/css/site.css"));
    }

    @Test void neverServesTheRestOfTheWorkingDirectory() throws Exception {
        Files.createDirectories(work.resolve("resources"));
        Files.createDirectories(work.resolve("data"));
        Files.writeString(work.resolve(".env"), "SECRET=1");
        Files.writeString(work.resolve("server.irj"), "source");
        Files.writeString(work.resolve("irij.toml"), "[project]");
        Files.writeString(work.resolve("data/registry.db"), "db");
        assertFalse(served("/.env"));
        assertFalse(served("/server.irj"));
        assertFalse(served("/irij.toml"));
        assertFalse(served("/data/registry.db"));
    }

    @Test void refusesTraversalOutOfResources() throws Exception {
        Files.createDirectories(work.resolve("resources"));
        Files.writeString(work.resolve("secret.txt"), "s");
        // The server refuses a dot segment, literal or escaped, outright…
        for (String t : new String[]{"/../secret.txt", "/%2e%2e/secret.txt",
                "/resources/../secret.txt", "/./secret.txt"}) {
            var bad = assertThrows(IrijHttpServer.BadRequest.class,
                    () -> request(t, new ByteArrayOutputStream()), t);
            assertEquals(400, bad.status);
        }
        // …and static serving refuses one on its own, should one get past.
        var ex = new IrijExchange("GET", java.net.URI.create("/x"), "/../secret.txt",
                new IrijHttpServer.IrijHeaders(), new byte[0], new ByteArrayOutputStream());
        assertFalse(ServeCapability.httpServeStatic(ex, work, false));
    }

    @Test void queryParamsSplitOnTheRawQuery() {
        // `%25zz` is what the server makes of a stray `%zz`.
        Map<String, Object> p = ServeCapability.parseQueryParams("a=x%26y&b=1%2B1&c+d=e+f&bad=%25zz&flag");
        assertEquals("x&y", p.get("a"));
        assertEquals("1+1", p.get("b"));
        assertEquals("e f", p.get("c d"));
        assertEquals("%zz", p.get("bad"));
        assertEquals("", p.get("flag"));
        assertEquals(5, p.size());
    }

    @Test void requestMapPathIsDecodedQueryIsRaw() throws Exception {
        var req = ServeCapability.buildRequestMap(
                request("/files/caf%C3%A9/a+b?q=a%26b+c&bad=%zz", new ByteArrayOutputStream()));
        assertEquals("/files/café/a+b", req.entries().get("path"));
        // Raw, as sent (a stray `%` arrives escaped): decoded, the `%26`
        // would split the value for code that splits on `&`.
        assertEquals("q=a%26b+c&bad=%25zz", req.entries().get("query"));
        var params = (Values.IrijMap) req.entries().get("params");
        assertEquals("a&b c", params.entries().get("q"));
        assertEquals("%zz", params.entries().get("bad"));
    }
}
