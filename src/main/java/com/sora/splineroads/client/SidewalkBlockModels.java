package com.sora.splineroads.client;

import com.sora.splineroads.core.RoadTerrainMesh.Section;
import com.sora.splineroads.world.RoadIndex;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.chunk.ChunkStatus;

/** Vanilla cube contacts are baked in native chunks even while roads use VBO. */
final class SidewalkBlockModels {
 private static final Map<UUID,Set<Section>> previous=new HashMap<>();
 private static long marked;
 static long markedSections(){return marked;}
 static void changed(RoadIndex index,UUID id){
  var sections=new HashSet<Section>();var road=index.roads.get(id);
  if(road!=null)for(var p:road.record.structures())if(p.material().name().startsWith("WALK_")){
   var top=p.top();double minX=top.stream().mapToDouble(v->v.x()).min().orElseThrow(),maxX=top.stream().mapToDouble(v->v.x()).max().orElseThrow();
   double minZ=top.stream().mapToDouble(v->v.z()).min().orElseThrow(),maxZ=top.stream().mapToDouble(v->v.z()).max().orElseThrow();
   double minY=top.stream().mapToDouble(v->v.y()).min().orElseThrow(),maxY=top.stream().mapToDouble(v->v.y()).max().orElseThrow();
   for(int x=(int)Math.floor(minX/16);x<=(int)Math.floor(maxX/16);x++)
    for(int z=(int)Math.floor(minZ/16);z<=(int)Math.floor(maxZ/16);z++)
     for(int y=(int)Math.floor(minY/16);y<=(int)Math.floor((maxY+2)/16);y++)sections.add(new Section(x,y,z));
  }
  var old=previous.remove(id);if(!sections.isEmpty())previous.put(id,Set.copyOf(sections));
  if(old!=null)sections.addAll(old);mark(sections);
 }
 static void clear(){var sections=new HashSet<Section>();previous.values().forEach(sections::addAll);previous.clear();mark(sections);}
 private static void mark(Collection<Section> sections){
  var mc=Minecraft.getInstance();if(mc.level==null)return;
  for(var s:sections)if(mc.level.getChunkSource().getChunk(s.x(),s.z(),ChunkStatus.FULL,false)!=null){mc.levelRenderer.setSectionDirty(s.x(),s.y(),s.z());marked++;}
 }
 private SidewalkBlockModels(){}
}
