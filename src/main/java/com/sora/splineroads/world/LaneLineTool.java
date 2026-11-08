package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.net.RoadNetwork;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
public final class LaneLineTool extends Item {
  public LaneLineTool(){super(new Properties().stacksTo(1));}
  @Override public InteractionResult useOn(UseOnContext c){
    if(c.getLevel().isClientSide)return InteractionResult.SUCCESS;
    if(c.getPlayer() instanceof ServerPlayer p)try{
      var built=RoadTool.pick(p);if(built==null)throw new IllegalArgumentException("请右键要编辑的道路");
      if(built.record.junction()!=null||built.record.toll()!=null)throw new IllegalArgumentException("请选中路口外的独立路段");
      RoadData.requireOwner(p,built.record.owner());
      if(!RoadProfile.modern(built.record.settings().style()))throw new IllegalArgumentException("请先把旧式道路更新为普通道路或高速公路");
      var t=new CompoundTag();t.putString("Kind","laneLines");t.putUUID("Id",built.record.id());t.putInt("Signature",GantryTool.signature(built.record));t.put("Settings",RoadRecord.writeSettings(built.record.settings()));
      var lines=new ListTag();int selected=0,n=0;double best=Double.POSITIVE_INFINITY;
      var hit=c.getClickLocation();double lateral=RoadQueries.horizontal(built.mesh,new V(hit.x,hit.y,hit.z)).lateral();
      for(var line:RoadLaneLines.lines(built.mesh)){var e=new CompoundTag();e.putString("Key",line.key());e.putString("Label",line.label());e.putDouble("Offset",line.offset());lines.add(e);double d=Math.abs(line.offset()-lateral);if(d<best){best=d;selected=n;}n++;}
      var at=RoadQueries.horizontal(built.mesh,new V(hit.x,hit.y,hit.z)).sample();
      double station=Math.min(built.mesh.length()-1e-6,Math.max(0,at.distance()));var range=RoadAttachments.range(built.mesh,station);var paint=RoadAttachments.paint(built.mesh,station);t.putDouble("Station",station);t.putDouble("RangeStart",range.start());t.putDouble("RangeEnd",range.end());t.put("Settings",RoadRecord.writeSettings(built.record.settings().options(built.record.settings().options().laneLines(paint.lines()).hideArrows(paint.hideArrows()))));t.putDouble("RoadYaw",RoadPlanner.yaw(at.left().left().mul(-1)));t.putDouble("PlayerYaw",p.getYRot());t.put("Lines",lines);t.putInt("Selected",selected);t.putDouble("Width",built.record.settings().width());RoadNetwork.open(p,t);
    }catch(IllegalArgumentException e){p.displayClientMessage(Component.literal(e.getMessage()),false);}
    return InteractionResult.CONSUME;
  }
}
