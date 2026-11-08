package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;

public final class RoadQueries {
  public record Projection(Sample sample, V tangent, double lateral, double horizontalDistance) {}

  // Identity avoids hashing complete sample lists. Evict one old entry rather than
  // clearing the entire working set whenever a junction exceeds 128 fragments.
  private record Key(Mesh mesh) {
    @Override public int hashCode(){return System.identityHashCode(mesh);}
    @Override public boolean equals(Object other){return other instanceof Key k&&mesh==k.mesh;}
  }
  private static final class Cache {
    final java.util.LinkedHashMap<Key,RoadProjection> entries=new java.util.LinkedHashMap<>(64,.75f,true);
    final int capacity,sampleLimit;int samples;long builds;
    Cache(int capacity,int sampleLimit){this.capacity=capacity;this.sampleLimit=sampleLimit;}
    RoadProjection get(Mesh mesh){
      var key=new Key(mesh);var old=entries.get(key);if(old!=null)return old;
      var value=new RoadProjection(mesh);builds++;int weight=mesh.samples().size();
      if(weight>sampleLimit)return value;
      var it=entries.entrySet().iterator();
      while(it.hasNext()&&(entries.size()>=capacity||samples+weight>sampleLimit)){samples-=it.next().getKey().mesh.samples().size();it.remove();}
      entries.put(key,value);samples+=weight;return value;
    }
  }
  private static final ThreadLocal<Cache> PROJECTIONS=ThreadLocal.withInitial(()->new Cache(128,65_536));

  /** A large edit may visit hundreds of junction fragments per paving cell.
   * Keep that transaction's working set without retaining it on the server thread. */
  public static final class Scope implements AutoCloseable {
    private final Cache previous=PROJECTIONS.get(),current=new Cache(1024,262_144);
    private Scope(){PROJECTIONS.set(current);}
    @Override public void close(){if(PROJECTIONS.get()==current)PROJECTIONS.set(previous);}
  }
  public static Scope scope(){return new Scope();}
  record CacheStats(int entries,int samples,long builds){}
  static CacheStats cacheStats(){var c=PROJECTIONS.get();return new CacheStats(c.entries.size(),c.samples,c.builds);}

  private static RoadProjection index(Mesh mesh) {
    return PROJECTIONS.get().get(mesh);
  }

  public static void clearThreadCache(){PROJECTIONS.remove();}

  public static Projection project(Mesh mesh, V point) {
    return index(mesh).project(point, false);
  }

  public static Projection horizontal(Mesh mesh, V point) {
    return index(mesh).project(point, true);
  }

  public static boolean contains(Mesh mesh, V point, double margin, double heightTolerance) {
    if (point.x() < mesh.min().x() - margin
        || point.x() > mesh.max().x() + margin
        || point.z() < mesh.min().z() - margin
        || point.z() > mesh.max().z() + margin
        || point.y() < mesh.min().y() - heightTolerance
        || point.y() > mesh.max().y() + heightTolerance) return false;
    var p = project(mesh, point);
    // A road ribbon has flat ends. Nearest-point distance alone creates semicircular caps
    // and incorrectly considers the adjoining road's guardrail to be inside this road.
    if (!mesh.closed()) {
      V forwardA = mesh.first().left().left().mul(-1), forwardB = mesh.last().left().left().mul(-1);
      if (p.sample().distance() < 1e-7 && point.sub(mesh.first().center()).dot(forwardA) < -1e-7)
        return false;
      if (p.sample().distance() > mesh.length() - 1e-7
          && point.sub(mesh.last().center()).dot(forwardB) > 1e-7) return false;
    }
    return LaneDeck.present(mesh,p.sample(),p.lateral(),margin)
        && Math.abs(p.lateral()) <= p.sample().halfWidth() + margin
        && Math.abs(point.y() - p.sample().center().y()) <= heightTolerance;
  }

  /** Legacy full-width Y branches retain their graded, shared throat. */
  public static boolean joins(Mesh road, Mesh other, V point) {
    boolean legacyFork =
        (road.settings().style().ramp() && !road.settings().laneRamp())
            || (other.settings().style().ramp() && !other.settings().laneRamp());
    boolean shared =
        road.first().center().distance(other.first().center()) < .01
            || road.first().center().distance(other.last().center()) < .01
            || road.last().center().distance(other.first().center()) < .01
            || road.last().center().distance(other.last().center()) < .01;
    return contains(other, point, -.02, legacyFork && shared ? 1.05 : .12);
  }

  public static double ray(Mesh mesh, V origin, V direction, double limit) {
    double best = limit;
    for (int i = 1; i < mesh.samples().size(); i++) {
      Sample a = mesh.samples().get(i - 1), b = mesh.samples().get(i);
      for(var strip:LaneDeck.strips(mesh,a,b)) {
        best=Math.min(best,triangle(origin,direction,strip.al(),strip.ar(),strip.br()));
        best=Math.min(best,triangle(origin,direction,strip.al(),strip.br(),strip.bl()));
      }
    }
    return best < limit ? best : Double.POSITIVE_INFINITY;
  }

  private static double triangle(V o, V d, V a, V b, V c) {
    V e = b.sub(a), f = c.sub(a), p = cross(d, f);
    double det = e.dot(p);
    if (Math.abs(det) < 1e-10) return Double.POSITIVE_INFINITY;
    V t = o.sub(a);
    double u = t.dot(p) / det;
    if (u < 0 || u > 1) return Double.POSITIVE_INFINITY;
    V q = cross(t, e);
    double v = d.dot(q) / det;
    if (v < 0 || u + v > 1) return Double.POSITIVE_INFINITY;
    double hit = f.dot(q) / det;
    return hit >= 0 ? hit : Double.POSITIVE_INFINITY;
  }

  private static V cross(V a, V b) {
    return new V(
        a.y() * b.z() - a.z() * b.y(),
        a.z() * b.x() - a.x() * b.z(),
        a.x() * b.y() - a.y() * b.x());
  }

  private RoadQueries() {}
}
