package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.V;
import com.sora.splineroads.core.RoadSurface.Face;
import java.util.*;

/** Paint is one disjoint surface, so distance cannot quantise overlapping inks to the same depth. */
public final class SmartSignFaces {
  private record Paint(List<V> points,int color,double depth,double x0,double x1,double y0,double y1) {
    Paint(List<V> p,int color,double depth){this(p,color,depth,p.stream().mapToDouble(V::x).min().orElse(0),p.stream().mapToDouble(V::x).max().orElse(0),p.stream().mapToDouble(V::z).min().orElse(0),p.stream().mapToDouble(V::z).max().orElse(0));}
    boolean overlaps(Paint p){return x1>p.x0+1e-9&&p.x1>x0+1e-9&&y1>p.y0+1e-9&&p.y1>y0+1e-9;}
  }
  public static boolean front(Face f,SmartSigns.Layout l){
    double lo=Double.POSITIVE_INFINITY,hi=Double.NEGATIVE_INFINITY;
    for(V p:f.points()){double d=p.sub(l.origin()).dot(l.front());lo=Math.min(lo,d);hi=Math.max(hi,d);}
    return lo>=SmartSigns.FACE-1e-6&&hi-lo<1e-6;
  }
  public static List<Face> compose(SmartSigns.Layout l){
    var out=new ArrayList<Face>();var paints=new ArrayList<Paint>();
    for(Face f:l.faces()){
      if(!front(f,l)){out.add(f);continue;}
      var points=f.points().stream().map(p->{V d=p.sub(l.origin());return new V(d.dot(l.right()),0,d.y());}).toList();
      paints.add(new Paint(points,f.color(),f.points().get(0).sub(l.origin()).dot(l.front())));
    }
    paints.sort(Comparator.comparingDouble(Paint::depth));
    for(int i=0;i<paints.size();i++){
      Paint p=paints.get(i);List<List<V>> pieces=List.of(p.points());
      for(int j=i+1;j<paints.size()&&!pieces.isEmpty();j++){
        Paint cover=paints.get(j);if(!p.overlaps(cover))continue;
        // A subtraction clips against infinite edge lines. Do not split pieces that
        // the actual cover never touches: dense JCT diagrams otherwise fragment
        // into thousands of invisible cuts and can exceed the client cache limit.
        var next=new ArrayList<List<V>>();for(var piece:pieces){
          if(RoadSurface.intersect(piece,cover.points()).isEmpty())next.add(piece);
          else next.addAll(RoadSurface.subtract(piece,cover.points()));
        }pieces=next;
      }
      for(var piece:pieces)out.add(new Face(piece.stream().map(v->l.at(v.x(),v.z(),SmartSigns.FACE)).toList(),p.color()));
    }
    return List.copyOf(out);
  }
  private SmartSignFaces(){}
}

