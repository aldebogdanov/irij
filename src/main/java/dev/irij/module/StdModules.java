package dev.irij.module;

import java.io.PrintStream;

/**
 * Registers standard library module factories.
 *
 * <p>Most std modules are implemented in Irij ({@code src/main/resources/std/*.irj})
 * and loaded from the classpath by {@link ModuleRegistry}. This class only registers
 * Java-implemented modules that cannot be expressed in Irij.
 *
 * <p>Currently no Java-only std modules exist — all std modules are Irij source files.
 * This class serves as the extension point for future Java-backed modules.
 */
public final class StdModules {

    private StdModules() {}

    /** Register all standard library module factories. */
    public static void registerAll(ModuleRegistry registry, PrintStream out) {
        // All std modules are currently Irij source files loaded from classpath.
        // Future Java-only modules (e.g., std.net, std.async) would be registered here:
        //
        // registry.registerFactory("std.net", () -> {
        //     var env = new Environment();
        //     env.define("http-get", new BuiltinFn(...));
        //     return new ModuleValue("std.net", env);
        // });
    }
}
