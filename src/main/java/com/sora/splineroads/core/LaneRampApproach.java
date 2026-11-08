package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Full-width auxiliary lane follows its host: a taper and a genuinely parallel running lane. */
public final class LaneRampApproach {
  public record Station(Mesh mesh,int lane,double distance){}
  /** Stations follow the driving direction across validated continuous hosts. */
  public static List<Sample> build(java.util.function.DoubleFunction<Station> stations,boolean source,Settings ramp,double transition,boolean tunnel){
    double taper=tunnel?Math.max(64,transition*2):taper(transition);
    return build(stations,source,ramp.width(),RoadProfile.layout(ramp,ramp.width()).motorCenter(),taper,taper+parallel(transition),tunnel);
  }
  public static double taper(double transition){return Math.max(40,transition*1.5);}
  // Transition controls the taper, not a second equally long locked straight.
  // Keep a genuine full-width parallel run without consuming all of a short free span.
  public static double parallel(double transition){return Math.max(16,transition/2);}
  public static double length(double transition){return taper(transition)+parallel(transition);}
  public static double taper(Mesh host,double transition){return host.settings().structure()==Structure.TUNNEL?Math.max(64,transition*2):taper(transition);}
  public static double length(Mesh host,double transition){return taper(host,transition)+parallel(transition);}
  /** EXTRA uses the chosen carriageway's outside lane. The clicked point still
   * determines its station and driving direction; it is not an at-grade path
   * across every through lane between an interior click and the road verge. */
  public static LanePoints.Lane outerLane(Mesh host,double station,int index){
    var selected=LanePoints.lane(host,station,index);var at=RoadStructures.sample(host,station);var layout=RoadProfile.layout(host,at);
    int side=layout.catalog().twoWay()?(selected.position().sub(at.center()).dot(at.left())<layout.medianCenter()?-1:1):layout.outside();
    // The rendered count is not a slot range: added lanes keep IDs 16..31
    // (legacy 8..15), while removed authored slots retain their identities.
    // Search actual open slots instead of inventing index 6 on a 3+3 road
    // with one added lane, or selecting a closed outer lane.
    LanePoints.Lane best=null;double far=Double.NEGATIVE_INFINITY;
    for(int i:LaneAdditions.slots(host,station)){
      if(!LaneSections.active(host,station,i))continue;
      var lane=LanePoints.lane(host,station,i);double lateral=side*lane.position().sub(at.center()).dot(at.left());
      if(lane.sign()==selected.sign()&&lateral>far){best=lane;far=lateral;}
    }
    if(best==null)throw new IllegalArgumentException("当前位置所选方向没有开放的外侧车道");
    return best;
  }

  public static List<Sample> build(Mesh host,int lane,double station,boolean source,double width,double transition){
    return build(host,lane,station,source,width,transition,0,host.settings().structure()==Structure.TUNNEL);
  }
  public static List<Sample> build(Mesh host,int lane,double station,boolean source,Settings ramp,double transition){
    return build(host,lane,station,source,ramp,transition,host.settings().structure()==Structure.TUNNEL);
  }
  public static List<Sample> build(Mesh host,int lane,double station,boolean source,Settings ramp,double transition,boolean tunnel){
    return build(host,lane,station,source,ramp.width(),transition,RoadProfile.layout(ramp,ramp.width()).motorCenter(),tunnel);
  }
  private static List<Sample> build(Mesh host,int lane,double station,boolean source,double width,double transition,double motorCenter,boolean tunnel){
    var selected=outerLane(host,station,lane);lane=selected.index();int sign=selected.sign();double taper=tunnel?Math.max(64,transition*2):taper(transition),length=taper+parallel(transition);
    double from=source?station:station-sign*length,to=source?station+sign*length:station;
    if(Math.min(from,to)<-1e-6||Math.max(from,to)>host.length()+1e-6)
      throw new IllegalArgumentException((source?"汇出":"汇入")+"额外车道需要沿主路 "+(int)length+" 格（渐变段＋并行段），当前位置空间不足");
    final int slot=lane;
    return build(d->new Station(host,slot,from+sign*d),source,width,motorCenter,taper,length,tunnel);
  }
  private static List<Sample> build(java.util.function.DoubleFunction<Station> stations,boolean source,double width,double motorCenter,double taper,double length,boolean tunnel){
    var out=new ArrayList<Sample>();int n=(int)Math.ceil(length/.5);double distance=0;
    for(int i=0;i<=n;i++){
      double d=length*i/n;var point=stations.apply(d);var host=point.mesh();double at=point.distance();
      var s=RoadStructures.sample(host,at);var l=outerLane(host,at,point.lane());
      var layout=RoadProfile.layout(host,s);double lateral=l.position().sub(s.center()).dot(s.left());
      int side=layout.catalog().twoWay()?(lateral<layout.medianCenter()?-1:1):layout.outside();
      // The host verge seals the pavement seam; leave a small positive margin
      // to its motor band so the free curve cannot invade a through lane.
      // An auxiliary MOTOR lane starts at the motor edge. Sidewalks/cycle lanes
      // and their separators are not another motor lane to branch around.
      // Highway emergency shoulders remain usable. Their parallel auxiliary
      // lane starts outside the paved verge, rather than reusing that shoulder.
      boolean motorEdge=layout.cycleWidth()>.01;
      double edge=motorEdge?side*layout.outer(side)+.02:s.halfWidth()-.02;
      // Reserve the actual main lining between parallel tunnel carriageways.
      // Without this gap the auxiliary shoulder erased the entire divider.
      if(tunnel)edge=Math.max(edge,s.halfWidth()+RoadAutoTunnels.LINING+.05);
      double offset=edge+width/2-side*lateral+side*motorCenter;
      double grow=Settings.smooth(Math.min(1,(source?d:length-d)/taper));
      V position=l.position().add(s.left().mul(side*offset*grow));
      if(i>0)distance+=position.sub(out.get(i-1).center()).horizontalLength();
      // The exposed OUTSIDE rail needs its full .75 m shoulder from the
      // beginning. Only the inside shoulder grows while still sharing the host.
      double narrow=Math.min(width,l.width()+.75);
      double halfWidth=(narrow+(width-narrow)*grow)/2;
      out.add(new Sample(position,l.direction().left(),distance,halfWidth));
    }
    // Tangents of the actual curved/tapered lane, with exact host headings at either join.
    var result=new ArrayList<Sample>();
    for(int i=0;i<out.size();i++){
      var s=out.get(i);V direction=i==0||i==out.size()-1?s.left().left().mul(-1):out.get(i+1).center().sub(out.get(i-1).center()).horizontalUnit();
      result.add(new Sample(s.center(),direction.left(),s.distance(),s.halfWidth()));
    }
    return List.copyOf(result);
  }
  private LaneRampApproach(){}
}
