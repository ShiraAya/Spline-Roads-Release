package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Replan only derived railway supports inside the ordinary road transaction. */
final class RailbedRoadEdits {
 static boolean support(Part p){return p.material()==Material.CONCRETE&&p.model().isEmpty();}
 static Set<Long> cells(RoadRecord road){
  var result=new HashSet<Long>();for(var c:RoadRaster.structures(road.structures().stream().filter(RailbedRoadEdits::support).toList(),null).keySet())result.add(BlockPos.asLong(c.x(),c.y(),c.z()));return result;
 }
 static void enlist(RoadData data,ServerLevel level,List<RoadIndex.Built> built,Set<UUID> removed,List<RoadIndex.Built> requested){
  var context=new ArrayList<RoadIndex.Built>();for(var old:data.index.roads.values())if(!removed.contains(old.record.id()))context.add(old);context.addAll(built);
  for(var entry:data.railbeds.entries.values()){
   if(!entry.road.legacyRailway())continue;
   var mesh=entry.road.mesh();if(entry.mode==Structure.TUNNEL||requested.stream().noneMatch(r->affected(mesh,r.mesh)))continue;
   if(data.railbeds.pending.containsKey(entry.road.id()))throw new IllegalArgumentException("相关铁路路基仍在施工，请等待完成");
   var others=context.stream().filter(r->!r.record.id().equals(entry.road.id())&&RoadIndex.overlapXZ(mesh,r.mesh,16)).toList();
   var rails=data.railbeds.entries.values().stream().filter(e->RoadIndex.overlapXZ(mesh,e.road.mesh(),16)).flatMap(e->e.paths.stream()).toList();
   var ground=RailbedBuildQueue.ground(level,data,entry.original,others,rails);
   var parts=new ArrayList<>(entry.road.structures().stream().filter(p->!support(p)).toList());
   var supports=new Mesh(mesh.samples(),mesh.settings().structure(entry.mode),mesh.min(),mesh.max(),mesh.length(),mesh.closed());
   // The existing road planner already tries shifts and portals and omits a
   // station with no legal support. Do not add a separate railway policy.
   parts.addAll(RoadStructures.supports(supports,ground,entry.road.furniturePhase()==null?RoadFurniture.Phase.DEFAULT:entry.road.furniturePhase()));
   if(parts.equals(entry.road.structures()))continue;
   var next=RoadIndex.Built.loading(entry.road.structures(parts));built.add(next);removed.add(entry.road.id());
   context.removeIf(r->r.record.id().equals(entry.road.id()));context.add(next);
  }
 }
 private static boolean affected(Mesh rail,Mesh road){
  if(!RoadIndex.overlapXZ(rail,road,16))return false;
  for(var at:rail.samples()){var q=RoadQueries.horizontal(road,at.center());double drop=at.center().y()-q.sample().center().y();if(drop>3&&drop<RoadStructures.MAX_DROP&&q.horizontalDistance()<at.halfWidth()+q.sample().halfWidth()+16)return true;}return false;
 }
 static void sync(RoadData data,ServerLevel level,List<RoadIndex.Built> built){
  for(var road:built)if(road.record.legacyRailway()){
   var old=data.railbeds.entries.get(road.record.id());var next=new RailbedStore.Entry(road.record,old.paths,old.width,old.mode,old.options);next.original.putAll(old.original);next.written.putAll(old.written);
   var touched=cells(old.road);touched.addAll(cells(road.record));
   for(long key:touched){var pos=BlockPos.of(key);if(!road.boxes(pos).isEmpty()){
     next.original.putIfAbsent(key,data.railbedOriginal(key));next.written.put(key,level.getBlockState(pos));
     if(data.index.at(key).stream().noneMatch(id->!data.index.roads.get(id).record.legacyRailway()))data.railbedOwns(key);
   }else if(!road.clearanceAt(pos)){next.original.remove(key);next.written.remove(key);}}
   data.railbeds.entries.put(road.record.id(),next);
  }
 }
 private RailbedRoadEdits(){}
}
