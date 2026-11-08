package com.sora.splineroads.client;

import java.util.Arrays;

/** Immutable visible index spans. Rebuilt when ownership changes, never per draw. */
final class RoadDrawSelection {
  static final RoadDrawSelection EMPTY=new RoadDrawSelection(false,new int[0],0,0);
  final boolean all;
  final int[] spans;
  final long indices,surfaceIndices;

  private RoadDrawSelection(boolean all,int[] spans,long indices,long surfaceIndices){
    this.all=all;this.spans=spans;this.indices=indices;this.surfaceIndices=surfaceIndices;
  }
  static final class Builder {
    private int[] spans=new int[8];
    private int size;
    private boolean all=true;
    private long indices,surfaceIndices;

    void add(int first,int count,boolean visible,boolean surface){
      all&=visible;
      if(!visible||count==0)return;
      indices+=count;if(surface)surfaceIndices+=count;
      if(size>0&&spans[size-2]+spans[size-1]==first){spans[size-1]+=count;return;}
      if(size+2>spans.length)spans=Arrays.copyOf(spans,spans.length*2);
      spans[size++]=first;spans[size++]=count;
    }

    RoadDrawSelection build(){
      return indices==0?EMPTY:new RoadDrawSelection(all,all?new int[0]:Arrays.copyOf(spans,size),indices,surfaceIndices);
    }
  }
}
