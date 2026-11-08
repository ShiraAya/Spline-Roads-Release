package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/**
 * Shared deterministic intent resolver. Existing junctions are constraints, placement yaw is a
 * hint.
 */
public final class RoadPlanner {
  public record Hint(Node node, boolean headingLocked, boolean gradeLocked, boolean linked) {
    public static Hint free(Node n) {
      return new Hint(n, false, false, false);
    }
  }

  public record Plan(Node start, Node end, Settings settings, Mesh mesh, String reason) {}

  public static String name(Mode m) {
    return switch (m) {
      case AUTO -> "智能";
      case STRAIGHT -> "直线";
      case CURVE -> "平滑曲线";
      case ARC -> "圆弧";
      case RING -> "圆环";
    };
  }

  public static double yaw(V direction) {
    return Math.toDegrees(Math.atan2(-direction.x(), direction.z()));
  }

  public static Settings mode(Settings s, Mode mode, double angle) {
    return new Settings(
        mode,
        s.style(),
        s.width(),
        s.thickness(),
        s.tension(),
        angle,
        s.startWidth(),
        s.endWidth(),
        s.structure(),
        s.taperVersion(),
        s.rampTurn(),
        s.options());
  }

  private static double angle(V a, V b) {
    return Math.atan2(a.x() * b.z() - a.z() * b.x(), a.dot(b));
  }

  private static Node heading(Node n, V direction, double grade) {
    return new Node(n.position(), yaw(direction), grade);
  }

  public static Plan plan(Hint ah, Hint bh, Settings request) {
    request.validate();
    Node a = ah.node, b = bh.node;
    var controls=request.options().routing().controls();
    if(!controls.isEmpty()){
      if(!ah.headingLocked())a=heading(a,controls.get(0).position().sub(a.position()),ah.gradeLocked()?a.grade():0);
      if(!bh.headingLocked())b=heading(b,b.position().sub(controls.get(controls.size()-1).position()),bh.gradeLocked()?b.grade():0);
      return make(a,b,mode(request,Mode.CURVE,request.arcDegrees()),request.options().routing().through()?"经过点平滑曲线":"多控制点贝塞尔曲线");
    }
    if (request.mode() != Mode.AUTO) return make(a, b, request, "手动选择");
    V chord = b.position().sub(a.position()).horizontalUnit();
    V da = a.direction(), db = b.direction();
    double ga = ah.gradeLocked ? a.grade() : 0, gb = bh.gradeLocked ? b.grade() : 0;
    // New roundabouts are independent JunctionSpec objects; explicit legacy RING stays readable.
    // Looking back when placing the second marker must not create an accidental U-turn.
    if (!ah.headingLocked && da.dot(chord) < 0) da = da.mul(-1);
    if (!bh.headingLocked && db.dot(chord) < 0) db = db.mul(-1);
    // Location wins over incidental player yaw on axis-aligned placements. Explicit headings
    // and existing junction tangents remain hard constraints.
    V delta = b.position().sub(a.position());
    boolean axis = Math.abs(delta.x()) < 1e-6 || Math.abs(delta.z()) < 1e-6;
    boolean forwardA = !ah.headingLocked || da.dot(chord) > 1 - 1e-8;
    boolean forwardB = !bh.headingLocked || db.dot(chord) > 1 - 1e-8;
    boolean casualAlignment =
        da.dot(chord) > Math.cos(Math.toRadians(15))
            && db.dot(chord) > Math.cos(Math.toRadians(15));
    if (forwardA && forwardB && (axis || ah.headingLocked || bh.headingLocked || casualAlignment)) {
      Plan straight =
          attempt(
              heading(a, chord, ga),
              heading(b, chord, gb),
              mode(request, Mode.STRAIGHT, 90),
              "位置对齐，优先采用直线");
      if (straight != null) return straight;
    }
    double angleA = 2 * angle(da, chord), angleB = 2 * angle(chord, db);
    List<Double> arcs = new ArrayList<>();
    if (ah.headingLocked && !bh.headingLocked) arcs.add(angleA);
    else if (!ah.headingLocked && bh.headingLocked) arcs.add(angleB);
    else if (Math.abs(angleA - angleB)
        < (ah.headingLocked && bh.headingLocked ? 1e-7 : Math.toRadians(16)))
      arcs.add((angleA + angleB) / 2);
    for (double theta : arcs) {
      if (Math.abs(theta) < Math.toRadians(5) || Math.abs(theta) > Math.toRadians(300)) continue;
      Plan p =
          attempt(
              heading(a, da, ga),
              heading(b, db, gb),
              mode(request, Mode.ARC, Math.toDegrees(theta)),
              "根据端点切线拟合圆弧");
      if (p != null && matches(p, ah, bh)) return p;
    }
    if (ah.headingLocked && !bh.headingLocked) db = chord;
    if (!ah.headingLocked && bh.headingLocked) da = chord;
    // Try short handles first when a wide road would otherwise fold at a tight bend.
    for (double tension : new double[] {request.tension(), .5, .65, .25, .18, .85, 1.1}) {
      Settings curve =
          new Settings(
              Mode.CURVE,
              request.style(),
              request.width(),
              request.thickness(),
              tension,
              90,
              request.startWidth(),
              request.endWidth(),
              request.structure(),
              request.taperVersion(),
              request.rampTurn(),
              request.options());
      Plan p = attempt(heading(a, da, ga), heading(b, db, gb), curve, "自动衔接已有方向与坡度");
      if (p != null && matches(p, ah, bh)) return p;
    }
    if (!ah.headingLocked && !bh.headingLocked) {
      double grade =
          (b.position().y() - a.position().y()) / b.position().sub(a.position()).horizontalLength();
      Plan p =
          attempt(
              heading(a, chord, ah.gradeLocked ? ga : grade),
              heading(b, chord, bh.gradeLocked ? gb : grade),
              mode(request, Mode.STRAIGHT, 90),
              "空间不足，自动采用直线坡道");
      if (p != null) return p;
    }
    throw new IllegalArgumentException("空间不足以平滑连接，请把终点放远一些，或展开微调");
  }

  private static boolean matches(Plan p, Hint a, Hint b) {
    V da = p.mesh.first().left().left().mul(-1), db = p.mesh.last().left().left().mul(-1);
    return (!a.headingLocked || da.dot(a.node.direction()) > 1 - 1e-8)
        && (!b.headingLocked || db.dot(b.node.direction()) > 1 - 1e-8);
  }

  private static Plan attempt(Node a, Node b, Settings s, String reason) {
    try {
      return make(a, b, s, reason);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static Plan make(Node a, Node b, Settings s, String reason) {
    Mesh mesh = RoadGeometry.build(a, b, s);
    // Persist analytical headings, so continuing a straight/arc road does not inherit placement
    // yaw.
    Node start = heading(a, mesh.first().left().left().mul(-1), a.grade());
    Node end =
        heading(
            b,
            s.mode() == Mode.RING
                ? mesh.samples().get((mesh.samples().size() - 1) / 2).left().left().mul(-1)
                : mesh.last().left().left().mul(-1),
            b.grade());
    return new Plan(start, end, s, mesh, reason);
  }

  private RoadPlanner() {}
}
