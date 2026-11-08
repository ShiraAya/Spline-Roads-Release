package com.sora.splineroads.client;

/** An unchanged view has no new deferred mesh work. The extra preparation
 * margin lets the camera move one chunk before another visibility pass. */
final class RoadBuildScope {
  private boolean initialized,terrain;
  private double x,z;
  private int distance;
  boolean changed(double nextX,double nextZ,int nextDistance,boolean nextTerrain){
    double dx=nextX-x,dz=nextZ-z;
    if(initialized&&dx*dx+dz*dz<=256&&distance==nextDistance&&terrain==nextTerrain)return false;
    initialized=true;x=nextX;z=nextZ;distance=nextDistance;terrain=nextTerrain;return true;
  }
  void clear(){initialized=false;}
}
