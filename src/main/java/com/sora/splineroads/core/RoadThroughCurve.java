package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Chord-parameterized Hermite interpolation. Every authored point is a sampling knot. */
final class RoadThroughCurve {
  private final List<V> points,tangents;
  private final double[] knots;
  RoadThroughCurve(Node a,Node b,Settings settings){
    var p=new ArrayList<V>();p.add(a.position());settings.options().routing().controls().forEach(c->p.add(c.position()));p.add(b.position());
    knots=new double[p.size()];double total=0;
    for(int i=1;i<p.size();i++){
      double length=p.get(i).sub(p.get(i-1)).horizontalLength();
      if(length<2)throw new IllegalArgumentException("相邻经过点至少相距2格");
      if(p.get(i).distance(a.position())>RoadLimits.MAX_ENDPOINT_DISTANCE||p.get(i).distance(b.position())>RoadLimits.MAX_ENDPOINT_DISTANCE)throw new IllegalArgumentException("经过点距离端点不能超过2048格");
      total+=length;knots[i]=total;
    }
    if(total>RoadLimits.MAX_PATH_LENGTH)throw new IllegalArgumentException("经过点路线过长，请分段连接");
    for(int i=1;i<knots.length;i++)knots[i]/=total;
    var d=new ArrayList<V>();double strength=Math.min(1.5,settings.tension()*3);
    d.add(a.direction().add(new V(0,a.grade(),0)).mul(total*strength));
    for(int i=1;i<p.size()-1;i++){
      double left=knots[i]-knots[i-1],right=knots[i+1]-knots[i];
      V incoming=p.get(i).sub(p.get(i-1)).mul(1/left),outgoing=p.get(i+1).sub(p.get(i)).mul(1/right);
      d.add(incoming.mul(right).add(outgoing.mul(left)).mul(strength/(left+right)));
    }
    d.add(b.direction().add(new V(0,b.grade(),0)).mul(total*strength));points=List.copyOf(p);tangents=List.copyOf(d);
  }
  void knots(Set<Double> output){for(double t:knots){output.removeIf(v->Math.abs(v-t)<1e-10);output.add(t);}}
  private int segment(double t){int i=Arrays.binarySearch(knots,t);return Math.max(0,Math.min(knots.length-2,i>=0?i:-i-2));}
  V point(double t){int i=segment(t);double h=knots[i+1]-knots[i],u=(t-knots[i])/h,u2=u*u,u3=u2*u;
    return points.get(i).mul(2*u3-3*u2+1).add(tangents.get(i).mul(h*(u3-2*u2+u))).add(points.get(i+1).mul(-2*u3+3*u2)).add(tangents.get(i+1).mul(h*(u3-u2)));
  }
  V tangent(double t){int i=segment(t);double h=knots[i+1]-knots[i],u=(t-knots[i])/h;
    return points.get(i).mul((6*u*u-6*u)/h).add(tangents.get(i).mul(3*u*u-4*u+1)).add(points.get(i+1).mul((-6*u*u+6*u)/h)).add(tangents.get(i+1).mul(3*u*u-2*u));
  }
}
