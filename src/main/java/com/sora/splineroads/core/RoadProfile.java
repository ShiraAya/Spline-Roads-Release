package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** A shared cross section for paint, attachment ports, furniture and collision. */
public final class RoadProfile {
  public enum Type {
    ORDINARY,
    HIGHWAY,
    RAMP,
    LEGACY
  }

  public enum Median {
    DOUBLE_YELLOW,
    DASHED_YELLOW,
    RAIL,
    GREEN,
    NONE
  }

  public enum OuterRail {
    AUTO("自动"),
    ON("开启"),
    OFF("关闭"),
    SOUND_LEFT("绿色隔音板·左侧"), SOUND_RIGHT("绿色隔音板·右侧"), SOUND_BOTH("绿色隔音板·双侧");
    public boolean sound(int side){return this==SOUND_BOTH||this==SOUND_LEFT&&side<0||this==SOUND_RIGHT&&side>0;}
    public final String label;

    OuterRail(String label) {
      this.label = label;
    }
  }

  public enum Side {
    AUTO,
    EAST,
    WEST,
    NORTH,
    SOUTH;

    public V vector() {
      return switch (this) {
        case EAST -> new V(1, 0, 0);
        case WEST -> new V(-1, 0, 0);
        case NORTH -> new V(0, 0, -1);
        case SOUTH -> new V(0, 0, 1);
        case AUTO -> new V(0, 0, 0);
      };
    }

    public String label() {
      return switch (this) {
        case EAST -> "东侧";
        case WEST -> "西侧";
        case NORTH -> "北侧";
        case SOUTH -> "南侧";
        case AUTO -> "自动";
      };
    }
  }

  /** Local Minecraft right-hand side: +X is east, +Z is south. */
  public static int trafficSign(boolean left) {
    return left ? -1 : 1;
  }

  public static V flowOnSide(V tangent, Side side, boolean left) {
    V horizontal = tangent.horizontalUnit();
    return horizontal.left().dot(side.vector()) * trafficSign(left) < 0 ? tangent.mul(-1) : tangent;
  }

  public static List<Side> sides(V tangent) {
    return Math.abs(tangent.z()) >= Math.abs(tangent.x())
        ? List.of(Side.EAST, Side.WEST)
        : List.of(Side.SOUTH, Side.NORTH);
  }

  public enum JoinMode {
    ADDED,
    OUTER_LANES
  }

  public enum RouteKind {
    DIRECT("直接连接"),
    VIA("定向曲线"),
    LOOP_LEFT("左环绕"),
    LOOP_RIGHT("右环绕");
    public final String label;

    RouteKind(String label) {
      this.label = label;
    }
  }

  public record Control(UUID id,V position) {
    public Control {Objects.requireNonNull(id);Objects.requireNonNull(position);if(!RoadGeometry.finite(position.x(),position.y(),position.z())||Math.abs(position.x())>29999984||Math.abs(position.z())>29999984||Math.abs(position.y())>4096)throw new IllegalArgumentException("控制点坐标超出范围");}
  }

  public record Routing(
      RouteKind kind,
      double radius,
      double offsetX,
      double offsetZ,
      boolean fitEdges,
      double transition, int fanArms, double fanStart, double fanEnd, List<Control> controls, boolean through) {
    public Routing(RouteKind kind,double radius,double x,double z,boolean fit,double transition,int arms,double start,double end,List<Control> controls){this(kind,radius,x,z,fit,transition,arms,start,end,controls,false);}
    public Routing(RouteKind kind,double radius,double x,double z,boolean fit,double transition,int arms,double start,double end){this(kind,radius,x,z,fit,transition,arms,start,end,List.of());}
    public Routing(RouteKind kind,double radius,double x,double z,boolean fit,double transition) {
      this(kind,radius,x,z,fit,transition,0,0,0);
    }
    public Routing(RouteKind kind, double radius, double x, double z, boolean fit) {
      this(kind, radius, x, z, fit, 36);
    }

    public static final Routing DEFAULT = new Routing(RouteKind.DIRECT, 24, 0, 0, true);

    public Routing {
      controls=List.copyOf(controls);
      if(controls.size()>32||controls.stream().map(Control::id).distinct().count()!=controls.size())throw new IllegalArgumentException("曲线控制点最多32个，且不能重复");
      if ((fanArms != 0 && fanArms != 5 && fanArms != 6)
          || !RoadGeometry.finite(fanStart,fanEnd) || fanStart<0 || fanEnd<0 || fanStart>512 || fanEnd>512)
        throw new IllegalArgumentException("多向分流参数无效");
      if (kind == null
          || !RoadGeometry.finite(radius, offsetX, offsetZ, transition)
          || transition < 18
          || transition > 96
          || radius < 8
          || radius > 96
          || Math.abs(offsetX) > 128
          || Math.abs(offsetZ) > 128)
        throw new IllegalArgumentException("过渡 18–96 格，环绕半径 8–96 格，水平偏移 ±128 格");
    }

    public Routing shape(RouteKind value, double r) {
      return new Routing(value, r, offsetX, offsetZ, fitEdges, transition,fanArms,fanStart,fanEnd,controls,through);
    }

    public Routing offset(double x, double z) {
      return new Routing(kind, radius, x, z, fitEdges, transition,fanArms,fanStart,fanEnd,controls,through);
    }

    public Routing transition(double value) {
      return new Routing(kind, radius, offsetX, offsetZ, fitEdges, value,fanArms,fanStart,fanEnd,controls,through);
    }

    public Routing fit(boolean value) {
      return new Routing(kind, radius, offsetX, offsetZ, value, transition,fanArms,fanStart,fanEnd,controls,through);
    }
    public Routing controls(List<Control> value){return new Routing(kind,radius,offsetX,offsetZ,fitEdges,transition,fanArms,fanStart,fanEnd,value,through);}
    public Routing through(boolean value){return new Routing(kind,radius,offsetX,offsetZ,fitEdges,transition,fanArms,fanStart,fanEnd,controls,value);}
    public Routing fan(int arms,double start,double end) {
      return new Routing(kind,radius,offsetX,offsetZ,fitEdges,transition,arms,start,end,controls,through);
    }
  }

