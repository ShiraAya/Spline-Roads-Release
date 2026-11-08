package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** Physical road envelopes shared by saved structures and their loaded-road preview.
 * A pier flag also describes lamp posts: callers retain their separate mount policy. */
public final class RoadStructureClearance {
  private final Map<Mesh,List<Mesh>> cycleDecks=new IdentityHashMap<>();

  public static double headroom(Mesh road){return Math.max(road.settings().style()==Style.RAILBED?RailbedGeometry.HEADROOM:4.25,RoadInfrastructure.clearance(road.settings()));}

  /** Lowered bicycle decks must be checked before the ordinary road's vertical bounds. */
  public boolean cycleBlocked(Part part,Mesh road){
    if(!part.pier()&&(part.material()!=Material.CONCRETE||part.height()<=.4))return false;
    for(var deck:cycleDecks.computeIfAbsent(road,RoadsideBypass::travel))
      if(RoadClearance.structureInvades(part,deck,RoadsideBypass.HEADROOM))return true;
    return false;
  }

  /** Same-road girders may meet their own underside, never a lower return leg. */
  public static boolean selfSupportBlocked(Part part,Mesh road){
    if(!part.pier()&&(part.material()!=Material.CONCRETE||part.width()<1||part.height()<.5))return false;
    if(!part.pier()&&part.material()==Material.CONCRETE&&RoadClearance.belowSurface(part,road,.025))return false;
    return RoadClearance.structureInvades(part,road,headroom(road));
  }

  /** Only an intact median may contain a shaft; tactile access is never an exemption. */
  public static boolean pierBlocked(Part part,Mesh road,List<Mesh> medianContext){
    if(part.material()==Material.CONCRETE&&RoadSidewalks.blocksTactile(part,road))return true;
    if(!RoadGantryMounts.foot(part)&&RoadStructures.fitsMedian(part,road,medianContext))return false;
    return RoadGantryMounts.foot(part)?RoadGantryMounts.blockedByRoad(part,road,headroom(road))
        :RoadClearance.structureInvades(part,road,headroom(road));
  }
}
