package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.TollPlaza.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;

/** Station and endpoint moves share RoadData's prevalidated, revision-bound transaction. */
public final class TollRoads {
  private record Approval(CompoundTag key,UUID owner,long created){}
  private static final Map<RoadData,LinkedHashMap<UUID,Approval>> APPROVALS=new WeakHashMap<>();
  /** A curved rail's cut face follows the shared endpoint plane, not its final chord. */
  static void alignApproachRails(RoadIndex.Built road,List<RoadIndex.Built> nearby,List<RoadStructures.Part> parts){
    if(road.record.toll()!=null)return;
    for(boolean first:new boolean[]{true,false}){
      var node=first?road.record.a():road.record.b();
      if(nearby.stream().noneMatch(b->b.record.toll()!=null&&(b.record.a().equals(node)||b.record.b().equals(node))))continue;
      var end=first?road.mesh.first():road.mesh.last();V along=end.left().left();
      for(int i=0;i<parts.size();i++){var p=parts.get(i);
        if(p.pier()||!p.model().isEmpty()||p.heights()!=null||p.width()>.7||p.height()>1.2||!Set.of(RoadStructures.Material.CONCRETE,RoadStructures.Material.STEEL,RoadStructures.Material.DARK_STEEL).contains(p.material())||p.a().sub(p.b()).horizontalLength()<.1)continue;
        // Generated rail posts have a finite 0.16-block footprint. A post
        // centered just inside a cut must also end on the approach side of the
        // shared plane; aligning only long rail faces leaves its nose in the station.
        if(p.material()==RoadStructures.Material.DARK_STEEL&&p.width()<=.2&&p.a().sub(p.b()).horizontalLength()<=.2){
          V outward=along.mul(first?1:-1);double penetration=p.base().stream().mapToDouble(v->v.sub(end.center()).dot(outward)).max().orElse(0);
          double center=p.a().add(p.b()).mul(.5).sub(end.center()).dot(outward);
          if(center<=.08&&center>-.25&&penetration>0&&penetration<=.2){V shift=outward.mul(-penetration-.001);parts.set(i,new RoadStructures.Part(p.a().add(shift),p.b().add(shift),p.width(),p.height(),false,p.material(),p.frameA(),p.frameB(),p.model(),p.heights()));continue;}
        }
        boolean a=Math.abs(p.a().sub(end.center()).dot(along))<.015&&p.a().y()>=end.center().y()-.05;
        boolean b=Math.abs(p.b().sub(end.center()).dot(along))<.015&&p.b().y()>=end.center().y()-.05;
        if(a||b){V frame=end.left().mul(p.width()/2);parts.set(i,p.frames(a?frame:p.frameA(),b?frame:p.frameB()));}
      }
    }
  }
  public static RoadRecord at(RoadData data,BlockPos endpoint){return data.index.atNode(endpoint).stream().map(data.index.roads::get).filter(b->b.record.toll()!=null).map(b->b.record).findFirst().orElse(null);}
  private static BlockPos pos(Node n){return BlockPos.containing(n.position().x(),n.position().y(),n.position().z());}
  public static CompoundTag payload(ServerLevel level,ServerPlayer player,long[] points,UUID id){
    var data=RoadData.get(level);RoadRecord old=null;
    if(id!=null){var b=data.index.roads.get(id);if(b==null||b.record.toll()==null)throw new IllegalArgumentException("收费站已不存在");old=b.record;if(player!=null)RoadData.requireOwner(player,old.owner());points=new long[]{old.a().asLong(),old.b().asLong()};}
    if(old!=null&&old.assembly()!=null){var group=data.interchanges.get(old.assembly());if(group!=null&&group.getBoolean("IntegratedToll"))return Interchanges.payload(level,player,group.getLongArray("Points"),old.assembly());}
    if(points.length!=2||points[0]==points[1])throw new IllegalArgumentException("请选择两个不同的真实道路端点 A、B");
    var a=RoadData.requireNode(level,BlockPos.of(points[0]),player);var b=RoadData.requireNode(level,BlockPos.of(points[1]),player);
    Node na=a.constructionNode(),nb=b.constructionNode();V axis=nb.position().sub(na.position()).horizontalUnit();double yaw=RoadPlanner.yaw(axis);
    na=endpoint(data,BlockPos.of(points[0]),na,axis,id);nb=endpoint(data,BlockPos.of(points[1]),nb,axis,id);
    var spec=new Spec(new Node(na.position(),yaw,na.grade()),new Node(nb.position(),yaw,nb.grade()),old==null?Config.DEFAULT:old.toll().config(),port(data,BlockPos.of(points[0]),axis,id,old==null?null:old.toll().portA()),port(data,BlockPos.of(points[1]),axis,id,old==null?null:old.toll().portB()));
    if(old!=null&&!old.toll().path().isEmpty())spec=old.toll();
    var t=new CompoundTag();t.putString("Kind","toll");t.putString("Dimension",level.dimension().location().toString());t.putLongArray("Points",points);t.put("Spec",TollCodec.write(spec));t.putString("Adjust","NONE");
    var connections=new ListTag();java.util.stream.Stream.concat(data.index.atNode(BlockPos.of(points[0])).stream(),data.index.atNode(BlockPos.of(points[1])).stream()).distinct().filter(v->!v.equals(id)).sorted().forEach(v->connections.add(data.index.roads.get(v).record.header()));t.put("Connections",connections);
    t.put("SnapshotA",a.saveWithoutMetadata());t.put("SnapshotB",b.saveWithoutMetadata());t.putLong("Revision",data.index.revision());
    if(old!=null){t.putUUID("Id",id);t.putUUID("Owner",old.owner());}t.putString("SelectionStamp",TollInputs.stamp(t));return t;
  }
  private static Settings port(RoadData data,BlockPos at,V axis,UUID station,Settings fallback){
    var outside=data.index.atNode(at).stream().filter(id->!id.equals(station)).map(data.index.roads::get).findFirst();
    if(outside.isEmpty())return fallback;
    var r=outside.get().record;normal(r,InterchangeConnectors.eligible(data,r));var mesh=r.mesh();boolean first=r.a().equals(at);
    var forward=(first?mesh.first():mesh.last()).left().left().mul(-1);
    return RoadEndpointSections.section(mesh,first,forward.dot(axis)<0);
  }
  private static Node endpoint(RoadData data,BlockPos at,Node node,V axis,UUID station){
    var outside=data.index.atNode(at).stream().filter(id->!id.equals(station)).map(data.index.roads::get).toList();
    if(outside.size()>1)throw new IllegalArgumentException("收费站每端最多连接一条站外完整道路");
    if(outside.isEmpty())return new Node(node.position(),RoadPlanner.yaw(axis),0);
    var r=outside.get(0).record;normal(r,InterchangeConnectors.eligible(data,r));boolean first=r.a().equals(at);var n=first?r.start():r.end();
    return new Node(n.position(),RoadPlanner.yaw(axis),n.grade()*(n.direction().dot(axis)<0?-1:1));
  }
  private static void normal(RoadRecord r,boolean connector){var type=RoadProfile.catalog(r.settings()).type();
    if(r.assembly()!=null&&!connector||r.junction()!=null||r.settings().style().ramp()||LaneTopology.metadata(r).link()!=null||type!=RoadProfile.Type.ORDINARY&&type!=RoadProfile.Type.HIGHWAY)
      throw new IllegalArgumentException("收费站 A/B 仅连接站外完整普通道路或高速道路；匝道请接到更远的站外路段");
  }
  private static CompoundTag key(CompoundTag t){var k=new CompoundTag();for(String name:List.of("Points","Spec","Adjust","SnapshotA","SnapshotB","Connections","Revision","Id","Dimension"))if(t.contains(name))k.put(name,t.get(name).copy());return k;}
  private static CompoundTag fresh(ServerLevel level,ServerPlayer player,CompoundTag t){
    if(!level.dimension().location().toString().equals(t.getString("Dimension")))throw new IllegalArgumentException("收费站选点维度已变化");
    var fresh=payload(level,player,t.getLongArray("Points"),t.hasUUID("Id")?t.getUUID("Id"):null);
    var source=TollCodec.read(fresh.getCompound("Spec"));var requested=TollCodec.read(t.getCompound("Spec"));
    if(!source.a().equals(requested.a())||!source.b().equals(requested.b())||!fresh.getCompound("SnapshotA").equals(t.getCompound("SnapshotA"))||!fresh.getCompound("SnapshotB").equals(t.getCompound("SnapshotB"))||!fresh.getList("Connections",Tag.TAG_COMPOUND).equals(t.getList("Connections",Tag.TAG_COMPOUND))||fresh.getLong("Revision")!=t.getLong("Revision"))
      throw new IllegalArgumentException("端点或连接道路已变化，请重新打开收费站并预览");
    return fresh;
  }
  public static CompoundTag preview(ServerLevel level,ServerPlayer player,CompoundTag command){
    if(command.hasUUID("InlineRoad"))return InlineTollRoads.preview(level,player,command);
    command=TollInputs.expand(level,player,command);
    var source=TollCodec.read(fresh(level,player,command).getCompound("Spec"));var data=RoadData.get(level);var requested=TollCodec.read(command.getCompound("Spec"));
    var original=new Spec(requested.a(),requested.b(),requested.config(),source.portA(),source.portB(),source.path());
    var points=command.getLongArray("Points");BlockPos oldA=BlockPos.of(points[0]),oldB=BlockPos.of(points[1]);
    UUID stationId=command.hasUUID("Id")?command.getUUID("Id"):null;
    Adjust adjust=Adjust.valueOf(command.getString("Adjust"));
    var aligned=alignFreePort(data,original,adjust,oldA,oldB,stationId);
    var lengthFit=TollPlanner.fit(aligned,adjust);var spec=lengthFit.fitted();
    var fit=new TollPlanner.Fit(original,spec,TollPlanner.lengths(spec),original.a().position().distance(spec.a().position()),original.b().position().distance(spec.b().position()));
    var plan=TollPlanner.plan(spec);
    UUID id=command.hasUUID("Id")?command.getUUID("Id"):UUID.randomUUID(),owner=command.hasUUID("Id")?data.index.roads.get(id).record.owner():player==null?new UUID(0,0):player.getUUID();
    var built=new ArrayList<RoadIndex.Built>();var removed=new HashSet<UUID>();if(command.hasUUID("Id"))removed.add(id);
    var moves=new ArrayList<RoadData.NodeMove>();var selected=new HashSet<BlockPos>(List.of(oldA,oldB));
    for(boolean a:new boolean[]{true,false}){
      BlockPos at=a?oldA:oldB;Node target=a?spec.a():spec.b();var outside=data.index.atNode(at).stream().filter(v->!v.equals(id)).map(data.index.roads::get).toList();
      if(outside.size()>1)throw new IllegalArgumentException("收费站每端只能外接一条完整道路");
      for(var other:outside){checkPort(spec,a,other.record,at,InterchangeConnectors.eligible(data,other.record));stage(data,level,player,at,target,other.record,built,removed);}
      Node old=a?original.a():original.b();if(old.position().distance(target.position())>1e-6){
        var marker=RoadData.requireNode(level,at,player);BlockPos to=pos(target);selected.add(to);moves.add(new RoadData.NodeMove(at,to,target,marker.saveWithoutMetadata()));
      }
    }
    BlockPos a=fit.movedA()>1e-6?pos(spec.a()):oldA,b=fit.movedB()>1e-6?pos(spec.b()):oldB;
    var record=new RoadRecord(id,owner,a,b,spec.a(),spec.b(),plan.mesh().settings(),false,4).toll(spec);
    var previous=data.index.roads.get(id);if(previous!=null&&InterchangeConnectors.eligible(data,previous.record))record=InterchangeConnectors.group(record,previous.record.assembly());
    built.add(new RoadIndex.Built(record));
    UUID token=UUID.randomUUID();List<RoadIndex.Built> prepared;
    try(var chunks=RoadWorkChunks.open(level)){prepared=data.prepareAssembly(level,player,built,removed,selected,moves,token);}
    var approval=APPROVALS.computeIfAbsent(data,d->new LinkedHashMap<>());approval.entrySet().removeIf(e->System.nanoTime()-e.getValue().created()>600_000_000_000L||Objects.equals(e.getValue().owner(),player==null?null:player.getUUID()));
    approval.put(token,new Approval(key(command),player==null?null:player.getUUID(),System.nanoTime()));while(approval.size()>4)approval.remove(approval.keySet().iterator().next());
    var reply=new CompoundTag();reply.putUUID("Token",token);reply.put("Spec",TollCodec.write(spec));reply.putDouble("MovedA",fit.movedA());reply.putDouble("MovedB",fit.movedB());reply.putInt("Affected",built.size()-1);
    var roads=new ListTag();for(var road:prepared)roads.add(road.record.save());reply.put("Roads",roads);return reply;
  }
  public static String build(ServerLevel level,ServerPlayer player,CompoundTag t){
    if(t.hasUUID("InlineRoad"))return InlineTollRoads.build(level,player,t);
    t=TollInputs.expand(level,player,t);
    var data=RoadData.get(level);if(!t.hasUUID("Token"))throw new IllegalArgumentException("请先完成收费站预览校验");UUID token=t.getUUID("Token");
    var approval=APPROVALS.getOrDefault(data,new LinkedHashMap<>()).get(token);
    if(approval==null||!approval.key().equals(key(t))||!Objects.equals(approval.owner(),player==null?null:player.getUUID()))throw new IllegalArgumentException("收费站参数已改变，请重新预览");
    try{fresh(level,player,t);data.buildPrepared(level,player,token);}finally{APPROVALS.get(data).remove(token);data.discardPreviews(player==null?null:player.getUUID());}
    return "收费站已建成；A/B 可外接完整普通道路或高速道路";
  }
  private static void stage(RoadData data,ServerLevel level,ServerPlayer player,BlockPos at,Node target,RoadRecord r,List<RoadIndex.Built> built,Set<UUID> removed){
    boolean first=r.a().equals(at);Node old=first?r.start():r.end();if(old.position().distance(target.position())<1e-6)return;
    if(player!=null)RoadData.requireOwner(player,r.owner());
    V axis=r.end().position().sub(r.start().position()).horizontalUnit(),delta=target.position().sub(old.position());Node far=first?r.end():r.start();
    if(r.settings().mode()!=Mode.STRAIGHT&&r.settings().mode()!=Mode.AUTO||!r.alignment().isEmpty()||Math.abs(delta.dot(axis.left()))>1e-5||Math.abs(delta.y())>1e-5||Math.abs(old.grade())>1e-6||Math.abs(far.grade())>1e-6||Math.abs(old.direction().dot(axis))<.99999999||Math.abs(far.direction().dot(axis))<.99999999)
      throw new IllegalArgumentException("自动移动不能完整保留这条曲线/坡道，请先调整站外道路；两个端点均未修改");
    if(target.position().sub(far.position()).dot(old.position().sub(far.position()))<=0||target.position().distance(far.position())<4)throw new IllegalArgumentException("自动调整将越过站外端点，或使外接道路不足 4 格");
    // Metadata positions and lane semantics are never silently remapped. The simple straight
    // case is linked atomically; more complex dependent edits require an explicit road edit.
    if(!r.settings().options().attachments().points().isEmpty()||LaneTopology.metadata(r).points().stream().anyMatch(p->!p.automatic())||!LaneTopology.dependents(data,Set.of(r.id())).isEmpty())
      throw new IllegalArgumentException("外接道路含附属点、手动车道点或匝道，当前调整不能保证原连接；请先处理该路段，原站与两端均保持不变");
    var a=first?new Node(target.position(),r.start().yaw(),r.start().grade()):r.start();var b=first?r.end():new Node(target.position(),r.end().yaw(),r.end().grade());
    built.add(new RoadIndex.Built(new RoadRecord(r.id(),r.owner(),first?pos(target):r.a(),first?r.b():pos(target),a,b,r.settings(),r.automatic(),r.clearance(),List.of(),r.endCaps(),r.buildVersion(),null,List.of(),r.furniturePhase())));removed.add(r.id());
  }
  /** Only the explicitly movable, unconnected endpoint may align to the fixed road tangent. */
  static Spec alignFreePort(RoadData data,Spec spec,Adjust adjust,BlockPos a,BlockPos b,UUID station){
    if(adjust!=Adjust.A&&adjust!=Adjust.B)return spec;
    boolean fixedA=adjust==Adjust.B;BlockPos fixed=fixedA?a:b,free=fixedA?b:a;
    var fixedRoads=data.index.atNode(fixed).stream().filter(id->!id.equals(station)).map(data.index.roads::get).toList();
    if(fixedRoads.size()!=1||data.index.atNode(free).stream().anyMatch(id->!id.equals(station)))return spec;
    var road=fixedRoads.get(0);normal(road.record,InterchangeConnectors.eligible(data,road.record));boolean first=road.record.a().equals(fixed);
    V away=(first?road.mesh.first():road.mesh.last()).left().left().mul(first?-1:1);
    return TollPlanner.alignFreePort(spec,adjust,away);
  }
  private static void checkPort(Spec s,boolean a,RoadRecord r,BlockPos at,boolean connector){
    normal(r,connector);boolean first=r.a().equals(at);V axis=TollInlineGeometry.direction(s,a);Node n=first?r.start():r.end();
    var mesh=r.mesh();var sample=first?mesh.first():mesh.last();V forward=sample.left().left().mul(-1),away=forward.mul(first?1:-1),outside=axis.mul(a?-1:1);
    double alignment=away.dot(outside);
    if(alignment<=0)throw new IllegalArgumentException((a?"A":"B")+" 端外接道路朝向站内；请把收费站放在道路端点外侧");
    if(alignment<.99999999)throw new IllegalArgumentException((a?"A":"B")+" 端外接道路朝外但未与收费站轴线对齐；可选择仅移动 "+(a?"B":"A")+"（该端须未接路），或手动对齐端点");
    var expected=TollPlaza.port(s,a);var nominal=TollPlaza.port(s.config(),a);var actual=RoadEndpointSections.section(mesh,first,forward.dot(axis)<0);
    if(!RoadLanes.counts(nominal).equals(RoadLanes.counts(actual))||Math.abs(RoadProfile.layout(actual,actual.width()).laneWidth()-s.config().laneWidth())>1e-5||Math.abs(expected.width()-actual.width())>1e-5||Math.abs((s.path().isEmpty()?1:expected.thickness())-actual.thickness())>1e-5)
      throw new IllegalArgumentException((a?"A":"B")+" 端外接道路断面不匹配；请先修改外接道路或恢复对应车道数/宽度");
    var e=RoadProfile.layout(expected,expected.width());var v=RoadProfile.layout(actual,actual.width());
    if(Math.abs(e.motorMin()-v.motorMin())>1e-5||Math.abs(e.motorMax()-v.motorMax())>1e-5||Math.abs(e.medianCenter()-v.medianCenter())>1e-5||s.config().leftTraffic()!=actual.options().leftTraffic())throw new IllegalArgumentException("收费站接口与外接道路各向车道位置不一致");
    if(!s.config().twoWay()&&forward.dot(axis)*(s.config().reverse()?-1:1)<.99999999)throw new IllegalArgumentException("外接单向道路行驶方向与收费站相反");
  }
  /** Both new stations and later ordinary-road/ramp transactions pass this boundary. */
  static void validateConnections(List<RoadIndex.Built> all){
    for(var station:all)if(station.record.toll()!=null){var s=station.record.toll();
      for(boolean a:new boolean[]{true,false}){BlockPos at=a?station.record.a():station.record.b();var links=all.stream().filter(r->!r.record.id().equals(station.record.id())&&(r.record.a().equals(at)||r.record.b().equals(at))).toList();
        // Only server-created connector tolls carry an assembly other than their own ID.
        // During insertion the metadata whitelist is committed after this validation.
        if(links.size()>1)throw new IllegalArgumentException("收费站 A/B 每端最多连接一条站外完整道路");for(var r:links)checkPort(s,a,r.record,at,station.record.assembly()!=null&&!station.record.assembly().equals(station.record.id())&&station.record.assembly().equals(r.record.assembly()));
      }
      var prepared=RoadClearance.prepare(station.mesh);
      for(var other:all)if(!other.record.id().equals(station.record.id())&&RoadIndex.overlapXZ(station.mesh,other.mesh,2)){
        for(var contact:RoadClearance.contacts(prepared,other.mesh))if(contact.usableClearance()<s.config().clearHeight()+1.5-.025)
          throw new IllegalArgumentException("道路/匝道的实际路面或分合流过渡侵入收费站，请将连接点移远");
        for(var part:other.record.structures())if(RoadClearance.structureInvades(part,station.mesh,s.config().clearHeight()))throw new IllegalArgumentException("站外道路设施侵入收费站净空");
        for(var part:station.record.structures())if(RoadClearance.structureInvades(part,other.mesh,Math.max(4.25,RoadInfrastructure.clearance(other.record.settings()))))throw new IllegalArgumentException("收费站设施与已有道路通行空间冲突");
      }
    }
  }
  public static void remove(ServerLevel level,ServerPlayer player,UUID id){
    var data=RoadData.get(level);var b=data.index.roads.get(id);if(b==null||b.record.toll()==null)throw new IllegalArgumentException("收费站已不存在");if(player!=null)RoadData.requireOwner(player,b.record.owner());
    var group=b.record.assembly()==null?null:data.interchanges.get(b.record.assembly());
    if(group!=null&&group.getBoolean("IntegratedToll"))throw new IllegalArgumentException("请在双喇叭配置中关闭收费站并整体更新，以同步恢复连接段");
    var restored=b.record.toll().path().isEmpty()?List.<RoadIndex.Built>of():List.of(new RoadIndex.Built(InterchangeConnectors.restored(data,b.record)));
    data.replaceAssembly(level,player,restored,Set.of(id),Set.of(b.record.a(),b.record.b()));
  }
  public static boolean reverseConnection(RoadData data,BlockPos a,BlockPos b){
    Boolean reverse=null;for(BlockPos p:List.of(a,b)){var r=at(data,p);if(r==null||r.toll().config().twoWay())continue;boolean stationA=r.a().equals(p),shouldStart=stationA==r.toll().config().reverse(),wrong=p.equals(a)!=shouldStart;
      if(reverse!=null&&reverse!=wrong)throw new IllegalArgumentException("两个收费站接口行驶方向不兼容");reverse=wrong;}
    return Boolean.TRUE.equals(reverse);
  }
  private TollRoads(){}
}
