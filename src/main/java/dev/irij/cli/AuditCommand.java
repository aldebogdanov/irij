package dev.irij.cli;

import dev.irij.compiler.IrijCompiler;
import dev.irij.compiler.TrustChecker;
import dev.irij.module.DependencyResolver;
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
 * standard library is trusted and summarised in one line.
 */
public final class AuditCommand {

    private AuditCommand() {}

    public static void run(String[] args) throws Exception {
        Path entry = entryPoint(args);
        Path root = entry.toAbsolutePath().getParent();
        List<Path> seedRoots = DependencyResolver.resolveSeedRoots(root, System.err);
        IrijCompiler.Audit audit = IrijCompiler.audit(entry, seedRoots);
        Trust trust = audit.trust();

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
                : "  (irij.toml [trust]: caps " + trust.caps() + ", jvm " + trust.jvm() + ")"));
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
        if (untrusted) {
            System.out.println();
            System.out.println("Some modules reach Java without [trust] listing them; the compiler refuses this program.");
            System.exit(1);
        }
    }

    private static String plural(long n, String what) {
        return n + " " + what + (n == 1 ? "" : "s");
    }

    private static Path entryPoint(String[] args) {
        for (String a : args) {
            if (!a.startsWith("-") && a.endsWith(".irj")) return Path.of(a);
        }
        for (String c : List.of("server.irj", "main.irj", "app.irj")) {
            if (Files.exists(Path.of(c))) return Path.of(c);
        }
        System.err.println("Error: no entry point found. Specify one: irij audit <file.irj>");
        System.err.println("Or create server.irj, main.irj, or app.irj in the project root.");
        System.exit(1);
        return null;
    }
}
