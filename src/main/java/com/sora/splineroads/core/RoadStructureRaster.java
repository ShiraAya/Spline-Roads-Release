package com.sora.splineroads.core;

import java.util.*;

/** One transaction-local raster pass supplies both collision bodies and tagged metadata.
 * Each part is rasterized exactly once; overlapping parts retain their original order.
 * No global cache, world access, GPU work, or geometric approximation is introduced. */
public final class RoadStructureRaster {
  public record Result(Map<RoadRaster.Cell,List<RoadRaster.Box>> cells,
      Set<RoadRaster.Cell> lights, Set<RoadRaster.Cell> shells,
      Map<RoadRaster.Cell,Double> walkTops, int rasterizedParts) {}

  public static Result build(List<RoadStructures.Part> parts) {
    boolean tagged=parts.stream().anyMatch(p->p.luminous()||p.material()==RoadStructures.Material.TUNNEL||RoadsideBypass.walkway(p));
    if(!tagged)return new Result(RoadRaster.structures(parts,null),Set.of(),Set.of(),Map.of(),parts.size());
    Map<RoadRaster.Cell,List<RoadRaster.Box>> cells=new HashMap<>();
    Set<RoadRaster.Cell> lights=new HashSet<>(),shells=new HashSet<>();
    Map<RoadRaster.Cell,Double> walkTops=new HashMap<>();
    for(var part:parts) {
      boolean luminous=part.luminous(),shell=part.material()==RoadStructures.Material.TUNNEL;
      boolean walk=RoadsideBypass.walkway(part);
      var raster=RoadRaster.structures(List.of(part),null);
      for(var e:raster.entrySet()) {
        var cell=e.getKey();var boxes=e.getValue();
        cells.computeIfAbsent(cell,key->new ArrayList<>()).addAll(boxes);
        if(luminous)lights.add(cell);
        if(shell)shells.add(cell);
        if(walk)walkTops.merge(cell,boxes.stream().mapToDouble(b->cell.y()+b.y1()).max().orElse(cell.y()),Math::max);
      }
    }
    return new Result(cells,lights,shells,walkTops,parts.size());
  }
  private RoadStructureRaster() {}
}
