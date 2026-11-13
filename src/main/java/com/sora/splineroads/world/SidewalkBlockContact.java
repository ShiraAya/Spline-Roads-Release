package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadGeometry.V;
import com.sora.splineroads.core.RoadRaster;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.*;

/** Derived contact only. Vanilla states, inventories, drops and the road ledger are untouched. */
public final class SidewalkBlockContact {
  public static boolean eligible(BlockGetter level,BlockPos pos,BlockState state){
    return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft")
        && !state.isAir()&&!state.hasBlockEntity()&&state.getFluidState().isEmpty()
        && Block.isShapeFullBlock(state.getCollisionShape(level,pos));
  }
  public static double bottom(BlockGetter level,RoadIndex index,BlockPos pos,BlockState state){
    if(index==null||!RoadBlocks.isCollider(level.getBlockState(pos.below()))||!eligible(level,pos,state))return 0;
    var faces=new ArrayList<List<V>>();var cell=new RoadRaster.Cell(pos.getX(),pos.getY()-1,pos.getZ());
    synchronized(index){
      for(var id:index.inChunk(new ChunkPos(pos).toLong())){
        var built=index.roads.get(id);if(built!=null)faces.addAll(built.prepareCollision().structureTops(cell,p->p.material().name().startsWith("WALK_")));
      }
    }
    return com.sora.splineroads.core.SidewalkBlockContact.bottom(pos.getX(),pos.getY(),pos.getZ(),faces);
  }
  public static VoxelShape collision(BlockGetter level,BlockPos pos,RoadIndex index,VoxelShape road){
    var upper=pos.above();double bottom=bottom(level,index,upper,level.getBlockState(upper));
    return bottom<0?Shapes.joinUnoptimized(road,Shapes.box(0,1+bottom,0,1,1,1),BooleanOp.OR):road;
  }
  private SidewalkBlockContact(){}
}
