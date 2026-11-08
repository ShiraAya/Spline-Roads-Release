package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Broad phase for the SAME sampled self-intersection/volume predicates. No sampling
 * is skipped. Immutable per-mesh bounds, bounded weak identity retention; cached
 * values do not retain Mesh keys. Query counters are not used for correctness. */
public final class RoadPathIndex {
  private static final WeakIdentityCache<Mesh,RoadBoundsIndex> SEGMENTS=
      new WeakIdentityCache<>(64,200_000,RoadBoundsIndex::bucketCount);
  public static RoadBoundsIndex segments(Mesh mesh){return SEGMENTS.get(mesh,m->{
    var bounds=new ArrayList<RoadBoundsIndex.Bounds>();var points=m.samples();
    for(int i=1;i<points.size();i++)bounds.add(segment(points.get(i-1).center(),points.get(i).center()));
    return new RoadBoundsIndex(bounds,16);
  });}
  public static RoadBoundsIndex.Bounds segment(V a,V b){return new RoadBoundsIndex.Bounds(
      Math.min(a.x(),b.x()),Math.min(a.z(),b.z()),Math.max(a.x(),b.x()),Math.max(a.z(),b.z()));}
  private static final WeakIdentityCache<Mesh,RoadBoundsIndex> POINTS=
      new WeakIdentityCache<>(64,200_000,RoadBoundsIndex::bucketCount);
  public static RoadBoundsIndex points(Mesh mesh){return POINTS.get(mesh,RoadPathIndex::pointIndex);}
  private static RoadBoundsIndex pointIndex(Mesh mesh){
    var bounds=new ArrayList<RoadBoundsIndex.Bounds>();
    for(int i=0;i<mesh.samples().size();i+=3){var p=mesh.samples().get(i).center();bounds.add(segment(p,p));}
    return new RoadBoundsIndex(bounds,16);
  }
  private RoadPathIndex(){}
}