  public record Ports(
      Side source,
      Side target,
      double sourceOutset,
      double targetOutset,
      JoinMode sourceJoin,
      JoinMode targetJoin) {
    public Ports(Side a, Side b, double x, double y) {
      this(a, b, x, y, JoinMode.ADDED, JoinMode.ADDED);
    }

    public static final Ports DEFAULT = new Ports(Side.AUTO, Side.AUTO, 0, 0);

    public Ports {
      if (source == null
          || target == null
          || sourceJoin == null
          || targetJoin == null
          || !RoadGeometry.finite(sourceOutset, targetOutset)
          || sourceOutset < 0
          || targetOutset < 0
          || sourceOutset > 64
          || targetOutset > 64) throw new IllegalArgumentException("无效的匝道接入侧或渐宽范围");
    }
  }

  public record Options(
      boolean leftTraffic,
      boolean cycle,
      boolean cycleRail,
      boolean curb,
      double liftPosition,
      double liftHeight,
      Ports ports,
      Routing routing,
      OuterRail outerRail,
      RoadTransitions.Ends ends, RoadSidewalks.Config sidewalk, RoadInfrastructure.Config infrastructure, List<RoadLaneLines.Edit> laneLines, boolean cycleAsphalt, boolean hideArrows, RoadStreetscape.Config streetscape, RoadAttachments.Data attachments, LanePoints.Data lanePoints, RoadLanes.Counts lanes, RailbedProfile railbed, List<RailwayCrossings.Surface> levelCrossings) {
    public Options(boolean leftTraffic,boolean cycle,boolean cycleRail,boolean curb,double liftPosition,double liftHeight,Ports ports,Routing routing,OuterRail outerRail,RoadTransitions.Ends ends,RoadSidewalks.Config sidewalk,RoadInfrastructure.Config infrastructure,List<RoadLaneLines.Edit> laneLines,boolean cycleAsphalt,boolean hideArrows,RoadStreetscape.Config streetscape,RoadAttachments.Data attachments,LanePoints.Data lanePoints,RoadLanes.Counts lanes,RailbedProfile railbed){
      this(leftTraffic,cycle,cycleRail,curb,liftPosition,liftHeight,ports,routing,outerRail,ends,sidewalk,infrastructure,laneLines,cycleAsphalt,hideArrows,streetscape,attachments,lanePoints,lanes,railbed,List.of());
    }
    public Options levelCrossings(List<RailwayCrossings.Surface> value){return new Options(leftTraffic,cycle,cycleRail,curb,liftPosition,liftHeight,ports,routing,outerRail,ends,sidewalk,infrastructure,laneLines,cycleAsphalt,hideArrows,streetscape,attachments,lanePoints,lanes,railbed,value);}
    public Options(boolean leftTraffic,boolean cycle,boolean cycleRail,boolean curb,double liftPosition,double liftHeight,Ports ports,Routing routing,OuterRail outerRail,RoadTransitions.Ends ends,RoadSidewalks.Config sidewalk,RoadInfrastructure.Config infrastructure,List<RoadLaneLines.Edit> laneLines,boolean cycleAsphalt,boolean hideArrows,RoadStreetscape.Config streetscape,RoadAttachments.Data attachments,LanePoints.Data lanePoints,RoadLanes.Counts lanes){
      this(leftTraffic,cycle,cycleRail,curb,liftPosition,liftHeight,ports,routing,outerRail,ends,sidewalk,infrastructure,laneLines,cycleAsphalt,hideArrows,streetscape,attachments,lanePoints,lanes,RailbedProfile.NONE);
    }
    public Options railbed(RailbedProfile value){return new Options(leftTraffic,cycle,cycleRail,curb,liftPosition,liftHeight,ports,routing,outerRail,ends,sidewalk,infrastructure,laneLines,cycleAsphalt,hideArrows,streetscape,attachments,lanePoints,lanes,value,levelCrossings);}


    public Options(boolean leftTraffic,boolean cycle,boolean cycleRail,boolean curb,double liftPosition,double liftHeight,Ports ports,Routing routing,OuterRail outerRail,RoadTransitions.Ends ends,RoadSidewalks.Config sidewalk,RoadInfrastructure.Config infrastructure,List<RoadLaneLines.Edit> laneLines,boolean cycleAsphalt,boolean hideArrows,RoadStreetscape.Config streetscape,RoadAttachments.Data attachments,LanePoints.Data lanePoints){
      this(leftTraffic,cycle,cycleRail,curb,liftPosition,liftHeight,ports,routing,outerRail,ends,sidewalk,infrastructure,laneLines,cycleAsphalt,hideArrows,streetscape,attachments,lanePoints,RoadLanes.Counts.AUTO);
    }
    public Options lanes(RoadLanes.Counts value){return new Options(leftTraffic,cycle,cycleRail,curb,liftPosition,liftHeight,ports,routing,outerRail,ends,sidewalk,infrastructure,laneLines,cycleAsphalt,hideArrows,streetscape,attachments,lanePoints,value,railbed,levelCrossings);}
    public Options(boolean leftTraffic,boolean cycle,boolean cycleRail,boolean curb,double liftPosition,double liftHeight,Ports ports,Routing routing,OuterRail outerRail,RoadTransitions.Ends ends,RoadSidewalks.Config sidewalk,RoadInfrastructure.Config infrastructure,List<RoadLaneLines.Edit> laneLines,boolean cycleAsphalt,boolean hideArrows,RoadStreetscape.Config streetscape){
      this(leftTraffic,cycle,cycleRail,curb,liftPosition,liftHeight,ports,routing,outerRail,ends,sidewalk,infrastructure,laneLines,cycleAsphalt,hideArrows,streetscape,RoadAttachments.Data.EMPTY);
    }
    public Options(boolean leftTraffic,boolean cycle,boolean cycleRail,boolean curb,double liftPosition,double liftHeight,Ports ports,Routing routing,OuterRail outerRail,RoadTransitions.Ends ends,RoadSidewalks.Config sidewalk,RoadInfrastructure.Config infrastructure,List<RoadLaneLines.Edit> laneLines,boolean cycleAsphalt,boolean hideArrows,RoadStreetscape.Config streetscape,RoadAttachments.Data attachments){
      this(leftTraffic,cycle,cycleRail,curb,liftPosition,liftHeight,ports,routing,outerRail,ends,sidewalk,infrastructure,laneLines,cycleAsphalt,hideArrows,streetscape,attachments,LanePoints.Data.EMPTY);
    }
    public Options lanePoints(LanePoints.Data value){return new Options(leftTraffic,cycle,cycleRail,curb,liftPosition,liftHeight,ports,routing,outerRail,ends,sidewalk,infrastructure,laneLines,cycleAsphalt,hideArrows,streetscape,attachments,value,lanes,railbed,levelCrossings);}
    public Options attachments(RoadAttachments.Data value){return new Options(leftTraffic,cycle,cycleRail,curb,liftPosition,liftHeight,ports,routing,outerRail,ends,sidewalk,infrastructure,laneLines,cycleAsphalt,hideArrows,streetscape,value,lanePoints,lanes,railbed,levelCrossings);}
    public Options(boolean leftTraffic,boolean cycle,boolean cycleRail,boolean curb,double liftPosition,double liftHeight,Ports ports,Routing routing,OuterRail outerRail,RoadTransitions.Ends ends,RoadSidewalks.Config sidewalk,RoadInfrastructure.Config infrastructure,List<RoadLaneLines.Edit> laneLines,boolean cycleAsphalt,boolean hideArrows){
      this(leftTraffic,cycle,cycleRail,curb,liftPosition,liftHeight,ports,routing,outerRail,ends,sidewalk,infrastructure,laneLines,cycleAsphalt,hideArrows,RoadStreetscape.Config.DEFAULT.separator(cycleRail?RoadStreetscape.Separator.RAIL:RoadStreetscape.Separator.LINE));
    }
    public Options streetscape(RoadStreetscape.Config v){return new Options(leftTraffic,cycle,cycleRail,curb,liftPosition,liftHeight,ports,routing,outerRail,ends,sidewalk,infrastructure,laneLines,cycleAsphalt,hideArrows,v,attachments,lanePoints,lanes,railbed,levelCrossings);}
    public Options(boolean leftTraffic,boolean cycle,boolean cycleRail,boolean curb,double liftPosition,double liftHeight,Ports ports,Routing routing,OuterRail outerRail,RoadTransitions.Ends ends,RoadSidewalks.Config sidewalk,RoadInfrastructure.Config infrastructure,List<RoadLaneLines.Edit> laneLines,boolean cycleAsphalt){
      this(leftTraffic,cycle,cycleRail,curb,liftPosition,liftHeight,ports,routing,outerRail,ends,sidewalk,infrastructure,laneLines,cycleAsphalt,false);
    }
    public Options hideArrows(boolean value){return new Options(leftTraffic,cycle,cycleRail,curb,liftPosition,liftHeight,ports,routing,outerRail,ends,sidewalk,infrastructure,laneLines,cycleAsphalt,value,streetscape,attachments,lanePoints,lanes,railbed,levelCrossings);}
    public enum CycleFinish { NONE("无非机动车道"), GREEN("绿色非机动车道"), ASPHALT("沥青非机动车道"), PARKING("停车线（临时车位）");public final String label;CycleFinish(String s){label=s;} }
    public CycleFinish cycleFinish(){return !cycle?CycleFinish.NONE:streetscape.parking()?CycleFinish.PARKING:cycleAsphalt?CycleFinish.ASPHALT:CycleFinish.GREEN;}
    public Options cycleFinish(CycleFinish value){return new Options(leftTraffic,value!=CycleFinish.NONE,cycleRail,curb,liftPosition,liftHeight,ports,routing,outerRail,ends,sidewalk,infrastructure,laneLines,value==CycleFinish.ASPHALT||value==CycleFinish.PARKING,hideArrows,streetscape.parking(value==CycleFinish.PARKING),attachments,lanePoints,lanes,railbed,levelCrossings);}
public Options(boolean leftTraffic,boolean cycle,boolean cycleRail,boolean curb,double liftPosition,double liftHeight,Ports ports,Routing routing,OuterRail outerRail,RoadTransitions.Ends ends,RoadSidewalks.Config sidewalk,RoadInfrastructure.Config infrastructure,List<RoadLaneLines.Edit> laneLines){this(leftTraffic,cycle,cycleRail,curb,liftPosition,liftHeight,ports,routing,outerRail,ends,sidewalk,infrastructure,laneLines,false);}
    public Options(boolean leftTraffic,boolean cycle,boolean cycleRail,boolean curb,double liftPosition,double liftHeight,Ports ports,Routing routing,OuterRail outerRail,RoadTransitions.Ends ends,RoadSidewalks.Config sidewalk,RoadInfrastructure.Config infrastructure){
      this(leftTraffic,cycle,cycleRail,curb,liftPosition,liftHeight,ports,routing,outerRail,ends,sidewalk,infrastructure,List.of());
    }
    public Options laneLines(List<RoadLaneLines.Edit> value){return new Options(leftTraffic,cycle,cycleRail,curb,liftPosition,liftHeight,ports,routing,outerRail,ends,sidewalk,infrastructure,value,cycleAsphalt,hideArrows,streetscape,attachments,lanePoints,lanes,railbed,levelCrossings);}
    public Options(boolean leftTraffic,boolean cycle,boolean cycleRail,boolean curb,double liftPosition,double liftHeight,Ports ports,Routing routing,OuterRail outerRail,RoadTransitions.Ends ends,RoadSidewalks.Config sidewalk) {
      this(leftTraffic,cycle,cycleRail,curb,liftPosition,liftHeight,ports,routing,outerRail,ends,sidewalk,RoadInfrastructure.Config.DEFAULT);
    }
    public Options infrastructure(RoadInfrastructure.Config value) {return new Options(leftTraffic,cycle,cycleRail,curb,liftPosition,liftHeight,ports,routing,outerRail,ends,sidewalk,value,laneLines,cycleAsphalt,hideArrows,streetscape,attachments,lanePoints,lanes,railbed,levelCrossings);}
    public Options(boolean leftTraffic,boolean cycle,boolean cycleRail,boolean curb,double liftPosition,double liftHeight,Ports ports,Routing routing,OuterRail outerRail,RoadTransitions.Ends ends) {
      this(leftTraffic,cycle,cycleRail,curb,liftPosition,liftHeight,ports,routing,outerRail,ends,RoadSidewalks.Config.DEFAULT);
    }
    public Options sidewalk(RoadSidewalks.Config value) {return new Options(leftTraffic,cycle,cycleRail,curb,liftPosition,liftHeight,ports,routing,outerRail,ends,value,infrastructure,laneLines,cycleAsphalt,hideArrows,streetscape,attachments,lanePoints,lanes,railbed,levelCrossings);}

    public Options(
        boolean leftTraffic,
        boolean cycle,
        boolean cycleRail,
        boolean curb,
        double liftPosition,
        double liftHeight,
        Ports ports,
        Routing routing,
        OuterRail outerRail) {
      this(
          leftTraffic,
          cycle,
          cycleRail,
          curb,
          liftPosition,
          liftHeight,
          ports,
          routing,
          outerRail,
          RoadTransitions.Ends.NONE);
    }

    public Options ends(RoadTransitions.Ends value) {
      return new Options(
          leftTraffic,
          cycle,
          cycleRail,
          curb,
          liftPosition,
          liftHeight,
          ports,
          routing,
          outerRail,
          value, sidewalk, infrastructure,laneLines,cycleAsphalt,hideArrows,streetscape,attachments,lanePoints,lanes,railbed,levelCrossings);
    }

    public Options(
        boolean leftTraffic,
        boolean cycle,
        boolean cycleRail,
        boolean curb,
        double liftPosition,
        double liftHeight,
        Ports ports,
        Routing routing) {
      this(
          leftTraffic,
          cycle,
          cycleRail,
          curb,
          liftPosition,
          liftHeight,
          ports,
          routing,
          OuterRail.AUTO);
    }

    public Options outerRail(OuterRail value) {
      return new Options(
          leftTraffic,
          cycle,
          cycleRail,
          curb,
          liftPosition,
          liftHeight,
          ports,
          routing,
          value,
          ends, sidewalk, infrastructure,laneLines,cycleAsphalt,hideArrows,streetscape,attachments,lanePoints,lanes,railbed,levelCrossings);
    }

    public Options(
        boolean left, boolean bike, boolean rail, boolean edge, double pos, double lift) {
      this(left, bike, rail, edge, pos, lift, Ports.DEFAULT, Routing.DEFAULT);
    }

    public Options(
        boolean left,
        boolean bike,
        boolean rail,
        boolean edge,
        double pos,
        double lift,
        Ports ports) {
      this(left, bike, rail, edge, pos, lift, ports, Routing.DEFAULT);
    }

    public static final Options DEFAULT = new Options(false, false, false, false, .5, 0);

    public Options {
      levelCrossings=List.copyOf(levelCrossings);if(levelCrossings.size()>64)throw new IllegalArgumentException("Too many railway crossings");
      if(railbed==null)railbed=RailbedProfile.NONE;
      if(lanes==null)lanes=RoadLanes.Counts.AUTO;
      if(lanePoints==null)lanePoints=LanePoints.Data.EMPTY;
      if(attachments==null)attachments=RoadAttachments.Data.EMPTY;
      Objects.requireNonNull(streetscape);
      cycleRail=cycle&&!streetscape.parking()&&streetscape.separator()==RoadStreetscape.Separator.RAIL;
      if(cycle&&streetscape.parking())cycleAsphalt=true;
      if(!cycle||!sidewalk.enabled()||streetscape.separator()==RoadStreetscape.Separator.LINE)streetscape=streetscape.walkLamps(false);
      sidewalk=RoadStreetscape.fitWalk(sidewalk,streetscape);
      laneLines=List.copyOf(laneLines);if(laneLines.size()>32||laneLines.stream().map(RoadLaneLines.Edit::key).distinct().count()!=laneLines.size())throw new IllegalArgumentException("车道线编辑记录重复或过多");
      if (infrastructure == null || sidewalk == null || ends == null
          || outerRail == null
          || ports == null
          || routing == null
          || !RoadGeometry.finite(liftPosition, liftHeight)
          || liftPosition < .15
          || liftPosition > .85
          || Math.abs(liftHeight) > 64)
        throw new IllegalArgumentException("控制点位置 15–85%，抬升 -64–64 格");
    }

    public Options ports(Ports value) {
      return new Options(
          leftTraffic,
          cycle,
          cycleRail,
          curb,
          liftPosition,
          liftHeight,
          value,
          routing,
          outerRail,
          ends, sidewalk, infrastructure,laneLines,cycleAsphalt,hideArrows,streetscape,attachments,lanePoints,lanes,railbed,levelCrossings);
    }


    public Options sides(Side a, Side b) {
      return ports(
          new Ports(
              a,
              b,
              ports.sourceOutset(),
              ports.targetOutset(),
              ports.sourceJoin(),
              ports.targetJoin()));
    }

    public Options outsets(double a, double b) {
      return ports(
          new Ports(ports.source(), ports.target(), a, b, ports.sourceJoin(), ports.targetJoin()));
    }

    public Options joins(JoinMode a, JoinMode b) {
      return ports(
          new Ports(
              ports.source(), ports.target(), ports.sourceOutset(), ports.targetOutset(), a, b));
    }

    public Options route(Routing value) {
      return new Options(
          leftTraffic,
          cycle,
          cycleRail,
          curb,
          liftPosition,
          liftHeight,
          ports,
          value,
          outerRail,
          ends, sidewalk, infrastructure,laneLines,cycleAsphalt,hideArrows,streetscape,attachments,lanePoints,lanes,railbed,levelCrossings);
    }

    public Options traffic(boolean value) {
      return new Options(
          value, cycle, cycleRail, curb, liftPosition, liftHeight, ports, routing, outerRail, ends, sidewalk, infrastructure,laneLines,cycleAsphalt,hideArrows,streetscape,attachments,lanePoints,lanes,railbed,levelCrossings);
    }

    public Options extras(boolean bike, boolean rail, boolean edge) {
      return new Options(
          leftTraffic, bike, rail, edge, liftPosition, liftHeight, ports, routing, outerRail, ends, sidewalk, infrastructure,laneLines,cycleAsphalt,hideArrows,rail!=cycleRail?streetscape.separator(rail?RoadStreetscape.Separator.RAIL:RoadStreetscape.Separator.LINE):streetscape,attachments,lanePoints,lanes,railbed,levelCrossings);
    }

    public Options lift(double pos, double height) {
      return new Options(
          leftTraffic, cycle, cycleRail, curb, pos, height, ports, routing, outerRail, ends, sidewalk, infrastructure,laneLines,cycleAsphalt,hideArrows,streetscape,attachments,lanePoints,lanes,railbed,levelCrossings);
    }
  }

