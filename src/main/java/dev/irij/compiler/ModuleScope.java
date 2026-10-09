package dev.irij.compiler;

import dev.irij.ast.Decl;
import dev.irij.ast.Expr;
import dev.irij.ast.Node;
import dev.irij.ast.Node.SourceLoc;
import dev.irij.ast.Pattern;
import dev.irij.ast.SpecExpr;
import dev.irij.ast.Stmt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the names one source file uses against what the file declares
 * and imports, before {@link ModuleInliner} flattens the program.
 *
 * <p>A bare name means, in order: a local (a parameter, a pattern variable,
 * a local binding), the file's own top-level definition, a name the file
 * imports with {@code :open} or {@code {names}}, or a builtin. A module's
 * values live under private names ({@link ModulePrivacy}), so an imported
 * name is rewritten to the private name it stands for, and an unimported one
 * cannot reach the module at all. {@code alias.name} after
 * {@code use m :as alias} is rewritten the same way, unless a local called
 * {@code alias} is in scope: then it is that local's field.
 *
 * <p>Specs (with their variants), effects (with their ops), protos (with
 * their methods) and newtypes keep their names program-wide, so for them
 * this check is the whole of the enforcement: a file names one only when it
 * declares or imports it. The effects builtins perform ({@code Console},
 * {@code Time}, …) need no import.
 *
 * <p>A name that is none of these but is some module's is a compile error
 * saying which module has it and how to import it; so is a module that
 * names something only the program defines. Any other unknown name is left
 * for the emitter to report.
 */
final class ModuleScope {

    private ModuleScope() {}

    /**
     * What a module offers its importers.
     *
     * @param module   the module's qualified name
     * @param values   pub fn, binding, handler and cap names → the name each
     *                 has in the flat program
     * @param types    pub spec (newtypes included), effect and proto names →
     *                 what importing one brings: itself, then its variants,
     *                 ops or methods
     * @param kinds    each of {@code types}' keys → "spec", "effect" or
     *                 "proto", for messages
     * @param privates names the module declares without {@code pub}
     */
    record Exports(String module, Map<String, String> values,
                   Map<String, List<String>> types, Map<String, String> kinds,
                   Set<String> privates) {

        /** The pub type-level group {@code name} belongs to, or null. */
        String groupOf(String name) {
            for (var e : types.entrySet()) if (e.getValue().contains(name)) return e.getKey();
            return null;
        }
    }

    /** What a privatized module exports, given its pub values' flat names. */
    static Exports exportsOf(String module, List<Decl> decls, Map<String, String> values) {
        Map<String, List<String>> types = new LinkedHashMap<>();
        Map<String, String> kinds = new HashMap<>();
        Set<String> privates = new HashSet<>();
        for (Decl d : decls) {
            boolean isPub = d instanceof Decl.PubDecl;
            Node inner = d instanceof Decl.PubDecl pd ? pd.inner() : d;
            List<String> group = typeGroup(inner);
            if (group != null) {
                if (isPub) {
                    types.put(group.get(0), group);
                    kinds.put(group.get(0), kindOf(inner));
                } else {
                    privates.addAll(group);
                }
                continue;
            }
            for (String n : valueNames(inner)) {
                String shown = ClassEmitter.displayName(n);
                if (!values.containsKey(shown)) privates.add(shown);
            }
        }
        return new Exports(module, Map.copyOf(values), types, kinds, privates);
    }

    /** The top-level names {@code decls} define: values and type-level. */
    static Set<String> ownNames(List<Decl> decls) {
        Set<String> out = new HashSet<>();
        for (Decl d : decls) {
            Node inner = d instanceof Decl.PubDecl pd ? pd.inner() : d;
            List<String> group = typeGroup(inner);
            if (group != null) out.addAll(group);
            out.addAll(valueNames(inner));
        }
        return out;
    }

    private static List<String> typeGroup(Node d) {
        List<String> g = new ArrayList<>();
        switch (d) {
            case Decl.SpecDecl sd -> {
                g.add(sd.name());
                if (sd.body() instanceof Decl.SpecBody.SumSpec ss) {
                    for (var v : ss.variants()) g.add(v.name());
                }
            }
            case Decl.EffectDecl ed -> {
                g.add(ed.name());
                for (var op : ed.ops()) g.add(op.name());
            }
            case Decl.ProtoDecl pd -> {
                g.add(pd.name());
                for (var m : pd.methods()) g.add(m.name());
            }
            default -> { return null; }
        }
        return g;
    }

    private static String kindOf(Node d) {
        return switch (d) {
            case Decl.SpecDecl sd -> "spec";
            case Decl.EffectDecl ed -> "effect";
            case Decl.ProtoDecl pd -> "proto";
            default -> throw new IllegalArgumentException("not a type-level decl: " + d);
        };
    }

