package com.sora.splineroads.client;

import java.util.function.LongSupplier;

/** Bound candidate visits even when none of them produces work. One initial
 * visit guarantees progress if a preceding phase used the shared deadline. */
final class RoadScanBudget {
  private final int limit;
  private final long deadline;
  private final LongSupplier clock;
  private int visits;

  RoadScanBudget(int limit,long deadline){this(limit,deadline,System::nanoTime);}
  RoadScanBudget(int limit,long deadline,LongSupplier clock){
    this.limit=Math.max(0,limit);this.deadline=deadline;this.clock=clock;
  }
  boolean take(){
    if(visits>=limit||visits>0&&clock.getAsLong()>=deadline)return false;
    visits++;return true;
  }
  int visits(){return visits;}
}
