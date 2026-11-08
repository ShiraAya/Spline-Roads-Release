package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** Close the small gap between two inner rail ends at a shared ramp nose. */
public final class RoadNoses {
  private record Key(long x, long y, long z, double width, double height, Material material) {
    Key(V p, Part part) {
      this(
          Math.round(p.x() * 100000),
          Math.round(p.y() * 100000),
          Math.round(p.z() * 100000),
          part.width(),
          part.height(),
          part.material());
    }
  }

  private record End(int road, V at, V outward, Part part) {}

  public static Map<Integer, List<Part>> connectors(List<Mesh> meshes, List<List<Part>> parts) {
    Map<Key, List<End>> ends = new HashMap<>();
    for (int i = 0; i < meshes.size(); i++) {
      if (!meshes.get(i).settings().style().ramp()) continue;
      for (Part p : parts.get(i)) {
        if (p.pier()
            || (p.material() != Material.STEEL && p.material() != Material.CONCRETE)
            || p.width() > .5
            || p.b().sub(p.a()).horizontalLength() < .05) continue;
        V dir = p.b().sub(p.a()).horizontalUnit();
        ends.computeIfAbsent(new Key(p.a(), p), ignored -> new ArrayList<>())
            .add(new End(i, p.a(), dir.mul(-1), p));
        ends.computeIfAbsent(new Key(p.b(), p), ignored -> new ArrayList<>())
            .add(new End(i, p.b(), dir, p));
      }
    }
    var dangling =
        ends.values().stream()
            .filter(e -> e.size() == 1)
            .map(e -> e.get(0))
            .sorted(
                Comparator.comparingInt(End::road)
                    .thenComparingDouble(e -> e.at.x())
                    .thenComparingDouble(e -> e.at.z())
                    .thenComparingDouble(e -> e.at.y()))
            .toList();
    Set<Integer> used = new HashSet<>();
    Map<Integer, List<Part>> result = new HashMap<>();
    for (int i = 0; i < dangling.size(); i++) {
      if (used.contains(i)) continue;
      End a = dangling.get(i);
      int best = -1;
      double nearest = 6;
      for (int j = i + 1; j < dangling.size(); j++) {
        End b = dangling.get(j);
        if (used.contains(j)
            || a.road == b.road
            || a.part.width() != b.part.width()
            || a.part.height() != b.part.height()
            || a.part.material() != b.part.material()
            || Math.abs(a.at.y() - b.at.y()) > .12) continue;
        double distance = a.at.sub(b.at).horizontalLength();
        if (distance < .02 || distance >= nearest) continue;
        V direction = b.at.sub(a.at).horizontalUnit();
        boolean facing = a.outward.dot(direction) > .92 && b.outward.dot(direction) < -.92;
        boolean nose = distance <= 1.25 && a.outward.dot(b.outward) >= .6
            && shared(meshes.get(a.road), meshes.get(b.road));
        if (!facing && !nose) continue;
        // The cap belongs to the outer edge strip, never across an open driving lane.
        boolean lane = false;
        for (double t = .1; t < 1; t += .1) {
          V middle = a.at.add(b.at.sub(a.at).mul(t));
          for (int index = 0; index < meshes.size(); index++) {
            var q = RoadQueries.horizontal(meshes.get(index), middle);
            if (Math.abs(q.sample().center().y() - middle.y()) > 1.6) continue;
            if (q.horizontalDistance() < q.sample().halfWidth() - .5) lane = true;
          }
        }
        if (lane) continue;
        nearest = distance;
        best = j;
      }
      if (best < 0) continue;
      End b = dangling.get(best);
      used.add(i);
      used.add(best);
      result
          .computeIfAbsent(a.road, ignored -> new ArrayList<>())
          .add(new Part(a.at, b.at, a.part.width(), a.part.height(), false, a.part.material()));
    }
    return result;
  }

  private static boolean shared(Mesh a, Mesh b) {
    return a.first().center().distance(b.first().center()) < .01
        || a.last().center().distance(b.last().center()) < .01;
  }

  private RoadNoses() {}
}
