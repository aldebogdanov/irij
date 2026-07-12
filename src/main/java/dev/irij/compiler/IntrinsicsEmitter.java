package dev.irij.compiler;

import dev.irij.ast.Decl;
import dev.irij.ast.Expr;
import dev.irij.ast.Node;
import dev.irij.ast.Pattern;
import dev.irij.ast.Stmt;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class IntrinsicsEmitter implements Opcodes {

    private final ClassEmitter ce;

    IntrinsicsEmitter(ClassEmitter ce) { this.ce = ce; }

    void emitBuiltinEffectCheck(String name, MethodVisitor mv) {
        String eff = ClassEmitter.BUILTIN_EFFECT.get(name);
        if (eff == null) return;
        mv.visitLdcInsn(eff);
        mv.visitLdcInsn(name);
        mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, "checkPerformEffect",
                "(Ljava/lang/String;Ljava/lang/String;)V", false);
    }


    boolean emitBuiltinApp(String name, List<Expr> args, MethodVisitor mv, Locals locals) {
        emitBuiltinEffectCheck(name, mv);
        switch (name) {
            case "print", "println" -> {
                if (args.size() != 1) return false;
                ce.exprEm.emitExpr(args.get(0), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, name, "(Ljava/lang/Object;)V", false);
                // print/println return unit — push UNIT for expression-position use
                mv.visitFieldInsn(GETSTATIC, ClassEmitter.VALUES, "UNIT", ClassEmitter.OBJ_DESC);
                return true;
            }
            case "to-str" -> {
                if (args.size() != 1) return false;
                ce.exprEm.emitExpr(args.get(0), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, "toStr",
                        "(Ljava/lang/Object;)Ljava/lang/String;", false);
                return true;
            }
            case "error" -> {
                if (args.size() != 1) return false;
                ce.exprEm.emitExpr(args.get(0), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, "errorBuiltin",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            case "spawn" -> {
                if (args.size() != 1) return false;
                ce.exprEm.emitExpr(args.get(0), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, "spawn",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            case "sleep" -> {
                if (args.size() != 1) return false;
                ce.exprEm.emitExpr(args.get(0), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, "sleep",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            case "await" -> {
                if (args.size() != 1) return false;
                ce.exprEm.emitExpr(args.get(0), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, "await",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            case "par" -> {
                ce.exprEm.pushObjectArray(args, mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, "par",
                        "([Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            case "race" -> {
                ce.exprEm.pushObjectArray(args, mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, "race",
                        "([Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            case "timeout" -> {
                if (args.size() != 2) return false;
                ce.exprEm.emitExpr(args.get(0), mv, locals);
                ce.exprEm.emitExpr(args.get(1), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, "timeout",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            case "try" -> {
                if (args.size() != 1) return false;
                ce.exprEm.emitExpr(args.get(0), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, "tryFn",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            // ── Collection / string primitives (Phase 2) ───────────────
            // Names match the interpreter convention in Builtins.java so a
            // single .irj source compiles + interprets identically. These
            // are the raw building blocks; stdlib fns (fold/map/filter/etc.)
            // are written in Irij on top.
            case "length" -> {
                if (args.size() != 1) return false;
                ce.exprEm.emitExpr(args.get(0), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, "length",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            case "nth" -> {
                if (args.size() != 2) return false;
                ce.exprEm.emitExpr(args.get(0), mv, locals);
                ce.exprEm.emitExpr(args.get(1), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, "nth",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            case "conj" -> {
                if (args.size() != 2) return false;
                ce.exprEm.emitExpr(args.get(0), mv, locals);
                ce.exprEm.emitExpr(args.get(1), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, "conj",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            case "empty?" -> {
                if (args.size() != 1) return false;
                ce.exprEm.emitExpr(args.get(0), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, "isEmpty",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            case "head" -> {
                if (args.size() != 1) return false;
                ce.exprEm.emitExpr(args.get(0), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, "head",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            case "tail" -> {
                if (args.size() != 1) return false;
                ce.exprEm.emitExpr(args.get(0), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, "tail",
                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            // Effect-transparent higher-order builtin: callback runs in
            // the caller's effect row (matches the interpreter
            // BuiltinFn semantics).
            case "fold" -> {
                if (args.size() != 3) return false;
                ce.exprEm.emitExpr(args.get(0), mv, locals);
                ce.exprEm.emitExpr(args.get(1), mv, locals);
                ce.exprEm.emitExpr(args.get(2), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, "fold",
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            // ── Phase R3 ports: strings + maps + misc ────────────────
            // Each case shape: emit args left-to-right, then a single
            // static call into RuntimeSupport. The RT method names are
            // Java-safe (containsP, getOp, etc.) since Irij names can
            // include `?` and `-`.
            case "replace"      -> { return emitRT3(args, mv, locals, "replace"); }
            case "substring"    -> { return emitRT3(args, mv, locals, "substring"); }
            case "split"        -> { return emitRT2(args, mv, locals, "split"); }
            case "join"         -> { return emitRT2(args, mv, locals, "join"); }
            case "trim"         -> { return emitRT1(args, mv, locals, "trimStr"); }
            case "upper-case"   -> { return emitRT1(args, mv, locals, "upperCase"); }
            case "lower-case"   -> { return emitRT1(args, mv, locals, "lowerCase"); }
            case "starts-with?" -> { return emitRT2(args, mv, locals, "startsWithP"); }
            case "ends-with?"   -> { return emitRT2(args, mv, locals, "endsWithP"); }
            case "index-of"     -> { return emitRT2(args, mv, locals, "indexOf"); }
            case "url-encode"   -> { return emitRT1(args, mv, locals, "urlEncode"); }
            case "url-decode"   -> { return emitRT1(args, mv, locals, "urlDecode"); }
            case "get"          -> { return emitRT2(args, mv, locals, "getOp"); }
            case "assoc"        -> { return emitRT3(args, mv, locals, "assoc"); }
            case "dissoc"       -> { return emitRT2(args, mv, locals, "dissoc"); }
            case "merge"        -> { return emitRT2(args, mv, locals, "merge"); }
            case "keys"         -> { return emitRT1(args, mv, locals, "keys"); }
            case "vals"         -> { return emitRT1(args, mv, locals, "vals"); }
            case "contains?"    -> { return emitRT2(args, mv, locals, "containsP"); }
            case "last"         -> { return emitRT1(args, mv, locals, "last"); }
            case "to-vec"       -> { return emitRT1(args, mv, locals, "toVec"); }
            case "not"          -> { return emitRT1(args, mv, locals, "notOp"); }
            case "type-of"      -> { return emitRT1(args, mv, locals, "typeOf"); }
            case "validate"     -> { return emitRT2(args, mv, locals, "validate"); }
            case "validate!"    -> { return emitRT2(args, mv, locals, "validateBang"); }
            // raw-db-* emit entries removed phase 3a; the Db effect ops
            // route through `db-jdbc.method` dispatch in std/db.irj.
            // raw-sse-* emit entries removed phase 3c (ServeCapability)
            // raw-multipart-* emit entries removed phase 3d (ServeCapability)
            // raw-http-serve emit entry removed phase 3c (ServeCapability)
            // ── R3 batch 4: JSON + FileIO + env / time ───────────────
            case "json-parse"          -> { return emitRT1(args, mv, locals, "jsonParse"); }
            case "json-encode"         -> { return emitRT1(args, mv, locals, "jsonEncode"); }
            case "json-encode-pretty"  -> { return emitRT1(args, mv, locals, "jsonEncodePretty"); }
            // FileIO fast-paths removed phase 3d (FsCapability path)
            case "get-env"             -> { return emitRT1(args, mv, locals, "getEnv"); }
            case "now-ms"              -> {
                if (!isZeroArgCall(args)) return false;
                mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, "nowMs", "()Ljava/lang/Object;", false);
                return true;
            }
            case "env" -> {
                ce.exprEm.pushObjectArray(args, mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, "envBuiltin",
                        "([Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            // ── R3: math primitives ──────────────────────────────────
            case "sqrt"  -> { return emitRT1(args, mv, locals, "sqrt"); }
            case "sin"   -> { return emitRT1(args, mv, locals, "sin"); }
            case "cos"   -> { return emitRT1(args, mv, locals, "cos"); }
            case "tan"   -> { return emitRT1(args, mv, locals, "tan"); }
            case "log"   -> {
                // `log` collides with a common effect op name
                // (`effect Log { log :: Str -> () }`). When the user
                // has declared such an effect, fall through to the
                // effect-op dispatch path instead of math.log.
                if (ce.effectOps.containsKey("log")) return false;
                return emitRT1(args, mv, locals, "log");
            }
            case "exp"   -> { return emitRT1(args, mv, locals, "exp"); }
            case "floor" -> { return emitRT1(args, mv, locals, "floor"); }
            case "ceil"  -> { return emitRT1(args, mv, locals, "ceil"); }
            case "round" -> { return emitRT1(args, mv, locals, "round"); }
            case "abs"   -> { return emitRT1(args, mv, locals, "abs"); }
            case "pow"   -> { return emitRT2(args, mv, locals, "pow"); }
            case "min"   -> { return emitRT2(args, mv, locals, "min"); }
            case "max"   -> { return emitRT2(args, mv, locals, "max"); }
            case "div"   -> { return emitRT2(args, mv, locals, "divInt"); }
            case "mod"   -> { return emitRT2(args, mv, locals, "modInt"); }
            // ── R3: random ───────────────────────────────────────────
            case "random-int"   -> { return emitRT1(args, mv, locals, "randomInt"); }
            case "random-float" -> {
                if (!isZeroArgCall(args)) return false;
                mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, "randomFloat",
                        "()Ljava/lang/Object;", false);
                return true;
            }
            // ── crypto / auth ────────────────────────────────────────
            case "sha256-hex"       -> { return emitRT1(args, mv, locals, "sha256Hex"); }
            case "hmac-sha256-hex"  -> { return emitRT2(args, mv, locals, "hmacSha256Hex"); }
            case "random-token"     -> { return emitRT1(args, mv, locals, "randomToken"); }
            // ── R3: string parsing / chars ───────────────────────────
            case "parse-int"      -> { return emitRT1(args, mv, locals, "parseInt"); }
            case "parse-float"    -> { return emitRT1(args, mv, locals, "parseFloat"); }
            case "char-at"        -> { return emitRT2(args, mv, locals, "charAt"); }
            case "char-code"      -> { return emitRT1(args, mv, locals, "charCode"); }
            case "from-char-code" -> { return emitRT1(args, mv, locals, "fromCharCode"); }
            // ── R3: vec ops not yet emitted ──────────────────────────
            case "reverse" -> { return emitRT1(args, mv, locals, "reverseVal"); }
            case "sort"    -> { return emitRT1(args, mv, locals, "sortVal"); }
            case "take"    -> { return emitRT2(args, mv, locals, "takeVal"); }
            case "drop"    -> { return emitRT2(args, mv, locals, "dropVal"); }
            case "concat"  -> { return emitRT2(args, mv, locals, "concatTwo"); }
            // ── R3: misc ─────────────────────────────────────────────
            case "dbg" -> {
                if (args.size() != 1) return false;
                ce.exprEm.emitExpr(args.get(0), mv, locals);
                mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, "dbg",
                        "(Ljava/lang/Object;)V", false);
                mv.visitFieldInsn(GETSTATIC, ClassEmitter.VALUES, "UNIT", ClassEmitter.OBJ_DESC);
                return true;
            }
            case "read-line" -> {
                if (!isZeroArgCall(args)) return false;
                mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, "readLine",
                        "()Ljava/lang/Object;", false);
                return true;
            }
            case "identity" -> {
                if (args.size() != 1) return false;
                ce.exprEm.emitExpr(args.get(0), mv, locals);
                return true; // x → x
            }
            case "const" -> {
                // const x y → x — emit x, evaluate-and-discard y
                if (args.size() != 2) return false;
                ce.exprEm.emitExpr(args.get(0), mv, locals);
                ce.exprEm.emitExpr(args.get(1), mv, locals);
                mv.visitInsn(POP);
                return true;
            }
            // ── R3 final batch: toml-parse, println, flip handled below ─
            case "toml-parse"       -> { return emitRT1(args, mv, locals, "tomlParse"); }
            // raw-http-request emit entry removed phase 3b
            //   (HttpClientCapability path via std.http)
            case "flip" -> {
                // flip f a b → f b a. Three-arg call form. The
                // interpreter's BuiltinFn version errors because of
                // missing partial-app support; bytecode just emits
                // the rewrite directly.
                if (args.size() != 3) return false;
                ce.exprEm.emitExpr(args.get(0), mv, locals);  // f
                ce.exprEm.emitExpr(args.get(2), mv, locals);  // b
                ce.exprEm.emitExpr(args.get(1), mv, locals);  // a
                // Stack: f, b, a — build [b, a] array, call f.apply
                ce.exprEm.pushIconst(mv, 2);
                mv.visitTypeInsn(ANEWARRAY, ClassEmitter.OBJ);
                // Array on top; need to populate from below-the-arr items.
                // Easier: push args in order, store in array via swap-y dance.
                // Simpler approach: stash into temp slots, then build.
                // Fall through: undo the above and rebuild cleanly via temps.
                mv.visitInsn(POP);
                int aSlot = locals.allocateAnon();
                int bSlot = locals.allocateAnon();
                int fSlot = locals.allocateAnon();
                mv.visitVarInsn(ASTORE, aSlot);
                mv.visitVarInsn(ASTORE, bSlot);
                mv.visitVarInsn(ASTORE, fSlot);
                mv.visitVarInsn(ALOAD, fSlot);
                ce.exprEm.pushIconst(mv, 2);
                mv.visitTypeInsn(ANEWARRAY, ClassEmitter.OBJ);
                mv.visitInsn(DUP);
                ce.exprEm.pushIconst(mv, 0);
                mv.visitVarInsn(ALOAD, bSlot);
                mv.visitInsn(AASTORE);
                mv.visitInsn(DUP);
                ce.exprEm.pushIconst(mv, 1);
                mv.visitVarInsn(ALOAD, aSlot);
                mv.visitInsn(AASTORE);
                mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, "callAny",
                        "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
                return true;
            }
            // ── raw-session-* fast-paths removed phase 3e ────────────
            //   Session effect now routes through std.session +
            //   SessionCapability. raw-nrepl-eval-sandboxed kept as
            //   an unannotated builtin for the standalone
            //   `irij nrepl-eval-sandboxed` CLI path (no handler scope).
            case "raw-nrepl-eval-sandboxed" -> {
                return emitSessions2(args, mv, locals, "rawNreplEvalSandboxed");
            }
            default -> { return false; }
        }
    }


    /** True if the call site is a zero-arg invocation OR a one-arg
     *  call where the single arg is the Unit literal — the idiomatic
     *  Irij way to write "no args" (e.g. `now-ms ()`). */
    static boolean isZeroArgCall(List<Expr> args) {
        if (args.isEmpty()) return true;
        return args.size() == 1 && args.get(0) instanceof Expr.UnitLit;
    }


    boolean emitSessions1(List<Expr> args, MethodVisitor mv, Locals locals, String method) {
        if (args.size() != 1) return false;
        ce.exprEm.emitExpr(args.get(0), mv, locals);
        mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.SESSIONS, method,
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        return true;
    }


    boolean emitSessions2(List<Expr> args, MethodVisitor mv, Locals locals, String method) {
        if (args.size() != 2) return false;
        ce.exprEm.emitExpr(args.get(0), mv, locals);
        ce.exprEm.emitExpr(args.get(1), mv, locals);
        mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.SESSIONS, method,
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
        return true;
    }


    /** Emit a 1-arg call to RT.<method>(Object): Object. */
    boolean emitRT1(List<Expr> args, MethodVisitor mv, Locals locals, String method) {
        if (args.size() != 1) return false;
        ce.exprEm.emitExpr(args.get(0), mv, locals);
        mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, method,
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        return true;
    }


    /** Emit a 2-arg call to RT.<method>(Object, Object): Object. */
    boolean emitRT2(List<Expr> args, MethodVisitor mv, Locals locals, String method) {
        if (args.size() != 2) return false;
        ce.exprEm.emitExpr(args.get(0), mv, locals);
        ce.exprEm.emitExpr(args.get(1), mv, locals);
        mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, method,
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
        return true;
    }


    /** Emit a 3-arg call to RT.<method>(Object, Object, Object): Object. */
    boolean emitRT3(List<Expr> args, MethodVisitor mv, Locals locals, String method) {
        if (args.size() != 3) return false;
        ce.exprEm.emitExpr(args.get(0), mv, locals);
        ce.exprEm.emitExpr(args.get(1), mv, locals);
        ce.exprEm.emitExpr(args.get(2), mv, locals);
        mv.visitMethodInsn(INVOKESTATIC, ClassEmitter.RT, method,
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                false);
        return true;
    }
}
