package dev.irij.interpreter;

import dev.irij.parser.IrijParser;
import dev.irij.parser.IrijParserBaseVisitor;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.*;

/**
 * Tree-walking interpreter for Irij.
 *
 * <p>Supports enough of the language to execute simple programs:
 * function declarations, effect declarations, handlers, with-blocks,
 * function application, string literals, and built-in IO operations.
 */
public final class IrijInterpreter extends IrijParserBaseVisitor<Object> {

    /** Unit value — the sole inhabitant of () */
    public static final Object UNIT = new Object() {
        @Override public String toString() { return "()"; }
    };

    // ── Top-level registries ───────────────────────────────────────────

    /** effect name → list of operation names */
    private final Map<String, List<String>> effects = new LinkedHashMap<>();

    /** handler name → (effect name, op name → HandlerClause) */
    private final Map<String, HandlerEntry> handlers = new LinkedHashMap<>();

    /** function name → FnDecl parse tree node */
    private final Map<String, IrijParser.FnDeclContext> functions = new LinkedHashMap<>();

    // ── Runtime state ──────────────────────────────────────────────────

    /** Stack of active handlers (most recent = top). Pushed by `with`. */
    private final Deque<HandlerEntry> handlerStack = new ArrayDeque<>();

    /** Lexical scope chain for variable bindings */
    private final Deque<Map<String, Object>> scopeStack = new ArrayDeque<>();

    // ── Data classes ───────────────────────────────────────────────────

    record HandlerEntry(String handlerName, String effectName,
                        Map<String, HandlerClause> operations) {}

    record HandlerClause(List<String> params,
                         IrijParser.ExprContext body) {}

    record IrijFunction(String name, IrijParser.FnDeclContext decl) {}

    // ── Entry point ────────────────────────────────────────────────────

    /**
     * Execute a parsed compilation unit. Collects declarations, then runs main().
     */
    public void execute(IrijParser.CompilationUnitContext cu) {
        // Phase 1: collect all declarations
        for (var decl : cu.topLevelDecl()) {
            collectDeclaration(decl);
        }

        // Phase 2: run main
        var main = functions.get("main");
        if (main == null) {
            throw new IrijRuntimeError("No 'main' function defined");
        }
        pushScope();
        try {
            evalFnBody(main);
        } finally {
            popScope();
        }
    }

    // ── Declaration collection ──────────────────────────────────────────

    private void collectDeclaration(IrijParser.TopLevelDeclContext decl) {
        if (decl.effectDecl() != null) {
            collectEffect(decl.effectDecl());
        } else if (decl.handlerDecl() != null) {
            collectHandler(decl.handlerDecl());
        } else if (decl.fnDecl() != null) {
            collectFunction(decl.fnDecl());
        }
        // Other declarations (type, proto, impl, etc.) are ignored for now
    }

    private void collectEffect(IrijParser.EffectDeclContext ctx) {
        String name = ctx.typeName().getText();
        var ops = new ArrayList<String>();
        for (var op : ctx.effectBody().effectOp()) {
            ops.add(op.LOWER_ID().getText());
        }
        effects.put(name, ops);
    }

    private void collectHandler(IrijParser.HandlerDeclContext ctx) {
        String handlerName = ctx.LOWER_ID().getText();
        String effectName = ctx.typeName().getText();
        var ops = new LinkedHashMap<String, HandlerClause>();

        for (var clause : ctx.handlerBody().handlerClause()) {
            if (clause.LOWER_ID() != null) {
                String opName = clause.LOWER_ID().getText();
                var params = new ArrayList<String>();
                for (var pat : clause.pattern()) {
                    params.add(pat.getText());
                }
                ops.put(opName, new HandlerClause(params, clause.expr()));
            }
        }
        handlers.put(handlerName, new HandlerEntry(handlerName, effectName, ops));
    }

    private void collectFunction(IrijParser.FnDeclContext ctx) {
        String name = ctx.fnName().getText();
        functions.put(name, ctx);
    }

    // ── Function body evaluation ────────────────────────────────────────

