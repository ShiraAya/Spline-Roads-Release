package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Central elevated terminals, with independently reserved driving directions. */
public final class ElevatedAccess {
  public record Direction(int sign,List<Integer> slots,boolean auxiliary){
    public Direction{slots=List.copyOf(slots);}
  }
  public record Gap(double from,double to,double low,double high){
    public Gap{if(!RoadGeometry.finite(from,to,low,high)||from>=to||low>=high)throw new IllegalArgumentException("高架出入口铺面开口无效");}
    public boolean at(double station){return station>from+1e-8&&station<to-1e-8;}
  }
  /** The actual connector UUID owns these derived reservations across reloads. */
  public record Reservation(UUID connection,UUID point,List<LaneSections.Cut> cuts,List<LaneAdditions.Addition> additions,List<Gap> gaps){
    public Reservation{Objects.requireNonNull(connection);Objects.requireNonNull(point);cuts=List.copyOf(cuts);additions=List.copyOf(additions);gaps=List.copyOf(gaps);
      if(cuts.size()>14||additions.size()>2||gaps.size()>3||cuts.stream().anyMatch(c->!c.connection().equals(connection))||additions.stream().anyMatch(a->!a.connection().equals(connection)))throw new IllegalArgumentException("高架出入口车道预留无效");}
  }
  /** Rebase local distances while keeping the same world-space connector and point. */
  public static Reservation shifted(Reservation r,double offset){
    var cuts=r.cuts().stream().map(c->new LaneSections.Cut(c.connection(),c.lane(),c.sign(),c.begin()+offset,c.end()+offset,
        c.transition(),c.replacement(),c.temporary(),c.arrival(),c.rectangular(),c.underpass())).toList();
    var adds=r.additions().stream().map(a->new LaneAdditions.Addition(a.connection(),a.slot(),a.sign(),a.station()+offset,a.transition(),a.end()+offset)).toList();
    var gaps=r.gaps().stream().map(g->new Gap(g.from()+offset,g.to()+offset,g.low(),g.high())).toList();
    return new Reservation(r.connection(),r.point(),cuts,adds,gaps);
  }
  public record Plan(Mesh road,Reservation reservation,List<Direction> directions){public Plan{directions=List.copyOf(directions);}}

  public static List<Direction> directions(Mesh host,double station,int forward,int reverse){
    if(forward<0||reverse<0||forward+reverse==0)throw new IllegalArgumentException("高架至少需要一个行驶方向");
    var layout=RoadProfile.layout(host,RoadStructures.sample(host,station));var out=new ArrayList<Direction>();
    for(int sign:new int[]{1,-1}){
      int count=sign>0?forward:reverse;var slots=new ArrayList<Integer>();
      for(int slot=0;slot<layout.catalog().lanes();slot++)if(LanePoints.lane(host,station,slot).sign()==sign)slots.add(slot);
      // Lane IDs on two-way roads already run from the median outward. For a
      // one-way road the inner edge depends on left/right driving.
      if(!layout.catalog().twoWay()&&layout.outside()<0)Collections.reverse(slots);
      if(count>slots.size())throw new IllegalArgumentException("高架该方向车道数超过下方道路，不能生成");
      if(count>0)out.add(new Direction(sign,slots.subList(0,count),count==slots.size()));
    }return List.copyOf(out);
  }

