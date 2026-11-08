package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Explicit, bounded sampled alignment shared by generated junctions and conformed ramp throats. */
public final class RoadRibbon {
  public static Mesh mesh(List<Sample> points, Settings settings) {
    settings.validate();
    if (points.size() < 2 || points.size() > RoadLimits.MAX_SAMPLES)
      throw new IllegalArgumentException("道路采样数量超限");
    V min = new V(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE),
        max = new V(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE);
    List<Sample> result = new ArrayList<>();
    Sample before = null;
    double d = 0;
    for (var p : points) {
      if (!RoadGeometry.finite(
              p.center().x(),
              p.center().y(),
              p.center().z(),
              p.left().x(),
              p.left().y(),
              p.left().z(),
              p.halfWidth())
          || Math.abs(p.center().x()) > 29999984
          || Math.abs(p.center().z()) > 29999984
          || p.center().y() < -2048
          || p.center().y() > 2048
          || p.halfWidth() < .25
          || p.halfWidth() > RoadLimits.maximumWidth(settings.style())/2
          || Math.abs(p.left().horizontalLength() - 1) > .001
          || Math.abs(p.left().y()) > .001) throw new IllegalArgumentException("道路采样数据超出有效范围");
      if (before != null) {
        V delta = p.center().sub(before.center());
        double h = delta.horizontalLength();
        if (h < 1e-8 || Math.abs(delta.y()) > h * .505)
          throw new IllegalArgumentException("立交坡度超过 50%，请扩大范围或降低层高");
        if (before.left().dot(p.left()) < 0) throw new IllegalArgumentException("立交路径折返");
        for (int side : new int[] {-1, 1})
          if (p.at(side * p.halfWidth(), 0).sub(before.at(side * before.halfWidth(), 0)).dot(delta)
              <= 0) throw new IllegalArgumentException("立交内侧半径不足，请扩大范围");
        d += delta.distance(new V(0, 0, 0));
      }
      result.add(new Sample(p.center(), p.left(), d, p.halfWidth()));
      before = p;
      for (int side : new int[] {-1, 1}) {
        V v = p.at(side * p.halfWidth(), 0);
        min =
            new V(
                Math.min(min.x(), v.x()),
                Math.min(min.y(), v.y() - settings.thickness()),
                Math.min(min.z(), v.z()));
        max = new V(Math.max(max.x(), v.x()), Math.max(max.y(), v.y()), Math.max(max.z(), v.z()));
      }
    }
    var bands=settings.options().lanePoints().bands();
    if(!bands.isEmpty()){
      var mapped=new ArrayList<LanePoints.Band>();int index=0;
      for(var p:points){
        double station=p.distance();while(index+1<bands.size()-1&&bands.get(index+1).station()<station)index++;
        var a=bands.get(index);var b=bands.get(Math.min(index+1,bands.size()-1));
        double t=b.station()==a.station()?0:Math.max(0,Math.min(1,(station-a.station())/(b.station()-a.station())));
        mapped.add(new LanePoints.Band(result.get(mapped.size()).distance(),a.low()+(b.low()-a.low())*t,a.high()+(b.high()-a.high())*t));
      }
      settings=settings.options(settings.options().lanePoints(settings.options().lanePoints().bands(mapped)));
    }
    return new Mesh(
        List.copyOf(result), settings, min, max, d, false, result.get(result.size() / 2).center());
  }

  public static List<Mesh> split(Mesh mesh, double length) {
    List<Mesh> out = new ArrayList<>();
    int first = 0;
    for (int i = 1; i < mesh.samples().size(); i++)
      if (mesh.samples().get(i).distance() - mesh.samples().get(first).distance() >= length
          || i == mesh.samples().size() - 1) {
        out.add(mesh(mesh.samples().subList(first, i + 1), mesh.settings()));
        first = i;
      }
    return List.copyOf(out);
  }

