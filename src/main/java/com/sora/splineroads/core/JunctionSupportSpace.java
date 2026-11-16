package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;

/** Explicit support space in junctions whose triangulated decks are UNMARKED. */
public final class JunctionSupportSpace {
  public static boolean contains(Part part,JunctionPlanner.Ref junction){
    if(junction==null||!part.pier()||part.material()!=Material.CONCRETE)return false;
    var spec=junction.spec();
    if(spec.kind()!=JunctionSpec.Kind.INTERSECTION||spec.oneSide()!=null)return false;
    // The crossing of the median axes is a separate, bounded support island.
    // Require the whole footprint inside every median, not merely its centre.
    boolean center=spec.arms().size()>=3;
    for(var arm:spec.arms()){
      if(arm.incoming()==0||arm.outgoing()==0||arm.median()<2){center=false;break;}
      for(V p:part.base())if(p.sub(spec.center()).horizontalLength()>4
          ||Math.abs(p.sub(spec.center()).dot(arm.inward().left())-arm.divider(spec.leftTraffic()))+.1>arm.median()/2){center=false;break;}
    }
    if(center)return true;
    for(int i=0;i<spec.arms().size();i++){
      var arm=spec.arms().get(i);if(arm.incoming()==0||arm.outgoing()==0||arm.medianKind()!=RoadProfile.Median.GREEN)continue;
      var mesh=junction.furnitureApproach(i);
      double end=JunctionApproachFurniture.medianEnd(spec,i,mesh);
      boolean fits=true;
      for(V p:part.base()){
        var q=RoadQueries.horizontal(mesh,p);
        // The approach's median moves between its authored outside port and
        // the widened junction mouth. The final arm divider is not its centre
        // along that transition. Match the actual planting, including its kerb inset.
        double divider=JunctionPlanner.approachMedian(arm,mesh,q.sample(),true,spec.leftTraffic());
        double green=1-RoadStreetscape.medianConversionBlend(mesh,q.sample());
        double width=Math.max(0,JunctionPlanner.approachMedian(arm,mesh,q.sample(),false,spec.leftTraffic())-.5)*green;
        if(q.sample().distance()<.1||q.sample().distance()>end
            ||RoadStreetscape.underground(mesh,q.sample().distance())
            ||Math.abs(q.lateral()-divider)+.1>width/2){fits=false;break;}
      }
      if(fits)return true;
    }
    return false;
  }
  private JunctionSupportSpace(){}
}