    private Object evalFnBody(IrijParser.FnDeclContext fn) {
        var body = fn.fnBody();
        if (body != null) {
            return evalFnBodyCtx(body);
        }
        var inline = fn.fnBodyInline();
        if (inline != null) {
            if (inline.lambdaExpr() != null) {
                return evalExpr(inline.lambdaExpr());
            }
            // matchArms — not needed for hello.irj
        }
        return UNIT;
    }

    private Object evalFnBodyCtx(IrijParser.FnBodyContext body) {
        // fnBody: matchArms | lambdaExpr NEWLINE* | statement (NEWLINE+ statement)* NEWLINE*
        if (body.matchArms() != null) {
            // Not needed for hello.irj yet
            return UNIT;
        }
        if (body.lambdaExpr() != null) {
            return evalExpr(body.lambdaExpr());
        }
        // Statements
        Object result = UNIT;
        for (var stmt : body.statement()) {
            result = evalStatement(stmt);
        }
        return result;
    }

    // ── Statements ──────────────────────────────────────────────────────

    private Object evalStatement(IrijParser.StatementContext stmt) {
        if (stmt.binding() != null) {
            return evalBinding(stmt.binding());
        }
        if (stmt.expr() != null) {
            return evalExpr(stmt.expr());
        }
        return UNIT;
    }

    private Object evalBinding(IrijParser.BindingContext bind) {
        if (bind.BIND() != null && bind.LOWER_ID() != null) {
            String name = bind.LOWER_ID().getText();
            Object value = evalExpr(bind.expr());
            currentScope().put(name, value);
            return value;
        }
        if (bind.MUT_BIND() != null && bind.LOWER_ID() != null) {
            String name = bind.LOWER_ID().getText();
            Object value = evalExpr(bind.expr());
            currentScope().put(name, value);
            return value;
        }
        if (bind.ASSIGN() != null && bind.LOWER_ID() != null) {
            String name = bind.LOWER_ID().getText();
            Object value = evalExpr(bind.expr());
            // Mutate in enclosing scope
            for (var scope : scopeStack) {
                if (scope.containsKey(name)) {
                    scope.put(name, value);
                    return value;
                }
            }
            currentScope().put(name, value);
            return value;
        }
        return UNIT;
    }

    // ── Expression evaluation ───────────────────────────────────────────

    private Object evalExpr(ParseTree node) {
        if (node == null) return UNIT;

        return switch (node) {
            case IrijParser.ExprContext ctx -> evalExprCtx(ctx);
            case IrijParser.WithExprContext ctx -> evalWithExpr(ctx);
            case IrijParser.PipeExprContext ctx -> evalPipeExpr(ctx);
            case IrijParser.ComposeExprContext ctx -> evalComposeExpr(ctx);
            case IrijParser.ChoreographyExprContext ctx -> evalExpr(ctx.orExpr());
            case IrijParser.OrExprContext ctx -> evalOrExpr(ctx);
            case IrijParser.AndExprContext ctx -> evalAndExpr(ctx);
            case IrijParser.EqExprContext ctx -> evalEqExpr(ctx);
            case IrijParser.CompExprContext ctx -> evalCompExpr(ctx);
            case IrijParser.ConcatExprContext ctx -> evalConcatExpr(ctx);
            case IrijParser.RangeExprContext ctx -> evalExpr(ctx.addExpr(0));
            case IrijParser.AddExprContext ctx -> evalAddExpr(ctx);
            case IrijParser.MulExprContext ctx -> evalMulExpr(ctx);
            case IrijParser.PowExprContext ctx -> evalExpr(ctx.unaryExpr(0));
            case IrijParser.UnaryExprContext ctx -> evalUnaryExpr(ctx);
            case IrijParser.SeqOpExprContext ctx -> evalSeqOpExpr(ctx);
            case IrijParser.PostfixExprContext ctx -> evalPostfixExpr(ctx);
            case IrijParser.AppExprContext ctx -> evalAppExpr(ctx);
            case IrijParser.AtomExprContext ctx -> evalAtomExpr(ctx);
            case IrijParser.LiteralContext ctx -> evalLiteral(ctx);
            case IrijParser.IfExprContext ctx -> evalIfExpr(ctx);
            case IrijParser.MatchExprContext ctx -> evalMatchExpr(ctx);
            case IrijParser.DoExprContext ctx -> evalDoExpr(ctx);
            case IrijParser.LambdaExprContext ctx -> evalLambdaOrReturn(ctx);
            case IrijParser.ScopeExprContext ctx -> evalScopeExpr(ctx);
            default -> {
                // Fallback: try to evaluate children
                if (node.getChildCount() == 1) {
                    yield evalExpr(node.getChild(0));
                }
                yield UNIT;
            }
        };
    }

