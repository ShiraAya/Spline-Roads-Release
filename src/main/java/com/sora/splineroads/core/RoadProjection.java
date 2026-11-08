package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;

/** Exact nearest-segment query with conservative bounds; no coarser geometry or tolerances. */
final class RoadProjection {
  private final Mesh mesh;
  private final Branch root;

  private final class Branch {
    final int from, to;
    final double minX, minY, minZ, maxX, maxY, maxZ;
    final Branch a, b;

    Branch(int from, int to) {
      this.from = from;
      this.to = to;
      double x0 = Double.POSITIVE_INFINITY, y0 = x0, z0 = x0;
      double x1 = -x0, y1 = x1, z1 = x1;
      for (int i = from - 1; i < to; i++) {
        V p = mesh.samples().get(i).center();
        x0 = Math.min(x0, p.x());
        y0 = Math.min(y0, p.y());
        z0 = Math.min(z0, p.z());
        x1 = Math.max(x1, p.x());
        y1 = Math.max(y1, p.y());
        z1 = Math.max(z1, p.z());
      }
      minX = x0;
      minY = y0;
      minZ = z0;
      maxX = x1;
      maxY = y1;
      maxZ = z1;
      int mid = (from + to) / 2;
      a = to - from > 8 ? new Branch(from, mid) : null;
      b = a == null ? null : new Branch(mid, to);
    }

    double bound(V p, boolean horizontal) {
      double x = Math.max(0, Math.max(minX - p.x(), p.x() - maxX));
      double z = Math.max(0, Math.max(minZ - p.z(), p.z() - maxZ));
      double y = horizontal ? 0 : Math.max(0, Math.max(minY - p.y(), p.y() - maxY));
      return x * x + y * y + z * z;
    }
  }

  RoadProjection(Mesh mesh) {
    this.mesh = mesh;
    root = new Branch(1, mesh.samples().size());
  }

  private static final class Hit {
    double score = Double.POSITIVE_INFINITY, t;
    int segment = Integer.MAX_VALUE;
  }

  RoadQueries.Projection project(V point, boolean horizontal) {
    Hit hit = new Hit();
    visit(root, point, horizontal, hit);
    Sample a = mesh.samples().get(hit.segment - 1), b = mesh.samples().get(hit.segment);
    V d = b.center().sub(a.center()), center = a.center().add(d.mul(hit.t));
    V left = a.left().add(b.left().sub(a.left()).mul(hit.t)).horizontalUnit();
    return new RoadQueries.Projection(
        new Sample(
            center,
            left,
            a.distance() + (b.distance() - a.distance()) * hit.t,
            a.halfWidth() + (b.halfWidth() - a.halfWidth()) * hit.t),
        d.mul(1 / d.horizontalLength()),
        point.sub(center).dot(left),
        point.sub(center).horizontalLength());
  }

  private void visit(Branch branch, V p, boolean horizontal, Hit hit) {
    if (branch.bound(p, horizontal) > hit.score + 1e-10) return;
    if (branch.a != null) {
      boolean first = branch.a.bound(p, horizontal) <= branch.b.bound(p, horizontal);
      visit(first ? branch.a : branch.b, p, horizontal, hit);
      visit(first ? branch.b : branch.a, p, horizontal, hit);
      return;
    }
    for (int i = branch.from; i < branch.to; i++) {
      V a = mesh.samples().get(i - 1).center(), b = mesh.samples().get(i).center();
      double dx = b.x() - a.x(), dz = b.z() - a.z();
      double t =
          Math.max(
              0, Math.min(1, ((p.x() - a.x()) * dx + (p.z() - a.z()) * dz) / (dx * dx + dz * dz)));
      double x = p.x() - (a.x() + dx * t), z = p.z() - (a.z() + dz * t);
      double y = horizontal ? 0 : p.y() - (a.y() + (b.y() - a.y()) * t);
      double score = x * x + z * z + y * y;
      if (score < hit.score || score == hit.score && i < hit.segment) {
        hit.score = score;
        hit.t = t;
        hit.segment = i;
      }
    }
  }
}
