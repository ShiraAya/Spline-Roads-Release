package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.net.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;

/** The preview token owns a complete immutable plan, never a delete-and-rebuild command. */
public final class RoadSplitter {
  public record Hit(RoadIndex.Built road,double station,V point,double distance){}
  record Plan(RoadRecord original,RoadSplitGeometry.Pair pair,List<RoadRecord> roads,RoadMetadataPatch<RoadRecord> streets,RoadMetadataPatch<CompoundTag> junctions,RoadMetadataPatch<CompoundTag> interchanges,Map<Integer,UUID> signOwners,RoadData.SplitPrepared prepared){}
  private record Pending(String dimension,UUID owner,long created,Plan plan){}
  private static final Map<UUID,Pending> PENDING=new LinkedHashMap<>();
  public static Hit hit(Collection<RoadIndex.Built> roads,V eye,V direction,double limit){
    Hit best=null;for(var road:roads){var m=road.mesh;if(eye.x()+limit<m.min().x()||eye.x()-limit>m.max().x()||eye.z()+limit<m.min().z()||eye.z()-limit>m.max().z()||eye.y()+limit<m.min().y()||eye.y()-limit>m.max().y())continue;var ray=RoadQueries.ray(road.mesh,eye,direction,limit);if(!Double.isFinite(ray))continue;double distance=ray;if(best==null||distance<best.distance()){V point=eye.add(direction.mul(distance));best=new Hit(road,RoadSplitGeometry.station(road.mesh,point),point,distance);}}return best;
  }
  private static Hit hit(ServerPlayer player){var eye=player.getEyePosition();var look=player.getLookAngle();var origin=new V(eye.x,eye.y,eye.z);var direction=new V(look.x,look.y,look.z);return hit(RoadData.get(player.serverLevel()).index.rayCandidates(origin,direction,64),origin,direction,64);}
  public static void cancel(UUID owner){PENDING.entrySet().removeIf(e->e.getValue().owner().equals(owner));}
  static void expire(long now){PENDING.entrySet().removeIf(e->now-e.getValue().created()>120_000_000_000L);}
  public static void clear(){PENDING.clear();}
  private static void requireTool(ServerPlayer player){if(!player.mayBuild()||player.isSpectator()||!(player.getMainHandItem().getItem() instanceof RoadSplitTool||player.getOffhandItem().getItem() instanceof RoadSplitTool))throw new IllegalArgumentException("请手持道路分割器并确保拥有建设权限");}
  public static CompoundTag preview(ServerPlayer player,CompoundTag command){
    requireTool(player);cancel(player.getUUID());var reply=new CompoundTag();reply.putString("Kind","splitCheck");reply.putLong("Request",command.getLong("Request"));reply.putString("Dimension",player.level().dimension().location().toString());
    var built=RoadData.get(player.serverLevel()).index.roads.get(command.getUUID("Id"));
    if(built==null||built.record.header().hashCode()!=command.getInt("Signature"))throw new IllegalArgumentException("道路已改变，请重新选择");
    var r=built.record;requireReach(player,r,command.getDouble("Station"));
    var mode=RoadSlicing.Connection.valueOf(command.getString("Mode"));var plan=plan(player.serverLevel(),player,r,command.getDouble("Station"),mode);
    UUID token=UUID.randomUUID();expire(System.nanoTime());PENDING.put(token,new Pending(reply.getString("Dimension"),player.getUUID(),System.nanoTime(),plan));while(PENDING.size()>1&&(PENDING.size()>4||PENDING.values().stream().mapToLong(p->p.plan().prepared().cells()).sum()>RoadLimits.MAX_EDIT_CELLS))PENDING.remove(PENDING.keySet().iterator().next());
    reply.putUUID("Token",token);reply.putUUID("Id",r.id());reply.putDouble("Station",plan.pair().station());reply.putDouble("Length",r.mesh().length());reply.put("Node",RoadRecord.writeNode(plan.pair().node()));reply.putString("Mode",mode.name());var paths=new ListTag();if(mode==RoadSlicing.Connection.JUNCTION)for(var road:plan.roads())paths.add(road.header());reply.put("PreviewRoads",paths);return reply;
  }
  public static String commit(ServerPlayer player,CompoundTag command){
    requireTool(player);var pending=PENDING.remove(command.getUUID("Token"));if(pending==null||!pending.owner().equals(player.getUUID())||!pending.dimension().equals(player.level().dimension().location().toString())||System.nanoTime()-pending.created()>120_000_000_000L)throw new IllegalArgumentException("分割预览已过期，请重新选择");
    var plan=pending.plan();var current=RoadData.get(player.serverLevel()).index.roads.get(plan.original().id());
    if(current==null||current.record!=plan.original())throw new IllegalArgumentException("道路已改变，请重新选择");
    requireReach(player,plan.original(),plan.pair().station());
    commit(player.serverLevel(),player,plan);return "道路已分割为两段，共享端点与原有设施已保存";
  }
  private static void requireReach(ServerPlayer player,RoadRecord road,double station){
    if(!Double.isFinite(station)||station<=0||station>=road.mesh().length())throw new IllegalArgumentException("分割位置无效");
    var eye=player.getEyePosition();var sample=RoadSlicing.at(road.mesh(),station);
    // A wide road may be selected at its edge, so measure to the selected cross-section.
    V point=new V(eye.x,eye.y,eye.z);double lateral=Math.max(-sample.halfWidth(),Math.min(sample.halfWidth(),point.sub(sample.center()).dot(sample.left())));
    if(sample.at(lateral,0).distance(point)>64)throw new IllegalArgumentException("已离开分割点的操作范围，请重新选择");
  }
  static Plan plan(ServerLevel level,ServerPlayer player,RoadRecord road,double station,RoadSlicing.Connection mode){
    var data=RoadData.get(level);if(player!=null)RoadData.requireOwner(player,road.owner());var pair=RoadSplitGeometry.split(road,station,mode,UUID.randomUUID());
    var occupied=new ArrayList<RoadSplitGeometry.Range>();boolean managedSource=false;
    for(var descriptor:data.interchanges.values()){
      if(ContinuousRoads.sources(descriptor).stream().anyMatch(s->s.road().id().equals(road.id())))managedSource=true;
      for(var entry:descriptor.getList("OccupiedRoads",Tag.TAG_COMPOUND)){var v=(CompoundTag)entry;if(v.getUUID("Road").equals(road.id()))occupied.add(new RoadSplitGeometry.Range(v.getDouble("From"),v.getDouble("To"),"这里属于立交生成器实际占用区间"));}
    }
    if(road.assembly()!=null&&!managedSource)throw new IllegalArgumentException("受管立交或组合内部道路不能直接分割");RoadSplitGeometry.requireClear(occupied,station,station);
    if(level.getBlockEntity(pair.pos())!=null&&!RoadBlocks.isCollider(level.getBlockState(pair.pos())))throw new IllegalArgumentException("新端点位置被方块实体占用");
    var first=pair.first();var second=pair.second();if(managedSource){if(occupied.stream().noneMatch(v->v.intersects(0,station)))first=assembly(first,null);if(occupied.stream().noneMatch(v->v.intersects(station,road.mesh().length())))second=assembly(second,null);}
    var replacement=new LinkedHashMap<UUID,RoadRecord>();replacement.put(first.id(),first);replacement.put(second.id(),second);
    var streets=new LinkedHashMap<>(data.streets);var logical=AutoJunctions.logical(data).get(road.id());RoadSplitGeometry.Pair full=!streets.containsKey(road.id())?null:RoadSplitGeometry.split(logical,mappedStation(logical,road,pair.station()),mode,second.id());
    if(full!=null){streets.put(first.id(),full.first());streets.put(second.id(),full.second());}
    var junctions=new LinkedHashMap<UUID,CompoundTag>();data.junctions.forEach((id,v)->junctions.put(id,remapJunction(v,road,pair.second().id())));
    var interchanges=new LinkedHashMap<UUID,CompoundTag>();for(var entry:data.interchanges.entrySet())interchanges.put(entry.getKey(),remapAssembly(entry.getValue(),road,pair));
    // Explicit point identity is the authority for connector ownership, including both ends.
    Set<UUID> secondPoints=new HashSet<>();for(var p:second.settings().options().lanePoints().points())secondPoints.add(p.id());
    for(var built:data.index.roads.values())if(!built.record.id().equals(road.id())){var r=remapLinks(built.record,road.id(),second.id(),secondPoints);if(r!=built.record)replacement.put(r.id(),r);}
    streets.replaceAll((id,r)->remapLinks(r,road.id(),pair.second().id(),secondPoints));
    if(mode==RoadSlicing.Connection.JUNCTION){
      if(RoadProfile.catalog(road.settings()).type()!=RoadProfile.Type.ORDINARY)throw new IllegalArgumentException("当前道路不支持平交路口，可使用连续模式");
      var config=AutoJunctions.config(pair.pos(),road.owner());var draft=AutoJunctions.plan(List.of(first,second),List.of(config));
      double left=station,right=station;for(var r:draft.roads())if(r.junction()==null){if(r.id().equals(first.id()))left=RoadSplitGeometry.station(road.mesh(),r.mesh().last().center());else if(r.id().equals(second.id()))right=RoadSplitGeometry.station(road.mesh(),r.mesh().first().center());}
      RoadSplitGeometry.requireClear(pair.protection(),left,right);RoadSplitGeometry.requireClear(occupied,left,right);
      for(var r:draft.roads())replacement.put(r.id(),r);streets.put(first.id(),first);streets.put(second.id(),second);for(var c:draft.centers())junctions.put(c.getUUID("Id"),c);
      for(var r:draft.roads())for(var old:data.index.roads.values())if(!old.record.id().equals(road.id())&&!replacement.containsKey(old.record.id())&&RoadIndex.overlapXZ(r.mesh(),old.mesh,1))Interchanges.checkExternal(r.mesh(),old.mesh,null);
    }
    var owners=new HashMap<Integer,UUID>();for(var r:List.of(first,second))for(int slot:RoadGantry.slots(r.mesh()))owners.put(slot,r.id());
    for(var state:data.smartSigns.road(road.id()))if(!owners.containsKey(state.key().slot()))throw new IllegalArgumentException("原标牌缺少可迁移的龙门架站位");
    var nodeTag=new CompoundTag();nodeTag.putUUID("Owner",road.owner());nodeTag.put("Node",RoadRecord.writeNode(pair.node()));nodeTag.putBoolean("HeightExplicit",true);nodeTag.putBoolean("HeadingLocked",true);nodeTag.putBoolean("GradeLocked",true);
    var moves=new RoadData.NodeMove(null,pair.pos(),pair.node(),nodeTag);var removed=new HashSet<>(replacement.keySet());removed.retainAll(data.index.roads.keySet());
    var roads=mode==RoadSlicing.Connection.JUNCTION?data.splitJunctionStructures(level,List.copyOf(replacement.values()),road.id()):List.copyOf(replacement.values());var built=roads.stream().map(RoadIndex.Built::new).toList();
    // The encoded batch must fit before the first world write, including all manual text.
    var packet=RoadNetwork.batchPacket(level,roads,removed,pair.pos());RoadWire.split(packet);
    var prepared=data.prepareSplit(level,player,built,removed,moves,mode==RoadSlicing.Connection.JUNCTION);
    return new Plan(road,pair,roads,RoadMetadataPatch.between(data.streets,streets,r->r),RoadMetadataPatch.between(data.junctions,junctions,CompoundTag::copy),RoadMetadataPatch.between(data.interchanges,interchanges,CompoundTag::copy),Map.copyOf(owners),prepared);
  }
  static void commit(ServerLevel level,ServerPlayer player,Plan plan){var data=RoadData.get(level);
    // Validate every changed metadata entry before prepare/commit can write a block.
    plan.streets().validate(data.streets);plan.junctions().validate(data.junctions);plan.interchanges().validate(data.interchanges);
    data.commitSplit(level,player,plan.prepared(),()->{
    plan.streets().apply(data.streets);plan.junctions().apply(data.junctions);plan.interchanges().apply(data.interchanges);data.smartSigns.splitKeys(plan.original().id(),plan.signOwners());
  });}
  private static RoadRecord assembly(RoadRecord r,UUID group){return new RoadRecord(r.id(),r.owner(),r.a(),r.b(),r.start(),r.end(),r.settings(),r.automatic(),r.clearance(),r.structures(),r.endCaps(),r.buildVersion(),group,r.alignment(),r.furniturePhase(),r.junction(),r.toll(),r.fragment());}
  private static LanePoints.Ref ref(LanePoints.Ref ref,UUID source,UUID second,Set<UUID> points){return source.equals(ref.road())&&points.contains(ref.point())?LanePoints.Ref.lane(second,ref.point()):ref;}
  /** Map the saved path station, never reproject its laterally offset median marker. */
  private static double mappedStation(RoadRecord whole,RoadRecord visible,double station){
    double mapped=station+visible.settings().options().ends().trimmedStart()-whole.settings().options().ends().trimmedStart();
    V point=RoadSlicing.at(visible.mesh(),station).center();
    if(mapped>=0&&mapped<=whole.mesh().length()&&RoadSlicing.at(whole.mesh(),mapped).center().distance(point)<1e-6)return mapped;
    return RoadSplitGeometry.station(whole.mesh(),point);
  }
  static RoadRecord remapLinks(RoadRecord road,UUID source,UUID second,Set<UUID> points){var data=road.settings().options().lanePoints();var old=data.link();if(old==null)return road;var from=ref(old.from(),source,second,points);var to=ref(old.to(),source,second,points);return from==old.from()&&to==old.to()?road:road.withLanePoints(data.link(new LanePoints.Link(from,to,old.options(),old.junctionMouth(),old.targetOffset(),old.protectedMerge(),old.rectangularClosure())));}
  static CompoundTag remapJunction(CompoundTag before,RoadRecord old,UUID second){var tag=before.copy();if(tag.getLong("CenterPos")==old.b().asLong()||Arrays.stream(tag.getLongArray("Ports")).anyMatch(v->v==old.b().asLong())){var arms=tag.getList("ArmRoads",Tag.TAG_STRING);for(int i=0;i<arms.size();i++)if(arms.getString(i).equals(old.id().toString()))arms.set(i,StringTag.valueOf(second.toString()));}return tag;}
  static CompoundTag remapAssembly(CompoundTag before,RoadRecord old,RoadSplitGeometry.Pair pair){
    boolean hasSource=before.getList("ContinuousSources",Tag.TAG_COMPOUND).stream().anyMatch(v->{var t=((CompoundTag)v).getCompound("Road");return t.hasUUID("Id")&&t.getUUID("Id").equals(old.id());});
    boolean occupiedSource=before.getList("OccupiedRoads",Tag.TAG_COMPOUND).stream().anyMatch(v->{var t=(CompoundTag)v;return t.hasUUID("Road")&&t.getUUID("Road").equals(old.id());});
    if(!hasSource&&!occupiedSource)return before;
    var result=before.copy();var sources=new ListTag();for(var entry:before.getList("ContinuousSources",Tag.TAG_COMPOUND)){var source=(CompoundTag)entry;var r=RoadRecord.load(source.getCompound("Road"));if(!r.id().equals(old.id())){sources.add(source.copy());continue;}
      var split=RoadSplitGeometry.split(r,mappedStation(r,old,pair.station()),pair.first().fragment().connection(),pair.second().id());for(var half:List.of(split.first(),split.second())){var next=source.copy();next.put("Road",half.header());sources.add(next);}}
    result.put("ContinuousSources",sources);var occupied=new ListTag();var owners=new HashSet<UUID>();
    for(var entry:before.getList("OccupiedRoads",Tag.TAG_COMPOUND)){var t=((CompoundTag)entry).copy();if(t.getUUID("Road").equals(old.id())){if(Math.min(t.getDouble("From"),t.getDouble("To"))>=pair.station()){t.putUUID("Road",pair.second().id());t.putDouble("From",t.getDouble("From")-pair.station());t.putDouble("To",t.getDouble("To")-pair.station());}owners.add(t.getUUID("Road"));}occupied.add(t);}result.put("OccupiedRoads",occupied);
    for(String key:List.of("SignTopology","CorridorRoles")){var list=new ListTag();for(var entry:before.getList(key,Tag.TAG_COMPOUND)){var t=(CompoundTag)entry;if(!t.getUUID("Road").equals(old.id())){list.add(t.copy());continue;}for(UUID owner:owners){var next=t.copy();next.putUUID("Road",owner);list.add(next);}}if(before.contains(key))result.put(key,list);}
    if(sources.stream().anyMatch(t->((CompoundTag)t).getCompound("Road").hasUUID("Id")&&((CompoundTag)t).getCompound("Road").getUUID("Id").equals(pair.second().id()))){var nodes=new LinkedHashSet<Long>();for(long v:before.getLongArray("PreservedNodes"))nodes.add(v);nodes.add(pair.pos().asLong());result.putLongArray("PreservedNodes",nodes.stream().mapToLong(Long::longValue).toArray());}
    return result;
  }
  private RoadSplitter(){}
}
