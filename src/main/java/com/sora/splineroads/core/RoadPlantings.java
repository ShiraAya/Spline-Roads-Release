package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadStreetscape.Planting;

/** Authored median dimensions and independent planting schedules; absent old fields use defaults. */
public record RoadPlantings(double greenWidth, double railGap, Planting medianTrees,
    double medianSpacing, Planting separatorTrees, double separatorSpacing) {
  public static final RoadPlantings DEFAULT = new RoadPlantings(3,0,Planting.NONE,16,Planting.NONE,16);
  public RoadPlantings {
    if(!RoadGeometry.finite(greenWidth,railGap,medianSpacing,separatorSpacing)
        ||greenWidth<.5||greenWidth>8||railGap<0||railGap>7
        ||Math.abs(greenWidth*2-Math.rint(greenWidth*2))>1e-7
        ||Math.abs(railGap*2-Math.rint(railGap*2))>1e-7
        ||medianSpacing<8||medianSpacing>64||separatorSpacing<8||separatorSpacing>64
        ||medianTrees==null||separatorTrees==null||medianTrees==Planting.FLOWERS||separatorTrees==Planting.FLOWERS)
      throw new IllegalArgumentException("中央绿化宽 0.5–8 格，护栏间距 0–7 格（步进 0.5）；植树间距 8–64 格");
  }
  public double railWidth(){return railGap==0?1:1.5+railGap;}
  public RoadPlantings greenWidth(double v){return new RoadPlantings(v,railGap,medianTrees,medianSpacing,separatorTrees,separatorSpacing);}
  public RoadPlantings railGap(double v){return new RoadPlantings(greenWidth,v,medianTrees,medianSpacing,separatorTrees,separatorSpacing);}
  public RoadPlantings medianTrees(Planting v){return new RoadPlantings(greenWidth,railGap,v,medianSpacing,separatorTrees,separatorSpacing);}
  public RoadPlantings medianSpacing(double v){return new RoadPlantings(greenWidth,railGap,medianTrees,v,separatorTrees,separatorSpacing);}
  public RoadPlantings separatorTrees(Planting v){return new RoadPlantings(greenWidth,railGap,medianTrees,medianSpacing,v,separatorSpacing);}
  public RoadPlantings separatorSpacing(double v){return new RoadPlantings(greenWidth,railGap,medianTrees,medianSpacing,separatorTrees,v);}
}
