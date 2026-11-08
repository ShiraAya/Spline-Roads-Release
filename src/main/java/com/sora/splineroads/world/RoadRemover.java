package com.sora.splineroads.world;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public final class RoadRemover extends Item {
  public RoadRemover() {
    super(new Properties().stacksTo(1));
  }

  private void select(Player player) {
    if (!(player instanceof ServerPlayer p)) return;
    try {
      var road = RoadTool.pick(p);
      if (road == null) p.displayClientMessage(Component.literal("请指向 256 格内已加载的道路，再右键选择删除"), true);
      else RoadTool.openRoad(p, road.record, true);
    } catch (IllegalArgumentException e) {
      p.displayClientMessage(Component.literal(e.getMessage()), false);
    }
  }

  @Override
  public InteractionResult useOn(UseOnContext context) {
    select(context.getPlayer());
    return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
  }

  @Override
  public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
    select(player);
    return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
  }

  @Override
  public void appendHoverText(ItemStack s, Level l, List<Component> lines, TooltipFlag flag) {
    lines.add(Component.translatable("tooltip.splineroads.remover"));
  }
}
