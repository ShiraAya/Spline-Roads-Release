package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** A normal fork shares its parent's deck height until the paved ribbons separate.
 * Only contiguous, forward-facing end contacts count; a later crossing is untouched. */
public final class LaneRampThroat {
  public static int end(Mesh ramp,List<Mesh> hosts,boolean first){
    int n=ramp.samples().size(),i=first?0:n-1,last=i;
    for(;i>=0&&i<n;i+=first?1:-1){
      if(height(ramp.samples().get(i),hosts)==null)break;last=i;
    }
    return last;
  }
  private static Double height(Sample at,List<Mesh> hosts){
    Double height=null;
    for(var host:hosts){
      var q=RoadQueries.horizontal(host,at.center());var h=q.sample();
      V forward=h.left().left().mul(-1);double along=at.center().sub(h.center()).dot(forward);
      if((q.sample().distance()<1e-5&&along<-.02)||(q.sample().distance()>host.length()-1e-5&&along>.02))continue;
      if(Math.abs(h.left().dot(at.left()))<.55||q.horizontalDistance()>=h.halfWidth()+at.halfWidth()-.02)continue;
      if(!LaneDeck.present(host,h,q.lateral(),at.halfWidth()))continue;
      if(height!=null&&Math.abs(height-h.center().y())>.025)throw new IllegalArgumentException("普通分流的共用路面高程不一致，请先更新原分流接头");
      height=h.center().y();
    }
    return height;
  }
  public static Mesh fit(Mesh ramp,List<Mesh> hosts,boolean first){
    if(hosts.isEmpty())return ramp;
    int end=end(ramp,hosts,first);var boundary=ramp.samples().get(end);
    double remaining=first?ramp.length()-boundary.distance():boundary.distance();
    if(remaining<.01){
      for(var at:ramp.samples()){var target=height(at,hosts);
        if(target!=null&&Math.abs(target-at.center().y())>.025)throw new IllegalArgumentException("普通分流尚未离开共用路面，不能改变高程");}
      return ramp;
    }
    var y=height(boundary,hosts);if(y==null)return ramp;
    double delta=y-boundary.center().y(),ease=Math.min(remaining,Math.max(16,Math.min(96,remaining*.5)));
    var out=new ArrayList<Sample>();
    for(int i=0;i<ramp.samples().size();i++){
      var at=ramp.samples().get(i);double dy;
      if(first?i<=end:i>=end){var target=height(at,hosts);dy=target==null?0:target-at.center().y();}
      else {double distance=first?at.distance()-boundary.distance():boundary.distance()-at.distance();dy=delta*(1-Settings.smooth(Math.min(1,distance/ease)));}
      out.add(new Sample(at.center().add(new V(0,dy,0)),at.left(),at.distance(),at.halfWidth()));
    }
    return RoadRibbon.mesh(out,ramp.settings());
  }
  private LaneRampThroat(){}
}
