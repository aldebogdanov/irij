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
        Ansi a = Ansi.detect();
        String found;
        try {
            found = Values.toIrijString(QuintCapability.version(Values.UNIT));
        } catch (IrijRuntimeError e) {
            for (String line : report(a, null, e.getMessage())) System.out.println(line);
            return false;
        }
        for (String line : report(a, found, null)) System.out.println(line);
        return true;
    }

    /** The doctor's report: {@code found} is the quint version, or null
     *  with {@code error} saying why quint can't be run. */
    static java.util.List<String> report(Ansi a, String found, String error) {
        var out = new java.util.ArrayList<String>();
        out.add(a.boldCyan("ℑ") + " " + a.bold("irij quint doctor"));
        out.add("");
        String tested = QuintCapability.TESTED_VERSION;
        if (found == null) {
            out.add("  " + a.red("✗") + " " + a.bold("quint   ") + "  " + a.boldRed("not usable"));
            out.add("              " + a.red(error));
            out.add("");
            out.add(a.dim("  Generating traces needs it. Replaying a committed .itf.json"));
            out.add(a.dim("  does not, so a repository's regression traces still run here."));
            return out;
        }
        boolean isTested = found.equals(tested);
        out.add(isTested
                ? "  " + a.green("✓") + " " + a.bold("quint   ") + "  " + a.green(found)
                        + a.dim("  (the tested version)")
                : "  " + a.yellow("⚠") + " " + a.bold("quint   ") + "  " + a.boldYellow(found)
                        + a.dim("  (tested: " + tested + ")"));
        out.add("  " + a.cyan("ℹ") + " " + a.bold("apalache") + "  "
                + a.dim("fetched by `quint verify` on first use"));
        out.add("");
        if (!isTested) {
            out.add(a.yellow("  ITF decoding was verified against " + tested + " and may differ here."));
            out.add(a.yellow("  A trace that decodes wrong is a bug worth reporting rather than"));
            out.add(a.yellow("  working around."));
            out.add("");
        }
        out.add(a.dim("  `verify` needs no separate Apalache install; its first run"));
        out.add(a.dim("  takes a few minutes."));
        return out;
    }

    private QuintCommand() {}
}