  public record Catalog(Type type, int lanes, boolean twoWay, Median median, boolean shoulder) {
    public double laneWidth() {
      return type == Type.HIGHWAY ? 5 : 4;
    }

    public String name() {
      return (twoWay ? "双向" : "单向") + lanes + "车道";
    }
  }

  private static final class Catalogs {
    static final Catalog[] VALUES=Arrays.stream(Style.values()).map(RoadProfile::makeCatalog).toArray(Catalog[]::new);
  }
  /** Immutable catalog entries: do not allocate one for every sampled vertex/width query. */
  public static Catalog catalog(Style s) {return Catalogs.VALUES[s.ordinal()];}
  public static Catalog catalog(Style s,Options o) {
    Catalog c=catalog(s);var n=o.lanes();
    if(s.connectorRamp()&&o.lanePoints().link()!=null)return new Catalog(c.type(),o.lanePoints().link().options().lanes(),false,Median.NONE,false);
    return n.explicit() && (c.type()==Type.ORDINARY||c.type()==Type.HIGHWAY||s.connectorRamp())
        ?new Catalog(c.type(),n.total(),n.twoWay(),n.twoWay()?c.median():Median.NONE,c.shoulder()):c;
  }
  public static Catalog catalog(Settings s){return catalog(s.style(),s.options());}
  private static Catalog makeCatalog(Style s) {
    return switch (s) {
      case O8_YELLOW -> new Catalog(Type.ORDINARY, 8, true, Median.DOUBLE_YELLOW, false);
      case O8_RAIL -> new Catalog(Type.ORDINARY, 8, true, Median.RAIL, false);
      case O8_GREEN -> new Catalog(Type.ORDINARY, 8, true, Median.GREEN, false);
      case O4_ONE -> new Catalog(Type.ORDINARY, 4, false, Median.NONE, false);
      case H8_RAIL -> new Catalog(Type.HIGHWAY, 8, true, Median.RAIL, true);
      case H8_GREEN -> new Catalog(Type.HIGHWAY, 8, true, Median.GREEN, true);
      case H4_ONE -> new Catalog(Type.HIGHWAY, 4, false, Median.NONE, true);
      case O2_YELLOW -> new Catalog(Type.ORDINARY, 2, true, Median.DOUBLE_YELLOW, false);
      case O4_YELLOW -> new Catalog(Type.ORDINARY, 4, true, Median.DOUBLE_YELLOW, false);
      case O6_YELLOW -> new Catalog(Type.ORDINARY, 6, true, Median.DOUBLE_YELLOW, false);
      case O2_DASHED -> new Catalog(Type.ORDINARY, 2, true, Median.DASHED_YELLOW, false);
      case O2_RAIL -> new Catalog(Type.ORDINARY, 2, true, Median.RAIL, false);
      case O4_RAIL -> new Catalog(Type.ORDINARY, 4, true, Median.RAIL, false);
      case O6_RAIL -> new Catalog(Type.ORDINARY, 6, true, Median.RAIL, false);
      case O2_GREEN -> new Catalog(Type.ORDINARY, 2, true, Median.GREEN, false);
      case O4_GREEN -> new Catalog(Type.ORDINARY, 4, true, Median.GREEN, false);
      case O6_GREEN -> new Catalog(Type.ORDINARY, 6, true, Median.GREEN, false);
      case O1_ONE -> new Catalog(Type.ORDINARY, 1, false, Median.NONE, false);
      case O2_ONE -> new Catalog(Type.ORDINARY, 2, false, Median.NONE, false);
      case O3_ONE -> new Catalog(Type.ORDINARY, 3, false, Median.NONE, false);
      case H4_RAIL -> new Catalog(Type.HIGHWAY, 4, true, Median.RAIL, true);
      case H6_RAIL -> new Catalog(Type.HIGHWAY, 6, true, Median.RAIL, true);
      case H4_GREEN -> new Catalog(Type.HIGHWAY, 4, true, Median.GREEN, true);
      case H6_GREEN -> new Catalog(Type.HIGHWAY, 6, true, Median.GREEN, true);
      case H4_YELLOW -> new Catalog(Type.HIGHWAY, 4, true, Median.DOUBLE_YELLOW, true);
      case H6_YELLOW -> new Catalog(Type.HIGHWAY, 6, true, Median.DOUBLE_YELLOW, true);
      case H1_ONE -> new Catalog(Type.HIGHWAY, 1, false, Median.NONE, false);
      case H2_ONE -> new Catalog(Type.HIGHWAY, 2, false, Median.NONE, true);
      case H3_ONE -> new Catalog(Type.HIGHWAY, 3, false, Median.NONE, true);
      case C1_RAMP, C1_HIGHWAY_RAMP -> new Catalog(Type.RAMP,1,false,Median.NONE,false);
      case R1 -> new Catalog(Type.RAMP, 1, false, Median.NONE, false);
      case R2 -> new Catalog(Type.RAMP, 2, false, Median.NONE, false);
      case R1_SHOULDER -> new Catalog(Type.RAMP, 1, false, Median.NONE, true);
      case R2_SHOULDER -> new Catalog(Type.RAMP, 2, false, Median.NONE, true);
      default ->
          new Catalog(
              Type.LEGACY,
              s == Style.FOUR_LANE ? 4 : s == Style.RAMP_ONE ? 1 : 2,
              s == Style.TWO_LANE || s == Style.FOUR_LANE || s == Style.UNMARKED,
              Median.NONE,
              false);
    };
  }

