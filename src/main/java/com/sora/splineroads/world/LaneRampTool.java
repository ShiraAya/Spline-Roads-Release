package com.sora.splineroads.world;
import com.sora.splineroads.core.LanePoints;
import com.sora.splineroads.net.RoadNetwork;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import java.util.*;

public final class LaneRampTool extends Item {
  private static LanePoints.Options freshOptions(){return new LanePoints.Options(LanePoints.Path.AUTO,LanePoints.Departure.TEMPORARY,LanePoints.Arrival.MERGE,24,32,LanePoints.Elevation.AUTO,LanePoints.Landing.FLEXIBLE);}
  public LaneRampTool(){super(new Properties().stacksTo(1));}
  private void select(ServerPlayer p,ItemStack stack){try{if(p.isShiftKeyDown()){clearSelection(p,stack);var reset=new CompoundTag();reset.putString("Kind","laneRampReset");RoadNetwork.open(p,reset);p.displayClientMessage(Component.literal("已清除匝道选点与预览"),true);return;}if(!p.mayBuild()||p.isSpectator())throw new IllegalArgumentException("没有建设权限");var state=stack.getOrCreateTag();String dimension=p.level().dimension().location().toString();if(!dimension.equals(state.getString("LaneDimension"))){clearSelection(p,stack);state.putString("LaneDimension",dimension);}var data=RoadData.get(p.serverLevel());var picked=LanePointTool.pick(p);LanePoints.Ref ref=null;
    if(picked!=null){ref=LanePoints.Ref.lane(picked.road().id(),picked.point().id());LaneRamps.host(LaneTopology.records(data),ref);RoadData.requireOwner(p,picked.road().owner());}else{var hit=p.pick(96,0,false);var at=net.minecraft.core.BlockPos.containing(hit.getLocation());double best=1.1;for(var e:data.junctions.entrySet()){var spec=RampJunctions.spec(data,e.getKey());var v=spec.center();double d=hit.getLocation().distanceTo(new net.minecraft.world.phys.Vec3(v.x(),v.y(),v.z()));if(d<best){ref=LanePoints.Ref.junction(e.getKey());best=d;}}}
    if(ref==null){var selected=RoadTool.pick(p);if(selected!=null&&LaneTopology.metadata(selected.record).link()!=null&&selected.record.assembly()==null){RoadData.requireOwner(p,selected.record.owner());if(!state.hasUUID("LaneEdit")||!state.getUUID("LaneEdit").equals(selected.record.id()))clearSelection(p,stack);state.putString("LaneDimension",dimension);state.putUUID("LaneEdit",selected.record.id());RoadNetwork.open(p,LaneRamps.tunnelChoices(LaneRamps.payload(selected.record,false),LaneTopology.records(data)));return;}
      if(state.contains("LaneTo")||state.hasUUID("LaneEdit")){var resume=new CompoundTag();resume.putString("Kind","laneRampResume");
        var fallback=new CompoundTag();if(state.hasUUID("LaneEdit")){var existing=data.index.roads.get(state.getUUID("LaneEdit"));if(existing!=null&&LaneTopology.metadata(existing.record).link()!=null)fallback=LaneRamps.payload(existing.record,false);}
        else {fallback.putString("Kind","laneRamp");fallback.put("From",state.getCompound("LaneFrom").copy());fallback.put("To",state.getCompound("LaneTo").copy());var preview=state.getCompound("LanePreview");var options=preview.contains("Road")?LaneTopology.metadata(RoadRecord.load(preview.getCompound("Road"))).link().options():freshOptions();fallback.put("Options",LanePointCodec.options(options));}
        if(!fallback.isEmpty())resume.put("Fallback",LaneRamps.tunnelChoices(fallback,LaneTopology.records(data)));RoadNetwork.open(p,resume);return;}}
    if(ref==null)throw new IllegalArgumentException("只能选择蓝色车道点，汇入还可选择路口中心；普通端点、红色附属点和路面不能替代");
    if(!state.contains("LaneFrom")){if(ref.junction()!=null)throw new IllegalArgumentException("先选择汇出车道点 A，路口中心只能作为汇入点 B");clearSelection(p,stack);state.putString("LaneDimension",dimension);state.put("LaneFrom",LanePointCodec.ref(ref));p.displayClientMessage(Component.literal("已选汇出 A；请右键汇入车道点或路口中心 B"),false);return;}
    if(state.getCompound("LaneFrom").equals(LanePointCodec.ref(ref)))throw new IllegalArgumentException("请另选汇入点 B；Shift＋右键清除选点");
    cancelPlanning(p,stack);state.put("LaneTo",LanePointCodec.ref(ref));var t=new CompoundTag();t.putString("Kind","laneRamp");t.put("From",state.getCompound("LaneFrom").copy());t.put("To",state.getCompound("LaneTo").copy());t.put("Options",LanePointCodec.options(freshOptions()));RoadNetwork.open(p,LaneRamps.tunnelChoices(t,LaneTopology.records(data)));
    }catch(IllegalArgumentException e){p.displayClientMessage(Component.literal(e.getMessage()),false);}}
  @Override public InteractionResult useOn(UseOnContext c){if(c.getPlayer() instanceof ServerPlayer p)select(p,c.getItemInHand());return InteractionResult.sidedSuccess(c.getLevel().isClientSide);}
  @Override public InteractionResultHolder<ItemStack> use(Level l,Player p,InteractionHand hand){if(p instanceof ServerPlayer sp)select(sp,p.getItemInHand(hand));return InteractionResultHolder.sidedSuccess(p.getItemInHand(hand),l.isClientSide);}
  static void cancelPlanning(ServerPlayer player,ItemStack stack){
    com.sora.splineroads.net.RoadPlanningJobs.cancel(player.getUUID());
    var state=stack.getOrCreateTag();var preview=state.getCompound("LanePreview");
    if(preview.hasUUID("Token"))RoadData.forgetPreview(player.getUUID(),preview.getUUID("Token"));
    state.remove("LanePreview");
  }
  static void clearSelection(ServerPlayer player,ItemStack stack){cancelPlanning(player,stack);clearSelection(stack);}
  static void clearSelection(ItemStack stack){var t=stack.getOrCreateTag();for(String key:List.of("LaneFrom","LaneTo","LanePreview","LaneDimension","LaneEdit"))t.remove(key);}
  /** The editor shares the normal dependency review and restoration transaction. */
  public static String deletion(ServerPlayer player,ItemStack tool,CompoundTag command,boolean commit){
    if(!command.hasUUID("Id"))throw new IllegalArgumentException("请先选择已建匝道");
    LaneRamps.selection(player,tool,command);
    var data=RoadData.get(player.serverLevel());var road=data.index.roads.get(command.getUUID("Id")).record;
    cancelPlanning(player,tool);
    if(commit){LaneDeletes.remove(player.serverLevel(),player,command);clearSelection(tool);return "匝道已删除，主路车道与附属设施已恢复";}
    var payload=new CompoundTag();payload.putString("Kind","road");payload.putBoolean("DeleteOnly",true);payload.putBoolean("RampDelete",true);
    payload.putUUID("Id",road.id());payload.putInt("Signature",road.header().hashCode());payload.put("Road",road.header());
    payload.put("From",command.getCompound("From").copy());payload.put("To",command.getCompound("To").copy());
    LaneDeletes.payload(data,road.id(),payload);com.sora.splineroads.net.RoadActionContext.echo(command,payload);RoadNetwork.open(player,payload);return "";
  }
  @Override public void appendHoverText(ItemStack s,Level l,List<Component> lines,TooltipFlag f){lines.add(Component.literal("先选蓝色汇出 A，再选蓝色汇入 B 或路口中心；右键已建匝道编辑"));lines.add(Component.literal("Shift＋右键清除选点与预览（空气、方块、点均可）"));}
}