    /** The value names (fns, handlers, caps, binding targets) {@code d} defines. */
    static List<String> valueNames(Node d) {
        return switch (d) {
            case Decl.FnDecl fn -> List.of(fn.name());
            case Decl.HandlerDecl hd -> List.of(hd.name());
            case Decl.CapDecl cd -> List.of(cd.name());
            case Decl.BindingDecl bd -> {
                Set<String> out = new LinkedHashSet<>();
                switch (bd.stmt()) {
                    case Stmt.Bind b -> targetNames(b.target(), out);
                    case Stmt.MutBind mb -> targetNames(mb.target(), out);
                    default -> { }
                }
                yield List.copyOf(out);
            }
            default -> List.of();
        };
    }

    private static void targetNames(Stmt.BindTarget t, Set<String> out) {
        switch (t) {
            case Stmt.BindTarget.Simple sm -> out.add(sm.name());
            case Stmt.BindTarget.Destructure ds -> binders(ds.pattern(), out);
        }
    }

    /** The variables pattern {@code p} binds. */
    private static void binders(Pattern p, Set<String> out) {
        if (p == null) return;
        switch (p) {
            case Pattern.VarPat vp -> out.add(vp.name());
            case Pattern.SpreadPat sp -> { if (!"_".equals(sp.name())) out.add(sp.name()); }
            case Pattern.ConstructorPat cp -> { for (Pattern a : cp.args()) binders(a, out); }
            case Pattern.KeywordPat kp -> binders(kp.arg(), out);
            case Pattern.GroupedPat gp -> binders(gp.inner(), out);
            case Pattern.VectorPat vp -> {
                for (Pattern e : vp.elements()) binders(e, out);
                binders(vp.spread(), out);
            }
            case Pattern.TuplePat tp -> { for (Pattern e : tp.elements()) binders(e, out); }
            case Pattern.DestructurePat dp -> { for (var f : dp.fields()) binders(f.value(), out); }
            case Pattern.LitPat lp -> { }
            case Pattern.WildcardPat wp -> { }
            case Pattern.UnitPat up -> { }
        }
    }

    /** The names one file can reach through its {@code use} lines. */
    static final class Imports {
        /** alias → the module it names. */
        final Map<String, Exports> aliases = new LinkedHashMap<>();
        /** bare value name → (module → the name it has in the flat program). */
        final Map<String, Map<String, String>> values = new HashMap<>();
        /** bare type-level names: specs, variants, effects, ops, … */
        final Set<String> types = new HashSet<>();

        /** Adds {@code use <ex.module> <modifier>}. */
        void add(Decl.UseModifier modifier, Exports ex, String file, SourceLoc loc) {
            switch (modifier) {
                case Decl.UseModifier.Open o -> {
                    ex.values().forEach((n, flat) -> addValue(n, ex.module(), flat));
                    ex.types().values().forEach(types::addAll);
                }
                case Decl.UseModifier.As as -> aliases.put(as.alias(), ex);
                case Decl.UseModifier.Selective sel -> {
                    for (String n : sel.names()) {
                        if (ex.values().containsKey(n)) {
                            addValue(n, ex.module(), ex.values().get(n));
                        } else if (ex.types().containsKey(n)) {
                            types.addAll(ex.types().get(n));
                        } else if (ex.groupOf(n) != null) {
                            types.add(n);
                        } else {
                            throw new IrijCompiler.CompileException("`use " + ex.module() + " {" + n
                                    + "}`: " + ex.module() + " exports no `" + n + "`"
                                    + (ex.privates().contains(n) ? " (it is private to " + ex.module() + ")" : "")
                                    + at(file, loc));
                        }
                    }
                }
            }
        }

        /** Adds what {@code ex} exports through {@code modifier} to {@code out}:
         *  {@code pub use} re-exports. */
        static void reexport(Decl.UseModifier modifier, Exports ex, Map<String, String> outValues,
                             Map<String, List<String>> outTypes, Map<String, String> outKinds,
                             String file, SourceLoc loc) {
            switch (modifier) {
                case Decl.UseModifier.Open o -> {
                    outValues.putAll(ex.values());
                    outTypes.putAll(ex.types());
                    outKinds.putAll(ex.kinds());
                }
                case Decl.UseModifier.Selective sel -> {
                    for (String n : sel.names()) {
                        if (ex.values().containsKey(n)) outValues.put(n, ex.values().get(n));
                        else if (ex.types().containsKey(n)) {
                            outTypes.put(n, ex.types().get(n));
                            outKinds.put(n, ex.kinds().get(n));
                        }
                    }
                }
                case Decl.UseModifier.As as -> throw new IrijCompiler.CompileException(
                        "`pub use " + ex.module() + " :as " + as.alias() + "`: a re-export names what it "
                                + "passes on: `pub use " + ex.module() + " :open` or `pub use "
                                + ex.module() + " {names}`" + at(file, loc));
            }
        }

