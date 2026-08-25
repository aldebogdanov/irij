package dev.irij.runtime;

import dev.irij.IrijRuntimeError;
import dev.irij.runtime.Values.IrijMap;
import dev.irij.runtime.Values.IrijVector;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Capability provider for the {@code Quint} effect — the only place
 * in Irij that starts a {@code quint} process.
 *
 * <p>Bound from Irij with:
 * <pre>
 *   cap quint-cli :: Quint = "dev.irij.runtime.QuintCapability"
 * </pre>
 *
 * <p>Each method takes the driver's option map and returns a raw
 * result record — exit code, both streams, the reproduce command and
 * the ITF files quint wrote. It decides nothing: turning a non-zero
 * exit into a diagnosis is std.quint's job, where the message can be
 * read and changed without recompiling the runtime.
 *
 * <p>That split is also what makes the whole replay path testable
 * with no quint installed. Swap {@code default-quint} for a handler
 * that returns canned records and nothing here ever runs.
 */
public final class QuintCapability {

    /**
     * The Quint the ITF decoding in std.quint.itf was verified
     * against. A different version is a warning, not a refusal —
     * the format is stable enough that refusing would age worse
     * than the mismatch it guards.
     */
    public static final String TESTED_VERSION = "0.32.0";

    private QuintCapability() {}

    // ── Commands ────────────────────────────────────────────────────

    /** {@code quint --version}, as a bare string. */
    public static Object version(Object ignored) {
        Exec e = exec(null, List.of("--version"));
        if (e.exit != 0) {
            throw new IrijRuntimeError(
                    "quint --version exited " + e.exit + ": " + e.err.strip());
        }
        return e.out.strip();
    }

    /**
     * {@code quint run --mbt} — N random traces from the spec.
     *
     * <p>{@code --max-samples} is attempts and {@code --n-traces} is
     * traces written; quint defaults samples to 1 and rejects
     * n-traces greater than it, so traces acts as the floor rather
     * than the value.
     */
    public static Object run(Object optsArg) {
        IrijMap opts = asMap(optsArg, "quint-run");
        long traces = num(opts, "traces", 10);
        long samples = Math.max(num(opts, "max-samples", 0), traces);
        // An absent seed is generated rather than left to quint, so that
        // the reproduce line in a failure report is one that reproduces.
        long seed = num(opts, "seed", Math.abs(new java.util.Random().nextInt()));
        return withSeed(seed, inScratch(opts, false, dir -> {
            List<String> args = new ArrayList<>(List.of(
                    "run", specFileName(opts),
                    "--mbt",
                    "--seed=" + seed,
                    "--n-traces=" + traces,
                    "--max-steps=" + num(opts, "max-steps", 20),
                    "--max-samples=" + samples,
                    "--out-itf=" + dir + "/run_{seq}.itf.json",
                    "--verbosity=0"));
            addIfPresent(args, opts, "main", "--main=");
            addIfPresent(args, opts, "init-action", "--init=");
            addIfPresent(args, opts, "step-action", "--step=");
            return args;
        }));
    }

    /**
     * {@code quint test} — one scripted {@code run} from the spec.
     *
     * <p>{@code --match} is a regex, anchored here so {@code depositTest}
     * cannot also select {@code depositTestTwo}. There is no
     * {@code --mbt} for this subcommand, so its traces carry no
     * {@code mbt::} variables and only drive an implementation when
     * the spec tracks its own action.
     */
    public static Object test(Object optsArg) {
        IrijMap opts = asMap(optsArg, "quint-test");
        return inScratch(opts, false, dir -> {
            List<String> args = new ArrayList<>(List.of(
                    "test", specFileName(opts),
                    "--out-itf=" + dir + "/test_{test}_{seq}.itf.json",
                    "--verbosity=0"));
            addIfPresent(args, opts, "main", "--main=");
            String t = str(opts, "test");
            if (t != null) args.add("--match=^" + t + "$");
            String seed = str(opts, "seed");
            if (seed != null) args.add("--seed=" + seed);
            String samples = str(opts, "max-samples");
            if (samples != null) args.add("--max-samples=" + samples);
            return args;
        });
    }

    /**
     * {@code quint verify} — Apalache proves the invariant or writes
     * a counterexample.
     *
     * <p>Runs in the scratch directory rather than the spec's own,
     * because Apalache writes an {@code _apalache-out/} directory of
     * logs into the working directory and cannot be told not to. The
     * spec is passed absolute so it still resolves; the logs are
     * deleted with the scratch directory.
     *
     * <p>The outcome is not in the exit code — holding exits 0, and a
     * counterexample, an unknown invariant, a spec that will not
     * typecheck and a missing file all exit 1. What separates them is
     * whether a trace was written, so that is what std.quint branches
     * on.
     */
    public static Object verify(Object optsArg) {
        IrijMap opts = asMap(optsArg, "quint-verify");
        return inScratch(opts, true, dir -> {
            List<String> args = new ArrayList<>(List.of(
                    "verify", specPath(opts).toAbsolutePath().toString(),
                    "--out-itf=" + dir + "/verify.itf.json",
                    "--verbosity=0"));
            addIfPresent(args, opts, "main", "--main=");
            addIfPresent(args, opts, "invariant", "--invariant=");
            addIfPresent(args, opts, "init-action", "--init=");
            addIfPresent(args, opts, "step-action", "--step=");
            addIfPresent(args, opts, "max-steps", "--max-steps=");
            return args;
        });
    }

    // ── Running one command ─────────────────────────────────────────

    private interface ArgsFor { List<String> apply(String scratchDir); }

