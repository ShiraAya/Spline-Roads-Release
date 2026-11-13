package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.V;
import java.util.*;

/** Contact for a complete vanilla cube above a continuous footway, without moving the block. */
public final class SidewalkBlockContact {
  public static double bottom(int x,int y,int z,Collection<List<V>> faces) {
    var square=List.of(new V(x,y,z),new V(x+1,y,z),new V(x+1,y,z+1),new V(x,y,z+1));
    List<List<V>> uncovered=List.of(square);double floor=Double.POSITIVE_INFINITY;
    for(var face:faces){
      var clipped=RoadSurface.intersect(face,square);if(clipped.isEmpty())continue;
      double low=clipped.stream().mapToDouble(V::y).min().orElseThrow(),high=clipped.stream().mapToDouble(V::y).max().orElseThrow();
      if(low<y-1+1e-6||high>y-1e-6)continue;
      floor=Math.min(floor,low);var next=new ArrayList<List<V>>();
      for(var piece:uncovered)next.addAll(RoadSurface.subtract(piece,clipped));uncovered=next;
    }
    // Require the full block footprint, not the centre or a conservative collision voxel.
    double remainder=uncovered.stream().mapToDouble(RoadSurface::area).sum();
    return remainder<1e-8&&Double.isFinite(floor)?floor-y:0;
  }
  private SidewalkBlockContact(){}
}