        private void addValue(String name, String module, String flat) {
            values.computeIfAbsent(name, k -> new LinkedHashMap<>()).put(module, flat);
        }
    }

    /**
     * Everything resolving one file needs.
     *
     * @param file         the file, as messages name it
     * @param module       the file's module, or null for the program
     * @param imports      what the file's {@code use} lines bring
     * @param own          the file's own top-level names (a module's values
     *                     already under their private names)
     * @param loaded       every module loaded so far, for saying where a name
     *                     lives
     * @param programNames the program's own top-level names, which a module
     *                     must not reach
     */
    record Context(String file, String module, Imports imports, Set<String> own,
                   Collection<Exports> loaded, Set<String> programNames) {}

    /** {@code decls} (a file's, without its {@code use} lines) with every name
     *  resolved; throws a {@link IrijCompiler.CompileException} for one the
     *  file may not use. */
    static List<Decl> resolve(List<Decl> decls, Context cx) {
        Walker w = new Walker(cx);
        List<Decl> out = new ArrayList<>(decls.size());
        for (Decl d : decls) out.add(w.decl(d));
        return out;
    }

    // ── Builtins ─────────────────────────────────────────────────────

    private static volatile Set<String> builtinValues;
    private static volatile Set<String> builtinEffects;

    /** Names a file reaches without importing anything. */
    static Set<String> builtinValues() {
        if (builtinValues == null) loadBuiltins();
        return builtinValues;
    }

    /** Builtin specs ({@link SpecValidator}'s primitive cases) and the
     *  constructors {@code try} returns: reached without an import, also by
     *  a module when the program declares a spec of the same name. */
    static final Set<String> BUILTIN_TYPES = Set.of(
            "Any", "Bool", "Float", "Fn", "Handler", "Int", "Keyword", "Map", "Rational",
            "Set", "Str", "Tuple", "Unit", "Vec", "Vector", "Ok", "Err");

    /** Effects builtins perform: named in rows without an import. */
    static Set<String> builtinEffects() {
        if (builtinEffects == null) loadBuiltins();
        return builtinEffects;
    }

    private static synchronized void loadBuiltins() {
        if (builtinValues != null) return;
        var env = new dev.irij.runtime.Environment();
        dev.irij.runtime.Builtins.install(env);
        Set<String> values = new HashSet<>(env.getBindings().keySet());
        values.addAll(ClassEmitter.BUILTIN_CONST_NAMES);
        values.addAll(IntrinsicsEmitter.NAMES);
        Set<String> effects = new HashSet<>(Set.of("Console", "JVM", "Any"));
        for (Object v : env.getBindings().values()) {
            if (v instanceof dev.irij.runtime.Values.BuiltinFn bf && bf.requiredEffects() != null) {
                effects.addAll(bf.requiredEffects());
            }
        }
        effects.addAll(EffectRowChecker.builtinEffectNames());
        builtinEffects = Set.copyOf(effects);
        builtinValues = Set.copyOf(values);
    }

    static String at(String file, SourceLoc loc) {
        if (loc == null || loc.line() == 0) return file != null ? " in " + file : "";
        return " at " + (file != null ? file + ":" : "") + loc.line() + ":" + loc.col();
    }

    // ── The walk ─────────────────────────────────────────────────────

    /** Local names in scope: a chain of blocks, innermost first. */
    private record Scope(Scope parent, Set<String> names) {
        static Scope top() { return new Scope(null, new HashSet<>()); }

        boolean has(String n) {
            for (Scope s = this; s != null; s = s.parent) if (s.names.contains(n)) return true;
            return false;
        }

        Scope child() { return new Scope(this, new HashSet<>()); }

        Scope with(Collection<String> ns) {
            Scope c = child();
            c.names.addAll(ns);
            return c;
        }
    }

    private static final class Walker {
        private final Context cx;
        /** Over every loaded module: type-level name → its module; value
         *  name → the modules exporting it; private name → its module. */
        private final Map<String, Exports> typeIndex = new HashMap<>();
        private final Map<String, List<Exports>> valueIndex = new HashMap<>();
        private final Map<String, Exports> privateIndex = new HashMap<>();

