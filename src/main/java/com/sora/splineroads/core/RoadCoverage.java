package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Chunk coverage follows each road strip, not the entire bounding rectangle of a long curve. */
public final class RoadCoverage {
  public static Set<Long> chunks(Mesh mesh, double margin) {
    if (!Double.isFinite(margin) || margin < 0 || margin > 16)
      throw new IllegalArgumentException("无效的道路区块边距");
    Set<Long> result = new HashSet<>();
    Sample previous = mesh.first();
    for (Sample sample : mesh.samples()) {
      V a = previous.at(-previous.halfWidth(), 0),
          b = previous.at(previous.halfWidth(), 0),
          c = sample.at(-sample.halfWidth(), 0),
          d = sample.at(sample.halfWidth(), 0);
      double minX = Math.min(Math.min(a.x(), b.x()), Math.min(c.x(), d.x())) - margin;
      double maxX = Math.max(Math.max(a.x(), b.x()), Math.max(c.x(), d.x())) + margin;
      double minZ = Math.min(Math.min(a.z(), b.z()), Math.min(c.z(), d.z())) - margin;
      double maxZ = Math.max(Math.max(a.z(), b.z()), Math.max(c.z(), d.z())) + margin;
      for (int x = (int) Math.floor(minX) >> 4; x <= ((int) Math.floor(maxX) >> 4); x++)
        for (int z = (int) Math.floor(minZ) >> 4; z <= ((int) Math.floor(maxZ) >> 4); z++)
          result.add((x & 0xffffffffL) | ((long) z << 32));
      previous = sample;
    }
    return result;
  }

  private RoadCoverage() {}
}
