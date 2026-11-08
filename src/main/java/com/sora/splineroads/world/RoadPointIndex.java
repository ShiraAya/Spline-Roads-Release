package com.sora.splineroads.world;

import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.core.RoadGeometry.V;
import java.util.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

/** Server marker bounds and reverse connector references. Derived from RoadIndex only. */
final class RoadPointIndex {
  record Marker(UUID road,LanePoints.Point point,int ordinal) {}
  private final Map<Long,Set<Marker>> chunks=new HashMap<>();
  private final Map<UUID,List<Marker>> markers=new HashMap<>();
  private final Map<UUID,LanePoints.Link> links=new HashMap<>();
  private final Map<LanePoints.Ref,Set<UUID>> references=new HashMap<>();
  private final Map<UUID,Long> order=new HashMap<>();
  private long nextOrder;

  static AABB bounds(LanePoints.Point point){
    var p=point.position();return new AABB(p.x()-.26,p.y()+.03,p.z()-.26,p.x()+.26,p.y()+.55,p.z()+.26);
  }
  private static Set<Long> chunks(LanePoints.Point point){
    var box=bounds(point);var out=new HashSet<Long>();
    int x0=(int)Math.floor(box.minX/16),x1=(int)Math.floor(box.maxX/16);
    int z0=(int)Math.floor(box.minZ/16),z1=(int)Math.floor(box.maxZ/16);
    // A marker is narrower than one chunk. Explicit corners also avoid an int
    // loop wrapping if an old/corrupt saved point is far outside world bounds.
    out.add(ChunkPos.asLong(x0,z0));out.add(ChunkPos.asLong(x0,z1));
    out.add(ChunkPos.asLong(x1,z0));out.add(ChunkPos.asLong(x1,z1));
    return out;
  }
  void put(RoadRecord road){
    removeContents(road.id());
    order.computeIfAbsent(road.id(),ignored->nextOrder++);
    if(road.railway())return; // Old spurious road-lane metadata is not a railway marker.
    var points=new ArrayList<Marker>();int ordinal=0;
    for(var point:LaneTopology.visiblePoints(road)){
      var marker=new Marker(road.id(),point,ordinal++);points.add(marker);
      for(long chunk:chunks(point))chunks.computeIfAbsent(chunk,ignored->new HashSet<>()).add(marker);
    }
    if(!points.isEmpty())markers.put(road.id(),List.copyOf(points));
    var link=LaneTopology.metadata(road).link();
    if(link!=null){
      links.put(road.id(),link);
      references.computeIfAbsent(link.from(),ignored->new HashSet<>()).add(road.id());
      references.computeIfAbsent(link.to(),ignored->new HashSet<>()).add(road.id());
    }
  }
  void remove(UUID road){removeContents(road);order.remove(road);}
  private void removeContents(UUID road){
    for(var marker:markers.getOrDefault(road,List.of()))for(long chunk:chunks(marker.point())){
      var values=chunks.get(chunk);if(values!=null){values.remove(marker);if(values.isEmpty())chunks.remove(chunk);}
    }
    markers.remove(road);var link=links.remove(road);
    if(link!=null)for(var ref:List.of(link.from(),link.to())){
      var values=references.get(ref);if(values!=null){values.remove(road);if(values.isEmpty())references.remove(ref);}
    }
  }
  List<Marker> candidates(V origin,V direction,double range){
    var found=new HashSet<Marker>();
    // Index the actual marker bounds, including their cross-chunk overhang. A
    // road centerline/chunk index alone can miss a marker beside a chunk edge.
    for(long chunk:RoadIndex.rayChunks(origin,direction,range))found.addAll(chunks.getOrDefault(chunk,Set.of()));
    var result=new ArrayList<>(found);
    result.sort(Comparator.comparingLong((Marker p)->order.get(p.road())).thenComparingInt(Marker::ordinal));
    return result;
  }
  List<UUID> references(LanePoints.Ref ref){return references.getOrDefault(ref,Set.of()).stream().sorted().toList();}
  boolean referenced(LanePoints.Ref ref){return !references.getOrDefault(ref,Set.of()).isEmpty();}
  void restore(Collection<RoadIndex.Built> roads){clear();for(var road:roads)put(road.record);}
  void clear(){chunks.clear();markers.clear();links.clear();references.clear();order.clear();nextOrder=0;}
}
