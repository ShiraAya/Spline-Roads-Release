package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadJunction.Paint;
import java.util.*;

/** Guidance for lane-point connectors only. Ordinary/interchange paint is unchanged. */
public final class LaneRampPaint {
  /** General arrows are absent for this road kind. Only EXTRA arrivals need a merge
   * arrow, located on their actual additional lane, before its narrowing taper.
   * The generic hideArrows option describes general arrows, not this required guide. */
  public static List<Paint> arrows(Mesh ramp,List<Mesh> neighbors) {
    if(!ramp.settings().style().connectorRamp())return List.of();
    var link=ramp.settings().options().lanePoints().link();
    if(link==null||!link.options().targetExtra()||link.to().road()==null)return List.of();
    Mesh target=null;LanePoints.Point point=null;
    for(var other:neighbors)for(var p:other.settings().options().lanePoints().points())
      if(p.id().equals(link.to().point())){target=other;point=p;break;}
    if(target==null)return List.of(); // Never guess a nearby parallel/opposing host.
    boolean highway=RoadProfile.highway(target.settings().style());
    var protectedDeck=highway?LaneDeck.motorOnly(target):target;
    double length=LaneRampApproach.length(target,link.options().transition());
    double taper=LaneRampApproach.taper(target,link.options().transition());
    // Approach distances are horizontal sampled lengths, not just authored station;
    // search a bounded upstream window and validate every actual vertex.
    double min=Math.max(4,ramp.length()-length*1.5),max=ramp.length()-4;
    double preferred=Math.max(min,ramp.length()-taper-8);
    var candidates=new LinkedHashSet<Double>();
    candidates.add(preferred);
    for(double d=preferred-1;d>=min;d-=1)candidates.add(d);
    for(double d=preferred+1;d<=max;d+=1)candidates.add(d);
    for(double d:candidates){
      if(highway&&(ramp.length()-d<taper+2||ramp.length()-d>length-2))continue;
      var at=RoadStructures.sample(ramp,d);var q=RoadQueries.horizontal(target,at.center());
      var lane=LaneRampApproach.outerLane(target,q.sample().distance(),point.lane());
      V forward=at.left().left().mul(-1);
      if(forward.dot(lane.direction())<.94||Math.abs(at.center().y()-lane.position().y())>.15)continue;
      double lateral=lane.position().sub(at.center()).dot(at.left());
      if(Math.abs(lateral)<at.halfWidth()+.5)continue;
      double side=Math.signum(lateral);var faces=guide(ramp,d,side);
      boolean fits=!faces.isEmpty();
      for(var face:faces)for(V p:face.points()){
        var here=RoadQueries.horizontal(ramp,p);var layout=RoadProfile.layout(ramp,here.sample());
        if(here.sample().distance()<1||here.sample().distance()>ramp.length()-1
            ||here.lateral()<layout.motorMin()+.18||here.lateral()>layout.motorMax()-.18
            ||!RoadQueries.contains(ramp,p,-.1,.08)||RoadQueries.contains(protectedDeck,p,.1,.15))fits=false;
      }
      if(fits)return faces; // Keep shaft, bend and head as an indivisible silhouette.
    }
    return List.of();
  }

  private static List<Paint> guide(Mesh mesh,double station,double side){
    var out=new ArrayList<Paint>();double width=RoadProfile.layout(mesh,RoadStructures.sample(mesh,station)).laneWidth();
    for(var shape:RoadMergeArrow.local(width,side)){var vertices=new ArrayList<V>();for(var p:shape.points()){
      var at=RoadStructures.sample(mesh,station+p.x());var profile=RoadProfile.layout(mesh,at);
      vertices.add(at.at(profile.motorCenter()+p.z(),0));
    }out.add(new Paint(List.copyOf(vertices),shape.color()));}
    return List.copyOf(out);
  }
  public record Auxiliary(Mesh ramp,int side,double from,double to){}
  public static List<Auxiliary> auxiliary(Mesh host,List<Mesh> neighbors){
    var out=new ArrayList<Auxiliary>();var context=new ArrayList<>(neighbors);if(!context.contains(host))context.add(host);
    for(var ramp:neighbors){
      if(ramp.settings().options().lanePoints().link()==null)continue;
      var interval=RoadRailJoin.auxiliaryInterval(ramp,host,context);if(interval==null)continue;
      double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY;int side=0;
      for(double d=interval[0];d<=interval[1]+.01;d+=.5){
        var sample=RoadStructures.sample(ramp,Math.min(d,interval[1]));var q=RoadQueries.horizontal(host,sample.center());
        low=Math.min(low,q.sample().distance());high=Math.max(high,q.sample().distance());
        if(Math.abs(q.lateral())>.1)side=q.lateral()<0?-1:1;
      }
      if(side!=0&&high>low)out.add(new Auxiliary(ramp,side,low-1,high+1));
    }
    return List.copyOf(out);
  }
  public static boolean auxiliaryAt(Mesh host,Sample a,Sample b,int side,List<Auxiliary> candidates){
    var m=RoadStructures.sample(host,(a.distance()+b.distance())/2);var layout=RoadProfile.layout(host,m);
    double edge=layout.cycleWidth()>.01?side*layout.outer(side):m.halfWidth();
    for(var item:candidates)if(item.side()==side&&m.distance()>=item.from()&&m.distance()<=item.to()
        &&RoadQueries.contains(item.ramp(),m.at(side*(edge+.35),0),0,.06)
        &&RoadQueries.contains(item.ramp(),m.at(side*(edge+.7),0),0,.06))return true;
    return false;
  }
  /** A whole dash needs room on its outside; a paper-thin taper is not a lane. */
  public static boolean auxiliaryGuideAt(Mesh host,Sample a,Sample b,int side,List<Auxiliary> candidates){
    if(!auxiliaryAt(host,a,b,side,candidates))return false;
    for(var item:candidates)if(item.side()==side&&guideFits(host,a,b,side,item.ramp()))return true;
    return false;
  }
  public static boolean guideFits(Mesh host,Sample a,Sample b,int side,Mesh ramp){
    for(var at:List.of(a,RoadStructures.sample(host,(a.distance()+b.distance())/2),b)){
      var layout=RoadProfile.layout(host,at);
      double edge=layout.cycleWidth()>.01||layout.catalog().type()==RoadProfile.Type.HIGHWAY?layout.outer(side):side*at.halfWidth();
      for(double offset:new double[]{.15,1.25})
        if(!RoadQueries.contains(ramp,at.at(edge+side*offset,0),0,.06))return false;
    }
    return true;
  }
  private LaneRampPaint(){}
}
