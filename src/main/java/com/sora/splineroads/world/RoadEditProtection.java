package com.sora.splineroads.world;

import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.level.BlockEvent;

/** Shared preflight for construction tools; no blocks are changed by these probes. */
final class RoadEditProtection {
  static boolean probing;
  static void check(ServerLevel level,ServerPlayer player,BlockPos pos,BlockState current,BlockState target){
    if(player==null||current.equals(target))return;
    if(!level.mayInteract(player,pos)||!player.mayUseItemAt(pos,Direction.UP,player.getMainHandItem()))throw new IllegalArgumentException("道路施工范围受保护："+pos.toShortString());
    boolean prior=probing;probing=true;
    try{
      if(!current.isAir()&&MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level,pos,current,player)))throw denied(pos);
      var snapshot=BlockSnapshot.create(level.dimension(),level,pos);
      var event=new BlockEvent.EntityPlaceEvent(snapshot,current,player){@Override public BlockState getPlacedBlock(){return target;}@Override public BlockState getState(){return target;}};
      if(MinecraftForge.EVENT_BUS.post(event))throw denied(pos);
    }finally{probing=prior;}
  }
  private static IllegalArgumentException denied(BlockPos pos){return new IllegalArgumentException("道路施工被区域保护拒绝："+pos.toShortString());}
  private RoadEditProtection(){}
}
