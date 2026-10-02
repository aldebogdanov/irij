package dev.irij.compiler;

import dev.irij.ast.Decl;
import dev.irij.ast.Expr;
import dev.irij.ast.Node;
import dev.irij.ast.Pattern;
import dev.irij.ast.Stmt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps a module's private names private once {@link ModuleInliner} has
 * flattened every module into one program.
 *
 * <p>The emitter resolves top-level names program-wide, so without this a
 * module's private {@code fn helper} and the program's own {@code helper}
 * were one name: whichever was emitted last replaced the other everywhere.
 * A program that happened to define {@code find-route} silently rewired
 * {@code std.serve}'s router; two seeds with the same private helper name
 * called each other's.
 *
 * <p>Each private top-level fn, binding and handler of a module is renamed
 * to {@code name$module$path} — {@code $} can't occur in an Irij
 * identifier, so the new name is fresh. The rename is applied to <em>every</em>
 * occurrence of the identifier in the module (uses, binders, parameters,
 * patterns): renaming one identifier consistently throughout is
 * meaning-preserving whatever the local scoping, so no scope analysis is
 * needed. Field names, map keys and effect-op names are not identifiers
 * and are left alone. The switches below are exhaustive over the sealed AST
 * types, so a new node kind is a compile error here rather than a missed
 * rename.
 */
final class ModulePrivacy {

    private ModulePrivacy() {}

    /** The private-name spelling for {@code name} in module {@code module}. */
    static String privateName(String name, String module) {
        return name + "$" + module.replace('.', '$');
    }

    /** {@code modDecls} with the module's private top-level names renamed. */
    static List<Decl> privatize(List<Decl> modDecls, String module) {
        Set<String> pub = new HashSet<>();
        Map<String, String> renames = new HashMap<>();
        for (Decl d : modDecls) {
            boolean isPub = d instanceof Decl.PubDecl;
            Node inner = d instanceof Decl.PubDecl pd ? pd.inner() : d;
            String name = switch (inner) {
                case Decl.FnDecl fn -> {
                    if (fn.isPub()) isPub = true;
                    yield fn.name();
                }
                case Decl.BindingDecl bd -> simpleTarget(bd.stmt());
                case Decl.HandlerDecl hd -> hd.name();
                default -> null;
            };
            if (name == null) continue;
            if (isPub) pub.add(name);
            else renames.put(name, privateName(name, module));
        }
        renames.keySet().removeAll(pub); // declared pub anywhere: public
        if (renames.isEmpty()) return modDecls;
        Renamer r = new Renamer(renames);
        List<Decl> out = new ArrayList<>(modDecls.size());
        for (Decl d : modDecls) out.add(r.decl(d));
        return out;
    }

    private static String simpleTarget(Stmt s) {
        Stmt.BindTarget t = switch (s) {
            case Stmt.Bind b -> b.target();
            case Stmt.MutBind mb -> mb.target();
            default -> null;
        };
        return t instanceof Stmt.BindTarget.Simple sm ? sm.name() : null;
    }

    /** Consistent renaming of a fixed set of identifiers. */
    private record Renamer(Map<String, String> renames) {

        String id(String name) {
            if (name == null) return null;
            String r = renames.get(name);
            return r != null ? r : name;
        }

        Node node(Node n) {
            return switch (n) {
                case Decl d -> decl(d);
                case Expr e -> expr(e);
                case Stmt s -> stmt(s);
                case Pattern p -> pat(p);
                default -> n;
            };
        }

        Decl decl(Decl d) {
            return switch (d) {
                case Decl.FnDecl fn -> new Decl.FnDecl(id(fn.name()), fn.isPub(), fn.effectRow(),
                        fn.specAnnotations(), body(fn.body()), exprs(fn.preConditions()),
                        exprs(fn.postConditions()), exprs(fn.inContracts()),
                        exprs(fn.outContracts()), fn.loc());
                case Decl.PubDecl pd -> new Decl.PubDecl(node(pd.inner()), pd.loc());
                case Decl.HandlerDecl hd -> new Decl.HandlerDecl(id(hd.name()), hd.effectName(),
                        hd.requiredEffects(), clauses(hd.clauses()), stmts(hd.stateBindings()),
                        hd.loc());
                case Decl.ImplDecl im -> new Decl.ImplDecl(im.protoName(), im.forType(),
                        im.bindings().stream()
                                .map(b -> new Decl.ImplBinding(b.name(), expr(b.value())))
                                .toList(),
                        im.loc());
                case Decl.CapDecl cd -> cd.recordExpr() == null ? cd
                        : new Decl.CapDecl(cd.isPub(), cd.name(), cd.effectName(),
                                cd.providerClass(), expr(cd.recordExpr()), cd.loc());
                case Decl.BindingDecl bd -> new Decl.BindingDecl(stmt(bd.stmt()), bd.loc());
                case Decl.ExprDecl ed -> new Decl.ExprDecl(expr(ed.expr()), ed.loc());
                case Decl.MatchDecl md -> new Decl.MatchDecl((Stmt.MatchStmt) stmt(md.match()), md.loc());
                case Decl.IfDecl id -> new Decl.IfDecl((Stmt.IfStmt) stmt(id.ifStmt()), id.loc());
                case Decl.WithDecl wd -> new Decl.WithDecl((Stmt.With) stmt(wd.with()), wd.loc());
                case Decl.ScopeDecl sd -> new Decl.ScopeDecl((Stmt.Scope) stmt(sd.scope()), sd.loc());
                case Decl.SpecDecl sd -> sd;
                case Decl.NewtypeDecl nd -> nd;
                case Decl.ModDecl md -> md;
                case Decl.UseDecl ud -> ud;
                case Decl.EffectDecl ed -> ed;
                case Decl.PartyDecl pd -> pd;
                case Decl.ProtoDecl pd -> pd;
                case Decl.StubDecl sd -> sd;
            };
        }