    /** The result record, with the seed that produced it recorded in it. */
    private static Object withSeed(long seed, Object result) {
        Map<String, Object> out = new LinkedHashMap<>(((IrijMap) result).entries());
        out.put("seed", seed);
        return new IrijMap(out);
    }

    private record Exec(int exit, String out, String err) {}

    /**
     * Run quint with ITF going into a scratch directory that is gone
     * before this returns, the files having been read out of it first.
     *
     * <p>{@code runInScratch} says where quint itself runs. For
     * {@code run} and {@code test} that is the spec's own directory,
     * so sibling modules resolve and {@code #meta.source} stays a bare
     * filename. For {@code verify} it is the scratch directory — see
     * {@link #verify}.
     */
    private static Object inScratch(IrijMap opts, boolean runInScratch, ArgsFor argsFor) {
        Path specDir = specPath(opts).toAbsolutePath().getParent();
        Path scratch = tempDir();
        List<String> args = argsFor.apply(scratch.toString());
        try {
            Exec e = exec(runInScratch ? scratch : specDir, args);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("exit", (long) e.exit);
            out.put("stdout", e.out);
            out.put("stderr", e.err);
            out.put("cmd", reproduce(args));
            out.put("dir", specDir == null ? "." : specDir.toString());
            out.put("traces", collect(scratch));
            return new IrijMap(out);
        } finally {
            deleteTree(scratch);
        }
    }

    private static Exec exec(Path dir, List<String> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("quint");
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (dir != null) pb.directory(dir.toFile());
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IrijRuntimeError(
                    "quint is not on PATH, so traces cannot be generated"
                    + " (replaying a committed .itf.json needs no quint)."
                    + " Install it with: npm i -g @informalsystems/quint");
        }
        // Both streams are pipes and a pipe that fills blocks the
        // writer, so draining one after the other deadlocks as soon as
        // the second fills while the first is still being read.
        CompletableFuture<String> err =
                CompletableFuture.supplyAsync(() -> drain(p.getErrorStream()));
        String out = drain(p.getInputStream());
        try {
            return new Exec(p.waitFor(), out, err.join());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IrijRuntimeError("interrupted waiting for quint");
        }
    }

    private static String drain(InputStream is) {
        try (is) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    // ── Files ───────────────────────────────────────────────────────

    /** The ITF files quint wrote, in the order it numbered them. */
    private static IrijVector collect(Path dir) {
        File[] files = dir.toFile().listFiles();
        if (files == null) return new IrijVector(List.of());
        List<File> itf = new ArrayList<>();
        for (File f : files) {
            if (f.getName().endsWith(".itf.json")) itf.add(f);
        }
        itf.sort(Comparator.comparingLong(QuintCapability::seqOf)
                .thenComparing(File::getName));
        List<Object> out = new ArrayList<>(itf.size());
        for (File f : itf) {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("name", f.getName());
            rec.put("json", readFile(f));
            out.add(new IrijMap(rec));
        }
        return new IrijVector(out);
    }

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private static long seqOf(File f) {
        Matcher m = DIGITS.matcher(f.getName());
        return m.find() ? Long.parseLong(m.group()) : 0L;
    }

    private static String readFile(File f) {
        try {
            return Files.readString(f.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IrijRuntimeError("cannot read the trace quint wrote: " + e.getMessage());
        }
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("irij-quint-");
        } catch (IOException e) {
            throw new IrijRuntimeError("cannot create a scratch directory: " + e.getMessage());
        }
    }

    private static void deleteTree(Path dir) {
        File root = dir.toFile();
        File[] kids = root.listFiles();
        if (kids != null) {
            for (File f : kids) {
                if (f.isDirectory()) deleteTree(f.toPath()); else f.delete();
            }
        }
        root.delete();
    }

    // ── Arguments ───────────────────────────────────────────────────

    /**
     * The command as a person could re-run it. The scratch directory
     * is deleted before we return, so its {@code --out-itf} path is
     * reduced to a basename rather than pointing at nothing.
     */
    private static IrijVector reproduce(List<String> args) {
        List<Object> out = new ArrayList<>(args.size() + 1);
        out.add("quint");
        for (String a : args) {
            if (a.startsWith("--out-itf=")) {
                out.add("--out-itf=" + new File(a.substring("--out-itf=".length())).getName());
            } else {
                out.add(a);
            }
        }
        return new IrijVector(out);
    }

    private static void addIfPresent(List<String> args, IrijMap opts, String key, String flag) {
        String v = str(opts, key);
        if (v != null) args.add(flag + v);
    }

    private static Path specPath(IrijMap opts) {
        String spec = str(opts, "spec-file");
        if (spec == null) {
            throw new IrijRuntimeError("the model has no spec-file; name the .qnt it is checked against");
        }
        return Path.of(spec);
    }

    private static String specFileName(IrijMap opts) {
        return specPath(opts).getFileName().toString();
    }

    // ── Reading the option map ──────────────────────────────────────

    private static IrijMap asMap(Object v, String who) {
        if (v instanceof IrijMap m) return m;
        throw new IrijRuntimeError(who + " expects a Map of options, got "
                + Values.typeName(v));
    }

    /** A value as a command-line string, or null when absent. */
    private static String str(IrijMap opts, String key) {
        Object v = opts.entries().get(key);
        if (v == null || v == Values.UNIT) return null;
        return Values.toIrijString(v);
    }

    private static long num(IrijMap opts, String key, long fallback) {
        Object v = opts.entries().get(key);
        if (v == null || v == Values.UNIT) return fallback;
        if (v instanceof Long l) return l;
        throw new IrijRuntimeError(key + " must be an Int, got " + Values.typeName(v));
    }
}
