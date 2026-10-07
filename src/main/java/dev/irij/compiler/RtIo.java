package dev.irij.compiler;

/** Split from RuntimeSupport (PR2 2026-07): RtIo domain. */
public final class RtIo {

    private RtIo() {}

    // ── Program arguments ───────────────────────────────────────────────

    /** What the program was started with; set first thing in every emitted
     *  {@code main(String[])}, so `irij file.irj a b` and a built jar agree. */
    private static volatile java.util.List<String> PROGRAM_ARGS = java.util.List.of();

    public static void setProgramArgs(String[] args) {
        PROGRAM_ARGS = args == null ? java.util.List.of() : java.util.List.of(args);
    }

    /** {@code program-args ()} — the arguments after the program, as a Vec of Str. */
    public static Object programArgs() {
        return new dev.irij.runtime.Values.IrijVector(new java.util.ArrayList<Object>(PROGRAM_ARGS));
    }


    // ── JSON (delegates to interp's Builtins helpers; same semantics) ──

    public static Object jsonParse(Object strArg) {
        String str = RtStrings.asStr(strArg, "json-parse");
        try {
            return dev.irij.runtime.Builtins.jsonToIrij(
                    com.google.gson.JsonParser.parseString(str));
        } catch (com.google.gson.JsonParseException e) {
            // Gson prefixes the cause's class name ("java.io.EOFException: …").
            String msg = e.getMessage() == null ? "malformed JSON" : e.getMessage();
            int colon = msg.indexOf(": ");
            if (msg.startsWith("java.") && colon > 0) msg = msg.substring(colon + 2);
            throw new dev.irij.IrijRuntimeError("json-parse: " + msg);
        }
    }


    public static Object jsonEncode(Object v) {
        return dev.irij.runtime.Builtins.irijToJson(v).toString();
    }


    public static Object jsonEncodePretty(Object v) {
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder()
                .setPrettyPrinting().create();
        return gson.toJson(dev.irij.runtime.Builtins.irijToJson(v));
    }


    // FileIO methods removed phase 3d — now in FsCapability.

    // ── Env / time / misc ────────────────────────────────────────────

    public static Object getEnv(Object nameArg) {
        String name = RtStrings.asStr(nameArg, "get-env");
        String v = System.getenv(name);
        return v != null ? v : dev.irij.runtime.Values.UNIT;
    }


    public static Object nowMs() {
        return System.currentTimeMillis();
    }

    public static Object tomlParse(Object strArg) {
        String s = RtStrings.asStr(strArg, "toml-parse");
        try {
            return dev.irij.runtime.Builtins.tomlValueToIrij(
                    new com.moandjiezana.toml.Toml().read(s).toMap());
        } catch (IllegalStateException e) {
            throw new dev.irij.IrijRuntimeError("toml-parse: " + e.getMessage());
        }
    }


    public static Object envBuiltin(Object[] args) {
        // env "NAME"            -> value or null
        // env "NAME" "default"  -> value or default
        if (args.length == 0) {
            throw new dev.irij.IrijRuntimeError("env requires at least one argument");
        }
        String name = RtStrings.asStr(args[0], "env");
        String v = System.getenv(name);
        if (v != null) return v;
        if (args.length >= 2) return RtStrings.asStr(args[args.length - 1], "env");
        return dev.irij.runtime.Values.UNIT;
    }
}
