package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** A right-turn road stays outside the filled plan-view envelope of a loop. */
public final class CloverleafEnvelope {
  public static boolean clear(Mesh right,Mesh loop){
    var sorted=new ArrayList<>(loop.samples().stream().map(Sample::center).toList());
    sorted.sort(Comparator.comparingDouble(V::x).thenComparingDouble(V::z));
    var hull=new ArrayList<V>();
    for(var p:sorted){while(hull.size()>1&&turn(hull.get(hull.size()-2),hull.get(hull.size()-1),p)<=1e-9)hull.remove(hull.size()-1);hull.add(p);}
    int lower=hull.size();
    for(int i=sorted.size()-2;i>=0;i--){var p=sorted.get(i);while(hull.size()>lower&&turn(hull.get(hull.size()-2),hull.get(hull.size()-1),p)<=1e-9)hull.remove(hull.size()-1);hull.add(p);}
    if(hull.size()<4)return true;hull.remove(hull.size()-1);
    double loopHalf=loop.samples().stream().mapToDouble(Sample::halfWidth).max().orElse(0);
    for(int j=1;j<right.samples().size();j++){
      var first=right.samples().get(j-1);var last=right.samples().get(j);
      double padding=loopHalf+Math.max(first.halfWidth(),last.halfWidth()),lo=0,hi=1;
      // Clip the complete segment against the expanded convex loop. Testing only
      // sampled centers can miss a chord whose two endpoints are outside.
      for(int i=0;i<hull.size()&&lo<=hi;i++){
        V a=hull.get(i),b=hull.get((i+1)%hull.size());double edge=b.sub(a).horizontalLength();
        double start=turn(a,b,first.center())+padding*edge-1e-6,end=turn(a,b,last.center())+padding*edge-1e-6,delta=end-start;
        if(Math.abs(delta)<1e-12){if(start<=0){lo=1;hi=0;}}
        else if(delta>0)lo=Math.max(lo,-start/delta);else hi=Math.min(hi,-start/delta);
      }
      if(lo<hi-1e-10)return false;
    }
    return true;
  }
  private static double turn(V a,V b,V p){return (b.x()-a.x())*(p.z()-a.z())-(b.z()-a.z())*(p.x()-a.x());}
  private CloverleafEnvelope(){}
}
