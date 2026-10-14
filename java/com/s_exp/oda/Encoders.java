// ABOUTME: User encoders per Java type, taking precedence over the writer's built-in encodings.
// ABOUTME: Resolves a value's class to its most specific encoder once, then caches it per class.

package com.s_exp.oda;

import clojure.lang.IFn;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentHashMap;
import clojure.lang.PersistentVector;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Encoders from a {@code {Class fn}} map. A class resolves like a Clojure
 * protocol extension: its own encoder, else the nearest superclass's
 * (Object excluded), else the most specific interface's (unrelated
 * interfaces: first found), else Object's.
 *
 * Resolutions live in an open-addressed identity table, replaced
 * copy-on-write: immutable once published (final fields), so lookups are
 * plain unsynchronized reads. Racing resolutions can drop one another's
 * entry; it is just resolved again.
 */
final class Encoders {

    private static final Object NONE = new Object();

    private static final IdentityCache<Encoders> CACHE = new IdentityCache<>(64);

    /** Encoders of a {@code {Class fn}} map, found by map identity. */
    static Encoders of(Object m) {
        return CACHE.get(m, Encoders::new);
    }

    private record Table(Class<?>[] keys, Object[] vals, int size) {
    }

    /** Copy of the map: the cache holds this weakly keyed by the map. */
    private final IdentityHashMap<Class<?>, IFn> byClass = new IdentityHashMap<>();

    private Table table = new Table(new Class<?>[16], new Object[16], 0);

    /**
     * The most common value classes, compared exactly (a subclass may have
     * its own encoder).
     */
    private static final Class<?>[] COMMON_TYPES = {
        String.class, Long.class, PersistentArrayMap.class, PersistentVector.class,
        Keyword.class, Double.class, Boolean.class, PersistentHashMap.class};

    /**
     * True when no encoder applies to any of the common value classes: the
     * writer then skips their lookup, which would otherwise be a hash probe
     * per value.
     */
    final boolean commonTypesUnencoded;

    private Encoders(Object m) {
        if (!(m instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "encoders must be a map of Class to fn, got " + m.getClass().getName());
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof Class<?> c)) {
                throw new IllegalArgumentException("encoders key must be a Class, got " + e.getKey());
            }
            if (!(e.getValue() instanceof IFn f)) {
                throw new IllegalArgumentException("encoder for " + c.getName() + " must be a fn, got "
                        + e.getValue());
            }
            byClass.put(c, f);
        }
        boolean unencoded = true;
        for (Class<?> c : COMMON_TYPES) {
            unencoded &= mostSpecific(c) == null;
        }
        commonTypesUnencoded = unencoded;
    }

    /** Whether c is one of COMMON_TYPES: the same classes, unrolled. */
    static boolean isCommonType(Class<?> c) {
        return c == String.class || c == Long.class || c == PersistentArrayMap.class
                || c == PersistentVector.class || c == Keyword.class || c == Double.class
                || c == Boolean.class || c == PersistentHashMap.class;
    }

    /** The encoder for values of class c, or null if none applies. */
    IFn find(Class<?> c) {
        Table t = table;
        Class<?>[] keys = t.keys();
        int mask = keys.length - 1;
        int i = System.identityHashCode(c) & mask;
        for (;;) {
            Class<?> k = keys[i];
            if (k == c) {
                Object v = t.vals()[i];
                return v == NONE ? null : (IFn) v;
            }
            if (k == null) {
                return resolve(t, c);
            }
            i = (i + 1) & mask;
        }
    }

    private IFn resolve(Table t, Class<?> c) {
        IFn f = mostSpecific(c);
        // load factor 1/2 keeps probe sequences short and always ends them
        // on an empty slot
        int cap = t.keys().length;
        if ((t.size() + 1) * 2 > cap) {
            cap <<= 1;
        }
        Class<?>[] keys = new Class<?>[cap];
        Object[] vals = new Object[cap];
        Class<?>[] oldKeys = t.keys();
        for (int j = 0; j < oldKeys.length; j++) {
            if (oldKeys[j] != null) {
                insert(keys, vals, oldKeys[j], t.vals()[j]);
            }
        }
        insert(keys, vals, c, f == null ? NONE : f);
        table = new Table(keys, vals, t.size() + 1);
        return f;
    }

    private static void insert(Class<?>[] keys, Object[] vals, Class<?> c, Object v) {
        int mask = keys.length - 1;
        int i = System.identityHashCode(c) & mask;
        while (keys[i] != null) {
            i = (i + 1) & mask;
        }
        keys[i] = c;
        vals[i] = v;
    }

    private IFn mostSpecific(Class<?> c) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            IFn f = byClass.get(k);
            if (f != null) {
                return f;
            }
        }
        Class<?> best = null;
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            best = mostSpecificInterface(k.getInterfaces(), best);
        }
        return byClass.get(best != null ? best : Object.class);
    }

    private Class<?> mostSpecificInterface(Class<?>[] interfaces, Class<?> best) {
        for (Class<?> i : interfaces) {
            if (byClass.containsKey(i) && (best == null || best.isAssignableFrom(i))) {
                best = i;
            }
            best = mostSpecificInterface(i.getInterfaces(), best);
        }
        return best;
    }
}
