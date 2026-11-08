package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.V;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** Fit the entire tactile ribbon to the visible, sloping sidewalk surface. */
public final class TactileSurface {
  private final Map<Long,List<Part>> cells=new HashMap<>();
  private final Map<Long,List<Part>> neighborhoods=new HashMap<>();
  private final Map<Part,List<V>> bases=new IdentityHashMap<>();
  private final Map<Part,Double> tops=new IdentityHashMap<>();
  private final Map<V,Double> grades=new HashMap<>();
  private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
  public TactileSurface(List<Part> parts){
    for(var p:parts)if(p.material().name().startsWith("WALK_")){
      var v=p.base();
      bases.put(p,v);
      tops.put(p,v.stream().mapToDouble(V::y).max().orElseThrow()+p.height());
      int x0=(int)Math.floor(v.stream().mapToDouble(V::x).min().orElseThrow()/4),x1=(int)Math.floor(v.stream().mapToDouble(V::x).max().orElseThrow()/4);
      int z0=(int)Math.floor(v.stream().mapToDouble(V::z).min().orElseThrow()/4),z1=(int)Math.floor(v.stream().mapToDouble(V::z).max().orElseThrow()/4);
      for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++)cells.computeIfAbsent(key(x,z),k->new ArrayList<>()).add(p);
    }
  }
  public static double triangle(V p,V a,V b,V c){
    double den=(b.z()-c.z())*(a.x()-c.x())+(c.x()-b.x())*(a.z()-c.z());
    if(Math.abs(den)<1e-12)return Double.NaN;
    double u=((b.z()-c.z())*(p.x()-c.x())+(c.x()-b.x())*(p.z()-c.z()))/den;
    double v=((c.z()-a.z())*(p.x()-c.x())+(a.x()-c.x())*(p.z()-c.z()))/den;
    return u>=-1e-5&&v>=-1e-5&&u+v<=1.00001?u*a.y()+v*b.y()+(1-u-v)*c.y():Double.NaN;
  }
  public double height(V point){
    double top=Double.NEGATIVE_INFINITY;
    for(var p:cells.getOrDefault(key((int)Math.floor(point.x()/4),(int)Math.floor(point.z()/4)),List.of())){
      var q=bases.get(p);double y=triangle(point,q.get(0),q.get(1),q.get(2));
      if(!Double.isFinite(y))y=triangle(point,q.get(0),q.get(2),q.get(3));
      y+=p.height();
      // Distinct decks do not share a sidewalk surface.
      if(Double.isFinite(y)&&Math.abs(y-point.y())<2)top=Math.max(top,y);
    }
    return top;
  }
  /** A continuous upper envelope replaces small sidewalk steps with a paved slope. */
  private double gradedHeight(V point){
    return grades.computeIfAbsent(point,this::computeGrade);
  }
  private double computeGrade(V point){
    double top=height(point);
    int x=(int)Math.floor(point.x()/4),z=(int)Math.floor(point.z()/4);
    var nearby=neighborhoods.computeIfAbsent(key(x,z),ignored->{
      var found=new HashSet<Part>();
      for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)found.addAll(cells.getOrDefault(key(x+dx,z+dz),List.of()));
      return List.copyOf(found);
    });
    for(var p:nearby){
      var q=bases.get(p);
      // The distance penalty cannot raise a slab above its highest corner.
      // Flat paved runs therefore need no repeated nearest-edge calculation.
      if(tops.get(p)<=top)continue;
      for(int i=0;i<4;i++){
        V a=q.get(i),b=q.get((i+1)%4),delta=b.sub(a);
        double den=delta.x()*delta.x()+delta.z()*delta.z();
        double t=den<1e-10?0:Math.max(0,Math.min(1,((point.x()-a.x())*delta.x()+(point.z()-a.z())*delta.z())/den));
        V at=a.add(delta.mul(t));double y=at.y()+p.height();
        if(Math.abs(y-point.y())>1.5)continue;
        top=Math.max(top,y-.2*at.sub(point).horizontalLength());
      }
    }
    return top;
  }
  public List<Part> gradePaving(List<Part> parts){
    var out=new ArrayList<Part>();
    for(var p:parts){
      RoadPlanningBudget.check();
      if(!p.material().name().startsWith("WALK_")){out.add(p);continue;}
      // Sample the envelope before clipping: both owners get the same seam height.
      var base=p.base();int n=Math.max(1,(int)Math.ceil(p.a().distance(p.b())/.4));
      for(int j=0;j<n;j++){
        double a=j/(double)n,b=(j+1)/(double)n;
        var q=new ArrayList<V>(List.of(base.get(0).add(base.get(3).sub(base.get(0)).mul(a)),base.get(1).add(base.get(2).sub(base.get(1)).mul(a)),base.get(1).add(base.get(2).sub(base.get(1)).mul(b)),base.get(0).add(base.get(3).sub(base.get(0)).mul(b))));
        for(int k=0;k<4;k++){V v=q.get(k);double y=gradedHeight(v);if(Double.isFinite(y))q.set(k,new V(v.x(),Math.max(v.y(),y-p.height()),v.z()));}
        V start=q.get(0).add(q.get(1)).mul(.5),end=q.get(2).add(q.get(3)).mul(.5);
        out.add(new Part(start,end,p.width(),p.height(),false,p.material(),q.get(0).sub(start),q.get(3).sub(end),p.model()));
      }
    }
    return List.copyOf(out);
  }
  public List<Part> conform(List<Part> parts){
    var out=new ArrayList<Part>();
    for(var p:parts){
      if(p.material()!=Material.TACTILE){out.add(p);continue;}
      var q=new ArrayList<>(p.base());double lift=p.height()>.018?.015:0;
      for(int i=0;i<4;i++){V v=q.get(i);double y=height(v);if(Double.isFinite(y))q.set(i,new V(v.x(),y+lift+.001,v.z()));}
      V a=q.get(0).add(q.get(1)).mul(.5),b=q.get(2).add(q.get(3)).mul(.5);
      out.add(new Part(a,b,p.width(),p.height(),false,p.material(),q.get(0).sub(a),q.get(3).sub(b),p.model()));
    }
    return List.copyOf(out);
  }
}
