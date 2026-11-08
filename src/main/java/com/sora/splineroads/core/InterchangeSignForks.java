package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.InterchangePlanner.Leg;
import java.util.*;

/** Physical diverging vertices, after the multi-arm planner deduplicates shared collectors. */
public final class InterchangeSignForks {
  private record Point(long x,long y,long z){Point(V p){this(Math.round(p.x()*100000),Math.round(p.y()*100000),Math.round(p.z()*100000));}}
  private record Out(String road,Point next,V heading){}
  public record Fork(String owner,String sibling,V origin,V right){}
  public static Map<String,Fork> find(List<Leg> legs){
    var out=new LinkedHashMap<Point,Map<Point,Out>>();var positions=new HashMap<Point,V>();
    var starts=new HashMap<String,Point>();
    for(var leg:legs)if(leg.mesh().settings().style().ramp()){
      starts.put(leg.name(),new Point(leg.mesh().first().center()));
      var samples=leg.mesh().samples();
      for(int i=1;i<samples.size();i++){
        V a=samples.get(i-1).center(),b=samples.get(i).center();Point p=new Point(a),q=new Point(b);
        if(p.equals(q))continue;positions.put(p,a);
        out.computeIfAbsent(p,k->new TreeMap<>(Comparator.comparingLong(Point::x).thenComparingLong(Point::y).thenComparingLong(Point::z)))
            .putIfAbsent(q,new Out(leg.name(),q,b.sub(a).horizontalUnit()));
      }
    }
    var result=new LinkedHashMap<String,Fork>();
    for(var entry:out.entrySet())if(entry.getValue().size()==2){
      var edges=new ArrayList<>(entry.getValue().values());var a=edges.get(0);var b=edges.get(1);
      if(a.road().equals(b.road())||a.heading().dot(b.heading())<.9)continue;
      // A newly split physical leg starts here. The continuing sibling may contain the trunk.
      Out owner=entry.getKey().equals(starts.get(a.road()))?a:b;
      if(!entry.getKey().equals(starts.get(owner.road())))continue;
      Out sibling=owner==a?b:a;
      result.put(owner.road(),new Fork(owner.road(),sibling.road(),positions.get(entry.getKey()),a.heading().add(b.heading()).horizontalUnit().left()));
    }
    return Collections.unmodifiableMap(result);
  }
  private InterchangeSignForks(){}
}
