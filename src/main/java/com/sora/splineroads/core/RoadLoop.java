package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.V;

/**
 * Circle/tangent/circle path with the selected initial turning side and exact endpoint headings.
 */
public final class RoadLoop {
  private record Path(
      V c1,
      V c2,
      V t1,
      V t2,
      double angle1,
      double angle2,
      double arc1,
      double arc2,
      double straight,
      double secondTurn) {
    double length(double radius) {
      return radius * (arc1 + arc2) + straight;
    }
  }

  private final V start, end;
  private final double radius, turn, length;
  private final Path path;

  public RoadLoop(V a, V b, V da, V db, double radius, boolean right) {
    start = a;
    end = b;
    this.radius = radius;
    turn = right ? 1 : -1;
    Path same = candidate(a, b, da, db, turn), opposite = candidate(a, b, da, db, -turn);
    path =
        opposite != null && opposite.length(radius) < same.length(radius) - 1e-7 ? opposite : same;
    length = path.length(radius);
    if (length < 1e-7) throw new IllegalArgumentException("环绕端口重合，请调整半径或端点");
  }

  private Path candidate(V a, V b, V da, V db, double second) {
    V c1 = a.add(da.horizontalUnit().left().mul(turn * radius)),
        c2 = b.add(db.horizontalUnit().left().mul(second * radius));
    V delta = c2.sub(c1);
    double d = delta.horizontalLength(),
        angle1 = Math.atan2(a.z() - c1.z(), a.x() - c1.x()),
        finish = Math.atan2(b.z() - c2.z(), b.x() - c2.x());
    if (d < 1e-7) {
      if (second != turn) return null;
      return new Path(
          c1, c2, b, b, angle1, finish, positive(turn * (finish - angle1)), 0, 0, second);
    }
    double offset = (second - turn) * radius;
    if (Math.abs(offset) > d + 1e-7) return null;
    double heading =
        Math.atan2(delta.z(), delta.x()) - Math.asin(Math.max(-1, Math.min(1, offset / d)));
    V forward = new V(Math.cos(heading), 0, Math.sin(heading));
    V r1 = forward.left().mul(-turn * radius), r2 = forward.left().mul(-second * radius);
    V t1 = c1.add(r1), t2 = c2.add(r2);
    double angleT1 = Math.atan2(r1.z(), r1.x()), angleT2 = Math.atan2(r2.z(), r2.x());
    return new Path(
        c1,
        c2,
        t1,
        t2,
        angle1,
        angleT2,
        positive(turn * (angleT1 - angle1)),
        positive(second * (finish - angleT2)),
        t2.sub(t1).horizontalLength(),
        second);
  }

  private static double positive(double angle) {
    double v = angle % (2 * Math.PI);
    if (v < -1e-10) v += 2 * Math.PI;
    return Math.max(0, v);
  }

  public double length() {
    return length;
  }

  public V point(double t) {
    if (t <= 0) return start;
    if (t >= 1) return end;
    double d = t * length;
    if (d < path.arc1() * radius) return circle(path.c1(), path.angle1() + turn * d / radius);
    d -= path.arc1() * radius;
    if (d < path.straight())
      return path.t1().add(path.t2().sub(path.t1()).mul(d / path.straight()));
    return circle(path.c2(), path.angle2() + path.secondTurn() * (d - path.straight()) / radius);
  }

  private V circle(V center, double angle) {
    return new V(center.x() + radius * Math.cos(angle), 0, center.z() + radius * Math.sin(angle));
  }
}
