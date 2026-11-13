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
    if(level.getBlockEntity(a) instanceof NodeEntity x&&level.getBlockEntity(b) instanceof NodeEntity y&&x.junctionSource!=null&&x.junctionSource.equals(y.junctionSource))throw new IllegalArgumentException("请选择不同道路上的路口点");
  }
  static void requireDifferent(ServerLevel level,BlockPos node,RoadRecord host){
    if(level.getBlockEntity(node) instanceof NodeEntity n&&n.junctionSource!=null&&n.junctionSource.equals(source(host)))throw new IllegalArgumentException("路口点和附属点必须位于不同道路");
  }
  static BlockPos create(ServerLevel level,ServerPlayer player,RoadIndex.Built road,V hit){
    if(player!=null&&(!player.mayBuild()||player.isSpectator()))throw new IllegalArgumentException("没有建设权限");
    if(road==null||!AutoJunctions.ordinary(road.record)||road.record.railway()||road.record.toll()!=null||road.record.settings().style().ramp())throw new IllegalArgumentException("路口点只能创建在独立普通道路上");
    if(player!=null)RoadData.requireOwner(player,road.record.owner());
    var q=RoadQueries.horizontal(road.mesh,hit);if(q.horizontalDistance()>q.sample().halfWidth()+.05)throw new IllegalArgumentException("请选择普通道路路面");
    var plan=RoadSplitter.plan(level,player,road.record,q.sample().distance(),RoadSlicing.Connection.CONTINUOUS,source(road.record));
    RoadSplitter.commit(level,player,plan);return plan.pair().pos();
  }
  @Override public InteractionResult useOn(UseOnContext c){
    if(c.getLevel().isClientSide)return InteractionResult.SUCCESS;
    if(c.getPlayer() instanceof ServerPlayer p)try(var work=RoadWorkChunks.open(p.serverLevel())){
      var hit=c.getClickLocation();create(p.serverLevel(),p,RoadTool.pick(p),new V(hit.x,hit.y,hit.z));p.displayClientMessage(Component.literal("路口点已创建；使用道路连接器连接端点或另一道路的路口点"),false);
    }catch(IllegalArgumentException failure){p.displayClientMessage(Component.literal(failure.getMessage()),false);}
    return InteractionResult.CONSUME;
  }
}