    private Object evalExprCtx(IrijParser.ExprContext ctx) {
        // expr delegates to one of: ifExpr, matchExpr, doExpr, withExpr,
        //   scopeExpr, selectExpr, enclaveExpr, lambdaExpr, pipeExpr
        if (ctx.withExpr() != null) return evalWithExpr(ctx.withExpr());
        if (ctx.ifExpr() != null) return evalIfExpr(ctx.ifExpr());
        if (ctx.matchExpr() != null) return evalMatchExpr(ctx.matchExpr());
        if (ctx.doExpr() != null) return evalDoExpr(ctx.doExpr());
        if (ctx.scopeExpr() != null) return evalScopeExpr(ctx.scopeExpr());
        if (ctx.lambdaExpr() != null) return evalLambdaOrReturn(ctx.lambdaExpr());
        if (ctx.pipeExpr() != null) return evalPipeExpr(ctx.pipeExpr());
        return UNIT;
    }

    // ── with handler body ───────────────────────────────────────────────

    private Object evalWithExpr(IrijParser.WithExprContext ctx) {
        // withExpr: WITH expr NEWLINE INDENT fnBody DEDENT onFailure?
        //         | WITH expr expr  (inline)
        //
        // The first expr is the handler reference (a LOWER_ID typically)
        // We need to resolve it to a handler name

        // Get the handler expression — for hello.irj this is just a LOWER_ID
        var handlerExpr = ctx.expr(0);
        String handlerName = extractIdentifier(handlerExpr);

        if (handlerName == null) {
            throw new IrijRuntimeError("Cannot resolve handler expression: " + handlerExpr.getText());
        }

        var handler = handlers.get(handlerName);
        if (handler == null) {
            throw new IrijRuntimeError("Unknown handler: " + handlerName);
        }

        // Push handler, execute body, pop handler
        handlerStack.push(handler);
        try {
            if (ctx.fnBody() != null) {
                return evalFnBodyCtx(ctx.fnBody());
            } else if (ctx.expr().size() > 1) {
                // inline form: with handler expr
                return evalExpr(ctx.expr(1));
            }
            return UNIT;
        } finally {
            handlerStack.pop();
        }
    }

    // ── Function application ────────────────────────────────────────────

    private Object evalAppExpr(IrijParser.AppExprContext ctx) {
        var atoms = ctx.atomExpr();
        if (atoms.size() < 2) {
            // Single atom, possibly with type annotation
            return evalAtomExpr(atoms.get(0));
        }

        // f x y z — first atom is the function, rest are arguments
        var fnAtom = atoms.get(0);
        String fnName = extractAtomIdentifier(fnAtom);

        // Evaluate arguments
        var args = new ArrayList<Object>();
        for (int i = 1; i < atoms.size(); i++) {
            args.add(evalAtomExpr(atoms.get(i)));
        }

        return callFunction(fnName, args, fnAtom);
    }

    private Object callFunction(String name, List<Object> args, ParseTree callSite) {
        if (name == null) {
            throw new IrijRuntimeError("Cannot resolve function at: " +
                    (callSite != null ? callSite.getText() : "?"));
        }

        // 1. Check if it's a user-defined function
        var fn = functions.get(name);
        if (fn != null) {
            return callUserFunction(fn, args);
        }

        // 2. Check if it's an effect operation
        var handlerClause = resolveEffectOp(name);
        if (handlerClause != null) {
            return callEffectOp(name, handlerClause, args);
        }

        // 3. Check if it's a variable that holds a lambda
        Object value = lookupVariable(name);
        if (value instanceof LambdaValue lambda) {
            return callLambda(lambda, args);
        }

        throw new IrijRuntimeError("Undefined function: " + name);
    }

    private Object callUserFunction(IrijParser.FnDeclContext fn, List<Object> args) {
        pushScope();
        try {
            // For now, functions don't have named params in the declaration body
            // (param names come from pattern matching in the body)
            // hello.irj: fn hello takes () — no actual params to bind
            return evalFnBody(fn);
        } finally {
            popScope();
        }
    }

