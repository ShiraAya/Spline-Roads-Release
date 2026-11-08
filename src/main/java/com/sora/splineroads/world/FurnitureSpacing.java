package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Shared by client furniture previews and server rebuilds; no per-segment spacing reset. */
public final class FurnitureSpacing {
  public static RoadFurniture.Phase resolve(RoadRecord road, Mesh mesh,
      Collection<RoadIndex.Built> references) {
    if (road.furniturePhase() != null) return road.furniturePhase();
    var old = references.stream().filter(r -> r.record.id().equals(road.id())).findFirst();
    if (old.isPresent() && old.get().record.furniturePhase() != null)
      return old.get().record.furniturePhase();
    double start = RoadQueries.horizontal(mesh, road.start().position()).sample().distance();
    for (var other : references.stream().sorted(Comparator.comparing(r -> r.record.id())).toList()) {
      if (other.record.id().equals(road.id()) || other.record.railway()!=road.railway() || other.mesh.closed()
          || other.record.settings().style().ramp() != road.settings().style().ramp()) continue;
      for (boolean first : new boolean[] {true, false}) {
        var node = first ? road.a() : road.b();
        boolean ofirst = other.record.a().equals(node);
        if (!ofirst && !other.record.b().equals(node)) continue;
        Sample at = RoadQueries.horizontal(mesh,
            (first ? road.start() : road.end()).position()).sample();
        Sample ot = RoadQueries.horizontal(other.mesh,
            (ofirst ? other.record.start() : other.record.end()).position()).sample();
        if (at.center().distance(ot.center()) > 1.05 || Math.abs(at.left().dot(ot.left())) < .94)
          continue;
        var phase = other.record.furniturePhase() == null
            ? RoadFurniture.Phase.DEFAULT : other.record.furniturePhase();
        double os = RoadQueries.horizontal(other.mesh, other.record.start().position()).sample().distance();
        int direction = phase.direction() * (at.left().dot(ot.left()) > 0 ? 1 : -1);
        return new RoadFurniture.Phase(phase.origin() + phase.direction() * (ot.distance() - os)
            - direction * (at.distance() - start), direction);
      }
    }
    return RoadFurniture.Phase.DEFAULT;
  }

  public static RoadFurniture.Phase onMesh(RoadRecord road, Mesh mesh) {
    var phase = road.furniturePhase() == null ? RoadFurniture.Phase.DEFAULT : road.furniturePhase();
    double start = RoadQueries.horizontal(mesh, road.start().position()).sample().distance();
    return phase.shift(-start);
  }
  private FurnitureSpacing() {}
}