  /** A loop may cross itself only with the same usable clearance as any other crossing. */
  public static void checkSelfIntersections(Mesh mesh, double clearance) {
    if(mesh.samples().size()<96){shortSelfIntersections(mesh,clearance);return;}
    var samples = mesh.samples();
    var index = RoadPathIndex.segments(mesh);
    for (int i = 1; i < samples.size(); i++) {
      RoadPlanningBudget.check();
      V a = samples.get(i - 1).center(), b = samples.get(i).center();
      for (int segment : index.query(RoadPathIndex.segment(a,b),0)) {
        int j=segment+1;if(j<i+3)continue;
        V c = samples.get(j - 1).center(), d = samples.get(j).center();
        if (Math.max(a.x(), b.x()) < Math.min(c.x(), d.x())
            || Math.min(a.x(), b.x()) > Math.max(c.x(), d.x())
            || Math.max(a.z(), b.z()) < Math.min(c.z(), d.z())
            || Math.min(a.z(), b.z()) > Math.max(c.z(), d.z())) continue;
        double dx = b.x() - a.x(),
            dz = b.z() - a.z(),
            ex = d.x() - c.x(),
            ez = d.z() - c.z(),
            det = dx * ez - dz * ex;
        if (Math.abs(det) < 1e-10) continue;
        double ox = c.x() - a.x(),
            oz = c.z() - a.z(),
            t = (ox * ez - oz * ex) / det,
            u = (ox * dz - oz * dx) / det;
        if (t < 0 || t > 1 || u < 0 || u > 1) continue;
        double first = a.y() + t * (b.y() - a.y()), second = c.y() + u * (d.y() - c.y());
        if (Math.abs(first - second) < clearance + mesh.settings().thickness() - .05)
          throw new IllegalArgumentException("匝道路线上存在净空不足的自交，请调整预设或范围");
      }
    }
  }

  public static double minRadius(Mesh mesh) {
    double min = Double.POSITIVE_INFINITY;
    for (int i = 1; i < mesh.samples().size(); i++) {
      var a = mesh.samples().get(i - 1);
      var b = mesh.samples().get(i);
      double angle = Math.acos(Math.max(-1, Math.min(1, a.left().dot(b.left()))));
      if (angle > 1e-7) min = Math.min(min, b.center().sub(a.center()).horizontalLength() / angle);
    }
    return min;
  }

  public static Node start(Mesh mesh) {
    return node(mesh, true);
  }

  public static Node end(Mesh mesh) {
    return node(mesh, false);
  }

  private static Node node(Mesh mesh, boolean first) {
    int i = first ? 0 : mesh.samples().size() - 1;
    Sample a = mesh.samples().get(i), b = mesh.samples().get(first ? 1 : i - 1);
    V delta = first ? b.center().sub(a.center()) : a.center().sub(b.center());
    return new Node(
        a.center(), RoadPlanner.yaw(a.left().left().mul(-1)), delta.y() / delta.horizontalLength());
  }

  private static void shortSelfIntersections(Mesh mesh, double clearance) {
    var samples = mesh.samples();
    for (int i = 1; i < samples.size(); i++) {
      RoadPlanningBudget.check();
      V a = samples.get(i - 1).center(), b = samples.get(i).center();
      for (int j = i + 3; j < samples.size(); j++) {
        V c = samples.get(j - 1).center(), d = samples.get(j).center();
        if (Math.max(a.x(), b.x()) < Math.min(c.x(), d.x())
            || Math.min(a.x(), b.x()) > Math.max(c.x(), d.x())
            || Math.max(a.z(), b.z()) < Math.min(c.z(), d.z())
            || Math.min(a.z(), b.z()) > Math.max(c.z(), d.z())) continue;
        double dx = b.x() - a.x(),
            dz = b.z() - a.z(),
            ex = d.x() - c.x(),
            ez = d.z() - c.z(),
            det = dx * ez - dz * ex;
        if (Math.abs(det) < 1e-10) continue;
        double ox = c.x() - a.x(),
            oz = c.z() - a.z(),
            t = (ox * ez - oz * ex) / det,
            u = (ox * dz - oz * dx) / det;
        if (t < 0 || t > 1 || u < 0 || u > 1) continue;
        double first = a.y() + t * (b.y() - a.y()), second = c.y() + u * (d.y() - c.y());
        if (Math.abs(first - second) < clearance + mesh.settings().thickness() - .05)
          throw new IllegalArgumentException("匝道路线上存在净空不足的自交，请调整预设或范围");
      }
    }
  }
  private RoadRibbon() {}
}
