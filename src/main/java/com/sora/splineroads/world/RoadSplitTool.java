package com.sora.splineroads.world;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
/** All mutation goes through the server's preview-token transaction. */
public final class RoadSplitTool extends Item {
  public static Runnable activateClient=()->{};
  public RoadSplitTool(){super(new Properties().stacksTo(1));}
  @Override public InteractionResult useOn(UseOnContext context){if(context.getLevel().isClientSide)activateClient.run();return InteractionResult.sidedSuccess(context.getLevel().isClientSide);}
  @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand){if(level.isClientSide)activateClient.run();return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand),level.isClientSide);}
}
