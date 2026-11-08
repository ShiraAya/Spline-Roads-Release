package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.InterchangePlanner.Leg;
import java.util.*;

/** Reconnects the physical, deduplicated five/six-arm collector graph. A
 * secondary movement starts inside a saved trunk, not necessarily at its end. */
public final class InterchangeSignPaths {
  private record Point(long x,long y,long z){Point(V p){this(Math.round(p.x()*100000),Math.round(p.y()*100000),Math.round(p.z()*100000));}}
  private record Edge(Point to,double length){}
  private record Visit(Point point,double distance){}
  public static Map<String,List<V>> routes(List<Leg> legs,V start,int from,List<Node> anchors){
    var graph=new HashMap<Point,List<Edge>>();var positions=new HashMap<Point,V>();var goals=new TreeMap<Integer,Set<Point>>();
    for(var leg:legs){var mesh=leg.mesh();if(!mesh.settings().style().ramp())continue;
      for(int i=1;i<mesh.samples().size();i++){V a=mesh.samples().get(i-1).center(),b=mesh.samples().get(i).center();var ka=new Point(a);var kb=new Point(b);if(ka.equals(kb))continue;positions.put(ka,a);positions.put(kb,b);graph.computeIfAbsent(ka,k->new ArrayList<>()).add(new Edge(kb,a.distance(b)));}
      if(leg.to()<0||leg.to()>=anchors.size()||leg.to()==from)continue;
      V end=mesh.last().center(),heading=mesh.last().left().left().mul(-1);
      for(var main:legs)if(!main.mesh().settings().style().ramp()&&(main.from()==leg.to()||main.to()==leg.to())){
        var q=RoadQueries.horizontal(main.mesh(),end);var at=q.sample();V outward=anchors.get(leg.to()).position().sub(at.center());
        if(Math.abs(end.y()-at.center().y())<.3&&RoadQueries.contains(main.mesh(),end,.25,.31)
            &&outward.horizontalLength()>1&&heading.dot(outward.horizontalUnit())>.9)goals.computeIfAbsent(leg.to(),k->new LinkedHashSet<>()).add(new Point(end));
      }
    }
    if(positions.size()>100000)throw new IllegalArgumentException("立交标牌拓扑超过可处理上限");
    var source=new Point(start);var distance=new HashMap<Point,Double>();var previous=new HashMap<Point,Point>();var queue=new PriorityQueue<Visit>(Comparator.comparingDouble(Visit::distance));distance.put(source,0.0);queue.add(new Visit(source,0));
    while(!queue.isEmpty()){var next=queue.remove();if(next.distance()>distance.getOrDefault(next.point(),Double.POSITIVE_INFINITY)+1e-7)continue;
      for(var edge:graph.getOrDefault(next.point(),List.of())){double d=next.distance()+edge.length();if(d+1e-7<distance.getOrDefault(edge.to(),Double.POSITIVE_INFINITY)){distance.put(edge.to(),d);previous.put(edge.to(),next.point());queue.add(new Visit(edge.to(),d));}}
    }
    var result=new LinkedHashMap<String,List<V>>();
    for(var goal:goals.entrySet()){var end=goal.getValue().stream().filter(distance::containsKey).min(Comparator.comparingDouble(distance::get)).orElse(null);if(end==null)continue;
      var path=new ArrayList<V>();for(var at=end;at!=null;at=previous.get(at)){path.add(positions.get(at));if(at.equals(source))break;}Collections.reverse(path);path.add(anchors.get(goal.getKey()).position());result.put("out:arm:"+goal.getKey(),List.copyOf(path));
    }
    return result;
  }
  private InterchangeSignPaths(){}
}
