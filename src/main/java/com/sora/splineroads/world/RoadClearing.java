package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** Shared ordinary-road and railbed excavation policy. Ownership/rail clearance
 * and region-protection checks remain at the transaction boundary. */
final class RoadClearing {
  static void validate(ServerLevel level,BlockPos pos,BlockState state,boolean placedSidewalk){
    if(!RoadBlocks.isCollider(state)&&!state.is(SplineRoads.TUNNEL_AIR.get())&&!placedSidewalk&&state.getDestroySpeed(level,pos)<0)
      throw new IllegalArgumentException("无法清除不可破坏方块："+pos.toShortString());
    if(state.hasBlockEntity()&&!RoadBlocks.isCollider(state))
      throw new IllegalArgumentException("道路或隧道范围被方块实体占用，无法清除："+pos.toShortString());
  }
  private RoadClearing(){}
}
