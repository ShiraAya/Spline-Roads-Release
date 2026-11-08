package com.sora.splineroads.client;

import java.util.BitSet;

/** Bounded hints for roads whose source was evicted. False positives only ask
 * the server for its authoritative watched roads; no hint can suppress a return.
 * A fixed bitmap avoids replacing the geometry leak with unbounded tombstones. */
final class RoadColdSync {
  private static final int MASK=(1<<20)-1;
  private final BitSet retired=new BitSet(MASK+1);
  private long generation,sentGeneration,lastRequest=Long.MIN_VALUE/2;
  private boolean pending,sent;
  private static long hash(long value){
    value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;
    value=(value^(value>>>27))*0x94d049bb133111ebL;
    return value^(value>>>31);
  }
  void evicted(long chunk){long h=hash(chunk);retired.set((int)h&MASK);retired.set((int)(h>>>32)&MASK);}
  void arrived(long chunk){
    long h=hash(chunk);
    if(retired.get((int)h&MASK)&&retired.get((int)(h>>>32)&MASK)){generation++;pending=true;}
  }
  boolean request(long tick){
    if(!pending||tick-lastRequest<41)return false;
    sent=true;sentGeneration=generation;lastRequest=tick;return true;
  }
  void acknowledged(){if(sent&&sentGeneration==generation){pending=false;sent=false;}}
  boolean pending(){return pending;}
  void clear(){retired.clear();generation=sentGeneration=0;lastRequest=Long.MIN_VALUE/2;pending=sent=false;}
}
