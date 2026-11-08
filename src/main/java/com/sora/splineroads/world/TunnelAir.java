package com.sora.splineroads.world;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.*;
/** Excavated air with no collision, visibility or ticking, but no liquid refill. */
public final class TunnelAir extends AirBlock implements LiquidBlockContainer {
  // This marker must count as a stored block. LevelChunk discards air writes in an
  // otherwise empty section, which would silently lose the liquid barrier.
  public TunnelAir(){super(Properties.of().noCollission().noOcclusion().replaceable().strength(-1,3600000).noLootTable());}
  /** Logical empty space, independent of Minecraft's section storage air flag. */
  public static boolean isAirSpace(BlockState state){return state.isAir()||state.getBlock() instanceof TunnelAir;}
  @Override public boolean canPlaceLiquid(BlockGetter world,BlockPos pos,BlockState state,Fluid fluid){return false;}
  @Override public boolean placeLiquid(LevelAccessor world,BlockPos pos,BlockState state,FluidState fluid){return false;}
}
