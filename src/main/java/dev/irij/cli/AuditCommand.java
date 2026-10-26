package dev.irij.cli;

import dev.irij.compiler.IrijCompiler;
import dev.irij.compiler.TrustChecker;
import dev.irij.module.DependencyResolver;
import dev.irij.module.ProjectFile;
import dev.irij.module.jvm.JvmDeps;
import dev.irij.module.ProjectFile.Trust;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code irij audit [file.irj]} — every place the program reaches Java,
 * by module: Java {@code cap} declarations and hand-written Java interop,
 * in the entry file, its modules and its seeds. With a {@code [trust]}
 * section in irij.toml, each module is marked trusted or not, and the
 * command exits 1 when one isn't (the compiler refuses that program). The
 * standard library is trusted and summarised in one line. Then the Java
 * libraries ({@code [jvm]}) the project and each seed bring, each seed
 * marked against {@code [trust] jars}. Nothing is downloaded.
 *
 * <p>{@code --fix} first adds to irij.toml's {@code [trust]} (creating
 * the section, or the file, if needed) exactly the entries this program
 * needs to compile: each module with a cap or Java of its own, each seed
 * with {@code [jvm]} libraries. It's the smallest policy that lets the
 * program build — written down for review, never wider than today.
 */
public final class AuditCommand {

    private AuditCommand() {}

    /** What to audit: a program (its entry file), or — in a seed, which
     *  has modules and no program — every module in it. */
    private record Target(Path root, String label, String source, boolean seed) {}

    public static void run(String[] args) throws Exception {
        Target t = target(args);
        Path root = t.root();
        Object entry = t.label();
        var manifest = ProjectFile.parseFile(root.resolve("irij.toml"));
        Map<String, Path> seeds = manifest.deps().isEmpty() ? Map.of()
                : new DependencyResolver(root, System.err).resolveAll(manifest.deps());
        var seedRoots = new java.util.ArrayList<>(seeds.values());
        if (t.seed()) seedRoots.add(0, root);     // the seed's own modules, found as a seed's
        IrijCompiler.Audit audit = IrijCompiler.auditSource(t.source(), "audit.irj", root, seedRoots);
        Trust trust = audit.trust();

        if (java.util.Arrays.asList(args).contains("--fix")) {
            fix(root.resolve("irij.toml"), audit, JvmDeps.plan(manifest, seeds),
                    dev.irij.module.jvm.JavaSources.plan(manifest, root, seeds), trust);
            String[] rest = java.util.Arrays.stream(args).filter(a -> !a.equals("--fix")).toArray(String[]::new);
            run(rest);
            return;
        }

        Map<String, List<TrustChecker.Site>> byModule = new LinkedHashMap<>();
        int stdCaps = 0, stdJvm = 0;
        for (TrustChecker.Site s : audit.sites()) {
            if (Trust.isStd(s.module())) {
                if (s.kind() == TrustChecker.Kind.CAP) stdCaps++; else stdJvm++;
                continue;
            }
            byModule.computeIfAbsent(s.module(), k -> new java.util.ArrayList<>()).add(s);
        }

        System.out.println("Java in " + entry + (trust == null
                ? "  (irij.toml has no [trust]: nothing is enforced)"
                : "  (irij.toml [trust]: caps " + trust.caps() + ", jvm " + trust.jvm() + ", jars " + trust.jars()
                        + ", java " + trust.java() + ")"));
        System.out.println();
        boolean untrusted = false;
        if (byModule.isEmpty()) {
            System.out.println("  no Java outside the standard library");
        }
        for (var e : byModule.entrySet()) {
            String module = e.getKey();
            List<TrustChecker.Site> sites = e.getValue();
            long caps = sites.stream().filter(s -> s.kind() == TrustChecker.Kind.CAP).count();
            long jvm = sites.size() - caps;
            String mark = "";
            if (trust != null) {
                boolean ok = (caps == 0 || trust.allowsCaps(module)) && (jvm == 0 || trust.allowsJvm(module));
                mark = ok ? "  trusted" : "  NOT TRUSTED";
                untrusted |= !ok;
            }
            System.out.println("  " + module + "  (" + plural(caps, "cap") + ", "
                    + plural(jvm, "Java interop site") + ")" + mark);
            for (TrustChecker.Site s : sites) {
                System.out.println("    " + s.where() + "  "
                        + (s.kind() == TrustChecker.Kind.CAP ? "cap " : "") + s.what());
            }
        }
        System.out.println();
        System.out.println("  std  (" + plural(stdCaps, "cap") + ", " + plural(stdJvm, "Java interop site")
                + ")  always trusted");

        var plan = JvmDeps.plan(manifest, seeds);
        boolean untrustedJars = printJars(root, plan);
        var javaPlan = dev.irij.module.jvm.JavaSources.plan(manifest, root, seeds);
        boolean untrustedJava = printJava(javaPlan);
        if (untrusted) {
            System.out.println();
            System.out.println("Some modules reach Java without [trust] listing them; the compiler refuses this program.");
        }
        if (untrustedJars) {
            System.out.println();
            System.out.println("Some seeds bring Java libraries without [trust] jars listing them; no Java library is downloaded until it does.");
        }
        if (untrustedJava) {
            System.out.println();
            System.out.println("Some seeds ship Java sources without [trust] java listing them; nothing is compiled until it does.");
        }
        if (untrusted || untrustedJars || untrustedJava) System.exit(1);
    }