        Walker(Context cx) {
            this.cx = cx;
            for (Exports ex : cx.loaded()) {
                for (List<String> g : ex.types().values()) for (String n : g) typeIndex.putIfAbsent(n, ex);
                for (String n : ex.values().keySet()) valueIndex.computeIfAbsent(n, k -> new ArrayList<>()).add(ex);
                if (!ex.module().equals(cx.module())) {
                    for (String n : ex.privates()) privateIndex.putIfAbsent(n, ex);
                }
            }
        }

        private IrijCompiler.CompileException error(String message, SourceLoc loc) {
            return new IrijCompiler.CompileException(message + at(cx.file(), loc));
        }

        // ── Names ───────────────────────────────────────────────────

        /** A bare value name used at {@code loc} → the name it resolves to. */
        String value(String name, Scope sc, SourceLoc loc) {
            if (name == null || sc.has(name) || name.indexOf('$') >= 0 || cx.own().contains(name)) {
                return name;
            }
            if (Character.isUpperCase(name.charAt(0))) {
                type(name, loc);
                return name;
            }
            Map<String, String> imported = cx.imports().values.get(name);
            if (imported != null) {
                Set<String> flats = new LinkedHashSet<>(imported.values());
                if (flats.size() > 1) {
                    throw error("`" + name + "` is imported from both "
                            + String.join(" and ", imported.keySet())
                            + "; import one of them with `:as` to choose", loc);
                }
                return flats.iterator().next();
            }
            if (cx.imports().types.contains(name)) return name;
            Exports opOwner = typeIndex.get(name);
            if (opOwner != null) throw notImportedType(name, opOwner, loc);
            if (builtinValues().contains(name)) return name;
            List<Exports> owners = valueIndex.getOrDefault(name, List.of());
            if (!owners.isEmpty()) {
                Exports ex = owners.get(0);
                String alias = aliasFor(ex);
                throw error("`" + name + "` is not imported here: it is "
                        + (owners.size() == 1 ? ex.module() + "'s"
                                : "exported by " + String.join(", ", owners.stream().map(Exports::module).toList()))
                        + "; import it with `use " + ex.module() + " {" + name + "}`, or write `"
                        + alias + "." + name + "` after `use " + ex.module() + " :as " + alias + "`", loc);
            }
            privateOrProgram(name, loc);
            return name;
        }

        /** A type-level name (spec, variant, effect, …) used at {@code loc}. */
        void type(String name, SourceLoc loc) {
            if (name == null || cx.own().contains(name) || cx.imports().types.contains(name)
                    || builtinEffects().contains(name) || BUILTIN_TYPES.contains(name)) {
                return;
            }
            Exports owner = typeIndex.get(name);
            if (owner != null) throw notImportedType(name, owner, loc);
            privateOrProgram(name, loc);
        }

        /** A type-level name as written — {@code Mode}, or {@code m.Mode}
         *  through an import alias — checked, as the program names it: a
         *  module's type-level names keep their spelling program-wide. */
        String typeName(String name, SourceLoc loc) {
            int dot = name == null ? -1 : name.indexOf('.');
            if (dot < 0) {
                type(name, loc);
                return name;
            }
            String alias = name.substring(0, dot);
            String member = name.substring(dot + 1);
            Exports ex = cx.imports().aliases.get(alias);
            if (ex == null) {
                throw error("`" + name + "`: `" + alias + "` is not an import alias here; import the module "
                        + "with `use <module> :as " + alias + "`", loc);
            }
            if (ex.groupOf(member) == null) {
                throw error("`" + name + "`: " + ex.module() + " exports no `" + member + "`"
                        + (ex.privates().contains(member) ? " (it is private to " + ex.module() + ")" : ""), loc);
            }
            return member;
        }

        /** An effect row: effects (bare or qualified) resolved, row variables kept. */
        private List<String> row(List<String> row, SourceLoc loc) {
            if (row == null) return null;
            List<String> out = new ArrayList<>(row.size());
            for (String e : row) {
                boolean effect = e.indexOf('.') >= 0 || (!e.isEmpty() && Character.isUpperCase(e.charAt(0)));
                out.add(effect ? typeName(e, loc) : e);
            }
            return out;
        }

        private IrijCompiler.CompileException notImportedType(String name, Exports ex, SourceLoc loc) {
            String group = ex.groupOf(name);
            String what = group.equals(name) ? ex.kinds().get(group) + " `" + name + "`"
                    : "`" + name + "`, of " + ex.kinds().get(group) + " `" + group + "`";
            String alias = aliasFor(ex);
            return error("`" + name + "` is not imported here: it is " + ex.module() + "'s ("
                    + what + "); import it with `use " + ex.module() + " {" + group + "}`, or write `"
                    + alias + "." + name + "` after `use " + ex.module() + " :as " + alias + "`", loc);
        }

