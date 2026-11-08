package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.net.RoadNetwork;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public class GantryTool extends Item {
  public GantryTool(){super(new Properties().stacksTo(1));}
  public static int signature(RoadRecord r){return Objects.hash(r.start(),r.end(),r.settings(),r.structures());}
  @Override public InteractionResult useOn(UseOnContext context){
    if(context.getLevel().isClientSide)return InteractionResult.SUCCESS;
    if(!(context.getPlayer() instanceof ServerPlayer player))return InteractionResult.PASS;
    try{
      var data=RoadData.get(player.serverLevel());var hit=context.getClickLocation();
      V point=new V(hit.x,hit.y,hit.z);RoadIndex.Built chosen=null;int slot=-1;double best=Double.POSITIVE_INFINITY;
      for(var id:data.index.at(context.getClickedPos().asLong())){
        var built=data.index.roads.get(id);if(built.record.junction()!=null||built.record.toll()!=null)continue;
        for(int i:RoadGantry.slots(built.mesh)){
          var station=RoadGantry.station(built.mesh,i);var sample=station.sample();
          V forward=sample.left().left().mul(-1);
          double score=Math.abs(point.sub(sample.center()).dot(forward))+Math.abs(point.y()-sample.center().y())*.01;
          if(score<best){best=score;chosen=built;slot=i;}
        }
      }
      if(chosen==null)throw new IllegalArgumentException("请右键龙门架，或右键其下方道路选择最近的一架");
      RoadData.requireOwner(player,chosen.record.owner());
      if(!player.mayBuild()||player.isSpectator())throw new IllegalArgumentException("没有建设权限");
      var t=new CompoundTag();t.putString("Kind","gantry");t.putUUID("Id",chosen.record.id());t.putInt("Slot",slot);
      t.putInt("Count",RoadGantry.slots(chosen.mesh).size());t.putInt("Signature",signature(chosen.record));
      t.putDouble("MaxOffset",RoadGantry.maxOffset(chosen.mesh,slot));
      var at=RoadGantry.station(chosen.mesh,slot);var look=player.getLookAngle();
      t.putInt("PlayerFlow",RoadGantry.playerFlow(at.sample(),new V(look.x,look.y,look.z)));
      var lanes=LaneSections.live(chosen.mesh,at.distance());t.putBoolean("TwoWay",lanes.count(1)>0&&lanes.count(-1)>0);
      t.put("Settings",RoadRecord.writeSettings(chosen.record.settings()));
      t.putBoolean("SignUpdating",data.smartSigns.pending());
      t.put("ForwardSign",SmartSignCodec.write(data.smartSigns.peek(new SmartSigns.Key(chosen.record.id(),slot,1))));
      t.put("ReverseSign",SmartSignCodec.write(data.smartSigns.peek(new SmartSigns.Key(chosen.record.id(),slot,-1))));RoadNetwork.open(player,t);
    }catch(IllegalArgumentException e){player.displayClientMessage(Component.literal(e.getMessage()),false);}
    return InteractionResult.CONSUME;
  }
  @Override public void appendHoverText(ItemStack s,Level level,List<Component> lines,TooltipFlag flag){
    lines.add(Component.translatable("tooltip.splineroads.gantry_editor"));
  }
}
