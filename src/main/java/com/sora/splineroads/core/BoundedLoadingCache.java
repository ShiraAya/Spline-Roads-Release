package com.sora.splineroads.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Expensive independent plans never run under the shared LRU lock. */
final class BoundedLoadingCache<K,V> {
  private record Entry<V>(V value,long bytes){}
  private final Map<K,Entry<V>> values=new LinkedHashMap<>(16,.75f,true);
  private final Map<K,CompletableFuture<V>> pending=new HashMap<>();
  private final int capacity;private final long budget;private final ToLongFunction<V> weight;private long bytes;
  BoundedLoadingCache(int capacity,long budget,ToLongFunction<V> weight){this.capacity=capacity;this.budget=budget;this.weight=weight;}
  V get(K key,Function<K,V> factory){
    CompletableFuture<V> future;boolean owner;
    synchronized(this){var hit=values.get(key);if(hit!=null)return hit.value();future=pending.get(key);owner=future==null;if(owner){future=new CompletableFuture<>();pending.put(key,future);}}
    if(!owner){try{return future.join();}catch(CompletionException failure){if(failure.getCause() instanceof RuntimeException runtime)throw runtime;if(failure.getCause() instanceof Error error)throw error;throw failure;}}
    try{V result=Objects.requireNonNull(factory.apply(key));long size=Math.max(1,weight.applyAsLong(result));
      synchronized(this){if(size<=budget){values.put(key,new Entry<>(result,size));bytes+=size;var iterator=values.entrySet().iterator();while(values.size()>capacity||bytes>budget){bytes-=iterator.next().getValue().bytes();iterator.remove();}}}
      future.complete(result);synchronized(this){pending.remove(key);}return result;
    }catch(RuntimeException|Error failure){future.completeExceptionally(failure);synchronized(this){pending.remove(key);}throw failure;}
  }
}
