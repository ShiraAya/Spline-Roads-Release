package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.net.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;

/** Authoritative preview, atomic installation and restoration of elevated terminals. */
public final class ElevatedAccessRoads {
  private record Approval(UUID owner,CompoundTag key,CompoundTag receipt,long created){}
  private static final Map<RoadData,Map<UUID,Approval>> APPROVALS=new WeakHashMap<>();
  public static boolean saved(RoadData data,UUID id){return id!=null&&data.interchanges.getOrDefault(id,new CompoundTag()).getBoolean("ElevatedAccess");}
  static LanePoints.Data logicalLanes(RoadRecord street,RoadRecord live){
    var lanes=LaneTopology.metadata(live);
    if(lanes.access().isEmpty()||street==live)return lanes;
    double shift=street.fragment()!=null&&live.fragment()!=null&&street.fragment().source().equals(live.fragment().source())
        ? live.fragment().origin()-street.fragment().origin()
        : RoadQueries.horizontal(street.rawMesh(),live.rawMesh().first().center()).sample().distance();
    if(Math.abs(shift)<1e-8)return lanes;
    var ids=new HashSet<UUID>();lanes.access().forEach(a->ids.add(a.connection()));
    var access=lanes.access().stream().map(a->ElevatedAccess.shifted(a,shift)).toList();
    var cuts=new ArrayList<>(lanes.cuts().stream().filter(c->!ids.contains(c.connection())).toList());
    var adds=new ArrayList<>(lanes.additions().stream().filter(a->!ids.contains(a.connection())).toList());
    for(var reservation:access){cuts.addAll(reservation.cuts());adds.addAll(reservation.additions());}
    return lanes.cuts(cuts).additions(adds).access(access);
  }
  static void protect(RoadData data,List<RoadIndex.Built> proposed,Set<UUID> removed){
    var all=LaneTopology.records(data);removed.forEach(all::remove);for(var b:proposed)all.put(b.record.id(),b.record);
    for(var entry:data.interchanges.entrySet())if(entry.getValue().getBoolean("ElevatedAccess")&&all.containsKey(entry.getKey())){
      var t=entry.getValue();var old=data.index.roads.get(t.getUUID("Main"));var next=all.get(t.getUUID("Main"));
      if(old==null||next==null)throw new IllegalArgumentException("主路仍连接高架，请同时删除依赖的高架连接");
      if(!sameGeometry(old.record,next)||next.settings().options().attachments().points().stream().noneMatch(p->p.id().equals(t.getUUID("Point"))&&p.elevated()))
        throw new IllegalArgumentException("主路已有高架出入口；改变主路线形或断面前，请先删除该连接");
      for(UUID member:members(t))if(!member.equals(next.id())){
        var prior=data.index.roads.get(member);var proposedRoad=all.get(member);
        if(prior==null||proposedRoad==null||!sameGeometry(prior.record,proposedRoad))throw new IllegalArgumentException("此段承载相邻高架出入口的辅路，请先删除连接");
      }
      var end=BlockPos.of(t.getLong("Endpoint"));var neighbors=all.values().stream().filter(r->!r.id().equals(entry.getKey())&&(r.a().equals(end)||r.b().equals(end))).toList();
      if(neighbors.size()>1||neighbors.isEmpty()&&!data.index.atNode(end).stream().allMatch(entry.getKey()::equals))throw new IllegalArgumentException("高架出入口只能连接道路尽头，不能把此端点改成多路交汇");
      for(var r:neighbors){var before=data.index.roads.get(r.id());if(before==null||!sameGeometry(before.record,r))throw new IllegalArgumentException("该道路尽头连接高架出入口，请先删除连接再改变其线形或断面");}
    }
  }
  private static boolean sameGeometry(RoadRecord a,RoadRecord b){return a.start().equals(b.start())&&a.end().equals(b.end())
      &&a.rawMesh().samples().equals(b.rawMesh().samples())&&a.settings().thickness()==b.settings().thickness()
      &&a.settings().style()==b.settings().style()&&RoadLanes.counts(a.settings()).equals(RoadLanes.counts(b.settings()))
      &&a.settings().options().leftTraffic()==b.settings().options().leftTraffic()&&a.settings().options().ends().equals(b.settings().options().ends());}
  static void prune(RoadData data){data.interchanges.entrySet().removeIf(e->e.getValue().getBoolean("ElevatedAccess")&&!data.index.roads.containsKey(e.getKey()));}
  private static RoadIndex.Built host(RoadData data,UUID id){var b=data.index.roads.get(id);
    if(b==null||b.record.assembly()!=null||b.record.junction()!=null||b.record.toll()!=null||b.record.railway()||LaneTopology.metadata(b.record).link()!=null)
      throw new IllegalArgumentException("高架出入点的主路已变化，请重新选择普通道路或高速道路");return b;}
  private static RoadAttachments.Point point(RoadRecord road,UUID id){return road.settings().options().attachments().points().stream().filter(p->p.id().equals(id)&&p.elevated()).findFirst().orElseThrow(()->new IllegalArgumentException("高架出入点已不存在"));}
  private static List<RoadIndex.Built> endpoint(RoadData data,BlockPos endpoint,UUID edited){
    var roads=data.index.atNode(endpoint).stream().filter(id->!id.equals(edited)).map(data.index.roads::get).toList();
    if(roads.size()!=1||!ElevatedPointTool.supported(roads.get(0).record))
      throw new IllegalArgumentException("高架出入口须连接已有高架的尽头端点");
    var road=roads.get(0);
    double station=road.record.a().equals(endpoint)?Math.min(.25,road.mesh.length()/2):Math.max(0,road.mesh.length()-.25);
    if(!RoadStreetscape.raised(road.mesh,RoadStructures.sample(road.mesh,station)))
      throw new IllegalArgumentException("所选道路尽头须为高架路段");
    return roads;
  }
  public static CompoundTag payload(ServerLevel level,ServerPlayer player,UUID main,UUID marker,BlockPos end,boolean first,UUID edited){
    var data=RoadData.get(level);var host=host(data,main);var point=point(host.record,marker);
    if(edited==null&&LaneTopology.metadata(host.record).access().stream().anyMatch(a->a.point().equals(marker)))throw new IllegalArgumentException("该高架出入点已连接道路，请编辑已有连接");
    if(edited!=null){
      var receipt=data.interchanges.get(edited);
      if(!saved(data,edited)||!data.index.roads.containsKey(edited)||!main.equals(receipt.getUUID("Main"))
          ||!marker.equals(receipt.getUUID("Point"))||end.asLong()!=receipt.getLong("Endpoint")||first!=receipt.getBoolean("MainFirst"))
        throw new IllegalArgumentException("已建高架连接已改变，请重新选择");
    }
    var node=RoadData.requireNode(level,end,player);var connected=endpoint(data,end,edited);
    if(player!=null){RoadData.requireOwner(player,host.record.owner());for(var b:connected)RoadData.requireOwner(player,b.record.owner());}
    var station=RoadQueries.horizontal(host.mesh,point.position()).sample();var counts=RoadLanes.counts(host.record.settings());
    if(!RoadLanes.counts(connected.get(0).record.settings()).twoWay())first=connected.get(0).record.a().equals(end);
    int axis=!counts.twoWay()?(first?1:-1):node.node().position().sub(station.center()).dot(station.left().left().mul(-1))<0?-1:1;
    boolean positive=(first?axis:-axis)>0;int maxForward=positive?counts.forward():counts.reverse(),maxReverse=positive?counts.reverse():counts.forward();
    var upper=connected.get(0).record;
    boolean atFirst=upper.a().equals(end);
    Settings settings=RoadEndpointSections.section(upper.caps(0).mesh(),atFirst,atFirst!=first);
    var t=new CompoundTag();t.putString("Kind","elevatedAccess");t.putString("Dimension",level.dimension().location().toString());t.putUUID("Main",main);t.putUUID("Point",marker);t.putLong("Endpoint",end.asLong());t.putBoolean("MainFirst",first);t.putInt("Signature",host.record.header().hashCode());t.putInt("TargetSignature",Objects.hash(node.node(),connected.stream().map(b->b.record.header()).toList()));
    t.putInt("MaxForward",maxForward);t.putInt("MaxReverse",maxReverse);t.putBoolean("Locked",true);t.put("Settings",RoadRecord.writeSettings(settings));if(edited!=null)t.putUUID("Id",edited);return t;
  }
  static void select(ServerPlayer player,CompoundTag tool,RoadIndex.Built road,RoadAttachments.Point marker){
    if(tool.hasUUID("SideMain"))throw new IllegalArgumentException("高架出入点须连接已有高架的尽头端点");
    String dimension=player.level().dimension().location().toString();
    if(tool.contains("Start")&&dimension.equals(tool.getString("Dimension"))){open(player,tool,payload(player.serverLevel(),player,road.record.id(),marker.id(),BlockPos.of(tool.getLong("Start")),false,null));return;}
    tool.remove("ElevatedSelection");tool.remove("SideSelection");tool.remove("Start");tool.remove(CurveControlNodes.KEY);tool.putUUID("SideMain",road.record.id());tool.putUUID("SidePoint",marker.id());tool.putString("Dimension",dimension);
    player.displayClientMessage(net.minecraft.network.chat.Component.literal("已选高架出入点，请右键已有高架的尽头端点"),true);
  }
  static void open(ServerPlayer player,CompoundTag tool,CompoundTag payload){tool.put("ElevatedSelection",selection(payload));RoadNetwork.open(player,payload);}
  public static void open(ServerPlayer player,UUID id){var data=RoadData.get(player.serverLevel());var saved=data.interchanges.get(id);if(saved==null)throw new IllegalArgumentException("高架连接已删除");var t=payload(player.serverLevel(),player,saved.getUUID("Main"),saved.getUUID("Point"),BlockPos.of(saved.getLong("Endpoint")),saved.getBoolean("MainFirst"),id);
    previewConnector(t,data.index.roads.get(id).record);
    RoadNetwork.open(player,t);
  }
  private static void previewConnector(CompoundTag t,RoadRecord connector){
    var records=new ListTag();records.add(connector.save());t.put("PlannerPreview",records);
  }
  private static CompoundTag selection(CompoundTag t){var key=new CompoundTag();for(String field:List.of("Main","Point","Endpoint","MainFirst","Dimension"))if(t.contains(field))key.put(field,t.get(field).copy());return key;}
  private static CompoundTag key(CompoundTag t){var key=selection(t);for(String field:List.of("Id","Settings","Signature","TargetSignature"))if(t.contains(field))key.put(field,t.get(field).copy());return key;}
  public static void requireSelection(ServerPlayer player,net.minecraft.world.item.ItemStack tool,CompoundTag t){if(!t.hasUUID("Id")&&!tool.getOrCreateTag().getCompound("ElevatedSelection").equals(selection(t)))throw new IllegalArgumentException("高架选点已改变，请重新选择");}

