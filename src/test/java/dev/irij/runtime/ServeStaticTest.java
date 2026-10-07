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
        assertFalse(served("/../secret.txt"));
        assertFalse(served("/%2e%2e/secret.txt"));
        assertFalse(served("/resources/../secret.txt"));
        assertFalse(served("/./secret.txt"));
    }

    @Test void queryParamsSplitOnTheRawQuery() {
        Map<String, Object> p = ServeCapability.parseQueryParams("a=x%26y&b=1%2B1&c+d=e+f&bad=%zz&flag");
        assertEquals("x&y", p.get("a"));
        assertEquals("1+1", p.get("b"));
        assertEquals("e f", p.get("c d"));
        assertEquals("%zz", p.get("bad"));
        assertEquals("", p.get("flag"));
        assertEquals(5, p.size());
    }
}
