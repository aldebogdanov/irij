package dev.irij.compiler;

public final class Fiber {
    public final java.util.concurrent.CompletableFuture<Object> result;
    public final Thread thread;
    Fiber(java.util.concurrent.CompletableFuture<Object> result, Thread thread) {
        this.result = result;
        this.thread = thread;
    }
}
