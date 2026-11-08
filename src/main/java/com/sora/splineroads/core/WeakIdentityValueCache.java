package com.sora.splineroads.core;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/** Bounded identity cache for derived values that retain their input through a reference
 * mesh. Both sides are weak: the cache itself cannot keep that cycle alive. A live caller
 * keeps its result and source alive normally. Factories run outside the cache lock. */
public final class WeakIdentityValueCache<K,V> {
  private static final class Key<K> extends WeakReference<K> {
    private final int hash;
    Key(K key,ReferenceQueue<K> queue){super(key,queue);hash=System.identityHashCode(key);}
    @Override public int hashCode(){return hash;}
    @Override public boolean equals(Object other){return this==other||other instanceof Key<?> key&&get()!=null&&get()==key.get();}
  }
  private static final class Value<K,V> extends WeakReference<V> {
    final Key<K> key;final int weight;
    Value(Key<K> key,V value,int weight,ReferenceQueue<V> queue){super(value,queue);this.key=key;this.weight=weight;}
  }
  private final int maxEntries;
  private final long maxWeight;
  private final ToIntFunction<V> weigh;
  private final ReferenceQueue<K> keys=new ReferenceQueue<>();
  private final ReferenceQueue<V> results=new ReferenceQueue<>();
  private final LinkedHashMap<Key<K>,Value<K,V>> entries=new LinkedHashMap<>(64,.75f,true);
  private long weight,hits,misses,epoch;
  public WeakIdentityValueCache(int maxEntries,long maxWeight,ToIntFunction<V> weigh){
    if(maxEntries<1||maxWeight<1)throw new IllegalArgumentException("Invalid cache budget");
    this.maxEntries=maxEntries;this.maxWeight=maxWeight;this.weigh=Objects.requireNonNull(weigh);
  }
  public V get(K key,Function<K,V> factory){
    Objects.requireNonNull(key);var lookup=new Key<>(key,null);long generation;
    synchronized(this){reap();V prior=live(lookup);if(prior!=null){hits++;return prior;}misses++;generation=epoch;}
    V created=Objects.requireNonNull(factory.apply(key));int size=Math.max(1,weigh.applyAsInt(created));
    synchronized(this){
      if(generation!=epoch)return created;
      reap();V prior=live(lookup);if(prior!=null)return prior;
      if(size>maxWeight)return created;
      var stored=new Key<>(key,keys);entries.put(stored,new Value<>(stored,created,size,results));weight+=size;
      var iterator=entries.entrySet().iterator();
      while(iterator.hasNext()&&(entries.size()>maxEntries||weight>maxWeight)){weight-=iterator.next().getValue().weight;iterator.remove();}
    }
    return created;
  }
  private V live(Key<K> key){
    var entry=entries.get(key);if(entry==null)return null;V value=entry.get();
    if(value==null){entries.remove(key);weight-=entry.weight;}return value;
  }
  private void reap(){
    for(var dead=keys.poll();dead!=null;dead=keys.poll()){var entry=entries.remove(dead);if(entry!=null)weight-=entry.weight;}
    for(var dead=results.poll();dead!=null;dead=results.poll()){
      @SuppressWarnings("unchecked") var entry=(Value<K,V>)dead;
      if(entries.remove(entry.key,entry))weight-=entry.weight;
    }
  }
  public synchronized void clear(){epoch++;entries.clear();weight=0;while(keys.poll()!=null){}while(results.poll()!=null){}}
  public synchronized WeakIdentityCache.Stats stats(){reap();return new WeakIdentityCache.Stats(entries.size(),weight,hits,misses);}
}
