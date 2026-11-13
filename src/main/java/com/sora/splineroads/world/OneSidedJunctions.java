package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.JunctionSpec.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;

/** Attached-point/endpoint connection with separate road and junction editors. */
public final class OneSidedJunctions {
  static Settings preference(CompoundTag tool){
    var s=tool.contains("Settings")?RoadRecord.readSettings(tool.getCompound("Settings")):new Settings(Mode.STRAIGHT,Style.O2_YELLOW,9,1,.35,90);
    return s.options(s.options().lanePoints(LanePoints.Data.EMPTY).attachments(RoadAttachments.Data.EMPTY).ends(RoadTransitions.Ends.NONE));
  }
  static boolean selectedEndpoint(ServerPlayer player,CompoundTag tool,BlockPos endpoint){
    if(!tool.hasUUID("SideMain"))return false;
    if(!tool.getString("Dimension").equals(player.serverLevel().dimension().location().toString())){tool.remove("ElevatedSelection");tool.remove("SideSelection");tool.remove("SideMain");tool.remove("SidePoint");return false;}
    var host=RoadData.get(player.serverLevel()).index.roads.get(tool.getUUID("SideMain"));
    if(host!=null&&host.record.settings().options().attachments().points().stream().anyMatch(p->p.id().equals(tool.getUUID("SidePoint"))&&p.elevated())){
      ElevatedAccessRoads.open(player,tool,ElevatedAccessRoads.payload(player.serverLevel(),player,host.record.id(),tool.getUUID("SidePoint"),endpoint,true,null));return true;
    }
    openSelection(player,tool,create(player.serverLevel(),player,tool.getUUID("SideMain"),tool.getUUID("SidePoint"),endpoint,true,preference(tool)));return true;
  }
  static void selectedAttached(ServerPlayer player,CompoundTag tool,RoadIndex.Built road,RoadAttachments.Point point){
    if(point.elevated()){ElevatedAccessRoads.select(player,tool,road,point);return;}
    RoadData.requireOwner(player,road.record.owner());String dimension=player.serverLevel().dimension().location().toString();
    if(tool.hasUUID("SideMain")){var selected=RoadData.get(player.serverLevel()).index.roads.get(tool.getUUID("SideMain"));if(selected!=null&&selected.record.settings().options().attachments().points().stream().anyMatch(p->p.id().equals(tool.getUUID("SidePoint"))&&p.elevated()))throw new IllegalArgumentException("高架出入点须连接已有高架的尽头端点");}
    if(tool.hasUUID("SideMain")&&dimension.equals(tool.getString("Dimension"))){openSelection(player,tool,PairedSideJunctions.create(player.serverLevel(),player,tool.getUUID("SideMain"),tool.getUUID("SidePoint"),road.record.id(),point.id(),preference(tool)));return;}
    if(tool.contains("Start")&&dimension.equals(tool.getString("Dimension"))){
      openSelection(player,tool,create(player.serverLevel(),player,road.record.id(),point.id(),BlockPos.of(tool.getLong("Start")),false,preference(tool)));return;
    }
    tool.remove("ElevatedSelection");tool.remove("SideSelection");tool.remove("Start");tool.remove(CurveControlNodes.KEY);tool.putUUID("SideMain",road.record.id());tool.putUUID("SidePoint",point.id());tool.putString("Dimension",dimension);
    player.displayClientMessage(net.minecraft.network.chat.Component.literal("已选附属点 A，请右键端点或另一主路附属点 B；单行连接按 A→B"),true);
  }
  private static CompoundTag selection(CompoundTag command){var t=new CompoundTag();for(String key:List.of("Main","AttachedPoint","Branch","MainFirst","Paired","MainB","AttachedPointB","MixedMarker","Marker","MixedLink"))if(command.contains(key))t.put(key,command.get(key).copy());return t;}
  private static void openSelection(ServerPlayer player,CompoundTag tool,CompoundTag command){tool.put("SideSelection",selection(command));com.sora.splineroads.net.RoadNetwork.open(player,SideRoads.payload(command));}
  public static String buildRoadSelected(ServerLevel level,ServerPlayer player,CompoundTag command,net.minecraft.world.item.ItemStack stack){
    var tool=stack.getOrCreateTag();
    if(!command.hasUUID("Id")&&(!level.dimension().location().toString().equals(tool.getString("Dimension"))||!tool.contains("SideSelection")||!tool.getCompound("SideSelection").equals(selection(command))))throw new IllegalArgumentException("连接选点已失效，请重新选择附属点和道路端点");
    String result=build(level,player,command,RoadRecord.readSettings(command.getCompound("Settings")));
    for(String key:List.of("SideSelection","SideMain","SidePoint","Start"))tool.remove(key);
    return result;
  }
  public static String buildSelected(ServerLevel level,ServerPlayer player,CompoundTag command,net.minecraft.world.item.ItemStack stack){
    var tool=stack.getOrCreateTag();
    if(!command.hasUUID("Id")&&(!level.dimension().location().toString().equals(tool.getString("Dimension"))||!tool.contains("SideSelection")||!tool.getCompound("SideSelection").equals(selection(command))))throw new IllegalArgumentException("单边路口选点已失效，请重新选择附属点和支路端点");
    String result=build(level,player,command);
    for(String key:List.of("SideSelection","SideMain","SidePoint","Start"))tool.remove(key);
    return result;
  }
  static boolean saved(RoadData data,UUID group){return group!=null&&data.junctions.getOrDefault(group,new CompoundTag()).getBoolean("OneSided");}
  public static CompoundTag create(ServerLevel level,ServerPlayer player,UUID mainId,UUID pointId,BlockPos branch,boolean mainFirst,Settings fallback){
    var main=RoadData.get(level).index.roads.get(mainId);if(main==null)throw new IllegalArgumentException("主路已变化，请重新选择附属点");
    JunctionPointTool.requireDifferent(level,branch,main.record);
    if(!SideHosts.attachable(main.record))throw new IllegalArgumentException("请选择普通主路路段的附属点；不能直接占用路口铺装或立交组成段");
    if(level.getBlockEntity(branch) instanceof NodeEntity node&&node.junctionSource!=null)return JunctionPointConnections.create(level,player,main.record,pointId,branch,mainFirst,fallback);
    var t=new CompoundTag();t.putBoolean("OneSided",true);t.putUUID("Main",mainId);t.putUUID("AttachedPoint",pointId);t.putLong("Branch",branch.asLong());t.putBoolean("MainFirst",mainFirst);t.put("Fallback",RoadRecord.writeSettings(fallback));
    var original=SideHosts.original(RoadData.get(level),main.record);t.put("OriginalMain",original.save());return fresh(level,player,t,original);
  }
  public static CompoundTag payload(ServerLevel level,ServerPlayer player,UUID group){
    if(JunctionPointConnections.saved(RoadData.get(level),group))return JunctionPointConnections.payload(level,player,group);
    if(PairedSideJunctions.saved(RoadData.get(level),group))return PairedSideJunctions.payload(level,player,group);
    var saved=RoadData.get(level).junctions.get(group);if(saved==null||!saved.getBoolean("OneSided"))throw new IllegalArgumentException("单边路口已经不存在");
    if(player!=null)RoadData.requireOwner(player,saved.getUUID("Owner"));
    return fresh(level,player,saved.copy(),RoadRecord.load(saved.getCompound("OriginalMain")));
  }
  static CompoundTag fresh(ServerLevel level,ServerPlayer player,CompoundTag t,RoadRecord original){return fresh(level,player,t,original,null);}
  static CompoundTag fresh(ServerLevel level,ServerPlayer player,CompoundTag t,RoadRecord original,Node virtual){
    if(player!=null)RoadData.requireOwner(player,original.owner());
    var mesh=original.mesh();var options=original.settings().options();
    if(!RoadProfile.catalog(original.settings().style()).twoWay()||options.leftTraffic())throw new IllegalArgumentException("单边路口主路须为右侧通行的双向普通道路");
    if(original.settings().mode()==Mode.RING||!options.lanePoints().points(List.of()).equals(LanePoints.Data.EMPTY)||options.lanePoints().points().stream().anyMatch(p->!p.automatic()||!LaneTopology.references(RoadData.get(level),LanePoints.Ref.lane(original.id(),p.id())).isEmpty())||!options.routing().controls().isEmpty()||options.attachments().points().stream().anyMatch(RoadAttachments.Point::controlled))throw new IllegalArgumentException("主路含匝道、车道改造或曲线控制点，请在独立普通路段建立单边路口");
    var point=options.attachments().points().stream().filter(p->p.id().equals(t.getUUID("AttachedPoint"))).findFirst().orElseThrow(()->new IllegalArgumentException("主路附属点已改变，请重新选择"));
    var at=RoadQueries.horizontal(mesh,point.position()).sample();BlockPos branch=BlockPos.of(t.getLong("Branch"));
    if(branch.equals(original.a())||branch.equals(original.b()))throw new IllegalArgumentException("支路端点不能是主路自身端点");
    if(virtual==null)JunctionPointTool.requireDifferent(level,branch,original);
    Node node=virtual==null?RoadData.requireNode(level,branch,player).constructionNode():virtual;V inward=at.center().sub(node.position()).horizontalUnit();
    int side=node.position().sub(at.center()).dot(at.left())>0?1:-1;
    var data=RoadData.get(level);UUID group=t.hasUUID("Id")?t.getUUID("Id"):null;
    var links=(virtual==null?data.index.atNode(branch):Set.<UUID>of()).stream().map(data.index.roads::get).filter(r->!Objects.equals(group,r.record.assembly())||group==null).toList();
    if(links.size()>1)throw new IllegalArgumentException("支路端点连接多条道路，请选只有一条外接路的端点");
    Settings profile=RoadRecord.readSettings(t.getCompound("Fallback"));boolean incoming=!t.getBoolean("MainFirst");
    if(!links.isEmpty()){
      var link=links.get(0);if(!AutoJunctions.ordinary(link.record))throw new IllegalArgumentException("支路须为普通道路端点");if(player!=null)RoadData.requireOwner(player,link.record.owner());
      boolean first=branch.equals(link.record.a());var sample=first?link.mesh.first():link.mesh.last();inward=sample.left().left().mul(first?1:-1);
      profile=RoadEndpointSections.orient(link.record.settings().taper(sample.halfWidth()*2,sample.halfWidth()*2),first);
      if(!RoadProfile.catalog(profile.style()).twoWay()&&incoming==first)throw new IllegalArgumentException("所选 A→B 与现有单行支路方向冲突，请按行驶方向重新选择");
      incoming=!first;node=new Node(sample.center(),RoadPlanner.yaw(inward),(first?-1:1)*(first?link.record.start():link.record.end()).grade());
      t.putUUID("BranchRoad",link.record.id());t.putInt("BranchSignature",link.record.header().hashCode());
    }else node=new Node(node.position(),RoadPlanner.yaw(inward),node.grade());
    if(RoadProfile.catalog(profile.style()).type()!=RoadProfile.Type.ORDINARY||profile.options().leftTraffic())throw new IllegalArgumentException("支路须为右侧通行的普通道路");
    profile=section(profile);
    if(t.contains("ConnectionSettings")){
      profile=SideRoads.settings(RoadRecord.readSettings(t.getCompound("ConnectionSettings")),profile,!links.isEmpty());
      incoming=!t.getBoolean("MainFirst");
    }
    JunctionSpec previous=t.contains("Spec")?JunctionCodec.read(t.getCompound("Spec")):null;
    double corner=previous==null?6:previous.cornerRadius();double span=Math.max(48,mesh.settings().width()+profile.width()+corner+16);
    double from=previous==null?at.distance()-span:previous.oneSide().from(),to=previous==null?at.distance()+span:previous.oneSide().to();
    if(from<2||to>mesh.length()-2)throw new IllegalArgumentException("附属点两侧主路至少各预留 "+(int)Math.ceil(span+2)+" 格，请移动附属点或延长主路");
    var protectedRanges=RoadSplitGeometry.protection(original);RoadSplitGeometry.requireClear(protectedRanges,from,from);RoadSplitGeometry.requireClear(protectedRanges,to,to);
    var arms=new ArrayList<Arm>();
    for(int i=0;i<2;i++){
      var port=RoadSlicing.at(mesh,i==0?from:to);V f=port.left().left().mul(i==0?-1:1);
      var section=section(RoadEndpointSections.orient(original.settings().taper(port.halfWidth()*2,port.halfWidth()*2),i==1));
      double d=.1;var before=RoadSlicing.at(mesh,Math.max(0,port.distance()-d));var after=RoadSlicing.at(mesh,Math.min(mesh.length(),port.distance()+d));double grade=(after.center().y()-before.center().y())/Math.max(1e-7,after.center().sub(before.center()).horizontalLength())*(i==0?1:-1);
      arms.add(JunctionSpec.arm(new Node(port.center(),RoadPlanner.yaw(f),grade),f,section,true,i).attached(true).crossing(false).signs(i==(side>0?0:1),false));
    }
    arms.add(JunctionSpec.arm(node,inward,profile,incoming,2).attached(!links.isEmpty()).signs(false,true));
    if(previous!=null)for(int i=0;i<3;i++){
      var old=previous.arms().get(i);var a=arms.get(i);
      if((i!=2||!t.contains("ConnectionSettings"))&&(a.incoming()!=old.incoming()||a.outgoing()!=old.outgoing()||a.width()!=old.width()))throw new IllegalArgumentException("接入道路断面已变化，请重新建立单边路口");
      var lanes=new ArrayList<Lane>();for(int lane=0;lane<a.incoming();lane++)lanes.add(lane<old.lanes().size()?old.lanes().get(lane):Lane.AUTO);
      arms.set(i,new Arm(a.endpoint(),a.inward(),a.external(),a.incoming(),a.outgoing(),a.width(),a.median(),a.medianKind(),a.cycleWidth(),a.curbWidth(),i==2&&old.crosswalk(),old.crossingWidth(),old.crossingSetback(),old.stopGap(),old.phase(),lanes,a.attached(),old.advanceSign(),old.nameSign(),false));
    }
    var source=new OneSidedJunction.Source(mesh,original.structures(),from,to,side);
    var spec=previous==null?new JunctionSpec(at.center(),Kind.INTERSECTION,false,corner,12,1,4,original.settings().thickness(),Control.NONE,20,3,1,0,true,true,arms):new JunctionSpec(at.center(),Kind.INTERSECTION,false,corner,12,1,4,original.settings().thickness(),previous.control(),previous.greenSeconds(),previous.yellowSeconds(),previous.allRedSeconds(),previous.timeOffset(),previous.guides(),true,arms);
    spec=spec.oneSide(source).supportedCrossings();OneSidedJunction.prepare(spec);
    SideHosts.available(data,original,point.id(),spec,group);
    t.putString("Kind","junction");t.put("Spec",JunctionCodec.write(spec));t.putInt("MainSignature",original.save().hashCode());
    t.putLongArray("Points",new long[]{original.a().asLong(),original.b().asLong(),branch.asLong()});t.putInt("SelectedArm",2);return t;
  }
  public static String build(ServerLevel level,ServerPlayer player,CompoundTag command){
    return build(level,player,command,null);
  }
  private static String build(ServerLevel level,ServerPlayer player,CompoundTag command,Settings roadSettings){
    if(command.getBoolean("MixedMarker")||command.hasUUID("Id")&&JunctionPointConnections.saved(RoadData.get(level),command.getUUID("Id")))return JunctionPointConnections.build(level,player,command,roadSettings);
    if(!command.hasUUID("Id")&&!command.getBoolean("MixedMarker")&&level.getBlockEntity(BlockPos.of(command.getLong("Branch"))) instanceof NodeEntity marker&&marker.junctionSource!=null)throw new IllegalArgumentException("请重新选择路口点与附属点");
    if(command.getBoolean("Paired")||command.hasUUID("Id")&&PairedSideJunctions.saved(RoadData.get(level),command.getUUID("Id")))return PairedSideJunctions.build(level,player,command,roadSettings);
    try(var work=RoadWorkChunks.open(level)){
      var data=RoadData.get(level);UUID group=command.hasUUID("Id")?command.getUUID("Id"):UUID.randomUUID();boolean editing=command.hasUUID("Id");
      var fresh=editing?payload(level,player,group):create(level,player,command.getUUID("Main"),command.getUUID("AttachedPoint"),BlockPos.of(command.getLong("Branch")),command.getBoolean("MainFirst"),RoadRecord.readSettings(command.getCompound("Fallback")));
      if(fresh.getInt("MainSignature")!=command.getInt("MainSignature")||fresh.getInt("BranchSignature")!=command.getInt("BranchSignature"))throw new IllegalArgumentException("道路在预览后发生变化，请重新打开单边路口");
      if(roadSettings!=null){
        if(SideRoads.signature(fresh)!=command.getInt("Signature"))throw new IllegalArgumentException("连接道路或路口已变化，请重新打开道路编辑器");
        fresh.put("ConnectionSettings",RoadRecord.writeSettings(roadSettings));
        fresh=fresh(level,player,fresh,RoadRecord.load(fresh.getCompound("OriginalMain")));
      }
      var requested=roadSettings==null?validated(fresh,command):JunctionCodec.read(fresh.getCompound("Spec"));
      // Rebuild from the authoritative original while retaining the previewed ports.
      fresh.put("Spec",JunctionCodec.write(requested));var original=RoadRecord.load(fresh.getCompound("OriginalMain"));fresh=fresh(level,player,fresh,original);requested=JunctionCodec.read(fresh.getCompound("Spec"));
      var plan=JunctionPlanner.plan(requested);var removed=new HashSet<UUID>();removed.add(original.id());
      if(editing)data.index.roads.values().stream().filter(r->group.equals(r.record.assembly())).forEach(r->removed.add(r.record.id()));
      var built=new ArrayList<RoadIndex.Built>();var center=BlockPos.containing(requested.center().x(),requested.center().y(),requested.center().z());
      for(int i=0;i<plan.pieces().size();i++){
        var p=plan.pieces().get(i);boolean retained=p.arm()==-3;var mesh=p.mesh();UUID id=retained?original.id():UUID.nameUUIDFromBytes((group+":one-side:"+i).getBytes(StandardCharsets.UTF_8));
        if(retained){built.add(new RoadIndex.Built(SideHosts.retained(data,original,group,requested)));continue;}
        var a=retained?original.start():new Node(mesh.first().center(),RoadPlanner.yaw(mesh.first().left().left().mul(-1)),0);var b=retained?original.end():new Node(mesh.last().center(),RoadPlanner.yaw(mesh.last().left().left().mul(-1)),0);
        BlockPos posA=retained?original.a():p.arm()==2?BlockPos.of(fresh.getLong("Branch")):center,posB=retained?original.b():center;
        built.add(new RoadIndex.Built(new RoadRecord(id,original.owner(),posA,posB,a,b,retained?original.settings():mesh.settings(),false,4).junction(group,new JunctionPlanner.Ref(requested,i))));
      }
      for(var proposed:built)for(var old:data.index.roads.values())if(!removed.contains(old.record.id())&&RoadIndex.overlapXZ(proposed.mesh,old.mesh,1))Interchanges.checkExternal(proposed.mesh,old.mesh,null);
      var nodes=new HashSet<BlockPos>(List.of(original.a(),original.b(),BlockPos.of(fresh.getLong("Branch"))));data.replaceAssembly(level,player,built,removed,nodes);
      fresh.putUUID("Id",group);fresh.putUUID("Owner",original.owner());data.junctions.put(group,fresh);data.setDirty();return "单边路口已保存；中央隔离带和对向道路保持原状";
    }
  }
  public static void remove(ServerLevel level,ServerPlayer player,UUID group){
    SideHosts.remove(level,player,group);
  }
  static JunctionSpec validated(CompoundTag fresh,CompoundTag command){
    var source=JunctionCodec.read(fresh.getCompound("Spec"));var request=command.getCompound("Spec").copy();if(!request.contains("OneSide"))request.put("OneSide",fresh.getCompound("Spec").getCompound("OneSide").copy());var requested=JunctionCodec.read(request);
      if(!source.center().equals(requested.center())||requested.arms().size()!=3||!source.oneSide().equals(requested.oneSide())||requested.leftTraffic()||requested.thickness()!=source.thickness())throw new IllegalArgumentException("主路及保护区参数已变化，请重新预览");
      for(int i=0;i<3;i++){var a=source.arms().get(i);var b=requested.arms().get(i);if(!a.endpoint().equals(b.endpoint())||!a.inward().equals(b.inward())||!a.external().equals(b.external())||a.width()!=b.width()||a.incoming()!=b.incoming()||a.outgoing()!=b.outgoing()||a.median()!=b.median()||a.medianKind()!=b.medianKind()||a.cycleWidth()!=b.cycleWidth()||a.curbWidth()!=b.curbWidth()||a.attached()!=b.attached())throw new IllegalArgumentException("单边路口继承接入道路断面，不能修改主路或支路宽度");}
    return requested;
  }
  private static Settings section(Settings s){return s.options(s.options().lanePoints(LanePoints.Data.EMPTY).attachments(RoadAttachments.Data.EMPTY));}
  private OneSidedJunctions(){}
}
