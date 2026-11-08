package com.sora.splineroads.world;
import java.util.*;
import java.util.function.Supplier;
/** Full edit metadata is materialized only when an edit actually needs to enumerate it. */
final class DeferredMap<K,V> extends AbstractMap<K,V> {
  private final Supplier<Map<K,V>> source;
  DeferredMap(Supplier<Map<K,V>> source){this.source=source;}
  @Override public Set<Entry<K,V>> entrySet(){return source.get().entrySet();}
  @Override public V get(Object key){return source.get().get(key);}
  @Override public boolean containsKey(Object key){return source.get().containsKey(key);}
  @Override public int size(){return source.get().size();}
}
