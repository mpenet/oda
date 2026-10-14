// ABOUTME: Lossy lock-free cache of state derived from an option object, found by identity.
// ABOUTME: Lets per-option state (key-fn tables, encoder resolutions) outlive a single call.

package com.s_exp.oda;

import java.lang.ref.WeakReference;
import java.util.function.Function;

/**
 * Fixed-size, one-way associative cache keyed by object identity: a def'd
 * option object keeps its state across calls, a fresh one per call gets
 * fresh state, and a colliding key evicts it at worst (cache goes cold,
 * results stay correct). Slots are immutable, so racy reads are safe; the
 * weak reference never pins a dropped key (values must not reference it).
 */
final class IdentityCache<V> {

    private record Slot<V>(WeakReference<Object> key, V value) {
    }

    private final Slot<V>[] slots;

    @SuppressWarnings("unchecked")
    IdentityCache(int size) {
        slots = (Slot<V>[]) new Slot<?>[size];
    }

    V get(Object key, Function<Object, V> make) {
        int i = System.identityHashCode(key) & (slots.length - 1);
        Slot<V> s = slots[i];
        if (s != null && s.key().get() == key) {
            return s.value();
        }
        V v = make.apply(key);
        slots[i] = new Slot<>(new WeakReference<>(key), v);
        return v;
    }
}
