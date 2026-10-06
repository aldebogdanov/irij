package dev.irij.compiler;

/** Public, opaque snapshot of effect-handling state taken at the
 *  call site. Capabilities that hand control to a fresh thread
 *  (HTTP request handlers, scheduled callbacks, anything backed
 *  by a Java executor) snapshot here and replay via
 *  {@link RtEffects#runWithEffectSnapshot} on the new thread so the
 *  user's Irij code finds the same handler chain it would have on the
 *  calling thread.
 *
 *  <p>Without this, fresh executor threads start with empty
 *  {@code EFFECT_ROW} / {@code SM_STACK} thread-locals and any
 *  {@code perform} blows up with "no handler on stack". */
public final class EffectSnapshot {
    public final ParentSnapshot inner;
    public EffectSnapshot(ParentSnapshot p) { this.inner = p; }
}
