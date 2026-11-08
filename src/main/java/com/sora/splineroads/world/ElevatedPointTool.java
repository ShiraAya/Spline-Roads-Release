package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.net.RoadNetwork;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/** A distinct central access marker. It never converts an ordinary side junction. */
public final class ElevatedPointTool extends Item {
  public ElevatedPointTool(){super(new Properties().stacksTo(1));}
  public static boolean supported(RoadRecord r){var type=RoadProfile.catalog(r.settings()).type();return r.assembly()==null&&r.junction()==null&&r.toll()==null&&!r.railway()&&r.settings().mode()!=Mode.RING&&LaneTopology.metadata(r).link()==null&&(type==RoadProfile.Type.ORDINARY||type==RoadProfile.Type.HIGHWAY);}
  public static RoadAttachments.Point create(ServerLevel level,ServerPlayer player,RoadIndex.Built road,V hit){
    if(road==null||!supported(road.record))
      throw new IllegalArgumentException("高架出入点只能创建在道路上，不能位于路口或自动生成的分支");
    if(player!=null){if(!player.mayBuild()||player.isSpectator())throw new IllegalArgumentException("没有建设权限");RoadData.requireOwner(player,road.record.owner());}
    var q=RoadQueries.horizontal(road.mesh,hit);
    if(q.horizontalDistance()>q.sample().halfWidth()+.05||Math.abs(hit.y()-q.sample().center().y())>2.5)
      throw new IllegalArgumentException("请在道路路面创建高架出入点");
    var layout=RoadProfile.layout(road.mesh,q.sample());
    V at=q.sample().at(layout.catalog().twoWay()?layout.medianCenter():0,0);
    var metadata=road.record.settings().options().attachments();
    for(var point:metadata.points())if(point.elevated()&&point.position().distance(at)<1.1)return point;
    var point=new RoadAttachments.Point(UUID.randomUUID(),at,q.sample().center(),false,at,true);
    var points=new ArrayList<>(metadata.points());points.add(point);
    RoadData.get(level).updatePointMetadata(level,player,road.record,metadata.points(points));return point;
  }
  @Override public InteractionResult useOn(UseOnContext context){
    if(context.getLevel().isClientSide)return InteractionResult.SUCCESS;
    if(context.getPlayer() instanceof ServerPlayer player)try{
      var hit=context.getClickLocation();var road=RoadTool.pick(player);
      var point=create(player.serverLevel(),player,road,new V(hit.x,hit.y,hit.z));
      var updated=RoadData.get(player.serverLevel()).index.roads.get(road.record.id());
      RoadNetwork.open(player,AttachedPointTool.payload(updated.record,point));
    }catch(IllegalArgumentException failure){player.displayClientMessage(Component.literal(failure.getMessage()),false);}
    return InteractionResult.CONSUME;
  }
}
