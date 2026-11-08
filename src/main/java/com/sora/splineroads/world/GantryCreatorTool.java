package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.V;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** The hit position creates a persistent independent station; Shift opens its editor. */
public final class GantryCreatorTool extends GantryTool {
  @Override public InteractionResult useOn(UseOnContext context){
    if(context.getPlayer()!=null&&context.getPlayer().isShiftKeyDown())return super.useOn(context);
    if(context.getLevel().isClientSide)return InteractionResult.SUCCESS;
    if(!(context.getPlayer() instanceof ServerPlayer player))return InteractionResult.PASS;
    try{
      var data=RoadData.get(player.serverLevel());var hit=context.getClickLocation();V point=new V(hit.x,hit.y,hit.z);
      var built=data.index.at(context.getClickedPos().asLong()).stream().map(data.index.roads::get).filter(Objects::nonNull)
          .filter(b->b.record.junction()==null&&b.record.toll()==null&&!b.mesh.settings().style().ramp())
          .min(Comparator.comparingDouble(b->Math.abs(RoadQueries.horizontal(b.mesh,point).sample().center().y()-point.y()))).orElseThrow(()->new IllegalArgumentException("请右键道路表面，在点击位置创建独立龙门架"));
      data.createGantry(player.serverLevel(),player,built.record.id(),point);
      player.displayClientMessage(Component.literal("独立龙门架已创建；Shift＋右键可编辑，也可使用龙门架编辑器"),true);
    }catch(IllegalArgumentException e){player.displayClientMessage(Component.literal(e.getMessage()),false);}
    return InteractionResult.CONSUME;
  }
  @Override public void appendHoverText(ItemStack s,Level level,List<Component> lines,TooltipFlag flag){lines.add(Component.translatable("tooltip.splineroads.gantry_creator"));}
}
