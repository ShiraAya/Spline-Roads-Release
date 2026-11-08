package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
/** Authored local lane drop anchored to a manual lane point, not a fake ramp link.
 * Existing DEPART/REPLACE geometry supplies the taper and reversible empty slot. */
public final class LaneMerge {
  public static LaneSections.Event event(Mesh mesh,LanePoints.Point point){
    var raw=LaneSections.reference(mesh);var lane=LanePoints.lane(raw,point);var c=RoadProfile.catalog(raw.settings().style());
    if(point.automatic()||point.mergeLength()<8||!LanePoints.supported(raw.settings()))throw new IllegalArgumentException("请选择独立道路上的手动外侧车道点");
    if(!LaneSections.edge(raw,lane.station(),point.lane(),mesh.settings().options().lanePoints().cuts(),point.id()))
      throw new IllegalArgumentException("合流缩减仅支持当前位置的最外侧车道，不能挖走内部车道");
    int neighbor=LaneSections.receiver(mesh,lane.station(),point.lane(),point.id());
    double remaining=lane.sign()>0?raw.length()-lane.station():lane.station();
    if(remaining<point.mergeLength()+2)throw new IllegalArgumentException("下游长度不足以完成合流渐变，请前移此车道点");
    for(double d=0;d<=point.mergeLength();d+=.5){double at=lane.station()+lane.sign()*d;
      if(!LaneSections.active(mesh,at,neighbor))throw new IllegalArgumentException("同向接收车道在合流区已封闭，不能把车流导入空位");
    }
    return new LaneSections.Event(point.id(),LaneSections.Kind.DEPART,point.lane(),lane.sign(),lane.station(),point.mergeLength());
  }
  public static boolean allowed(Mesh mesh,LanePoints.Point point){try{event(mesh,point.merge(32));return true;}catch(IllegalArgumentException e){return false;}}
  public static boolean sameDefinitions(LanePoints.Data a,LanePoints.Data b){
    return a.points().stream().filter(p->p.mergeLength()!=0).toList().equals(b.points().stream().filter(p->p.mergeLength()!=0).toList());
  }
  public static boolean linkedTo(Mesh host,Mesh candidate){
    var l=candidate.settings().options().lanePoints().link();if(l==null)return false;
    for(var point:host.settings().options().lanePoints().points())if(point.id().equals(l.from().point())||point.id().equals(l.to().point()))return true;
    return false;
  }
  public static boolean merging(Mesh mesh,double station,int slot){
    for(var p:mesh.settings().options().lanePoints().points())if(p.mergeLength()>0&&p.lane()==slot){
      var at=LanePoints.lane(reference(mesh),p);double d=at.sign()*(station-at.station());
      if(d>=-8&&d<=p.mergeLength())return true;
    }return false;
  }
  private static Mesh reference(Mesh m){return LaneSections.reference(m);}
  /** One inward guide before the narrowing lane disappears; never points across a median. */
  public static List<RoadJunction.Paint> guides(Mesh mesh){
    var out=new ArrayList<RoadJunction.Paint>();var raw=reference(mesh);
    for(var point:mesh.settings().options().lanePoints().points())if(point.mergeLength()>0){
      var lane=LanePoints.lane(raw,point);double station=lane.station()+lane.sign()*Math.min(4,point.mergeLength()*.12);
      int receiver=LaneSections.receiver(mesh,lane.station(),point.lane(),point.id());
      var at=LanePoints.lane(raw,station,point.lane());V lateral=at.direction().left();
      double side=Math.signum(LanePoints.lane(raw,station,receiver).position().sub(at.position()).dot(lateral));
      var group=new ArrayList<RoadJunction.Paint>();boolean fits=true;
      for(var poly:RoadMergeArrow.local(at.width(),side)){var vs=new ArrayList<V>();for(var q:poly.points()){
        var frame=LanePoints.lane(raw,station+lane.sign()*q.x(),point.lane());V v=frame.position().add(frame.direction().left().mul(q.z()));
        if(!RoadQueries.contains(mesh,v,-.1,.1))fits=false;vs.add(v);
      }group.add(new RoadJunction.Paint(List.copyOf(vs),poly.color()));}
      if(fits)out.addAll(group);
    }return List.copyOf(out);
  }
  private LaneMerge(){}
}
