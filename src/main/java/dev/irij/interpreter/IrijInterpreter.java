package dev.irij.interpreter;

import dev.irij.parser.IrijLexer;
import dev.irij.parser.IrijParser;
import dev.irij.parser.IrijParserBaseVisitor;
import org.antlr.v4.runtime.*;
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

    /** Built-in functions from the standard library */
    private final Map<String, Builtin> builtins = new LinkedHashMap<>();

    {
        Stdlib.register(builtins);
    }

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
        } else if (decl.typeDecl() != null) {
            collectTypeDecl(decl.typeDecl());
        } else if (decl.newtypeDecl() != null) {
            // newtypes are transparent wrappers — no constructor needed
        }
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

    private void collectTypeDecl(IrijParser.TypeDeclContext ctx) {
        if (ctx.typeBody() == null) return; // type alias — no constructors

        var body = ctx.typeBody();

        // Sum type: variants like Ok a | Err b
        if (!body.typeVariant().isEmpty()) {
            for (var variant : body.typeVariant()) {
                String ctorName = variant.UPPER_ID().getText();
                int arity = variant.typeExpr().size();
                if (arity == 0) {
                    // Nullary constructor: None, True, etc.
                    builtins.put(ctorName, args -> new Tagged(ctorName));
                } else {
                    builtins.put(ctorName, args -> {
                        if (args.size() == 1) return new Tagged(ctorName, args.get(0));
                        return new Tagged(ctorName, new ArrayList<>(args));
                    });
                }
            }
        }

        // Record type: fields like name :: Str, age :: Int
        if (!body.typeField().isEmpty()) {
            String typeName = ctx.typeName().getText();
            var fieldNames = new ArrayList<String>();
            for (var field : body.typeField()) {
                fieldNames.add(field.anyId().getText());
            }
            // Register constructor that creates a map from positional args
            builtins.put(typeName, args -> {
                var map = new LinkedHashMap<String, Object>();
                for (int i = 0; i < fieldNames.size() && i < args.size(); i++) {
                    map.put(fieldNames.get(i), args.get(i));
                }
                return map;
            });
        }
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
            case IrijParser.RangeExprContext ctx -> evalRangeExpr(ctx);
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
            return evalAtomExpr(atoms.get(0));
        }

        var fnAtom = atoms.get(0);
        String fnName = extractAtomIdentifier(fnAtom);

        // If first atom is a literal, this is space-separated values inside
        // a vector/set literal (e.g. #[1 2 3] parses 1 2 3 as appExpr).
        // Return just the first value.
        if (fnAtom.literal() != null) {
            return evalAtomExpr(fnAtom);
        }

        // If first atom is `recur` keyword (parsed as RECUR atomExpr* with 0 atomExprs),
        // treat remaining atoms from appExpr as the recur arguments
        if (fnAtom.RECUR() != null && fnAtom.lambdaExpr() == null) {
            var args = new ArrayList<Object>();
            for (int i = 1; i < atoms.size(); i++) {
                args.add(evalAtomExpr(atoms.get(i)));
            }
            throw new RecurSignal(args);
        }

        // f x y z — first atom is the function, rest are arguments
        var args = new ArrayList<Object>();
        for (int i = 1; i < atoms.size(); i++) {
            args.add(evalAtomExpr(atoms.get(i)));
        }

        return callFunction(fnName, args, fnAtom);
    }

    private Object callFunction(String name, List<Object> args, ParseTree callSite) {
        if (name == null) {
            // Try evaluating callSite as an expression to get a lambda
            if (callSite instanceof IrijParser.AtomExprContext atom) {
                Object val = evalAtomExpr(atom);
                if (val instanceof LambdaValue lambda) {
                    return callLambda(lambda, args);
                }
            }
            throw new IrijRuntimeError("Cannot resolve function at: " +
                    (callSite != null ? callSite.getText() : "?"));
        }

        // 0. Check for recur — throws signal to unwind to loop head
        if ("recur".equals(name)) {
            throw new RecurSignal(args);
        }

        // 1. Check local scope for lambda/function value
        Object value = lookupVariable(name);
        if (value instanceof LambdaValue lambda) {
            return callLambda(lambda, args);
        }
        if (value instanceof Builtin b) {
            return b.call(args);
        }

        // 2. Check if it's a user-defined function
        var fn = functions.get(name);
        if (fn != null) {
            return callUserFunction(fn, args);
        }

        // 3. Check if it's an effect operation
        var handlerClause = resolveEffectOp(name);
        if (handlerClause != null) {
            return callEffectOp(name, handlerClause, args);
        }

        // 4. Check built-in functions (std library)
        var builtin = builtins.get(name);
        if (builtin != null) {
            return builtin.call(args);
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

    @SuppressWarnings("unchecked")
    private Object evalPostfixExpr(IrijParser.PostfixExprContext ctx) {
        if (ctx.DOT().isEmpty()) {
            return evalExpr(ctx.appExpr());
        }

        // Build the dotted path to detect IO builtins
        String fullPath = ctx.getText();

        // IO builtins — special-cased because "io" is not a real value
        if (fullPath.startsWith("io.stdout.write")) {
            var appArgs = ctx.appArgs();
            if (!appArgs.isEmpty()) {
                var lastArgs = appArgs.get(appArgs.size() - 1);
                for (var arg : lastArgs.atomExpr()) {
                    System.out.println(Stdlib.show(evalAtomExpr(arg)));
                }
            }
            return UNIT;
        }
        if (fullPath.startsWith("io.stderr.write")) {
            var appArgs = ctx.appArgs();
            if (!appArgs.isEmpty()) {
                var lastArgs = appArgs.get(appArgs.size() - 1);
                for (var arg : lastArgs.atomExpr()) {
                    System.err.println(Stdlib.show(evalAtomExpr(arg)));
                }
            }
            return UNIT;
        }
        if (fullPath.startsWith("io.stdin.read-line")) {
            var scanner = new Scanner(System.in);
            return scanner.hasNextLine() ? scanner.nextLine() : "";
        }

        // General postfix: evaluate base, then chain dot accesses
        // postfixExpr: appExpr (DOT (LOWER_ID | UPPER_ID) appArgs?)*
        Object base = evalExpr(ctx.appExpr());

        // Walk the dot chain using children list
        // Pattern: appExpr DOT ID [appArgs] DOT ID [appArgs] ...
        int dotIdx = 0;
        for (var dotNode : ctx.DOT()) {
            int childIndex = ctx.children.indexOf(dotNode);
            if (childIndex + 1 >= ctx.children.size()) break;

            String field = ctx.children.get(childIndex + 1).getText();

            // Collect method call args if present
            List<Object> dotArgs = null;
            if (dotIdx < ctx.appArgs().size()) {
                // Check if this DOT has appArgs following the field name
                var appArgsCtx = ctx.appArgs().get(dotIdx);
                int appArgsIndex = ctx.children.indexOf(appArgsCtx);
                if (appArgsIndex == childIndex + 2) {
                    dotArgs = new ArrayList<>();
                    for (var atom : appArgsCtx.atomExpr()) {
                        dotArgs.add(evalAtomExpr(atom));
                    }
                    dotIdx++;
                }
            }

            // Resolve the field on the current base value
            base = resolveDotAccess(base, field, dotArgs);
        }

        return base;
    }

    /**
     * Resolve a dot access (field lookup or method call) on a value.
     */
    @SuppressWarnings("unchecked")
    private Object resolveDotAccess(Object base, String field, List<Object> args) {
        // Map field access: record.field
        if (base instanceof Map<?, ?> map) {
            Object val = ((Map<String, Object>) map).get(field);
            if (val != null) {
                // If args were provided and val is callable, apply it
                if (args != null && !args.isEmpty()) {
                    return applyValue(val, args);
                }
                return val;
            }
        }

        // Tagged value with single map field: look through to the map
        if (base instanceof Tagged t && t.fields().size() == 1 && t.field(0) instanceof Map<?, ?> innerMap) {
            Object val = ((Map<String, Object>) innerMap).get(field);
            if (val != null) {
                if (args != null && !args.isEmpty()) {
                    return applyValue(val, args);
                }
                return val;
            }
        }

        // Tagged field access by index
        if (base instanceof Tagged t) {
            // Could add named field access for records in the future
            return UNIT;
        }

        return UNIT;
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
            // Boolean constants
            if ("true".equals(name)) return true;
            if ("false".equals(name)) return false;
            // Could be a function reference
            if (functions.containsKey(name)) {
                return new IrijFunction(name, functions.get(name));
            }
            // Could be a builtin (for use as value, e.g. in pipelines)
            var builtin = builtins.get(name);
            if (builtin != null) return builtin;
            throw new IrijRuntimeError("Undefined variable: " + name);
        }
        if (ctx.UPPER_ID() != null) {
            String name = ctx.UPPER_ID().getText();
            // Check if it's a constructor builtin (None has no args)
            var builtin = builtins.get(name);
            if (builtin != null) return builtin.call(List.of());
            // Otherwise a bare constructor tag or type reference
            return name;
        }
        // RECUR and LOOP must be checked before grouped expr (ctx.expr() != null)
        // because RECUR expr lambdaExpr also has an expr child
        if (ctx.LOOP() != null && ctx.lambdaExpr() != null) {
            return evalLoopExpr(ctx.lambdaExpr());
        }
        if (ctx.RECUR() != null) {
            if (ctx.lambdaExpr() != null) {
                // recur init (acc -> body) — loop setup form
                return evalRecurExpr(ctx.expr(), ctx.lambdaExpr());
            } else {
                // recur val — signal restart inside loop body
                var args = new ArrayList<Object>();
                for (var atom : ctx.atomExpr()) {
                    args.add(evalAtomExpr(atom));
                }
                throw new RecurSignal(args);
            }
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
        if (ctx.setLiteral() != null) {
            return evalSetLiteral(ctx.setLiteral());
        }
        if (ctx.tupleLiteral() != null) {
            return evalTupleLiteral(ctx.tupleLiteral());
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
                // Find matching close brace, handling nested braces
                int depth = 1;
                int j = i + 2;
                while (j < s.length() && depth > 0) {
                    if (s.charAt(j) == '{') depth++;
                    else if (s.charAt(j) == '}') depth--;
                    if (depth > 0) j++;
                }
                if (depth == 0) {
                    String exprStr = s.substring(i + 2, j).trim();
                    Object val = evalInterpolationExpr(exprStr);
                    sb.append(Stdlib.show(val));
                    i = j + 1;
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

    /**
     * Parse and evaluate an expression string for string interpolation.
     * Falls back to variable lookup for simple identifiers.
     */
    private Object evalInterpolationExpr(String exprStr) {
        // Fast path: simple variable name
        if (exprStr.matches("[a-z][a-z0-9-]*[!?]?")) {
            Object val = lookupVariable(exprStr);
            return val != null ? val : UNIT;
        }
        // Parse as an Irij expression
        try {
            var lexer = new IrijLexer(CharStreams.fromString(exprStr));
            lexer.removeErrorListeners();
            var tokens = new CommonTokenStream(lexer);
            var parser = new IrijParser(tokens);
            parser.removeErrorListeners();
            var expr = parser.expr();
            if (parser.getNumberOfSyntaxErrors() > 0) {
                // If parsing fails, try as variable lookup
                Object val = lookupVariable(exprStr);
                return val != null ? val : exprStr;
            }
            return evalExpr(expr);
        } catch (Exception e) {
            Object val = lookupVariable(exprStr);
            return val != null ? val : exprStr;
        }
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
            val = applyValue(fn, List.of(val));
        }
        return val;
    }

    private Object evalComposeExpr(IrijParser.ComposeExprContext ctx) {
        if (ctx.choreographyExpr().size() == 1) {
            return evalExpr(ctx.choreographyExpr(0));
        }
        // f >> g >> h — compose left-to-right: (x -> x |> f |> g |> h)
        // f << g << h — compose right-to-left: (x -> x |> h |> g |> f)
        var fns = new ArrayList<Object>();
        for (var choreo : ctx.choreographyExpr()) {
            fns.add(evalExpr(choreo));
        }
        boolean isForward = ctx.COMPOSE().size() > 0; // >> vs <<
        if (!isForward) {
            Collections.reverse(fns);
        }
        // Return a Builtin that applies each function in sequence
        return (Builtin) args -> {
            Object val = Stdlib.arg(args, 0);
            for (var fn : fns) {
                val = applyValue(fn, List.of(val));
            }
            return val;
        };
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

    private Object evalRangeExpr(IrijParser.RangeExprContext ctx) {
        if (ctx.addExpr().size() == 1) return evalExpr(ctx.addExpr(0));
        long start = Stdlib.toLong(evalExpr(ctx.addExpr(0)));
        long end = Stdlib.toLong(evalExpr(ctx.addExpr(1)));
        var result = new ArrayList<Object>();
        if (ctx.RANGE() != null) {
            // inclusive: 1..5 → [1, 2, 3, 4, 5]
            for (long i = start; i <= end; i++) result.add(i);
        } else {
            // exclusive: 1..<5 → [1, 2, 3, 4]
            for (long i = start; i < end; i++) result.add(i);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object evalConcatExpr(IrijParser.ConcatExprContext ctx) {
        if (ctx.rangeExpr().size() == 1) return evalExpr(ctx.rangeExpr(0));
        Object result = evalExpr(ctx.rangeExpr(0));
        for (int i = 1; i < ctx.rangeExpr().size(); i++) {
            Object right = evalExpr(ctx.rangeExpr(i));
            if (result instanceof List<?> l && right instanceof List<?> r) {
                var combined = new ArrayList<>((List<Object>) l);
                combined.addAll((List<Object>) r);
                result = combined;
            } else {
                result = Stdlib.show(result) + Stdlib.show(right);
            }
        }
        return result;
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

    @SuppressWarnings("unchecked")
    private Object evalSeqOpExpr(IrijParser.SeqOpExprContext ctx) {
        if (ctx.postfixExpr() != null) return evalPostfixExpr(ctx.postfixExpr());

        // J-inspired sequence operators — these are used in pipelines: vec |> /+
        // When used standalone, they return a lambda that operates on a vector
        if (ctx.REDUCE_PLUS() != null) return (Builtin) args -> Stdlib.evalSeqOp("/+", Stdlib.arg(args, 0));
        if (ctx.REDUCE_STAR() != null) return (Builtin) args -> Stdlib.evalSeqOp("/*", Stdlib.arg(args, 0));
        if (ctx.COUNT() != null)       return (Builtin) args -> Stdlib.evalSeqOp("/#", Stdlib.arg(args, 0));
        if (ctx.REDUCE_AND() != null)  return (Builtin) args -> Stdlib.evalSeqOp("/&", Stdlib.arg(args, 0));
        if (ctx.REDUCE_OR() != null)   return (Builtin) args -> Stdlib.evalSeqOp("/|", Stdlib.arg(args, 0));

        // @ f — map with function f
        if (ctx.AT() != null && ctx.postfixExpr() != null) {
            Object fn = evalPostfixExpr(ctx.postfixExpr());
            return (Builtin) args -> {
                var list = Stdlib.toList(Stdlib.arg(args, 0));
                var result = new ArrayList<Object>();
                for (var item : list) {
                    result.add(applyValue(fn, List.of(item)));
                }
                return result;
            };
        }

        // ? pred — filter with predicate
        if (ctx.FILTER() != null && ctx.postfixExpr() != null) {
            Object fn = evalPostfixExpr(ctx.postfixExpr());
            return (Builtin) args -> {
                var list = Stdlib.toList(Stdlib.arg(args, 0));
                var result = new ArrayList<Object>();
                for (var item : list) {
                    if (Stdlib.isTruthy(applyValue(fn, List.of(item)))) {
                        result.add(item);
                    }
                }
                return result;
            };
        }

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

    /** Sentinel exception thrown by `recur` to restart the loop body. */
    static final class RecurSignal extends RuntimeException {
        final List<Object> args;
        RecurSignal(List<Object> args) {
            super(null, null, true, false); // no stack trace for performance
            this.args = args;
        }
    }

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
        if (pat.LOWER_ID() != null && pat.UPPER_ID() == null) {
            bindings.put(pat.LOWER_ID().getText(), value);
            return true;
        }
        if (pat.literal() != null) {
            Object litVal = evalLiteral(pat.literal());
            return Objects.equals(litVal, value);
        }
        if (pat.UPPER_ID() != null) {
            // Constructor pattern: Ok value, Some x, None, Err msg
            String ctorName = pat.UPPER_ID().getText();
            var subPatterns = pat.pattern();

            if (value instanceof Tagged t) {
                if (!t.tag().equals(ctorName)) return false;
                // Match sub-patterns against fields
                for (int i = 0; i < subPatterns.size(); i++) {
                    Object field = t.field(i);
                    if (!matchPattern(subPatterns.get(i), field, bindings)) return false;
                }
                return true;
            }
            // Bare string tag (legacy)
            if (subPatterns.isEmpty()) {
                return ctorName.equals(value);
            }
            return false;
        }
        if (pat.LPAREN() != null && pat.RPAREN() != null && pat.pattern().isEmpty()) {
            return value == UNIT;
        }
        // Vector pattern: #[x y ...rest]
        if (pat.vectorPattern() != null) {
            if (!(value instanceof List<?> list)) return false;
            var vecPat = pat.vectorPattern().patternListWithSpread();
            if (vecPat == null) return list.isEmpty();
            var subPats = vecPat.pattern();
            if (list.size() < subPats.size()) return false;
            for (int i = 0; i < subPats.size(); i++) {
                if (!matchPattern(subPats.get(i), list.get(i), bindings)) return false;
            }
            // Spread: ...rest
            if (vecPat.SPREAD() != null && vecPat.LOWER_ID() != null) {
                String restName = vecPat.LOWER_ID().getText();
                bindings.put(restName, new ArrayList<>(list.subList(subPats.size(), list.size())));
            }
            return true;
        }
        // Destructure pattern: {name: n}
        if (pat.destructurePattern() != null) {
            if (!(value instanceof Map<?, ?> map)) return false;
            for (var field : pat.destructurePattern().destructureField()) {
                String key = field.LOWER_ID().getText();
                Object val = map.get(key);
                if (val == null) val = UNIT;
                if (!matchPattern(field.pattern(), val, bindings)) return false;
            }
            return true;
        }
        // Grouped: (pattern)
        if (pat.LPAREN() != null && !pat.pattern().isEmpty()) {
            return matchPattern(pat.pattern().get(0), value, bindings);
        }
        return false;
    }

    // ── Collection literals ─────────────────────────────────────────────

    private Object evalVectorLiteral(IrijParser.VectorLiteralContext ctx) {
        if (ctx.exprList() == null) return new ArrayList<>();
        var items = new ArrayList<Object>();
        for (var e : ctx.exprList().expr()) {
            // Space-separated values like #[1 2 3] get parsed as appExpr(1, 2, 3).
            // We need to unpack them into individual elements.
            collectVectorElements(e, items);
        }
        return items;
    }

    /**
     * Recursively extract vector elements from an expression.
     * Handles the case where space-separated values parse as function application.
     */
    private void collectVectorElements(ParseTree node, List<Object> out) {
        // Walk down the single-child chain to get to the appExpr
        if (node instanceof IrijParser.ExprContext ctx && ctx.pipeExpr() != null) {
            collectVectorElements(ctx.pipeExpr(), out);
            return;
        }
        if (node instanceof IrijParser.PipeExprContext ctx && ctx.composeExpr().size() == 1) {
            collectVectorElements(ctx.composeExpr(0), out);
            return;
        }
        if (node instanceof IrijParser.ComposeExprContext ctx && ctx.choreographyExpr().size() == 1) {
            collectVectorElements(ctx.choreographyExpr(0), out);
            return;
        }
        if (node instanceof IrijParser.ChoreographyExprContext ctx && ctx.orExpr() != null) {
            collectVectorElements(ctx.orExpr(), out);
            return;
        }
        if (node instanceof IrijParser.OrExprContext ctx && ctx.andExpr().size() == 1) {
            collectVectorElements(ctx.andExpr(0), out);
            return;
        }
        if (node instanceof IrijParser.AndExprContext ctx && ctx.eqExpr().size() == 1) {
            collectVectorElements(ctx.eqExpr(0), out);
            return;
        }
        if (node instanceof IrijParser.EqExprContext ctx && ctx.compExpr().size() == 1) {
            collectVectorElements(ctx.compExpr(0), out);
            return;
        }
        if (node instanceof IrijParser.CompExprContext ctx && ctx.concatExpr().size() == 1) {
            collectVectorElements(ctx.concatExpr(0), out);
            return;
        }
        if (node instanceof IrijParser.ConcatExprContext ctx && ctx.rangeExpr().size() == 1) {
            collectVectorElements(ctx.rangeExpr(0), out);
            return;
        }
        if (node instanceof IrijParser.RangeExprContext ctx && ctx.addExpr().size() == 1) {
            collectVectorElements(ctx.addExpr(0), out);
            return;
        }
        if (node instanceof IrijParser.AddExprContext ctx && ctx.mulExpr().size() == 1) {
            collectVectorElements(ctx.mulExpr(0), out);
            return;
        }
        if (node instanceof IrijParser.MulExprContext ctx && ctx.powExpr().size() == 1) {
            collectVectorElements(ctx.powExpr(0), out);
            return;
        }
        if (node instanceof IrijParser.PowExprContext ctx && ctx.unaryExpr().size() == 1) {
            collectVectorElements(ctx.unaryExpr(0), out);
            return;
        }
        if (node instanceof IrijParser.UnaryExprContext ctx && ctx.seqOpExpr() != null) {
            collectVectorElements(ctx.seqOpExpr(), out);
            return;
        }
        if (node instanceof IrijParser.SeqOpExprContext ctx && ctx.postfixExpr() != null) {
            collectVectorElements(ctx.postfixExpr(), out);
            return;
        }
        if (node instanceof IrijParser.PostfixExprContext ctx && ctx.DOT().isEmpty()) {
            collectVectorElements(ctx.appExpr(), out);
            return;
        }

        // appExpr with multiple atoms — these are our space-separated values
        if (node instanceof IrijParser.AppExprContext ctx) {
            var atoms = ctx.atomExpr();
            if (atoms.size() > 1) {
                // Check if first atom looks like a function call or just values
                var firstAtom = atoms.get(0);
                String fnName = extractAtomIdentifier(firstAtom);

                // If first atom is a literal, vector, or boolean identifier, treat ALL atoms as separate values
                boolean isBoolId = "true".equals(fnName) || "false".equals(fnName);
                if (isBoolId || firstAtom.literal() != null || firstAtom.vectorLiteral() != null
                        || firstAtom.setLiteral() != null || firstAtom.tupleLiteral() != null
                        || firstAtom.mapLiteral() != null
                        || (firstAtom.LPAREN() != null && firstAtom.RPAREN() != null && firstAtom.expr() == null)) {
                    for (var atom : atoms) {
                        out.add(evalAtomExpr(atom));
                    }
                    return;
                }
                // If it's a LOWER_ID that's actually a keyword literal (like true/false)
                // or an UPPER_ID constructor... treat as function application
            }
            // Single atom or genuine function application — evaluate normally
            out.add(evalExpr(node));
            return;
        }

        // Default: just evaluate the expression
        out.add(evalExpr(node));
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

    // ── Loop/Recur ────────────────────────────────────────────────────────

    /**
     * loop (-> body) — execute thunk body repeatedly.
     * Body should call `recur` to continue or return a value to stop.
     */
    private Object evalLoopExpr(IrijParser.LambdaExprContext lambdaCtx) {
        // Loop thunk: (-> body) — no params, just repeatedly evaluate
        while (true) {
            try {
                return evalExpr(lambdaCtx.expr());
            } catch (RecurSignal signal) {
                // Continue loop — recur was called with no meaningful binding for loop form
            }
        }
    }

    /**
     * recur init (acc -> body) — tail-recursive loop.
     * Evaluate body with acc bound to init, restart on RecurSignal with new value.
     */
    private Object evalRecurExpr(IrijParser.ExprContext initExpr, IrijParser.LambdaExprContext lambdaCtx) {
        Object acc = evalExpr(initExpr);
        var lambda = (LambdaValue) evalLambdaOrReturn(lambdaCtx);

        while (true) {
            try {
                return callLambda(lambda, List.of(acc));
            } catch (RecurSignal signal) {
                acc = signal.args.isEmpty() ? UNIT : signal.args.get(0);
            }
        }
    }

    // ── Set/Tuple literals ──────────────────────────────────────────────

    private Object evalSetLiteral(IrijParser.SetLiteralContext ctx) {
        if (ctx.exprList() == null) return new LinkedHashSet<>();
        var items = new LinkedHashSet<Object>();
        for (var e : ctx.exprList().expr()) {
            var elems = new ArrayList<Object>();
            collectVectorElements(e, elems);
            items.addAll(elems);
        }
        return items;
    }

    private Object evalTupleLiteral(IrijParser.TupleLiteralContext ctx) {
        if (ctx.exprList() == null) return List.of();
        var items = new ArrayList<Object>();
        for (var e : ctx.exprList().expr()) {
            collectVectorElements(e, items);
        }
        return List.copyOf(items);
    }

    // ── Applying a runtime value as a function ────────────────────────────

    private Object applyValue(Object fn, List<Object> args) {
        if (fn instanceof LambdaValue lambda) {
            return callLambda(lambda, args);
        }
        if (fn instanceof IrijFunction ifn) {
            return callUserFunction(ifn.decl(), args);
        }
        if (fn instanceof Builtin b) {
            return b.call(args);
        }
        // Try as named function
        if (fn instanceof String name) {
            return callFunction(name, args, null);
        }
        throw new IrijRuntimeError("Not a function: " + Stdlib.show(fn));
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
