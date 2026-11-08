package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
/** Exact retained terrain footprints. Render geometry never uses the outward collision raster. */
public final class TerrainEdgeGeometry {
  public static List<List<V>> retain(V origin,List<Mesh> roads){
    List<List<V>> pieces=List.of(List.of(new V(0,0,0),new V(1,0,0),new V(1,0,1),new V(0,0,1)));
    var cell=new RoadBoundsIndex.Bounds(origin.x(),origin.z(),origin.x()+1,origin.z()+1);
    for(var mesh:roads)for(int index:RoadPathIndex.segments(mesh).query(cell,mesh.settings().width()/2+2)){
      var a=mesh.samples().get(index);var b=mesh.samples().get(index+1);
      for(var strip:LaneDeck.strips(mesh,a,b)){
        var cut=List.of(strip.al(),strip.ar(),strip.br(),strip.bl()).stream().map(p->new V(p.x()-origin.x(),0,p.z()-origin.z())).toList();
        var next=new ArrayList<List<V>>();for(var piece:pieces)next.addAll(RoadSurface.subtract(piece,cut));pieces=next;
        if(pieces.isEmpty())return List.of();
      }
    }
    // Convex adjacent pieces are one face, not a triangle fan for every road sample.
    var result=new ArrayList<>(pieces);boolean changed=true;
    while(changed){changed=false;outer:for(int i=0;i<result.size();i++)for(int j=i+1;j<result.size();j++){
      var points=new ArrayList<>(result.get(i));points.addAll(result.get(j));var hull=hull(points);
      if(Math.abs(RoadSurface.area(hull)-RoadSurface.area(result.get(i))-RoadSurface.area(result.get(j)))<1e-9){result.set(i,hull);result.remove(j);changed=true;break outer;}
    }}return result.stream().map(List::copyOf).toList();
  }
  private static List<V> hull(List<V> points){
    var sorted=points.stream().distinct().sorted(Comparator.comparingDouble(V::x).thenComparingDouble(V::z)).toList();var hull=new ArrayList<V>();
    for(var p:sorted){while(hull.size()>1&&cross(hull.get(hull.size()-2),hull.get(hull.size()-1),p)<=1e-10)hull.remove(hull.size()-1);hull.add(p);}
    int lower=hull.size();for(int i=sorted.size()-2;i>=0;i--){var p=sorted.get(i);while(hull.size()>lower&&cross(hull.get(hull.size()-2),hull.get(hull.size()-1),p)<=1e-10)hull.remove(hull.size()-1);hull.add(p);}
    if(hull.size()>1)hull.remove(hull.size()-1);return hull;
  }
  private static double cross(V a,V b,V c){return (b.x()-a.x())*(c.z()-a.z())-(b.z()-a.z())*(c.x()-a.x());}
  /** Inscribed collision strips cannot put restored terrain inside the train volume. */
  public static List<RoadRaster.Box> collision(List<List<V>> pieces){
    var boxes=new ArrayList<RoadRaster.Box>();
    for(var piece:pieces){var levels=new TreeSet<Double>();piece.forEach(p->levels.add(p.z()));for(int i=0;i<=8;i++)levels.add(i/8.0);var zs=new ArrayList<>(levels);
      for(int i=1;i<zs.size();i++){double lo=zs.get(i-1),hi=zs.get(i);var a=range(piece,lo+1e-9);var b=range(piece,hi-1e-9);double left=Math.max(a[0],b[0]),right=Math.min(a[1],b[1]);
        if(right-left>1e-8&&hi-lo>1e-8)boxes.add(new RoadRaster.Box(Math.max(0,left),0,Math.max(0,lo),Math.min(1,right),1,Math.min(1,hi)));
      }
    }return List.copyOf(boxes);
  }
  private static double[] range(List<V> p,double z){double lo=Double.POSITIVE_INFINITY,hi=Double.NEGATIVE_INFINITY;
    for(int i=0;i<p.size();i++){var a=p.get(i);var b=p.get((i+1)%p.size());if(z<Math.min(a.z(),b.z())||z>Math.max(a.z(),b.z())||Math.abs(b.z()-a.z())<1e-12)continue;double x=a.x()+(b.x()-a.x())*(z-a.z())/(b.z()-a.z());lo=Math.min(lo,x);hi=Math.max(hi,x);}return new double[]{lo,hi};}
  public record Face(int axis,boolean positive,List<V> points){}
  public static List<Face> faces(List<List<V>> pieces){
    var result=new ArrayList<Face>();for(var piece:pieces){
      var top=new ArrayList<V>();for(var p:piece)top.add(new V(p.x(),1,p.z()));Collections.reverse(top);result.add(new Face(1,true,List.copyOf(top)));result.add(new Face(1,false,piece));
      for(int i=0;i<piece.size();i++){var a=piece.get(i);var b=piece.get((i+1)%piece.size());if(a.distance(b)<1e-8)continue;
        // The same partition edge is interior. Clipped neighboring faces share endpoints.
        boolean internal=false;for(var other:pieces)if(other!=piece)for(int k=0;k<other.size();k++)if(a.distance(other.get((k+1)%other.size()))<1e-8&&b.distance(other.get(k))<1e-8)internal=true;
        if(internal)continue;var delta=b.sub(a);int axis=Math.abs(delta.z())>Math.abs(delta.x())?0:2;boolean positive=axis==0?delta.z()>0:delta.x()<0;
        result.add(new Face(axis,positive,List.of(a,new V(a.x(),1,a.z()),new V(b.x(),1,b.z()),b)));
      }
    }return List.copyOf(result);
  }
  private TerrainEdgeGeometry(){}
}
