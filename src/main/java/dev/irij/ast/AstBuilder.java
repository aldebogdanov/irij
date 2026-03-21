package dev.irij.ast;

import dev.irij.ast.Node.SourceLoc;
import dev.irij.parser.IrijParser;
import dev.irij.parser.IrijParser.*;
import dev.irij.parser.IrijParseDriver;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds the Irij AST from an ANTLR parse tree.
 *
 * The parser grammar has 12+ precedence levels with passthrough rules.
 * This builder collapses them into a clean AST with explicit node types.
 */
public class AstBuilder {

    // ═══════════════════════════════════════════════════════════════════
    // Entry Point
    // ═══════════════════════════════════════════════════════════════════

    /** Build a list of top-level declarations from a compilation unit. */
    public List<Decl> build(CompilationUnitContext ctx) {
        var decls = new ArrayList<Decl>();
        for (var tld : ctx.topLevelDecl()) {
            decls.add(visitTopLevelDecl(tld));
        }
        return decls;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Top-Level Declarations
    // ═══════════════════════════════════════════════════════════════════

    private Decl visitTopLevelDecl(TopLevelDeclContext ctx) {
        var loc = loc(ctx);
        if (ctx.modulDecl() != null) return visitModulDecl(ctx.modulDecl());
        if (ctx.useDecl() != null) return visitUseDecl(ctx.useDecl());
        if (ctx.pubDecl() != null) return visitPubDecl(ctx.pubDecl());
        if (ctx.fnDecl() != null) return visitFnDecl(ctx.fnDecl(), false);
        if (ctx.typeDecl() != null) return visitTypeDecl(ctx.typeDecl());
        if (ctx.newtypeDecl() != null) return visitNewtypeDecl(ctx.newtypeDecl());
        if (ctx.effectDecl() != null) return visitEffectDecl(ctx.effectDecl());
        if (ctx.handlerDecl() != null) return visitHandlerDecl(ctx.handlerDecl());
        if (ctx.capDecl() != null) return new Decl.StubDecl("cap", getText(ctx), loc);
        if (ctx.protoDecl() != null) return visitProtoDecl(ctx.protoDecl());
        if (ctx.implDecl() != null) return visitImplDecl(ctx.implDecl());
        if (ctx.roleDecl() != null) return visitRoleDecl(ctx.roleDecl());
        if (ctx.matchStmt() != null) return new Decl.MatchDecl(visitMatchStmt(ctx.matchStmt()), loc);
        if (ctx.ifStmt() != null) return new Decl.IfDecl(visitIfStmt(ctx.ifStmt()), loc);
        if (ctx.withExpr() != null) return new Decl.WithDecl(visitWithExpr(ctx.withExpr()), loc);
        if (ctx.scopeExpr() != null) return new Decl.ScopeDecl(visitScopeExpr(ctx.scopeExpr()), loc);
        if (ctx.binding() != null) return new Decl.BindingDecl(visitBinding(ctx.binding()), loc);
        if (ctx.expr() != null) return new Decl.ExprDecl(visitExpr(ctx.expr()), loc);
        throw new IllegalStateException("Unknown top-level declaration at " + loc);
    }

    // ── Module / Use / Pub ──────────────────────────────────────────────

    private Decl visitModulDecl(ModulDeclContext ctx) {
        return new Decl.ModDecl(ctx.qualifiedName().getText(), loc(ctx));
    }

    private Decl visitUseDecl(UseDeclContext ctx) {
        String name = ctx.qualifiedName().getText();
        Decl.UseModifier mod = null;
        if (ctx.useModifier() != null) {
            var mc = ctx.useModifier();
            if (mc.KEYWORD() != null) {
                mod = new Decl.UseModifier.Open(mc.KEYWORD().getText());
            } else if (mc.nameList() != null) {
                var names = new ArrayList<String>();
                for (var item : mc.nameList().nameListItem()) {
                    names.add(item.getText());
                }
                mod = new Decl.UseModifier.Selective(names);
            }
        }
        return new Decl.UseDecl(name, mod, loc(ctx));
    }

    private Decl visitPubDecl(PubDeclContext ctx) {
        var loc = loc(ctx);
        if (ctx.fnDecl() != null) return new Decl.PubDecl(visitFnDecl(ctx.fnDecl(), true), loc);
        if (ctx.useDecl() != null) return new Decl.PubDecl(visitUseDecl(ctx.useDecl()), loc);
        if (ctx.binding() != null) return new Decl.PubDecl(new Decl.BindingDecl(visitBinding(ctx.binding()), loc), loc);
        throw new IllegalStateException("Unknown pub declaration at " + loc);
    }

    // ── fn ──────────────────────────────────────────────────────────────

    private Decl.FnDecl visitFnDecl(FnDeclContext ctx, boolean isPub) {
        String name = ctx.fnName().IDENT().getText();
        Decl.FnBody body;
        if (ctx.fnBody() != null) {
            body = visitFnBody(ctx.fnBody());
        } else {
            body = new Decl.FnBody.NoBody();
        }
        return new Decl.FnDecl(name, isPub, body, loc(ctx));
    }

    private Decl.FnBody visitFnBody(FnBodyContext ctx) {
        var content = ctx.fnBodyContent();
        if (content.lambdaBody() != null) {
            return visitLambdaBodyAsFnBody(content.lambdaBody());
        }
        if (content.imperativeBlock() != null) {
            return visitImperativeBlockAsFnBody(content.imperativeBlock());
        }
        if (content.matchArms() != null) {
            return new Decl.FnBody.MatchArmsBody(visitMatchArms(content.matchArms()));
        }
        throw new IllegalStateException("Unknown fn body form at " + loc(ctx));
    }

    private Decl.FnBody.LambdaBody visitLambdaBodyAsFnBody(LambdaBodyContext ctx) {
        var params = visitLambdaParams(ctx.lambdaParams());
        var body = visitExprSeq(ctx.exprSeq());
        return new Decl.FnBody.LambdaBody(params, body);
    }

    private Decl.FnBody.ImperativeBody visitImperativeBlockAsFnBody(ImperativeBlockContext ctx) {
        var params = new ArrayList<Pattern>();
        for (var p : ctx.pattern()) {
            params.add(visitPattern(p));
        }
        var stmts = visitStmtList(ctx.stmtList());
        return new Decl.FnBody.ImperativeBody(params, stmts);
    }

    // ── type / newtype ──────────────────────────────────────────────────

    private Decl.TypeDecl visitTypeDecl(TypeDeclContext ctx) {
        String name = ctx.typeName().TYPE_NAME().getText();
        var typeParams = new ArrayList<String>();
        if (ctx.typeParams() != null) {
            for (var id : ctx.typeParams().IDENT()) {
                typeParams.add(id.getText());
            }
        }
        var body = visitTypeBody(ctx.typeBody());
        return new Decl.TypeDecl(name, typeParams, body, loc(ctx));
    }

    private Decl.TypeBody visitTypeBody(TypeBodyContext ctx) {
        // Check if it's variants (TYPE_NAME starts) or fields (IDENT :: starts)
        if (!ctx.typeVariant().isEmpty()) {
            var variants = new ArrayList<Decl.Variant>();
            for (var v : ctx.typeVariant()) {
                String name = v.TYPE_NAME().getText();
                int arity = v.typeExpr().size();
                variants.add(new Decl.Variant(name, arity));
            }
            return new Decl.TypeBody.SumType(variants);
        }
        if (!ctx.typeField().isEmpty()) {
            var fields = new ArrayList<Decl.Field>();
            for (var f : ctx.typeField()) {
                fields.add(new Decl.Field(f.IDENT().getText()));
            }
            return new Decl.TypeBody.ProductType(fields);
        }
        throw new IllegalStateException("Empty type body at " + loc(ctx));
    }

    private Decl visitNewtypeDecl(NewtypeDeclContext ctx) {
        String name = ctx.typeName().TYPE_NAME().getText();
        return new Decl.NewtypeDecl(name, loc(ctx));
    }

    // ── effect / handler ────────────────────────────────────────────────

    private Decl visitEffectDecl(EffectDeclContext ctx) {
        String name = ctx.typeName().TYPE_NAME().getText();
        var typeParams = new ArrayList<String>();
        if (ctx.typeParams() != null) {
            for (var id : ctx.typeParams().IDENT()) {
                typeParams.add(id.getText());
            }
        }
        var ops = new ArrayList<Decl.EffectOp>();
        if (ctx.effectBody() != null) {
            for (var op : ctx.effectBody().effectOp()) {
                ops.add(new Decl.EffectOp(op.IDENT().getText()));
            }
        }
        return new Decl.EffectDecl(name, typeParams, ops, loc(ctx));
    }

    private Decl visitHandlerDecl(HandlerDeclContext ctx) {
        String name = ctx.fnName().IDENT().getText();
        String effectName = ctx.typeName().TYPE_NAME().getText();
        var clauses = new ArrayList<Decl.HandlerClause>();
        var stateBindings = new ArrayList<Stmt>();
        for (var c : ctx.handlerBody().handlerClause()) {
            if (c.IDENT() != null) {
                String opName = c.IDENT().getText();
                var params = new ArrayList<Pattern>();
                for (var p : c.pattern()) {
                    params.add(visitPattern(p));
                }
                Expr body = visitArmBody(c.armBody());
                clauses.add(new Decl.HandlerClause(opName, params, body));
            } else if (c.binding() != null) {
                stateBindings.add(visitBinding(c.binding()));
            }
        }
        return new Decl.HandlerDecl(name, effectName, clauses, stateBindings, loc(ctx));
    }

    // ── role / proto / impl ─────────────────────────────────────────────

    private Decl visitRoleDecl(RoleDeclContext ctx) {
        return new Decl.RoleDecl(ctx.ROLE_NAME().getText(), loc(ctx));
    }

    private Decl visitProtoDecl(ProtoDeclContext ctx) {
        String name = ctx.typeName().TYPE_NAME().getText();
        return new Decl.StubDecl("proto", name, loc(ctx));
    }

    private Decl visitImplDecl(ImplDeclContext ctx) {
        String protoName = ctx.typeName(0).TYPE_NAME().getText();
        String forType = ctx.typeName(1).TYPE_NAME().getText();
        return new Decl.StubDecl("impl", protoName + " for " + forType, loc(ctx));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Statements
    // ═══════════════════════════════════════════════════════════════════

    private List<Stmt> visitStmtList(StmtListContext ctx) {
        var stmts = new ArrayList<Stmt>();
        for (var s : ctx.stmt()) {
            stmts.add(visitStmt(s));
        }
        return stmts;
    }

    private Stmt visitStmt(StmtContext ctx) {
        if (ctx.binding() != null) return visitBinding(ctx.binding());
        if (ctx.withExpr() != null) return visitWithExpr(ctx.withExpr());
        if (ctx.scopeExpr() != null) return visitScopeExpr(ctx.scopeExpr());
        if (ctx.matchStmt() != null) return visitMatchStmt(ctx.matchStmt());
        if (ctx.ifStmt() != null) return visitIfStmt(ctx.ifStmt());
        if (ctx.selectExpr() != null) return new Stmt.ExprStmt(visitExpr(ctx.selectExpr().selectArms().selectArm(0).expr(0)), loc(ctx));
        if (ctx.parEachExpr() != null) return new Stmt.ExprStmt(new Expr.UnitLit(loc(ctx)), loc(ctx)); // stub
        if (ctx.enclaveExpr() != null) return new Stmt.ExprStmt(new Expr.UnitLit(loc(ctx)), loc(ctx)); // stub
        if (ctx.expr() != null) return new Stmt.ExprStmt(visitExpr(ctx.expr()), loc(ctx));
        throw new IllegalStateException("Unknown statement at " + loc(ctx));
    }

    private Stmt visitBinding(BindingContext ctx) {
        var target = visitBindTarget(ctx.bindTarget());
        var loc = loc(ctx);
        if (ctx.BIND() != null) {
            return new Stmt.Bind(target, visitExpr(ctx.expr()), loc);
        }
        if (ctx.MUT_BIND() != null) {
            return new Stmt.MutBind(target, visitExpr(ctx.expr()), loc);
        }
        if (ctx.ASSIGN() != null) {
            return new Stmt.Assign(target, visitExpr(ctx.expr()), loc);
        }
        throw new IllegalStateException("Unknown binding type at " + loc);
    }

    private Stmt.BindTarget visitBindTarget(BindTargetContext ctx) {
        if (ctx.IDENT() != null) {
            return new Stmt.BindTarget.Simple(ctx.IDENT().getText());
        }
        if (ctx.destructurePattern() != null) {
            return new Stmt.BindTarget.Destructure(visitDestructurePattern(ctx.destructurePattern()));
        }
        throw new IllegalStateException("Unknown bind target at " + loc(ctx));
    }

    // ── Control-flow statements ─────────────────────────────────────────

    private Stmt.MatchStmt visitMatchStmt(MatchStmtContext ctx) {
        var scrutinee = visitExpr(ctx.expr());
        var arms = visitMatchArms(ctx.matchArms());
        return new Stmt.MatchStmt(scrutinee, arms, loc(ctx));
    }

    private Stmt.IfStmt visitIfStmt(IfStmtContext ctx) {
        var cond = visitExpr(ctx.expr());
        var stmtLists = ctx.stmtList();
        var thenBranch = visitStmtList(stmtLists.get(0));
        var elseBranch = stmtLists.size() > 1 ? visitStmtList(stmtLists.get(1)) : List.<Stmt>of();
        return new Stmt.IfStmt(cond, thenBranch, elseBranch, loc(ctx));
    }

    private Stmt.With visitWithExpr(WithExprContext ctx) {
        var handler = visitExpr(ctx.expr());
        var body = visitStmtList(ctx.stmtList(0));
        var onFailure = ctx.stmtList().size() > 1 ? visitStmtList(ctx.stmtList(1)) : List.<Stmt>of();
        return new Stmt.With(handler, body, onFailure, loc(ctx));
    }

    private Stmt.Scope visitScopeExpr(ScopeExprContext ctx) {
        String modifier = null;
        String name = null;
        // scope.race name  or  scope name
        var idents = ctx.IDENT();
        if (ctx.DOT() != null && idents.size() >= 1) {
            modifier = idents.get(0).getText();
            if (idents.size() >= 2) name = idents.get(1).getText();
        } else if (!idents.isEmpty()) {
            name = idents.get(0).getText();
        }
        var body = visitStmtList(ctx.stmtList());
        return new Stmt.Scope(modifier, name, body, loc(ctx));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Match Arms
    // ═══════════════════════════════════════════════════════════════════

    private List<Expr.MatchArm> visitMatchArms(MatchArmsContext ctx) {
        var arms = new ArrayList<Expr.MatchArm>();
        for (var arm : ctx.matchArm()) {
            arms.add(visitMatchArm(arm));
        }
        return arms;
    }

    private Expr.MatchArm visitMatchArm(MatchArmContext ctx) {
        var pattern = visitPattern(ctx.pattern());
        Expr guard = null;
        if (ctx.guard() != null) {
            guard = visitExpr(ctx.guard().expr());
        }
        Expr body = visitArmBody(ctx.armBody());
        return new Expr.MatchArm(pattern, guard, body);
    }

    private Expr visitArmBody(ArmBodyContext ctx) {
        if (ctx.expr() != null) {
            return visitExpr(ctx.expr());
        }
        // Multi-line arm body
        var stmts = visitStmtList(ctx.stmtList());
        return new Expr.Block(stmts, loc(ctx));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Expressions (precedence chain)
    // ═══════════════════════════════════════════════════════════════════

    private Expr visitExpr(ExprContext ctx) {
        return visitApplyToExpr(ctx.applyToExpr());
    }

    private Expr visitApplyToExpr(ApplyToExprContext ctx) {
        if (ctx.applyToExpr() != null) {
            // f ~ rest  →  App(f, [rest])
            Expr fn   = visitChoreographyExpr(ctx.choreographyExpr());
            Expr rest = visitApplyToExpr(ctx.applyToExpr());
            return new Expr.App(fn, List.of(rest), loc(ctx));
        }
        return visitChoreographyExpr(ctx.choreographyExpr());
    }

    private Expr visitChoreographyExpr(ChoreographyExprContext ctx) {
        var pipes = ctx.pipeExpr();
        if (pipes.size() == 1) return visitPipeExpr(pipes.get(0));
        // Multiple with choreography operators
        Expr result = visitPipeExpr(pipes.get(0));
        for (int i = 1; i < pipes.size(); i++) {
            // Determine which operator (SEND, RECV, BROADCAST, CH_SELECT)
            // tokens between pipe exprs
            String op = "~>"; // default
            var children = ctx.children;
            int tokensBefore = 0;
            for (int j = 0; j < children.size(); j++) {
                if (children.get(j) instanceof TerminalNode tn) {
                    var type = tn.getSymbol().getType();
                    if (type == IrijParser.SEND) op = "~>";
                    else if (type == IrijParser.RECV) op = "<~";
                    else if (type == IrijParser.BROADCAST) op = "~*>";
                    else if (type == IrijParser.CH_SELECT) op = "~/";
                }
                if (children.get(j) == pipes.get(i)) break;
            }
            result = new Expr.ChoreoExpr(op, result, visitPipeExpr(pipes.get(i)), loc(ctx));
        }
        return result;
    }

    private Expr visitPipeExpr(PipeExprContext ctx) {
        var comps = ctx.composeExpr();
        if (comps.size() == 1) return visitComposeExpr(comps.get(0));
        Expr result = visitComposeExpr(comps.get(0));
        for (int i = 1; i < comps.size(); i++) {
            boolean forward = isForwardOp(ctx, i, IrijParser.PIPE, IrijParser.PIPE_BACK);
            result = new Expr.Pipe(result, visitComposeExpr(comps.get(i)), forward, loc(ctx));
        }
        return result;
    }

    private Expr visitComposeExpr(ComposeExprContext ctx) {
        var ors = ctx.orExpr();
        if (ors.size() == 1) return visitOrExpr(ors.get(0));
        Expr result = visitOrExpr(ors.get(0));
        for (int i = 1; i < ors.size(); i++) {
            boolean forward = isForwardOp(ctx, i, IrijParser.COMPOSE, IrijParser.COMPOSE_BACK);
            result = new Expr.Compose(result, visitOrExpr(ors.get(i)), forward, loc(ctx));
        }
        return result;
    }

    private Expr visitOrExpr(OrExprContext ctx) {
        var ands = ctx.andExpr();
        if (ands.size() == 1) return visitAndExpr(ands.get(0));
        Expr result = visitAndExpr(ands.get(0));
        for (int i = 1; i < ands.size(); i++) {
            result = new Expr.BinaryOp("||", result, visitAndExpr(ands.get(i)), loc(ctx));
        }
        return result;
    }

    private Expr visitAndExpr(AndExprContext ctx) {
        var eqs = ctx.eqExpr();
        if (eqs.size() == 1) return visitEqExpr(eqs.get(0));
        Expr result = visitEqExpr(eqs.get(0));
        for (int i = 1; i < eqs.size(); i++) {
            result = new Expr.BinaryOp("&&", result, visitEqExpr(eqs.get(i)), loc(ctx));
        }
        return result;
    }

    private Expr visitEqExpr(EqExprContext ctx) {
        var comps = ctx.compExpr();
        if (comps.size() == 1) return visitCompExpr(comps.get(0));
        Expr result = visitCompExpr(comps.get(0));
        for (int i = 1; i < comps.size(); i++) {
            String op = findOpBetween(ctx, comps.get(i - 1), comps.get(i),
                    IrijParser.EQ, IrijParser.NEQ);
            result = new Expr.BinaryOp(op, result, visitCompExpr(comps.get(i)), loc(ctx));
        }
        return result;
    }

    private Expr visitCompExpr(CompExprContext ctx) {
        var concats = ctx.concatExpr();
        if (concats.size() == 1) return visitConcatExpr(concats.get(0));
        Expr result = visitConcatExpr(concats.get(0));
        for (int i = 1; i < concats.size(); i++) {
            String op = findOpBetween(ctx, concats.get(i - 1), concats.get(i),
                    IrijParser.LT, IrijParser.GT, IrijParser.LTE, IrijParser.GTE);
            result = new Expr.BinaryOp(op, result, visitConcatExpr(concats.get(i)), loc(ctx));
        }
        return result;
    }

    private Expr visitConcatExpr(ConcatExprContext ctx) {
        var ranges = ctx.rangeExpr();
        if (ranges.size() == 1) return visitRangeExpr(ranges.get(0));
        Expr result = visitRangeExpr(ranges.get(0));
        for (int i = 1; i < ranges.size(); i++) {
            result = new Expr.BinaryOp("++", result, visitRangeExpr(ranges.get(i)), loc(ctx));
        }
        return result;
    }

    private Expr visitRangeExpr(RangeExprContext ctx) {
        var adds = ctx.addExpr();
        if (adds.size() == 1) return visitAddExpr(adds.get(0));
        boolean exclusive = ctx.RANGE_EXCL() != null;
        return new Expr.Range(visitAddExpr(adds.get(0)), visitAddExpr(adds.get(1)),
                exclusive, loc(ctx));
    }

    private Expr visitAddExpr(AddExprContext ctx) {
        var muls = ctx.mulExpr();
        if (muls.size() == 1) return visitMulExpr(muls.get(0));
        Expr result = visitMulExpr(muls.get(0));
        for (int i = 1; i < muls.size(); i++) {
            String op = findOpBetween(ctx, muls.get(i - 1), muls.get(i),
                    IrijParser.PLUS, IrijParser.MINUS);
            result = new Expr.BinaryOp(op, result, visitMulExpr(muls.get(i)), loc(ctx));
        }
        return result;
    }

    private Expr visitMulExpr(MulExprContext ctx) {
        var pows = ctx.powExpr();
        if (pows.size() == 1) return visitPowExpr(pows.get(0));
        Expr result = visitPowExpr(pows.get(0));
        for (int i = 1; i < pows.size(); i++) {
            String op = findOpBetween(ctx, pows.get(i - 1), pows.get(i),
                    IrijParser.STAR, IrijParser.SLASH, IrijParser.PERCENT);
            result = new Expr.BinaryOp(op, result, visitPowExpr(pows.get(i)), loc(ctx));
        }
        return result;
    }

    private Expr visitPowExpr(PowExprContext ctx) {
        var unarys = ctx.unaryExpr();
        if (unarys.size() == 1) return visitUnaryExpr(unarys.get(0));
        return new Expr.BinaryOp("**", visitUnaryExpr(unarys.get(0)),
                visitUnaryExpr(unarys.get(1)), loc(ctx));
    }

    private Expr visitUnaryExpr(UnaryExprContext ctx) {
        if (ctx.NOT() != null) {
            return new Expr.UnaryOp("!", visitUnaryExpr(ctx.unaryExpr()), loc(ctx));
        }
        if (ctx.MINUS() != null) {
            return new Expr.UnaryOp("-", visitUnaryExpr(ctx.unaryExpr()), loc(ctx));
        }
        return visitSeqOpExpr(ctx.seqOpExpr());
    }

    private Expr visitSeqOpExpr(SeqOpExprContext ctx) {
        if (ctx.seqOp() != null) {
            String op = ctx.seqOp().getText();
            Expr arg = ctx.appExpr() != null ? visitAppExpr(ctx.appExpr()) : null;
            return new Expr.SeqOp(op, arg, loc(ctx));
        }
        return visitAppExpr(ctx.appExpr());
    }

    private Expr visitAppExpr(AppExprContext ctx) {
        var postfixes = ctx.postfixExpr();
        if (postfixes.size() == 1) return visitPostfixExpr(postfixes.get(0));
        // First is function, rest are arguments
        Expr fn = visitPostfixExpr(postfixes.get(0));
        var args = new ArrayList<Expr>();
        for (int i = 1; i < postfixes.size(); i++) {
            args.add(visitPostfixExpr(postfixes.get(i)));
        }
        return new Expr.App(fn, args, loc(ctx));
    }

    private Expr visitPostfixExpr(PostfixExprContext ctx) {
        Expr result = visitAtomExpr(ctx.atomExpr());
        // Dot access chain: walk children after atomExpr
        // Grammar: atomExpr (DOT (IDENT | TYPE_NAME))* (MAP_AT ROLE_NAME)?
        boolean afterDot = false;
        for (var child : ctx.children) {
            if (child instanceof TerminalNode tn) {
                int type = tn.getSymbol().getType();
                if (type == IrijParser.DOT) {
                    afterDot = true;
                } else if (afterDot && (type == IrijParser.IDENT || type == IrijParser.TYPE_NAME)) {
                    result = new Expr.DotAccess(result, tn.getText(), loc(ctx));
                    afterDot = false;
                }
                // MAP_AT ROLE_NAME — ignore at runtime
            }
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Atomic Expressions
    // ═══════════════════════════════════════════════════════════════════

    private Expr visitAtomExpr(AtomExprContext ctx) {
        if (ctx.literal() != null) return visitLiteral(ctx.literal());
        if (ctx.IDENT() != null) return new Expr.Var(ctx.IDENT().getText(), loc(ctx));
        if (ctx.TYPE_NAME() != null) return new Expr.TypeRef(ctx.TYPE_NAME().getText(), loc(ctx));
        if (ctx.ROLE_NAME() != null) return new Expr.RoleRef(ctx.ROLE_NAME().getText(), loc(ctx));
        if (ctx.KEYWORD() != null) return visitKeyword(ctx.KEYWORD(), loc(ctx));
        if (ctx.UNDERSCORE() != null) return new Expr.Wildcard(loc(ctx));
        if (ctx.ifExpr() != null) return visitIfExpr(ctx.ifExpr());
        if (ctx.matchExpr() != null) return visitMatchExpr(ctx.matchExpr());
        if (ctx.lambdaExpr() != null) return visitLambdaExpr(ctx.lambdaExpr());
        if (ctx.operatorAsValue() != null) return visitOperatorAsValue(ctx.operatorAsValue());
        if (ctx.unitExpr() != null) return new Expr.UnitLit(loc(ctx));
        if (ctx.parenExpr() != null) return visitParenExpr(ctx.parenExpr());
        if (ctx.vectorLiteral() != null) return visitVectorLiteral(ctx.vectorLiteral());
        if (ctx.setLiteral() != null) return visitSetLiteral(ctx.setLiteral());
        if (ctx.tupleLiteral() != null) return visitTupleLiteral(ctx.tupleLiteral());
        if (ctx.mapLiteral() != null) return visitMapLiteral(ctx.mapLiteral());
        if (ctx.doExpr() != null) return visitDoExpr(ctx.doExpr());
        throw new IllegalStateException("Unknown atom expression at " + loc(ctx));
    }

    private Expr visitLiteral(LiteralContext ctx) {
        var loc = loc(ctx);
        if (ctx.INT_LIT() != null) {
            return new Expr.IntLit(parseLong(ctx.INT_LIT().getText()), loc);
        }
        if (ctx.FLOAT_LIT() != null) {
            return new Expr.FloatLit(parseDouble(ctx.FLOAT_LIT().getText()), loc);
        }
        if (ctx.HEX_LIT() != null) {
            String text = ctx.HEX_LIT().getText().replaceAll("_", "");
            return new Expr.HexLit(Long.parseLong(text.substring(2), 16), loc);
        }
        if (ctx.RATIONAL() != null) {
            String text = ctx.RATIONAL().getText();
            int slash = text.indexOf('/');
            long num = Long.parseLong(text.substring(0, slash));
            long den = Long.parseLong(text.substring(slash + 1));
            return new Expr.RationalLit(num, den, loc);
        }
        if (ctx.STRING() != null) {
            return visitString(ctx.STRING(), loc);
        }
        throw new IllegalStateException("Unknown literal at " + loc);
    }

    private Expr visitString(TerminalNode stringToken, SourceLoc loc) {
        String raw = stringToken.getText();
        // Remove surrounding quotes
        String content = raw.substring(1, raw.length() - 1);

        // Check for interpolation
        if (!content.contains("${")) {
            return new Expr.StrLit(unescapeString(content), loc);
        }

        // Parse interpolated string
        var parts = new ArrayList<Expr.StringPart>();
        int i = 0;
        while (i < content.length()) {
            int interpStart = content.indexOf("${", i);
            if (interpStart < 0) {
                // Rest is literal
                parts.add(new Expr.StringPart.Literal(unescapeString(content.substring(i))));
                break;
            }
            // Add literal part before ${
            if (interpStart > i) {
                parts.add(new Expr.StringPart.Literal(unescapeString(content.substring(i, interpStart))));
            }
            // Find matching }
            int depth = 0;
            int j = interpStart + 2;
            while (j < content.length()) {
                if (content.charAt(j) == '{') depth++;
                else if (content.charAt(j) == '}') {
                    if (depth == 0) break;
                    depth--;
                }
                j++;
            }
            String exprText = content.substring(interpStart + 2, j);
            // Parse the expression inside ${}
            var parseResult = IrijParseDriver.parse(exprText + "\n");
            if (!parseResult.hasErrors() && parseResult.tree().topLevelDecl().size() > 0) {
                var tld = parseResult.tree().topLevelDecl().get(0);
                if (tld.expr() != null) {
                    var interpExpr = visitExpr(tld.expr());
                    parts.add(new Expr.StringPart.Interpolation(interpExpr));
                } else {
                    // Fall back to variable reference
                    parts.add(new Expr.StringPart.Interpolation(new Expr.Var(exprText.strip(), loc)));
                }
            } else {
                // Simple variable reference
                parts.add(new Expr.StringPart.Interpolation(new Expr.Var(exprText.strip(), loc)));
            }
            i = j + 1;
        }

        if (parts.size() == 1 && parts.get(0) instanceof Expr.StringPart.Literal(var text)) {
            return new Expr.StrLit(text, loc);
        }
        return new Expr.StringInterp(parts, loc);
    }

    private Expr visitKeyword(TerminalNode kw, SourceLoc loc) {
        String text = kw.getText(); // ":ok" → strip the colon
        return new Expr.KeywordLit(text.substring(1), loc);
    }

    private Expr visitIfExpr(IfExprContext ctx) {
        var cond = visitAtomExpr(ctx.atomExpr(0));
        var then_ = visitAtomExpr(ctx.atomExpr(1));
        var else_ = visitAtomExpr(ctx.atomExpr(2));
        return new Expr.IfExpr(cond, then_, else_, loc(ctx));
    }

    private Expr visitMatchExpr(MatchExprContext ctx) {
        var scrutinee = visitExpr(ctx.expr());
        var arms = visitMatchArms(ctx.matchArms());
        return new Expr.MatchExpr(scrutinee, arms, loc(ctx));
    }

    private Expr visitLambdaExpr(LambdaExprContext ctx) {
        var params = visitLambdaParams(ctx.lambdaParams());
        var body = visitExprSeq(ctx.exprSeq());
        return new Expr.Lambda(params, body, loc(ctx));
    }

    private List<Pattern> visitLambdaParams(LambdaParamsContext ctx) {
        var params = new ArrayList<Pattern>();
        for (var p : ctx.pattern()) {
            params.add(visitPattern(p));
        }
        return params;
    }

    private Expr visitOperatorAsValue(OperatorAsValueContext ctx) {
        // The operator token is between LPAREN and RPAREN — get the second child
        var opToken = (TerminalNode) ctx.getChild(1);
        return new Expr.OpSection(opToken.getText(), loc(ctx));
    }

    private Expr visitExprSeq(ExprSeqContext ctx) {
        var exprs = ctx.expr();
        if (exprs.size() == 1) return visitExpr(exprs.get(0));
        // Multiple expressions separated by semicolons → Block
        var stmts = new ArrayList<Stmt>();
        for (var e : exprs) {
            stmts.add(new Stmt.ExprStmt(visitExpr(e), loc(e)));
        }
        return new Expr.Block(stmts, loc(ctx));
    }

    private Expr visitParenExpr(ParenExprContext ctx) {
        return visitExprSeq(ctx.exprSeq());
    }

    // ── Collection Literals ─────────────────────────────────────────────

    private Expr visitVectorLiteral(VectorLiteralContext ctx) {
        var elements = ctx.exprList() != null ? visitExprListFlat(ctx.exprList()) : List.<Expr>of();
        return new Expr.VectorLit(elements, loc(ctx));
    }

    private Expr visitSetLiteral(SetLiteralContext ctx) {
        var elements = ctx.exprList() != null ? visitExprListFlat(ctx.exprList()) : List.<Expr>of();
        return new Expr.SetLit(elements, loc(ctx));
    }

    private Expr visitTupleLiteral(TupleLiteralContext ctx) {
        var elements = ctx.exprList() != null ? visitExprListFlat(ctx.exprList()) : List.<Expr>of();
        return new Expr.TupleLit(elements, loc(ctx));
    }

    private Expr visitMapLiteral(MapLiteralContext ctx) {
        if (ctx.mapEntryList() == null) {
            return new Expr.MapLit(List.of(), loc(ctx));
        }
        var entries = new ArrayList<Expr.MapEntry>();
        boolean hasSpreadFirst = false;
        String spreadBase = null;
        for (var entry : ctx.mapEntryList().mapEntry()) {
            if (entry.SPREAD() != null) {
                spreadBase = entry.IDENT().getText();
                hasSpreadFirst = true;
            } else {
                entries.add(new Expr.MapEntry.Field(entry.IDENT().getText(), visitExpr(entry.expr())));
            }
        }
        if (hasSpreadFirst) {
            return new Expr.RecordUpdate(spreadBase, entries, loc(ctx));
        }
        return new Expr.MapLit(entries, loc(ctx));
    }

    private List<Expr> visitExprList(ExprListContext ctx) {
        var exprs = new ArrayList<Expr>();
        for (var e : ctx.expr()) {
            exprs.add(visitExpr(e));
        }
        return exprs;
    }

    /**
     * Visit an exprList in a collection context (vector, set, tuple).
     * The grammar's appExpr : postfixExpr+ is greedy, so #[1 2 3] parses as
     * a single expr with appExpr containing 3 postfixExprs (function application).
     * In collection context, we want to flatten that into 3 separate elements.
     *
     * Heuristic: if the exprList has exactly one expr, and that expr's bottom
     * appExpr has multiple postfixExprs where the first is a literal/non-callable,
     * flatten into separate elements.
     */
    private List<Expr> visitExprListFlat(ExprListContext ctx) {
        var exprs = ctx.expr();
        if (exprs.size() > 1) {
            // Multiple expr nodes → just visit each normally
            return visitExprList(ctx);
        }
        if (exprs.size() == 1) {
            // Drill down to appExpr level
            var appExpr = findAppExpr(exprs.get(0));
            if (appExpr != null && appExpr.postfixExpr().size() > 1) {
                // Flatten: each postfixExpr is a separate collection element
                var result = new ArrayList<Expr>();
                for (var pe : appExpr.postfixExpr()) {
                    result.add(visitPostfixExpr(pe));
                }
                return result;
            }
            // Single element
            return List.of(visitExpr(exprs.get(0)));
        }
        return List.of();
    }

    /**
     * Navigate down the expression precedence chain to find the bottom appExpr,
     * but only if each level has exactly one child (no operators at any level).
     */
    private AppExprContext findAppExpr(ExprContext ctx) {
        var applyTo = ctx.applyToExpr();
        if (applyTo.applyToExpr() != null) return null; // has ~ operator
        var choreo = applyTo.choreographyExpr();
        if (choreo.pipeExpr().size() != 1) return null;
        var pipe = choreo.pipeExpr().get(0);
        if (pipe.composeExpr().size() != 1) return null;
        var compose = pipe.composeExpr().get(0);
        if (compose.orExpr().size() != 1) return null;
        var or = compose.orExpr().get(0);
        if (or.andExpr().size() != 1) return null;
        var and = or.andExpr().get(0);
        if (and.eqExpr().size() != 1) return null;
        var eq = and.eqExpr().get(0);
        if (eq.compExpr().size() != 1) return null;
        var comp = eq.compExpr().get(0);
        if (comp.concatExpr().size() != 1) return null;
        var concat = comp.concatExpr().get(0);
        if (concat.rangeExpr().size() != 1) return null;
        var range = concat.rangeExpr().get(0);
        if (range.addExpr().size() != 1) return null;
        var add = range.addExpr().get(0);
        if (add.mulExpr().size() != 1) return null;
        var mul = add.mulExpr().get(0);
        if (mul.powExpr().size() != 1) return null;
        var pow = mul.powExpr().get(0);
        if (pow.unaryExpr().size() != 1) return null;
        var unary = pow.unaryExpr().get(0);
        if (unary.seqOpExpr() == null) return null;
        var seqOp = unary.seqOpExpr();
        if (seqOp.seqOp() != null) return null; // has a seq op prefix
        return seqOp.appExpr();
    }

    private Expr visitDoExpr(DoExprContext ctx) {
        var exprs = new ArrayList<Expr>();
        for (var p : ctx.parenExpr()) {
            exprs.add(visitParenExpr(p));
        }
        return new Expr.DoExpr(exprs, loc(ctx));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Patterns
    // ═══════════════════════════════════════════════════════════════════

    private Pattern visitPattern(PatternContext ctx) {
        var loc = loc(ctx);

        // Constructor pattern: TYPE_NAME patterns*
        if (ctx.TYPE_NAME() != null) {
            String name = ctx.TYPE_NAME().getText();
            var args = new ArrayList<Pattern>();
            for (var p : ctx.pattern()) {
                args.add(visitPattern(p));
            }
            return new Pattern.ConstructorPat(name, args, loc);
        }

        // Keyword pattern: :ok  or  :ok value
        if (ctx.KEYWORD() != null) {
            String name = ctx.KEYWORD().getText().substring(1);
            Pattern arg = null;
            if (!ctx.pattern().isEmpty()) {
                arg = visitPattern(ctx.pattern().get(0));
            }
            return new Pattern.KeywordPat(name, arg, loc);
        }

        // Spread pattern: ...rest or ..._ (MUST be checked before IDENT/UNDERSCORE)
        if (ctx.SPREAD() != null) {
            if (ctx.IDENT() != null) {
                return new Pattern.SpreadPat(ctx.IDENT().getText(), loc);
            }
            if (ctx.UNDERSCORE() != null) {
                return new Pattern.SpreadPat("_", loc);
            }
            return new Pattern.SpreadPat("_", loc);
        }

        // Variable pattern
        if (ctx.IDENT() != null) {
            return new Pattern.VarPat(ctx.IDENT().getText(), loc);
        }

        // Wildcard
        if (ctx.UNDERSCORE() != null) {
            return new Pattern.WildcardPat(loc);
        }

        // Literal pattern
        if (ctx.literal() != null) {
            return new Pattern.LitPat(visitLiteral(ctx.literal()), loc);
        }

        // Unit pattern: ()
        if (ctx.LPAREN() != null && ctx.RPAREN() != null && ctx.pattern().isEmpty()) {
            return new Pattern.UnitPat(loc);
        }

        // Grouped pattern: (pat)
        if (ctx.LPAREN() != null && !ctx.pattern().isEmpty()) {
            return new Pattern.GroupedPat(visitPattern(ctx.pattern().get(0)), loc);
        }

        // Vector pattern
        if (ctx.vectorPattern() != null) {
            return visitVectorPattern(ctx.vectorPattern());
        }

        // Tuple pattern
        if (ctx.tuplePattern() != null) {
            return visitTuplePattern(ctx.tuplePattern());
        }

        // Destructure pattern
        if (ctx.destructurePattern() != null) {
            return visitDestructurePattern(ctx.destructurePattern());
        }

        throw new IllegalStateException("Unknown pattern at " + loc);
    }

    private Pattern visitVectorPattern(VectorPatternContext ctx) {
        if (ctx.patternListWithSpread() == null) {
            return new Pattern.VectorPat(List.of(), null, loc(ctx));
        }
        var elements = new ArrayList<Pattern>();
        Pattern.SpreadPat spread = null;
        var plws = ctx.patternListWithSpread();

        // The patternListWithSpread rule can have:
        // 1. pattern children (including spread patterns like ...r parsed as pattern -> SPREAD IDENT)
        // 2. Direct SPREAD IDENT/UNDERSCORE tokens (from the alternative in the rule)
        for (var child : plws.children) {
            if (child instanceof PatternContext pc) {
                var pat = visitPattern(pc);
                if (pat instanceof Pattern.SpreadPat sp) {
                    spread = sp;
                } else {
                    elements.add(pat);
                }
            }
        }
        // Also check for direct SPREAD tokens in the rule (not wrapped in pattern)
        if (spread == null && plws.SPREAD() != null && !plws.SPREAD().isEmpty()) {
            var spreadIdents = plws.IDENT();
            if (spreadIdents != null && !spreadIdents.isEmpty()) {
                spread = new Pattern.SpreadPat(spreadIdents.get(0).getText(), loc(ctx));
            } else if (plws.UNDERSCORE() != null && !plws.UNDERSCORE().isEmpty()) {
                spread = new Pattern.SpreadPat("_", loc(ctx));
            }
        }
        return new Pattern.VectorPat(elements, spread, loc(ctx));
    }

    private Pattern visitTuplePattern(TuplePatternContext ctx) {
        var elements = new ArrayList<Pattern>();
        for (var p : ctx.pattern()) {
            elements.add(visitPattern(p));
        }
        return new Pattern.TuplePat(elements, loc(ctx));
    }

    private Pattern visitDestructurePattern(DestructurePatternContext ctx) {
        var fields = new ArrayList<Pattern.DestructureField>();
        for (var f : ctx.destructureField()) {
            fields.add(new Pattern.DestructureField(f.IDENT().getText(), visitPattern(f.pattern())));
        }
        return new Pattern.DestructurePat(fields, loc(ctx));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════

    private SourceLoc loc(ParserRuleContext ctx) {
        Token start = ctx.getStart();
        return start != null ? new SourceLoc(start.getLine(), start.getCharPositionInLine() + 1) : SourceLoc.UNKNOWN;
    }

    private String getText(ParserRuleContext ctx) {
        return ctx.getText();
    }

    private long parseLong(String text) {
        return Long.parseLong(text.replaceAll("_", ""));
    }

    private double parseDouble(String text) {
        return Double.parseDouble(text.replaceAll("_", ""));
    }

    private String unescapeString(String s) {
        return s.replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\$", "$");
    }

    /** Determine if the operator between two sub-expressions is forward or backward. */
    private boolean isForwardOp(ParserRuleContext ctx, int exprIndex, int forwardType, int backwardType) {
        // Walk children to find the operator token before the i-th sub-expression
        int subExprCount = 0;
        for (var child : ctx.children) {
            if (child instanceof ParserRuleContext) {
                subExprCount++;
                if (subExprCount == exprIndex + 1) break;
            }
            if (child instanceof TerminalNode tn && subExprCount == exprIndex) {
                return tn.getSymbol().getType() == forwardType;
            }
        }
        return true; // default forward
    }

    /** Find which operator token is between two sub-expressions. */
    private String findOpBetween(ParserRuleContext ctx,
                                 ParserRuleContext before, ParserRuleContext after,
                                 int... tokenTypes) {
        boolean passedBefore = false;
        for (var child : ctx.children) {
            if (child == before) { passedBefore = true; continue; }
            if (child == after) break;
            if (passedBefore && child instanceof TerminalNode tn) {
                int type = tn.getSymbol().getType();
                for (int tt : tokenTypes) {
                    if (type == tt) return tn.getText();
                }
            }
        }
        // Fallback
        return switch (tokenTypes[0]) {
            case IrijParser.PLUS -> "+";
            case IrijParser.STAR -> "*";
            case IrijParser.EQ -> "==";
            case IrijParser.LT -> "<";
            default -> "?";
        };
    }
}
