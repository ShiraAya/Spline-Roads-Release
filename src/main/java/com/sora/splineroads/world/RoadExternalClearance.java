package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import java.util.*;

/** Imported travel envelopes are planning inputs, not fake roads or world blocks. */
final class RoadExternalClearance implements AutoCloseable {
  private static final ThreadLocal<RoadExternalClearance> CURRENT=new ThreadLocal<>();
  private final RoadExternalClearance previous;
  private final RoadSolidOverlap.Index clearance,poles;
  private RoadExternalClearance(List<RailbedPath.Path> selected,List<RailbedPath.Path> surrounding){
    previous=CURRENT.get();var ids=new HashSet<String>();selected.forEach(p->p.segments().forEach(s->ids.add(s.id())));
    var other=surrounding.stream().filter(p->p.segments().stream().noneMatch(s->ids.contains(s.id()))).toList();clearance=RailbedStructures.clearance(other);poles=RailbedStructures.catenaryClearance(other);CURRENT.set(this);
  }
  static RoadExternalClearance open(List<RailbedPath.Path> selected,List<RailbedPath.Path> surrounding){return new RoadExternalClearance(selected,surrounding);}
  static boolean blocked(RoadStructures.Part part){var scope=CURRENT.get();return scope!=null&&!RailbedFurniture.visualOnly(part)&&(part.model().startsWith("sr:catenary")?scope.poles:scope.clearance).intersects(part);}
  static void validate(RoadRecord road){
    if(CURRENT.get()==null)return;
    var parts=new ArrayList<>(RoadTravelValidation.deckParts(road.mesh()));parts.addAll(road.structures());
    for(var part:parts)if(blocked(part))throw new IllegalArgumentException(String.format(Locale.ROOT,"构件与未纳入路基的轨道相交：%.2f, %.2f, %.2f；请把相邻轨道加入同一路基或调整设置",part.a().x(),part.a().y(),part.a().z()));
  }
  public void close(){if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
}
