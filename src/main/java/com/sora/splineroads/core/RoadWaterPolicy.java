package com.sora.splineroads.core;

/** Cell-state decision, not a renderer workaround. A sealed LIGHT shell can contain
 * outside water while the tunnel's reserved interior and slab remain water-free. */
public final class RoadWaterPolicy {
  public static boolean permeable(boolean slab,boolean retainedTerrain,boolean tunnelOwner,
      boolean shell,boolean dryInterior){
    if(slab||retainedTerrain||dryInterior)return false;
    return !tunnelOwner||shell;
  }
  public static boolean waterlogged(boolean permeable,boolean terrainWater,boolean previousWater,boolean originalWater){
    return permeable&&(terrainWater||previousWater||originalWater);
  }
  private RoadWaterPolicy(){}
}
