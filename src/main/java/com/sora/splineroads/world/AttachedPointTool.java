package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.net.RoadNetwork;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import java.util.*;

public final class AttachedPointTool extends Item {
  static boolean supported(RoadRecord r){return SideHosts.attachable(r)||r.junction()==null&&r.toll()==null&&!r.railway()&&!r.settings().style().ramp()&&r.settings().mode()!=Mode.RING&&(RoadProfile.catalog(r.settings()).type()==RoadProfile.Type.ORDINARY||RoadProfile.catalog(r.settings()).type()==RoadProfile.Type.HIGHWAY);}
  public record Pick(RoadIndex.Built road,RoadAttachments.Point point,double distance){}
  /** Match the elevated visible handle; occluding world blocks still stop selection. */
  public static Pick pick(ServerPlayer player){
    var eye=player.getEyePosition();var direction=player.getLookAngle();var end=eye.add(direction.scale(96));
    double stop=player.pick(96,0,false).getLocation().distanceTo(eye)+.7;var index=RoadData.get(player.serverLevel()).index;var ids=new HashSet<UUID>();
    for(int d=0;d<=96;d+=8){var at=eye.add(direction.scale(d));int cx=(int)Math.floor(at.x)>>4,cz=(int)Math.floor(at.z)>>4;for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)ids.addAll(index.inChunk(net.minecraft.world.level.ChunkPos.asLong(cx+x,cz+z)));}
    Pick best=null;
    for(UUID id:ids){var road=index.roads.get(id);if(road==null||!supported(road.record)&&!ElevatedPointTool.supported(road.record))continue;
      for(var point:road.record.settings().options().attachments().points()){
        if(!point.elevated()&&!supported(road.record))continue;
        if(road.record.settings().options().routing().controls().stream().anyMatch(c->c.id().equals(point.id())))continue;
        var p=point.position();var hit=new net.minecraft.world.phys.AABB(p.x()-.26,p.y()+1.6,p.z()-.26,p.x()+.26,p.y()+2.2,p.z()+.26).clip(eye,end);
        if(hit.isPresent()){double distance=hit.get().distanceTo(eye);if(distance<=stop&&(best==null||distance<best.distance()))best=new Pick(road,point,distance);}
      }
    }return best;
  }
  public AttachedPointTool(){super(new Properties().stacksTo(1));}
  static RoadAttachments.Point create(ServerLevel level,ServerPlayer player,RoadIndex.Built road,V hit){
    var q=RoadQueries.horizontal(road.mesh,hit);if(q.horizontalDistance()>q.sample().halfWidth()+.05)throw new IllegalArgumentException("附属点必须位于道路路面");
    V pos=q.sample().center();var existing=nearest(road,pos);if(existing!=null)return existing;
    var point=new RoadAttachments.Point(UUID.randomUUID(),pos,pos,false);
    var metadata=road.record.settings().options().attachments();var points=new ArrayList<>(metadata.points());points.add(point);
    RoadData.get(level).updatePointMetadata(level,player,road.record,metadata.points(points));return point;
  }
  public static RoadAttachments.Point nearest(RoadIndex.Built road,V hit){return road.record.settings().options().attachments().points().stream().filter(p->road.record.settings().options().routing().controls().stream().noneMatch(c->c.id().equals(p.id()))).filter(p->p.position().sub(hit).horizontalLength()<1.1&&hit.y()-p.position().y()>=-.2&&hit.y()-p.position().y()<=2.5).min(Comparator.comparingDouble(p->p.position().sub(hit).horizontalLength())).orElse(null);}
  @Override public InteractionResult useOn(UseOnContext c){
    if(c.getLevel().isClientSide)return InteractionResult.SUCCESS;
    if(c.getPlayer() instanceof ServerPlayer p)try{
      if(!p.mayBuild()||p.isSpectator())throw new IllegalArgumentException("没有建设权限");
      var hit=c.getClickLocation();V v=new V(hit.x,hit.y,hit.z);var road=SideHosts.hostAt(RoadData.get(p.serverLevel()),RoadTool.pick(p),v);if(road==null||!supported(road.record))throw new IllegalArgumentException("请右键道路路面，不能选路口中心铺装");
      RoadData.requireOwner(p,road.record.owner());var point=nearest(road,v);
      if(point==null){point=create(p.serverLevel(),p,road,v);
        road=RoadData.get(p.serverLevel()).index.roads.get(road.record.id());
      }
      RoadNetwork.open(p,payload(road.record,point));
    }catch(IllegalArgumentException e){p.displayClientMessage(Component.literal(e.getMessage()),false);}
    return InteractionResult.CONSUME;
  }
  public static CompoundTag payload(RoadRecord r,RoadAttachments.Point p){var t=new CompoundTag();t.putString("Kind","attachedPoint");t.putBoolean("Elevated",p.elevated());t.put("Road",r.header());t.putUUID("Id",r.id());t.putUUID("Point",p.id());t.putInt("Signature",GantryTool.signature(r));t.put("Position",RoadRecord.writeNode(new Node(p.position(),0,0)));t.put("Origin",RoadRecord.writeNode(new Node(p.origin(),0,0)));t.putBoolean("MarkingsOnly",r.assembly()!=null);return t;}
  public static String edit(ServerLevel level,ServerPlayer player,CompoundTag t){
    var data=RoadData.get(level);var built=data.index.roads.get(t.getUUID("Id"));if(built==null||built.record.toll()!=null)throw new IllegalArgumentException("收费站请使用收费站生成器编辑");var r=built.record;
    if(player!=null){RoadData.requireOwner(player,r.owner());if(!player.mayBuild()||player.isSpectator())throw new IllegalArgumentException("没有建设权限");}
    if(t.getInt("Signature")!=GantryTool.signature(r))throw new IllegalArgumentException("道路已改变，请重新选择附属点");
    var metadata=r.settings().options().attachments();var point=metadata.points().stream().filter(p->p.id().equals(t.getUUID("Point"))).findFirst().orElseThrow(()->new IllegalArgumentException("附属点已改变所属道路，请重新选择"));
    var points=new ArrayList<>(metadata.points());
    if(t.getBoolean("Delete")){
      double d=RoadAttachments.station(built.mesh,point.position());
      if(d>1e-5&&d<built.mesh.length()-1e-5&&!RoadAttachments.paint(built.mesh,d-1e-5).equals(RoadAttachments.paint(built.mesh,d+1e-5)))throw new IllegalArgumentException("附属点两侧标线不同，请先统一两个区间的标线后再删除");
      points.remove(point);if(point.controlled())data.removeAttachedPoint(level,player,r,metadata.points(points),point.id());else data.updatePointMetadata(level,player,r,metadata.points(points));return "附属点已删除";
    }
    throw new IllegalArgumentException("附属点固定在道路断面中央，不能调整坐标或用作曲线控制点");
  }
}