    /** Write what [trust] lacks for this program; print what changed. */
    private static void fix(Path toml, IrijCompiler.Audit audit, JvmDeps.Plan plan,
                            dev.irij.module.jvm.JavaSources.Plan javaPlan, Trust trust)
            throws java.io.IOException {
        var caps = new java.util.ArrayList<String>();
        var jvm = new java.util.ArrayList<String>();
        var jars = new java.util.ArrayList<String>();
        for (TrustChecker.Site s : audit.sites()) {
            if (Trust.isStd(s.module())) continue;
            if (s.kind() == TrustChecker.Kind.CAP) {
                if (trust == null || !trust.allowsCaps(s.module())) caps.add(s.module());
            } else if (trust == null || !trust.allowsJvm(s.module())) {
                jvm.add(s.module());
            }
        }
        for (JvmDeps.Request r : plan.requests()) {
            if (r.seed() != null && (trust == null || !trust.allowsJars(r.seed()))) jars.add(r.seed());
        }
        var javaSeeds = new java.util.ArrayList<String>();
        for (String seed : javaPlan.seeds()) {
            if (trust == null || !trust.allowsJava(seed)) javaSeeds.add(seed);
        }
        var add = dev.irij.module.TrustFix.entries(caps, jvm, jars, javaSeeds);
        if (trust != null && add.isEmpty()) {
            System.out.println("irij.toml [trust] already lets this program build; nothing to add.");
            System.out.println();
            return;
        }
        String before = Files.exists(toml) ? Files.readString(toml) : "";
        String after;
        try {
            after = dev.irij.module.TrustFix.apply(before, add);
        } catch (IllegalStateException e) {
            System.err.println("irij audit --fix: " + e.getMessage());
            System.exit(1);
            return;
        }
        Files.writeString(toml, after);
        System.out.println((trust == null ? "Wrote [trust] to " : "Added to [trust] in ") + toml.getFileName()
                + (add.isEmpty() ? " (empty: no Java outside std and the project's own [jvm])" : ":"));
        for (var e : add.entrySet()) System.out.println("  " + e.getKey() + " += " + e.getValue());
        System.out.println("Review it: each entry lets that module or seed reach Java.");
        System.out.println();
    }