        /** The alias this file imports {@code ex} under, or its last segment. */
        private String aliasFor(Exports ex) {
            for (var e : cx.imports().aliases.entrySet()) {
                if (e.getValue().module().equals(ex.module())) return e.getKey();
            }
            return ex.module().substring(ex.module().lastIndexOf('.') + 1);
        }

        /** A name only a module's private code, or only the program, defines. */
        private void privateOrProgram(String name, SourceLoc loc) {
            if (privateIndex.get(name) instanceof Exports ex) {
                throw error("`" + name + "` is private to " + ex.module(), loc);
            }
            if (cx.module() != null && cx.programNames().contains(name)) {
                throw error("`" + name + "` is not defined in " + cx.module() + ": the program defines "
                        + "one, but a module sees only its own names and what it imports", loc);
            }
        }

        // ── Declarations ────────────────────────────────────────────

        Decl decl(Decl d) {
            return switch (d) {
                case Decl.FnDecl fn -> fnDecl(fn);
                case Decl.PubDecl pd -> new Decl.PubDecl(pd.inner() instanceof Decl inner ? decl(inner) : pd.inner(),
                        pd.loc());
                case Decl.HandlerDecl hd -> {
                    String effect = typeName(hd.effectName(), hd.loc());
                    List<String> required = row(hd.requiredEffects(), hd.loc());
                    Scope sc = Scope.top().child();
                    List<Stmt> state = stmtsInto(hd.stateBindings(), sc);
                    List<Decl.HandlerClause> clauses = new ArrayList<>();
                    for (var c : hd.clauses()) {
                        Scope cs = sc.with(bound(c.params()));
                        clauses.add(new Decl.HandlerClause(c.opName(), pats(c.params()), expr(c.body(), cs)));
                    }
                    yield new Decl.HandlerDecl(hd.name(), effect, required, clauses, state, hd.loc());
                }
                case Decl.ImplDecl im -> new Decl.ImplDecl(typeName(im.protoName(), im.loc()),
                        typeName(im.forType(), im.loc()), im.bindings().stream()
                                .map(b -> new Decl.ImplBinding(b.name(), expr(b.value(), Scope.top())))
                                .toList(), im.loc());
                case Decl.CapDecl cd -> new Decl.CapDecl(cd.isPub(), cd.name(), typeName(cd.effectName(), cd.loc()),
                        cd.providerClass(), expr(cd.recordExpr(), Scope.top()), cd.loc());
                case Decl.SpecDecl sd -> {
                    if (!(sd.body() instanceof Decl.SpecBody.ProductSpec ps)) yield sd;
                    Set<String> vars = Set.copyOf(sd.specParams());
                    List<Decl.SpecField> fields = new ArrayList<>();
                    for (var f : ps.fields()) fields.add(new Decl.SpecField(f.name(), spec(f.spec(), vars, sd.loc())));
                    yield new Decl.SpecDecl(sd.name(), sd.specParams(), sd.rowParams(),
                            new Decl.SpecBody.ProductSpec(fields), sd.loc());
                }
                case Decl.BindingDecl bd -> new Decl.BindingDecl(stmt(bd.stmt(), Scope.top()), bd.loc());
                case Decl.ExprDecl ed -> new Decl.ExprDecl(expr(ed.expr(), Scope.top()), ed.loc());
                case Decl.MatchDecl md -> new Decl.MatchDecl((Stmt.MatchStmt) stmt(md.match(), Scope.top()), md.loc());
                case Decl.IfDecl id -> new Decl.IfDecl((Stmt.IfStmt) stmt(id.ifStmt(), Scope.top()), id.loc());
                case Decl.WithDecl wd -> new Decl.WithDecl((Stmt.With) stmt(wd.with(), Scope.top()), wd.loc());
                case Decl.ScopeDecl sd -> new Decl.ScopeDecl((Stmt.Scope) stmt(sd.scope(), Scope.top()), sd.loc());
                case Decl.ModDecl md -> md;
                case Decl.UseDecl ud -> ud;
                case Decl.EffectDecl ed -> ed;
                case Decl.PartyDecl pd -> pd;
                case Decl.ProtoDecl pd -> pd;
                case Decl.StubDecl sd -> sd;
            };
        }

