package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/** Match bed edges at real MTR nodes without changing the source rail alignment. */
final class RailbedJoints {
  static void normalize(RoadIndex index,List<RoadIndex.Built> built,Set<UUID> removed,ServerPlayer player){
    var affected=new HashSet<BlockPos>();var all=new LinkedHashMap<UUID,RoadIndex.Built>(index.roads);
    for(var id:removed){var old=all.remove(id);if(old!=null&&old.record.railway()){affected.add(old.record.a());affected.add(old.record.b());}}
    for(var r:built){all.put(r.record.id(),r);if(r.record.railway()){affected.add(r.record.a());affected.add(r.record.b());}}
    if(affected.isEmpty())return;
    var joints=new HashMap<BlockPos,List<RoadIndex.Built>>();
    for(var r:all.values())if(r.record.settings().options().railbed().active())for(var p:List.of(r.record.a(),r.record.b()))joints.computeIfAbsent(p,k->new ArrayList<>()).add(r);
    for(var r:all.values()){
      var record=r.record;var profile=record.settings().options().railbed();
      if(!profile.active()||!affected.contains(record.a())&&!affected.contains(record.b()))continue;
      var base=RailbedGeometry.plan(profile.paths(),profile.requestedWidth(),record.settings().structure()).mesh();
      double a=width(r,record.a(),joints),b=width(r,record.b(),joints),blend=Math.min(16,base.length()/2);
      var samples=new ArrayList<Sample>();
      for(var at:base.samples()){
        double from=Math.max(0,1-at.distance()/blend),to=Math.max(0,1-(base.length()-at.distance())/blend);
        double half=at.halfWidth()+Math.max((a/2-at.halfWidth())*from,(b/2-at.halfWidth())*to);
        samples.add(new Sample(at.center(),at.left(),at.distance(),half));
      }
      if(samples.equals(record.alignment()))continue;
      if(player!=null)RoadData.requireOwner(player,record.owner());
      var next=new RoadIndex.Built(record.alignment(record.assembly(),RoadRibbon.mesh(samples,record.settings())).structures(List.of()));
      int slot=-1;for(int i=0;i<built.size();i++)if(built.get(i).record.id().equals(record.id()))slot=i;
      if(slot<0)built.add(next);else built.set(slot,next);
      if(index.roads.containsKey(record.id()))removed.add(record.id());
    }
  }
  private static double width(RoadIndex.Built road,BlockPos node,Map<BlockPos,List<RoadIndex.Built>> joints){
    double width=road.record.settings().width();var links=joints.getOrDefault(node,List.of());
    if(links.size()!=2)return width;
    var own=endpoints(road,node);var at=road.record.a().equals(node)?road.mesh.first():road.mesh.last();
    for(var other:links){if(other==road||!own.equals(endpoints(other,node)))continue;
      var end=other.record.a().equals(node)?other.mesh.first():other.mesh.last();
      if(at.center().distance(end.center())<1e-6)width=Math.max(width,other.record.settings().width());
    }return width;
  }
  private static Set<RailbedPath.Node> endpoints(RoadIndex.Built road,BlockPos node){
    var result=new HashSet<RailbedPath.Node>();
    for(var path:road.record.settings().options().railbed().paths()){
      var start=path.segments().get(0).from();var end=path.segments().get(path.segments().size()-1).to();
      // Neighbor rails may have been read in the opposite direction.
      result.add(start.center().distance(RailbedCodec.node(node).center())<=end.center().distance(RailbedCodec.node(node).center())?start:end);
    }return result;
  }
  private RailbedJoints(){}
}
