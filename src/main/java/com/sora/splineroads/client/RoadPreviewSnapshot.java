package com.sora.splineroads.client;

import com.sora.splineroads.core.RoadGeometry.Mesh;
import com.sora.splineroads.world.RoadFillEntity;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.material.FluidState;

/** Copies loaded chunk palettes on the client thread in short steps. Planning
 * reads only these private palettes; it never calls a live Level off-thread. */
final class RoadPreviewSnapshot implements BlockGetter {
  private record Fill(BlockState state,double cap){}
  private final Map<Long,List<PalettedContainer<BlockState>>> blocks=new HashMap<>();
  final Map<Long,LevelChunk> observed=new HashMap<>();
  private final Map<BlockPos,Fill> fills=new HashMap<>();
  private final int minY,height,minX,maxX,maxZ;
  private int x,z;
  RoadPreviewSnapshot(Level level,Mesh mesh){
    minY=level.getMinBuildHeight();height=level.getHeight();
    minX=(int)Math.floor(mesh.min().x()-90)>>4;maxX=(int)Math.floor(mesh.max().x()+90)>>4;
    x=minX;z=(int)Math.floor(mesh.min().z()-90)>>4;maxZ=(int)Math.floor(mesh.max().z()+90)>>4;
  }
  boolean step(Level level){
    long until=System.nanoTime()+2_000_000;
    do {
      long key=ChunkPos.asLong(x,z);
      var chunk=level.getChunkSource().getChunk(x,z,ChunkStatus.FULL,false);
      if(chunk instanceof LevelChunk loaded&&!(loaded instanceof EmptyLevelChunk)){
        var sections=new ArrayList<PalettedContainer<BlockState>>();
        for(var section:loaded.getSections())sections.add(section.hasOnlyAir()?null:section.getStates().copy());
        blocks.put(key,Collections.unmodifiableList(sections));observed.put(key,loaded);
        for(var entry:loaded.getBlockEntities().entrySet())if(entry.getValue() instanceof RoadFillEntity fill)
          fills.put(entry.getKey().immutable(),new Fill(fill.fill(),fill.cap()));
      }
      if(++x>maxX){x=minX;z++;}
    }while(z<=maxZ&&System.nanoTime()<until);
    return z>maxZ;
  }
  boolean loaded(BlockPos p){return blocks.containsKey(ChunkPos.asLong(p.getX()>>4,p.getZ()>>4));}
  boolean visible(com.sora.splineroads.core.RoadStructures.Part part){
    double extent=part.halfExtent();
    for(int x=(int)Math.floor(Math.min(part.a().x(),part.b().x())-extent)>>4;x<=((int)Math.floor(Math.max(part.a().x(),part.b().x())+extent)>>4);x++)
      for(int z=(int)Math.floor(Math.min(part.a().z(),part.b().z())-extent)>>4;z<=((int)Math.floor(Math.max(part.a().z(),part.b().z())+extent)>>4);z++)
        if(blocks.containsKey(ChunkPos.asLong(x,z)))return true;
    return false;
  }
  BlockState retained(BlockPos p,BlockState fallback){var f=fills.get(p);return f==null?fallback:f.state();}
  double cap(BlockPos p){var f=fills.get(p);return f==null?1:f.cap();}
  @Override public BlockState getBlockState(BlockPos p){
    int section=(p.getY()-minY)>>4;var column=blocks.get(ChunkPos.asLong(p.getX()>>4,p.getZ()>>4));
    if(column==null||section<0||section>=column.size()||column.get(section)==null)return Blocks.AIR.defaultBlockState();
    return column.get(section).get(p.getX()&15,p.getY()&15,p.getZ()&15);
  }
  @Override public BlockEntity getBlockEntity(BlockPos p){return null;}
  @Override public FluidState getFluidState(BlockPos p){return getBlockState(p).getFluidState();}
  @Override public int getHeight(){return height;}
  @Override public int getMinBuildHeight(){return minY;}
}