    private HandlerClause resolveEffectOp(String opName) {
        // Walk the handler stack top-down to find who handles this operation
        for (var handler : handlerStack) {
            var clause = handler.operations().get(opName);
            if (clause != null) {
                return clause;
            }
        }
        return null;
    }

    private Object callEffectOp(String opName, HandlerClause clause, List<Object> args) {
        pushScope();
        try {
            // Bind parameters
            for (int i = 0; i < clause.params().size() && i < args.size(); i++) {
                currentScope().put(clause.params().get(i), args.get(i));
            }
            return evalExpr(clause.body());
        } finally {
            popScope();
        }
    }

    // ── Postfix (dot access) ────────────────────────────────────────────

    private Object evalPostfixExpr(IrijParser.PostfixExprContext ctx) {
        if (ctx.DOT().isEmpty()) {
            // No dot access, just the appExpr
            return evalExpr(ctx.appExpr());
        }

        // Build the dotted path to detect builtins like io.stdout.write
        String fullPath = ctx.getText();

        // Check for known builtins
        if (fullPath.startsWith("io.stdout.write")) {
            // io.stdout.write — extract args
            var appArgs = ctx.appArgs();
            if (!appArgs.isEmpty()) {
                var lastArgs = appArgs.get(appArgs.size() - 1);
                for (var arg : lastArgs.atomExpr()) {
                    Object val = evalAtomExpr(arg);
                    System.out.println(val);
                }
            }
            return UNIT;
        }

        if (fullPath.startsWith("io.stdin.read-line")) {
            // io.stdin.read-line — read from stdin
            var scanner = new Scanner(System.in);
            return scanner.hasNextLine() ? scanner.nextLine() : "";
        }

        // General postfix: evaluate base and chain dot accesses
        Object base = evalExpr(ctx.appExpr());

        // For now, dot access on runtime values is not supported
        // (would need records/maps)
        return base;
    }

    // ── Atom evaluation ─────────────────────────────────────────────────

    private Object evalAtomExpr(IrijParser.AtomExprContext ctx) {
        if (ctx.literal() != null) {
            return evalLiteral(ctx.literal());
        }
        if (ctx.LOWER_ID() != null) {
            String name = ctx.LOWER_ID().getText();
            // Check local scope first
            Object val = lookupVariable(name);
            if (val != null) return val;
            // Could be a function reference — return as-is for now
            if (functions.containsKey(name)) {
                return new IrijFunction(name, functions.get(name));
            }
            throw new IrijRuntimeError("Undefined variable: " + name);
        }
        if (ctx.UPPER_ID() != null) {
            // Constructor or type reference
            return ctx.UPPER_ID().getText();
        }
        if (ctx.LPAREN() != null && ctx.RPAREN() != null && ctx.expr() == null) {
            // Unit value: ()
            return UNIT;
        }
        if (ctx.expr() != null) {
            // Grouped: (expr)
            return evalExpr(ctx.expr());
        }
        if (ctx.vectorLiteral() != null) {
            return evalVectorLiteral(ctx.vectorLiteral());
        }
        if (ctx.mapLiteral() != null) {
            return evalMapLiteral(ctx.mapLiteral());
        }
        return UNIT;
    }

    // ── Literals ────────────────────────────────────────────────────────

    private Object evalLiteral(IrijParser.LiteralContext ctx) {
        if (ctx.INT_LIT() != null) {
            return Long.parseLong(ctx.INT_LIT().getText().replace("_", ""));
        }
        if (ctx.FLOAT_LIT() != null) {
            return Double.parseDouble(ctx.FLOAT_LIT().getText().replace("_", ""));
        }
        if (ctx.STRING_LIT() != null) {
            return evalString(ctx.STRING_LIT().getText());
        }
        if (ctx.KEYWORD_LIT() != null) {
            return ctx.KEYWORD_LIT().getText(); // :ok, :error
        }
        if (ctx.HEX_LIT() != null) {
            return Long.decode(ctx.HEX_LIT().getText());
        }
        return UNIT;
    }

