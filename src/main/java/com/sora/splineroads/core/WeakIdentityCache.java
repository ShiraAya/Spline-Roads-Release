package com.sora.splineroads.core;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/** Bounded, weak identity keys: never hash a road's complete sample/structure lists.
 * Values must not retain their key. Computation runs OUTSIDE the cache lock and must be pure.
 * Epoch checks prevent a job finishing after clear() from repopulating a retired session.
 */
public final class WeakIdentityCache<K,V> {
  private static final class Key<K> extends WeakReference<K> {
    final int hash;
    Key(K value, ReferenceQueue<K> queue) { super(value,queue); hash=System.identityHashCode(value); }
    @Override public int hashCode(){return hash;}
    @Override public boolean equals(Object value){
      if(this==value)return true;
      return value instanceof Key<?> key && get()!=null && get()==key.get();
    }
  }
  private record Entry<V>(V value,int weight){}
  public record Stats(int entries,long weight,long hits,long misses){}
  private final int maxEntries;
  private final long maxWeight;
  private final ToIntFunction<V> weigh;
  private final ReferenceQueue<K> queue=new ReferenceQueue<>();
  private final LinkedHashMap<Key<K>,Entry<V>> values=new LinkedHashMap<>(64,.75f,true);
  private long weight,hits,misses,epoch;
  public WeakIdentityCache(int maxEntries,long maxWeight,ToIntFunction<V> weigh){
    if(maxEntries<1||maxWeight<1)throw new IllegalArgumentException("Invalid cache budget");
    this.maxEntries=maxEntries;this.maxWeight=maxWeight;this.weigh=Objects.requireNonNull(weigh);
  }
  public V get(K key,Function<K,V> factory){
    Objects.requireNonNull(key);var lookup=new Key<>(key,null);long generation;
    synchronized(this){reap();var old=values.get(lookup);if(old!=null){hits++;return old.value();}misses++;generation=epoch;}
    V created=Objects.requireNonNull(factory.apply(key));int size=Math.max(1,weigh.applyAsInt(created));
    synchronized(this){
      if(generation!=epoch)return created;
      reap();var old=values.get(lookup);if(old!=null)return old.value();
      if(size>maxWeight)return created;
      values.put(new Key<>(key,queue),new Entry<>(created,size));weight+=size;
      var it=values.entrySet().iterator();
      while(it.hasNext()&&(values.size()>maxEntries||weight>maxWeight)){weight-=it.next().getValue().weight();it.remove();}
    }
    return created;
  }
  private void reap(){for(var dead=queue.poll();dead!=null;dead=queue.poll()){var old=values.remove(dead);if(old!=null)weight-=old.weight();}}
  public synchronized void clear(){epoch++;values.clear();weight=0;while(queue.poll()!=null){} }
  public synchronized Stats stats(){reap();return new Stats(values.size(),weight,hits,misses);}
}
