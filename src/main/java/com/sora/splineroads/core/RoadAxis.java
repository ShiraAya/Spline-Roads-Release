package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** An authored centreline, parameterised by horizontal distance, with tangent extensions. */
public final class RoadAxis {
  private final List<V> points;
  private final double[] stations;
  private final V[] tangents;
  public RoadAxis(List<V> source) {
    var clean=new ArrayList<V>();
    for(V p:source){
      if(!RoadGeometry.finite(p.x(),p.y(),p.z()))throw new IllegalArgumentException("主路路径数值无效");
      if(clean.isEmpty()||p.sub(clean.get(clean.size()-1)).horizontalLength()>1e-6)clean.add(p);
    }
    if(clean.size()<2||clean.size()>RoadLimits.MAX_SAMPLES)throw new IllegalArgumentException("主路路径采样无效");
    points=List.copyOf(clean);stations=new double[points.size()];tangents=new V[points.size()];
    for(int i=1;i<points.size();i++)stations[i]=stations[i-1]+points.get(i).sub(points.get(i-1)).horizontalLength();
    for(int i=0;i<points.size();i++){
      V before=points.get(Math.max(1,i)).sub(points.get(Math.max(0,i-1))).horizontalUnit();
      V after=points.get(Math.min(i+1,points.size()-1)).sub(points.get(Math.min(i,points.size()-2))).horizontalUnit();
      if(i>0&&i<points.size()-1&&before.dot(after)<.985)throw new IllegalArgumentException("选中的主路含有折角；请先改为平滑曲线");
      tangents[i]=i==0?after:i==points.size()-1?before:before.add(after).horizontalUnit();
    }
  }
  public static RoadAxis straight(V a,V b){return new RoadAxis(List.of(a,b));}
  public static RoadAxis of(Mesh mesh){return new RoadAxis(mesh.samples().stream().map(Sample::center).toList());}
  public List<V> points(){return points;}
  public double length(){return stations[stations.length-1];}
  public boolean curved(){V a=points.get(0),u=points.get(points.size()-1).sub(a).horizontalUnit();return points.stream().anyMatch(p->Math.abs(p.sub(a).dot(u.left()))>.1);}
  public boolean flat(){double y=points.get(0).y();return points.stream().allMatch(p->Math.abs(p.y()-y)<.001);}
  private int segment(double d){int i=Arrays.binarySearch(stations,d);return Math.max(0,Math.min(stations.length-2,i>=0?i:-i-2));}
  public V at(double d){
    if(d<0)return points.get(0).add(tangents[0].mul(d));
    if(d>length())return points.get(points.size()-1).add(tangents[tangents.length-1].mul(d-length()));
    int i=segment(d);double t=(d-stations[i])/(stations[i+1]-stations[i]);return points.get(i).add(points.get(i+1).sub(points.get(i)).mul(t));
  }
  public V tangent(double d){int i=segment(d);double t=Math.max(0,Math.min(1,(d-stations[i])/(stations[i+1]-stations[i])));return tangents[i].mul(1-t).add(tangents[i+1].mul(t)).horizontalUnit();}
  public double project(V point){
    double best=Double.POSITIVE_INFINITY,result=0;
    for(int i=0;i<points.size()-1;i++){
      V a=points.get(i),delta=points.get(i+1).sub(a);double span=stations[i+1]-stations[i];
      double t=point.sub(a).dot(delta.horizontalUnit());
      t=Math.max(i==0?-4096:0,Math.min(i==points.size()-2?span+4096:span,t));
      double error=point.sub(a.add(delta.horizontalUnit().mul(t))).horizontalLength();
      if(error<best){best=error;result=stations[i]+t;}
    }return result;
  }
  public RoadAxis slice(double start,double end,double yShift){
    if(end-start<1)throw new IllegalArgumentException("主路剩余长度不足");
    var p=new ArrayList<V>();p.add(at(start).add(new V(0,yShift,0)));
    for(int i=0;i<points.size();i++)if(stations[i]>start+1e-6&&stations[i]<end-1e-6)p.add(points.get(i).add(new V(0,yShift,0)));
    p.add(at(end).add(new V(0,yShift,0)));return new RoadAxis(p);
  }
  public record Crossing(double first,double second,V point){}
  /** One real crossing, or a tangent extension of the terminating C approach. */
  public static Crossing crossing(RoadAxis a,RoadAxis b,boolean terminating){
    var matches=new ArrayList<Crossing>();
    for(int i=0;i<a.points.size()-1;i++)for(int j=0;j<b.points.size()-(terminating?0:1);j++){
      V p=a.points.get(i),q=b.at(j==b.points.size()-1?b.length():b.stations[j]);
      V da=a.points.get(i+1).sub(p),db=j==b.points.size()-1?b.tangent(b.length()).mul(2048):b.points.get(j+1).sub(q);
      double det=cross(da,db);if(Math.abs(det)<1e-8)continue;
      double x=cross(q.sub(p),db)/det,z=cross(q.sub(p),da)/det;
      if(x< -1e-7||x>1+1e-7||z< -1e-7||z>1+1e-7)continue;
      double sa=a.stations[i]+x*da.horizontalLength(),sb=b.stations[j]+z*db.horizontalLength();
      if(matches.stream().noneMatch(c->Math.abs(c.first-sa)<.05&&Math.abs(c.second-sb)<.05))matches.add(new Crossing(sa,sb,a.at(sa)));
    }
    if(matches.size()!=1)throw new IllegalArgumentException(matches.isEmpty()?"两条主路曲线未相交；三向 C 支路应朝向 AB":"两条主路多次相交，请缩小选区至一个交点");
    return matches.get(0);
  }
  public static double cross(V a,V b){return a.x()*b.z()-a.z()*b.x();}
}
