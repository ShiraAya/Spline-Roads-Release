package com.sora.splineroads.world;

import com.sora.splineroads.net.RoadNetwork;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.Heightmap;

/** Bounded native chunk requests. The map retains sampled tiles, not 50,000 ticking chunks. */
final class ManualPlannerTerrain implements AutoCloseable {
  private static final TicketType<UUID> TICKET=TicketType.create("sr_planner_map",UUID::compareTo);
  private final ServerPlayer player;private final ServerLevel level;private final UUID session;
  private final ArrayDeque<ChunkPos> waiting=new ArrayDeque<>();private final Set<Long> sampled=new HashSet<>();
  private final Map<ChunkPos,Long> pending=new LinkedHashMap<>();
  private int radius=256,total;private double centerX,centerZ;private boolean closed;
  ManualPlannerTerrain(ServerPlayer p,UUID id){player=p;level=p.serverLevel();session=id;configure(256);}
  final Map<Long,CompoundTag> nodes=new HashMap<>();
  int radius(){return radius;}int completed(){return sampled.size();}int total(){return total;}
  void configure(int value){
    if(value<16||value>2048)throw new IllegalArgumentException("地表加载半径应为16–2048格");
    radius=value;centerX=player.getX();centerZ=player.getZ();waiting.clear();var next=new ArrayList<ChunkPos>();
    sampled.removeIf(key->{var c=new ChunkPos(key);return Math.hypot(c.x*16+8-centerX,c.z*16+8-centerZ)>2080;});
    nodes.keySet().removeIf(key->{var p=BlockPos.of(key);return Math.hypot(p.getX()-centerX,p.getZ()-centerZ)>2080;});
    for(int x=((int)Math.floor(centerX-radius))>>4;x<=((int)Math.floor(centerX+radius))>>4;x++)
      for(int z=((int)Math.floor(centerZ-radius))>>4;z<=((int)Math.floor(centerZ+radius))>>4;z++){
        var pos=new ChunkPos(x,z);double dx=Math.max(0,Math.max(x*16-centerX,centerX-(x*16+16))),dz=Math.max(0,Math.max(z*16-centerZ,centerZ-(z*16+16)));
        if(dx*dx+dz*dz<=radius*(double)radius&&level.getWorldBorder().isWithinBounds(pos)&&!sampled.contains(pos.toLong()))next.add(pos);
      }
    next.sort(Comparator.comparingDouble(p->Math.pow(p.x*16+8-centerX,2)+Math.pow(p.z*16+8-centerZ,2)));
    waiting.addAll(next);total=sampled.size()+next.size();
  }
  void changed(Set<Long> chunks){
    for(var e:nodes.entrySet()){var pos=BlockPos.of(e.getKey());if(chunks.contains(new ChunkPos(pos).toLong())&&level.hasChunkAt(pos)){var be=level.getBlockEntity(pos);if(be instanceof NodeEntity n)e.setValue(nodeTag(n));else e.setValue(new CompoundTag());}}
    nodes.values().removeIf(CompoundTag::isEmpty);
    // Keep the session cache and scan progress. Only changed columns need a new sample.
    for(long key:chunks){var pos=new ChunkPos(key);if(Math.hypot(pos.x*16+8-centerX,pos.z*16+8-centerZ)>2080)continue;sampled.remove(key);waiting.remove(pos);if(!pending.containsKey(pos))waiting.addFirst(pos);}
    total=sampled.size()+waiting.size()+pending.size();
  }
  static CompoundTag nodeTag(NodeEntity n){var item=new CompoundTag();item.putLong("Pos",n.getBlockPos().asLong());item.put("Node",RoadRecord.writeNode(n.node()));item.putBoolean("JunctionMarker",n.junctionSource!=null&&AutoJunctions.center(RoadData.get((ServerLevel)n.getLevel()),n.getBlockPos())==null);return item;}
  void tick(){
    if(closed)return;
    if(Math.hypot(player.getX()-centerX,player.getZ()-centerZ)>=16)configure(radius);
    long end=System.nanoTime()+3_000_000;
    for(var iterator=pending.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();var pos=entry.getKey();var loaded=level.getChunkSource().getChunkNow(pos.x,pos.z);
      if(loaded==null&&level.getGameTime()-entry.getValue()<2400)continue;
      try{if(loaded!=null){send(loaded);sampled.add(pos.toLong());}else{var t=new CompoundTag();t.putString("Kind","manualTerrain");t.putUUID("PlannerSession",session);t.putString("Error","地表分块加载超时，可在设置中重新加载");RoadNetwork.open(player,t);}}
      catch(RuntimeException failure){com.mojang.logging.LogUtils.getLogger().warn("Planner terrain tile failed at {}",pos,failure);}
      finally{level.getChunkSource().removeRegionTicket(TICKET,pos,0,session);iterator.remove();}
      if(System.nanoTime()>=end)break;
    }
    int submitted=0;
    while(pending.size()<32&&!waiting.isEmpty()&&submitted++<64&&System.nanoTime()<end){
      var pos=waiting.removeFirst();if(sampled.contains(pos.toLong())||pending.containsKey(pos))continue;
      // Already available chunks need neither a ticket nor another scheduler tick.
      var loaded=level.getChunkSource().getChunkNow(pos.x,pos.z);
      if(loaded!=null){try{send(loaded);sampled.add(pos.toLong());}catch(RuntimeException failure){com.mojang.logging.LogUtils.getLogger().warn("Planner terrain tile failed at {}",pos,failure);}continue;}
      // FULL ticket advancement is owned by Minecraft's chunk scheduler. getChunkFuture
      // would managedBlock on the server thread, so completion is polled with getChunkNow.
      level.getChunkSource().addRegionTicket(TICKET,pos,0,session);pending.put(pos,level.getGameTime());
    }
  }
  private void send(LevelChunk chunk){
    int[] colors=new int[256],heights=new int[256];var origin=chunk.getPos();
    for(int z=0;z<16;z++)for(int x=0;x<16;x++){
      int y=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,x,z);var pos=new BlockPos(origin.x*16+x,y,origin.z*16+z);
      int color=chunk.getBlockState(pos).getMapColor(level,pos).col;if(color==0)color=0x777777;
      int north=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,x,Math.max(0,z-1));int shade=y>north?255:y<north?175:220;
      colors[z*16+x]=0xff000000|(((color&255)*shade/255)<<16)|((((color>>8)&255)*shade/255)<<8)|(((color>>16)&255)*shade/255);heights[z*16+x]=y+1;
    }
    var t=new CompoundTag();t.putString("Kind","manualTerrain");t.putUUID("PlannerSession",session);t.putInt("ChunkX",origin.x);t.putInt("ChunkZ",origin.z);t.putIntArray("Colors",colors);t.putIntArray("Heights",heights);t.putInt("Loaded",sampled.size()+1);t.putInt("Total",total);
    nodes.entrySet().removeIf(e->new ChunkPos(BlockPos.of(e.getKey())).equals(origin));var found=new ListTag();for(var be:chunk.getBlockEntities().values())if(be instanceof NodeEntity n){var item=nodeTag(n);nodes.put(n.getBlockPos().asLong(),item);found.add(item);}t.put("Nodes",found);RoadNetwork.open(player,t);
  }
  private void releasePending(){for(var pos:pending.keySet())level.getChunkSource().removeRegionTicket(TICKET,pos,0,session);pending.clear();}
  @Override public void close(){closed=true;releasePending();waiting.clear();sampled.clear();nodes.clear();}
}