  public static boolean highway(Style s) { return catalog(s).type()==Type.HIGHWAY||s==Style.C1_HIGHWAY_RAMP; }

  public static boolean modern(Style s) {
    return catalog(s).type() != Type.LEGACY;
  }

  /** Deprecated highway-yellow records still load; editing opens with the supported barrier. */
  public static Settings editable(Settings s) {
    if (s.style() != Style.H4_YELLOW && s.style() != Style.H6_YELLOW) return s;
    Style style = s.style() == Style.H4_YELLOW ? Style.H4_RAIL : Style.H6_RAIL;
    double width = width(style, s.options(), layout(s, s.width()).laneWidth());
    return new Settings(
        s.mode(),
        style,
        width,
        s.thickness(),
        s.tension(),
        s.arcDegrees(),
        s.startWidth() + width - s.width(),
        s.endWidth() + width - s.width(),
        s.structure(),
        s.taperVersion(),
        s.rampTurn(),
        s.options());
  }

  public static List<Style> styles(Type type) {
    return Arrays.stream(Style.values())
        .filter(s -> catalog(s).type() == type)
        .filter(s -> s != Style.H4_YELLOW && s != Style.H6_YELLOW && !s.connectorRamp() && s!=Style.TOLL && s!=Style.RAILBED)
        .toList();
  }

