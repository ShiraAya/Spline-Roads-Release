package com.sora.splineroads.client;

import com.sora.splineroads.world.*;
import java.util.*;
import net.minecraft.world.level.ChunkPos;

/** Point coordinates have their own index: an authored attached point may be
 * outside the road's pavement bounds. Only changed records rebuild membership. */
final class ClientToolIndex {
  private static final Set<UUID> changed=new LinkedHashSet<>();
  private static final Map<Long,Set<UUID>> lanes=new HashMap<>(),attachments=new HashMap<>();
  private static final Map<UUID,Set<Long>> laneChunks=new HashMap<>(),attachmentChunks=new HashMap<>();
  private static long revision;
  private static long cachedRevision=-1,cachedChunk;
  private static boolean cachedLanes;
  private static List<RoadIndex.Built> cached=List.of();
  static void changed(UUID id){changed.add(id);}
  static void clear(){changed.clear();lanes.clear();attachments.clear();laneChunks.clear();attachmentChunks.clear();cached=List.of();cachedRevision=-1;revision++;}
  private static long chunk(double x,double z){return ChunkPos.asLong((int)Math.floor(x)>>4,(int)Math.floor(z)>>4);}
  private static void replace(UUID road,Set<Long> keys,Map<UUID,Set<Long>> byRoad,Map<Long,Set<UUID>> byChunk){
    for(long old:byRoad.getOrDefault(road,Set.of())){var set=byChunk.get(old);if(set!=null){set.remove(road);if(set.isEmpty())byChunk.remove(old);}}
    if(keys.isEmpty())byRoad.remove(road);else {byRoad.put(road,keys);for(long key:keys)byChunk.computeIfAbsent(key,k->new LinkedHashSet<>()).add(road);}
  }
  private static void update(){
    if(changed.isEmpty())return;
    for(UUID id:changed){var road=ClientRoads.INDEX.roads.get(id);Set<Long> lane=new HashSet<>(),attached=new HashSet<>();
      if(road!=null&&!road.record.railway()){for(var p:LaneTopology.visiblePoints(road.record))lane.add(chunk(p.position().x(),p.position().z()));for(var p:road.record.settings().options().attachments().points())attached.add(chunk(p.position().x(),p.position().z()));}
      replace(id,lane,laneChunks,lanes);replace(id,attached,attachmentChunks,attachments);
    }changed.clear();revision++;
  }
  static List<RoadIndex.Built> near(double x,double z,boolean lanePoints){
    update();long center=chunk(x,z);
    if(cachedRevision==revision&&cachedChunk==center&&cachedLanes==lanePoints)return cached;
    int cx=(int)center,cz=(int)(center>>32);var index=lanePoints?lanes:attachments;Set<UUID> ids=new LinkedHashSet<>();
    for(int dx=-6;dx<=6;dx++)for(int dz=-6;dz<=6;dz++)ids.addAll(index.getOrDefault(ChunkPos.asLong(cx+dx,cz+dz),Set.of()));
    cached=ids.stream().map(ClientRoads.INDEX.roads::get).filter(Objects::nonNull).toList();cachedRevision=revision;cachedChunk=center;cachedLanes=lanePoints;return cached;
  }
  private ClientToolIndex(){}
}
