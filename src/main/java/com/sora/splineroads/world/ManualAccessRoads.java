package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;

/** Paired access ramps, their four real lane points and the exit road share one prepared edit. */
public final class ManualAccessRoads {
  private record Approval(UUID owner,RoadRecord source,CompoundTag key,long created){}
  private static final Map<RoadData,Map<UUID,Approval>> APPROVALS=new WeakHashMap<>();
  private static boolean supported(RoadRecord r){return r.assembly()==null&&r.toll()==null&&r.junction()==null&&LaneTopology.metadata(r).link()==null&&!r.mesh().closed()&&LanePoints.supported(r.settings())&&!r.settings().style().ramp();}
  static CompoundTag payload(ServerPlayer player,V hit,CompoundTag selection){
    var data=RoadData.get(player.serverLevel());RoadRecord host=null;
    if(selection.contains("LaneFrom")){var ref=LanePointCodec.ref(selection.getCompound("LaneFrom"));var built=data.index.roads.get(ref.road());if(built!=null)host=built.record;}
    if(host==null)host=data.index.roads.values().stream().map(b->b.record).filter(ManualAccessRoads::supported).min(Comparator.comparingDouble(r->RoadQueries.horizontal(r.mesh(),hit).horizontalDistance())).orElseThrow(()->new IllegalArgumentException("附近没有可接入的独立普通道路或高速道路"));
    if(!supported(host))throw new IllegalArgumentException("请选择独立普通道路或高速道路作为主路");RoadData.requireOwner(player,host.owner());
    var t=new CompoundTag();t.putString("Kind","manualAccess");t.putString("Dimension",player.level().dimension().location().toString());t.putUUID("Road",host.id());t.putInt("Signature",host.header().hashCode());t.putDouble("X",hit.x());t.putDouble("Y",hit.y());t.putDouble("Z",hit.z());t.putDouble("SpurLength",80);return t;
  }
  private static CompoundTag key(CompoundTag t){var out=new CompoundTag();for(String name:List.of("Road","Signature","X","Y","Z","SpurLength","Dimension"))if(t.contains(name))out.put(name,t.get(name).copy());return out;}
  static CompoundTag preview(ServerPlayer player,CompoundTag command){return preview(player.serverLevel(),player,command);}
  static CompoundTag preview(ServerLevel level,ServerPlayer player,CompoundTag command){try(var chunks=RoadWorkChunks.open(level)){
    var data=RoadData.get(level);var built=data.index.roads.get(command.getUUID("Road"));if(built==null||!supported(built.record)||built.record.header().hashCode()!=command.getInt("Signature"))throw new IllegalArgumentException("主路已变化，请重新放置接入点");var host=built.record;if(player!=null)RoadData.requireOwner(player,host.owner());if(!level.dimension().location().toString().equals(command.getString("Dimension")))throw new IllegalArgumentException("地图维度已变化");
    V target=new V(command.getDouble("X"),command.getDouble("Y"),command.getDouble("Z"));double length=command.getDouble("SpurLength");if(!RoadGeometry.finite(target.x(),target.y(),target.z(),length)||length<64||length>160)throw new IllegalArgumentException("接出道路长度应为64–160格");
    var q=RoadQueries.horizontal(built.mesh,target);double distance=q.horizontalDistance();if(distance<length+48||distance>384)throw new IllegalArgumentException("接入点与主路需保留至少道路长度加48格、最多384格的横向空间");
    V outward=target.sub(q.sample().center()).horizontalUnit();double station=q.sample().distance(),spread=Math.max(64,Math.min(120,distance*.6));if(station-spread<40||station+spread>built.mesh.length()-40)throw new IllegalArgumentException("主路两侧需有足够分合流长度，请在更长路段中部放置");
    var lanes=LaneAdditions.slots(built.mesh,station).stream().filter(i->LaneSections.active(built.mesh,station,i)).map(i->LanePoints.lane(built.mesh,station,i)).toList();
    var outgoing=lanes.stream().min(Comparator.comparingDouble(l->l.position().sub(target).horizontalLength())).orElseThrow(()->new IllegalArgumentException("所选主路没有可用车道"));
    var incoming=lanes.stream().filter(l->l.sign()==outgoing.sign()).max(Comparator.comparingDouble(l->l.position().sub(q.sample().center()).dot(outward))).orElseThrow();
    var departure=LanePoints.point(UUID.randomUUID(),LanePoints.Origin.MANUAL,built.mesh,station-spread*outgoing.sign(),outgoing.index());
    var arrival=LanePoints.point(UUID.randomUUID(),LanePoints.Origin.MANUAL,built.mesh,station+spread*outgoing.sign(),incoming.index());
    var points=new ArrayList<>(LaneTopology.metadata(host).points());points.add(departure);points.add(arrival);var changedHost=host.withLanePoints(LaneTopology.metadata(host).points(points));
    V start=target.sub(outward.mul(length));var na=new Node(start,RoadPlanner.yaw(outward),0);var nb=new Node(target,RoadPlanner.yaw(outward),0);BlockPos a=BlockPos.containing(start.x(),start.y(),start.z()),b=BlockPos.containing(target.x(),target.y(),target.z());
    var settings=RoadLanes.configure(Settings.defaults(),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(1,1),RoadProfile.layout(built.mesh,q.sample()).laneWidth()).structure(Structure.AUTO);settings=RoadPlanner.mode(settings,Mode.STRAIGHT,90).options(settings.options().traffic(host.settings().options().leftTraffic()).route(RoadProfile.Routing.DEFAULT.fit(false)).infrastructure(settings.options().infrastructure().gantry(RoadInfrastructure.Gantry.OFF)));
    var spur=new RoadRecord(UUID.randomUUID(),host.owner(),a,b,na,nb,settings,false,4);var spurMesh=spur.mesh();var spurLanes=LaneAdditions.slots(spurMesh,length/2).stream().map(i->LanePoints.lane(spurMesh,length/2,i)).toList();
    var entry=LanePoints.point(UUID.randomUUID(),LanePoints.Origin.MANUAL,spurMesh,length/2,spurLanes.stream().filter(l->l.sign()>0).findFirst().orElseThrow().index());
    var exit=LanePoints.point(UUID.randomUUID(),LanePoints.Origin.MANUAL,spurMesh,length/2,spurLanes.stream().filter(l->l.sign()<0).findFirst().orElseThrow().index());spur=spur.withLanePoints(LanePoints.Data.EMPTY.points(List.of(entry,exit)));
    var all=new LinkedHashMap<>(LaneTopology.records(data));all.put(host.id(),changedHost);all.put(spur.id(),spur);
    var options=new LanePoints.Options(LanePoints.Path.AUTO,LanePoints.Departure.BRANCH,LanePoints.Arrival.FLOW,24,24,LanePoints.Elevation.AUTO,LanePoints.Landing.EXACT);
    var firstLink=new LanePoints.Link(LanePoints.Ref.lane(host.id(),departure.id()),LanePoints.Ref.lane(spur.id(),entry.id()),options,null);
    var first=LaneRamps.generate(data,all,UUID.randomUUID(),host.owner(),firstLink);all.put(first.id(),first);
    var secondLink=new LanePoints.Link(LanePoints.Ref.lane(spur.id(),exit.id()),LanePoints.Ref.lane(host.id(),arrival.id()),options,null);
    var second=LaneRamps.generate(data,all,UUID.randomUUID(),host.owner(),secondLink);all.put(second.id(),second);
    var selected=new HashSet<BlockPos>();selected.addAll(LaneRamps.contactEndpoints(all,first));selected.addAll(LaneRamps.contactEndpoints(all,second));selected.add(a);selected.add(b);
    UUID token=UUID.randomUUID();var requested=List.of(changedHost,spur,first,second);var prepared=data.prepareAssembly(level,player,requested.stream().map(RoadIndex.Built::new).toList(),Set.of(host.id()),selected,List.of(node(a,na,host.owner()),node(b,nb,host.owner())),token,Set.of(spur.id(),first.id(),second.id()),true);
    var table=APPROVALS.computeIfAbsent(data,d->new LinkedHashMap<>());table.entrySet().removeIf(e->System.nanoTime()-e.getValue().created()>600_000_000_000L||Objects.equals(e.getValue().owner(),player==null?null:player.getUUID()));table.put(token,new Approval(player==null?null:player.getUUID(),host,key(command),System.nanoTime()));while(table.size()>4)table.remove(table.keySet().iterator().next());
    var reply=command.copy();reply.remove("Action");reply.putString("Kind","manualAccess");reply.putUUID("Token",token);var roads=new ListTag();prepared.forEach(r->roads.add(r.record.save()));reply.put("PlannerPreview",roads);com.sora.splineroads.net.RoadActionContext.echo(command,reply);return reply;
  }}
  private static RoadData.NodeMove node(BlockPos p,Node n,UUID owner){var t=new CompoundTag();t.putUUID("Owner",owner);t.put("Node",RoadRecord.writeNode(n));t.putBoolean("HeadingLocked",true);t.putBoolean("HeightExplicit",true);return new RoadData.NodeMove(null,p,n,t);}
  static String build(ServerPlayer player,CompoundTag t){return build(player.serverLevel(),player,t);}
  static String build(ServerLevel level,ServerPlayer player,CompoundTag t){var data=RoadData.get(level);var table=APPROVALS.get(data);var approval=table==null||!t.hasUUID("Token")?null:table.remove(t.getUUID("Token"));if(approval==null||System.nanoTime()-approval.created()>600_000_000_000L||!Objects.equals(approval.owner(),player==null?null:player.getUUID())||!approval.key().equals(key(t))||data.index.roads.get(approval.source().id())==null||data.index.roads.get(approval.source().id()).record!=approval.source())throw new IllegalArgumentException("接入匝道预览已失效，请重新预览");data.buildPrepared(level,player,t.getUUID("Token"));return "双向进出匝道、接出道路及分合流点已统一建造";}
  private ManualAccessRoads(){}
}