  public static Style choose(
      Type type, int lanes, boolean twoWay, Median median, boolean shoulder) {
    Median supportedMedian =
        type == Type.HIGHWAY && twoWay && median != Median.GREEN ? Median.RAIL : median;
    return styles(type).stream()
        .filter(
            s -> {
              var c = catalog(s);
              return c.lanes() == lanes
                  && c.twoWay() == twoWay
                  && c.median() == supportedMedian
                  && c.shoulder() == shoulder;
            })
        .findFirst()
        .orElseThrow();
  }

  public static double width(Style style, Options o, double laneWidth) {
    if(style.connectorRamp())return laneWidth;
    var c = catalog(style,o);
    int sides = c.twoWay() ? 2 : 1;
    return c.lanes() * laneWidth
        + medianWidth(c,o)
        + 1
        + (c.shoulder() ? 3 * sides : 0)
        + (c.type() == Type.ORDINARY
            ? (o.cycle() ? (2.5+RoadStreetscape.separatorWidth(o)) * sides : 0) + (o.curb() ? .5 * sides : 0)
            : 0);
  }

  public static double medianWidth(Catalog c,Options o) {
    return c.median()==Median.GREEN?o.streetscape().plantings().greenWidth()
        :c.median()==Median.RAIL?o.streetscape().plantings().railWidth():0;
  }

