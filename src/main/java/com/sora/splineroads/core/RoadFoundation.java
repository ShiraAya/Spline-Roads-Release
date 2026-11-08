package com.sora.splineroads.core;

import java.util.Map;

/** Original supporting terrain is not the same thing as the visible fill retained in
 * a collider. Replanning must not mistake a previously excavated planter for a bridge.
 * This is a read-only classification view; it never restores blocks or headroom. */
public final class RoadFoundation {
  public static <T> T source(long key,T current,boolean owned,boolean air,
      Map<Long,T> original,Map<Long,T> retained,T empty) {
    if(owned || air && original.containsKey(key))
      return original.containsKey(key)?original.get(key):retained.getOrDefault(key,empty);
    return current;
  }
  private RoadFoundation(){}
}
