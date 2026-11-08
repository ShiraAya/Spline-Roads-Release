package com.sora.splineroads.world;

import com.sora.splineroads.net.RoadNetwork;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public final class JunctionTool extends Item {
  public JunctionTool(){super(new Properties().stacksTo(1));}
  @Override public InteractionResult useOn(UseOnContext context) {
    if(context.getLevel().isClientSide)return InteractionResult.SUCCESS;
    if(!(context.getPlayer() instanceof ServerPlayer player))return InteractionResult.PASS;
    try {
      BlockPos p=RoadTool.endpointAtHit(context);
      if(player.serverLevel().getBlockEntity(p) instanceof NodeEntity) {
        RoadData.requireNode(player.serverLevel(),p,player);var tag=context.getItemInHand().getOrCreateTag();
        if(tag.getLongArray("Points").length==0){
          var data=RoadData.get(player.serverLevel());
          for(var entry:data.junctions.entrySet()){var saved=entry.getValue();var points=saved.getLongArray("Points");
            if(saved.contains("CenterPos")&&saved.getLong("CenterPos")==p.asLong()||points.length>0&&points[0]==p.asLong()){
              RoadNetwork.open(player,Junctions.payload(player.serverLevel(),player,new long[0],entry.getKey()));return InteractionResult.CONSUME;
            }
          }
        }
        String dim=player.level().dimension().location().toString();long[] old=dim.equals(tag.getString("Dimension"))?tag.getLongArray("Points"):new long[0];
        if(old.length>=7)throw new IllegalArgumentException("已选中心和 6 个接入口；右键空气打开设置，Shift＋右键空气重新选点");
        for(long point:old)if(point==p.asLong())throw new IllegalArgumentException("此点已选；右键空气打开设置");
        long[] next=Arrays.copyOf(old,old.length+1);next[old.length]=p.asLong();tag.putLongArray("Points",next);tag.putString("Dimension",dim);
        player.displayClientMessage(Component.literal(old.length==0?"中心已选；继续选择 3–6 个道路接入口":("已选 "+old.length+" 个接入口；"+(old.length>=3?"右键空气打开设置，或继续选点":"至少需要 3 个接入口"))),true);
      } else open(player,context.getItemInHand());
    } catch(IllegalArgumentException e){player.displayClientMessage(Component.literal(e.getMessage()),false);}
    return InteractionResult.CONSUME;
  }
  private void open(ServerPlayer p,ItemStack stack) {
    var hit=RoadTool.pick(p);
    if(hit!=null&&hit.record.junction()!=null){RoadNetwork.open(p,Junctions.selectedPayload(p.serverLevel(),p,hit.record));return;}
    if(hit!=null&&hit.record.assembly()!=null){var saved=RoadData.get(p.serverLevel()).interchanges.get(hit.record.assembly());if(saved!=null&&saved.getBoolean("YJunction")){RoadNetwork.open(p,YJunctionTool.payload(p.serverLevel(),p,saved.getLongArray("Points"),hit.record.assembly()));return;}}
    var t=stack.getOrCreateTag();if(!p.level().dimension().location().toString().equals(t.getString("Dimension")))throw new IllegalArgumentException("先选中心，再选 3–6 个接入口");
    RoadNetwork.open(p,Junctions.payload(p.serverLevel(),p,t.getLongArray("Points"),null));
  }
  @Override public InteractionResultHolder<ItemStack> use(Level level,Player who,InteractionHand hand) {
    ItemStack stack=who.getItemInHand(hand);
    if(!level.isClientSide&&who instanceof ServerPlayer p)try {
      if(who.isShiftKeyDown()){stack.getOrCreateTag().remove("Points");var t=new CompoundTag();t.putString("Kind","junctionReset");RoadNetwork.open(p,t);}
      else open(p,stack);
    }catch(IllegalArgumentException e){who.displayClientMessage(Component.literal(e.getMessage()),false);}
    return InteractionResultHolder.sidedSuccess(stack,level.isClientSide);
  }
  @Override public void appendHoverText(ItemStack s,Level l,List<Component> lines,TooltipFlag f){lines.add(Component.literal("中心端点 → 3–6 个接入口 → 右键空气设置"));lines.add(Component.literal("右键编辑普通路口、环岛或 Y 字路口；Shift＋右键空气清除选点"));}
}
