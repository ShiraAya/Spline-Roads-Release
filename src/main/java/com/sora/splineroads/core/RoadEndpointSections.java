package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadProfile.*;
import java.util.*;

/** Snapshots an actual port once. Persistent slot IDs stay on the host; a continuation gets
 * a fresh lane configuration and exact lateral geometry, never the host's removal metadata. */
public final class RoadEndpointSections {
  public static boolean changed(Mesh mesh,boolean first){
    double station=first?0:mesh.length();
    return mesh.settings().options().lanePoints().additions().stream().anyMatch(a->a.fraction(station)>1e-6)||mesh.settings().options().lanePoints().cuts().stream()
        .anyMatch(c->!c.temporary()&&c.removed(station)>1e-6);
  }
  public static Settings section(Mesh mesh,boolean first,boolean reversed){
    return sectionAt(mesh,first?mesh.first():mesh.last(),reversed);
  }
  public static Settings sectionAt(Mesh mesh,Sample at,boolean reversed){
    var base=mesh.settings();var l=RoadProfile.layout(mesh,at);var c=l.catalog();
    if(c.type()!=Type.ORDINARY&&c.type()!=Type.HIGHWAY)throw new IllegalArgumentException("此接点不是普通道路或高速的通行断面");
    int low=c.twoWay()?l.lanesOnSide(-1):c.lanes(),high=c.twoWay()?l.lanesOnSide(1):0;
    var counts=c.twoWay()?new RoadLanes.Counts(l.outside()>0?high:low,l.outside()>0?low:high):new RoadLanes.Counts(low,0);
    if(reversed)counts=counts.mirrored();
    if(c.twoWay()&&(low<1||high<1))throw new IllegalArgumentException("该端点有一个方向已无开放通行车道，不能作为普通双向续接端口");
    for(var cut:base.options().lanePoints().cuts())if(!cut.temporary()){
      double r=cut.removed(at.distance());if(r>1e-6&&r<1-1e-6)throw new IllegalArgumentException("接点仍在车道合并渐变段中，请将合并过渡留在端点前完成");
    }
    for(var added:base.options().lanePoints().additions()){
      double f=added.fraction(at.distance());if(f>1e-6&&f<1-1e-6)throw new IllegalArgumentException("接点仍在新增车道渐变段中，请在完整断面处续接");
    }
    double sign=reversed?-1:1;
    List<Double> dividers=l.dividers().stream().filter(d->d>l.motorMin()+.12&&d<l.motorMax()-.12)
        .map(d->sign*d).sorted().distinct().toList();
    var port=new RoadTransitions.Port(reversed?-l.motorMax():l.motorMin(),reversed?-l.motorMin():l.motorMax(),l.median(),dividers,l.curbWidth(),l.curbWidth(),sign*l.medianCenter());
    var options=base.options().lanePoints(LanePoints.Data.EMPTY).attachments(RoadAttachments.Data.EMPTY)
        .laneLines(List.of()).streetscape(base.options().streetscape().raisedSpans(List.of())).lanes(counts).ends(RoadTransitions.Ends.NONE.port(port))
        .route(base.options().routing().fit(false));
    var style=RoadLanes.carrier(c.type(),counts,c.median());
    var result=new Settings(Mode.STRAIGHT,style,at.halfWidth()*2,base.thickness(),base.tension(),base.arcDegrees()).structure(base.structure()).options(options);
    result.validate();return result;
  }
  public static Settings orient(Settings s,boolean reverse){
    if(!reverse)return s;
    var o=s.options();var n=RoadLanes.counts(s);var p=o.ends().port();
    if(n.twoWay()&&o.lanes().explicit())o=o.lanes(n.mirrored());
    o=o.outerRail(switch(o.outerRail()){
      case SOUND_LEFT->RoadProfile.OuterRail.SOUND_RIGHT;
      case SOUND_RIGHT->RoadProfile.OuterRail.SOUND_LEFT;
      default->o.outerRail();
    });
    var walk=o.sidewalk();
    var side=switch(walk.side()){case LEFT->RoadSidewalks.Side.RIGHT;case RIGHT->RoadSidewalks.Side.LEFT;default->walk.side();};
    o=o.sidewalk(new RoadSidewalks.Config(walk.enabled(),side,walk.width(),walk.material(),walk.smooth(),walk.tactile()));
    if(p!=null)o=o.ends(o.ends().port(new RoadTransitions.Port(-p.motorMax(),-p.motorMin(),p.median(),p.dividers().stream().map(d->-d).sorted().toList(),p.curbRight(),p.curbLeft(),-p.medianCenter())));
    return s.options(o);
  }
  /** Copy the complete current port for a new road without copying ownership/cuts/point IDs. */
  public static Settings inherit(Settings requested,Settings port){
    return new Settings(requested.mode(),port.style(),port.width(),requested.thickness(),requested.tension(),requested.arcDegrees())
        .structure(requested.structure()).options(port.options().infrastructure(requested.options().infrastructure())
        .lanePoints(LanePoints.Data.EMPTY).attachments(RoadAttachments.Data.EMPTY).lift(.5,0));
  }
  private RoadEndpointSections(){}
}
