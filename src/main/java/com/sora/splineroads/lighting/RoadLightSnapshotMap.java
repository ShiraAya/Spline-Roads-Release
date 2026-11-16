package com.sora.splineroads.lighting;

import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import java.util.Arrays;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.LongFunction;

/**
 * Shallow copy-on-write snapshots for vanilla's section-to-DataLayer map.
 * The light engine still owns DataLayer copying, publication and synchronization.
 * Only its two initial section maps use this implementation; other fastutil maps
 * are untouched. A writer and its published snapshots never mutate shared shards.
 *
 * Vanilla uses primitive get/put/remove/containsKey/clone. Less common APIs switch
 * this instance permanently to a normal map, so live views and entry mutations
 * retain fastutil semantics, including when another mod inspects the storage.
 */
public final class RoadLightSnapshotMap<V> extends Long2ObjectOpenHashMap<V> {
    private static final long serialVersionUID = 1L;
    private static final int SHARDS = 1024;
    private final Long2ObjectOpenHashMap<V>[] shards;
    private final boolean[] owned;
    private int count;
    private volatile Long2ObjectOpenHashMap<V> materialized;

    @SuppressWarnings("unchecked")
    public RoadLightSnapshotMap() {
        super(0);
        shards = (Long2ObjectOpenHashMap<V>[]) new Long2ObjectOpenHashMap<?>[SHARDS];
        owned = new boolean[SHARDS];
    }

    private RoadLightSnapshotMap(RoadLightSnapshotMap<V> source) {
        super(0);
        defRetValue = source.defRetValue;
        shards = source.shards.clone();
        owned = new boolean[SHARDS];
        count = source.count;
        Arrays.fill(source.owned, false);
        if (source.materialized != null) materialized = source.materialized.clone();
    }

    private static int shard(long key) {
        // fastutil uses the low hash bits inside each shard; use different bits
        // here so selecting a shard does not force all its keys to collide.
        return (int) (HashCommon.mix(key) >>> 32) & (SHARDS - 1);
    }

    private Long2ObjectOpenHashMap<V> writable(int index) {
        Long2ObjectOpenHashMap<V> map = shards[index];
        if (map == null) {
            map = new Long2ObjectOpenHashMap<>(4);
            shards[index] = map;
            owned[index] = true;
        } else if (!owned[index]) {
            map = map.clone();
            shards[index] = map;
            owned[index] = true;
        }
        return map;
    }

    @Override public RoadLightSnapshotMap<V> clone() {
        return new RoadLightSnapshotMap<>(this);
    }

    @Override public V get(long key) {
        Long2ObjectOpenHashMap<V> full = materialized;
        if (full != null) return full.get(key);
        Long2ObjectOpenHashMap<V> map = shards[shard(key)];
        if (map == null) return defRetValue;
        V value = map.get(key);
        return value == null && defRetValue != null && !map.containsKey(key) ? defRetValue : value;
    }

    @Override public boolean containsKey(long key) {
        Long2ObjectOpenHashMap<V> full = materialized;
        if (full != null) return full.containsKey(key);
        Long2ObjectOpenHashMap<V> map = shards[shard(key)];
        return map != null && map.containsKey(key);
    }

    @Override public V getOrDefault(long key, V fallback) {
        Long2ObjectOpenHashMap<V> full = materialized;
        if (full != null) return full.getOrDefault(key, fallback);
        Long2ObjectOpenHashMap<V> map = shards[shard(key)];
        return map == null ? fallback : map.getOrDefault(key, fallback);
    }

    @Override public V put(long key, V value) {
        Long2ObjectOpenHashMap<V> full = materialized;
        if (full != null) return full.put(key, value);
        Long2ObjectOpenHashMap<V> map = writable(shard(key));
        int before = map.size();
        V old = map.put(key, value);
        if (map.size() != before) { count++; return defRetValue; }
        return old;
    }

    @Override public V remove(long key) {
        Long2ObjectOpenHashMap<V> full = materialized;
        if (full != null) return full.remove(key);
        int index = shard(key);
        Long2ObjectOpenHashMap<V> map = shards[index];
        if (map == null || !map.containsKey(key)) return defRetValue;
        map = writable(index);
        V old = map.remove(key);
        count--;
        return old;
    }

    @Override public int size() {
        Long2ObjectOpenHashMap<V> full = materialized;
        return full == null ? count : full.size();
    }

    @Override public boolean isEmpty() { return size() == 0; }

    @Override public void clear() {
        Long2ObjectOpenHashMap<V> full = materialized;
        if (full != null) { full.clear(); return; }
        Arrays.fill(shards, null);
        Arrays.fill(owned, false);
        count = 0;
    }

    @Override public void defaultReturnValue(V value) {
        defRetValue = value;
        Long2ObjectOpenHashMap<V> full = materialized;
        if (full != null) full.defaultReturnValue(value);
    }

    private synchronized Long2ObjectOpenHashMap<V> full() {
        if (materialized == null) {
            Long2ObjectOpenHashMap<V> result = new Long2ObjectOpenHashMap<>(count);
            result.defaultReturnValue(defRetValue);
            for (Long2ObjectOpenHashMap<V> map : shards) if (map != null) result.putAll(map);
            // Assign only when complete. Retain shards for concurrent readers of a
            // published snapshot that entered get() before this assignment.
            materialized = result;
        }
        return materialized;
    }

    @Override public boolean containsValue(Object value) { return full().containsValue(value); }
    @Override public void putAll(Map<? extends Long, ? extends V> map) { full().putAll(map); }
    @Override public V putIfAbsent(long key, V value) { return full().putIfAbsent(key, value); }
    @Override public boolean remove(long key, Object value) { return full().remove(key, value); }
    @Override public boolean replace(long key, V old, V value) { return full().replace(key, old, value); }
    @Override public V replace(long key, V value) { return full().replace(key, value); }
    @Override public V computeIfAbsent(long key, LongFunction<? extends V> f) { return full().computeIfAbsent(key, f); }
    @Override public V computeIfAbsent(long key, Long2ObjectFunction<? extends V> f) { return full().computeIfAbsent(key, f); }
    @Override public V computeIfPresent(long key, BiFunction<? super Long, ? super V, ? extends V> f) { return full().computeIfPresent(key, f); }
    @Override public V compute(long key, BiFunction<? super Long, ? super V, ? extends V> f) { return full().compute(key, f); }
    @Override public V merge(long key, V value, BiFunction<? super V, ? super V, ? extends V> f) { return full().merge(key, value, f); }
    @Override public Long2ObjectMap.FastEntrySet<V> long2ObjectEntrySet() { return full().long2ObjectEntrySet(); }
    @Override public LongSet keySet() { return full().keySet(); }
    @Override public ObjectCollection<V> values() { return full().values(); }
    @Override public boolean trim() { return full().trim(); }
    @Override public boolean trim(int size) { return full().trim(size); }
    @Override public int hashCode() { return full().hashCode(); }

    // Serialize the ordinary map, whose private serialization code owns its arrays.
    private Object writeReplace() { return full(); }
}
