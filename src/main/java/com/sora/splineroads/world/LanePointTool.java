package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.net.RoadNetwork;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import java.util.*;

public final class LanePointTool extends Item {
  public LanePointTool(){super(new Properties().stacksTo(1));}
  private record Pending(UUID token,long expires) {}
  private static final Map<ServerPlayer,Pending> OPENING=new WeakHashMap<>();
  static UUID pendingToken(ServerPlayer p){var v=OPENING.get(p);return v==null?null:v.token();}
  public static void acknowledge(ServerPlayer p,UUID token){var v=OPENING.get(p);if(v!=null&&v.token().equals(token))OPENING.remove(p);}
  private static void open(ServerPlayer p,RoadRecord r,LanePoints.Point point){
    var t=payload(RoadData.get(p.serverLevel()),r,point);UUID token=UUID.randomUUID();
    OPENING.put(p,new Pending(token,p.serverLevel().getGameTime()+100));t.putUUID("Interaction",token);RoadNetwork.open(p,t);
  }
  public record Pick(RoadRecord road,LanePoints.Point point,double distance){}
  public static Pick pick(ServerPlayer player){
    var eye=player.getEyePosition();double stop=player.pick(96,0,false).getLocation().distanceTo(eye)+.7;
    return pick(RoadData.get(player.serverLevel()).index,eye,player.getLookAngle(),stop,player.isShiftKeyDown());
  }
  static Pick pick(RoadIndex index,Vec3 eye,Vec3 direction,double stop,boolean preferAutomatic){
    var end=eye.add(direction.scale(96));var hits=new ArrayList<Pick>();
    var origin=new V(eye.x,eye.y,eye.z);var vector=new V(direction.x,direction.y,direction.z);
    for(var marker:index.lanePointCandidates(origin,vector,96)){
      var road=index.roads.get(marker.road());if(road==null)continue;
      var hit=RoadPointIndex.bounds(marker.point()).clip(eye,end);
      if(hit.isPresent()&&hit.get().distanceTo(eye)<stop)hits.add(new Pick(road.record,marker.point(),hit.get().distanceTo(eye)));
    }
    // Candidates retain road insertion/point order, including exact distance ties.
    hits.sort(Comparator.comparingDouble(Pick::distance));if(hits.isEmpty())return null;
    double nearest=hits.get(0).distance();
    return hits.stream().filter(h->h.distance()<nearest+.15)
        .min(Comparator.comparingInt(h->h.point().automatic()==preferAutomatic?0:1)).orElse(hits.get(0));
  }
  public static LanePoints.Point create(ServerLevel level,ServerPlayer player,UUID id,V hit){var data=RoadData.get(level);var b=data.index.roads.get(id);if(b==null||b.record.railway()||b.record.junction()!=null||b.record.toll()!=null)throw new IllegalArgumentException("请在独立路段的机动车道上放点");if(player!=null)RoadData.requireOwner(player,b.record.owner());for(var other:data.index.roads.values())if(other.record.toll()!=null&&TollPlanner.contains(other.mesh,hit,.03,other.record.toll().config().clearHeight()))throw new IllegalArgumentException("收费站内部和 A/B 接口不能创建匝道车道点");var lane=LanePoints.clicked(b.mesh,hit);var md=LaneTopology.metadata(b.record);var existing=md.points().stream().filter(p->!p.automatic()&&p.lane()==lane.index()&&p.position().distance(lane.position())<.65).findFirst();if(existing.isPresent())return existing.get();var p=LanePoints.point(UUID.randomUUID(),LanePoints.Origin.MANUAL,b.mesh,lane.station(),lane.index());var points=new ArrayList<>(md.points());points.add(p);update(level,player,b.record,md.points(points),Set.of());return p;}
  private void select(ServerPlayer p,V hit){var pending=OPENING.get(p);if(pending!=null&&p.serverLevel().getGameTime()<pending.expires())return;try{if(!p.mayBuild()||p.isSpectator())throw new IllegalArgumentException("没有建设权限");var picked=pick(p);if(picked!=null&&!(p.isShiftKeyDown()&&picked.point().automatic()&&LaneTopology.metadata(picked.road()).points().stream().noneMatch(v->!v.automatic()&&v.position().distance(picked.point().position())<.65))){RoadData.requireOwner(p,picked.road().owner());open(p,picked.road(),picked.point());return;}var b=RoadTool.pick(p);if(b==null||hit==null)throw new IllegalArgumentException("请右键机动车道创建蓝色车道点，或右键已有蓝色点");var point=create(p.serverLevel(),p,b.record.id(),hit);open(p,RoadData.get(p.serverLevel()).index.roads.get(b.record.id()).record,point);}catch(IllegalArgumentException e){p.displayClientMessage(Component.literal(e.getMessage()),false);}}
  @Override public InteractionResult useOn(UseOnContext c){if(c.getPlayer() instanceof ServerPlayer p){var h=c.getClickLocation();select(p,new V(h.x,h.y,h.z));}return InteractionResult.sidedSuccess(c.getLevel().isClientSide);}
  @Override public InteractionResultHolder<ItemStack> use(Level l,Player p,InteractionHand hand){if(p instanceof ServerPlayer sp)select(sp,null);return InteractionResultHolder.sidedSuccess(p.getItemInHand(hand),l.isClientSide);}
  public static CompoundTag payload(RoadData data,RoadRecord r,LanePoints.Point p){var t=new CompoundTag();t.putString("Kind","lanePoint");t.put("Road",r.header());t.putUUID("Id",r.id());t.putUUID("Point",p.id());t.putInt("Signature",r.header().hashCode());t.putBoolean("Supported",r.assembly()==null&&LanePoints.supported(r.settings()));var refs=LaneTopology.references(data,LanePoints.Ref.lane(r.id(),p.id()));var dependencies=LaneTopology.dependents(data,new HashSet<>(refs));var all=new TreeSet<UUID>(refs);all.addAll(dependencies);var list=new ListTag();all.forEach(id->list.add(StringTag.valueOf(id.toString())));for(var cut:LaneTopology.metadata(r).cuts())if(cut.connection().equals(p.id())&&cut.replacement()!=null){all.add(cut.replacement());all.addAll(LaneTopology.dependents(data,Set.of(cut.replacement())));}
    list.clear();all.forEach(id->list.add(StringTag.valueOf(id.toString())));t.put("Dependencies",list);return t;}
  public static String edit(ServerLevel level,ServerPlayer player,CompoundTag t){var data=RoadData.get(level);var b=data.index.roads.get(t.getUUID("Id"));if(b==null)throw new IllegalArgumentException("道路已不存在");var r=b.record;if(player!=null)RoadData.requireOwner(player,r.owner());if(t.getInt("Signature")!=r.header().hashCode())throw new IllegalArgumentException("道路已变化，请重新打开车道点");var md=LaneTopology.metadata(r);var p=LaneTopology.point(r,t.getUUID("Point"));if(p.automatic())throw new IllegalArgumentException("自动尽头点不能删除或切换车道");var points=new ArrayList<>(md.points());Set<UUID> removed=new HashSet<>();
    if(t.contains("MergeLength")){
      if(r.assembly()!=null||!LanePoints.supported(r.settings()))throw new IllegalArgumentException("仅独立且支持车道连接的道路可设置合流缩减");
      double length=t.getDouble("MergeLength");var next=p.merge(length);
      if(length>0)LaneMerge.event(r.rawMesh(),next);else if(length<0)LaneExpansion.addition(r.rawMesh(),next,8);
      if(p.mergeLength()>0&&length==0&&md.cuts().stream().anyMatch(c->c.connection().equals(p.id())&&c.replacement()!=null))
        throw new IllegalArgumentException("此空位已有补入匝道，请先删除或改为其他汇入模式，再取消合流缩减");
      points.set(points.indexOf(p),next);update(level,player,r,md.points(points),Set.of());return length>0?"外侧车道已合流缩减，下游空位可补入":length<0?"已设置外侧扩流，下游新增一条同向车道":"车道增减已取消，主路已恢复";
    }

    if(t.getBoolean("Delete")){for(var cut:md.cuts())if(cut.connection().equals(p.id())&&cut.replacement()!=null)removed.add(cut.replacement());var refs=LaneTopology.references(data,LanePoints.Ref.lane(r.id(),p.id()));removed.addAll(refs);removed.addAll(LaneTopology.dependents(data,removed));LaneDeletes.requireConfirmation(t,removed);if(player!=null)for(UUID id:removed)RoadData.requireOwner(player,data.index.roads.get(id).record.owner());points.remove(p);}else{if(p.mergeLength()!=0)throw new IllegalArgumentException("请先取消车道增减，再切换此车道点");int lane=t.getInt("Lane");var selected=LanePoints.lane(b.mesh,LanePoints.lane(b.mesh,p).station(),lane);points.set(points.indexOf(p),p.at(selected,b.mesh));}
    update(level,player,r,md.points(points),removed);return t.getBoolean("Delete")?"手动车道点及确认的依赖匝道已删除":"车道已切换，依赖匝道已同步重建";}
  static void update(ServerLevel level,ServerPlayer player,RoadRecord r,LanePoints.Data md,Set<UUID> deleted){
    var data=RoadData.get(level);boolean referenced=false;
    for(var p:LaneTopology.metadata(r).points()) {
      var next=md.points().stream().filter(v->v.id().equals(p.id())).findFirst().orElse(null);
      if(!p.equals(next)&&data.index.laneReferenced(LanePoints.Ref.lane(r.id(),p.id()))){referenced=true;break;}
    }
    if(!referenced&&deleted.isEmpty()&&LaneMerge.sameDefinitions(LaneTopology.metadata(r),md)){data.updateLanePointMetadata(level,player,r,md);return;}
    var removed=new HashSet<>(deleted);removed.add(r.id());data.replaceAssembly(level,player,List.of(new RoadIndex.Built(r.withLanePoints(md))),removed,Set.of(r.a(),r.b()));
  }
  @Override public void appendHoverText(ItemStack s,Level l,List<Component> lines,TooltipFlag f){lines.add(Component.literal("右键机动车道吸附到该车道中轴线；右键蓝色点编辑"));lines.add(Component.literal("Shift＋右键自动点创建手动点；重合时 Shift 选择自动点"));}
}