    /** The Java libraries section. True when a seed's libraries aren't trusted. */
    static boolean printJars(Path root, JvmDeps.Plan plan) throws java.io.IOException {
        System.out.println();
        if (plan.isEmpty()) {
            System.out.println("Java libraries: none ([jvm] is empty here and in every seed)");
            return false;
        }
        var locked = JvmDeps.lockedJars(root, plan);
        System.out.println("Java libraries" + (locked == null
                ? "  (irij.lock missing or out of date; the next run resolves [jvm])"
                : "  (irij.lock: " + plural(locked.size(), "jar") + " with dependencies)"));
        System.out.println();
        for (var e : JvmDeps.byDeclarer(plan).entrySet()) {
            String who = e.getKey();
            String mark = "";
            if (!who.equals("irij.toml") && plan.trustEnforced()) {
                mark = plan.untrusted().contains(who) ? "  NOT TRUSTED" : "  trusted";
            }
            System.out.println("  " + who + mark);
            for (var d : e.getValue()) System.out.println("    " + d.coord());
        }
        for (String n : plan.notes()) System.out.println("  note: " + n);
        return !plan.untrusted().isEmpty();
    }

    /** The Java sources section. True when a seed's sources aren't trusted. */
    static boolean printJava(dev.irij.module.jvm.JavaSources.Plan plan) {
        System.out.println();
        if (plan.isEmpty()) {
            System.out.println("Java sources: none (no java/ here or in any seed)");
            return false;
        }
        System.out.println("Java sources  (java/, compiled into the program's Java libraries)");
        System.out.println();
        for (var u : plan.units()) {
            String mark = "";
            if (u.seed() != null && plan.trustEnforced()) {
                mark = plan.untrusted().contains(u.seed()) ? "  NOT TRUSTED" : "  trusted";
            }
            System.out.println("  " + u.who() + "  (" + plural(u.files().size(), "file") + ")" + mark);
            Path dir = u.root().resolve(dev.irij.module.jvm.JavaSources.DIR);
            for (Path f : u.files()) {
                System.out.println("    java/" + dir.relativize(f).toString().replace('\\', '/'));
            }
        }
        return !plan.untrusted().isEmpty();
    }

    private static String plural(long n, String what) {
        return n + " " + what + (n == 1 ? "" : "s");
    }

    private static Target target(String[] args) throws java.io.IOException {
        for (String a : args) {
            if (!a.startsWith("-") && a.endsWith(".irj")) return program(Path.of(a));
        }
        for (String c : List.of("server.irj", "main.irj", "app.irj")) {
            Path p = Path.of(c);
            if (Files.exists(p) && moduleName(p) == null) return program(p);
        }
        Path root = Path.of("").toAbsolutePath();
        if (Files.exists(root.resolve("irij.toml"))) {
            var modules = new java.util.ArrayList<String>();
            try (var files = Files.list(root)) {
                for (Path f : files.filter(f -> f.toString().endsWith(".irj")).sorted().toList()) {
                    String m = moduleName(f);
                    if (m != null) modules.add(m);
                }
            }
            if (!modules.isEmpty()) {
                var src = new StringBuilder();
                for (int i = 0; i < modules.size(); i++) {
                    src.append("use ").append(modules.get(i)).append(" :as audit").append(i).append('\n');
                }
                return new Target(root, "the seed's modules (" + String.join(", ", modules) + ")",
                        src.toString(), true);
            }
        }
        System.err.println("Error: no entry point found. Specify one: irij audit <file.irj>");
        System.err.println("Or create server.irj, main.irj, or app.irj in the project root;"
                + " in a seed, `irij audit` covers every module.");
        System.exit(1);
        return null;
    }

    private static Target program(Path entry) throws java.io.IOException {
        return new Target(entry.toAbsolutePath().getParent(), entry.toString(), Files.readString(entry), false);
    }

    /** The module a file declares ({@code mod uzor.core}), or null for a
     *  program. */
    private static String moduleName(Path file) throws java.io.IOException {
        for (String line : Files.readAllLines(file)) {
            var m = java.util.regex.Pattern.compile("^mod\\s+([A-Za-z0-9_.-]+)\\s*$").matcher(line);
            if (m.matches()) return m.group(1);
        }
        return null;
    }
}
