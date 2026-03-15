package dev.irij.nrepl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the nREPL TCP server.
 */
class NReplServerTest {

    @Test
    @Timeout(10)
    void evalOverSocket() throws Exception {
        var server = new NReplServer(0); // random port
        var started = new CountDownLatch(1);

        var serverThread = Thread.startVirtualThread(() -> {
            try {
                started.countDown();
                server.start();
            } catch (IOException e) {
                // normal on shutdown
            }
        });

        // Wait for server to be ready
        started.await(5, TimeUnit.SECONDS);
        Thread.sleep(200); // give ServerSocket time to bind

        try (var socket = new Socket("localhost", server.getPort())) {
            socket.setSoTimeout(5000);
            var out = socket.getOutputStream();
            var in = new BufferedInputStream(socket.getInputStream());

            // Step 1: Clone a session
            Bencode.encodeMap(new LinkedHashMap<>(Map.of(
                    "op", "clone",
                    "id", "1"
            )), out);
            out.flush();

            var cloneResp = Bencode.decodeMap(in);
            assertNotNull(cloneResp);
            assertNotNull(cloneResp.get("new-session"), "Clone should return new-session id");
            String sessionId = (String) cloneResp.get("new-session");

            // Step 2: Eval an expression
            var evalMsg = new LinkedHashMap<String, Object>();
            evalMsg.put("op", "eval");
            evalMsg.put("code", "1 + 2");
            evalMsg.put("session", sessionId);
            evalMsg.put("id", "2");
            Bencode.encodeMap(evalMsg, out);
            out.flush();

            var evalResp = Bencode.decodeMap(in);
            assertNotNull(evalResp);
            assertEquals("3", evalResp.get("value"));

            // Step 3: Eval with state
            var bindMsg = new LinkedHashMap<String, Object>();
            bindMsg.put("op", "eval");
            bindMsg.put("code", "x := 42");
            bindMsg.put("session", sessionId);
            bindMsg.put("id", "3");
            Bencode.encodeMap(bindMsg, out);
            out.flush();
            Bencode.decodeMap(in); // consume response

            var lookupMsg = new LinkedHashMap<String, Object>();
            lookupMsg.put("op", "eval");
            lookupMsg.put("code", "x * 2");
            lookupMsg.put("session", sessionId);
            lookupMsg.put("id", "4");
            Bencode.encodeMap(lookupMsg, out);
            out.flush();

            var lookupResp = Bencode.decodeMap(in);
            assertNotNull(lookupResp);
            assertEquals("84", lookupResp.get("value"));

        } finally {
            server.stop();
        }
    }
}
