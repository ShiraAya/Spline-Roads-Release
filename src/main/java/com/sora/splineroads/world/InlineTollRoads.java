package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.TollPlaza.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;

/** A station insertion owns only its selected interval; the two outside fragments keep exact geometry. */
public final class InlineTollRoads {
  private record Approval(UUID owner,CompoundTag key,RoadRecord source,RoadMetadataPatch<RoadRecord> streets,
      RoadMetadataPatch<CompoundTag> junctions,RoadMetadataPatch<CompoundTag> interchanges,Map<Integer,UUID> signs,long created){}
  private static final Map<RoadData,Map<UUID,Approval>> approvals=new WeakHashMap<>();
  private record Layout(Spec spec,double from,double to){}
  private static RoadRecord source(ServerLevel level,ServerPlayer player,UUID id){
    var built=RoadData.get(level).index.roads.get(id);if(built==null)throw new IllegalArgumentException("道路已不存在");var r=built.record;
    if(player!=null)RoadData.requireOwner(player,r.owner());var type=RoadProfile.catalog(r.settings()).type();
    if(r.assembly()!=null&&!InterchangeConnectors.eligible(RoadData.get(level),r)||r.junction()!=null||r.toll()!=null||r.mesh().closed()||r.settings().style().ramp()||LaneTopology.metadata(r).link()!=null||type!=RoadProfile.Type.ORDINARY&&type!=RoadProfile.Type.HIGHWAY)throw new IllegalArgumentException("请选择独立普通/高速道路，或双喇叭的中央连接段完整断面");
    return r;
  }
  private static Settings section(RoadRecord r,double station){return RoadEndpointSections.sectionAt(r.mesh(),RoadSlicing.at(r.mesh(),station),false);}
  public static Config defaults(RoadRecord r,double station){
    var port=section(r,station);var n=RoadLanes.counts(port);var layout=RoadProfile.layout(port,port.width());int backward=Math.max(1,n.reverse());
    return new Config(n.twoWay(),false,port.options().leftTraffic(),layout.catalog().type()==RoadProfile.Type.HIGHWAY,new Direction(n.forward(),n.forward(),0,n.forward()),new Direction(backward,backward,0,backward),layout.laneWidth(),4,6,port.structure());
  }
  private static Layout layout(RoadRecord r,double station,Config c){
    var mesh=r.mesh();if(!Double.isFinite(station)||station<=0||station>=mesh.length())throw new IllegalArgumentException("请选择道路内部断面");
    var expected=defaults(r,station);
    if(c.twoWay()!=expected.twoWay()||c.reverse()||c.leftTraffic()!=expected.leftTraffic()||c.highway()!=expected.highway()||Math.abs(c.laneWidth()-expected.laneWidth())>1e-6||c.forward().aLanes()!=expected.forward().aLanes()||c.forward().bLanes()!=expected.forward().bLanes()||c.twoWay()&&(c.backward().aLanes()!=expected.backward().aLanes()||c.backward().bLanes()!=expected.backward().bLanes()))throw new IllegalArgumentException("道路断面模式、通行方向及接口车道由原道路确定；可调整 ETC、人工通道和站房尺寸");
    var port=section(r,station);double span=0;
    Spec spec=null;
    for(int i=0;i<8;i++){
      var a=span==0?port:section(r,station-span);var b=span==0?port:section(r,station+span);
      var trial=new Spec(new Node(new V(0,0,0),0,0),new Node(new V(0,0,1024),0,0),c,a,b);var lengths=TollPlanner.lengths(trial);
      double next=Math.ceil((Math.max(lengths.a(),lengths.b())+TollPlanner.CORE/2)*2)/2;
      span=Math.max(span,next);
      if(station-span<2||station+span>mesh.length()-2)throw new IllegalArgumentException(String.format(Locale.ROOT,"当前断面两侧各需 %.1f 格站区；请减少通道数量或选择更长的道路中段",span));
      double from=station-span,to=station+span;
      var path=RoadSlicing.slice(mesh,from,to,port).mesh().samples();
      spec=new Spec(RoadSplitGeometry.node(mesh,from),RoadSplitGeometry.node(mesh,to),c,section(r,from),section(r,to),path);
      if(TollPlanner.lengths(spec).deficit()<1e-5){
        try{TollPlanner.plan(spec);return new Layout(spec,from,to);}
        catch(IllegalArgumentException e){
          if(!e.getMessage().contains("纵坡最多12%"))throw e;
          if(i==7)throw e;
          // Keep the chosen station center and both outside roads. Reserve more
          // of the source interval for a level core without relaxing the grade.
          span=Math.ceil((span+Math.max(4,span*.15))*2)/2;
        }
      }
    }
    throw new IllegalArgumentException("两侧断面变化过大，无法形成收费站过渡");
  }
  public static CompoundTag payload(ServerLevel level,ServerPlayer player,UUID road,double station){
    var source=source(level,player,road);var layout=layout(source,station,defaults(source,station));var t=new CompoundTag();
    t.putString("Kind","toll");t.putString("Dimension",level.dimension().location().toString());t.putUUID("InlineRoad",road);t.putDouble("InlineStation",station);t.putInt("InlineSignature",source.header().hashCode());t.put("Spec",TollCodec.write(layout.spec()));t.putString("Adjust","NONE");return t;
  }
  private static CompoundTag key(CompoundTag t){var k=new CompoundTag();for(String field:List.of("InlineRoad","InlineStation","InlineSignature","Dimension"))if(t.contains(field))k.put(field,t.get(field).copy());k.put("Config",TollCodec.config(TollCodec.config(t.getCompound("Spec"))));return k;}
  private static void preserveInterior(RoadData data,RoadRecord r,double from,double to){
    for(var p:r.settings().options().attachments().points()){double d=RoadQueries.horizontal(r.mesh(),p.position()).sample().distance();if(d>=from&&d<=to)throw new IllegalArgumentException("站区内有附属点，请移到站外后再生成");}
    for(var p:LaneTopology.metadata(r).points()){double d=RoadQueries.horizontal(r.mesh(),p.position()).sample().distance();if(d>=from&&d<=to&&!p.automatic())throw new IllegalArgumentException("站区内有手动车道点，请先移动选址以保留原匝道连接");}
    for(var cut:LaneTopology.metadata(r).cuts())if(Math.max(cut.begin(),cut.end())>from&&Math.min(cut.begin(),cut.end())<to)throw new IllegalArgumentException("站区与车道关闭/分离区重叠，请在完整断面处生成");
    for(var opening:LaneTopology.metadata(r).openings())if(opening.centerline().stream().anyMatch(p->{double d=RoadQueries.horizontal(r.mesh(),p).sample().distance();return d>=from&&d<=to;}))throw new IllegalArgumentException("站区与现有匝道接口重叠");
    for(var state:data.smartSigns.road(r.id())){double d=RoadGantry.station(r.mesh(),state.key().slot()).distance();if(d>=from&&d<=to)throw new IllegalArgumentException("站区内有已保存标牌，请在标牌之外选址");}
    for(var edit:r.settings().options().infrastructure().gantryEdits())if(!edit.automatic()){double d=RoadGantry.station(r.mesh(),edit.slot()).distance();if(d>=from&&d<=to)throw new IllegalArgumentException("站区内有手动龙门架，请在架体外选址");}
  }
  public static CompoundTag preview(ServerLevel level,ServerPlayer player,CompoundTag t){
    var data=RoadData.get(level);var r=source(level,player,t.getUUID("InlineRoad"));
    if(!level.dimension().location().toString().equals(t.getString("Dimension"))||r.header().hashCode()!=t.getInt("InlineSignature"))throw new IllegalArgumentException("所选道路已变化，请重新选择断面");
    var layout=layout(r,t.getDouble("InlineStation"),TollCodec.config(t.getCompound("Spec")));preserveInterior(data,r,layout.from(),layout.to());
    boolean connector=InterchangeConnectors.eligible(data,r);
    var first=RoadSplitGeometry.split(connector?InterchangeConnectors.forTollSplit(r):r,layout.from(),RoadSlicing.Connection.CONTINUOUS,UUID.randomUUID());
    var second=RoadSplitGeometry.split(first.second(),layout.to()-layout.from(),RoadSlicing.Connection.CONTINUOUS,UUID.randomUUID());
    var station=second.first().toll(layout.spec());if(connector)station=InterchangeConnectors.group(station,r.assembly());var roads=new LinkedHashMap<UUID,RoadRecord>();roads.put(first.first().id(),first.first());roads.put(station.id(),station);roads.put(second.second().id(),second.second());
    var rightPoints=new HashSet<UUID>();LaneTopology.metadata(second.second()).points().forEach(p->rightPoints.add(p.id()));
    for(var old:data.index.roads.values())if(!old.record.id().equals(r.id())){var next=RoadSplitter.remapLinks(old.record,r.id(),second.second().id(),rightPoints);if(next!=old.record)roads.put(next.id(),next);}
    var streets=new LinkedHashMap<>(data.streets);var logical=streets.remove(r.id());
    if(logical!=null){double offset=r.settings().options().ends().trimmedStart()-logical.settings().options().ends().trimmedStart();streets.put(r.id(),RoadSplitGeometry.range(logical,0,layout.from()+offset,r.id(),logical.a(),first.pos(),logical.start(),first.node(),RoadSlicing.Connection.CONTINUOUS));streets.put(second.second().id(),RoadSplitGeometry.range(logical,layout.to()+offset,logical.mesh().length(),second.second().id(),second.pos(),logical.b(),second.node(),logical.end(),RoadSlicing.Connection.CONTINUOUS));}
    streets.replaceAll((id,old)->RoadSplitter.remapLinks(old,r.id(),second.second().id(),rightPoints));
    var junctions=new LinkedHashMap<UUID,CompoundTag>();data.junctions.forEach((id,v)->junctions.put(id,RoadSplitter.remapJunction(v,r,second.second().id())));
    var interchanges=new LinkedHashMap<UUID,CompoundTag>();data.interchanges.forEach((id,v)->interchanges.put(id,InterchangeConnectors.split(InterchangeConnectors.split(RoadSplitter.remapAssembly(RoadSplitter.remapAssembly(v,r,first),first.second(),second),r,first),first.second(),second)));
    var owners=new HashMap<Integer,UUID>();for(var fragment:List.of(first.first(),second.second()))for(int slot:RoadGantry.slots(fragment.mesh()))owners.put(slot,fragment.id());
    for(var state:data.smartSigns.road(r.id()))if(!owners.containsKey(state.key().slot()))throw new IllegalArgumentException("原标牌无法完整迁移到站外路段");
    var removed=new HashSet<>(roads.keySet());removed.retainAll(data.index.roads.keySet());var nodes=new HashSet<>(List.of(r.a(),r.b(),first.pos(),second.pos()));
    var moves=List.of(move(first,r.owner()),move(second,r.owner()));UUID token=UUID.randomUUID();
    var prepared=data.prepareAssembly(level,player,roads.values().stream().map(RoadIndex.Built::new).toList(),removed,nodes,moves,token,Set.of(station.id()),true);
    var table=approvals.computeIfAbsent(data,k->new LinkedHashMap<>());table.entrySet().removeIf(e->System.nanoTime()-e.getValue().created()>600_000_000_000L||Objects.equals(e.getValue().owner(),player==null?null:player.getUUID()));
    table.put(token,new Approval(player==null?null:player.getUUID(),key(t),r,RoadMetadataPatch.between(data.streets,streets,v->v),RoadMetadataPatch.between(data.junctions,junctions,CompoundTag::copy),RoadMetadataPatch.between(data.interchanges,interchanges,CompoundTag::copy),Map.copyOf(owners),System.nanoTime()));while(table.size()>4)table.remove(table.keySet().iterator().next());
    var reply=new CompoundTag();reply.putUUID("Token",token);reply.put("Spec",TollCodec.write(layout.spec()));reply.putInt("Affected",2);var list=new ListTag();prepared.forEach(b->list.add(b.record.save()));reply.put("Roads",list);return reply;
  }
  private static RoadData.NodeMove move(RoadSplitGeometry.Pair pair,UUID owner){var t=new CompoundTag();t.putUUID("Owner",owner);t.put("Node",RoadRecord.writeNode(pair.node()));t.putBoolean("HeadingLocked",true);t.putBoolean("GradeLocked",true);t.putBoolean("HeightExplicit",true);return new RoadData.NodeMove(null,pair.pos(),pair.node(),t);}
  public static String build(ServerLevel level,ServerPlayer player,CompoundTag t){
    var data=RoadData.get(level);var table=approvals.get(data);UUID token=t.hasUUID("Token")?t.getUUID("Token"):null;var approval=table==null?null:table.remove(token);
    if(approval==null||System.nanoTime()-approval.created()>600_000_000_000L||!Objects.equals(approval.owner(),player==null?null:player.getUUID())||!approval.key().equals(key(t)))throw new IllegalArgumentException("断面收费站预览已失效，请重新预览");
    var current=data.index.roads.get(approval.source().id());if(current==null||current.record!=approval.source())throw new IllegalArgumentException("原道路已变化，请重新选择断面");
    approval.streets().validate(data.streets);approval.junctions().validate(data.junctions);approval.interchanges().validate(data.interchanges);
    data.buildPrepared(level,player,token,()->{approval.streets().apply(data.streets);approval.junctions().apply(data.junctions);approval.interchanges().apply(data.interchanges);data.smartSigns.splitKeys(approval.source().id(),approval.signs());});
    return "已按当前断面生成收费站，站外原路及其连接已保留";
  }
  private InlineTollRoads(){}
}
