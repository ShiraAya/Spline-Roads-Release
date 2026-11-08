package com.sora.splineroads.client;

import java.util.LinkedHashMap;

/** Weighted cold allocation queue. A loaded region is never a candidate;
 * leaving and re-entering the loaded set refreshes its retention age. Values
 * are allocation identities, so the two vertex encodings share one budget. */
final class RoadGpuRetention<K> {
  private final LinkedHashMap<K,Long> cold=new LinkedHashMap<>();
  private long bytes;

  void update(K key,long allocationBytes,boolean loaded){
    if(loaded||allocationBytes<=0){remove(key);return;}
    var previous=cold.put(key,allocationBytes);
    bytes+=allocationBytes-(previous==null?0:previous);
  }
  void remove(K key){var previous=cold.remove(key);if(previous!=null)bytes-=previous;}
  K takeOldest(long limit){
    if(bytes<=Math.max(0,limit))return null;
    var iterator=cold.entrySet().iterator();var entry=iterator.next();
    K key=entry.getKey();bytes-=entry.getValue();iterator.remove();return key;
  }
  long bytes(){return bytes;}
  int size(){return cold.size();}
  void clear(){cold.clear();bytes=0;}
}
