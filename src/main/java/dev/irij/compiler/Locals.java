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


// ── Local slots ────────────────────────────────────────────────────

final class Locals {
    final Map<String, Integer> slots;
    final int[] counter;
    final Locals parent;

    Locals() {
        this.slots = new HashMap<>();
        this.counter = new int[]{0};
        this.parent = null;
    }

    Locals(Locals parent) {
        this.slots = new HashMap<>();
        this.counter = parent.counter;
        this.parent = parent;
    }

    void reserveArgsArray() { counter[0] = 1; }

    int allocate(String name) {
        int s = counter[0]++;
        slots.put(name, s);
        return s;
    }

    int allocateAnon() { return counter[0]++; }

    Locals childScope() { return new Locals(this); }

    Integer lookup(String name) {
        Integer s = slots.get(name);
        if (s != null) return s;
        return parent == null ? null : parent.lookup(name);
    }
}
