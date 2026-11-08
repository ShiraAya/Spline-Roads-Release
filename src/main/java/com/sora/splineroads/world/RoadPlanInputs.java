package com.sora.splineroads.world;

import com.sora.splineroads.core.RoadPlanningBudget;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Server-thread read set for cached structure/sidewalk planning. World-write checks are
 * independently rerun at commit. Neighbors cover terrain collision-shape dependencies. */
final class RoadPlanInputs implements AutoCloseable {
  private static final ThreadLocal<RoadPlanInputs> CURRENT=new ThreadLocal<>();
  private RoadPlanInputs previous;
  private final ServerLevel level;
  private final boolean recording;
  private final Map<Long,BlockState> states=new Long2ObjectOpenHashMap<>();
  private it.unimi.dsi.fastutil.longs.LongOpenHashSet probes=new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
  private final Map<Long,CompoundTag> nodes=new HashMap<>();
  private final Map<Long,Integer> heights=new HashMap<>();
  private RoadPlanInputs(ServerLevel level,boolean recording){
    this.level=level;this.recording=recording;previous=CURRENT.get();CURRENT.set(this);
  }
  static RoadPlanInputs open(ServerLevel level,boolean recording){return new RoadPlanInputs(level,recording);}
  /** Continue an incremental server-thread plan without retaining a thread scope
   * between ticks. The accumulated read set remains exact across all steps. */
  RoadPlanInputs resume(){previous=CURRENT.get();CURRENT.set(this);probes=new it.unimi.dsi.fastutil.longs.LongOpenHashSet();return this;}
  static BlockState state(ServerLevel level,BlockPos pos){
    var state=level.getBlockState(pos);var scope=CURRENT.get();
    if(scope!=null&&scope.recording&&scope.level==level&&scope.probes.add(pos.asLong())){
      // Save the actual states, not hashes: different terrain cannot alias a valid plan.
      for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++){
        var p=pos.offset(dx,dy,dz);
        // putIfAbsent still evaluated getBlockState for every overlapping
        // neighbor. Read each cell once per synchronous planning scope.
        if(!level.isOutsideBuildHeight(p)&&!scope.states.containsKey(p.asLong()))
          {if(scope.states.size()>=4_000_000)throw new IllegalArgumentException("预览地形读取量过大，请分段施工");scope.states.put(p.asLong(),p.equals(pos)?state:level.getBlockState(p));}
      }
    }
    return state;
  }
  static BlockEntity node(ServerLevel level,BlockPos pos){
    var entity=level.getBlockEntity(pos);var scope=CURRENT.get();
    if(scope!=null&&scope.recording&&scope.level==level&&!scope.nodes.containsKey(pos.asLong()))
      scope.nodes.put(pos.asLong(),entity==null?null:entity.saveWithFullMetadata());
    return entity;
  }
  static int height(ServerLevel level,int x,int z){
    int height=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,x,z);
    var scope=CURRENT.get();if(scope!=null&&scope.recording&&scope.level==level)scope.heights.putIfAbsent(BlockPos.asLong(x,0,z),height);
    return height;
  }
  void validate(ServerLevel current){
    if(current!=level)throw new IllegalArgumentException("预览世界已改变，请重新预览");
    var chunks=new HashSet<Long>();for(var keys:List.of(states.keySet(),heights.keySet(),nodes.keySet()))for(long key:keys)chunks.add(new net.minecraft.world.level.ChunkPos(BlockPos.of(key)).toLong());
    try(var lease=RoadWorkChunks.open(level)){
      lease.load(chunks);
      for(var entry:heights.entrySet()){
        RoadPlanningBudget.check();var p=BlockPos.of(entry.getKey());
        if(level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,p.getX(),p.getZ())!=entry.getValue())
          throw new IllegalArgumentException("预览后地形高度已改变，请重新预览");
      }
      for(var entry:states.entrySet()){
        RoadPlanningBudget.check();
        var pos=BlockPos.of(entry.getKey());var now=level.getBlockState(pos);var before=entry.getValue();
        if(!equivalentTerrain(before,now))
          throw new IllegalArgumentException("预览后地形已改变："+pos.toShortString()+"，"+before+" → "+now+"；请重新预览");
      }
      for(var entry:nodes.entrySet()){
        var entity=level.getBlockEntity(BlockPos.of(entry.getKey()));
        if(!Objects.equals(entry.getValue(),entity==null?null:entity.saveWithFullMetadata()))
          throw new IllegalArgumentException("预览后端点已改变，请重新预览");
      }
    }
  }
  /** Vanilla grass decay/spread changes no supporting shape, fluid, or clearance.
   * Construction still rereads actual blocks when forming protected writes, so the
   * current terrain is preserved. Every other material/state change stays strict. */
  static boolean equivalentTerrain(BlockState before,BlockState now){
    if(before.equals(now)||(soil(before)&&soil(now)))return true;
    // Vanilla leaf-distance propagation does not alter shape, fluid or material.
    // Keep persistence/waterlogging and every other property strict.
    var distance=net.minecraft.world.level.block.state.properties.BlockStateProperties.DISTANCE;
    return before.getBlock()==now.getBlock()&&before.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock
        &&before.hasProperty(distance)&&now.hasProperty(distance)
        &&before.setValue(distance,now.getValue(distance)).equals(now);
  }
  private static boolean soil(BlockState state){return state.is(Blocks.GRASS_BLOCK)||state.is(Blocks.DIRT);}
  int retainedStates(){return states.size();}
  @Override public void close(){if(previous==null)CURRENT.remove();else CURRENT.set(previous);previous=null;probes=null;}
}
