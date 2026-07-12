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


/** One chunk of a SM body. Three flavours:
 *  - Trailing pure: opName == null && innerWith == null
 *  - Op terminator: opName != null (perform yields to enclosing handler)
 *  - Nested-with terminator: innerWith != null (run a nested `with` whose
 *    own performs may escape and be caught by the OUTER trampoline; the
 *    inner continuation lives in the outer's k.fields[innerSlot] so
 *    state survives across the outer resume cycle).
 */
record Segment(
        List<Stmt> pureStmts,
        String opName,
        List<Expr> opArgs,
        String bindName,
        Stmt.With innerWith,
        int innerSlot,
        String innerBind) {

    /** Pure / op-terminated segment (no nested with). */
    public Segment(List<Stmt> pureStmts, String opName,
                   List<Expr> opArgs, String bindName) {
        this(pureStmts, opName, opArgs, bindName, null, -1, null);
    }
}


sealed interface WithBodyShape {
    record Sequence(List<Segment> segments, List<String> liftedLocals)
            implements WithBodyShape {}
    record Pure() implements WithBodyShape {}
    /** Op appears at body[idx] either as an ExprStmt or `bindName := op args`. */
    record SingleOp(int idx, String opName, List<Expr> opArgs, String bindName)
            implements WithBodyShape {}
    /** Full CFG: blocks with branch/jump/perform/return terminators.
     *  Used when the body contains an IfStmt/MatchStmt whose branches
     *  contain op calls (step 3b). */
    record EffIR(List<BB> blocks,
                 List<String> liftedLocals,
                 Map<Integer, String> resumeBindOf,
                 int lastValueBlock) implements WithBodyShape {}
    record Unsupported() implements WithBodyShape {}
}


// ── EffIR terminators + blocks (step 3b) ────────────────────────────

sealed interface Term {
    /** Return a value (expr may be null → return Unit/null). */
    record Return(Expr expr) implements Term {}
    /** Throw PerformSignal; on resume, continue at block `next`.
     *  If bindName != null, the resume value is stored into
     *  k.fields[lifted[bindName]] on re-entry to `next`. */
    record Perform(String effectName, String opName, List<Expr> args,
                   String bindName, int next) implements Term {}
    /** Pure branch (cond has no op). */
    record Branch(Expr cond, int thenId, int elseId) implements Term {}
    /** Unconditional jump inside the same step invocation. */
    record Jump(int target) implements Term {}
}


record BB(int id, List<Stmt> pure, Term term) {}


record TopLevelBindWith(String bindName, Stmt.With with) {}


record TopLevelOp(String opName, List<Expr> args, String bindName) {}