  public static double medianWidth(Catalog c) {
    return c.median() == Median.RAIL ? 1 : c.median() == Median.GREEN ? 3 : 0;
  }

  /** Offsets follow Sample.left; asymmetric one-way profiles mirror with traffic side. */
  public record Layout(
      Catalog catalog,
      double laneWidth,
      double median,
      double motorMin,
      double motorMax,
      double cycleWidth,
      double curbWidth,
      double shoulderWidth,
      int outside,
      List<Double> transitionDividers, double medianCenter) {
    public Layout(Catalog catalog, double laneWidth, double median, double motorMin, double motorMax,
        double cycleWidth, double curbWidth, double shoulderWidth, int outside, List<Double> dividers) {
      this(catalog,laneWidth,median,motorMin,motorMax,cycleWidth,curbWidth,shoulderWidth,outside,dividers,0);
    }
    public double medianEdge(int side) { return medianCenter + side * median / 2; }
    public int lanesOnSide(int side) {
      if(!catalog.twoWay())return catalog.lanes();
      double span=side<0?medianEdge(-1)-motorMin:motorMax-medianEdge(1);
      return Math.max(0,(int)Math.floor(span/Math.max(.001,laneWidth)+.5));
    }
    public double outer(int side) {
      return side < 0 ? motorMin : motorMax;
    }

    public double motorCenter() {
      return (motorMin + motorMax) / 2;
    }

    public int[] outsideSides() {
      return catalog.twoWay() ? new int[] {-1, 1} : new int[] {outside};
    }

    public List<Double> dividers() {
      if (transitionDividers != null) return transitionDividers;
      List<Double> out = new ArrayList<>();
      if (catalog.twoWay())
        for (int sign : new int[] {-1, 1})
          for (int n = 1; n < lanesOnSide(sign); n++)
            out.add(medianCenter + sign * (median / 2 + n * laneWidth));
      else for (int n = 1; n < catalog.lanes(); n++) out.add(motorMin + n * laneWidth);
      return out;
    }

    public double attachment(int side) {
      return outer(side) + side * (shoulderWidth > 0 ? shoulderWidth / 2 : -.25);
    }
  }

