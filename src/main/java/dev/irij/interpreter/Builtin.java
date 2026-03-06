package dev.irij.interpreter;

import java.util.List;

/**
 * A built-in function implemented in Java.
 */
@FunctionalInterface
public interface Builtin {
    Object call(List<Object> args);
}