        private Decl.FnDecl fnDecl(Decl.FnDecl fn) {
            List<String> row = row(fn.effectRow(), fn.loc());
            List<SpecExpr> specs = null;
            if (fn.specAnnotations() != null) {
                specs = new ArrayList<>();
                for (SpecExpr s : fn.specAnnotations()) specs.add(spec(s, Set.of(), fn.loc()));
            }
            Scope sc = Scope.top();
            Decl.FnBody body = switch (fn.body()) {
                case Decl.FnBody.LambdaBody lb -> {
                    sc = sc.with(params(lb.params(), lb.restParam()));
                    yield new Decl.FnBody.LambdaBody(pats(lb.params()), lb.restParam(), expr(lb.body(), sc));
                }
                case Decl.FnBody.ImperativeBody ib -> {
                    sc = sc.with(params(ib.params(), ib.restParam()));
                    yield new Decl.FnBody.ImperativeBody(pats(ib.params()), ib.restParam(), stmts(ib.stmts(), sc));
                }
                case Decl.FnBody.MatchArmsBody mab -> new Decl.FnBody.MatchArmsBody(arms(mab.arms(), sc));
                case Decl.FnBody.NoBody nb -> nb;
            };
            return new Decl.FnDecl(fn.name(), fn.isPub(), row, specs, body,
                    exprs(fn.preConditions(), sc), exprs(fn.postConditions(), sc),
                    exprs(fn.inContracts(), sc), exprs(fn.outContracts(), sc), fn.loc());
        }

        private static Set<String> params(List<Pattern> ps, String rest) {
            Set<String> out = bound(ps);
            if (rest != null) out.add(rest);
            return out;
        }

        private static Set<String> bound(List<Pattern> ps) {
            Set<String> out = new HashSet<>();
            if (ps != null) for (Pattern p : ps) binders(p, out);
            return out;
        }

        /** A spec with its type-level names checked and resolved;
         *  {@code vars} are its type parameters. */
        private SpecExpr spec(SpecExpr s, Set<String> vars, SourceLoc loc) {
            if (s == null) return null;
            return switch (s) {
                case SpecExpr.Name n -> vars.contains(n.name()) ? n : new SpecExpr.Name(typeName(n.name(), loc));
                case SpecExpr.App a -> {
                    row(a.rowVar() == null ? null : List.of(a.rowVar()), loc);
                    yield new SpecExpr.App(vars.contains(a.head()) ? a.head() : typeName(a.head(), loc),
                            specs(a.args(), vars, loc), a.rowVar());
                }
                case SpecExpr.Arrow ar -> {
                    row(ar.rowVar() == null ? null : List.of(ar.rowVar()), loc);
                    yield new SpecExpr.Arrow(specs(ar.inputs(), vars, loc), spec(ar.output(), vars, loc), ar.rowVar());
                }
                case SpecExpr.VecSpec v -> new SpecExpr.VecSpec(spec(v.elemSpec(), vars, loc));
                case SpecExpr.SetSpec v -> new SpecExpr.SetSpec(spec(v.elemSpec(), vars, loc));
                case SpecExpr.TupleSpec t -> new SpecExpr.TupleSpec(specs(t.elemSpecs(), vars, loc));
                case SpecExpr.RecordSpec r -> {
                    var fields = new LinkedHashMap<String, SpecExpr>();
                    r.fields().forEach((k, x) -> fields.put(k, spec(x, vars, loc)));
                    yield new SpecExpr.RecordSpec(fields);
                }
                case SpecExpr.Enum e -> e;
                case SpecExpr.Var v -> v;
                case SpecExpr.Wildcard w -> w;
                case SpecExpr.Unit u -> u;
            };
        }

        private List<SpecExpr> specs(List<SpecExpr> ss, Set<String> vars, SourceLoc loc) {
            List<SpecExpr> out = new ArrayList<>(ss.size());
            for (SpecExpr x : ss) out.add(spec(x, vars, loc));
            return out;
        }

        // ── Statements ──────────────────────────────────────────────

        /** A block: each binding is in scope for the statements after it. */
        private List<Stmt> stmts(List<Stmt> ss, Scope sc) {
            return ss == null ? null : stmtsInto(ss, sc.child());
        }

        private List<Stmt> stmtsInto(List<Stmt> ss, Scope block) {
            if (ss == null) return null;
            List<Stmt> out = new ArrayList<>(ss.size());
            for (Stmt s : ss) {
                out.add(stmt(s, block));
                switch (s) {
                    case Stmt.Bind b -> targetNames(b.target(), block.names());
                    case Stmt.MutBind mb -> targetNames(mb.target(), block.names());
                    default -> { }
                }
            }
            return out;
        }

