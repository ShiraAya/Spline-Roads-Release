package com.sora.splineroads.client;

import java.util.*;

/** Conservative X/Z anchor buckets. Exact 3D distance and frustum tests remain
 * in SmartSignClient; a key occupies one bucket and never owns sign state. */
final class SignCandidateIndex<K> {
  private static final int CELL_SIZE=64;
  private final Map<K,Long> membership=new HashMap<>();
  private final Map<Long,Set<K>> buckets=new HashMap<>();
  void put(K key,double x,double z){
    long cell=key(cell(x),cell(z));var old=membership.get(key);
    if(old!=null&&old==cell)return;
    remove(key);membership.put(key,cell);
    buckets.computeIfAbsent(cell,k->new HashSet<>()).add(key);
  }
  void remove(K key){
    var cell=membership.remove(key);if(cell==null)return;
    var bucket=buckets.get(cell);bucket.remove(key);if(bucket.isEmpty())buckets.remove(cell);
  }
  List<K> near(double x,double z){
    var result=new ArrayList<K>();
    // Saturating cell coordinates keep even finite out-of-world saved anchors
    // bounded. Long loop counters cannot wrap at either integer endpoint.
    int x0=cell(x-144),x1=cell(x+144),z0=cell(z-144),z1=cell(z+144);
    for(long cx=x0;cx<=x1;cx++)for(long cz=z0;cz<=z1;cz++){
      var bucket=buckets.get(key((int)cx,(int)cz));if(bucket!=null)result.addAll(bucket);
    }
    return result;
  }
  void clear(){membership.clear();buckets.clear();}
  int size(){return membership.size();}
  int bucketCount(){return buckets.size();}
  private static int cell(double value){return (int)Math.floor(value/CELL_SIZE);}
  private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
}
