package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Immutable, versioned input shared by world construction, previews, saving and rendering. */
public record JunctionSpec(V center, Kind kind, boolean leftTraffic, double cornerRadius,
    double islandRadius, int ringLanes, double ringLaneWidth, double thickness,
    Control control, int greenSeconds, int yellowSeconds, int allRedSeconds,
    int timeOffset, boolean guides, boolean greenIsland, boolean outerRail, List<Arm> arms, boolean parallelApproaches, OneSidedJunction.Source oneSide) {
  public JunctionSpec(V center,Kind kind,boolean leftTraffic,double cornerRadius,double islandRadius,int ringLanes,double ringLaneWidth,double thickness,Control control,int greenSeconds,int yellowSeconds,int allRedSeconds,int timeOffset,boolean guides,boolean greenIsland,boolean outerRail,List<Arm> arms,boolean parallelApproaches){this(center,kind,leftTraffic,cornerRadius,islandRadius,ringLanes,ringLaneWidth,thickness,control,greenSeconds,yellowSeconds,allRedSeconds,timeOffset,guides,greenIsland,outerRail,arms,parallelApproaches,null);}
  public JunctionSpec oneSide(OneSidedJunction.Source value){return new JunctionSpec(center,kind,leftTraffic,cornerRadius,islandRadius,ringLanes,ringLaneWidth,thickness,control,greenSeconds,yellowSeconds,allRedSeconds,timeOffset,guides,greenIsland,outerRail,arms,parallelApproaches,value);}
  public JunctionSpec(V center,Kind kind,boolean leftTraffic,double cornerRadius,double islandRadius,int ringLanes,double ringLaneWidth,double thickness,Control control,int greenSeconds,int yellowSeconds,int allRedSeconds,int timeOffset,boolean guides,boolean greenIsland,boolean outerRail,List<Arm> arms){this(center,kind,leftTraffic,cornerRadius,islandRadius,ringLanes,ringLaneWidth,thickness,control,greenSeconds,yellowSeconds,allRedSeconds,timeOffset,guides,greenIsland,outerRail,arms,false);}
  public JunctionSpec parallel(boolean value){return new JunctionSpec(center,kind,leftTraffic,cornerRadius,islandRadius,ringLanes,ringLaneWidth,thickness,control,greenSeconds,yellowSeconds,allRedSeconds,timeOffset,guides,greenIsland,outerRail,arms,value,oneSide);}
  public JunctionSpec(V center,Kind kind,boolean leftTraffic,double cornerRadius,double islandRadius,
      int ringLanes,double ringLaneWidth,double thickness,Control control,int greenSeconds,
      int yellowSeconds,int allRedSeconds,int timeOffset,boolean guides,boolean greenIsland,List<Arm> arms) {
    this(center,kind,leftTraffic,cornerRadius,islandRadius,ringLanes,ringLaneWidth,thickness,control,
        greenSeconds,yellowSeconds,allRedSeconds,timeOffset,guides,greenIsland,false,arms);
  }
  public enum Kind { INTERSECTION, ROUNDABOUT }
  public enum Control { NONE, YIELD, SIGNALS }
  public static final int STRAIGHT = 1, LEFT = 2, RIGHT = 4, UTURN = 8;
  public static final int[] TURNS = {STRAIGHT, LEFT, RIGHT, UTURN};
  public record Lane(int mask, List<Integer> targets, List<Integer> targetLanes,boolean splitLeft,int leftPhase,boolean controlRight) {
    public Lane(int mask,List<Integer> targets,List<Integer> targetLanes,boolean splitLeft,int leftPhase){this(mask,targets,targetLanes,splitLeft,leftPhase,false);}
    public Lane(int mask,List<Integer> targets,List<Integer> targetLanes){this(mask,targets,targetLanes,false,-1);}
    public Lane signals(boolean split,int phase){return new Lane(mask,targets,targetLanes,split,phase,controlRight);}
    public Lane rightSignal(boolean enabled){return new Lane(mask,targets,targetLanes,splitLeft,leftPhase,enabled);}
    public int effectiveLeftPhase(int arm){return leftPhase<0?8+arm:leftPhase;}
    public static final Lane AUTO = new Lane(-1, List.of(-1,-1,-1,-1), List.of(-1,-1,-1,-1));
    public Lane {
      targets = List.copyOf(targets); targetLanes = List.copyOf(targetLanes);
      if (leftPhase < -1 || leftPhase > 15 || mask < -1 || mask > 15 || targets.size() != 4 || targetLanes.size() != 4
          || targets.stream().anyMatch(n -> n < -1 || n > 7)
          || targetLanes.stream().anyMatch(n -> n < -1 || n >= RoadLanes.MAX_DIRECTION))
        throw new IllegalArgumentException("车道通行配置无效");
    }
    public Lane mask(int value) { return new Lane(value, targets, targetLanes,splitLeft,leftPhase,controlRight); }
    public Lane target(int turn, int arm, int lane) {
      var a = new ArrayList<>(targets); var b = new ArrayList<>(targetLanes);
      a.set(turn, arm); b.set(turn, lane); return new Lane(mask, a, b,splitLeft,leftPhase,controlRight);
    }
  }
  public record Arm(Node endpoint, V inward, Settings external, int incoming, int outgoing,
      double width, double median, RoadProfile.Median medianKind, double cycleWidth,
      double curbWidth, boolean crosswalk, double crossingWidth, double crossingSetback,
      double stopGap, int phase, List<Lane> lanes, boolean attached,
      boolean advanceSign, boolean nameSign, boolean rightSlip) {
    public Arm(Node endpoint,V inward,Settings external,int incoming,int outgoing,double width,
        double median,RoadProfile.Median medianKind,double cycleWidth,double curbWidth,
        boolean crosswalk,double crossingWidth,double crossingSetback,double stopGap,int phase,
        List<Lane> lanes,boolean attached,boolean advanceSign,boolean nameSign) {
      this(endpoint,inward,external,incoming,outgoing,width,median,medianKind,cycleWidth,curbWidth,
          crosswalk,crossingWidth,crossingSetback,stopGap,phase,lanes,attached,advanceSign,nameSign,false);
    }
    public Arm(Node endpoint,V inward,Settings external,int incoming,int outgoing,double width,
        double median,RoadProfile.Median medianKind,double cycleWidth,double curbWidth,
        boolean crosswalk,double crossingWidth,double crossingSetback,double stopGap,int phase,
        List<Lane> lanes,boolean attached) {
      this(endpoint,inward,external,incoming,outgoing,width,median,medianKind,cycleWidth,curbWidth,
          crosswalk,crossingWidth,crossingSetback,stopGap,phase,lanes,attached,true,true);
    }
    public Arm {
      lanes = List.copyOf(lanes);
      if(Math.abs(inward.horizontalLength()-1)>1e-12)inward=inward.horizontalUnit();
      // NBT canonicalizes -0.0. Canonicalize it before planning as well so saved
      // headings produce exactly the same samples and cache keys after reload.
      inward = new V(inward.x()==0?0:inward.x(),0,inward.z()==0?0:inward.z());
      if (incoming < 0 || incoming > RoadLanes.MAX_DIRECTION || outgoing < 0 || outgoing > RoadLanes.MAX_DIRECTION
          || incoming + outgoing == 0 || lanes.size() != incoming
          || !RoadGeometry.finite(width, median, cycleWidth, curbWidth, crossingWidth, crossingSetback, stopGap)
          || width < 4 || width > 96 || median < 0 || median > 8
          || cycleWidth < 0 || cycleWidth > 4 || curbWidth < 0 || curbWidth > 2
          || (width - median - 1 - 2*(cycleWidth+curbWidth))/(incoming+outgoing) < 2
          || crossingWidth < 2 || crossingWidth > 8 || crossingSetback < 1 || crossingSetback > 16
          || stopGap < .5 || stopGap > 8 || phase < 0 || phase > 7
          || RoadProfile.catalog(external.style()).type() != RoadProfile.Type.ORDINARY)
        throw new IllegalArgumentException("普通道路断面无效：每条车道至少 2 格，进／出各 0–7 车道，总宽 4–96 格");
      if ((incoming == 0 || outgoing == 0) && median > 0)
        throw new IllegalArgumentException("单行接入口不能设置中央分隔带");
    }
    public double laneWidth() { return (width-median-1-2*(cycleWidth+curbWidth))/(incoming+outgoing); }
    public double divider(boolean left) { return (outgoing-incoming)*laneWidth()/2*(left?-1:1); }
    public double laneCenter(boolean incomingLane, int lane, boolean left) {
      int count = incomingLane ? incoming : outgoing;
      int order = left ? count-1-lane : lane;
      double side = (incomingLane ? 1 : -1)*(left?-1:1);
      return divider(left) + side*(median/2+(order+.5)*laneWidth());
    }
    public double motorEdge(boolean incomingLane, boolean left) {
      int n = incomingLane ? incoming : outgoing;
      return divider(left)+(incomingLane?1:-1)*(left?-1:1)*(median/2+n*laneWidth());
    }
    public Arm lanes(List<Lane> value) { return new Arm(endpoint,inward,external,incoming,outgoing,width,
        median,medianKind,cycleWidth,curbWidth,crosswalk,crossingWidth,crossingSetback,stopGap,phase,value,attached,advanceSign,nameSign,rightSlip); }
    public Arm node(Node value, V direction, Settings profile) { return new Arm(value,direction,profile,
        incoming,outgoing,width,median,medianKind,cycleWidth,curbWidth,crosswalk,crossingWidth,crossingSetback,stopGap,phase,lanes,attached,advanceSign,nameSign,rightSlip); }
    public Arm attached(boolean value) { return new Arm(endpoint,inward,external,incoming,outgoing,width,median,medianKind,cycleWidth,curbWidth,crosswalk,crossingWidth,crossingSetback,stopGap,phase,lanes,value,advanceSign,nameSign,rightSlip); }
    public Arm signs(boolean advance,boolean name) { return new Arm(endpoint,inward,external,incoming,outgoing,width,median,medianKind,cycleWidth,curbWidth,crosswalk,crossingWidth,crossingSetback,stopGap,phase,lanes,attached,advance,name,rightSlip); }
    public Arm crossing(boolean enabled) { return new Arm(endpoint,inward,external,incoming,outgoing,width,median,medianKind,cycleWidth,curbWidth,enabled,crossingWidth,crossingSetback,stopGap,phase,lanes,attached,advanceSign,nameSign,rightSlip); }
    public boolean hasSlowTraffic() { return external.options().sidewalk().enabled() || cycleWidth > .01; }
    public Arm slip(boolean enabled) { return new Arm(endpoint,inward,external,incoming,outgoing,width,median,medianKind,cycleWidth,curbWidth,crosswalk,crossingWidth,crossingSetback,stopGap,phase,lanes,attached,advanceSign,nameSign,enabled); }
    public boolean signEnabled(int slot) { return slot==0?advanceSign:slot==1&&nameSign; }
  }
  public JunctionSpec {
    arms = List.copyOf(arms);
    if (center == null || kind == null || control == null || (kind == Kind.INTERSECTION ? arms.size() < 2 || arms.size() > 6 : arms.size() > 8)
        || !RoadGeometry.finite(center.x(),center.y(),center.z(),cornerRadius,islandRadius,ringLaneWidth,thickness)
        || Math.abs(center.x()) > 29_999_000 || Math.abs(center.z()) > 29_999_000
        || center.y() < -2040 || center.y() > 2040 || cornerRadius < 2 || cornerRadius > 32
        || islandRadius < 6 || islandRadius > 48 || ringLanes < 1 || ringLanes > 3
        || ringLaneWidth < 2 || ringLaneWidth > 6 || thickness < .25 || thickness > 4
        || greenSeconds < 5 || greenSeconds > 120 || yellowSeconds < 0 || yellowSeconds > 10
        || allRedSeconds < 0 || allRedSeconds > 10 || Math.abs(timeOffset) > 86400)
      throw new IllegalArgumentException("路口参数无效：普通路口 2–6 向，环岛 0–8 个接入口，转角 2–32 格，环岛半径 6–48 格");
  }
  public static Arm arm(Node node, V inward, Settings external, boolean incomingOneWay, int phase) {
    var layout=RoadProfile.layout(external,external.width()); var c=layout.catalog();
    int in=c.twoWay()?RoadLanes.counts(external).forward():incomingOneWay?c.lanes():0;
    int out=c.twoWay()?RoadLanes.counts(external).reverse():incomingOneWay?0:c.lanes();
    // Junction shoulders remain symmetric; account for the one-sided ordinary extras.
    double extras = c.twoWay()?1:.5;
    return new Arm(node,inward,external,in,out,external.width(),layout.median(),c.median(),
        layout.cycleWidth()*extras,layout.curbWidth()*extras,true,4,3,1.5,phase,
        Collections.nCopies(in,Lane.AUTO),false);
  }
  public JunctionSpec arms(List<Arm> value) { return new JunctionSpec(center,kind,leftTraffic,cornerRadius,
      islandRadius,ringLanes,ringLaneWidth,thickness,control,greenSeconds,yellowSeconds,allRedSeconds,timeOffset,guides,greenIsland,outerRail,value,parallelApproaches,oneSide); }
  public JunctionSpec outerRail(boolean value) { return new JunctionSpec(center,kind,leftTraffic,cornerRadius,
      islandRadius,ringLanes,ringLaneWidth,thickness,control,greenSeconds,yellowSeconds,allRedSeconds,timeOffset,guides,greenIsland,value,arms,parallelApproaches,oneSide); }
  /** Current builds only: saved old geometry is not implicitly upgraded. */
  public JunctionSpec supportedCrossings() { return arms(arms.stream().map(a -> a.crosswalk() && !a.hasSlowTraffic() ? a.crossing(false) : a).toList()); }
  public static String maskName(int mask) {
    if(mask<0)return "自动"; if(mask==0)return "禁行";
    var names=new ArrayList<String>();
    if((mask&LEFT)!=0)names.add("左转"); if((mask&STRAIGHT)!=0)names.add("直行");
    if((mask&RIGHT)!=0)names.add("右转"); if((mask&UTURN)!=0)names.add("掉头");
    return String.join("＋",names);
  }
}