        private Stmt stmt(Stmt s, Scope sc) {
            return switch (s) {
                case Stmt.ExprStmt es -> new Stmt.ExprStmt(expr(es.expr(), sc), es.loc());
                case Stmt.Bind b -> new Stmt.Bind(target(b.target()), expr(b.value(), sc),
                        spec(b.specAnnotation(), Set.of(), b.loc()), b.loc());
                case Stmt.MutBind mb -> new Stmt.MutBind(target(mb.target()), expr(mb.value(), sc), mb.loc());
                case Stmt.Assign a -> new Stmt.Assign(a.target() instanceof Stmt.BindTarget.Simple sm
                        ? new Stmt.BindTarget.Simple(value(sm.name(), sc, a.loc())) : a.target(),
                        expr(a.value(), sc), a.loc());
                case Stmt.With w -> new Stmt.With(expr(w.handler(), sc), stmts(w.body(), sc),
                        stmts(w.onFailure(), sc), w.loc());
                case Stmt.Scope scp -> new Stmt.Scope(scp.modifier(), scp.name(),
                        stmts(scp.body(), scp.name() == null ? sc : sc.with(Set.of(scp.name()))), scp.loc());
                case Stmt.MatchStmt ms -> new Stmt.MatchStmt(expr(ms.scrutinee(), sc), arms(ms.arms(), sc), ms.loc());
                case Stmt.IfStmt is -> new Stmt.IfStmt(expr(is.cond(), sc), stmts(is.thenBranch(), sc),
                        stmts(is.elseBranch(), sc), is.loc());
            };
        }

        private Stmt.BindTarget target(Stmt.BindTarget t) {
            return t instanceof Stmt.BindTarget.Destructure ds ? new Stmt.BindTarget.Destructure(pat(ds.pattern())) : t;
        }

        /** A pattern with the constructors it names checked and resolved. */
        private Pattern pat(Pattern p) {
            if (p == null) return null;
            return switch (p) {
                case Pattern.ConstructorPat cp -> new Pattern.ConstructorPat(typeName(cp.name(), cp.loc()),
                        pats(cp.args()), cp.loc());
                case Pattern.KeywordPat kp -> new Pattern.KeywordPat(kp.name(), pat(kp.arg()), kp.loc());
                case Pattern.GroupedPat gp -> new Pattern.GroupedPat(pat(gp.inner()), gp.loc());
                case Pattern.VectorPat vp -> new Pattern.VectorPat(pats(vp.elements()), vp.spread(), vp.loc());
                case Pattern.TuplePat tp -> new Pattern.TuplePat(pats(tp.elements()), tp.loc());
                case Pattern.DestructurePat dp -> new Pattern.DestructurePat(dp.fields().stream()
                        .map(f -> new Pattern.DestructureField(f.key(), pat(f.value()))).toList(), dp.loc());
                case Pattern.VarPat vp -> vp;
                case Pattern.SpreadPat sp -> sp;
                case Pattern.LitPat lp -> lp;
                case Pattern.WildcardPat wp -> wp;
                case Pattern.UnitPat up -> up;
            };
        }

        private List<Pattern> pats(List<Pattern> ps) {
            if (ps == null) return null;
            List<Pattern> out = new ArrayList<>(ps.size());
            for (Pattern p : ps) out.add(pat(p));
            return out;
        }

        private List<Expr.MatchArm> arms(List<Expr.MatchArm> as, Scope sc) {
            List<Expr.MatchArm> out = new ArrayList<>(as.size());
            for (var a : as) {
                Set<String> b = new HashSet<>();
                binders(a.pattern(), b);
                Scope as2 = sc.with(b);
                out.add(new Expr.MatchArm(pat(a.pattern()), expr(a.guard(), as2), expr(a.body(), as2)));
            }
            return out;
        }

        // ── Expressions ─────────────────────────────────────────────

        private List<Expr> exprs(List<Expr> es, Scope sc) {
            if (es == null) return null;
            List<Expr> out = new ArrayList<>(es.size());
            for (Expr e : es) out.add(expr(e, sc));
            return out;
        }

