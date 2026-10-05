package dev.irij.compiler;

import dev.irij.ast.SpecExpr;
import dev.irij.IrijRuntimeError;
import dev.irij.runtime.Values;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bytecode-mode spec validation. Provides the full coverage of
 * {@code Interpreter.validateAgainstSpecExpr} so compiled fns enforce
 * the same contracts as interpreted ones.
 *
 * <p>The emitter calls {@link #encode(SpecExpr)} at compile time to
 * serialise each spec to a compact string; the resulting LDC constant
 * is passed to {@link #validateEncoded} at every input arg / tail
 * return. {@link #decode(String)} caches parses per-string so the hot
 * path is one map lookup + record-walk.
 *
 * <p>Encoding (recursive-descent):
 *
 * <pre>
 *   Wildcard   →  _
 *   Var x      →  ?x
 *   Unit       →  ()
 *   Name N     →  N
 *   App H[a,b] →  H[a,b]
 *   Arrow      →  (a,b->c)
 *   Enum       →  :a|:b|:c
 *   VecSpec    →  Vec[e]       (normalised to App form)
 *   SetSpec    →  Set[e]       (normalised to App form)
 *   TupleSpec  →  Tuple[a,b]   (normalised to App form)
 * </pre>
 *
 * <p>Name characters: {@code [A-Za-z0-9_-]}. The grammar reserves
 * {@code _ ? ( ) [ ] , : | > - } as control; {@code -} is allowed in
 * names but disambiguated by lookahead at {@code ->}.
 */
public final class SpecValidator {

    private SpecValidator() {}

    private static final ConcurrentHashMap<String, SpecExpr> CACHE = new ConcurrentHashMap<>();

    // ── User-declared product/sum spec registry ─────────────────────────
    //
    // Bytecode-mode parity for `spec MyShape { ... }` / sum specs.
    // Populated at class-load time via {@code <clinit>} calls emitted
    // by ClassEmitter, one per Decl.SpecDecl. validateNamed() consults
    // this registry when the name isn't a primitive.

    public sealed interface Descriptor {
        /** @param fields     declared field names, in declaration order
         *  @param fieldSpecs {@link #encode}d spec per field, parallel
         *                    to {@code fields}. An empty string means
         *                    the field's spec was a wildcard or a type
         *                    variable — present but unconstrained. */
        record Product(List<String> fields, List<String> fieldSpecs) implements Descriptor {}
        record Sum(java.util.LinkedHashMap<String, Integer> variants) implements Descriptor {}
    }

    /** Declared product/sum specs of one program: name → descriptor. */
    public static final class Registry {
        final ConcurrentHashMap<String, Descriptor> specs = new ConcurrentHashMap<>();
    }

    /** One registry per classloader. Every program run, and every
     *  session (whose evals share one loader), has its own loader, so its
     *  `spec` declarations are its own: a process-wide map let two
     *  Playground visitors who both declared `spec Person` validate
     *  against each other's. */
    private static final java.util.Map<ClassLoader, Registry> BY_LOADER =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static final ClassValue<Registry> OF_CLASS = new ClassValue<>() {
        @Override protected Registry computeValue(Class<?> type) {
            return forLoader(type.getClassLoader());
        }
    };

    private static Registry forLoader(ClassLoader cl) {
        return BY_LOADER.computeIfAbsent(cl, k -> new Registry());
    }

    /** The registry used by entry points that don't name a program. */
    private static final Registry SHARED = forLoader(SpecValidator.class.getClassLoader());

    /** The registry of the program whose code {@code owner} is. */
    public static Registry registryOf(Class<?> owner) {
        return owner == null ? SHARED : OF_CLASS.get(owner);
    }

    /** The registry the validation in progress on this thread resolves
     *  user-declared spec names against. */
    private static final ScopedValue<Registry> CURRENT = ScopedValue.newInstance();

    private static Registry current() {
        return CURRENT.isBound() ? CURRENT.get() : SHARED;
    }

    /** Emitter-side: register a product spec (record-shaped). The
     *  fields array carries field names in declaration order; the
     *  parallel specs array carries each field's {@link #encode}d
     *  spec, or {@code ""} where the declaration left it open. */
    public static void registerProduct(Class<?> owner, String name, String[] fields, String[] fieldSpecs) {
        var specs = new java.util.ArrayList<String>(fields.length);
        for (int i = 0; i < fields.length; i++) {
            specs.add(i < fieldSpecs.length && fieldSpecs[i] != null ? fieldSpecs[i] : "");
        }
        registryOf(owner).specs.put(name, new Descriptor.Product(List.of(fields), List.copyOf(specs)));
    }

    public static void registerProduct(String name, String[] fields, String[] fieldSpecs) {
        registerProduct(null, name, fields, fieldSpecs);
    }

    /** Names-only registration — every field unconstrained. Kept for
     *  callers that have no spec information to hand (tests, tooling). */
    public static void registerProduct(String name, String[] fields) {
        registerProduct(name, fields, new String[fields.length]);
    }

    /** Emitter-side: register a sum spec. The flat array alternates
     *  {@code variantName, arity, variantName, arity, ...}. Order is
     *  preserved for error-message determinism. */
    public static void registerSum(String name, Object[] flatVariants) {
        registerSum(null, name, flatVariants);
    }

    public static void registerSum(Class<?> owner, String name, Object[] flatVariants) {
        java.util.LinkedHashMap<String, Integer> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < flatVariants.length; i += 2) {
            String vname = (String) flatVariants[i];
            int arity = ((Number) flatVariants[i + 1]).intValue();
            map.put(vname, arity);
        }
        registryOf(owner).specs.put(name, new Descriptor.Sum(map));
    }

    /** Test/inspection helper: {@code name} as declared by {@code owner}'s program. */
    public static Descriptor lookup(Class<?> owner, String name) {
        return registryOf(owner).specs.get(name);
    }

    // ── Encode ──────────────────────────────────────────────────────────

    public static String encode(SpecExpr spec) {
        StringBuilder sb = new StringBuilder();
        encodeInto(spec, sb);
        return sb.toString();
    }

    private static void encodeInto(SpecExpr spec, StringBuilder sb) {
        switch (spec) {
            case SpecExpr.Wildcard w -> sb.append('_');
            case SpecExpr.Var v -> sb.append('?').append(v.name());
            case SpecExpr.Unit u -> sb.append("()");
            case SpecExpr.Name n -> sb.append(n.name());
            case SpecExpr.App a -> {
                sb.append(a.head()).append('[');
                for (int i = 0; i < a.args().size(); i++) {
                    if (i > 0) sb.append(',');
                    encodeInto(a.args().get(i), sb);
                }
                sb.append(']');
            }
            case SpecExpr.Arrow a -> {
                sb.append('(');
                for (int i = 0; i < a.inputs().size(); i++) {
                    if (i > 0) sb.append(',');
                    encodeInto(a.inputs().get(i), sb);
                }
                sb.append("->");
                encodeInto(a.output(), sb);
                sb.append(')');
            }
            case SpecExpr.Enum e -> {
                for (int i = 0; i < e.values().size(); i++) {
                    if (i > 0) sb.append('|');
                    sb.append(':').append(e.values().get(i));
                }
            }
            case SpecExpr.VecSpec v -> {
                sb.append("Vec[");
                encodeInto(v.elemSpec(), sb);
                sb.append(']');
            }
            case SpecExpr.SetSpec s -> {
                sb.append("Set[");
                encodeInto(s.elemSpec(), sb);
                sb.append(']');
            }
            case SpecExpr.TupleSpec t -> {
                sb.append("Tuple[");
                for (int i = 0; i < t.elemSpecs().size(); i++) {
                    if (i > 0) sb.append(',');
                    encodeInto(t.elemSpecs().get(i), sb);
                }
                sb.append(']');
            }
            case SpecExpr.RecordSpec r -> {
                // Record[field=spec,field=spec]. Field order preserved
                // (LinkedHashMap iteration matches insertion order).
                sb.append("Record[");
                boolean first = true;
                for (var e : r.fields().entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(e.getKey()).append('=');
                    encodeInto(e.getValue(), sb);
                }
                sb.append(']');
            }
        }
    }

    // ── Decode ──────────────────────────────────────────────────────────

    public static SpecExpr decode(String src) {
        return CACHE.computeIfAbsent(src, s -> new Parser(s).parseAll());
    }

    private static final class Parser {
        final String src;
        int pos;

        Parser(String src) { this.src = src; }

        SpecExpr parseAll() {
            SpecExpr s = parseSpec();
            if (pos != src.length()) {
                throw new IllegalStateException(
                        "Spec decode: trailing input at " + pos + ": " + src);
            }
            return s;
        }

        SpecExpr parseSpec() {
            if (pos >= src.length()) {
                throw new IllegalStateException("Spec decode: unexpected end of input");
            }
            char c = src.charAt(pos);
            if (c == '_') { pos++; return new SpecExpr.Wildcard(); }
            if (c == '?') { pos++; return new SpecExpr.Var(readName()); }
            if (c == '(') return parseGroup();
            if (c == ':') return parseEnum();
            // Name or App-head
            String name = readName();
            if (pos < src.length() && src.charAt(pos) == '[') {
                pos++; // consume [
                // Record: head is "Record", body is field=spec pairs.
                if ("Record".equals(name)) {
                    var fields = new java.util.LinkedHashMap<String, SpecExpr>();
                    if (src.charAt(pos) != ']') {
                        parseRecordField(fields);
                        while (pos < src.length() && src.charAt(pos) == ',') {
                            pos++;
                            parseRecordField(fields);
                        }
                    }
                    expect(']');
                    return new SpecExpr.RecordSpec(fields);
                }
                List<SpecExpr> args = new ArrayList<>();
                if (src.charAt(pos) != ']') {
                    args.add(parseSpec());
                    while (pos < src.length() && src.charAt(pos) == ',') {
                        pos++;
                        args.add(parseSpec());
                    }
                }
                expect(']');
                return normaliseApp(name, args);
            }
            return new SpecExpr.Name(name);
        }

        private void parseRecordField(java.util.LinkedHashMap<String, SpecExpr> out) {
            String fname = readName();
            expect('=');
            SpecExpr fspec = parseSpec();
            out.put(fname, fspec);
        }

        SpecExpr parseGroup() {
            expect('(');
            // Empty group → Unit.
            if (pos < src.length() && src.charAt(pos) == ')') {
                pos++;
                return new SpecExpr.Unit();
            }
            // Otherwise Arrow: a (',' a)* '->' a.
            List<SpecExpr> inputs = new ArrayList<>();
            inputs.add(parseSpec());
            while (pos < src.length() && src.charAt(pos) == ',') {
                pos++;
                inputs.add(parseSpec());
            }
            // Expect "->"
            if (pos + 1 < src.length() && src.charAt(pos) == '-' && src.charAt(pos + 1) == '>') {
                pos += 2;
                SpecExpr output = parseSpec();
                expect(')');
                return new SpecExpr.Arrow(List.copyOf(inputs), output);
            }
            throw new IllegalStateException("Spec decode: expected '->' in arrow at " + pos);
        }

        SpecExpr parseEnum() {
            List<String> vals = new ArrayList<>();
            while (pos < src.length() && src.charAt(pos) == ':') {
                pos++;
                vals.add(readName());
                if (pos < src.length() && src.charAt(pos) == '|') {
                    pos++;
                } else {
                    break;
                }
            }
            return new SpecExpr.Enum(List.copyOf(vals));
        }

        String readName() {
            int start = pos;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (isNameChar(c)) pos++;
                else break;
            }
            if (pos == start) {
                throw new IllegalStateException("Spec decode: expected name at " + start);
            }
            return src.substring(start, pos);
        }

        static boolean isNameChar(char c) {
            if (c >= 'A' && c <= 'Z') return true;
            if (c >= 'a' && c <= 'z') return true;
            if (c >= '0' && c <= '9') return true;
            if (c == '_') return true;
            // Hyphen is part of names UNLESS followed by '>' (arrow lookahead).
            // The caller never reads '-' as first char (would be ambiguous with
            // a hypothetical negative literal — specs don't have those).
            return false;
        }

        void expect(char c) {
            if (pos >= src.length() || src.charAt(pos) != c) {
                throw new IllegalStateException(
                        "Spec decode: expected '" + c + "' at " + pos + " in " + src);
            }
            pos++;
        }
    }

    /** Normalise App-form back to the dedicated record where possible —
     *  the interpreter walks both shapes but tests sometimes pattern-
     *  match on the dedicated record. */
    private static SpecExpr normaliseApp(String head, List<SpecExpr> args) {
        return switch (head) {
            case "Vec" -> args.size() == 1
                    ? new SpecExpr.VecSpec(args.get(0))
                    : new SpecExpr.App(head, List.copyOf(args));
            case "Set" -> args.size() == 1
                    ? new SpecExpr.SetSpec(args.get(0))
                    : new SpecExpr.App(head, List.copyOf(args));
            case "Tuple" -> new SpecExpr.TupleSpec(List.copyOf(args));
            default -> new SpecExpr.App(head, List.copyOf(args));
        };
    }

    // ── Validate ────────────────────────────────────────────────────────

    /** Entry point from emitted bytecode. argIdx ≥ 0 for inputs;
     *  argIdx == -1 marks an output return. fnName is the surrounding
     *  fn for error messages. Returns {@code value} on success so the
     *  call site can re-store it without a separate dup/swap. */
    public static Object validateEncoded(Object value, String encodedSpec,
                                          String fnName, int argIdx, Class<?> owner) {
        SpecExpr spec = decode(encodedSpec);
        try {
            return validateIn(registryOf(owner), value, spec);
        } catch (IrijRuntimeError e) {
            throw new IrijRuntimeError(blameMessage(e.getMessage(), fnName, argIdx));
        }
    }

    public static Object validateEncoded(Object value, String encodedSpec,
                                          String fnName, int argIdx) {
        return validateEncoded(value, encodedSpec, fnName, argIdx, null);
    }

    /** {@link #validate} with user-declared names resolved in {@code reg}. */
    public static Object validateIn(Registry reg, Object value, SpecExpr spec) {
        if (CURRENT.isBound() && CURRENT.get() == reg) return validate(value, spec);
        return ScopedValue.where(CURRENT, reg).call(() -> validate(value, spec));
    }

    private static String blameMessage(String reason, String fnName, int argIdx) {
        if (argIdx < 0) {
            return "Spec failure on output of " + fnName + ": " + reason;
        }
        return "Spec failure on input " + argIdx + " of " + fnName + ": " + reason;
    }

    /** Validate a value against a spec; throws IrijRuntimeError on
     *  mismatch, returns the value on success. Mirrors
     *  {@code Interpreter.validateAgainstSpecExpr} for the variants
     *  bytecode-mode supports. User-declared product/sum specs are
     *  not in scope here (no specRegistry at runtime); they fall
     *  through as accepted (interpreter has the full coverage). */
    public static Object validate(Object value, SpecExpr spec) {
        if (spec == null) return value;
        return switch (spec) {
            case SpecExpr.Wildcard w -> value;
            case SpecExpr.Var v -> value;
            case SpecExpr.Unit u -> {
                if (value == null || value == Values.UNIT) yield value;
                throw fail("expected Unit, got " + typeName(value));
            }
            case SpecExpr.Name n -> validateNamed(value, n.name());
            case SpecExpr.App a -> validateApp(value, a);
            case SpecExpr.Arrow a -> validateArrow(value, a);
            case SpecExpr.Enum e -> validateEnum(value, e);
            case SpecExpr.VecSpec v -> validateVec(value, v.elemSpec());
            case SpecExpr.SetSpec s -> validateSet(value, s.elemSpec());
            case SpecExpr.TupleSpec t -> validateTuple(value, t.elemSpecs());
            case SpecExpr.RecordSpec r -> validateRecord(value, r.fields());
        };
    }

    /** Validate that {@code value} is an IrijMap whose listed fields
     *  each match their declared spec. Records are open by default —
     *  extra fields on the map are silently accepted. */
    private static Object validateRecord(Object value,
            java.util.LinkedHashMap<String, SpecExpr> fields) {
        if (!(value instanceof Values.IrijMap m)) {
            throw fail("expected Map (record), got " + typeName(value));
        }
        var entries = m.entries();
        for (var e : fields.entrySet()) {
            String name = e.getKey();
            if (!entries.containsKey(name)) {
                throw fail("missing required field '" + name + "' in record");
            }
            try { validate(entries.get(name), e.getValue()); }
            catch (IrijRuntimeError re) {
                throw fail("field '" + name + "': " + re.getMessage());
            }
        }
        return value;
    }

    private static Object validateNamed(Object value, String name) {
        return switch (name) {
            case "Int" -> requireType(value, Long.class, "Int");
            case "Float" -> requireType(value, Double.class, "Float");
            case "Bool" -> requireType(value, Boolean.class, "Bool");
            case "Str" -> requireType(value, String.class, "Str");
            case "Keyword" -> requireType(value, Values.Keyword.class, "Keyword");
            case "Rational" -> requireType(value, Values.Rational.class, "Rational");
            case "Vec", "Vector" -> requireType(value, Values.IrijVector.class, "Vec");
            case "Map" -> {
                // IrijMap obviously matches.
                if (value instanceof Values.IrijMap) yield value;
                // Records (Values.Tagged with namedFields) read like
                // maps via dot-access already; accept them here so
                // user code stops having to choose between record
                // ergonomics and Map-typed boundaries. Common case:
                // vrata's `El`/`Node` values flowing into fns
                // declared with `:: Map ...`.
                if (value instanceof Values.Tagged t && t.namedFields() != null) yield value;
                throw fail("expected Map, got " + typeName(value));
            }
            case "Set" -> requireType(value, Values.IrijSet.class, "Set");
            case "Tuple" -> requireType(value, Values.IrijTuple.class, "Tuple");
            case "Fn" -> {
                if (isCallable(value)) yield value;
                throw fail("expected Fn, got " + typeName(value));
            }
            case "Unit" -> {
                if (value == null || value == Values.UNIT) yield value;
                throw fail("expected Unit, got " + typeName(value));
            }
            case "Any" -> value;
            default -> validateUserDeclared(value, name);
        };
    }

    /** Look up {@code name} in the current program's {@link Registry} and validate against
     *  the descriptor. Falls through (returns value) if no descriptor
     *  is registered — same behaviour as before product/sum specs
     *  were wired into bytecode mode. */
    private static Object validateUserDeclared(Object value, String name) {
        // O(1) certification fast-path — Constructor sets specName at
        // construction time, so a Tagged carrying the matching spec
        // name is already known-good. Matches Interpreter's
        // validateAgainstSpec short-circuit.
        if (value instanceof Values.Tagged t && name.equals(t.specName())) {
            return value;
        }
        Descriptor d = current().specs.get(name);
        if (d == null) return value;
        return switch (d) {
            case Descriptor.Product p -> validateProductShape(value, name, p);
            case Descriptor.Sum s -> validateSumShape(value, name, s.variants());
        };
    }

    /**
     * Constructor-side certification. Called on every product value
     * the emitter builds, and deliberately bypasses the specName
     * fast-path in {@link #validateUserDeclared} — that fast-path is
     * sound only because this ran first.
     */
    public static Object certifyProduct(Object value, String specName, Class<?> owner) {
        Registry reg = registryOf(owner);
        Descriptor d = reg.specs.get(specName);
        if (d instanceof Descriptor.Product p
                && value instanceof Values.Tagged t
                && t.namedFields() != null) {
            ScopedValue.where(CURRENT, reg).run(() -> checkProductFields(t.namedFields(), specName, p));
        }
        return value;
    }

    public static Object certifyProduct(Object value, String specName) {
        return certifyProduct(value, specName, null);
    }

    /**
     * A product is <b>closed</b>: exactly the declared fields, each
     * satisfying its declared spec. An extra field is a mismatch, not
     * a superset that happens to fit — otherwise any record carrying
     * the right names would pass as any other, and the spec would
     * name a shape it doesn't actually pin down.
     */
    private static Object validateProductShape(Object value, String specName,
                                                Descriptor.Product p) {
        if (value instanceof Values.Tagged t && t.namedFields() != null) {
            checkProductFields(t.namedFields(), specName, p);
            return new Values.Tagged(t.tag(), t.fields(), t.namedFields(), specName);
        }
        if (value instanceof Values.IrijMap m) {
            checkProductFields(m.entries(), specName, p);
            var named = new java.util.LinkedHashMap<>(m.entries());
            List<Object> fields = new java.util.ArrayList<>(p.fields().size());
            for (var field : p.fields()) fields.add(named.get(field));
            return new Values.Tagged(specName, fields, named, specName);
        }
        throw fail("cannot validate " + typeName(value) + " as " + specName);
    }

    private static void checkProductFields(java.util.Map<String, Object> actual,
                                            String specName, Descriptor.Product p) {
        for (int i = 0; i < p.fields().size(); i++) {
            String field = p.fields().get(i);
            if (!actual.containsKey(field)) {
                throw fail(specName + " requires field '" + field + "'");
            }
            String encoded = p.fieldSpecs().get(i);
            if (encoded.isEmpty()) continue;
            try {
                validate(actual.get(field), decode(encoded));
            } catch (IrijRuntimeError e) {
                throw fail(specName + " field '" + field + "': " + e.getMessage());
            }
        }
        for (var key : actual.keySet()) {
            if (!p.fields().contains(key)) {
                throw fail(specName + " has no field '" + key + "' (declares: "
                        + String.join(", ", p.fields()) + ")");
            }
        }
    }

    /** Primitive spec names allowed as sum-spec variants: a bare value
     *  of the matching Java type satisfies the sum without a tag. */
    private static final java.util.Set<String> PRIMITIVE_VARIANTS = java.util.Set.of(
            "Str", "Int", "Float", "Bool", "Keyword", "Rational", "Unit");

    /** True when {@code name} may appear as a primitive variant in a
     *  sum spec (used by the emitter to keep primitive names out of
     *  the constructor/tag registry). */
    public static boolean isPrimitiveVariant(String name) {
        return PRIMITIVE_VARIANTS.contains(name);
    }

    private static boolean primitiveMatches(String name, Object value) {
        return switch (name) {
            case "Str" -> value instanceof String;
            case "Int" -> value instanceof Long;
            case "Float" -> value instanceof Double;
            case "Bool" -> value instanceof Boolean;
            case "Keyword" -> value instanceof Values.Keyword;
            case "Rational" -> value instanceof Values.Rational;
            case "Unit" -> value == Values.UNIT;
            default -> false;
        };
    }

    private static Object validateSumShape(Object value, String specName,
                                            java.util.Map<String, Integer> variants) {
        if (!(value instanceof Values.Tagged t)) {
            // Primitive variants: `spec Node / Raw / El / Str` accepts a
            // bare string by type. Primitives carry no certification tag;
            // the value passes through unchanged.
            for (String v : variants.keySet()) {
                if (PRIMITIVE_VARIANTS.contains(v) && primitiveMatches(v, value)) {
                    return value;
                }
            }
            throw fail("expected " + specName + " variant, got " + typeName(value));
        }
        Integer arity = variants.get(t.tag());
        if (arity == null) {
            throw fail("'" + t.tag() + "' is not a variant of " + specName
                    + " (expected one of: " + variants.keySet() + ")");
        }
        if (t.fields().size() != arity) {
            throw fail(t.tag() + " expects " + arity + " fields, got " + t.fields().size());
        }
        return new Values.Tagged(t.tag(), t.fields(), t.namedFields(), specName);
    }

    private static Object validateApp(Object value, SpecExpr.App app) {
        return switch (app.head()) {
            case "Vec", "Vector" -> validateVec(value,
                    app.args().isEmpty() ? null : app.args().get(0));
            case "Set" -> validateSet(value,
                    app.args().isEmpty() ? null : app.args().get(0));
            case "Map" -> {
                if (!(value instanceof Values.IrijMap m)) {
                    throw fail("expected Map, got " + typeName(value));
                }
                if (app.args().size() >= 2) {
                    SpecExpr keySpec = app.args().get(0);
                    SpecExpr valSpec = app.args().get(1);
                    for (var entry : m.entries().entrySet()) {
                        validate(entry.getKey(), keySpec);
                        validate(entry.getValue(), valSpec);
                    }
                }
                yield value;
            }
            case "Tuple" -> validateTuple(value, app.args());
            case "Fn" -> {
                if (!isCallable(value)) {
                    throw fail("expected Fn, got " + typeName(value));
                }
                // Optional arity arg: (Fn 2). Not enforced for bytecode-
                // emitted closures (no Lambda arity metadata at runtime
                // without reflection).
                yield value;
            }
            // Handler:Effect spec (v0.8.x). `Handler Logger` validates
            // that the value is a CompiledHandler (or a
            // CompiledComposedHandler covering at least the named
            // effect). First-class handler values land in 8.0b; this
            // already lets fn signatures type-check handler-shaped
            // args without surface gymnastics.
            case "Handler" -> {
                String required = app.args().isEmpty() ? null
                        : (app.args().get(0) instanceof SpecExpr.Name n
                                ? n.name() : null);
                if (value instanceof dev.irij.compiler.CompiledHandler ch) {
                    if (required != null && !required.equals(ch.effectName)) {
                        throw fail("expected Handler " + required
                                + ", got Handler " + ch.effectName);
                    }
                    yield value;
                }
                if (value instanceof dev.irij.compiler.CompiledComposedHandler cch) {
                    if (required != null) {
                        for (var h : cch.handlers) {
                            if (required.equals(h.effectName)) yield value;
                        }
                        throw fail("expected composed Handler covering "
                                + required + ", no matching effect found");
                    }
                    yield value;
                }
                throw fail("expected Handler"
                        + (required != null ? " " + required : "")
                        + ", got " + typeName(value));
            }
            default -> value;
        };
    }

    private static Object validateArrow(Object value, SpecExpr.Arrow arrow) {
        if (!isCallable(value)) {
            throw fail("expected function " + arrow + ", got " + typeName(value));
        }
        return value; // wrapping with a validating proxy is interp-only
    }

    private static Object validateEnum(Object value, SpecExpr.Enum e) {
        if (!(value instanceof Values.Keyword kw)) {
            throw fail("expected Keyword, got " + typeName(value));
        }
        if (!e.values().contains(kw.name())) {
            throw fail(":" + kw.name() + " is not in " + e);
        }
        return value;
    }

    private static Object validateVec(Object value, SpecExpr elemSpec) {
        if (!(value instanceof Values.IrijVector vec)) {
            throw fail("expected Vec, got " + typeName(value));
        }
        if (elemSpec != null) {
            for (var elem : vec.elements()) validate(elem, elemSpec);
        }
        return value;
    }

    private static Object validateSet(Object value, SpecExpr elemSpec) {
        if (!(value instanceof Values.IrijSet set)) {
            throw fail("expected Set, got " + typeName(value));
        }
        if (elemSpec != null) {
            for (var elem : set.elements()) validate(elem, elemSpec);
        }
        return value;
    }

    private static Object validateTuple(Object value, List<SpecExpr> elemSpecs) {
        if (!(value instanceof Values.IrijTuple tup)) {
            throw fail("expected Tuple, got " + typeName(value));
        }
        if (!elemSpecs.isEmpty()) {
            if (tup.elements().length != elemSpecs.size()) {
                throw fail("expected Tuple with " + elemSpecs.size()
                        + " elements, got " + tup.elements().length);
            }
            for (int i = 0; i < elemSpecs.size(); i++) {
                validate(tup.elements()[i], elemSpecs.get(i));
            }
        }
        return value;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static Object requireType(Object value, Class<?> cls, String name) {
        if (cls.isInstance(value)) return value;
        throw fail("expected " + name + ", got " + typeName(value));
    }

    private static IrijRuntimeError fail(String reason) {
        return new IrijRuntimeError(reason);
    }

    private static String typeName(Object v) {
        return RuntimeSupport.typeTag(v);
    }

    private static boolean isCallable(Object v) {
        return v instanceof RuntimeSupport.IrijFn
                || v instanceof Values.BuiltinFn;
    }
}
