package com.sora.splineroads.world;

import com.sora.splineroads.config.StreetServerConfig;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.StreetPlan.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** Bounded server-thread capture. At most one new chunk is acquired per step. */
public final class StreetTerrainCapture {
  private final ServerLevel level;private final Iterator<Cell> remaining;private final Map<Cell,StreetTerrain.Chunk> result=new TreeMap<>();
  private Cell cell;private int column=256;private int[] heights;private byte[] ground;private long[] fingerprints;
  private int completed;private final int count;private boolean done;
  public StreetTerrainCapture(ServerLevel level,Set<Cell> cells){
    if(cells.size()>StreetServerConfig.MAX_CHUNKS.get())throw new IllegalArgumentException("选区超过服务器路网区块上限");
    this.level=level;count=cells.size();remaining=new TreeSet<>(cells).iterator();
  }
  public boolean step(){
    if(!level.getServer().isSameThread())throw new IllegalStateException("地形必须在服务端线程读取");if(done)return true;
    if(column==256){if(!remaining.hasNext()){done=true;return true;}cell=remaining.next();column=0;heights=new int[256];ground=new byte[256];fingerprints=new long[256];}
    long until=System.nanoTime()+StreetServerConfig.WORK_MICROS.get()*1000L;int budget=StreetServerConfig.CAPTURE_COLUMNS.get();
    try(var lease=RoadWorkChunks.open(level)){
      lease.load(Set.of(ChunkPos.asLong(cell.x(),cell.z())));
      do{int x=cell.x()*16+column%16,z=cell.z()*16+column/16;var value=column(level,x,z);heights[column]=value.height;ground[column]=(byte)value.ground.ordinal();fingerprints[column]=value.fingerprint;column++;budget--;}while(column<256&&budget>0&&System.nanoTime()<until);
    }
    if(column==256){result.put(cell,new StreetTerrain.Chunk(heights,ground,fingerprints));completed++;}
    return false;
  }
  public int completed(){return completed;}public int count(){return count;}public StreetTerrain snapshot(){return new StreetTerrain(result);}
  record Column(int height,Ground ground,long fingerprint){}
  static Column column(ServerLevel level,int x,int z){
    int height=level.getHeight(Heightmap.Types.WORLD_SURFACE,x,z);Ground ground=Ground.LAND;
    while(height>level.getMinBuildHeight()&&plant(level.getBlockState(new BlockPos(x,height-1,z))))height--;
    if(height<=level.getMinBuildHeight()||height+10>=level.getMaxBuildHeight())ground=Ground.OBSTACLE;
    long hash=0xcbf29ce484222325L;
    // Hash the natural deck/earthwork band and all potential overhead fixtures.
    // A building's roof is an obstacle even when its interior is hollow.
    for(int y=Math.max(level.getMinBuildHeight(),height-5);y<Math.min(level.getMaxBuildHeight(),height+11);y++){
      var p=new BlockPos(x,y,z);var state=level.getBlockState(p);var entity=state.hasBlockEntity()?level.getBlockEntity(p):null;
      // Grass can spread to dirt below transparent road colliders between ticks.
      // Treat only this natural surface-cover change as equivalent; height,
      // every other block, fluid and block-entity payload stay fingerprinted.
      var fingerprintState=state.is(Blocks.GRASS_BLOCK)?Blocks.DIRT.defaultBlockState():state;
      hash=(hash^Block.getId(fingerprintState))*0x100000001b3L;hash=(hash^(entity==null?0:entity.saveWithFullMetadata().hashCode()))*0x100000001b3L;
      if(!state.getFluidState().isEmpty())ground=Ground.WATER;
      else if(RoadBlocks.isCollider(state)||entity instanceof NodeEntity){if(ground==Ground.LAND)ground=Ground.ROAD;}
      else if(entity!=null||!natural(state))ground=Ground.OBSTACLE;
    }
    hash=(hash^height)*0x100000001b3L;return new Column(height,ground,hash);
  }
  static boolean natural(BlockState state){
    var block=state.getBlock();return state.isAir()||block==Blocks.GRASS_BLOCK||state.is(BlockTags.DIRT)||state.is(BlockTags.BASE_STONE_OVERWORLD)
        ||state.is(BlockTags.SAND)||block==Blocks.GRAVEL||block==Blocks.CLAY||block==Blocks.SNOW||block==Blocks.SNOW_BLOCK||block==Blocks.BEDROCK
        ||block==Blocks.GRASS||block==Blocks.TALL_GRASS||block==Blocks.FERN||block==Blocks.LARGE_FERN||state.is(BlockTags.FLOWERS);
  }
  private static boolean plant(BlockState state){var b=state.getBlock();return b==Blocks.GRASS||b==Blocks.TALL_GRASS||b==Blocks.FERN||b==Blocks.LARGE_FERN||state.is(BlockTags.FLOWERS);}
}
