package com.sora.splineroads.client;

import com.sora.splineroads.core.RoadMapTile;
import com.sora.splineroads.world.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/** Optional Xaero sampling boundary. Immutable source lists are published by
 * the client; map worker threads never access the mutable road/collision index. */
public final class XaeroRoadMap {
  private record Owner(RoadMapTile.Source source,Set<Long> chunks) {}
  private record Tile(List<RoadMapTile.Source> sources,RoadMapTile pixels) {}
  private static final Map<UUID,Owner> owners=new HashMap<>(); // client thread only
  private static final ConcurrentHashMap<Long,List<RoadMapTile.Source>> chunks=new ConcurrentHashMap<>();
  private static final LinkedHashMap<Long,Tile> cache=new LinkedHashMap<>(16,.75f,true);
  private static final Map<LevelChunk,java.lang.ref.WeakReference<List<RoadMapTile.Source>>> miniSeen=new WeakHashMap<>();
  private static final int MAX_TILES=512,MAX_PIXELS=262144;
  private static int retainedPixels;
  private static boolean enabled;
  private static volatile net.minecraft.world.level.Level publishedLevel;
  private static final boolean METRICS=Boolean.getBoolean("road.xaeroMetrics");
  private static final java.util.concurrent.atomic.LongAdder worldReads=new java.util.concurrent.atomic.LongAdder(),miniReads=new java.util.concurrent.atomic.LongAdder(),miniProjected=new java.util.concurrent.atomic.LongAdder(),miniAsphalt=new java.util.concurrent.atomic.LongAdder(),miniGreen=new java.util.concurrent.atomic.LongAdder();
  public static String metrics(){synchronized(cache){return "world="+worldReads.sum()+" mini="+miniReads.sum()+" miniProjected="+miniProjected.sum()+" miniAsphalt="+miniAsphalt.sum()+" miniGreen="+miniGreen.sum()+" tiles="+cache.size()+" pixels="+retainedPixels;}}
  public static BlockState readWorld(LevelChunk chunk,BlockPos local){if(METRICS)worldReads.increment();return read(chunk,local);}
  public static BlockState readMini(LevelChunk chunk,BlockPos local){
    // A clean-flag query can be followed by a deferred tile update. Acknowledge
    // a generation only when the writer actually starts the tile's first column.
    // Publication halfway through a tile must trigger another complete scan.
    if((local.getX()&15)==0&&(local.getZ()&15)==0&&chunk.getLevel()==publishedLevel){var sources=chunks.get(chunk.getPos().toLong());synchronized(miniSeen){
      if(sources==null)miniSeen.remove(chunk);else{var previous=miniSeen.get(chunk);if(previous==null||previous.get()!=sources)miniSeen.put(chunk,new java.lang.ref.WeakReference<>(sources));}
    }}
    var value=read(chunk,local);if(METRICS){miniReads.increment();if(value!=chunk.getBlockState(local)&&!value.isAir())miniProjected.increment();if(value.is(Blocks.GRAY_CONCRETE))miniAsphalt.increment();if(value.is(Blocks.OAK_LEAVES))miniGreen.increment();}return value;
  }
  /** SR geometry arrives separately from vanilla blocks. Xaero's clean-chunk
   * flag alone would permanently reuse the terrain sampled before that packet.
   * Observe each published source generation on the map writer's own thread. */
  public static Object miniChunkClean(java.lang.reflect.Field field,Object object)throws IllegalAccessException{
    Object clean=field.get(object);
    if(!(object instanceof LevelChunk chunk)||chunk.getLevel()!=publishedLevel)return clean;
    var sources=chunks.get(chunk.getPos().toLong());
    synchronized(miniSeen){
      var previous=miniSeen.get(chunk);
      if(sources==null)return previous==null?clean:false;
      if(previous==null||previous.get()!=sources)return false;
    }
    return clean;
  }
  static void setup(){enabled=net.minecraftforge.fml.ModList.get().isLoaded("xaeroworldmap")||net.minecraftforge.fml.ModList.get().isLoaded("xaerominimap");}
  static void changed(UUID id,RoadIndex.Built road){
    if(!enabled)return;
    if(id==null){publishedLevel=null;owners.clear();chunks.clear();synchronized(cache){cache.clear();retainedPixels=0;}synchronized(miniSeen){miniSeen.clear();}return;}
    publishedLevel=net.minecraft.client.Minecraft.getInstance().level;
    Owner old=owners.remove(id);var affected=new HashSet<Long>();if(old!=null)affected.addAll(old.chunks());
    RoadMapTile.Source source=null;
    if(road!=null){source=new RoadMapTile.Source(id,road.mesh,road.record.structures());var keys=Set.copyOf(road.chunks);owners.put(id,new Owner(source,keys));affected.addAll(keys);}
    for(long key:affected){var list=new ArrayList<>(chunks.getOrDefault(key,List.of()));list.removeIf(s->s.id().equals(id));if(source!=null&&road.chunks.contains(key))list.add(source);
      if(list.isEmpty())chunks.remove(key);else chunks.put(key,List.copyOf(list));
      synchronized(cache){var removed=cache.remove(key);if(removed!=null)retainedPixels-=removed.pixels().size();}
    }
  }
  private static RoadMapTile tile(long key){
    var sources=chunks.get(key);if(sources==null)return null;
    synchronized(cache){var old=cache.get(key);if(old!=null&&old.sources()==sources)return old.pixels();}
    var pixels=new RoadMapTile((int)key,(int)(key>>32),sources);
    synchronized(cache){if(chunks.get(key)==sources){
      var old=cache.put(key,new Tile(sources,pixels));if(old!=null)retainedPixels-=old.pixels().size();retainedPixels+=pixels.size();
      while(cache.size()>MAX_TILES||retainedPixels>MAX_PIXELS){var it=cache.values().iterator();retainedPixels-=it.next().pixels().size();it.remove();}
      return pixels;
    }}return null; // An edit/reset overtook the map worker's snapshot.
  }
  /** Both Xaero writers pass chunk-local X/Z, even when the chunk is negative. */
  public static BlockState read(LevelChunk chunk,BlockPos local){
    BlockState original=chunk.getBlockState(local);
    if(chunk.getLevel()!=publishedLevel)return original;
    if(!(original.getBlock() instanceof RoadBlocks.Road)&&!(original.getBlock() instanceof RoadBlocks.Node))return original;
    var position=chunk.getPos();var tile=tile(position.toLong());
    if(tile==null||chunk.getLevel()!=publishedLevel)return original;
    int x=(position.x<<4)+(local.getX()&15),z=(position.z<<4)+(local.getZ()&15);
    var material=tile.at(x,local.getY(),z);if(material!=null)return state(material);
    // Retained land under/beside a road is still land; empty proxy volume is air.
    var global=new BlockPos(x,local.getY(),z);
    if(chunk.getBlockEntities().get(global) instanceof RoadFillEntity fill)return fill.fill();
    return original.hasProperty(RoadBlocks.Road.FILL)?original.getValue(RoadBlocks.Road.FILL).state():Blocks.AIR.defaultBlockState();
  }
  private static BlockState state(RoadMapTile.Material m){return switch(m){
    // Cartographic asphalt uses a darker neutral swatch so stone pavements remain
    // distinguishable at map resolution. This proxy never changes world blocks.
    case ASPHALT -> Blocks.GRAY_CONCRETE.defaultBlockState();case GREEN,OAK -> Blocks.OAK_LEAVES.defaultBlockState();
    case BIRCH -> Blocks.BIRCH_LEAVES.defaultBlockState();case SPRUCE -> Blocks.SPRUCE_LEAVES.defaultBlockState();case CHERRY -> Blocks.CHERRY_LEAVES.defaultBlockState();
    case STONE_BRICKS -> Blocks.STONE_BRICKS.defaultBlockState();case BRICKS -> Blocks.BRICKS.defaultBlockState();case ANDESITE -> Blocks.ANDESITE.defaultBlockState();
    case DIORITE -> Blocks.DIORITE.defaultBlockState();case GRANITE -> Blocks.GRANITE.defaultBlockState();case SMOOTH_STONE -> Blocks.SMOOTH_STONE.defaultBlockState();
    case YELLOW -> Blocks.YELLOW_CONCRETE.defaultBlockState();case CONCRETE -> Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();case SOIL -> Blocks.DIRT.defaultBlockState();
  };}
  private XaeroRoadMap(){}
}
