package com.sora.splineroads.core;

import java.util.function.DoubleUnaryOperator;

/** Follow connected solid from the road plane, stopping even at fractional air gaps. */
public final class RoadTerrainSurface {
  public static double connected(double roadY,DoubleUnaryOperator solidTop){
    double probe=roadY+.0001;
    for(int i=0;i<RoadStructures.MAX_DROP*16&&probe<=roadY+RoadStructures.MAX_DROP;i++){
      RoadPlanningBudget.check();
      double top=solidTop.applyAsDouble(probe);
      if(!Double.isFinite(top)||top<=probe+1e-7)return top;
      probe=top+.0001;
    }
    return roadY+RoadStructures.MAX_DROP;
  }
  private RoadTerrainSurface(){}
}
