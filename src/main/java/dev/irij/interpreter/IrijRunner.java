package dev.irij.interpreter;

import dev.irij.cli.IrijCli;

/**
 * Development-time entry point — delegates to IrijCli.
 * Referenced by the Gradle `run` task (before the shadow JAR exists).
 */
public final class IrijRunner {
    public static void main(String[] args) throws Exception {
        IrijCli.main(args);
    }
}
