package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.network.chat.Component;

/** A persistent reversible marker at an exact continuous split, with ordinary-junction semantics. */
public final class JunctionPointTool extends Item {
  public JunctionPointTool(){super(new Properties().stacksTo(1));}
  static UUID source(RoadRecord r){return r.fragment()==null?r.id():r.fragment().source();}
  static void requireDifferent(ServerLevel level,BlockPos a,BlockPos b){
    if(RoadBuildWorld.entity(level,a) instanceof NodeEntity x&&RoadBuildWorld.entity(level,b) instanceof NodeEntity y&&x.junctionSource!=null&&x.junctionSource.equals(y.junctionSource))throw new IllegalArgumentException("请选择不同道路上的路口点");
  }
  static void requireDifferent(ServerLevel level,BlockPos node,RoadRecord host){
    if(RoadBuildWorld.entity(level,node) instanceof NodeEntity n&&n.junctionSource!=null&&n.junctionSource.equals(source(host)))throw new IllegalArgumentException("路口点和附属点必须位于不同道路");
  }
  static void remove(ServerLevel level, ServerPlayer player, BlockPos pos) {
    var marker = RoadData.requireNode(level, pos, player);
    var data = RoadData.get(level);
    if (marker.junctionSource == null || !marker.visibleMarker())
      throw new IllegalArgumentException("路口点已不存在");
    requireFreeSeam(data, pos, marker.junctionSource, player);
    // The two road records can carry independent edits. Keep their exact deck
    // seam and retire only the player-facing junction marker.
    ManualPlannerHistory.capture(level, List.of(pos.asLong()));
    marker.showJunctionPoint(false);
  }

  static void requireFreeSeam(RoadData data, BlockPos pos, UUID source, ServerPlayer player) {
    var roads = data.index.atNode(pos);
    if (AutoJunctions.center(data, pos) != null || roads.size() != 2)
      throw new IllegalArgumentException("请先撤除使用该路口点的连接");
    for (UUID id : roads) {
      var road = data.index.roads.get(id).record;
      if (!AutoJunctions.ordinary(road) || road.fragment() == null
          || road.fragment().connection() != RoadSlicing.Connection.CONTINUOUS
          || !source.equals(road.fragment().source()))
        throw new IllegalArgumentException("请先撤除使用该路口点的连接");
      if (player != null) RoadData.requireOwner(player, road.owner());
    }
  }

  static BlockPos create(ServerLevel level,ServerPlayer player,RoadIndex.Built road,V hit){
    if(player!=null&&(!player.mayBuild()||player.isSpectator()))throw new IllegalArgumentException("没有建设权限");
    if(road==null||!AutoJunctions.ordinary(road.record)||road.record.railway()||road.record.toll()!=null||road.record.settings().style().ramp())throw new IllegalArgumentException("路口点只能创建在独立普通道路上");
    if(player!=null)RoadData.requireOwner(player,road.record.owner());
    var q=RoadQueries.horizontal(road.mesh,hit);if(q.horizontalDistance()>q.sample().halfWidth()+.05)throw new IllegalArgumentException("请选择普通道路路面");
    for (BlockPos end : List.of(road.record.a(), road.record.b())) {
      if (!(level.getBlockEntity(end) instanceof NodeEntity marker) || marker.visibleMarker()
          || !marker.junctionSource.equals(source(road.record))) continue;
      var seam = end.equals(road.record.a()) ? road.mesh.first() : road.mesh.last();
      if (Math.abs(q.sample().distance() - seam.distance()) > .75
          || Math.abs(hit.sub(seam.center()).dot(seam.left().left())) > .75) continue;
      RoadData.requireNode(level, end, player);
      JunctionPointTool.requireFreeSeam(RoadData.get(level), end, marker.junctionSource, player);
      ManualPlannerHistory.capture(level, List.of(end.asLong()));
      marker.showJunctionPoint(true);
      return end;
    }
    var plan=RoadSplitter.plan(level,player,road.record,q.sample().distance(),RoadSlicing.Connection.CONTINUOUS,source(road.record));
    RoadSplitter.commit(level,player,plan);return plan.pair().pos();
  }
  @Override public InteractionResult useOn(UseOnContext c){
    if(c.getLevel().isClientSide)return InteractionResult.SUCCESS;
    if(c.getPlayer() instanceof ServerPlayer p)try(var work=RoadWorkChunks.open(p.serverLevel())){
      if (p.isShiftKeyDown()) {
        remove(p.serverLevel(), p, RoadTool.endpointAtHit(c));
        p.displayClientMessage(Component.literal("路口点已删除"), false);
      } else {
        var hit=c.getClickLocation();create(p.serverLevel(),p,RoadTool.pick(p),new V(hit.x,hit.y,hit.z));p.displayClientMessage(Component.literal("路口点已创建；使用道路连接器连接端点或另一道路的路口点"),false);
      }
    }catch(IllegalArgumentException failure){p.displayClientMessage(Component.literal(failure.getMessage()),false);}
    return InteractionResult.CONSUME;
  }
}