        private Expr expr(Expr e, Scope sc) {
            if (e == null) return null;
            return switch (e) {
                case Expr.Var v -> {
                    String r = value(v.name(), sc, v.loc());
                    yield r.equals(v.name()) ? v : new Expr.Var(r, v.loc());
                }
                case Expr.TypeRef t -> {
                    type(t.name(), t.loc());
                    yield t;
                }
                case Expr.DotAccess da -> {
                    if (da.target() instanceof Expr.Var v && !sc.has(v.name())
                            && cx.imports().aliases.get(v.name()) instanceof Exports ex) {
                        String flat = ex.values().get(da.field());
                        if (flat != null) yield new Expr.Var(flat, da.loc());
                        if (ex.groupOf(da.field()) != null) {
                            // Type-level names keep their spelling program-wide: a
                            // variant or newtype is a constructor, an effect's op or
                            // a proto's method is called by name.
                            yield Character.isUpperCase(da.field().charAt(0))
                                    ? new Expr.TypeRef(da.field(), da.loc()) : new Expr.Var(da.field(), da.loc());
                        }
                        throw error("`" + v.name() + "." + da.field() + "`: " + ex.module() + " exports no `"
                                + da.field() + "`"
                                + (ex.privates().contains(da.field()) ? " (it is private to " + ex.module() + ")" : ""),
                                da.loc());
                    }
                    yield new Expr.DotAccess(expr(da.target(), sc), da.field(), da.loc());
                }
                case Expr.App a -> new Expr.App(expr(a.fn(), sc), exprs(a.args(), sc), a.loc());
                case Expr.Lambda l -> new Expr.Lambda(pats(l.params()), l.restParam(),
                        lambdaBody(l, sc), l.loc());
                case Expr.BinaryOp b -> new Expr.BinaryOp(b.op(), expr(b.left(), sc), expr(b.right(), sc), b.loc());
                case Expr.UnaryOp u -> new Expr.UnaryOp(u.op(), expr(u.operand(), sc), u.loc());
                case Expr.Pipe p -> new Expr.Pipe(expr(p.left(), sc), expr(p.right(), sc), p.forward(), p.loc());
                case Expr.Compose c -> new Expr.Compose(expr(c.left(), sc), expr(c.right(), sc), c.forward(), c.loc());
                case Expr.SeqOp so -> new Expr.SeqOp(so.op(), expr(so.arg(), sc), so.loc());
                case Expr.IfExpr ie -> new Expr.IfExpr(expr(ie.cond(), sc), expr(ie.thenBranch(), sc),
                        expr(ie.elseBranch(), sc), ie.loc());
                case Expr.MatchExpr me -> new Expr.MatchExpr(expr(me.scrutinee(), sc), arms(me.arms(), sc), me.loc());
                case Expr.VectorLit vl -> new Expr.VectorLit(exprs(vl.elements(), sc), vl.loc());
                case Expr.SetLit sl -> new Expr.SetLit(exprs(sl.elements(), sc), sl.loc());
                case Expr.TupleLit tl -> new Expr.TupleLit(exprs(tl.elements(), sc), tl.loc());
                case Expr.MapLit ml -> new Expr.MapLit(entries(ml.entries(), sc, ml.loc()), ml.loc());
                case Expr.RecordUpdate ru -> new Expr.RecordUpdate(value(ru.base(), sc, ru.loc()),
                        entries(ru.updates(), sc, ru.loc()), ru.loc());
                case Expr.Range r -> new Expr.Range(expr(r.from(), sc), expr(r.to(), sc), r.exclusive(), r.loc());
                case Expr.StringInterp si -> new Expr.StringInterp(si.parts().stream()
                        .map(p -> switch (p) {
                            case Expr.StringPart.Literal lit -> (Expr.StringPart) lit;
                            case Expr.StringPart.Interpolation in ->
                                    new Expr.StringPart.Interpolation(expr(in.expr(), sc));
                        }).toList(), si.loc());
                case Expr.DoExpr de -> new Expr.DoExpr(exprs(de.exprs(), sc), de.loc());
                case Expr.Block bl -> new Expr.Block(stmts(bl.stmts(), sc), bl.loc());
                case Expr.ChoreoExpr ce -> new Expr.ChoreoExpr(ce.op(), expr(ce.left(), sc), expr(ce.right(), sc), ce.loc());
                case Expr.IntLit x -> x;
                case Expr.BigIntLit x -> x;
                case Expr.FloatLit x -> x;
                case Expr.RationalLit x -> x;
                case Expr.HexLit x -> x;
                case Expr.StrLit x -> x;
                case Expr.BoolLit x -> x;
                case Expr.KeywordLit x -> x;
                case Expr.UnitLit x -> x;
                case Expr.PartyRef x -> x;
                case Expr.JavaRef x -> x;
                case Expr.OpSection x -> x;
                case Expr.Wildcard x -> x;
            };
        }

        private Expr lambdaBody(Expr.Lambda l, Scope sc) {
            return expr(l.body(), sc.with(params(l.params(), l.restParam())));
        }

        private List<Expr.MapEntry> entries(List<Expr.MapEntry> es, Scope sc, SourceLoc loc) {
            List<Expr.MapEntry> out = new ArrayList<>(es.size());
            for (var me : es) {
                out.add(switch (me) {
                    case Expr.MapEntry.Field f -> new Expr.MapEntry.Field(f.key(), expr(f.value(), sc));
                    case Expr.MapEntry.DynField df -> new Expr.MapEntry.DynField(expr(df.keyExpr(), sc), expr(df.value(), sc));
                    case Expr.MapEntry.Spread sp -> new Expr.MapEntry.Spread(value(sp.name(), sc, loc));
                });
            }
            return out;
        }
    }
}
