package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.V;
import com.sora.splineroads.core.RoadSurface.*;
import java.util.*;

/** SR-authored solid primitives. Every polygon has an outward winding. */
public final class OriginalModels {
  public static V cross(V a,V b){return new V(a.y()*b.z()-a.z()*b.y(),a.z()*b.x()-a.x()*b.z(),a.x()*b.y()-a.y()*b.x());}
  public static void face(List<Face> out,List<V> points,V outward,int color,boolean light,Texture texture){
    var p=new ArrayList<>(points);if(cross(p.get(1).sub(p.get(0)),p.get(2).sub(p.get(0))).dot(outward)<0)Collections.reverse(p);
    out.add(new Face(List.copyOf(p),color,light,texture));
  }
  public static void box(List<Face> out,double x0,double y0,double z0,double x1,double y1,double z1,int color){
    prism(out,List.of(new V(x0,0,z0),new V(x1,0,z0),new V(x1,0,z1),new V(x0,0,z1)),y0,y1,color);
  }
  public static void bevel(List<Face> out,double x0,double y0,double z0,double x1,double y1,double z1,double bevel,int color){
    double b=Math.min(bevel,Math.min(x1-x0,z1-z0)/2);
    prism(out,List.of(new V(x0+b,0,z0),new V(x1-b,0,z0),new V(x1,0,z0+b),new V(x1,0,z1-b),new V(x1-b,0,z1),new V(x0+b,0,z1),new V(x0,0,z1-b),new V(x0,0,z0+b)),y0,y1,color);
  }
  public static void prism(List<Face> out,List<V> shape,double bottom,double top,int color){
    V center=shape.stream().reduce(new V(0,0,0),V::add).mul(1./shape.size());
    var lo=shape.stream().map(v->new V(v.x(),bottom,v.z())).toList();var hi=shape.stream().map(v->new V(v.x(),top,v.z())).toList();
    face(out,lo,new V(0,-1,0),color,false,Texture.PLAIN);face(out,hi,new V(0,1,0),color,false,Texture.PLAIN);
    for(int i=0;i<shape.size();i++){int j=(i+1)%shape.size();face(out,List.of(lo.get(i),lo.get(j),hi.get(j),hi.get(i)),shape.get(i).add(shape.get(j)).mul(.5).sub(center),color,false,Texture.PLAIN);}
  }
  public static void cylinder(List<Face> out,V a,V b,double ra,double rb,int segments,int color){
    V delta=b.sub(a);double length=a.distance(b);if(length<1e-8)return;V axis=delta.mul(1/length);
    V u=cross(axis,Math.abs(axis.y())<.9?new V(0,1,0):new V(1,0,0));u=u.mul(1/Math.sqrt(u.dot(u)));V v=cross(axis,u);
    var lo=new ArrayList<V>();var hi=new ArrayList<V>();
    for(int i=0;i<segments;i++){double angle=2*Math.PI*i/segments;V radial=u.mul(Math.cos(angle)).add(v.mul(Math.sin(angle)));lo.add(a.add(radial.mul(ra)));hi.add(b.add(radial.mul(rb)));}
    face(out,lo,axis.mul(-1),color,false,Texture.PLAIN);face(out,hi,axis,color,false,Texture.PLAIN);
    for(int i=0;i<segments;i++){int j=(i+1)%segments;face(out,List.of(lo.get(i),lo.get(j),hi.get(j),hi.get(i)),lo.get(i).add(lo.get(j)).mul(.5).sub(a),color,false,Texture.PLAIN);}
  }
  public static List<Face> transform(List<Face> source,V origin,V x,V y,V z){
    boolean mirror=cross(x,y).dot(z)<0;var out=new ArrayList<Face>();
    for(var f:source){var p=new ArrayList<V>();for(var v:f.points())p.add(origin.add(x.mul(v.x())).add(y.mul(v.y())).add(z.mul(v.z())));if(mirror)Collections.reverse(p);var uv=new ArrayList<>(f.uv());if(mirror)Collections.reverse(uv);out.add(new Face(List.copyOf(p),f.color(),f.emissive(),f.texture(),List.copyOf(uv)));}
    return List.copyOf(out);
  }
  private OriginalModels(){}
}
