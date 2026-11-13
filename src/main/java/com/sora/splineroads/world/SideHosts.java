package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;

/** Independent side junctions share one original host and one clipped pavement record. */
final class SideHosts {
  record Use(UUID group,String originalKey,String specKey,UUID point,RoadRecord original,JunctionSpec spec){}
  static boolean host(RoadRecord r){return r.junction()!=null&&OneSidedJunction.retainedPiece(r.junction());}
  static boolean attachable(RoadRecord r){return AutoJunctions.ordinary(r)||host(r);}
  static RoadIndex.Built hostAt(RoadData data,RoadIndex.Built picked,com.sora.splineroads.core.RoadGeometry.V hit){
    if(picked==null||attachable(picked.record)||picked.record.junction()==null||picked.record.junction().spec().oneSide()==null)return picked;
    var spec=picked.record.junction().spec();var q=RoadQueries.horizontal(spec.oneSide().road(),hit);
    if(q.horizontalDistance()>q.sample().halfWidth()+.1)return picked;
    var receipt=data.junctions.get(picked.record.assembly());if(receipt==null)return picked;
    String key=JunctionCodec.read(receipt.getCompound("Spec")).center().equals(spec.center())?"OriginalMain":"OriginalMainB";
    var original=receipt.getCompound(key);return original.hasUUID("Id")?data.index.roads.getOrDefault(original.getUUID("Id"),picked):picked;
  }
  static List<Use> uses(RoadData data,UUID road){
    return uses(data,road,Map.of());
  }
  static List<Use> uses(RoadData data,UUID road,Map<UUID,CompoundTag> overrides){
    var result=new ArrayList<Use>();
    for(var entry:data.junctions.entrySet()){
      var t=overrides.getOrDefault(entry.getKey(),entry.getValue());if(!t.getBoolean("OneSided"))continue;
      for(int side=0;side<(t.getBoolean("Paired")?2:1);side++){
        String key=side==0?"OriginalMain":"OriginalMainB",specKey=side==0?"Spec":"PairSpec";
        var saved=t.getCompound(key);if(!saved.hasUUID("Id")||!saved.getUUID("Id").equals(road))continue;
        result.add(new Use(entry.getKey(),key,specKey,t.getUUID(side==0?"AttachedPoint":"AttachedPointB"),RoadRecord.load(saved),JunctionCodec.read(t.getCompound(specKey))));
      }
    }
    result.sort(Comparator.comparing(Use::group));return result;
  }
  static RoadRecord original(RoadData data,RoadRecord live){
    if(!host(live))return live;var uses=uses(data,live.id());
    if(uses.isEmpty())throw new IllegalArgumentException("附属点主路记录不完整，请重新加载存档");
    return uses.get(0).original().withAttachments(live.settings().options().attachments());
  }
  static void available(RoadData data,RoadRecord original,UUID point,JunctionSpec proposed,UUID editing){
    for(var use:uses(data,original.id())){
      if(use.group().equals(editing))continue;
      if(use.point().equals(point)&&use.spec().oneSide().side()==proposed.oneSide().side())
        throw new IllegalArgumentException("此附属点的这一侧已有连接道路；主路两侧各可连接一条");
      var a=use.spec().oneSide();var b=proposed.oneSide();
      if(a.side()==b.side()&&Math.min(a.to(),b.to())>Math.max(a.from(),b.from())+1e-7)
        throw new IllegalArgumentException("新连接与已有单边路口接入范围重叠；请移动道路端点或另选附属点，原附属点保留");
    }
  }
  static RoadRecord retained(RoadData data,RoadRecord original,UUID replacing,JunctionSpec proposed){
    var all=new ArrayList<>(uses(data,original.id()).stream().filter(u->!u.group().equals(replacing)).toList());
    if(proposed!=null)all.add(new Use(replacing,"","",null,original,proposed));
    return retained(original,all);
  }
  static RoadRecord rebased(RoadData data,RoadRecord original,Map<UUID,CompoundTag> receipts){
    return retained(original,new ArrayList<>(uses(data,original.id(),receipts)));
  }
  private static RoadRecord retained(RoadRecord original,List<Use> all){
    all.sort(Comparator.comparing(Use::group));if(all.isEmpty())return original;
    var owner=all.get(0);var spec=owner.spec();
    // The common source includes all markers added since the first connection.
    var source=spec.oneSide();spec=spec.oneSide(new OneSidedJunction.Source(original.mesh(),original.structures(),source.from(),source.to(),source.side()));
    var cuts=all.stream().map(u->u.spec().oneSide()).map(s->new OneSidedJunction.Cut(s.from(),s.to(),s.side())).toList();
    var plan=JunctionPlanner.plan(spec);return original.junction(owner.group(),new JunctionPlanner.Ref(spec,plan.pieces().size()-1,54,cuts));
  }
  static void remove(ServerLevel level,ServerPlayer player,UUID group){
    if(JunctionPointConnections.saved(RoadData.get(level),group)){JunctionPointConnections.remove(level,player,group);return;}
    var data=RoadData.get(level);var t=data.junctions.get(group);if(t==null)throw new IllegalArgumentException("连接已不存在");
    if(player!=null)RoadData.requireOwner(player,t.getUUID("Owner"));
    var removed=new HashSet<UUID>();var nodes=new HashSet<BlockPos>();var built=new ArrayList<RoadIndex.Built>();
    data.index.roads.values().stream().filter(r->group.equals(r.record.assembly())).forEach(r->removed.add(r.record.id()));
    for(String key:t.getBoolean("Paired")?List.of("OriginalMain","OriginalMainB"):List.of("OriginalMain")){
      var saved=RoadRecord.load(t.getCompound(key));var live=data.index.roads.get(saved.id());
      if(live==null||!host(live.record))throw new IllegalArgumentException("保留主路已变化，不能覆盖恢复");
      var original=original(data,live.record);removed.add(original.id());nodes.add(original.a());nodes.add(original.b());
      built.add(new RoadIndex.Built(retained(data,original,group,null)));
    }
    nodes.add(BlockPos.of(t.getLong("Branch")));data.replaceAssembly(level,player,built,removed,nodes);data.junctions.remove(group);data.setDirty();
  }
  static void updatePoints(ServerLevel level,RoadRecord host,RoadAttachments.Data points){
    var data=RoadData.get(level);var uses=uses(data,host.id());
    for(var use:uses)if(points.points().stream().noneMatch(p->p.id().equals(use.point())))throw new IllegalArgumentException("此附属点仍有连接道路，请先删除该点的连接");
    var original=original(data,host).withAttachments(points);var receipts=new HashMap<UUID,CompoundTag>();var replacements=new HashMap<UUID,RoadRecord>();
    for(var use:uses){
      var t=receipts.computeIfAbsent(use.group(),id->data.junctions.get(id).copy());var source=use.spec().oneSide();
      var spec=use.spec().oneSide(new OneSidedJunction.Source(original.mesh(),original.structures(),source.from(),source.to(),source.side()));
      t.put(use.originalKey(),original.save());t.put(use.specKey(),JunctionCodec.write(spec));t.putInt(use.originalKey().equals("OriginalMain")?"MainSignature":"MainSignatureB",original.save().hashCode());
      for(var member:data.index.roads.values())if(use.group().equals(member.record.assembly())&&member.record.junction()!=null&&member.record.junction().spec().center().equals(use.spec().center())&&!host(member.record)){
        var r=member.record;replacements.put(r.id(),r.junction(use.group(),new JunctionPlanner.Ref(spec,r.junction().piece(),r.junction().geometryVersion())).structures(r.structures()));
      }
    }
    // Metadata changes never run a construction transaction or remove existing points.
    var next=retained(data,original,null,null).structures(host.structures());replacements.put(host.id(),next);data.junctions.putAll(receipts);
    replacements.values().forEach(r->{data.index.put(RoadIndex.Built.loading(r));com.sora.splineroads.net.RoadNetwork.broadcastRoad(level,r);});
    if(data.streets.containsKey(host.id()))data.streets.put(host.id(),data.streets.get(host.id()).withAttachments(points));data.setDirty();
  }
  private SideHosts(){}
}
