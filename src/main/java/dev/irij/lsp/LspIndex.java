package dev.irij.lsp;

import dev.irij.ast.AstBuilder;
import dev.irij.ast.Decl;
import dev.irij.ast.Node.SourceLoc;
import dev.irij.parser.IrijParseDriver;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-document symbol index. Built from the parsed AST on every
 * {@code didOpen} / {@code didChange}; queried by hover, goto-def,
 * and completion handlers.
 *
 * <p>One symbol per top-level decl we want to surface — fn,
 * effect, handler, cap, spec, newtype, proto, role. Each carries:
 * name, kind, location (1-based line/col from the AST's
 * {@code SourceLoc}), and a short signature blurb computed
 * cheaply from the decl shape.
 *
 * <p>The index is small (one document's worth) and rebuilt on
 * every change; no incremental update logic. v0.8.x's workspace-
 * wide index across module imports is future work.
 */
final class LspIndex {

    enum Kind { FN, EFFECT, HANDLER, CAP, SPEC, NEWTYPE, PROTO, ROLE }

    record Symbol(String name, Kind kind, SourceLoc loc, String signature) {}

    private LspIndex() {}

    /** Parse {@code source} and produce a flat symbol list. Errors
     *  during parse / AST build are swallowed (LSP keeps editing
     *  responsive even on broken source); we return what we got. */
    static List<Symbol> build(String source) {
        List<Symbol> out = new ArrayList<>();
        try {
            IrijParseDriver.ParseResult pr = IrijParseDriver.parse(source);
            if (pr.tree() == null) return out;
            List<Decl> decls = new AstBuilder().build(pr.tree());
            for (Decl d : decls) collect(d, out);
        } catch (Exception ignored) {
            // Defensive — partial source while typing.
        }
        return out;
    }

    private static void collect(Decl d, List<Symbol> out) {
        // Unwrap `pub` wrapper to surface the inner decl by name.
        Decl inner = (d instanceof Decl.PubDecl pd
                && pd.inner() instanceof Decl di) ? di : d;
        switch (inner) {
            case Decl.FnDecl fn -> out.add(new Symbol(
                    fn.name(), Kind.FN, fn.loc(), fnSignature(fn)));
            case Decl.EffectDecl ed -> out.add(new Symbol(
                    ed.name(), Kind.EFFECT, ed.loc(),
                    "effect " + ed.name()
                            + (ed.ops().isEmpty() ? "" :
                                    " (" + ed.ops().size() + " op"
                                    + (ed.ops().size() == 1 ? "" : "s") + ")")));
            case Decl.HandlerDecl hd -> out.add(new Symbol(
                    hd.name(), Kind.HANDLER, hd.loc(),
                    "handler " + hd.name() + " :: " + hd.effectName()
                            + (hd.requiredEffects() != null && !hd.requiredEffects().isEmpty()
                                    ? " ::: " + String.join(" ", hd.requiredEffects())
                                    : "")));
            case Decl.CapDecl cap -> out.add(new Symbol(
                    cap.name(), Kind.CAP, cap.loc(),
                    "cap " + cap.name() + " :: " + cap.effectName()
                            + " = " + (cap.isRecord()
                                    ? "{…}  ;; Irij-record provider"
                                    : "\"" + cap.providerClass() + "\"")));
            case Decl.SpecDecl sd -> out.add(new Symbol(
                    sd.name(), Kind.SPEC, sd.loc(),
                    "spec " + sd.name()
                            + (sd.specParams().isEmpty() ? "" : " " + String.join(" ", sd.specParams()))
                            + (sd.rowParams().isEmpty() ? "" : " ::: " + String.join(" ", sd.rowParams()))));
            case Decl.NewtypeDecl nt -> out.add(new Symbol(
                    nt.name(), Kind.NEWTYPE, nt.loc(),
                    "newtype " + nt.name()));
            case Decl.ProtoDecl pd -> out.add(new Symbol(
                    pd.name(), Kind.PROTO, pd.loc(),
                    "proto " + pd.name()
                            + (pd.typeParams().isEmpty() ? "" : " " + String.join(" ", pd.typeParams()))));
            case Decl.RoleDecl rd -> out.add(new Symbol(
                    rd.name(), Kind.ROLE, rd.loc(),
                    "role " + rd.name()));
            default -> { /* not a name-surfaceable decl */ }
        }
    }

    /** Compose a one-line signature for a fn from its declared
     *  effect row + spec annotations. Keeps the hover payload small;
     *  the editor truncates further if needed. */
    private static String fnSignature(Decl.FnDecl fn) {
        StringBuilder sb = new StringBuilder("fn ").append(fn.name());
        if (fn.specAnnotations() != null && !fn.specAnnotations().isEmpty()) {
            sb.append(" :: ");
            boolean first = true;
            for (var s : fn.specAnnotations()) {
                if (!first) sb.append(' ');
                first = false;
                sb.append(s);
            }
        }
        if (fn.effectRow() != null && !fn.effectRow().isEmpty()) {
            sb.append(" ::: ").append(String.join(" ", fn.effectRow()));
        }
        return sb.toString();
    }

    /** Find a symbol by exact name, or null if absent. */
    static Symbol findByName(List<Symbol> index, String name) {
        for (Symbol s : index) {
            if (s.name().equals(name)) return s;
        }
        return null;
    }
}