    /**
     * Evaluate a string literal, handling escape sequences and interpolation.
     */
    private String evalString(String raw) {
        // Strip quotes
        String s = raw.substring(1, raw.length() - 1);
        var sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '\\' -> sb.append('\\');
                    case '"' -> sb.append('"');
                    case '$' -> sb.append('$');
                    default -> { sb.append('\\'); sb.append(next); }
                }
                i += 2;
            } else if (c == '$' && i + 1 < s.length() && s.charAt(i + 1) == '{') {
                // String interpolation: ${expr}
                int close = s.indexOf('}', i + 2);
                if (close >= 0) {
                    String varName = s.substring(i + 2, close).trim();
                    Object val = lookupVariable(varName);
                    sb.append(val != null ? val.toString() : "${" + varName + "}");
                    i = close + 1;
                } else {
                    sb.append(c);
                    i++;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    // ── Operator expressions (pass-through for single-child cases) ──────

    private Object evalPipeExpr(IrijParser.PipeExprContext ctx) {
        if (ctx.composeExpr().size() == 1) {
            return evalExpr(ctx.composeExpr(0));
        }
        // Pipeline: x |> f |> g
        Object val = evalExpr(ctx.composeExpr(0));
        for (int i = 1; i < ctx.composeExpr().size(); i++) {
            Object fn = evalExpr(ctx.composeExpr(i));
            if (fn instanceof IrijFunction ifn) {
                val = callUserFunction(ifn.decl(), List.of(val));
            } else {
                val = fn; // fallback
            }
        }
        return val;
    }

    private Object evalComposeExpr(IrijParser.ComposeExprContext ctx) {
        if (ctx.choreographyExpr().size() == 1) {
            return evalExpr(ctx.choreographyExpr(0));
        }
        return evalExpr(ctx.choreographyExpr(0));
    }

    private Object evalOrExpr(IrijParser.OrExprContext ctx) {
        if (ctx.andExpr().size() == 1) return evalExpr(ctx.andExpr(0));
        Object left = evalExpr(ctx.andExpr(0));
        for (int i = 1; i < ctx.andExpr().size(); i++) {
            if (isTruthy(left)) return left;
            left = evalExpr(ctx.andExpr(i));
        }
        return left;
    }

    private Object evalAndExpr(IrijParser.AndExprContext ctx) {
        if (ctx.eqExpr().size() == 1) return evalExpr(ctx.eqExpr(0));
        Object left = evalExpr(ctx.eqExpr(0));
        for (int i = 1; i < ctx.eqExpr().size(); i++) {
            if (!isTruthy(left)) return left;
            left = evalExpr(ctx.eqExpr(i));
        }
        return left;
    }

    private Object evalEqExpr(IrijParser.EqExprContext ctx) {
        if (ctx.compExpr().size() == 1) return evalExpr(ctx.compExpr(0));
        Object left = evalExpr(ctx.compExpr(0));
        Object right = evalExpr(ctx.compExpr(1));
        if (ctx.EQ() != null) return Objects.equals(left, right);
        if (ctx.NEQ() != null) return !Objects.equals(left, right);
        return left;
    }

    private Object evalCompExpr(IrijParser.CompExprContext ctx) {
        if (ctx.concatExpr().size() == 1) return evalExpr(ctx.concatExpr(0));
        Object left = evalExpr(ctx.concatExpr(0));
        Object right = evalExpr(ctx.concatExpr(1));
        if (left instanceof Long l && right instanceof Long r) {
            if (ctx.LT() != null) return l < r;
            if (ctx.GT() != null) return l > r;
            if (ctx.LTE() != null) return l <= r;
            if (ctx.GTE() != null) return l >= r;
        }
        return left;
    }

    private Object evalConcatExpr(IrijParser.ConcatExprContext ctx) {
        if (ctx.rangeExpr().size() == 1) return evalExpr(ctx.rangeExpr(0));
        var sb = new StringBuilder();
        for (var re : ctx.rangeExpr()) {
            sb.append(evalExpr(re));
        }
        return sb.toString();
    }

    private Object evalAddExpr(IrijParser.AddExprContext ctx) {
        if (ctx.mulExpr().size() == 1) return evalExpr(ctx.mulExpr(0));
        Object result = evalExpr(ctx.mulExpr(0));
        // Operators are interleaved: PLUS/MINUS tokens between mulExpr children
        for (int i = 1; i < ctx.mulExpr().size(); i++) {
            Object right = evalExpr(ctx.mulExpr(i));
            var op = ctx.getChild(2 * i - 1); // operator token
            if (result instanceof Long l && right instanceof Long r) {
                result = "+".equals(op.getText()) ? l + r : l - r;
            } else if (result instanceof Double || right instanceof Double) {
                double l = ((Number) result).doubleValue();
                double r = ((Number) right).doubleValue();
                result = "+".equals(op.getText()) ? l + r : l - r;
            } else if ("+".equals(op.getText())) {
                result = result.toString() + right.toString();
            }
        }
        return result;
    }

    private Object evalMulExpr(IrijParser.MulExprContext ctx) {
        if (ctx.powExpr().size() == 1) return evalExpr(ctx.powExpr(0));
        Object result = evalExpr(ctx.powExpr(0));
        for (int i = 1; i < ctx.powExpr().size(); i++) {
            Object right = evalExpr(ctx.powExpr(i));
            var op = ctx.getChild(2 * i - 1);
            if (result instanceof Long l && right instanceof Long r) {
                String opText = op.getText();
                if ("*".equals(opText)) {
                    result = l * r;
                } else if ("/".equals(opText)) {
                    if (r == 0) throw new IrijRuntimeError("Division by zero");
                    result = l / r;
                } else if ("%".equals(opText)) {
                    if (r == 0) throw new IrijRuntimeError("Division by zero");
                    result = l % r;
                }
            }
        }
        return result;
    }

    private Object evalUnaryExpr(IrijParser.UnaryExprContext ctx) {
        if (ctx.seqOpExpr() != null) return evalSeqOpExpr(ctx.seqOpExpr());
        if (ctx.NOT() != null) return !isTruthy(evalExpr(ctx.unaryExpr()));
        if (ctx.MINUS() != null) {
            Object val = evalExpr(ctx.unaryExpr());
            if (val instanceof Long l) return -l;
            if (val instanceof Double d) return -d;
        }
        return UNIT;
    }

    private Object evalSeqOpExpr(IrijParser.SeqOpExprContext ctx) {
        if (ctx.postfixExpr() != null) return evalPostfixExpr(ctx.postfixExpr());
        return UNIT;
    }

    // ── Control flow ────────────────────────────────────────────────────

    private Object evalIfExpr(IrijParser.IfExprContext ctx) {
        Object cond = evalExpr(ctx.expr(0));
        if (isTruthy(cond)) {
            if (ctx.fnBody().size() > 0) {
                return evalFnBodyCtx(ctx.fnBody(0));
            }
            return evalExpr(ctx.expr(1));
        } else {
            if (ctx.fnBody().size() > 1) {
                return evalFnBodyCtx(ctx.fnBody(1));
            }
            return evalExpr(ctx.expr(2));
        }
    }

    private Object evalMatchExpr(IrijParser.MatchExprContext ctx) {
        Object scrutinee = evalExpr(ctx.expr());
        for (var arm : ctx.matchArms().matchArm()) {
            var pattern = arm.pattern();
            var bindings = new LinkedHashMap<String, Object>();
            if (matchPattern(pattern, scrutinee, bindings)) {
                // Check guard if present
                if (arm.guard() != null) {
                    pushScope();
                    currentScope().putAll(bindings);
                    Object guardVal = evalExpr(arm.guard().expr());
                    popScope();
                    if (!isTruthy(guardVal)) continue;
                }
                pushScope();
                currentScope().putAll(bindings);
                try {
                    if (arm.fnBody() != null) {
                        return evalFnBodyCtx(arm.fnBody());
                    }
                    return evalExpr(arm.expr());
                } finally {
                    popScope();
                }
            }
        }
        throw new IrijRuntimeError("Non-exhaustive match on: " + scrutinee);
    }

    private Object evalDoExpr(IrijParser.DoExprContext ctx) {
        Object result = UNIT;
        for (var stmt : ctx.statement()) {
            result = evalStatement(stmt);
        }
        return result;
    }

    private Object evalScopeExpr(IrijParser.ScopeExprContext ctx) {
        // For now, just execute the body
        if (ctx.fnBody() != null) {
            return evalFnBodyCtx(ctx.fnBody());
        }
        return UNIT;
    }

    private Object evalLambdaOrReturn(IrijParser.LambdaExprContext ctx) {
        // If no params, it's a thunk (-> expr) — evaluate immediately
        if (ctx.lambdaParams() == null) {
            return evalExpr(ctx.expr());
        }
        // Otherwise, return a lambda value
        var paramNames = new ArrayList<String>();
        for (var pat : ctx.lambdaParams().pattern()) {
            paramNames.add(pat.getText());
        }
        // Capture current scope
        var captured = new LinkedHashMap<String, Object>();
        for (var scope : scopeStack) {
            captured.putAll(scope);
        }
        return new LambdaValue(paramNames, ctx.expr(), captured);
    }

    // ── Lambda support ──────────────────────────────────────────────────

    record LambdaValue(List<String> params, IrijParser.ExprContext body,
                       Map<String, Object> captured) {}

    private Object callLambda(LambdaValue lambda, List<Object> args) {
        pushScope();
        currentScope().putAll(lambda.captured());
        for (int i = 0; i < lambda.params().size() && i < args.size(); i++) {
            currentScope().put(lambda.params().get(i), args.get(i));
        }
        try {
            return evalExpr(lambda.body());
        } finally {
            popScope();
        }
    }

    // ── Pattern matching ────────────────────────────────────────────────

    private boolean matchPattern(IrijParser.PatternContext pat, Object value,
                                  Map<String, Object> bindings) {
        if (pat.UNDERSCORE() != null) return true;
        if (pat.LOWER_ID() != null) {
            bindings.put(pat.LOWER_ID().getText(), value);
            return true;
        }
        if (pat.literal() != null) {
            Object litVal = evalLiteral(pat.literal());
            return Objects.equals(litVal, value);
        }
        if (pat.UPPER_ID() != null) {
            // Constructor pattern
            String ctorName = pat.UPPER_ID().getText();
            return ctorName.equals(value);
        }
        if (pat.LPAREN() != null && pat.RPAREN() != null && pat.pattern().isEmpty()) {
            return value == UNIT;
        }
        return false;
    }

    // ── Collection literals ─────────────────────────────────────────────

    private Object evalVectorLiteral(IrijParser.VectorLiteralContext ctx) {
        if (ctx.exprList() == null) return List.of();
        var items = new ArrayList<Object>();
        for (var e : ctx.exprList().expr()) {
            items.add(evalExpr(e));
        }
        return items;
    }

    private Object evalMapLiteral(IrijParser.MapLiteralContext ctx) {
        if (ctx.mapEntryList() == null) return Map.of();
        var map = new LinkedHashMap<String, Object>();
        for (var entry : ctx.mapEntryList().mapEntry()) {
            String key = entry.LOWER_ID().getText();
            Object val = evalExpr(entry.expr());
            map.put(key, val);
        }
        return map;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private String extractIdentifier(ParseTree node) {
        if (node == null) return null;
        String text = node.getText();
        // Walk down through wrapper rules to find the actual identifier
        if (node.getChildCount() == 1) {
            return extractIdentifier(node.getChild(0));
        }
        // Terminal node
        if (node.getChildCount() == 0) {
            return text;
        }
        return text;
    }

    private String extractAtomIdentifier(IrijParser.AtomExprContext atom) {
        if (atom.LOWER_ID() != null) return atom.LOWER_ID().getText();
        if (atom.UPPER_ID() != null) return atom.UPPER_ID().getText();
        return null;
    }

    private boolean isTruthy(Object val) {
        if (val == null || val == UNIT) return false;
        if (val instanceof Boolean b) return b;
        if (val instanceof Long l) return l != 0;
        if (val instanceof String s) return !s.isEmpty();
        return true;
    }

    private Object lookupVariable(String name) {
        for (var scope : scopeStack) {
            if (scope.containsKey(name)) {
                return scope.get(name);
            }
        }
        return null;
    }

    private void pushScope() {
        scopeStack.push(new LinkedHashMap<>());
    }

    private void popScope() {
        scopeStack.pop();
    }

    private Map<String, Object> currentScope() {
        return scopeStack.peek();
    }
}
