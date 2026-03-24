package dev.irij.mcp;

import com.google.gson.*;
import dev.irij.ast.AstBuilder;
import dev.irij.interpreter.Interpreter;
import dev.irij.interpreter.IrijRuntimeError;
import dev.irij.interpreter.Values;
import dev.irij.nrepl.BackgroundOutputStream;
import dev.irij.nrepl.IndirectOutputStream;
import dev.irij.parser.IrijParseDriver;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * MCP (Model Context Protocol) server for Irij.
 *
 * <p>Exposes three tools over stdio JSON-RPC:
 * <ul>
 *   <li>{@code irij_eval} — evaluate code in a persistent REPL session</li>
 *   <li>{@code irij_run} — run an Irij program (file or inline, fresh interpreter)</li>
 *   <li>{@code irij_test} — run Gradle tests and return results</li>
 * </ul>
 *
 * <p>Transport: newline-delimited JSON-RPC 2.0 over stdin/stdout.
 * All logging goes to stderr.
 */
public final class IrijMcpServer {

    private static final String SERVER_NAME = "irij";
    private static final String SERVER_VERSION = "0.1.1";

    private final Path projectRoot;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    // JSON-RPC reader/writer
    private BufferedReader in;
    private PrintStream out;

    public IrijMcpServer(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    // ── Entry point ──────────────────────────────────────────────────────

    public void start() throws IOException {
        // Redirect System.out to stderr BEFORE creating transport
        // (MCP stdio transport owns stdout — interpreter output must NOT leak)
        var realStdout = System.out;
        System.setOut(new PrintStream(System.err, true));

        this.in = new BufferedReader(new InputStreamReader(System.in));
        this.out = realStdout;

        log("MCP server starting (project root: " + projectRoot + ")");

        // Main message loop
        String line;
        while ((line = this.in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;

            try {
                var msg = JsonParser.parseString(line).getAsJsonObject();
                var response = dispatch(msg);
                if (response != null) {
                    sendResponse(response);
                }
            } catch (Exception e) {
                log("Error processing message: " + e.getMessage());
                // If we can extract an ID, send an error response
                try {
                    var msg = JsonParser.parseString(line).getAsJsonObject();
                    if (msg.has("id")) {
                        sendResponse(errorResponse(msg.get("id"), -32603, "Internal error: " + e.getMessage()));
                    }
                } catch (Exception ignored) {}
            }
        }

        log("MCP server shutting down");
    }

    // ── JSON-RPC dispatch ────────────────────────────────────────────────

    private JsonObject dispatch(JsonObject msg) {
        String method = msg.has("method") ? msg.get("method").getAsString() : null;
        if (method == null) return null; // Response or notification without method

        var id = msg.get("id"); // null for notifications
        var params = msg.has("params") ? msg.getAsJsonObject("params") : new JsonObject();

        return switch (method) {
            case "initialize" -> handleInitialize(id, params);
            case "notifications/initialized" -> null; // No response for notifications
            case "tools/list" -> handleToolsList(id);
            case "tools/call" -> handleToolsCall(id, params);
            case "ping" -> jsonRpcResult(id, new JsonObject());
            default -> {
                if (id != null) {
                    yield errorResponse(id, -32601, "Method not found: " + method);
                }
                yield null; // Ignore unknown notifications
            }
        };
    }

    // ── initialize ───────────────────────────────────────────────────────

    private JsonObject handleInitialize(JsonElement id, JsonObject params) {
        var result = new JsonObject();
        result.addProperty("protocolVersion", "2024-11-05");

        var serverInfo = new JsonObject();
        serverInfo.addProperty("name", SERVER_NAME);
        serverInfo.addProperty("version", SERVER_VERSION);
        result.add("serverInfo", serverInfo);

        var capabilities = new JsonObject();
        var tools = new JsonObject();
        capabilities.add("tools", tools);
        result.add("capabilities", capabilities);

        return jsonRpcResult(id, result);
    }

    // ── tools/list ───────────────────────────────────────────────────────

    private JsonObject handleToolsList(JsonElement id) {
        var toolsArray = new JsonArray();
        toolsArray.add(evalToolDef());
        toolsArray.add(runToolDef());
        toolsArray.add(testToolDef());

        var result = new JsonObject();
        result.add("tools", toolsArray);
        return jsonRpcResult(id, result);
    }

    // ── tools/call ───────────────────────────────────────────────────────

    private JsonObject handleToolsCall(JsonElement id, JsonObject params) {
        String toolName = params.has("name") ? params.get("name").getAsString() : "";
        var args = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();

        return switch (toolName) {
            case "irij_eval" -> callEval(id, args);
            case "irij_run" -> callRun(id, args);
            case "irij_test" -> callTest(id, args);
            default -> toolErrorResult(id, "Unknown tool: " + toolName);
        };
    }

    // ── Tool: irij_eval ──────────────────────────────────────────────────

    private JsonObject callEval(JsonElement id, JsonObject args) {
        String code = getStr(args, "code");
        if (code == null || code.isBlank()) {
            return toolErrorResult(id, "Missing required parameter 'code'");
        }

        String sessionId = getStr(args, "session_id");
        if (sessionId == null) sessionId = "default";

        boolean reset = args.has("reset") && args.get("reset").getAsBoolean();

        if (reset) {
            sessions.remove(sessionId);
        }

        var session = sessions.computeIfAbsent(sessionId, k -> new Session(projectRoot));

        // Capture stdout
        var capture = new ByteArrayOutputStream();
        session.indirectOut.setTarget(capture);
        try {
            var parseResult = IrijParseDriver.parse(code);
            if (parseResult.hasErrors()) {
                var sb = new StringBuilder("Parse error:\n");
                for (var err : parseResult.errors()) {
                    sb.append("  ").append(err).append("\n");
                }
                return toolErrorResult(id, sb.toString().strip());
            }

            var ast = new AstBuilder().build(parseResult.tree());
            var value = session.interpreter.run(ast);

            String stdout = capture.toString().strip();
            var sb = new StringBuilder();
            if (!stdout.isEmpty()) {
                sb.append(stdout).append("\n");
            }
            if (value != Values.UNIT) {
                sb.append("=> ").append(Values.toIrijString(value));
            }
            return toolResult(id, sb.toString().strip());

        } catch (IrijRuntimeError e) {
            String stdout = capture.toString().strip();
            var sb = new StringBuilder();
            if (!stdout.isEmpty()) {
                sb.append(stdout).append("\n\n");
            }
            sb.append("Runtime error: ").append(e.getMessage());
            return toolErrorResult(id, sb.toString().strip());
        } catch (Exception e) {
            return toolErrorResult(id, "Error: " + e.getMessage());
        } finally {
            session.indirectOut.setTarget(session.backgroundOut);
        }
    }

    // ── Tool: irij_run ───────────────────────────────────────────────────

    private JsonObject callRun(JsonElement id, JsonObject args) {
        String code = getStr(args, "code");
        String file = getStr(args, "file");

        if ((code == null || code.isBlank()) && (file == null || file.isBlank())) {
            return toolErrorResult(id, "Provide either 'code' or 'file' parameter");
        }

        // Fresh interpreter for each run
        var capture = new ByteArrayOutputStream();
        var printStream = new PrintStream(capture, true);
        var interpreter = new Interpreter(printStream);

        try {
            IrijParseDriver.ParseResult parseResult;
            if (file != null && !file.isBlank()) {
                var path = Path.of(file);
                if (!Files.exists(path)) {
                    return toolErrorResult(id, "File not found: " + file);
                }
                parseResult = IrijParseDriver.parseFile(path);
                interpreter.setSourcePath(path.toAbsolutePath().getParent());
            } else {
                parseResult = IrijParseDriver.parse(code);
                interpreter.setSourcePath(projectRoot);
            }

            if (parseResult.hasErrors()) {
                var sb = new StringBuilder("Parse error:\n");
                for (var err : parseResult.errors()) {
                    sb.append("  ").append(err).append("\n");
                }
                return toolErrorResult(id, sb.toString().strip());
            }

            var ast = new AstBuilder().build(parseResult.tree());
            var value = interpreter.run(ast);

            String stdout = capture.toString().strip();
            var sb = new StringBuilder();
            if (!stdout.isEmpty()) {
                sb.append(stdout).append("\n");
            }
            if (value != Values.UNIT) {
                sb.append("=> ").append(Values.toIrijString(value));
            }
            String result = sb.toString().strip();
            return toolResult(id, result.isEmpty() ? "(no output)" : result);

        } catch (IrijRuntimeError e) {
            String stdout = capture.toString().strip();
            var sb = new StringBuilder();
            if (!stdout.isEmpty()) {
                sb.append(stdout).append("\n\n");
            }
            sb.append("Runtime error: ").append(e.getMessage());
            return toolErrorResult(id, sb.toString().strip());
        } catch (Exception e) {
            return toolErrorResult(id, "Error: " + e.getMessage());
        }
    }

    // ── Tool: irij_test ──────────────────────────────────────────────────

    private JsonObject callTest(JsonElement id, JsonObject args) {
        String filter = getStr(args, "filter");
        boolean rerun = !args.has("rerun") || args.get("rerun").getAsBoolean();

        var cmd = new java.util.ArrayList<String>();
        cmd.add("./gradlew");
        cmd.add("test");
        if (rerun) cmd.add("--rerun");
        if (filter != null && !filter.isBlank()) {
            cmd.add("--tests");
            cmd.add(filter);
        }

        try {
            var pb = new ProcessBuilder(cmd);
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);
            var process = pb.start();

            String output;
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return toolErrorResult(id, "Test timed out after 120 seconds.\n\nPartial output:\n" + output);
            }

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                return toolResult(id, output);
            } else {
                return toolErrorResult(id, output);
            }

        } catch (Exception e) {
            return toolErrorResult(id, "Failed to run tests: " + e.getMessage());
        }
    }

    // ── Tool definitions (JSON Schema) ───────────────────────────────────

    private JsonObject evalToolDef() {
        var tool = new JsonObject();
        tool.addProperty("name", "irij_eval");
        tool.addProperty("description",
            "Evaluate Irij code in a persistent REPL session. " +
            "Bindings, functions, and type definitions persist across calls within the same session. " +
            "Use this for interactive exploration and incremental development.");

        var schema = new JsonObject();
        schema.addProperty("type", "object");
        var props = new JsonObject();

        var codeProp = new JsonObject();
        codeProp.addProperty("type", "string");
        codeProp.addProperty("description", "Irij source code to evaluate");
        props.add("code", codeProp);

        var sessionProp = new JsonObject();
        sessionProp.addProperty("type", "string");
        sessionProp.addProperty("description", "Session ID (default: 'default'). Use different IDs for independent sessions.");
        props.add("session_id", sessionProp);

        var resetProp = new JsonObject();
        resetProp.addProperty("type", "boolean");
        resetProp.addProperty("description", "Reset the session (fresh interpreter) before evaluating");
        props.add("reset", resetProp);

        schema.add("properties", props);
        var required = new JsonArray();
        required.add("code");
        schema.add("required", required);

        tool.add("inputSchema", schema);
        return tool;
    }

    private JsonObject runToolDef() {
        var tool = new JsonObject();
        tool.addProperty("name", "irij_run");
        tool.addProperty("description",
            "Run an Irij program with a fresh interpreter (no session state). " +
            "Provide either inline code or a file path. " +
            "Use this for running complete programs or .irj files.");

        var schema = new JsonObject();
        schema.addProperty("type", "object");
        var props = new JsonObject();

        var codeProp = new JsonObject();
        codeProp.addProperty("type", "string");
        codeProp.addProperty("description", "Inline Irij source code to execute");
        props.add("code", codeProp);

        var fileProp = new JsonObject();
        fileProp.addProperty("type", "string");
        fileProp.addProperty("description", "Absolute path to an .irj file to execute");
        props.add("file", fileProp);

        schema.add("properties", props);
        tool.add("inputSchema", schema);
        return tool;
    }

    private JsonObject testToolDef() {
        var tool = new JsonObject();
        tool.addProperty("name", "irij_test");
        tool.addProperty("description",
            "Run the Irij test suite via Gradle. " +
            "Returns test results including pass/fail counts. " +
            "Use filter to run specific test classes or methods.");

        var schema = new JsonObject();
        schema.addProperty("type", "object");
        var props = new JsonObject();

        var filterProp = new JsonObject();
        filterProp.addProperty("type", "string");
        filterProp.addProperty("description",
            "Gradle test filter (e.g. 'dev.irij.interpreter.InterpreterTest$Contracts'). Omit to run all tests.");
        props.add("filter", filterProp);

        var rerunProp = new JsonObject();
        rerunProp.addProperty("type", "boolean");
        rerunProp.addProperty("description", "Force re-run of tests (default: true)");
        props.add("rerun", rerunProp);

        schema.add("properties", props);
        tool.add("inputSchema", schema);
        return tool;
    }

    // ── JSON-RPC helpers ─────────────────────────────────────────────────

    private JsonObject jsonRpcResult(JsonElement id, JsonObject result) {
        var resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        resp.add("id", id);
        resp.add("result", result);
        return resp;
    }

    private JsonObject errorResponse(JsonElement id, int code, String message) {
        var resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        resp.add("id", id);
        var error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        resp.add("error", error);
        return resp;
    }

    private JsonObject toolResult(JsonElement id, String text) {
        var result = new JsonObject();
        var content = new JsonArray();
        var item = new JsonObject();
        item.addProperty("type", "text");
        item.addProperty("text", text);
        content.add(item);
        result.add("content", content);
        return jsonRpcResult(id, result);
    }

    private JsonObject toolErrorResult(JsonElement id, String text) {
        var result = new JsonObject();
        var content = new JsonArray();
        var item = new JsonObject();
        item.addProperty("type", "text");
        item.addProperty("text", text);
        content.add(item);
        result.add("content", content);
        result.addProperty("isError", true);
        return jsonRpcResult(id, result);
    }

    private void sendResponse(JsonObject response) {
        out.println(gson.toJson(response));
        out.flush();
    }

    // ── Utility ──────────────────────────────────────────────────────────

    private static String getStr(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    private static void log(String msg) {
        System.err.println("[irij-mcp] " + msg);
    }

    // ── Session (persistent interpreter for irij_eval) ───────────────────

    private static final class Session {
        final Interpreter interpreter;
        final IndirectOutputStream indirectOut;
        final BackgroundOutputStream backgroundOut;

        Session(Path sourcePath) {
            this.backgroundOut = new BackgroundOutputStream();
            this.indirectOut = new IndirectOutputStream(backgroundOut);
            var printStream = new PrintStream(indirectOut, true);
            this.interpreter = new Interpreter(printStream);
            if (sourcePath != null) {
                this.interpreter.setSourcePath(sourcePath);
            }
        }
    }
}
