package dev.irij.compiler;

import dev.irij.ast.Decl;
import dev.irij.ast.Expr;
import dev.irij.ast.Node.SourceLoc;
import dev.irij.module.ProjectFile.Trust;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a program reaches Java, and whether irij.toml's {@code [trust]}
 * lets it.
 *
 * <p>Two kinds of site: a Java {@code cap} declaration (it binds a Java
 * class to an effect — a call through it performs that effect, see
 * capabilities.md) and hand-written Java interop ({@code Class/member},
 * {@code obj#member}, {@code #member}). {@link #sites} finds them all, in
 * every inlined module, by walking the AST records reflectively — every
 * node is a record, so no node type can be missed as the language grows.
 * {@link #enforce} refuses the ones in a module {@code [trust]} doesn't
 * list; {@code irij audit} prints them.
 */
public final class TrustChecker {

    /** A decl's module (null for the program — "main") and file. */
    public record Origin(String module, String file) {
        public String moduleName() { return module == null ? "main" : module; }
    }

    public enum Kind { CAP, JVM }

    /** {@code what}: the Java it names ({@code java.time.Instant/now},
     *  {@code #toString}, or a cap's {@code name → Class (Effect)}). */
    public record Site(Kind kind, String module, String file, String what, SourceLoc loc) {
        public String where() {
            return file + (loc != null ? ":" + loc.line() + ":" + loc.col() : "");
        }
    }

    private TrustChecker() {}

    public static List<Site> sites(List<Decl> decls, Map<Decl, Origin> origins) {
        var out = new ArrayList<Site>();
        for (Decl d : decls) {
            if (d instanceof Decl.ModDecl) continue;
            Origin o = origins.getOrDefault(d, new Origin(null, "?"));
            walk(d, o, out);
        }
        return out;
    }

    /** Refuses every site in a module {@code trust} doesn't list, naming
     *  them all and the line to add. */
    public static void enforce(List<Site> sites, Trust trust) {
        var bad = new ArrayList<Site>();
        for (Site s : sites) {
            boolean ok = s.kind() == Kind.CAP ? trust.allowsCaps(s.module()) : trust.allowsJvm(s.module());
            if (!ok) bad.add(s);
        }
        if (bad.isEmpty()) return;
        var sb = new StringBuilder("irij.toml [trust] doesn't let these modules reach Java:");
        for (Site s : bad) {
            sb.append("\n  ").append(s.where()).append("  ").append(s.module()).append(": ")
              .append(s.kind() == Kind.CAP ? "cap " : "Java interop ").append(s.what())
              .append("  — add \"").append(s.module()).append("\" to [trust] ")
              .append(s.kind() == Kind.CAP ? "caps" : "jvm");
        }
        sb.append("\nPut Java behind a cap in a trusted module, and the code that uses it"
                + " needs no Java of its own (`irij audit` lists every site).");
        throw new IrijCompiler.CompileException(sb.toString());
    }

    /** With no {@code [trust]}: one warning naming the seed modules that
     *  reach Java, since `irij build` will refuse the project until
     *  {@code [trust]} says whether they may. Nothing when none do. */
    public static void warnUntrusted(List<Site> sites, java.util.Set<String> seedModules,
                                     java.util.function.Consumer<String> warn) {
        var bySeedModule = new java.util.TreeMap<String, Integer>();
        for (Site s : sites) {
            if (seedModules.contains(s.module())) bySeedModule.merge(s.module(), 1, Integer::sum);
        }
        if (bySeedModule.isEmpty()) return;
        var sb = new StringBuilder("warning: irij.toml has no [trust], and seeds reach Java:");
        for (var e : bySeedModule.entrySet()) {
            sb.append("\n  ").append(e.getKey()).append(" (").append(e.getValue())
              .append(e.getValue() == 1 ? " site)" : " sites)");
        }
        sb.append("\n`irij build` refuses a project without [trust]; `irij audit --fix` writes one"
                + " (review it), `irij audit` lists every site.");
        warn.accept(sb.toString());
    }

    // ── The walk ────────────────────────────────────────────────────

    private static final Map<Class<?>, Method[]> ACCESSORS = new ConcurrentHashMap<>();

    private static void walk(Object node, Origin o, List<Site> out) {
        if (node == null || node instanceof SourceLoc || node instanceof String) return;
        switch (node) {
            case Expr.JavaRef jr -> {
                out.add(new Site(Kind.JVM, o.moduleName(), o.file(), jr.ref(), jr.loc()));
                return;
            }
            case Expr.JavaMember jm ->
                    out.add(new Site(Kind.JVM, o.moduleName(), o.file(), "#" + jm.member(), jm.loc()));
            case Expr.JavaMemberRef jr ->
                    out.add(new Site(Kind.JVM, o.moduleName(), o.file(), "#" + jr.member(), jr.loc()));
            case Decl.CapDecl cap when cap.providerClass() != null -> {
                out.add(new Site(Kind.CAP, o.moduleName(), o.file(),
                        ClassEmitter.displayName(cap.name()) + " = " + cap.providerClass()
                                + " (" + cap.effectName() + ")", cap.loc()));
                return;
            }
            case Collection<?> c -> {
                for (Object x : c) walk(x, o, out);
                return;
            }
            case Map<?, ?> m -> {
                for (Object x : m.values()) walk(x, o, out);
                return;
            }
            case Optional<?> opt -> {
                opt.ifPresent(x -> walk(x, o, out));
                return;
            }
            default -> {}
        }
        if (!node.getClass().isRecord()) return;
        for (Method acc : accessors(node.getClass())) {
            try {
                walk(acc.invoke(node), o, out);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("TrustChecker can't read " + acc, e);
            }
        }
    }

    private static Method[] accessors(Class<?> c) {
        return ACCESSORS.computeIfAbsent(c, k -> {
            RecordComponent[] rcs = k.getRecordComponents();
            Method[] ms = new Method[rcs.length];
            for (int i = 0; i < rcs.length; i++) {
                ms[i] = rcs[i].getAccessor();
                ms[i].setAccessible(true);
            }
            return ms;
        });
    }
}