        Decl.FnBody body(Decl.FnBody b) {
            return switch (b) {
                case Decl.FnBody.LambdaBody lb ->
                        new Decl.FnBody.LambdaBody(pats(lb.params()), id(lb.restParam()), expr(lb.body()));
                case Decl.FnBody.MatchArmsBody mab -> new Decl.FnBody.MatchArmsBody(arms(mab.arms()));
                case Decl.FnBody.ImperativeBody ib ->
                        new Decl.FnBody.ImperativeBody(pats(ib.params()), id(ib.restParam()), stmts(ib.stmts()));
                case Decl.FnBody.NoBody nb -> nb;
            };
        }

        List<Decl.HandlerClause> clauses(List<Decl.HandlerClause> cs) {
            List<Decl.HandlerClause> out = new ArrayList<>(cs.size());
            for (var c : cs) out.add(new Decl.HandlerClause(c.opName(), pats(c.params()), expr(c.body())));
            return out;
        }

        Stmt stmt(Stmt s) {
            return switch (s) {
                case Stmt.ExprStmt es -> new Stmt.ExprStmt(expr(es.expr()), es.loc());
                case Stmt.Bind b -> new Stmt.Bind(target(b.target()), expr(b.value()),
                        b.specAnnotation(), b.loc());
                case Stmt.MutBind mb -> new Stmt.MutBind(target(mb.target()), expr(mb.value()), mb.loc());
                case Stmt.Assign a -> new Stmt.Assign(target(a.target()), expr(a.value()), a.loc());
                case Stmt.With w -> new Stmt.With(expr(w.handler()), stmts(w.body()),
                        w.onFailure() == null ? null : stmts(w.onFailure()), w.loc());
                case Stmt.Scope sc -> new Stmt.Scope(sc.modifier(), id(sc.name()), stmts(sc.body()), sc.loc());
                case Stmt.MatchStmt ms -> new Stmt.MatchStmt(expr(ms.scrutinee()), arms(ms.arms()), ms.loc());
                case Stmt.IfStmt is -> new Stmt.IfStmt(expr(is.cond()), stmts(is.thenBranch()),
                        is.elseBranch() == null ? null : stmts(is.elseBranch()), is.loc());
            };
        }

        Stmt.BindTarget target(Stmt.BindTarget t) {
            return switch (t) {
                case Stmt.BindTarget.Simple sm -> new Stmt.BindTarget.Simple(id(sm.name()));
                case Stmt.BindTarget.Destructure ds -> new Stmt.BindTarget.Destructure(pat(ds.pattern()));
            };
        }

