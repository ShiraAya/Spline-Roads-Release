package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import java.util.*;

/** Fixed source alignments use the same exact deck/structure geometry as road routing.
 * Check the final dependency plan, after automatic lamps and supports can move. */
final class RoadTravelValidation {
  static void check(List<RoadIndex.Built> roads,Set<UUID> changed,Map<UUID,RoadIndex.Built> previous){
    var railway=new ArrayList<Integer>();for(int i=0;i<roads.size();i++)if(roads.get(i).record.settings().options().railbed().active())railway.add(i);
    for(int i:railway)for(int j=0;j<roads.size();j++){
      var a=roads.get(i);var b=roads.get(j);
      if(i==j||j<i&&b.record.settings().options().railbed().active()
          ||(!changed.contains(a.record.id())&&!changed.contains(b.record.id()))||!RoadIndex.overlapXZ(a.mesh,b.mesh,2))continue;
      var oldA=previous.get(a.record.id());var oldB=previous.get(b.record.id());
      boolean sameA=oldA!=null&&oldA.mesh.samples().equals(a.mesh.samples())&&oldA.mesh.settings().thickness()==a.mesh.settings().thickness();
      boolean sameB=oldB!=null&&oldB.mesh.samples().equals(b.mesh.samples())&&oldB.mesh.settings().thickness()==b.mesh.settings().thickness();
      if(!sameA||!sameB)RoadClearance.check(a.mesh,b.mesh);
      fixtures(a,b,oldA,sameB);fixtures(b,a,oldB,sameA);
    }
  }
  private static void fixtures(RoadIndex.Built source,RoadIndex.Built target,RoadIndex.Built old,boolean sameTravel){
    if(!target.record.railway())return;
    var retained=old==null?Set.<RoadStructures.Part>of():new HashSet<>(old.record.structures());
    var profile=target.record.settings().options().railbed();
    var trainSpace=profile.active()?RailbedStructures.clearance(profile.paths()):null;
    var poleSpace=profile.active()?RailbedStructures.catenaryClearance(profile.paths()):null;
    for(var part:source.record.structures()){
      if(RailbedFurniture.visualOnly(part)||sameTravel&&retained.contains(part))continue;
      if(RoadClearance.belowSurface(part,target.mesh,.025))continue;
      if(trainSpace==null?RoadInteractions.invades(part,target.mesh):(part.model().startsWith("sr:catenary")?poleSpace:trainSpace).intersects(part))throw new IllegalArgumentException(String.format(Locale.ROOT,
          "规划后的构件与铁路路基通行空间相交：%.2f, %.2f, %.2f（%s）",part.a().x(),part.a().y(),part.a().z(),part.material()));
    }
  }
  static List<RoadStructures.Part> deckParts(com.sora.splineroads.core.RoadGeometry.Mesh mesh){
    var result=new ArrayList<RoadStructures.Part>();var samples=mesh.samples();double depth=mesh.settings().thickness();
    for(int i=1;i<samples.size();i++){var a=samples.get(i-1);var b=samples.get(i);
      result.add(new RoadStructures.Part(a.at(0,depth),b.at(0,depth),1,depth,false,RoadStructures.Material.CONCRETE,a.left().mul(a.halfWidth()),b.left().mul(b.halfWidth()),""));
    }return result;
  }
  private RoadTravelValidation(){}
}
