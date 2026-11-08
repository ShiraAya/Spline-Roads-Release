package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadProfile.*;

/** Authored lane configuration. Counts use reference A-to-B / B-to-A, not fixed lane slots. */
public final class RoadLanes {
  public static final int MAX_DIRECTION = 7;
  public record Counts(int forward, int reverse) {
    public static final Counts AUTO = new Counts(0,0);
    public Counts {
      if (forward<0 || forward>MAX_DIRECTION || reverse<0 || reverse>MAX_DIRECTION || forward==0 && reverse!=0)
        throw new IllegalArgumentException("每个行驶方向须为 1–7 车道；单向不设反向车道");
    }
    public boolean explicit(){return forward>0;}
    public boolean twoWay(){return reverse>0;}
    public int total(){return forward+reverse;}
    public Counts mirrored(){return twoWay()?new Counts(reverse,forward):this;}
    public String label(){return twoWay()?"双向 "+forward+"+"+reverse+" 车道":"单向 "+forward+" 车道";}
  }
  public static Counts counts(Style style, Options options){
    if(options.lanes().explicit())return options.lanes();
    var c=RoadProfile.catalog(style);return new Counts(c.twoWay()?c.lanes()/2:c.lanes(),c.twoWay()?c.lanes()/2:0);
  }
  public static Counts counts(Settings s){return counts(s.style(),s.options());}
  public static Style carrier(Type type,Counts counts,Median median){
    if(type!=Type.ORDINARY && type!=Type.HIGHWAY)throw new IllegalArgumentException("独立车道数仅适用于普通道路与高速");
    if(!counts.explicit())throw new IllegalArgumentException("尚未设置车道数");
    if(!counts.twoWay())return type==Type.HIGHWAY?switch(counts.forward()){case 1->Style.H1_ONE;case 2->Style.H2_ONE;case 3->Style.H3_ONE;default->Style.H4_ONE;}:switch(counts.forward()){case 1->Style.O1_ONE;case 2->Style.O2_ONE;case 3->Style.O3_ONE;default->Style.O4_ONE;};
    if(median==Median.NONE)median=type==Type.HIGHWAY?Median.RAIL:Median.DOUBLE_YELLOW;
    if(type==Type.HIGHWAY && median!=Median.GREEN)median=Median.RAIL;
    int n=Math.min(8,Math.max(counts.forward(),counts.reverse())*2);
    if(type==Type.HIGHWAY)n=Math.max(4,n); // carrier describes facilities only; counts are explicit.
    if(median==Median.DASHED_YELLOW && n!=2)median=Median.DOUBLE_YELLOW;
    return RoadProfile.choose(type,n,true,median,type==Type.HIGHWAY);
  }
  public static Settings configure(Settings s,Type type,Counts counts,double laneWidth){
    var style=carrier(type,counts,RoadProfile.catalog(s.style()).median());
    var options=s.options().lanes(counts).ends(RoadTransitions.Ends.NONE);
    options=options.lanePoints(LaneAdditions.reserveAuthored(options.lanePoints(),counts.total()));
    double w=RoadProfile.width(style,options,laneWidth);
    return new Settings(s.mode(),style,w,s.thickness(),s.tension(),s.arcDegrees()).structure(s.structure()).options(options);
  }
  /** An explicit width edit changes the authored body, retaining endpoint tapers.
   * A copied exact port is only its initial body shape, not a permanent width lock. */
  public static Settings width(Settings s,double laneWidth){
    var options=s.options().ends(s.options().ends().port(null));
    return new Settings(s.mode(),s.style(),RoadProfile.width(s.style(),options,laneWidth),s.thickness(),s.tension(),s.arcDegrees())
        .structure(s.structure()).options(options);
  }
  private RoadLanes(){}
}
