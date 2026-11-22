package com.sora.splineroads.world;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.RoadPlanningBudget;
import java.util.*;
import java.util.concurrent.*;
import it.unimi.dsi.fastutil.longs.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.material.FluidState;

/** A build's private block palettes, entities and write overlay. Only capture()
 * and validate() touch the live world, on its server thread. The worker can wait
 * for a palette; the server never waits for the worker. */
final class RoadBuildWorld implements BlockGetter,AutoCloseable {
  private static final ThreadLocal<RoadBuildWorld> CURRENT=new ThreadLocal<>();
  record Column(List<PalettedContainer<BlockState>> sections,int[] heights,Map<Long,CompoundTag> entities,Map<Long,RoadFillEntity.Fill> fills){}
  record Cell(BlockState before,CompoundTag oldEntity,BlockState after,CompoundTag entity,RoadFillEntity.Fill fill,boolean knownNeighbors){}
  record SignSplit(UUID source,Map<Integer,UUID> owners){}
  final List<SignSplit> signSplits=new ArrayList<>();
  record ReadSection(long section,short[] positions,BlockState[] states){}
  List<ReadSection> validation=List.of();
  final ServerLevel level;
  final UUID owner;
  final int minY,height;
  final WorldBorder border=new WorldBorder();
  final Map<Long,CompletableFuture<Column>> columns=new ConcurrentHashMap<>();
  final Queue<Long> requests=new ConcurrentLinkedQueue<>();
  final Long2ObjectOpenHashMap<BlockState> reads=new Long2ObjectOpenHashMap<>(),writes=new Long2ObjectOpenHashMap<>();
  final Long2ObjectOpenHashMap<BlockState> rollbackPlants=new Long2ObjectOpenHashMap<>();
  final Long2ObjectOpenHashMap<CompoundTag> entityReads=new Long2ObjectOpenHashMap<>();
  final Long2ObjectOpenHashMap<RoadFillEntity.Fill> fillReads=new Long2ObjectOpenHashMap<>();
  final Long2IntOpenHashMap heightReads=new Long2IntOpenHashMap();
  final Long2ObjectOpenHashMap<BlockEntity> entities=new Long2ObjectOpenHashMap<>();
  final Set<Long> entityLoaded=new LongOpenHashSet();
  RoadData data;
  volatile boolean cancelled;
  RoadBuildWorld(ServerLevel level,UUID owner){
    this.level=level;this.owner=owner;minY=level.getMinBuildHeight();height=level.getHeight();
    border.applySettings(level.getWorldBorder().createSettings());
  }
  static RoadBuildWorld current(){return CURRENT.get();}
  RoadBuildWorld enter(RoadData copy){data=copy;CURRENT.set(this);return this;}
  @Override public void close(){CURRENT.remove();}
  void request(Collection<Long> keys){
    for(long key:keys){if(columns.size()>=com.sora.splineroads.core.RoadLimits.MAX_MULTI_INTERCHANGE_WORK_CHUNKS&&!columns.containsKey(key))throw new IllegalArgumentException("一次道路操作涉及区块过多，请分段建造");
      columns.computeIfAbsent(key,k->{requests.add(k);return new CompletableFuture<>();});}
    for(long key:keys)column(key);
  }
  private Column column(long key){
    RoadPlanningBudget.check();
    if(columns.size()>=com.sora.splineroads.core.RoadLimits.MAX_MULTI_INTERCHANGE_WORK_CHUNKS&&!columns.containsKey(key))throw new IllegalArgumentException("一次道路操作涉及区块过多，请分段建造");
    var future=columns.computeIfAbsent(key,k->{requests.add(k);return new CompletableFuture<>();});
    try{return future.get();}
    catch(InterruptedException e){Thread.currentThread().interrupt();throw new RoadPlanningBudget.Aborted("道路计算已取消，未写入世界");}
    catch(ExecutionException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new IllegalStateException(e.getCause());}
  }
  static Column capture(LevelChunk chunk){
    var sections=new ArrayList<PalettedContainer<BlockState>>();
    for(var section:chunk.getSections())sections.add(section.hasOnlyAir()?null:section.getStates().copy());
    int[] heights=new int[256];for(int z=0;z<16;z++)for(int x=0;x<16;x++)heights[z*16+x]=chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,x,z)+1;
    var entities=new HashMap<Long,CompoundTag>();var fills=new HashMap<Long,RoadFillEntity.Fill>();
    for(var entry:chunk.getBlockEntities().entrySet()){
      if(entry.getValue() instanceof RoadFillEntity fill)fills.put(entry.getKey().asLong(),fill.snapshot());
      else entities.put(entry.getKey().asLong(),entry.getValue().saveWithFullMetadata());
    }
    return new Column(Collections.unmodifiableList(sections),heights,Map.copyOf(entities),Map.copyOf(fills));
  }
  private BlockState original(BlockPos p){
    if(p.getY()<minY||p.getY()>=minY+height)return Blocks.AIR.defaultBlockState();
    var column=column(new ChunkPos(p).toLong());var section=column.sections().get((p.getY()-minY)>>4);
    return section==null?Blocks.AIR.defaultBlockState():section.get(p.getX()&15,p.getY()&15,p.getZ()&15);
  }
  @Override public BlockState getBlockState(BlockPos p){
    long key=p.asLong();var before=reads.computeIfAbsent(key,k->original(p));return writes.getOrDefault(key,before);
  }
  @Override public BlockEntity getBlockEntity(BlockPos p){
    var state=getBlockState(p);if(!state.hasBlockEntity())return null;
    long key=p.asLong();if(entityLoaded.add(key)){
      var column=column(new ChunkPos(p).toLong());var fill=column.fills().get(key);
      if(fill!=null){fillReads.put(key,fill);var copy=new RoadFillEntity(p,state);copy.fill(fill);entities.put(key,copy);}
      else {var tag=column.entities().get(key);entityReads.put(key,tag);if(tag!=null&&state.is(SplineRoads.NODE.get()))entities.put(key,BlockEntity.loadStatic(p,state,tag.copy()));}
    }return entities.get(key);
  }
  boolean write(BlockPos p,BlockState state){
    var old=getBlockState(p);getBlockEntity(p);writes.put(p.asLong(),state);
    if(old.getBlock()!=state.getBlock()){
      entities.remove(p.asLong());
      if(state.getBlock() instanceof EntityBlock block){var entity=block.newBlockEntity(p,state);if(entity!=null)entities.put(p.asLong(),entity);}
    }else if(entities.get(p.asLong())!=null)entities.get(p.asLong()).setBlockState(state);
    return !old.equals(state);
  }
  int height(int x,int z){int value=column(ChunkPos.asLong(x>>4,z>>4)).heights()[(z&15)*16+(x&15)];heightReads.put(BlockPos.asLong(x,0,z),value);return value;}
  Map<Long,Cell> changes(){
    var keys=new LongOpenHashSet(writes.keySet());keys.addAll(entities.keySet());var out=new Long2ObjectOpenHashMap<Cell>();
    for(long key:keys){var p=BlockPos.of(key);var before=reads.get(key);var after=getBlockState(p);var old=entityReads.get(key);var entity=entities.get(key);
      var oldFill=fillReads.get(key);var fill=entity instanceof RoadFillEntity f?f.snapshot():null;var next=entity==null||fill!=null?null:entity.saveWithFullMetadata();
      if(!before.equals(after)||!Objects.equals(old,next)||!Objects.equals(oldFill,fill)){
        if(oldFill!=null){var saved=new RoadFillEntity(p,before);saved.fill(oldFill);old=saved.saveWithFullMetadata();}
        boolean known=RoadBlocks.isCollider(before)||RoadBlocks.isCollider(after);
        for(var direction:net.minecraft.core.Direction.values())if(known){var neighbor=getBlockState(p.relative(direction));known=neighbor.isAir()||neighbor.getBlock().getClass()==Block.class||RoadBlocks.isCollider(neighbor)&&neighbor.getFluidState().isEmpty();}
        out.put(key,new Cell(before,old,after,next,fill,known));
      }}
    for(long key:out.keySet()){var plant=BlockPos.of(key).above();for(int half=0;half<2;half++,plant=plant.above()){if(plant.getY()>=minY+height)break;var state=original(plant);if(!(state.getBlock() instanceof BushBlock)&&!(state.getBlock() instanceof SnowLayerBlock))break;reads.putIfAbsent(plant.asLong(),state);if(!out.containsKey(plant.asLong()))rollbackPlants.put(plant.asLong(),state);}}
    var groups=new Long2ObjectOpenHashMap<it.unimi.dsi.fastutil.longs.LongArrayList>();
    for(long key:reads.keySet()){int x=BlockPos.getX(key),y=BlockPos.getY(key),z=BlockPos.getZ(key);if(y<minY||y>=minY+height)continue;long section=net.minecraft.core.SectionPos.asLong(x>>4,y>>4,z>>4);groups.computeIfAbsent(section,k->new it.unimi.dsi.fastutil.longs.LongArrayList()).add(key);}
    var prepared=new ArrayList<ReadSection>(groups.size());
    for(var entry:groups.long2ObjectEntrySet()){var keysInSection=entry.getValue();var positions=new short[keysInSection.size()];var states=new BlockState[positions.length];
      for(int i=0;i<positions.length;i++){long key=keysInSection.getLong(i);positions[i]=(short)((BlockPos.getX(key)&15)|((BlockPos.getZ(key)&15)<<4)|((BlockPos.getY(key)&15)<<8));states[i]=reads.get(key);}
      prepared.add(new ReadSection(entry.getLongKey(),positions,states));}
    validation=List.copyOf(prepared);return out;
  }
  void validate(net.minecraft.server.level.ServerPlayer player){
    if(!level.getServer().isSameThread())throw new IllegalStateException("World validation must run on the server thread");
    for(var entry:heightReads.long2IntEntrySet()){var p=BlockPos.of(entry.getLongKey());if(level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,p.getX(),p.getZ())!=entry.getIntValue())throw new IllegalArgumentException("计算期间地形高度已改变，请重新保存道路");}
    for(var section:validation){long key=section.section();var chunk=level.getChunk(net.minecraft.core.SectionPos.x(key),net.minecraft.core.SectionPos.z(key));var live=chunk.getSection(net.minecraft.core.SectionPos.y(key)-(minY>>4));
      for(int i=0;i<section.positions().length;i++){int p=section.positions()[i];if(!RoadPlanInputs.equivalentTerrain(section.states()[i],live.getBlockState(p&15,p>>8&15,p>>4&15)))throw new IllegalArgumentException("计算期间地形已改变，请重新保存道路");}}
    for(var entry:entityReads.long2ObjectEntrySet()){
      var entity=level.getBlockEntity(BlockPos.of(entry.getLongKey()));var now=entity==null?null:entity.saveWithFullMetadata();
      if(!Objects.equals(entry.getValue(),now))throw new IllegalArgumentException("计算期间端点或填土已改变，请重新保存道路");
      if(player!=null&&entity instanceof NodeEntity node){RoadData.requireOwner(player,node.owner);if(!level.mayInteract(player,node.getBlockPos()))throw new IllegalArgumentException("此处没有建设权限");}
    }
    for(var entry:fillReads.long2ObjectEntrySet())if(!(level.getBlockEntity(BlockPos.of(entry.getLongKey())) instanceof RoadFillEntity fill)||!fill.snapshot().equals(entry.getValue()))throw new IllegalArgumentException("计算期间填土已改变，请重新保存道路");
  }
  @Override public FluidState getFluidState(BlockPos p){return getBlockState(p).getFluidState();}
  @Override public int getHeight(){return height;}
  @Override public int getMinBuildHeight(){return minY;}
  static BlockGetter blocks(ServerLevel level){var w=current();return w==null?level:w;}
  static BlockState state(ServerLevel level,BlockPos p){return blocks(level).getBlockState(p);}
  static BlockEntity entity(ServerLevel level,BlockPos p){return blocks(level).getBlockEntity(p);}
  static boolean set(ServerLevel level,BlockPos p,BlockState state,int flags){var w=current();return w==null?level.setBlock(p,state,flags):w.write(p,state);}
  static boolean loaded(ServerLevel level,BlockPos p){var w=current();if(w==null)return level.hasChunkAt(p);w.column(new ChunkPos(p).toLong());return true;}
  static WorldBorder border(ServerLevel level){var w=current();return w==null?level.getWorldBorder():w.border;}
  static int min(ServerLevel level){var w=current();return w==null?level.getMinBuildHeight():w.minY;}
  static int max(ServerLevel level){var w=current();return w==null?level.getMaxBuildHeight():w.minY+w.height;}
  static boolean outside(ServerLevel level,BlockPos p){return p.getY()<min(level)||p.getY()>=max(level);}
  static UUID owner(net.minecraft.server.level.ServerPlayer player){var w=current();return player!=null?player.getUUID():w==null?new UUID(0,0):w.owner;}
}