  public static Layout layout(Settings s, double actualWidth) {
    var c = catalog(s);
    var o = s.options();
    // New precise lane connectors have one centred driveable strip. Inherit neither
    // ordinary-road verge nor the highway's asymmetric shoulder at a four-block lane port.
    // The persisted marker keeps old saved meshes unchanged until an explicit edit.
    if(s.style().connectorRamp()||o.lanePoints().link()!=null&&o.lanePoints().link().protectedMerge()&&c.lanes()==1){
      var p=o.ends().port();
      double low=p==null?-actualWidth/2:p.motorMin(),high=p==null?actualWidth/2:p.motorMax();
      return new Layout(new Catalog(c.type(),c.lanes(),false,Median.NONE,false),(high-low)/c.lanes(),0,low,high,0,0,0,trafficSign(o.leftTraffic()),null);
    }
    if(o.lanes().explicit() && o.ends().port()!=null){
      var p=o.ends().port();var clean=s.options(o.ends(RoadTransitions.Ends.NONE));
      var b=layout(clean,actualWidth);
      return new Layout(c,(p.motorMax()-p.motorMin()-p.median())/c.lanes(),p.median(),p.motorMin(),p.motorMax(),b.cycleWidth(),b.curbWidth(),b.shoulderWidth(),b.outside(),p.dividers(),p.medianCenter());
    }
    int sides = c.twoWay() ? 2 : 1, outside = trafficSign(o.leftTraffic());
    double cycle = c.type() == Type.ORDINARY && o.cycle() ? 2.5+RoadStreetscape.separatorWidth(o) : 0,
        curb = c.type() == Type.ORDINARY && o.curb() ? .5 : 0;
    double shoulder = c.shoulder() ? 3 : 0, median = medianWidth(c,o);
    double sectionWidth=o.lanePoints().link()!=null&&c.lanes()==1?actualWidth:s.width();
    double nominal = (sectionWidth - median - 1 - (cycle + curb + shoulder) * sides) / c.lanes();
    double scale = s.style().ramp() ? actualWidth / s.width() : 1,
        lw = nominal * scale,
        m = median * scale;
    double center = c.twoWay() ? 0 : -outside * (cycle + curb + shoulder) * scale / 2;
    double half = (lw * c.lanes() + m) / 2;
    return new Layout(
        c,
        lw,
        m,
        center - half,
        center + half,
        cycle * scale,
        curb * scale,
        shoulder * scale,
        outside,
        null,
        c.twoWay()&&o.lanes().explicit()?(o.leftTraffic()?o.lanes().forward()-o.lanes().reverse():o.lanes().reverse()-o.lanes().forward())*lw/2:0);
  }

