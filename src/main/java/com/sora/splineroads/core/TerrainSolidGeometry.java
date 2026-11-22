package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.V;
import java.util.*;

/** Exact render solids. Collision's conservative voxel boxes must never become
 * the visible diagonal wall or footway boundary. Coordinates are cell-local. */
public final class TerrainSolidGeometry {
  private record Plane(V normal,double offset) {double distance(V p){return normal.dot(p)-offset;}}
  public static List<List<V>> retain(V origin,double cap,List<List<V>> outlines,List<RoadStructures.Part> cuts){
    var footprints=outlines.isEmpty()?List.of(List.of(new V(0,0,0),new V(1,0,0),new V(1,0,1),new V(0,0,1))):outlines;
    var pieces=new ArrayList<List<List<V>>>();
    for(var footprint:footprints){
      var faces=TerrainEdgeGeometry.faces(List.of(footprint)).stream().map(f->f.points().stream().map(p->new V(p.x(),p.y()*cap,p.z())).toList()).toList();
      pieces.add(faces);
    }
    for(var part:cuts){
      RoadPlanningBudget.check();var planes=new ArrayList<Plane>();
      for(var f:part.faces()){
        var n=RoadLighting.normal(f);if(n.dot(n)<.5)continue;
        planes.add(new Plane(n,n.dot(f.points().get(0).sub(origin))));
      }
      var next=new ArrayList<List<List<V>>>();
      for(var piece:pieces){
        if(planes.stream().anyMatch(p->piece.stream().flatMap(List::stream).allMatch(v->p.distance(v)>=-1e-8))){next.add(piece);continue;}
        var remainder=piece;
        for(var plane:planes){
          var outside=clip(remainder,new Plane(plane.normal().mul(-1),-plane.offset()));
          if(!outside.isEmpty())next.add(outside);
          remainder=clip(remainder,plane);if(remainder.isEmpty())break;
        }
      }
      pieces=next;if(pieces.isEmpty())break;
    }
    return pieces.stream().flatMap(List::stream).map(f->f.stream().map(p->new V(clamp(p.x()),clamp(p.y()/cap),clamp(p.z()))).toList()).toList();
  }
  private static double clamp(double v){return Math.max(0,Math.min(1,v));}
  private static List<List<V>> clip(List<List<V>> faces,Plane plane){
    if(faces.isEmpty())return List.of();
    boolean inside=false,outside=false;
    for(var f:faces)for(var v:f){double d=plane.distance(v);inside|=d< -1e-8;outside|=d>1e-8;}
    if(!inside)return List.of();if(!outside)return faces;
    var out=new ArrayList<List<V>>();var edge=new ArrayList<V>();
    for(var face:faces){
      var polygon=new ArrayList<V>();
      for(int i=0;i<face.size();i++){
        V a=face.get(i),b=face.get((i+1)%face.size());double da=plane.distance(a),db=plane.distance(b);
        if(da<=1e-9)polygon.add(a);
        if((da< -1e-9&&db>1e-9)||(da>1e-9&&db< -1e-9)){V p=a.add(b.sub(a).mul(da/(da-db)));polygon.add(p);edge.add(p);}
        else if(Math.abs(da)<=1e-9)edge.add(a);
      }
      polygon=clean(polygon);if(area(polygon)>1e-10)out.add(List.copyOf(polygon));
    }
    var ring=clean(edge);if(ring.size()>=3){
      V center=ring.stream().reduce(new V(0,0,0),V::add).mul(1.0/ring.size()),n=plane.normal();
      V u=unit(Math.abs(n.y())<.9?cross(new V(0,1,0),n):cross(new V(1,0,0),n)),v=cross(n,u);
      ring.sort(Comparator.comparingDouble(p->Math.atan2(p.sub(center).dot(v),p.sub(center).dot(u))));
      if(area(ring)>1e-10)out.add(List.copyOf(ring));
    }
    return out;
  }
  private static ArrayList<V> clean(List<V> points){var out=new ArrayList<V>();for(var p:points)if(out.stream().noneMatch(q->q.distance(p)<1e-8))out.add(p);return out;}
  private static V cross(V a,V b){return new V(a.y()*b.z()-a.z()*b.y(),a.z()*b.x()-a.x()*b.z(),a.x()*b.y()-a.y()*b.x());}
  private static V unit(V p){return p.mul(1/Math.sqrt(p.dot(p)));}
  private static double area(List<V> p){double result=0;for(int i=1;i+1<p.size();i++){var n=cross(p.get(i).sub(p.get(0)),p.get(i+1).sub(p.get(0)));result+=Math.sqrt(n.dot(n))*.5;}return result;}
  private TerrainSolidGeometry(){}
}
