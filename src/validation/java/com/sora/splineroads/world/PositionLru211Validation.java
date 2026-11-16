package com.sora.splineroads.world;
import java.util.*;
import net.minecraft.core.BlockPos;

/** Exact LRU ordering, misses, cached nulls, copies and eviction under voxel keys. */
public final class PositionLru211Validation {
 public static void main(String[] args){
  var random=new Random(2111116);long checks=0;
  for(int limit:new int[]{1,7,1024,2048}){
   var old=new LinkedHashMap<Long,Integer>(128,.75f,true){protected boolean removeEldestEntry(Map.Entry<Long,Integer> e){return size()>limit;}};
   var next=new RoadIndex.LongLru<Integer>(limit);
   for(int i=0;i<50000;i++){
    long key=BlockPos.asLong(random.nextInt(128)-64,0,random.nextInt(128)-64);
    Integer value=i%7==0?null:random.nextInt();int op=random.nextInt(7);Object a,b;
    switch(op){
     case 0: case 1:a=old.put(key,value);b=next.put(key,value);break;
     case 2:a=old.get(key);b=next.get(key);break;
     case 3:a=old.containsKey(key);b=next.containsKey(key);break;
     case 4:a=old.computeIfAbsent(key,k->value);b=next.computeIfAbsent(key,k->value);break;
     case 5:Map<Long,Integer> exposed=next;a=old.get(key);b=exposed.get(key);break;
     default:a=old.remove(key);b=next.remove(key);break;
    }
    if(!Objects.equals(a,b))throw new AssertionError("result differs "+limit+" "+i);
    if(old.size()!=next.size()||!new ArrayList<>(old.entrySet()).equals(new ArrayList<>(next.entrySet())))throw new AssertionError("LRU differs "+limit+" "+i);
    checks++;
    if(i%3001==0){var copy=new RoadIndex.LongLru<Integer>(limit);copy.putAll(next);if(!new ArrayList<>(copy.entrySet()).equals(new ArrayList<>(old.entrySet())))throw new AssertionError("copy differs");next.clear();old.clear();}
   }
  }
  System.out.println("PASS exact_lru_operations="+checks+" limits=1,7,1024,2048");
 }
}