  public static Layout layout(Mesh mesh, Sample sample) {
    var bands=mesh.settings().options().lanePoints().bands();
    if(mesh.settings().style().connectorRamp()&&!bands.isEmpty()){
      double d=sample.distance()+mesh.settings().options().ends().trimmedStart();
      int lo=0,hi=bands.size()-1;while(lo+1<hi){int m=(lo+hi)>>>1;if(bands.get(m).station()<=d)lo=m;else hi=m;}
      var a=bands.get(lo);var b=bands.get(hi);double t=hi==lo?0:Math.max(0,Math.min(1,(d-a.station())/(b.station()-a.station())));
      double low=a.low()+(b.low()-a.low())*t,high=a.high()+(b.high()-a.high())*t;
      int count=catalog(mesh.settings()).lanes();return new Layout(new Catalog(Type.RAMP,count,false,Median.NONE,false),(high-low)/count,0,low,high,0,0,0,trafficSign(mesh.settings().options().leftTraffic()),count==2?List.of((low+high)/2):List.of(),0);
    }
    var reserved=LaneSections.layout(mesh,sample);
    var base=reserved!=null?reserved:RoadTransitions.layout(mesh,sample);
    if(mesh.settings().structure()==Structure.TUNNEL)return base.catalog().median()==Median.GREEN?RaisedRoadProfile.apply(base,1,RoadTransitions.railWidth(mesh,sample)):base;
    if(base.catalog().median()==Median.GREEN&&RoadStreetscape.tunnelBlend(mesh,sample)>0)
      return RaisedRoadProfile.apply(base,Math.max(RoadStreetscape.tunnelBlend(mesh,sample),RoadStreetscape.medianConversionBlend(mesh,sample)),RoadTransitions.railWidth(mesh,sample));
    if(reserved!=null||!base.catalog().twoWay())return base;
    return RaisedRoadProfile.apply(base,RoadStreetscape.medianConversionBlend(mesh,sample),RoadTransitions.railWidth(mesh,sample));
  }

  /** Physical raised curb excludes the shoulder and fades out with the curb option. */
  public static double curbExtent(Layout layout,Sample sample,int side) {
    if(layout.curbWidth()<=0||!layout.catalog().twoWay()&&layout.outside()!=side)return 0;
    double available=sample.halfWidth()-Math.abs(layout.outer(side))-layout.cycleWidth()-layout.shoulderWidth();
    return Math.max(0,Math.min(available,2*layout.curbWidth()));
  }

  public static String medianName(Median m) {
    return switch (m) {
      case DOUBLE_YELLOW -> "双黄实线";
      case DASHED_YELLOW -> "黄虚线";
      case RAIL -> "中央护栏";
      case GREEN -> "中央绿化带";
      case NONE -> "无中央隔离";
    };
  }

  private RoadProfile() {}
}
