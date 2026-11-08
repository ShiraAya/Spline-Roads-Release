package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadPlanner.*;

/** Minimal horizontal extension. Fixed mouths retain their position, elevation and slope. */
public final class RoadTunnelFit {
  public enum Adjustment {
    OFF("关闭"),BOTH("两端"),START("仅起点"),END("仅终点");
    public final String label;Adjustment(String label){this.label=label;}
  }
  public static boolean enabled(Settings s){return (s.structure()==Structure.TUNNEL||s.structure()==Structure.BRIDGE)&&s.options().infrastructure().adjustment()!=Adjustment.OFF;}
  public static Plan plan(Hint a,Hint b,Settings s){
    IllegalArgumentException failure;
    try{return RoadPlanner.plan(a,b,s);}catch(IllegalArgumentException e){failure=e;}
    if(!enabled(s))throw failure;
    double length=b.node().position().sub(a.node().position()).horizontalLength();
    double limit=RoadLimits.MAX_ENDPOINT_DISTANCE-length,low=0,high=8;
    Plan best=null;
    for(int i=0;i<24&&limit>0;i++){
      high=Math.min(limit,high);
      try{best=extended(a,b,s,high);break;}catch(IllegalArgumentException ignored){low=high;}
      if(high>=limit)break;high=Math.min(limit,Math.ceil(high*1.4+8));
    }
    if(best==null)throw new IllegalArgumentException("自动延长后仍不能满足桥隧高差；请检查端点方向、坡度或分段建造");
    for(int i=0;i<10&&high-low>1;i++){
      double mid=Math.floor((low+high)/2);
      try{var candidate=extended(a,b,s,mid);best=candidate;high=mid;}catch(IllegalArgumentException ignored){low=mid;}
    }
    return best;
  }
  private static Plan extended(Hint a,Hint b,Settings s,double extension){
    V axis=b.node().position().sub(a.node().position()).horizontalUnit();
    var mode=s.options().infrastructure().adjustment();
    double start=mode==Adjustment.END?0:mode==Adjustment.START?extension:extension/2;
    double end=extension-start;
    Plan p=RoadPlanner.plan(move(a,axis.mul(-start)),move(b,axis.mul(end)),s);
    return new Plan(p.start(),p.end(),p.settings(),p.mesh(),String.format(java.util.Locale.ROOT,"桥隧端点调整：起点 %.1f / 终点 %.1f 格",start,end));
  }
  private static Hint move(Hint h,V delta){
    Node n=h.node();return new Hint(new Node(n.position().add(delta),n.yaw(),n.grade()),h.headingLocked(),h.gradeLocked(),h.linked());
  }
  private RoadTunnelFit(){}
}
