package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.V;
import com.sora.splineroads.core.RoadStructures.Part;
import java.util.*;

/** Actual vertical-extruded triangle volumes, not just a road centreline/width.
 * Generic Part collision splits its base into the same two triangles. This helper
 * lets lamps respect an upper sidewalk OUTSIDE the road deck's own footprint.
 * No world reads, voxelization, or approximation by a global sloped-part Y interval.
 */
public final class RoadSolidOverlap {
  private static final double EPS=1e-7;
  private static final V UP=new V(0,1,0);
  private record Body(List<V[]> prisms,double x0,double y0,double z0,double x1,double y1,double z1) {}
  private static Body body(Part part){
    var base=part.base();var top=part.top();var prisms=new ArrayList<V[]>();
    for(int i=1;i<base.size()-1;i++){
      V a=base.get(0),b=base.get(i),c=base.get(i+1);
      if(Math.abs(cross(b.sub(a),c.sub(a)).y())<1e-12)continue;
      prisms.add(new V[]{a,b,c,top.get(0),top.get(i),top.get(i+1)});
    }
    return new Body(List.copyOf(prisms),base.stream().mapToDouble(V::x).min().orElseThrow(),base.stream().mapToDouble(V::y).min().orElseThrow(),base.stream().mapToDouble(V::z).min().orElseThrow(),
        base.stream().mapToDouble(V::x).max().orElseThrow(),base.stream().mapToDouble(V::y).max().orElseThrow()+part.height(),base.stream().mapToDouble(V::z).max().orElseThrow());
  }
  public static boolean intersects(Part a,Part b){return intersects(body(a),body(b));}
  private static boolean intersects(Body a,Body b){
    if(a.x1<=b.x0+EPS||b.x1<=a.x0+EPS||a.y1<=b.y0+EPS||b.y1<=a.y0+EPS||a.z1<=b.z0+EPS||b.z1<=a.z0+EPS)return false;
    for(var p:a.prisms)for(var q:b.prisms)if(prisms(p,q))return true;return false;
  }
  private static boolean prisms(V[] a,V[] b){
    V[] ea={a[1].sub(a[0]),a[2].sub(a[1]),a[0].sub(a[2]),UP};
    V[] eb={b[1].sub(b[0]),b[2].sub(b[1]),b[0].sub(b[2]),UP};
    if(separates(cross(ea[0],ea[1]),a,b)||separates(cross(eb[0],eb[1]),a,b)
        ||separates(cross(a[4].sub(a[3]),a[5].sub(a[3])),a,b)||separates(cross(b[4].sub(b[3]),b[5].sub(b[3])),a,b))return false;
    for(int i=0;i<3;i++)if(separates(cross(ea[i],UP),a,b)||separates(cross(eb[i],UP),a,b))return false;
    if(parallel(a)&&parallel(b)){for(V x:ea)for(V y:eb)if(separates(cross(x,y),a,b))return false;return true;}
    var ax=new ArrayList<V>(Arrays.asList(ea));var bx=new ArrayList<V>(Arrays.asList(eb));
    for(int i=0;i<3;i++){ax.add(a[3+(i+1)%3].sub(a[3+i]));bx.add(b[3+(i+1)%3].sub(b[3+i]));}
    for(V x:ax)for(V y:bx)if(separates(cross(x,y),a,b))return false;
    return true;
  }
  private static boolean parallel(V[] p){double h=p[3].y()-p[0].y();return Math.abs(p[4].y()-p[1].y()-h)<1e-10&&Math.abs(p[5].y()-p[2].y()-h)<1e-10;}
  private static boolean separates(V axis,V[] a,V[] b){
    double norm=Math.sqrt(axis.dot(axis));if(norm<1e-12)return false;
    double amin=Double.POSITIVE_INFINITY,amax=-amin,bmin=amin,bmax=-amin;
    // Common local origin avoids large-world-coordinate dot cancellation.
    V origin=a[0];for(V v:a){double t=v.sub(origin).dot(axis);amin=Math.min(amin,t);amax=Math.max(amax,t);}
    for(V v:b){double t=v.sub(origin).dot(axis);bmin=Math.min(bmin,t);bmax=Math.max(bmax,t);}
    return amax<=bmin+EPS*norm||bmax<=amin+EPS*norm;
  }
  private static V cross(V a,V b){return new V(a.y()*b.z()-a.z()*b.y(),a.z()*b.x()-a.x()*b.z(),a.x()*b.y()-a.y()*b.x());}
  private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
  /** Built once per structure-planning snapshot, shared by all candidate lamp parts. */
  public static final class Index {
    private final Map<Long,List<Body>> bins=new HashMap<>();
    public Index(Collection<Part> parts){for(Part p:parts){Body b=body(p);for(int x=(int)Math.floor(b.x0/16);x<=(int)Math.floor(b.x1/16);x++)for(int z=(int)Math.floor(b.z0/16);z<=(int)Math.floor(b.z1/16);z++)bins.computeIfAbsent(key(x,z),k->new ArrayList<>()).add(b);}}
    public boolean intersects(Part p){Body b=body(p);Set<Body> visited=Collections.newSetFromMap(new IdentityHashMap<>());
      for(int x=(int)Math.floor(b.x0/16);x<=(int)Math.floor(b.x1/16);x++)for(int z=(int)Math.floor(b.z0/16);z<=(int)Math.floor(b.z1/16);z++)
        for(Body other:bins.getOrDefault(key(x,z),List.of()))if(visited.add(other)&&RoadSolidOverlap.intersects(b,other))return true;
      return false;
    }
  }
  private RoadSolidOverlap(){}
}
