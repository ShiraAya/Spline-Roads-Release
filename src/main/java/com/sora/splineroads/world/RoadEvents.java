package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.net.RoadNetwork;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.*;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = SplineRoads.ID)
public final class RoadEvents {
  @SubscribeEvent
  public static void infill(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock e) {
    if(!(e.getItemStack().getItem() instanceof net.minecraft.world.item.BlockItem item)||e.getFace()==null)return;
    var level=e.getLevel();var fill=item.getBlock().defaultBlockState();
    var target=e.getPos().relative(e.getFace());
    var hit=e.getHitVec().getLocation();double coordinate=switch(e.getFace().getAxis()){case X->hit.x-e.getPos().getX();case Y->hit.y-e.getPos().getY();case Z->hit.z-e.getPos().getZ();};
    boolean inside=coordinate>.001&&coordinate<.999;
    var index=RoadBlocks.index(level);
    // Complete a partial equipment beam cell first, so the attached block touches its surface.
    if(inside&&index!=null&&RoadBlocks.gantryCell(index,e.getPos())&&RoadBlocks.canInfill(level,e.getPos(),fill))target=e.getPos();
    else if(!RoadBlocks.canInfill(level,target,fill)) {
      if(!inside)return;
      target=e.getPos();if(!RoadBlocks.canInfill(level,target,fill))return;
    }
    var context=net.minecraft.world.item.context.BlockPlaceContext.at(
        new net.minecraft.world.item.context.BlockPlaceContext(e.getEntity(),e.getHand(),e.getItemStack(),e.getHitVec()),target,e.getFace());
    fill=item.getBlock().getStateForPlacement(context);
    if(fill==null||!RoadBlocks.canInfill(level,target,fill))return;
    if(!e.getEntity().mayBuild()||!e.getEntity().mayUseItemAt(target,e.getFace(),e.getItemStack()))return;
    if(level instanceof ServerLevel server&&e.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
      var snapshot=net.minecraftforge.common.util.BlockSnapshot.create(level.dimension(),level,target);
      final var placed=fill;
      var placeEvent=new BlockEvent.EntityPlaceEvent(snapshot,level.getBlockState(target.relative(e.getFace().getOpposite())),player){
        @Override public net.minecraft.world.level.block.state.BlockState getPlacedBlock(){return placed;}
        @Override public net.minecraft.world.level.block.state.BlockState getState(){return placed;}
      };
      if(net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(placeEvent))return;
      if(!RoadData.get(server).infill(server,target,fill,player))return;
      if(!player.isCreative())e.getItemStack().shrink(1);
      level.playSound(null,target,fill.getSoundType(level,target,player).getPlaceSound(),net.minecraft.sounds.SoundSource.BLOCKS,1,.8f);
    }
    e.setCanceled(true);e.setCancellationResult(net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide));
  }
  @SubscribeEvent
  public static void watch(ChunkWatchEvent.Watch e) {
    RoadNetwork.watch(e.getPlayer(), e.getPos().toLong(), true);
    RoadData data = RoadData.get(e.getLevel());
    data.repairChunk(e.getLevel(), e.getPos());
    RoadIndex index = data.index;
    for (UUID id : index.inChunk(e.getPos().toLong()))
      RoadNetwork.road(e.getPlayer(), index.roads.get(id).record);
  }

  @SubscribeEvent
  public static void unwatch(ChunkWatchEvent.UnWatch e) {
    RoadNetwork.watch(e.getPlayer(), e.getPos().toLong(), false);
  }

  @SubscribeEvent
  public static void dimension(PlayerEvent.PlayerChangedDimensionEvent e) {
    if (e.getEntity() instanceof net.minecraft.server.level.ServerPlayer player){
      RoadSplitter.cancel(player.getUUID());RoadData.forgetPreviews(player.getUUID());RoadNetwork.dimensionChanged(player);
    }
  }

  @SubscribeEvent
  public static void breakBlock(BlockEvent.BreakEvent e) {
    if(RailbedBuildQueue.protectionProbe()||RoadEditProtection.probing)return;
    if (e.getLevel() instanceof ServerLevel level) {
      RoadData data = RoadData.get(level);
      if (RoadBlocks.isCollider(e.getState())
          && e.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player
          && data.excavateSupport(level, e.getPos(), player)) {
        e.setCanceled(true);
        return;
      }
      if (RoadBlocks.isCollider(e.getState()) || data.linked(e.getPos())) {
        e.setCanceled(true);
        e.getPlayer().displayClientMessage(Component.literal("请用道路连接器删除相连道路，再拆除端点"), true);
      } else if (level.getBlockEntity(e.getPos()) instanceof NodeEntity n
          && n.owner != null
          && !n.owner.equals(e.getPlayer().getUUID())
          && !e.getPlayer().hasPermissions(2)) e.setCanceled(true);
    }
  }

  @SubscribeEvent
  public static void explosion(ExplosionEvent.Detonate e) {
    if (e.getLevel() instanceof ServerLevel level) {
      RoadData d = RoadData.get(level);
      e.getAffectedBlocks()
          .removeIf(p -> d.linked(p) || RoadBlocks.isCollider(level.getBlockState(p)));
    }
  }

  @SubscribeEvent
  public static void tick(net.minecraftforge.event.TickEvent.ServerTickEvent e){
    if(e.phase==net.minecraftforge.event.TickEvent.Phase.END){RoadNetwork.tick(e.getServer());ManualPlanner.tick();RailbedBuildQueue.tick(e.getServer());if(e.getServer().getTickCount()%100==0)RoadData.expirePreviews();}
  }
  @SubscribeEvent
  public static void stop(net.minecraftforge.event.server.ServerStoppingEvent e){ManualPlanner.stop();RailbedBuildQueue.stop();RoadSplitter.clear();RoadData.clearPreviews();RoadNetwork.stop();}
  @SubscribeEvent
  public static void logout(PlayerEvent.PlayerLoggedOutEvent e) {
    if(e.getEntity() instanceof net.minecraft.server.level.ServerPlayer p)ManualPlanner.forget(p);RoadNetwork.forget(e.getEntity().getUUID());RoadData.forgetPreviews(e.getEntity().getUUID());
  }
}
