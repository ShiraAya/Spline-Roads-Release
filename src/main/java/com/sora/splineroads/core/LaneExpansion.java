package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
/** Authored outer-lane expansion shares the stable addition slots used by connectors. */
public final class LaneExpansion {
  public static LaneAdditions.Addition addition(Mesh mesh,LanePoints.Point point,int slot){
    var raw=LaneSections.reference(mesh);var lane=LanePoints.lane(raw,point);double length=-point.mergeLength();
    if(point.automatic()||length<8||length>256||!LanePoints.supported(raw.settings()))throw new IllegalArgumentException("请选择手动外侧车道点设置扩流");
    // Ignore only our own previous expansion while validating an edit/reload.
    var md=raw.settings().options().lanePoints();var settings=raw.settings().options(raw.settings().options().lanePoints(md.additions(md.additions().stream().filter(a->!a.connection().equals(point.id())).toList())));
    var clean=new Mesh(raw.samples(),settings,raw.min(),raw.max(),raw.length(),raw.closed());
    if(!LaneSections.edge(clean,lane.station(),point.lane(),md.cuts(),point.id()))throw new IllegalArgumentException("扩流须选择行驶方向最外侧车道");
    var at=RoadStructures.sample(clean,lane.station());var layout=RoadProfile.layout(clean,at);int side=LaneAdditions.side(layout,lane.sign());
    for(var other:LaneSections.live(clean,lane.station()).lanes())if(other.sign()==lane.sign()&&other.index()!=point.lane()&&other.position().sub(lane.position()).dot(at.left())*side>1e-6)
      throw new IllegalArgumentException("扩流请选择行驶方向最外侧车道");
    double end=lane.station()+lane.sign()*length;
    if(end<0||end>raw.length())throw new IllegalArgumentException("下游长度不足以完成扩流渐变");
    if(LaneSections.live(clean,end).count(lane.sign())>=4)throw new IllegalArgumentException("该方向已达四车道，不能再扩流");
    return new LaneAdditions.Addition(point.id(),slot,lane.sign(),end,length);
  }
  public static boolean allowed(Mesh mesh,LanePoints.Point point){try{addition(mesh,point.merge(-32),8);return true;}catch(IllegalArgumentException e){return false;}}
  private LaneExpansion(){}
}
