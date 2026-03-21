package dev.irij.ast;

import java.util.List;

/**
 * All declaration AST nodes.
 */
public sealed interface Decl extends Node {

    /** Function declaration: fn name :: Type  body. */
    record FnDecl(String name, boolean isPub, FnBody body,
                  List<Expr> preConditions, List<Expr> postConditions,
                  SourceLoc loc) implements Decl {
        /** Convenience constructor for functions without contracts. */
        public FnDecl(String name, boolean isPub, FnBody body, SourceLoc loc) {
            this(name, isPub, body, List.of(), List.of(), loc);
        }
    }

    /** Type declaration: type Name params  variants|fields. */
    record TypeDecl(String name, List<String> typeParams, TypeBody body, SourceLoc loc) implements Decl {}

    /** Newtype declaration: newtype Name := Type. */
    record NewtypeDecl(String name, SourceLoc loc) implements Decl {}

    /** Module declaration: mod qualified.name. */
    record ModDecl(String qualifiedName, SourceLoc loc) implements Decl {}

    /** Use declaration: use qualified.name modifier?. */
    record UseDecl(String qualifiedName, UseModifier modifier, SourceLoc loc) implements Decl {}

    /** pub wrapper: pub decl. */
    record PubDecl(Node inner, SourceLoc loc) implements Decl {}

    /** Effect declaration (parsed, runtime stub). */
    record EffectDecl(String name, List<String> typeParams,
                      List<EffectOp> ops, SourceLoc loc) implements Decl {}

    /** Handler declaration with optional state bindings. */
    record HandlerDecl(String name, String effectName,
                       List<HandlerClause> clauses, List<Stmt> stateBindings,
                       SourceLoc loc) implements Decl {}

    /** Role declaration: role $NAME. */
    record RoleDecl(String name, SourceLoc loc) implements Decl {}

    /** Protocol declaration: proto Name a  methods/laws. */
    record ProtoDecl(String name, List<String> typeParams,
                     List<ProtoMethod> methods, List<ProtoLaw> laws,
                     SourceLoc loc) implements Decl {}

    /** Implementation declaration: impl Proto for Type  bindings. */
    record ImplDecl(String protoName, String forType,
                    List<ImplBinding> bindings, SourceLoc loc) implements Decl {}

    /** Stub for cap declarations (parsed, not executed). */
    record StubDecl(String kind, String name, SourceLoc loc) implements Decl {}

    /** A binding used as a top-level declaration. */
    record BindingDecl(Stmt stmt, SourceLoc loc) implements Decl {}

    /** An expression used as a top-level declaration. */
    record ExprDecl(Expr expr, SourceLoc loc) implements Decl {}

    /** A top-level match statement. */
    record MatchDecl(Stmt.MatchStmt match, SourceLoc loc) implements Decl {}

    /** A top-level if statement. */
    record IfDecl(Stmt.IfStmt ifStmt, SourceLoc loc) implements Decl {}

    /** A top-level with expression. */
    record WithDecl(Stmt.With with, SourceLoc loc) implements Decl {}

    /** A top-level scope expression. */
    record ScopeDecl(Stmt.Scope scope, SourceLoc loc) implements Decl {}

    // ── Function Body ───────────────────────────────────────────────────

    sealed interface FnBody {
        /** Lambda body: (params -> expr). */
        record LambdaBody(List<Pattern> params, Expr body) implements FnBody {}

        /** Match arms body: pattern => expr, one per line. */
        record MatchArmsBody(List<Expr.MatchArm> arms) implements FnBody {}

        /** Imperative block: => params  stmts. */
        record ImperativeBody(List<Pattern> params, List<Stmt> stmts) implements FnBody {}

        /** No body provided (just type signature). */
        record NoBody() implements FnBody {}
    }

    // ── Type Body ───────────────────────────────────────────────────────

    sealed interface TypeBody {
        /** Sum type: variants with constructors. */
        record SumType(List<Variant> variants) implements TypeBody {}

        /** Product type: named fields. */
        record ProductType(List<Field> fields) implements TypeBody {}
    }

    record Variant(String name, int arity) {}
    record Field(String name) {}

    // ── Effect / Handler helpers ─────────────────────────────────────────

    record EffectOp(String name) {}
    record HandlerClause(String opName, List<Pattern> params, Expr body) {}

    // ── Protocol helpers ────────────────────────────────────────────────

    record ProtoMethod(String name) {}
    record ProtoLaw(String name, Expr body) {}
    record ImplBinding(String name, Expr value) {}

    // ── Use Modifier ────────────────────────────────────────────────────

    sealed interface UseModifier {
        record Open(String keyword) implements UseModifier {}
        record Selective(List<String> names) implements UseModifier {}
    }
}
