package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Candidate membership is explicit; only the SR snapshot is clipped at AB cross-sections. */
public final class RailbedCandidates {
  public static String key(RailbedPath.Path path){return path.segments().stream().map(RailbedPath.Segment::id).sorted().toList().toString();}
  public static List<RailbedPath.Path> find(RailbedPath.Path base,List<RailbedPath.Path> nearby){
    var graph=new HashMap<RailbedPath.Node,List<RailbedPath.Segment>>();var segments=new LinkedHashMap<String,RailbedPath.Segment>();
    nearby.forEach(p->p.segments().forEach(s->segments.put(s.id(),s)));segments.values().forEach(s->{graph.computeIfAbsent(s.from(),n->new ArrayList<>()).add(s);graph.computeIfAbsent(s.to(),n->new ArrayList<>()).add(s);});
    var excluded=new HashSet<String>();base.segments().forEach(s->excluded.add(s.id()));var visited=new HashSet<String>();var result=new ArrayList<RailbedPath.Path>();
    for(var seed:segments.values()){
      if(visited.contains(seed.id()))continue;var component=new LinkedHashSet<RailbedPath.Segment>();var todo=new ArrayDeque<RailbedPath.Segment>();todo.add(seed);boolean branch=false;
      while(!todo.isEmpty()){var s=todo.remove();if(!component.add(s))continue;visited.add(s.id());for(var n:List.of(s.from(),s.to())){var links=graph.get(n);branch|=links.size()>2;for(var other:links)if(!component.contains(other))todo.add(other);}}
      if(branch||component.stream().anyMatch(s->excluded.contains(s.id())))continue;
      var start=component.stream().flatMap(s->java.util.stream.Stream.of(s.from(),s.to())).filter(n->graph.get(n).size()==1).min(Comparator.naturalOrder()).orElse(null);if(start==null)continue;
      var chain=new ArrayList<RailbedPath.Segment>();var used=new HashSet<String>();var at=start;
      while(true){RailbedPath.Segment next=null;for(var s:graph.get(at))if(!used.contains(s.id())){next=s;break;}if(next==null)break;used.add(next.id());if(!next.from().equals(at))next=reverse(next);chain.add(next);at=next.to();}
      try{var clipped=clip(base,chain);RailbedGeometry.plan(List.of(base,clipped),0,Structure.AUTO);result.add(clipped);}catch(IllegalArgumentException unsupported){/* Not a full, level parallel candidate. Still retained in construction protection. */}
    }
    return List.copyOf(result);
  }
  /** Initial selection includes the adjacent, compatible full-length track group.
   * Keep an eight-metre inter-track gap so a separate line across a road is not captured. */
  public static List<RailbedPath.Path> automatic(RailbedPath.Path base,List<RailbedPath.Path> candidates){
    var selected=new ArrayList<RailbedPath.Path>();selected.add(base);var remaining=new ArrayList<>(candidates);
    while(selected.size()<RailbedGeometry.MAX_TRACKS){
      var next=remaining.stream().filter(p->selected.stream().anyMatch(s->endpointDistance(p,s)<=8.01))
          .sorted(Comparator.comparingDouble((RailbedPath.Path p)->endpointDistance(p,base)).thenComparing(RailbedCandidates::key)).toList();
      boolean added=false;for(var p:next){remaining.remove(p);var proposed=new ArrayList<>(selected);proposed.add(p);
        try{RailbedGeometry.plan(proposed,0,Structure.AUTO);selected.add(p);added=true;break;}catch(IllegalArgumentException unsupported){}}
      if(!added)break;
    }return List.copyOf(selected);
  }
  private static double endpointDistance(RailbedPath.Path a,RailbedPath.Path b){
    var points=a.positions();var origin=b.positions().get(0);
    return Math.min(points.get(0).distance(origin),points.get(points.size()-1).distance(origin));
  }
  private static RailbedPath.Segment reverse(RailbedPath.Segment s){var points=new ArrayList<RailbedPath.Point>();for(int i=s.points().size()-1;i>=0;i--){var p=s.points().get(i);points.add(new RailbedPath.Point(s.length()-p.parameter(),p.position()));}return new RailbedPath.Segment(s.id(),s.to(),s.from(),!s.reverse(),s.length(),s.styles(),points);}
  private record Located(int segment,double parameter,V position,double order){}
  private static Located crossing(List<RailbedPath.Segment> chain,V origin,V tangent){Located hit=null;double order=0;
    for(int s=0;s<chain.size();s++){var points=chain.get(s).points();for(int i=1;i<points.size();i++){
      var a=points.get(i-1);var b=points.get(i);double da=a.position().sub(origin).dot(tangent),db=b.position().sub(origin).dot(tangent);double distance=a.position().distance(b.position());
      if(s==0&&i==1&&Math.abs(da)<=.006)da=0;if(s==chain.size()-1&&i==points.size()-1&&Math.abs(db)<=.006)db=0;
      if(da*db<=1e-8&&Math.abs(da-db)>1e-10){double t=Math.max(0,Math.min(1,da/(da-db)));var p=a.position().mul(1-t).add(b.position().mul(t));if(p.sub(origin).horizontalLength()<64){var next=new Located(s,a.parameter()+(b.parameter()-a.parameter())*t,p,order+distance*t);if(hit!=null&&Math.abs(hit.order-next.order)>1e-5)throw new IllegalArgumentException("邻轨与端部断面多次相交");hit=next;}}order+=distance;
    }}if(hit==null)throw new IllegalArgumentException("邻轨没有覆盖 A–B 区间");return hit;
  }
  public static RailbedPath.Path clip(RailbedPath.Path base,List<RailbedPath.Segment> chain){
    var points=base.positions();var a=crossing(chain,points.get(0),RailbedPath.tangent(points,0));int end=points.size()-1;var b=crossing(chain,points.get(end),RailbedPath.tangent(points,end));
    if(a.order>b.order){var swap=a;a=b;b=swap;}if(b.order-a.order<.01)throw new IllegalArgumentException("邻轨截取范围无效");var output=new ArrayList<RailbedPath.Segment>();
    for(int i=a.segment;i<=b.segment;i++){var source=chain.get(i);var sample=new ArrayList<RailbedPath.Point>();double lo=i==a.segment?a.parameter:source.points().get(0).parameter(),hi=i==b.segment?b.parameter:source.points().get(source.points().size()-1).parameter();
      if(hi-lo<1e-8)continue;if(i==a.segment)sample.add(new RailbedPath.Point(lo,a.position));for(var p:source.points())if(p.parameter()>lo+1e-8&&p.parameter()<hi-1e-8)sample.add(p);if(i!=a.segment)sample.add(0,source.points().get(0));if(i==b.segment)sample.add(new RailbedPath.Point(hi,b.position));else sample.add(source.points().get(source.points().size()-1));
      output.add(new RailbedPath.Segment(source.id(),source.from(),source.to(),source.reverse(),source.length(),source.styles(),sample));
    }return new RailbedPath.Path(output);
  }
  private RailbedCandidates(){}
}
