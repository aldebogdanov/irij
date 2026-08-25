package dev.irij.cli;

import dev.irij.IrijRuntimeError;
import dev.irij.runtime.QuintCapability;
import dev.irij.runtime.Values;

/**
 * {@code irij quint} — the toolchain side of model-based testing.
 *
 * <p>Only {@code doctor} for now: says whether the {@code quint}
 * binary is reachable and whether it is the version the ITF decoding
 * was verified against. Running a model is `irij run` or `irij test`,
 * because a model is ordinary Irij code.
 */
final class QuintCommand {

    static void run(String[] args) {
        String sub = args.length > 0 ? args[0] : "doctor";
        if (sub.equals("doctor")) {
            System.exit(doctor() ? 0 : 1);
        }
        System.err.println("Unknown quint subcommand: " + sub);
        System.err.println("Usage: irij quint doctor");
        System.exit(1);
    }

    /** True when traces can be generated here. */
    private static boolean doctor() {
        String found;
        try {
            found = Values.toIrijString(QuintCapability.version(Values.UNIT));
        } catch (IrijRuntimeError e) {
            System.out.println("quint     not usable");
            System.out.println("          " + e.getMessage());
            System.out.println();
            System.out.println("Generating traces needs it. Replaying a committed");
            System.out.println(".itf.json does not, so a repository's regression traces");
            System.out.println("still run here.");
            return false;
        }
        boolean tested = found.equals(QuintCapability.TESTED_VERSION);
        System.out.println("quint     " + found + (tested ? "" : "   (not the tested version)"));
        System.out.println("tested    " + QuintCapability.TESTED_VERSION);
        if (!tested) {
            System.out.println();
            System.out.println("ITF decoding was verified against " + QuintCapability.TESTED_VERSION
                    + " and may differ here.");
            System.out.println("A trace that decodes wrong is a bug worth reporting rather than");
            System.out.println("working around.");
        }
        System.out.println();
        System.out.println("Apalache is fetched by `quint verify` on first use, so `verify`");
        System.out.println("works without a separate install (and takes a few minutes the");
        System.out.println("first time).");
        return true;
    }

    private QuintCommand() {}
}