  public static Plan plan(UUID id,UUID point,Mesh host,double station,Node endpoint,Settings requested,boolean pointFirst){
    var at=RoadStructures.sample(host,station);var hostLayout=RoadProfile.layout(host,at);
    if(endpoint.position().y()-at.center().y()<RoadClearance.REQUIRED+requested.thickness()-.001)
      throw new IllegalArgumentException("高架端点高度不足以满足下方道路净空");
    V towards=endpoint.position().sub(at.center()).horizontalUnit(),tangent=at.left().left().mul(-1);
    int axis=!hostLayout.catalog().twoWay()?(pointFirst?1:-1):towards.dot(tangent)<0?-1:1;
    var counts=RoadLanes.counts(requested);
    int flowAxis=pointFirst?axis:-axis;
    int forward=flowAxis>0?counts.forward():counts.reverse(),reverse=flowAxis>0?counts.reverse():counts.forward();
    var selected=directions(host,station,forward,reverse);
    double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY;
    for(var direction:selected)for(int slot:direction.slots()){
      var lane=LanePoints.lane(host,station,slot);double offset=lane.position().sub(at.center()).dot(at.left());
      low=Math.min(low,offset-lane.width()/2);high=Math.max(high,offset+lane.width()/2);
    }
    double center=(low+high)/2,drive=high-low;
    V start=at.at(center,0),initial=tangent.mul(axis);
    V finalDirection=endpoint.direction().horizontalUnit();
    if(finalDirection.dot(towards)<0)finalDirection=finalDirection.mul(-1);
    var options=requested.options().attachments(RoadAttachments.Data.EMPTY).lanePoints(LanePoints.Data.EMPTY).lanes(counts)
        .ends(RoadTransitions.Ends.NONE).extras(false,false,false).sidewalk(RoadSidewalks.Config.DEFAULT.enabled(false))
        .streetscape(requested.options().streetscape().raisedSpans(List.of()).tunnels(List.of()).crossings(List.of()));
    var base=new Settings(Mode.CURVE,requested.style(),requested.width(),requested.thickness(),.35,90)
        .structure(Structure.AUTO).options(options);
    double median=selected.size()>1?hostLayout.median():0;
    double localMedian=axis*(hostLayout.medianCenter()-center);
    var dividers=new ArrayList<Double>();
    for(var direction:selected)for(int i=1;i<direction.slots().size();i++){
      var lane=LanePoints.lane(host,station,direction.slots().get(i));
      double local=axis*(lane.position().sub(at.center()).dot(at.left())-center);
      int side=local<localMedian?-1:1;dividers.add(local-side*lane.width()/2);
    }
    Collections.sort(dividers);
    // The start has exactly the selected lane boundaries, with no shoulder
    // extending into the neighboring through lane.
    var mouthStyle=RoadLanes.carrier(hostLayout.catalog().type(),pointFirst?counts:counts.mirrored(),hostLayout.catalog().median());
    var section=new RoadTransitions.Section(mouthStyle,drive,false,false,false,RoadProfile.OuterRail.ON)
        .port(new RoadTransitions.Port(-drive/2,drive/2,median,dividers,0,0,selected.size()>1?localMedian:0));
    section=new RoadTransitions.Section(section.style(),section.width(),false,false,false,section.outerRail(),section.sidewalk(),false,section.port(),options.streetscape(),pointFirst?counts:counts.mirrored());
    Settings pathSettings=pointFirst?base:base.options(base.options().lanes(counts.mirrored()));
    pathSettings=RoadTransitions.ends(pathSettings,section,RoadTransitions.Section.of(RoadEndpointSections.orient(requested,!pointFirst)));
    double step=Math.min(.5,host.length()/20);var before=RoadStructures.sample(host,Math.max(0,station-step));var after=RoadStructures.sample(host,Math.min(host.length(),station+step));
    double grade=(after.center().y()-before.center().y())/Math.max(.001,after.center().sub(before.center()).horizontalLength())*axis;
    var a=new LaneRampPaths.Port(start,initial,initial.left(),drive,grade);
    var b=new LaneRampPaths.Port(endpoint.position(),finalDirection,finalDirection.left(),base.width(),endpoint.grade()*(endpoint.direction().dot(finalDirection)<0?-1:1));
    var routeOptions=new LanePoints.Options(LanePoints.Path.AUTO,LanePoints.Departure.TEMPORARY,LanePoints.Arrival.MERGE,12,24,LanePoints.Elevation.OVER,LanePoints.Landing.EXACT);
    var candidates=LaneRampPaths.smoothTurns(a,b,pathSettings,routeOptions,.20,false);
    candidates=new ArrayList<>(candidates);candidates.sort(Comparator.comparingDouble(c->c.mesh().length()));
    String error="没有满足坡度、转弯半径和下方通行净空的高架连接";
    for(var candidate:candidates)try{
      var path=holdMouth(candidate.mesh(),host,center,axis);var cuts=new ArrayList<LaneSections.Cut>();var additions=new ArrayList<LaneAdditions.Addition>();var gaps=new ArrayList<Gap>();
      int slot=LaneAdditions.FIRST_NEW_SLOT;var used=new HashSet<Integer>();for(var addition:host.settings().options().lanePoints().additions())used.add(addition.slot());
      double medianFrom=station,medianTo=station;
      for(var direction:selected){double begin=station,end=station;
        boolean departure=direction.sign()==axis;
        for(int lane:direction.slots()){
          double other=departure?LaneReopening.restoreStation(host,lane,station,path,List.of(),24,true):LaneReopening.closeBeforeStation(host,lane,station,reverse(path,RoadEndpointSections.orient(path.settings(),true)),List.of(),24,true);
          if(departure)end=direction.sign()>0?Math.max(end,other):Math.min(end,other);else begin=direction.sign()>0?Math.min(begin,other):Math.max(begin,other);
        }
        double lo=Math.min(begin,end),hi=Math.max(begin,end);medianFrom=Math.min(medianFrom,lo);medianTo=Math.max(medianTo,hi);
        for(int lane:direction.slots())cuts.add(new LaneSections.Cut(id,lane,direction.sign(),begin,end,24,null,true,!departure,true));
        if(direction.auxiliary()){
          while(used.contains(slot))slot++;used.add(slot);
          // Drivers need a full-width lane to change into before the warning Xs
          // begin (24 m upstream), and an open receiving lane beyond the closure.
          double passing=36, taper=Math.max(32,8*hostLayout.laneWidth());
          double from=lo-passing,to=hi+passing;
          if(from<taper||to+taper>host.length())throw new IllegalArgumentException("主路长度不足以容纳高架辅路的渐变和换道区");
          additions.add(new LaneAdditions.Addition(id,slot++,direction.sign(),
              direction.sign()>0?from:to,taper,direction.sign()>0?to:from));
        }
      }
      if(selected.size()>1&&median>0&&medianTo>medianFrom)gaps.add(new Gap(medianFrom,medianTo,hostLayout.medianCenter()-median/2,hostLayout.medianCenter()+median/2));
      var reservation=new Reservation(id,point,cuts,additions,gaps);
      var md=host.settings().options().lanePoints();var allCuts=new ArrayList<>(md.cuts());allCuts.addAll(cuts);var allAdditions=new ArrayList<>(md.additions());allAdditions.addAll(additions);var allAccess=new ArrayList<>(md.access());allAccess.add(reservation);
      var effective=LaneSections.apply(RoadRibbon.mesh(host.samples(),host.settings().options(host.settings().options().lanePoints(md.access(allAccess).cuts(allCuts).additions(allAdditions)))));
      RoadClearance.check(path,effective,null);
      return new Plan(pointFirst?path:reverse(path,base),reservation,selected);
    }catch(IllegalArgumentException failure){error=failure.getMessage();}
    throw new IllegalArgumentException(error);
  }
  private static Mesh holdMouth(Mesh path,Mesh host,double offset,int axis){
    double hold=0;
    for(var sample:path.samples()){
      var lower=RoadQueries.horizontal(host,sample.center()).sample();
      if(sample.center().y()-path.settings().thickness()-lower.center().y()<RoadClearance.REQUIRED+.25)hold=sample.distance();
    }
    if(hold>=path.length()-.5)throw new IllegalArgumentException("高架端点净空不足，无法完成道路断面过渡");
    // An asymmetric host's median is not its centerline. Follow the selected
    // slots until clear before moving laterally toward the upper endpoint.
    double blend=Math.min(48,(path.length()-hold)*.75);var centers=new ArrayList<V>();int held=0;
    for(int i=0;i<path.samples().size();i++){var sample=path.samples().get(i);var q=RoadQueries.horizontal(host,sample.center()).sample();V aligned=q.at(offset,0);
      double t=Settings.smooth(Math.max(0,Math.min(1,(sample.distance()-hold)/blend)));var p=aligned.mul(1-t).add(sample.center().mul(t));centers.add(new V(p.x(),sample.center().y(),p.z()));if(sample.distance()<=hold)held=i;}
    var points=new ArrayList<Sample>();for(int i=0;i<centers.size();i++){
      V left=i==0?RoadQueries.horizontal(host,centers.get(i)).sample().left().mul(axis):i==centers.size()-1?path.last().left():centers.get(i+1).sub(centers.get(i-1)).horizontalUnit().left();
      points.add(new Sample(centers.get(i),left,0,path.samples().get(i).halfWidth()));
    }
    var aligned=RoadRibbon.mesh(points,path.settings());hold=aligned.samples().get(held).distance();
    var settings=path.settings();settings=settings.options(settings.options().ends(settings.options().ends().hold(hold,0)));
    points.clear();for(var sample:aligned.samples())points.add(new Sample(sample.center(),sample.left(),sample.distance(),RoadTransitions.width(settings,sample.distance(),aligned.length())/2));
    var result=RoadRibbon.mesh(points,settings);LaneRampGrade.validate(result,.20);RoadRibbon.checkSelfIntersections(result,4);return result;
  }
  private static Mesh reverse(Mesh path,Settings base){
    var samples=new ArrayList<Sample>();for(int i=path.samples().size()-1;i>=0;i--){var s=path.samples().get(i);samples.add(new Sample(s.center(),s.left().mul(-1),0,s.halfWidth()));}
    // Reflection includes the exact port, not only the centerline.
    var e=path.settings().options().ends();var start=reflect(e.end(),base.options().leftTraffic());var end=reflect(e.start(),base.options().leftTraffic());
    var settings=RoadTransitions.ends(base,start,end);settings=settings.options(settings.options().ends(settings.options().ends().hold(e.endHold(),e.startHold())));
    return RoadRibbon.mesh(samples,settings);
  }
  private static RoadTransitions.Section reflect(RoadTransitions.Section section,boolean left){return section==null?null:RoadTransitions.Section.of(RoadEndpointSections.orient(section.settings(left),true));}
  private ElevatedAccess(){}
}
