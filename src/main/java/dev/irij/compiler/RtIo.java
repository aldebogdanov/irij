package dev.irij.compiler;

/** Split from RuntimeSupport (PR2 2026-07): RtIo domain. */
public final class RtIo {

    private RtIo() {}


    // ── JSON (delegates to interp's Builtins helpers; same semantics) ──

    public static Object jsonParse(Object strArg) {
        String str = RtStrings.asStr(strArg, "json-parse");
        try {
            return dev.irij.runtime.Builtins.jsonToIrij(
                    com.google.gson.JsonParser.parseString(str));
        } catch (com.google.gson.JsonSyntaxException e) {
            throw new dev.irij.IrijRuntimeError("json-parse: " + e.getMessage());
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
