package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.V;
/** CPU reference for the SR world-solid shader's translation-invariant fog law. */
public final class RoadFog {
  public static double distance(V world,V camera,boolean cylindrical){V p=world.sub(camera);return cylindrical?Math.max(p.horizontalLength(),Math.abs(p.y())):Math.sqrt(p.dot(p));}
  private RoadFog(){}
}
