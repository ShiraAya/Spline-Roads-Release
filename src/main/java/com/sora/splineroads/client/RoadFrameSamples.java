package com.sora.splineroads.client;

import java.util.Arrays;

/** Bounded primitive cache for one uninterrupted render-thread step. A collision
 * only causes a fresh world read; keys are always checked before reusing a value. */
final class RoadFrameSamples {
  private final int capacity;
  private long[] keys;
  private int[] values, stamps;
  private int generation=1;

  RoadFrameSamples(int capacity){
    if(capacity<=0||(capacity&(capacity-1))!=0)throw new IllegalArgumentException("Power-of-two capacity required");
    this.capacity=capacity;
  }
  private int slot(long key){return (int)it.unimi.dsi.fastutil.HashCommon.mix(key)&(capacity-1);}
  int get(long key){
    if(keys==null)return 0;
    int slot=slot(key);
    return stamps[slot]==generation&&keys[slot]==key?values[slot]:0;
  }
  void put(long key,int value){
    if(keys==null){keys=new long[capacity];values=new int[capacity];stamps=new int[capacity];}
    int slot=slot(key);keys[slot]=key;values[slot]=value;stamps[slot]=generation;
  }
  void clear(){
    if(++generation==0){if(stamps!=null)Arrays.fill(stamps,0);generation=1;}
  }
}
