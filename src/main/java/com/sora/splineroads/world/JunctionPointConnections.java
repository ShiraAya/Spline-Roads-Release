package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;

/** A marker junction and an attached-point junction share one reversible connection transaction. */
public final class JunctionPointConnections {
  static boolean saved(RoadData data,UUID id){return id!=null&&data.junctions.getOrDefault(id,new CompoundTag()).getBoolean("MixedMarker");}
  static UUID group(RoadData data,UUID link){return data.junctions.entrySet().stream().filter(e->e.getValue().getBoolean("MixedMarker")&&e.getValue().getUUID("MixedLink").equals(link)).map(Map.Entry::getKey).findFirst().orElse(null);}
  public static CompoundTag create(ServerLevel level,ServerPlayer player,RoadRecord main,UUID point,BlockPos marker,boolean mainFirst,Settings fallback){
    if(!SideHosts.attachable(main))throw new IllegalArgumentException("请选择独立普通主路附属点");
    var t=new CompoundTag();t.putBoolean("OneSided",true);t.putBoolean("MixedMarker",true);t.putUUID("Main",main.id());t.putUUID("AttachedPoint",point);t.putLong("Marker",marker.asLong());t.putBoolean("MainFirst",mainFirst);
    t.putUUID("MixedLink",UUID.nameUUIDFromBytes(("junction-marker:"+marker.asLong()+":"+main.id()+":"+point).getBytes(StandardCharsets.UTF_8)));
    t.put("OriginalMain",SideHosts.original(RoadData.get(level),main).save());t.put("Fallback",RoadRecord.writeSettings(fallback));return fresh(level,player,t);
  }
  public static CompoundTag payload(ServerLevel level,ServerPlayer player,UUID group){
    var t=RoadData.get(level).junctions.get(group);if(t==null||!t.getBoolean("MixedMarker"))throw new IllegalArgumentException("路口点连接已不存在");
    if(player!=null)RoadData.requireOwner(player,t.getUUID("Owner"));return fresh(level,player,t.copy());
  }
  private static CompoundTag fresh(ServerLevel level,ServerPlayer player,CompoundTag t){
    var data=RoadData.get(level);var original=RoadRecord.load(t.getCompound("OriginalMain"));var marker=BlockPos.of(t.getLong("Marker"));var node=RoadData.requireNode(level,marker,player);
    if(node.junctionSource==null)throw new IllegalArgumentException("所选路口点已经改变");JunctionPointTool.requireDifferent(level,marker,original);
    var pointId=t.getUUID("AttachedPoint");
    var point=original.settings().options().attachments().points().stream().filter(p->p.id().equals(pointId)&&!p.elevated()).findFirst().orElseThrow(()->new IllegalArgumentException("附属点已经改变"));
    V end=node.constructionNode().position(),at=RoadQueries.horizontal(original.mesh(),point.position()).sample().center(),mid=at.add(end).mul(.5);
    if(at.sub(end).horizontalLength()<4)throw new IllegalArgumentException("两连接点没有足够的道路空间");
    var seam=BlockPos.containing(mid.x(),mid.y(),mid.z());if(level.getBlockEntity(seam) instanceof NodeEntity)throw new IllegalArgumentException("连接中点已有端点，请调整连接位置");
    t.putLong("Branch",seam.asLong());t.put("MarkerNode",RoadRecord.writeNode(node.constructionNode()));t.put("BranchNode",RoadRecord.writeNode(new Node(mid,RoadPlanner.yaw(at.sub(end)),0)));
    t=OneSidedJunctions.fresh(level,player,t,original,RoadRecord.readNode(t.getCompound("BranchNode")));
    var graph=AutoJunctions.context(data,Set.of(marker,seam));t.put("MarkerGraph",graph);t.putInt("MarkerSignature",graph.hashCode());
    // Both previews use the same logical link and the same endpoint sections as commit.
    var settings=t.contains("ConnectionSettings")?RoadRecord.readSettings(t.getCompound("ConnectionSettings")):RoadRecord.readSettings(t.getCompound("Fallback"));
    AutoJunctions.previewComposite(graph,link(t,settings));return t;
  }
  private static RoadRecord link(CompoundTag t,Settings wanted){
    var spec=JunctionCodec.read(t.getCompound("Spec"));var settings=SideRoads.settings(wanted,spec.arms().get(2).external(),false);
    var marker=RoadRecord.readNode(t.getCompound("MarkerNode")).position();var seam=RoadRecord.readNode(t.getCompound("BranchNode")).position();boolean first=t.getBoolean("MainFirst");
    V a=first?seam:marker,b=first?marker:seam;double yaw=RoadPlanner.yaw(b.sub(a));double grade=(b.y()-a.y())/Math.max(1e-7,b.sub(a).horizontalLength());
    settings=new Settings(Mode.STRAIGHT,settings.style(),settings.width(),settings.thickness(),settings.tension(),settings.arcDegrees()).structure(settings.structure()).options(settings.options().route(spec.arms().get(2).external().options().routing().fit(false)).ends(RoadTransitions.Ends.NONE));
    return new RoadRecord(t.getUUID("MixedLink"),RoadRecord.load(t.getCompound("OriginalMain")).owner(),BlockPos.of(t.getLong(first?"Branch":"Marker")),BlockPos.of(t.getLong(first?"Marker":"Branch")),new Node(a,yaw,grade),new Node(b,yaw,grade),settings,false,4);
  }
  public static List<RoadRecord> preview(CompoundTag t,Settings settings){var next=link(t,settings);return AutoJunctions.previewComposite(t.getCompound("MarkerGraph"),next).roads();}
  static String build(ServerLevel level,ServerPlayer player,CompoundTag command,Settings roadSettings){
    try(var work=RoadWorkChunks.open(level)){
      var data=RoadData.get(level);boolean editing=command.hasUUID("Id");UUID group=editing?command.getUUID("Id"):UUID.randomUUID();
      var host=data.index.roads.get(command.getUUID("Main"));if(host==null)throw new IllegalArgumentException("附属点主路已经改变");
      var t=editing?payload(level,player,group):create(level,player,host.record,command.getUUID("AttachedPoint"),BlockPos.of(command.getLong("Marker")),command.getBoolean("MainFirst"),RoadRecord.readSettings(command.getCompound("Fallback")));
      if(t.getInt("MainSignature")!=command.getInt("MainSignature")||t.getInt("MarkerSignature")!=command.getInt("MarkerSignature"))throw new IllegalArgumentException("连接道路在预览后已经改变，请重新打开");
      if(roadSettings!=null){if(SideRoads.signature(t)!=command.getInt("Signature"))throw new IllegalArgumentException("连接设置已经改变，请重新打开");t.put("ConnectionSettings",RoadRecord.writeSettings(roadSettings));t=fresh(level,player,t);}
      var spec=roadSettings==null?OneSidedJunctions.validated(t,command):JunctionCodec.read(t.getCompound("Spec"));t.put("Spec",JunctionCodec.write(spec));OneSidedJunction.prepare(spec);
      var settings=t.contains("ConnectionSettings")?RoadRecord.readSettings(t.getCompound("ConnectionSettings")):RoadRecord.readSettings(t.getCompound("Fallback"));
      var next=link(t,settings);var change=AutoJunctions.compositeStreet(level,player,next);var original=RoadRecord.load(t.getCompound("OriginalMain"));
      var removed=new HashSet<UUID>();removed.add(original.id());if(editing)data.index.roads.values().stream().filter(r->group.equals(r.record.assembly())).forEach(r->removed.add(r.record.id()));
      var input=AutoJunctions.compositeInput(level,player,change,removed);removed.addAll(input.removed());var built=new ArrayList<>(input.built());
      var plan=JunctionPlanner.plan(spec);var center=BlockPos.containing(spec.center().x(),spec.center().y(),spec.center().z());var seam=BlockPos.of(t.getLong("Branch"));
      for(int i=0;i<plan.pieces().size();i++){
        var p=plan.pieces().get(i);if(p.arm()==-3){built.add(new RoadIndex.Built(SideHosts.retained(data,original,group,spec)));continue;}
        var m=p.mesh();UUID id=UUID.nameUUIDFromBytes((group+":marker-side:"+i).getBytes(StandardCharsets.UTF_8));
        built.add(new RoadIndex.Built(new RoadRecord(id,original.owner(),p.arm()==2?seam:center,center,new Node(m.first().center(),RoadPlanner.yaw(m.first().left().left().mul(-1)),0),new Node(m.last().center(),RoadPlanner.yaw(m.last().left().left().mul(-1)),0),m.settings(),false,4).junction(group,new JunctionPlanner.Ref(spec,i))));
      }
      var nodes=new HashSet<>(input.selected());nodes.addAll(List.of(original.a(),original.b(),seam,BlockPos.of(t.getLong("Marker"))));check(level,built,removed);
      var direct=new HashSet<>(AutoJunctions.directlyEditedRoads(change.draft(),Set.of(next.id()),Set.of()));built.stream().filter(r->group.equals(r.record.assembly())||r.record.id().equals(original.id())).forEach(r->direct.add(r.record.id()));
      data.replaceAssembly(level,player,built,removed,nodes,List.of(),input.editing(direct));AutoJunctions.compositeMetadata(data,change,input);
      t.putUUID("Id",group);t.putUUID("Owner",original.owner());data.junctions.put(group,t);data.setDirty();return "路口点连接已保存：完整路口与单边路口已同时更新";
    }
  }
  static void remove(ServerLevel level,ServerPlayer player,UUID group){
    try(var work=RoadWorkChunks.open(level)){
      var data=RoadData.get(level);var t=data.junctions.get(group);if(t==null||!t.getBoolean("MixedMarker"))throw new IllegalArgumentException("连接已经不存在");if(player!=null)RoadData.requireOwner(player,t.getUUID("Owner"));
      var saved=RoadRecord.load(t.getCompound("OriginalMain"));var live=data.index.roads.get(saved.id());if(live==null||!SideHosts.host(live.record))throw new IllegalArgumentException("附属点主路已改变，不能覆盖恢复");var original=SideHosts.original(data,live.record);
      var removed=new HashSet<UUID>();removed.add(original.id());data.index.roads.values().stream().filter(r->group.equals(r.record.assembly())).forEach(r->removed.add(r.record.id()));
      var change=AutoJunctions.withoutStreet(level,player,t.getUUID("MixedLink"));var input=AutoJunctions.compositeInput(level,player,change,removed);removed.addAll(input.removed());var built=new ArrayList<>(input.built());built.add(new RoadIndex.Built(SideHosts.retained(data,original,group,null)));
      var nodes=new HashSet<>(input.selected());nodes.addAll(List.of(original.a(),original.b(),BlockPos.of(t.getLong("Marker")),BlockPos.of(t.getLong("Branch"))));check(level,built,removed);
      data.replaceAssembly(level,player,built,removed,nodes,List.of(),input.editing(Set.of(original.id(),t.getUUID("MixedLink"))));AutoJunctions.compositeMetadata(data,change,input);data.junctions.remove(group);data.setDirty();
    }
  }
  private static void check(ServerLevel level,List<RoadIndex.Built> built,Set<UUID> removed){var data=RoadData.get(level);for(var next:built)for(var old:data.index.roads.values())if(!removed.contains(old.record.id())&&RoadIndex.overlapXZ(next.mesh,old.mesh,1))Interchanges.checkExternal(next.mesh,old.mesh,data.index.roads.containsKey(next.record.id())?data.index.roads.get(next.record.id()).mesh:null);}
  private JunctionPointConnections(){}
}