  public static CompoundTag preview(ServerLevel level,ServerPlayer player,CompoundTag command){try(var chunks=RoadWorkChunks.open(level)){
    var data=RoadData.get(level);UUID edited=command.hasUUID("Id")?command.getUUID("Id"):null;
    var fresh=payload(level,player,command.getUUID("Main"),command.getUUID("Point"),BlockPos.of(command.getLong("Endpoint")),command.getBoolean("MainFirst"),edited);
    if(!fresh.getString("Dimension").equals(command.getString("Dimension"))||fresh.getInt("Signature")!=command.getInt("Signature")||fresh.getInt("TargetSignature")!=command.getInt("TargetSignature"))throw new IllegalArgumentException("道路或端点已改变，请重新打开高架连接设置");
    var host=host(data,command.getUUID("Main"));var marker=point(host.record,command.getUUID("Point"));
    if(LaneTopology.metadata(host.record).access().stream().anyMatch(a->a.point().equals(marker.id())&&!a.connection().equals(edited)))throw new IllegalArgumentException("该高架出入点已连接道路，请编辑已有连接");
    var requested=RoadRecord.readSettings(command.getCompound("Settings"));var inherited=RoadRecord.readSettings(fresh.getCompound("Settings"));
    var selected=RoadLanes.counts(requested);
    if(!selected.equals(RoadLanes.counts(inherited)))throw new IllegalArgumentException("已有高架尽头须保持其实际各方向车道数");
    if(selected.forward()>fresh.getInt("MaxForward")||selected.reverse()>fresh.getInt("MaxReverse"))throw new IllegalArgumentException("高架车道数不能超过下方道路对应方向");
    var clean=inherited;
    clean=clean.options(clean.options().attachments(RoadAttachments.Data.EMPTY).lanePoints(LanePoints.Data.EMPTY).route(RoadProfile.Routing.DEFAULT.fit(false)));
    var end=BlockPos.of(command.getLong("Endpoint"));var node=RoadData.requireNode(level,end,player).node();var links=endpoint(data,end,edited);
    {var road=links.get(0);boolean first=road.record.a().equals(end);var geometry=road.record.caps(0).mesh();var s=first?geometry.first():geometry.last();V direction=s.left().left().mul(first?-1:1);var n=first?road.record.start():road.record.end();node=new Node(s.center(),RoadPlanner.yaw(direction),n.grade()*(first?1:-1));}
    UUID id=edited==null?UUID.randomUUID():edited;
    var original=without(host.record,id);
    var result=ElevatedAccessChain.plan(data,original,marker,id,node,clean,fresh.getBoolean("MainFirst"));
    var plan=result.plan();
    var affected=new LinkedHashSet<>(result.reservations().keySet());
    if(edited!=null)affected.addAll(members(data.interchanges.get(edited)));
    var changes=new ArrayList<RoadIndex.Built>();
    var opening=new LanePoints.Opening(id,plan.road().samples().stream().map(Sample::center).toList(),plan.road().samples().stream().mapToDouble(Sample::halfWidth).max().orElseThrow());
    for(var member:affected){
      var road=without(host(data,member).record,id);
      if(player!=null)RoadData.requireOwner(player,road.owner());
      var reservation=result.reservations().get(member);
      if(reservation!=null){
        var md=LaneTopology.metadata(road);
        var access=new ArrayList<>(md.access());access.add(reservation);
        var cuts=new ArrayList<>(md.cuts());cuts.addAll(reservation.cuts());
        var additions=new ArrayList<>(md.additions());additions.addAll(reservation.additions());
        var openings=new ArrayList<>(md.openings());openings.add(opening);
        road=road.withLanePoints(md.access(access).cuts(cuts).additions(additions).openings(openings));
      }
      changes.add(new RoadIndex.Built(road.structures(List.of())));
    }
    var path=plan.road();var a=RoadRibbon.start(path);var b=RoadRibbon.end(path);var pa=BlockPos.containing(a.position().x(),a.position().y(),a.position().z());var pb=BlockPos.containing(b.position().x(),b.position().y(),b.position().z());
    // Finite sampled secants are not the authored endpoint derivative. Keep
    // the upper road's exact grade, including a flat terminal at a crest.
    if(fresh.getBoolean("MainFirst"))b=new Node(b.position(),b.yaw(),node.grade()*(b.direction().dot(node.direction())<0?-1:1));
    else a=new Node(a.position(),a.yaw(),node.grade()*(a.direction().dot(node.direction())<0?-1:1));
    var connector=new RoadRecord(id,host.record.owner(),fresh.getBoolean("MainFirst")?pa:end,fresh.getBoolean("MainFirst")?end:pb,a,b,path.settings(),false,4).alignment(id,path);
    // Check the complete physical lane deck. Reserved holes permit only the
    // selected access; the other lanes remain ordinary protected road space.
    var nodes=new HashSet<BlockPos>();nodes.add(end);
    for(var changed:changes){
      Interchanges.checkExternal(connector.mesh(),changed.mesh,null);
      nodes.add(changed.record.a());nodes.add(changed.record.b());
    }
    var removed=new HashSet<>(affected);if(edited!=null)removed.add(edited);
    UUID token=UUID.randomUUID();changes.add(new RoadIndex.Built(connector));
    var prepared=data.prepareAssembly(level,player,changes,removed,nodes,List.of(),token,Set.of(connector.id()),true);
    var receipt=selection(fresh);receipt.putUUID("Id",id);receipt.putBoolean("ElevatedAccess",true);receipt.putUUID("Owner",host.record.owner());receipt.put("Settings",RoadRecord.writeSettings(clean));
    var hosts=new ListTag();for(UUID member:result.reservations().keySet())hosts.add(net.minecraft.nbt.NbtUtils.createUUID(member));receipt.put("Hosts",hosts);
    var table=APPROVALS.computeIfAbsent(data,d->new LinkedHashMap<>());table.entrySet().removeIf(e->System.nanoTime()-e.getValue().created()>600_000_000_000L||Objects.equals(e.getValue().owner(),player==null?null:player.getUUID()));table.put(token,new Approval(player==null?null:player.getUUID(),key(command),receipt,System.nanoTime()));
    while(table.size()>4)table.remove(table.keySet().iterator().next());var reply=command.copy();reply.remove("Action");reply.putString("Kind","elevatedAccess");reply.putUUID("Token",token);previewConnector(reply,prepared.stream().filter(p->p.record.id().equals(id)).findFirst().orElseThrow().record);RoadActionContext.echo(command,reply);return reply;
  }}
  public static String build(ServerLevel level,ServerPlayer player,CompoundTag t){var data=RoadData.get(level);var table=APPROVALS.get(data);var approval=table==null||!t.hasUUID("Token")?null:table.remove(t.getUUID("Token"));
    if(approval==null||!Objects.equals(approval.owner(),player==null?null:player.getUUID())||System.nanoTime()-approval.created()>600_000_000_000L||!approval.key().equals(key(t)))throw new IllegalArgumentException("高架连接预览已失效，请重新计算预览");
    data.buildPrepared(level,player,t.getUUID("Token"),()->data.interchanges.put(approval.receipt().getUUID("Id"),approval.receipt().copy()));return "高架上下坡连接已建造，地面车道和直行辅路已同步更新";
  }
  static void remove(ServerLevel level,ServerPlayer player,UUID id){var data=RoadData.get(level);var saved=data.interchanges.get(id);if(saved==null)return;
    var replacements=new ArrayList<RoadIndex.Built>();var removed=new HashSet<UUID>();removed.add(id);var nodes=new HashSet<BlockPos>();
    for(var member:members(saved)){
      var main=data.index.roads.get(member);if(main==null)continue;
      replacements.add(new RoadIndex.Built(without(main.record,id).structures(List.of())));
      removed.add(member);nodes.add(main.record.a());nodes.add(main.record.b());
    }
    nodes.add(BlockPos.of(saved.getLong("Endpoint")));data.replaceAssembly(level,player,replacements,removed,nodes);data.interchanges.remove(id);data.setDirty();
  }
  private static Set<UUID> members(CompoundTag saved){
    var ids=new LinkedHashSet<UUID>();ids.add(saved.getUUID("Main"));
    for(var t:saved.getList("Hosts",Tag.TAG_INT_ARRAY))ids.add(NbtUtils.loadUUID(t));
    return ids;
  }
  private static RoadRecord without(RoadRecord road,UUID id){
    var md=LaneTopology.metadata(road);
    return road.withLanePoints(md.access(md.access().stream().filter(a->!a.connection().equals(id)).toList())
        .cuts(md.cuts().stream().filter(c->!c.connection().equals(id)).toList())
        .additions(md.additions().stream().filter(a->!a.connection().equals(id)).toList())
        .openings(md.openings().stream().filter(o->!o.connection().equals(id)).toList()));
  }
  private ElevatedAccessRoads(){}
}