        Expr expr(Expr e) {
            if (e == null) return null;
            return switch (e) {
                case Expr.Var v -> renames.containsKey(v.name()) ? new Expr.Var(id(v.name()), v.loc()) : v;
                case Expr.App a -> new Expr.App(expr(a.fn()), exprs(a.args()), a.loc());
                case Expr.Lambda l -> new Expr.Lambda(pats(l.params()), id(l.restParam()), expr(l.body()), l.loc());
                case Expr.BinaryOp b -> new Expr.BinaryOp(b.op(), expr(b.left()), expr(b.right()), b.loc());
                case Expr.UnaryOp u -> new Expr.UnaryOp(u.op(), expr(u.operand()), u.loc());
                case Expr.Pipe p -> new Expr.Pipe(expr(p.left()), expr(p.right()), p.forward(), p.loc());
                case Expr.Compose c -> new Expr.Compose(expr(c.left()), expr(c.right()), c.forward(), c.loc());
                case Expr.SeqOp so -> new Expr.SeqOp(so.op(), expr(so.arg()), so.loc());
                case Expr.IfExpr ie -> new Expr.IfExpr(expr(ie.cond()), expr(ie.thenBranch()),
                        expr(ie.elseBranch()), ie.loc());
                case Expr.MatchExpr me -> new Expr.MatchExpr(expr(me.scrutinee()), arms(me.arms()), me.loc());
                case Expr.VectorLit vl -> new Expr.VectorLit(exprs(vl.elements()), vl.loc());
                case Expr.SetLit sl -> new Expr.SetLit(exprs(sl.elements()), sl.loc());
                case Expr.TupleLit tl -> new Expr.TupleLit(exprs(tl.elements()), tl.loc());
                case Expr.MapLit ml -> new Expr.MapLit(entries(ml.entries()), ml.loc());
                case Expr.RecordUpdate ru -> new Expr.RecordUpdate(id(ru.base()), entries(ru.updates()), ru.loc());
                case Expr.Range r -> new Expr.Range(expr(r.from()), expr(r.to()), r.exclusive(), r.loc());
                case Expr.StringInterp si -> new Expr.StringInterp(si.parts().stream()
                        .map(p -> switch (p) {
                            case Expr.StringPart.Literal lit -> (Expr.StringPart) lit;
                            case Expr.StringPart.Interpolation in ->
                                    new Expr.StringPart.Interpolation(expr(in.expr()));
                        }).toList(), si.loc());
                case Expr.DotAccess da -> new Expr.DotAccess(expr(da.target()), da.field(), da.loc());
                case Expr.DoExpr de -> new Expr.DoExpr(exprs(de.exprs()), de.loc());
                case Expr.Block bl -> new Expr.Block(stmts(bl.stmts()), bl.loc());
                case Expr.ChoreoExpr ce -> new Expr.ChoreoExpr(ce.op(), expr(ce.left()), expr(ce.right()), ce.loc());
                case Expr.IntLit x -> x;
                case Expr.FloatLit x -> x;
                case Expr.RationalLit x -> x;
                case Expr.HexLit x -> x;
                case Expr.StrLit x -> x;
                case Expr.BoolLit x -> x;
                case Expr.KeywordLit x -> x;
                case Expr.UnitLit x -> x;
                case Expr.TypeRef x -> x;
                case Expr.PartyRef x -> x;
                case Expr.JavaRef x -> x;
                case Expr.OpSection x -> x;
                case Expr.Wildcard x -> x;
            };
        }

        List<Expr.MapEntry> entries(List<Expr.MapEntry> es) {
            List<Expr.MapEntry> out = new ArrayList<>(es.size());
            for (var me : es) {
                out.add(switch (me) {
                    case Expr.MapEntry.Field f -> new Expr.MapEntry.Field(f.key(), expr(f.value()));
                    case Expr.MapEntry.DynField df -> new Expr.MapEntry.DynField(expr(df.keyExpr()), expr(df.value()));
                    case Expr.MapEntry.Spread sp -> new Expr.MapEntry.Spread(id(sp.name()));
                });
            }
            return out;
        }

        List<Expr.MatchArm> arms(List<Expr.MatchArm> as) {
            List<Expr.MatchArm> out = new ArrayList<>(as.size());
            for (var a : as) out.add(new Expr.MatchArm(pat(a.pattern()), expr(a.guard()), expr(a.body())));
            return out;
        }

        Pattern pat(Pattern p) {
            if (p == null) return null;
            return switch (p) {
                case Pattern.VarPat vp -> new Pattern.VarPat(id(vp.name()), vp.loc());
                case Pattern.ConstructorPat cp -> new Pattern.ConstructorPat(cp.name(), pats(cp.args()), cp.loc());
                case Pattern.KeywordPat kp -> new Pattern.KeywordPat(kp.name(), pat(kp.arg()), kp.loc());
                case Pattern.GroupedPat gp -> new Pattern.GroupedPat(pat(gp.inner()), gp.loc());
                case Pattern.VectorPat vp -> new Pattern.VectorPat(pats(vp.elements()),
                        (Pattern.SpreadPat) pat(vp.spread()), vp.loc());
                case Pattern.TuplePat tp -> new Pattern.TuplePat(pats(tp.elements()), tp.loc());
                case Pattern.DestructurePat dp -> new Pattern.DestructurePat(dp.fields().stream()
                        .map(f -> new Pattern.DestructureField(f.key(), pat(f.value()))).toList(), dp.loc());
                case Pattern.SpreadPat sp -> new Pattern.SpreadPat(id(sp.name()), sp.loc());
                case Pattern.LitPat lp -> lp;
                case Pattern.WildcardPat wp -> wp;
                case Pattern.UnitPat up -> up;
            };
        }

        List<Expr> exprs(List<Expr> es) {
            if (es == null) return null;
            List<Expr> out = new ArrayList<>(es.size());
            for (Expr e : es) out.add(expr(e));
            return out;
        }

        List<Stmt> stmts(List<Stmt> ss) {
            if (ss == null) return null;
            List<Stmt> out = new ArrayList<>(ss.size());
            for (Stmt s : ss) out.add(stmt(s));
            return out;
        }

        List<Pattern> pats(List<Pattern> ps) {
            if (ps == null) return null;
            List<Pattern> out = new ArrayList<>(ps.size());
            for (Pattern p : ps) out.add(pat(p));
            return out;
        }
    }
}
