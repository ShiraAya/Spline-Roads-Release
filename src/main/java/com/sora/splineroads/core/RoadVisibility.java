package com.sora.splineroads.core;

/** Conservative horizontal view window for independently rendered road solids.
 * Terrain columns can remain visible at a high camera altitude or at a square-window
 * corner. A 3-D sphere must not remove their road/tunnel cover while the ground stays.
 * This is only a distance prefilter; the renderer still performs its normal frustum test.
 */
public final class RoadVisibility {
  public static boolean within(double minX,double minZ,double maxX,double maxZ,
      double cameraX,double cameraZ,int renderChunks) {
    if(!RoadGeometry.finite(minX,minZ,maxX,maxZ,cameraX,cameraZ)||minX>maxX||minZ>maxZ)return false;
    double reach=Math.max(0,renderChunks)*16.0+32;
    double dx=Math.max(0,Math.max(minX-cameraX,cameraX-maxX));
    double dz=Math.max(0,Math.max(minZ-cameraZ,cameraZ-maxZ));
    return dx<=reach&&dz<=reach;
  }
  private RoadVisibility(){}
}
