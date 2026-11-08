package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadTerrainMesh.Chunk;
import java.util.*;

/** Worker-owned streaming index. Joining face lists, counting retained source
 * storage and copying the complete immutable lookup all happen on the worker.
 * A cancelled/retried window replaces its contribution instead of appending it. */
public final class RoadTerrainSourceWindows<K> {
  public record Publication(RoadTerrainMesh.Source source,Set<Chunk> changed,long bytes){}
  private final Map<K,RoadTerrainMesh.Source> windows=new HashMap<>();
  private final Map<K,Long> costs=new HashMap<>();
  private final Map<Chunk,Map<K,RoadSurface.Geometry>> parts=new LinkedHashMap<>();
  private final Map<Chunk,RoadSurface.Geometry> combined=new LinkedHashMap<>();
  private long bytes;

  public Publication replace(K window,RoadTerrainMesh.Source source){
    var changed=new LinkedHashSet<>(source.chunks().keySet());var old=windows.put(window,source);
    if(old!=null)for(var chunk:old.chunks().keySet()){
      changed.add(chunk);var contributions=parts.get(chunk);
      if(contributions!=null){contributions.remove(window);if(contributions.isEmpty())parts.remove(chunk);}
    }
    source.chunks().forEach((chunk,geometry)->parts.computeIfAbsent(chunk,key->new LinkedHashMap<>()).put(window,geometry));
    for(var chunk:changed){
      var contributions=parts.get(chunk);if(contributions==null||contributions.isEmpty()){combined.remove(chunk);continue;}
      if(contributions.size()==1){combined.put(chunk,contributions.values().iterator().next());continue;}
      var pavement=new ArrayList<RoadSurface.Face>();var paint=new ArrayList<RoadSurface.Face>();
      for(var geometry:contributions.values()){pavement.addAll(geometry.pavement());paint.addAll(geometry.markings());}
      combined.put(chunk,new RoadSurface.Geometry(List.copyOf(pavement),List.copyOf(paint)));
    }
    long cost=bytes(source);bytes+=cost-costs.getOrDefault(window,0L);costs.put(window,cost);
    return new Publication(new RoadTerrainMesh.Source(Collections.unmodifiableMap(new LinkedHashMap<>(combined))),Set.copyOf(changed),bytes);
  }
  public static long bytes(RoadTerrainMesh.Source source){
    long result=0;var faces=Collections.newSetFromMap(new IdentityHashMap<RoadSurface.Face,Boolean>());
    for(var geometry:source.chunks().values())for(var list:List.of(geometry.pavement(),geometry.markings())){result+=64L+list.size()*16L;faces.addAll(list);}
    for(var face:faces)result+=96L+face.points().size()*64L;return result;
  }
}
