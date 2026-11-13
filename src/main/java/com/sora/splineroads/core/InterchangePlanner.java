package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadProfile.*;
import java.util.*;

/** Deterministic complete interchange plan; preview and server use exactly the same inputs. */
public final class InterchangePlanner {
  public enum Preset {
    CLOVERLEAF("苜蓿叶 · 四向", false, 2),
    TURBINE("涡轮 · 四向", false, 5),
    STACK("堆栈 · 四向", false, 3),
    HYBRID("苜蓿叶＋定向 · 四向", false, 3),
    TRUMPET("喇叭 · 三向", true, 3),
    DIRECTIONAL_T("定向 T · 三向", true, 3),
    Y("定向 Y · 三向", true, 3),
    DIAMOND("菱形 · 四向", false, 1),
    PARCLO("部分苜蓿叶 · 四向", false, 2),
    DOUBLE_TRUMPET("双喇叭 · 四向", false, 3),
    ROUNDABOUT("三层环形 · 四向", false, 2),
    SPUI("单点式菱形 · 四向", false, 1),
    DIRECTIONAL_FIVE("全定向堆栈 · 五向", false, 3),
    DIRECTIONAL_SIX("全定向堆栈 · 六向", false, 3);
    public final String label;
    public final boolean three;

    /** Retain enum IDs only for loading old worlds; retired layouts cannot be generated. */
    public boolean supported() {
      return this != PARCLO;
    }

    public boolean multi(){return this==DIRECTIONAL_FIVE||this==DIRECTIONAL_SIX;}

    public final int heightSlots;

    Preset(String label, boolean three, int heightSlots) {
      this.label = label;
      this.three = three;
      this.heightSlots = heightSlots;
    }
  }

  /** Only common terminating layouts are offered for three real anchors. */
  public static List<Preset> presets(int anchors) {
    if(anchors==5)return List.of(Preset.DIRECTIONAL_FIVE);
    if(anchors==6)return List.of(Preset.DIRECTIONAL_SIX);
    if (anchors != 3 && anchors != 4) throw new IllegalArgumentException("请选择三至六个端点");
    return Arrays.stream(Preset.values())
        .filter(p -> p.supported() && p!=Preset.DIRECTIONAL_FIVE && p!=Preset.DIRECTIONAL_SIX && p.three == (anchors == 3))
        .toList();
  }

  public static Node[] expand(Node[] anchors, Options options) {
    if (anchors.length == 4) return anchors.clone(); // editable 0.8 three-way descriptors
    if (anchors.length != 3) throw new IllegalArgumentException("请选择 A/B 与 C，或 A/B 与 C/D");
    if (!options.preset.three) throw new IllegalArgumentException("三个端点只能选择三向立交");
    V a = anchors[0].position(), b = anchors[1].position(), c = anchors[2].position();
    V ab = b.sub(a).horizontalUnit();
    V foot = a.add(ab.mul(c.sub(a).dot(ab)));
    V d = new V(2 * foot.x() - c.x(), c.y(), 2 * foot.z() - c.z());
    return new Node[] {anchors[0], anchors[1], anchors[2], new Node(d, anchors[2].yaw(), 0)};
  }

  public record Options(
      Preset preset,
      boolean leftTraffic,
      int lanes,
      double transition,
      double radius,
      double clearance,
      int upper,
      double rampWidth,
      boolean adjustEndpoints,
      boolean allowShrink,
      double maxLowering,
      int junctionAxis, int loopMask, boolean allowAbove, boolean flatConnector, double connectorHeight, DoubleTrumpetToll.Config toll) {
    public Options(Preset preset,boolean leftTraffic,int lanes,double transition,double radius,double clearance,int upper,double rampWidth,boolean adjustEndpoints,boolean allowShrink,double maxLowering,int junctionAxis,int loopMask,boolean allowAbove,boolean flatConnector,double connectorHeight){
      this(preset,leftTraffic,lanes,transition,radius,clearance,upper,rampWidth,adjustEndpoints,allowShrink,maxLowering,junctionAxis,loopMask,allowAbove,flatConnector,connectorHeight,DoubleTrumpetToll.Config.OFF);
    }
    public Options(Preset preset,boolean leftTraffic,int lanes,double transition,double radius,double clearance,int upper,double rampWidth,boolean adjustEndpoints,boolean allowShrink,double maxLowering,int junctionAxis,int loopMask){
      this(preset,leftTraffic,lanes,transition,radius,clearance,upper,rampWidth,adjustEndpoints,allowShrink,maxLowering,junctionAxis,loopMask,false,false,0);
    }
    public Options(Preset preset,boolean leftTraffic,int lanes,double transition,double radius,double clearance,int upper,double rampWidth,boolean adjustEndpoints,boolean allowShrink,double maxLowering,int junctionAxis){
      this(preset,leftTraffic,lanes,transition,radius,clearance,upper,rampWidth,adjustEndpoints,allowShrink,maxLowering,junctionAxis,5);
    }
    /** Old descriptors keep their C/D intersection. */
    public Options(Preset preset, boolean leftTraffic, int lanes, double transition,
        double radius, double clearance, int upper, double rampWidth,
        boolean adjustEndpoints, boolean allowShrink, double maxLowering) {
      this(preset,leftTraffic,lanes,transition,radius,clearance,upper,rampWidth,adjustEndpoints,allowShrink,maxLowering,1);
    }
    public Options(Preset preset, boolean leftTraffic, int lanes, double transition,
        double radius, double clearance, int upper, double rampWidth,
        boolean adjustEndpoints, boolean allowShrink) {
      this(preset, leftTraffic, lanes, transition, radius, clearance, upper, rampWidth,
          adjustEndpoints, allowShrink, 8);
    }
    public Options(
        Preset preset,
        boolean leftTraffic,
        int lanes,
        double transition,
        double radius,
        double clearance,
        int upper,
        double rampWidth,
        boolean adjustEndpoints) {
      this(
          preset,
          leftTraffic,
          lanes,
          transition,
          radius,
          clearance,
          upper,
          rampWidth,
          adjustEndpoints,
          false);
    }

    public Options(
        Preset preset,
        boolean leftTraffic,
        int lanes,
        double transition,
        double radius,
        double clearance,
        int upper) {
      this(preset, leftTraffic, lanes, transition, radius, clearance, upper, 0, false);
    }

    public double width() {
      return rampWidth == 0 ? (lanes == 1 ? 5 : 9) : rampWidth;
    }

    public Options adjust(boolean value) {
      return new Options(
          preset,
          leftTraffic,
          lanes,
          transition,
          radius,
          clearance,
          upper,
          rampWidth,
          value,
          allowShrink,
          maxLowering, junctionAxis, loopMask,allowAbove,flatConnector,connectorHeight,toll);
    }

    public Options shrink(boolean value) {
      return new Options(
          preset,
          leftTraffic,
          lanes,
          transition,
          radius,
          clearance,
          upper,
          rampWidth,
          adjustEndpoints || value,
          value,
          maxLowering, junctionAxis, loopMask,allowAbove,flatConnector,connectorHeight,toll);
    }

    public Options lowering(double value) {
      return new Options(preset, leftTraffic, lanes, transition, radius, clearance, upper,
          rampWidth, adjustEndpoints, allowShrink, value, junctionAxis, loopMask,allowAbove,flatConnector,connectorHeight,toll);
    }

    public Options junction(int axis) {
      return new Options(preset,leftTraffic,lanes,transition,radius,clearance,upper,
          rampWidth,adjustEndpoints,allowShrink,maxLowering,axis,loopMask,allowAbove,flatConnector,connectorHeight,toll);
    }

    public Options loops(int mask){return new Options(preset,leftTraffic,lanes,transition,radius,clearance,upper,rampWidth,adjustEndpoints,allowShrink,maxLowering,junctionAxis,mask,allowAbove,flatConnector,connectorHeight,toll);}
    public boolean aboveAvailable(){return preset==Preset.TURBINE||preset==Preset.HYBRID;}
    public Options above(boolean value){return new Options(preset,leftTraffic,lanes,transition,radius,clearance,upper,rampWidth,adjustEndpoints,allowShrink,maxLowering,junctionAxis,loopMask,value,flatConnector,connectorHeight,toll);}
    public Options connector(boolean flat,double height){return new Options(preset,leftTraffic,lanes,transition,radius,clearance,upper,rampWidth,adjustEndpoints,allowShrink,maxLowering,junctionAxis,loopMask,allowAbove,flat,height,toll);}

    public Options toll(DoubleTrumpetToll.Config value){return new Options(preset,leftTraffic,lanes,transition,radius,clearance,upper,rampWidth,adjustEndpoints,allowShrink,maxLowering,junctionAxis,loopMask,allowAbove,flatConnector,connectorHeight,value);}

    public static final Options DEFAULT = new Options(Preset.CLOVERLEAF, false, 1, 96, 20, 5, 2);

    public Options {
      toll=toll==null?DoubleTrumpetToll.Config.OFF:toll;
      if(preset!=Preset.DOUBLE_TRUMPET)toll=DoubleTrumpetToll.Config.OFF;
      allowAbove=allowAbove&&(preset==Preset.TURBINE||preset==Preset.HYBRID);
      flatConnector=flatConnector&&preset==Preset.DOUBLE_TRUMPET;
      if(!Double.isFinite(connectorHeight)||connectorHeight!=Math.rint(connectorHeight))throw new IllegalArgumentException("双喇叭水平段高度必须为整数");
      if (loopMask<0 || loopMask>15 || Integer.bitCount(loopMask)!=2 || junctionAxis < 0 || junctionAxis > 1 || preset == null
          || lanes < 1
          || lanes > 2
          || !RoadGeometry.finite(transition, radius, clearance, rampWidth, maxLowering)
          || maxLowering < 0 || maxLowering > 64
          || rampWidth != 0 && (rampWidth < lanes * 4 || rampWidth > 32)
          || transition < 24
          || transition > 192
          || radius < 12
          || radius > 64
          || clearance < 4
          || clearance > 12
          || upper < 1
          || upper > 2)
        throw new IllegalArgumentException(
            "立交参数无效：匝道宽度每车道至少 4 格、总宽不超过 32；过渡 24–192（实际至少 80）、半径 12–64、净高 4–12 格");
    }
  }

  public record Leg(String name, int from, int to, Mesh mesh, JunctionPlanner.Ref junction,TollPlaza.Spec toll) {
    public Leg(String name,int from,int to,Mesh mesh){this(name,from,to,mesh,null,null);}
    public Leg(String name,int from,int to,Mesh mesh,JunctionPlanner.Ref junction){this(name,from,to,mesh,junction,null);}
  }

  public record Plan(
      List<Leg> legs,
      int movements,
      double minRadius,
      double highest,
      V center,
      List<Node> anchors) {
    public Plan(List<Leg> legs, int movements, double minRadius, double highest, V center) {
      this(legs, movements, minRadius, highest, center, List.of());
    }
  }

  private record Knot(V p, V direction) {}

  private static final V[] ARMS = {
    new V(-1, 0, 0), new V(0, 0, -1), new V(1, 0, 0), new V(0, 0, 1)
  };
  private final Node[] nodes;
  private final Settings[] main;
  private final Options options;
  private final V center, u, v;
  private final double[] lengths = new double[4];
  private final int traffic;
  private final double base, step, loopRadius;
  private final double[] heights;
  private final List<Leg> roads = new ArrayList<>();
  private final List<Route> routes = new ArrayList<>();
  private final List<Double> serviceCenters = new ArrayList<>();
  private double directCurve = .40;
  private final Map<Mesh,Map<Mesh,double[]>> mouthSides=new IdentityHashMap<>();
  private double cornerScale = 1;
  private static final class CornerRadiusFailure extends IllegalArgumentException { CornerRadiusFailure(String message){super(message);} }
  private final java.util.function.UnaryOperator<V> conformance;

  private static final class Route {
    String name;
    int from, to, leadEnd, tailStart, priority, leadCut, tailCut;
    List<V> points;
    List<Double> widths;
    int plateauStart = -1, plateauEnd = -1;
    double layer;

    Route(String n, int f, int t, List<V> p, List<Double> w, int a, int b, int priority) {
      this.priority = priority;
      name = n;
      from = f;
      to = t;
      points = p;
      widths = w;
      leadEnd = leadCut = a;
      tailStart = tailCut = b;
    }
  }

  private InterchangePlanner(Node[] nodes, Settings a, Settings b, Options options,java.util.function.UnaryOperator<V> conformance) {
    this.conformance=conformance;
    if (!options.preset.supported()) throw new IllegalArgumentException("此立交预设已移除，请改选其他布局");
    this.nodes = nodes.clone();
    this.main = new Settings[] {a, b};
    this.options = options;
    for (Settings s : main) s.validate();
    for (Settings s : main)
      if (s.style().ramp() || !RoadProfile.catalog(s).twoWay())
        throw new IllegalArgumentException("预设立交的两条主路需要双向道路");
    if(options.preset==Preset.DIAMOND||options.preset==Preset.SPUI){
      if(RoadProfile.catalog(a).type()==Type.HIGHWAY&&RoadProfile.catalog(b).type()==Type.HIGHWAY)
        throw new IllegalArgumentException("AB、CD 均为高速，不能使用菱形或单点菱形立交");
      if(RoadProfile.catalog(main[options.junctionAxis]).type()==Type.HIGHWAY)
        throw new IllegalArgumentException("菱形／单点菱形的路口只能设置在非高速主路上，请切换路口主路");
    }
    V ab = nodes[1].position().sub(nodes[0].position()),
        cd = nodes[3].position().sub(nodes[2].position());
    if (ab.horizontalLength() < 100
        || cd.horizontalLength() < 70
        || ab.horizontalLength()
            > (options.preset == Preset.DOUBLE_TRUMPET ? 4096 : RoadLimits.MAX_ENDPOINT_DISTANCE) + 1e-6
        || cd.horizontalLength()
            > (options.preset == Preset.DOUBLE_TRUMPET || options.preset.three
                ? 4096
                : RoadLimits.MAX_ENDPOINT_DISTANCE) + 1e-6)
      throw new IllegalArgumentException(
          options.preset == Preset.DOUBLE_TRUMPET
              ? "双喇叭两轴总长上限 4096 格"
              : "AB 总长需为 100–2048 格，四向 CD 总长 70–2048 格，三向支路不超过 2048 格");
    u = ab.horizontalUnit();
    v = cd.horizontalUnit();
    double det = cross(u, v);
    if (Math.abs(det) < .9) throw new IllegalArgumentException("预设立交需要两条约垂直的主路，夹角需为 65°–115°");
    V delta = nodes[2].position().sub(nodes[0].position());
    double along = cross(delta, v) / det;
    center = nodes[0].position().add(u.mul(along));
    lengths[0] = along;
    lengths[2] = ab.horizontalLength() - along;
    double cv = center.sub(nodes[2].position()).dot(v);
    lengths[1] = cv;
    lengths[3] = cd.horizontalLength() - cv;
    if (Math.min(lengths[0], lengths[2]) < 45
        || lengths[1] < 45
        || (!options.preset.three && lengths[3] < 45))
      throw new IllegalArgumentException("两条主路需要在端点之间相交，交点离各有效方向端点至少 45 格");
    if (options.preset.three) lengths[3] = Math.max(lengths[1], Math.min(lengths[0], lengths[2]));
    traffic = (options.leftTraffic ? -1 : 1) * (det < 0 ? -1 : 1);
    var levels = levels(nodes, a, b, options);
    heights = new double[] {levels.first(), levels.second()};
    base = Math.min(heights[0], heights[1]);
    step = layeredDifference(a, b, options) / options.preset.heightSlots;
    double gap = Math.abs(heights[0] - heights[1]);
    double sweep = Math.PI + Math.asin(Math.min(1, Math.abs(det)));
    loopRadius =
        Math.max(
            options.radius,
            (options.preset == Preset.CLOVERLEAF || options.preset == Preset.HYBRID)
                ? (gap * 1.25 / RoadGrades.MAX_RAMP_GRADE + 42) / sweep + 2
                : Math.max(gap, options.preset.three ? layeredDifference(a, b, options) : 0) * 2.5);
  }

  public static Plan plan(Node[] nodes, Settings a, Settings b, Options options) {
    return plan(nodes,a,b,options,null);
  }
  /** Curve conformance reserves the complete pavement boundary before assigning vertical plateaus. */
  public static Plan plan(Node[] nodes,Settings a,Settings b,Options options,java.util.function.UnaryOperator<V> conformance){
    RoadPlanningBudget.check();
    if(nodes.length>=5)return MultiInterchange.plan(nodes,new Settings[]{a,b,b},options);
    if(options.preset().multi())throw new IllegalArgumentException("五、六向布局需要对应数量的端点");
    if (options.adjustEndpoints) return InterchangeFit.fit(nodes, a, b, options,conformance);
    if(options.preset==Preset.DOUBLE_TRUMPET)return DoubleTrumpetPlanner.plan(nodes,a,b,options);
    IllegalArgumentException first = null;
    // Adapt the continuous curve to leaf crossings and the available descent distance.
    // Every candidate still runs the complete radius, grade and clearance validation.
    double[] curves = options.preset == Preset.DIAMOND || options.preset == Preset.SPUI || options.preset == Preset.ROUNDABOUT
        ? new double[] {.40} : options.preset == Preset.CLOVERLEAF
        ? new double[] {.40, .52, .56, .60, .36, .32, .28, .24, .20, .16, .12, .64, .68, .72}
        : new double[] {.40, .36, .32, .28, .24, .20, .16, .52, .60};
    double[] cornerScales=conformance!=null&&options.preset==Preset.STACK?new double[]{1,1.25,1.5,2,3,4,6,8}:new double[]{1};
    for(double corner:cornerScales)for (double curve : curves) {
      RoadPlanningBudget.check();
      var planner = new InterchangePlanner(expand(nodes, options), a, b, options,conformance);
      planner.directCurve = curve;
      planner.cornerScale = corner;
      try {
        var result = planner.build();
        // A fitted candidate is successful only after checking its mapped, physical geometry.
        // Otherwise endpoint fitting stops early and the caller retries the same invalid footprint.
        if(conformance!=null)CurvedRoadPlans.validate(result.legs,options);
        return new Plan(result.legs, result.movements, result.minRadius, result.highest,
            result.center, List.copyOf(Arrays.asList(nodes)));
      } catch (IllegalArgumentException error) {
        if (Boolean.getBoolean("sr.curveDebug")) System.err.println(options.preset+" curve="+curve+" "+error.getMessage());
        if (first == null) first = error;
        // Direct-curve tension changes right turns only. Do not repeat an identical
        // failing left turn nine times before trying a wider central bend.
        if(error instanceof CornerRadiusFailure)break;
      }
    }
    throw first;
  }

  private V world(V p) {
    return center.add(u.mul(p.x())).add(v.mul(p.z())).add(new V(0, p.y() - center.y(), 0));
  }

  private V local(V p) {
    V q = p.sub(center);
    double d = cross(u, v);
    return new V(cross(q, v) / d, p.y(), cross(u, q) / d);
  }

  private V direction(V d) {
    return u.mul(d.x()).add(v.mul(d.z())).horizontalUnit();
  }

  public record Levels(double first, double second, double difference, double required) {}

  public static Levels levels(Node[] nodes, Settings a, Settings b, Options options) {
    nodes = expand(nodes, options);
    for (Node node : nodes)
      if (!RoadGeometry.finite(
          node.position().x(), node.position().y(), node.position().z(), node.grade(), node.yaw()))
        throw new IllegalArgumentException("立交端点数值无效");
    double y1 = (nodes[0].position().y() + nodes[1].position().y()) / 2,
        y2 = (nodes[2].position().y() + nodes[3].position().y()) / 2;
    for (int i = 0; i < 4; i += 2)
      if (Math.abs(nodes[i].position().y() - nodes[i + 1].position().y()) > 1e-6)
        throw new IllegalArgumentException("自动立交要求 AB、CD 各自等高；请先调平主路端点");
    double required = minimumDifference(a, b, options), gap = Math.abs(y1 - y2);
    if (gap + 1e-6 < required) {
      String upper = y1 > y2 ? "AB" : "CD";
      throw new IllegalArgumentException(
          String.format(
              Locale.ROOT,
              "%s：当前高差 %.1f 格，最低 %.1f 格；请将 %s 再抬高 %.1f 格",
              options.preset.label,
              gap,
              required,
              upper,
              required - gap));
    }
    return new Levels(y1, y2, gap, required);
  }

  /** Three-way hosts may be level; the ramps can cross above both hosts. */
  public static double minimumDifference(Settings a, Settings b, Options options) {
    if(options.preset.three)return 0;
    // Once intermediate ramps may climb above the upper host, only the two
    // crossing main decks have to fit between these two elevations.
    if(options.allowAbove||options.preset==Preset.DOUBLE_TRUMPET)return Math.ceil(options.clearance+Math.max(1,Math.max(a.thickness(),b.thickness()))+.3);
    return layeredDifference(a,b,options);
  }

  /** Gap needed to fit the intermediate decks wholly between the host elevations. */
  public static double layeredDifference(Settings a, Settings b, Options options) {
    int slots = options.preset == Preset.CLOVERLEAF ? 1 : options.preset.heightSlots;
    return Math.ceil(
        slots * (options.clearance + Math.max(1, Math.max(a.thickness(), b.thickness())) + .3)
            + (options.preset == Preset.SPUI ? 4 : 0));
  }

  private double height(int axis, double coordinate) {
    return heights[axis];
  }

  private Settings setting(int axis) {
    var s = main[axis];
    return s.options(
            s.options()
                .traffic(options.leftTraffic)
                .route(s.options().routing().fit(false))
                .ends(RoadTransitions.Ends.NONE))
        .structure(s.structure()==Structure.BRIDGE||s.structure()==Structure.TUNNEL?s.structure():Structure.AUTO);
  }

  private Plan build() {
    for (int axis = 0; axis < 2; axis++) {
      RoadPlanningBudget.check();
      double lo = -lengths[axis == 0 ? 0 : 1],
          hi = axis == 1 && options.preset.three ? -terminalStation() : lengths[axis == 0 ? 2 : 3];
      Settings settings = setting(axis);
      if (axis == 1 && options.preset.three) {
        settings = RoadTransitions.ends(settings, null, terminalSection());
        settings =
            settings.options(
                settings.options().ends(new RoadTransitions.Ends(null, terminalSection(), true)));
      }
      List<Sample> samples = new ArrayList<>();
      int count = (int) Math.ceil((hi - lo) / .5);
      for (int i = 0; i <= count; i++) {
        if((i&63)==0)RoadPlanningBudget.check();
        double x = lo + (hi - lo) * i / count;
        V p = world(axis == 0 ? new V(x, height(axis, x), 0) : new V(0, height(axis, x), x));
        V d = axis == 0 ? u : v;
        samples.add(
            new Sample(
                p,
                d.left(),
                0,
                (axis == 1 && options.preset.three
                    ? RoadTransitions.width(settings, x - lo, hi - lo) / 2
                    : main[axis].width() / 2 + padding(axis))));
      }
      roads.add(
          new Leg(
              "主路 " + (axis + 1),
              axis == 0 ? 0 : 1,
              axis == 0 ? 2 : 3,
              RoadRibbon.mesh(samples, settings)));
    }
    if (options.preset == Preset.ROUNDABOUT) return roundabout();
    if (servicePreset()) return service();
    if (collectorLoops()) addCollectors();
    int active = options.preset.three ? 3 : 4;
    for (int from = 0; from < active; from++)
      for (int to = 0; to < active; to++) {
        RoadPlanningBudget.check();
        if (to == from || Math.abs(from - to) == 2) continue;
        boolean right = ((to - from + 4) % 4) == (traffic > 0 ? 3 : 1);
        boolean leaf =
            !right
                && (options.preset == Preset.CLOVERLEAF
                    || options.preset == Preset.HYBRID && (options.loopMask & (1<<from)) != 0
                    || options.preset == Preset.TRUMPET && from == 1);
        routes.add(route(from, to, right, leaf));
      }
    if(conformance!=null){
      // Fit elevations against the ACTUAL curved hosts and ramp lengths, not a deformed result
      // whose already-finalised vertical profile could become too steep or clip a road edge.
      for(int i=0;i<roads.size();i++){RoadPlanningBudget.check();var leg=roads.get(i);for(Sample sample:leg.mesh().samples())checkBounds(sample.center());roads.set(i,new Leg(leg.name(),leg.from(),leg.to(),CurvedRoadPlans.map(leg.mesh(),conformance)));}
      for(Route route:routes){RoadPlanningBudget.check();for(V p:route.points)checkBounds(p);route.points=route.points.stream().map(p->{RoadPlanningBudget.check();return conformance.apply(p);}).toList();}
      for(Route route:routes){RoadPlanningBudget.check();double radius=RoadRibbon.minRadius(horizontal(route));
        if(radius+.1<options.radius){String message=String.format(Locale.ROOT,"%s 实际转弯半径 %.1f 格，要求 %.1f 格",route.name,radius,options.radius);
          if(options.preset==Preset.STACK&&route.priority==2)throw new CornerRadiusFailure(message);
          throw new IllegalArgumentException(message);}}
    }
    routes.sort(java.util.Comparator.comparing(r -> r.priority));
    // Keep a shared entrance/exit flat until neighboring branches have actually separated.
    for (Route r : routes) extendThroat(r);
    // One horizontal contact pass fixes the preset layers; no vertical retry/search loop.
    List<Mesh> flat = routes.stream().map(this::horizontal).toList();
    if(collectorLoops())for(int i=0;i<routes.size();i++)if(routes.get(i).priority==0)
      for(int j=0;j<routes.size();j++)if(routes.get(j).priority==1&&!CloverleafEnvelope.clear(flat.get(i),flat.get(j)))
        throw new IllegalArgumentException("苜蓿右转匝道必须绕过左转圆环外侧；请扩大范围或减小回环半径");
    List<Mesh> mains = roads.stream().map(Leg::mesh).toList();
    int mainCount = mains.size();
    for (int i = 0; i < routes.size(); i++) {
      RoadPlanningBudget.check();
      Route r = routes.get(i);
      int slot =
          r.priority < 2
              ? 1
              : switch (options.preset) {
                case TURBINE -> r.from + 1;
                case STACK -> r.from % 2 + 1;
                case HYBRID -> 2;
                case DOUBLE_TRUMPET -> r.name.startsWith("C") ? 1 : 2;
                case TRUMPET, DIRECTIONAL_T, Y -> r.from == 1 ? 2 : 1;
                default -> 1;
              };
      if(options.preset==Preset.HYBRID&&r.priority>0)slot=heights[r.from%2]>heights[r.to%2]?1:2;
      // The trumpet's loop uses the second intermediate deck.
      if ((options.preset == Preset.TRUMPET || options.preset == Preset.DOUBLE_TRUMPET)
          && r.priority == 1) slot = 2;
      r.layer =
          options.preset == Preset.STACK && r.priority == 2
              ? base
                  + Math.abs(heights[0] - heights[1])
                      * (heights[r.from % 2] < heights[r.to % 2] ? 1 : 2)
                      / 3
              : options.allowAbove || options.preset.three && Math.abs(heights[0]-heights[1])+1e-6 < layeredDifference(main[0],main[1],options)
                  ? Math.max(heights[0],heights[1]) + slot * step
                  : base + slot * step;
      List<Mesh> others = new ArrayList<>(mains);
      others.addAll(flat);
      for (int k = 0; k < others.size(); k++) {
        RoadPlanningBudget.check();
        if (k == i + mainCount
            || r.priority < 2
                && k >= mainCount
                && !(r.priority == 1
                    && options.preset == Preset.TRUMPET
                    && routes.get(k - mainCount).priority >= 2)) continue;
        Mesh other = others.get(k);
        for (int j = 0; j < flat.get(i).samples().size(); j += 2) {
          if((j&63)==0)RoadPlanningBudget.check();
          Sample p = flat.get(i).samples().get(j);
          var q = overlap(other, p);
          if (q == null
              || shared(r, j, p, k < mainCount ? null : routes.get(k - mainCount), k, other, q))
            continue;
          if (j <= r.leadEnd || j >= r.tailStart) continue;
          r.plateauStart = r.plateauStart < 0 ? j : Math.min(r.plateauStart, j);
          r.plateauEnd = Math.max(r.plateauEnd, j);
        }
      }
    }
    List<Mesh> ramps = elevateCrossings(flat);
    for(var mesh:ramps)RoadRibbon.checkSelfIntersections(mesh,options.clearance);
    // Sampling is useful while choosing a candidate, but only complete clipped
    // pavement triangles can certify the final edge clearance. Validate before
    // shared ports are split, while each route still has its declared mouth spans.
    validateExactClearance(ramps);
    for (int i = 0; i < ramps.size(); i++) {
      var r = routes.get(i);
      addWithSharedPorts(r, ramps.get(i));
    }
    double min = ramps.stream().mapToDouble(RoadRibbon::minRadius).min().orElse(0),
        highest = roads.stream().mapToDouble(l -> l.mesh.max().y()).max().orElse(base);
    if(conformance==null)for(Leg road:roads)for(Sample p:road.mesh.samples())checkBounds(p.center());
    for (Leg road : roads) RoadGrades.validate(road.mesh());
    return new Plan(List.copyOf(roads), ramps.size(), min, highest, center);
  }

  private void checkBounds(V point){
    V q=local(point);double pad=Math.max(main[0].width(),main[1].width());
    if(q.x() < -lengths[0]-pad||q.x()>lengths[2]+pad||q.z() < -lengths[1]-pad||q.z()>lengths[3]+pad)
      throw new IllegalArgumentException("该预设超出选定范围，请减小环绕半径或扩大范围");
  }

  public double transitionLength() {
    return Math.max(80, Math.max(options.transition, options.lanes == 1 ? 80 : 108));
  }

  private double terminalStation() {
    double shortest = Math.min(lengths[0], Math.min(lengths[1], lengths[2]));
    return Math.min(
        lengths[1] - Math.max(transitionLength(), 100), shortest * .94 - transitionLength());
  }

  private RoadTransitions.Section terminalSection() {
    var section = new RoadTransitions.Section(
        options.lanes() == 1 ? Style.O2_RAIL : Style.O4_RAIL,
        options.width() * 2 + 1,
        false,
        false,
        false,
        main[1].options().outerRail());
    // Match the two independent ramp strips, including their inner shoulders.
    // Equal division of the combined roadway shifts each two-lane divider by .25.
    if(options.lanes()==2){
      var layout=section.layout(options.leftTraffic());double center=options.width()/2+.5;
      section=section.port(new RoadTransitions.Port(layout.motorMin(),layout.motorMax(),layout.median(),List.of(-center,center),0,0));
    }
    return section;
  }

  /** One continuous auxiliary carriageway between each pair of adjacent loop mouths. */
  private boolean collectorLoops(){return options.preset==Preset.CLOVERLEAF||options.preset==Preset.HYBRID;}

  /** Tangencies on the two collector axes must share one circle, even on skewed hosts. */
  private V[] leafPorts(int from, int to) {
    V da = direction(ARMS[from].mul(-1)), db = direction(ARMS[to]);
    double hand = options.leftTraffic ? -1 : 1;
    V a = world(new V(0, 0, 0)).add(da.left().mul(hand * (auxiliaryOffset(from % 2) + loopRadius)));
    V b = world(new V(0, 0, 0)).add(db.left().mul(hand * (auxiliaryOffset(to % 2) + loopRadius)));
    V delta = b.sub(a);
    double det = da.x() * db.z() - da.z() * db.x();
    V circle = a.add(da.mul((delta.x() * db.z() - delta.z() * db.x()) / det));
    return new V[] {circle.sub(da.left().mul(hand * loopRadius)),
        circle.sub(db.left().mul(hand * loopRadius))};
  }

  private void addCollectors() {
    for (int arm = 0; arm < 4; arm++) {
      RoadPlanningBudget.check();
      boolean departure = options.preset == Preset.CLOVERLEAF || (options.loopMask & (1 << arm)) != 0;
      int arrivingFrom = (arm + (traffic > 0 ? 1 : 3)) % 4;
      boolean arrival = options.preset == Preset.CLOVERLEAF || (options.loopMask & (1 << arrivingFrom)) != 0;
      if (!departure && !arrival) continue;
      int axis = arm % 2;
      double offset = auxiliaryOffset(axis);
      V flow = direction(ARMS[arm].mul(-1));
      V side = flow.left().mul(options.leftTraffic ? -1 : 1);
      V b = leafPorts(arrivingFrom, (arm + 2) % 4)[1];
      V c = leafPorts(arm, (arm + (traffic > 0 ? 1 : 3)) % 4)[0];
      List<V> path = new ArrayList<>();
      List<Double> widths = new ArrayList<>();
      if (arrival && departure) {
        int count = (int) Math.ceil(c.sub(b).horizontalLength() / .4);
        for (int i = 0; i <= count; i++) {
          if((i&63)==0)RoadPlanningBudget.check();
          path.add(b.add(c.sub(b).mul(i / (double) count)));
          widths.add(options.width());
        }
      } else {
        // A single loop needs one acceleration/deceleration taper, not the
        // full cloverleaf lane ending at a nonexistent second loop mouth.
        double lead = transitionLength(), shift = offset - attachment(axis);
        V first = departure ? c.sub(flow.mul(lead)).sub(side.mul(shift)) : b;
        V last = departure ? c : b.add(flow.mul(lead)).sub(side.mul(shift));
        appendLead(axis, path, widths, first, last, flow, lead,
            departure ? portWidth(axis) : options.width(), departure ? options.width() : portWidth(axis), false);
      }
      List<Sample> samples = new ArrayList<>();
      for (int i = 0; i < path.size(); i++) {
        if((i&63)==0)RoadPlanningBudget.check();
        V p = path.get(i);
        V tangent =
            path.get(Math.min(i + 1, path.size() - 1))
                .sub(path.get(Math.max(0, i - 1)))
                .horizontalUnit();
        samples.add(
            new Sample(new V(p.x(), heights[axis], p.z()), tangent.left(), 0, widths.get(i) / 2));
      }
      roads.add(
          new Leg("连续集散道 " + arm, arm, (arm + 2) % 4, RoadRibbon.mesh(samples, rampSettings())));
    }
  }

  private final Set<String> emittedPorts = new HashSet<>();

  private void addWithSharedPorts(Route r, Mesh mesh) {
    boolean sharedStart =
        r.leadCut > 0
            && routes.stream()
                .anyMatch(
                    o ->
                        o != r
                            && o.from == r.from
                            && o.points.get(0).distance(r.points.get(0)) < .01
                            && o.points.get(o.leadCut).distance(r.points.get(r.leadCut)) < .01
                            && o.points.get(o.leadCut * 4 / 5).distance(r.points.get(r.leadCut * 4 / 5)) < .01);
    boolean sharedEnd =
        r.tailCut < mesh.samples().size() - 1
            && routes.stream()
                .anyMatch(
                    o ->
                        o != r
                            && o.to == r.to
                            && o.points
                                    .get(o.points.size() - 1)
                                    .distance(r.points.get(r.points.size() - 1))
                                < .01
                            && o.points.get(o.tailCut).distance(r.points.get(r.tailCut)) < .01
                            && o.points.get((o.tailCut * 4 + o.points.size() - 1) / 5)
                                .distance(r.points.get((r.tailCut * 4 + r.points.size() - 1) / 5)) < .01);
    if (sharedStart && emittedPorts.add("out:" + r.from))
      roads.add(
          new Leg(
              "共用汇出 " + r.from,
              r.from,
              -1,
              RoadRibbon.mesh(mesh.samples().subList(0, r.leadCut + 1), mesh.settings())));
    if (sharedEnd && emittedPorts.add("in:" + r.to))
      roads.add(
          new Leg(
              "共用汇入 " + r.to,
              -1,
              r.to,
              RoadRibbon.mesh(
                  mesh.samples().subList(r.tailCut, mesh.samples().size()), mesh.settings())));
    int first = sharedStart ? r.leadCut : 0,
        last = sharedEnd ? r.tailCut : mesh.samples().size() - 1;
    roads.add(
        new Leg(
            r.name,
            r.from,
            r.to,
            RoadRibbon.mesh(mesh.samples().subList(first, last + 1), mesh.settings())));
  }

  private boolean servicePreset() {
    return switch (options.preset) {
      case DIAMOND, PARCLO, ROUNDABOUT, SPUI -> true;
      default -> false;
    };
  }

  // A quarter turn keeps traffic handedness and the original AB/CD leg identities.
  private V serviceFrame(V p){return options.junctionAxis()==1?p:new V(-p.z(),p.y(),p.x());}
  private V serviceWorld(V p){return world(serviceFrame(p));}
  private V serviceDirection(V p){return direction(serviceFrame(p));}
  private static String armName(int arm){return new String[]{"A","C","B","D"}[arm];}

  private Plan service() {
    boolean single = options.preset == Preset.SPUI;
    int cross=options.junctionAxis(), host=1-cross;
    double width = options.width();
    double station = Math.max(options.radius * 2, main[host].width() / 2 + width + 26);
    if (options.preset == Preset.PARCLO) station = loopRadius * 1.5 + main[host].width() * 2;
    double corner = Math.max(24, width * 2);
    if (!single && station + main[cross].width() > Math.min(lengths[cross], lengths[cross+2]) - 16)
      throw new IllegalArgumentException("菱形的两个匝道端部路口放不下，请扩大 "+(cross==0?"A/B":"C/D")+" 范围");
    double singleStation = main[host].width() / 2 + width / 2 + 3;
    if (single) intersection(0, corner, singleStation + width / 2);
    else for (int side : new int[] {-1, 1}) intersection(side * station, corner, width / 2);
    for (int arm : new int[] {0, 2})
      for (boolean exit : new boolean[] {true, false}) {
        double orientation = arm == 0 ? 1 : -1, side = traffic * orientation * (exit ? 1 : -1);
        int actualArm=(arm+host)%4;
        double lead = transitionLength(), source = lengths[actualArm] - 8;
        // Leave a real half-block gap before a parallel service ramp changes height.
        double outer = detachedOffset(host);
        V a = serviceWorld(new V(-orientation * source, heights[host], side * attachment(host))),
            b = serviceWorld(new V(-orientation * (source - lead), heights[host], side * outer));
        V forward = serviceDirection(new V(orientation, 0, 0));
        List<V> points = new ArrayList<>();
        List<Double> widths = new ArrayList<>();
        appendLead(host, points, widths, a, b, forward, lead, portWidth(host), width, false);
        int hold = points.size() - 1;
        double targetX = main[cross].width() / 2 + corner + (single ? singleStation : 0);
        V target =
            serviceWorld(
                new V(-orientation * targetX, heights[cross], side * (single ? singleStation : station)));
        V tangent = forward;
        if (source - lead < targetX + 24)
          throw new IllegalArgumentException("四条菱形匝道需要独立的减速段和爬坡段，请延长 "+(host==0?"A/B":"C/D")+" 范围");
        List<Knot> approach = new ArrayList<>();
        approach.add(new Knot(b, leadTangent(a, b, forward, lead, host)));
        if (single)
          approach.add(
              new Knot(
                  serviceWorld(new V(-orientation * (targetX + 75), heights[cross], side * outer)), forward));
        approach.add(new Knot(target, tangent));
        appendCurve(points, widths, curve(approach), width);
        Mesh ramp =
            serviceRamp(
                points,
                widths,
                hold,
                heights[host],
                heights[cross],
                single ? 85 : 12,
                !exit,
                armName(actualArm) + (exit ? " 汇出" : " 汇入"));
        roads.add(
            new Leg(
                armName(actualArm) + (exit ? " 汇出至" : " 汇入自") + (single ? "单点中心路口" : "菱形端部路口"),
                exit ? actualArm : -1,
                exit ? -1 : actualArm,
                ramp));
      }
    if (options.preset == Preset.PARCLO) {
      // A4 partial cloverleaf: two opposite free-flow leaves plus the four diamond ramps.
      for (int arm : new int[] {0, 2}) {
        int to = (arm + (traffic > 0 ? 1 : 3)) % 4;
        Route r = route(arm, to, false, true);
        Mesh m = horizontal(r);
        // The leaves cross the axes on the single intermediate deck.
        List<Mesh> mains = roads.subList(0, 2).stream().map(Leg::mesh).toList();
        for (int i = r.leadEnd + 1; i < r.tailStart; i++)
          for (Mesh main : mains)
            if (overlap(main, m.samples().get(i)) != null) {
              r.plateauStart = r.plateauStart < 0 ? i : Math.min(r.plateauStart, i);
              r.plateauEnd = Math.max(r.plateauEnd, i);
            }
        r.layer = base + step;
        roads.add(new Leg(r.name, arm, to, elevate(r)));
      }
    }
    if(options.preset==Preset.DIAMOND||options.preset==Preset.SPUI)unifiedServiceJunctions();
    validateService();
    return finish(8);
  }

  private Settings rampSettings() {
    Style style = options.lanes == 1 ? Style.R1 : Style.R2;
    return new Settings(Mode.CURVE, style, options.width(), 1, .4, 90)
        .options(
            RoadProfile.Options.DEFAULT
                .traffic(options.leftTraffic)
                .route(Routing.DEFAULT.fit(false)));
  }

  private Mesh straight(V a, V b, Settings s) {
    List<Sample> samples = new ArrayList<>();
    int n = (int) Math.ceil(a.sub(b).horizontalLength() / .5);
    V left = direction(b.sub(a)).left();
    for (int i = 0; i <= n; i++) {
      if((i&63)==0)RoadPlanningBudget.check();
      samples.add(
          new Sample(
              world(a.mul(1 - i / (double) n).add(b.mul(i / (double) n))), left, 0, s.width() / 2));
    }
    return RoadRibbon.mesh(samples, s);
  }

  /** Rounded paved corners, with a real crossroad through the two ramp terminals. */
  private void intersection(double station, double corner, double rampHalf) {
    if(options.preset==Preset.DIAMOND||options.preset==Preset.SPUI){serviceCenters.add(station);return;}
    double inner = main[options.junctionAxis()].width() / 2, reach = inner + corner;
    Settings s =
        new Settings(
                Mode.STRAIGHT,
                Style.UNMARKED,
                Math.min(64, rampHalf * 2),
                Math.max(1, main[options.junctionAxis()].thickness()),
                .4,
                90)
            .structure(Structure.AUTO)
            .options(RoadProfile.Options.DEFAULT.route(Routing.DEFAULT.fit(false)));
    List<Sample> samples = new ArrayList<>();
    int n = (int) Math.ceil(reach * 4);
    for (int i = 0; i <= n; i++) {
      if((i&63)==0)RoadPlanningBudget.check();
      double x = -reach + 2 * reach * i / n;
      double offset = Math.max(0, Math.abs(x) - inner);
      double half =
          rampHalf
              + corner
              - Math.sqrt(Math.max(0, corner * corner - (corner - offset) * (corner - offset)));
      samples.add(
          new Sample(serviceWorld(new V(x, heights[options.junctionAxis()], station)), serviceDirection(new V(1,0,0)).left(), 0, Math.min(32, half)));
    }
    roads.add(
        new Leg(
            options.preset == Preset.SPUI ? "单点中心路口" : "菱形端部路口",
            -1,
            -1,
            RoadRibbon.mesh(samples, s)));
  }

  private void unifiedServiceJunctions(){
    int cross=options.junctionAxis();Leg mainLeg=roads.get(cross);Mesh full=mainLeg.mesh();
    double reach=options.preset==Preset.SPUI?main[1-cross].width()/2+options.width()+36:Math.max(28,(main[cross].width()+options.width())/4+16);
    var cuts=new ArrayList<double[]>();var pieces=new ArrayList<Leg>();
    for(double station:serviceCenters){
      V center=serviceWorld(new V(0,heights[cross],station));var arms=new ArrayList<JunctionSpec.Arm>();
      for(int side:new int[]{-1,1}){
        V endpoint=serviceWorld(new V(0,heights[cross],station+side*reach));V inward=center.sub(endpoint).horizontalUnit();
        arms.add(JunctionSpec.arm(new Node(endpoint,RoadPlanner.yaw(inward),0),inward,setting(cross),true,arms.size()).attached(true));
      }
      for(var leg:roads){
        if(!leg.mesh().settings().style().ramp()||leg.from()>=0&&leg.to()>=0)continue;
        boolean incoming=leg.to()<0;Mesh mesh=leg.mesh();var endpoint=incoming?mesh.last():mesh.first();
        V local=local(endpoint.center());double along=cross==1?local.z():-local.x();
        if(serviceCenters.size()>1&&Math.abs(along-station)>1)continue;
        V inward=endpoint.left().left().mul(incoming?-1:1);
        if(options.preset==Preset.SPUI){V axis=serviceDirection(new V(1,0,0));inward=axis.mul(inward.dot(axis)>=0?1:-1);}
        Settings ordinary=new Settings(Mode.STRAIGHT,options.lanes==1?Style.O1_ONE:Style.O2_ONE,mesh.settings().width(),1,.4,90)
            .options(mesh.settings().options()).structure(Structure.AUTO);
        arms.add(JunctionSpec.arm(new Node(endpoint.center(),RoadPlanner.yaw(inward),0),inward,ordinary,incoming,arms.size()).attached(true));
      }
      boolean elevated=main[cross].structure()==Structure.BRIDGE||heights[cross]>heights[1-cross]+1;
      arms.replaceAll(a->(elevated&&!main[cross].options().sidewalk().enabled()?a.crossing(false):a).signs(false,false));
      var spec=new JunctionSpec(center,JunctionSpec.Kind.INTERSECTION,options.leftTraffic,6,12,1,4,Math.max(1,main[cross].thickness()),JunctionSpec.Control.SIGNALS,20,3,1,0,true,true,true,arms).parallel(options.preset==Preset.SPUI);
      // The ordinary editor measures stop-line space from the actual junction
      // boundary. A fixed 28-block reach becomes too short on wider main roads;
      // extending the outer interchange endpoints cannot fix that inner arm.
      double junctionRadius=JunctionPlanner.radius(spec);
      for(int i=0;i<2;i++){
        var arm=arms.get(i);double required=junctionRadius+arm.crossingSetback()
            +(arm.crosswalk()?arm.crossingWidth()+arm.stopGap():0)+6;
        double current=arm.endpoint().position().sub(center).horizontalLength();
        if(current<required){V endpoint=center.sub(arm.inward().mul(required));
          arms.set(i,arm.node(new Node(endpoint,arm.endpoint().yaw(),0),arm.inward(),arm.external()));}
      }
      spec=spec.arms(arms);
      // Projection below is clamped to the selected mainline. Validate the real
      // arm endpoints first, retaining a selectable straight road at both ends.
      // Two blocks is the same minimum as an ordinary continuous source piece.
      V mainStart=full.first().center(),mainDirection=full.last().center().sub(mainStart).horizontalUnit();
      double mainLength=full.last().center().sub(mainStart).dot(mainDirection);
      for(int i=0;i<2;i++){
        double at=arms.get(i).endpoint().position().sub(mainStart).dot(mainDirection);
        if(at<2-1e-6||at>mainLength-2+1e-6)
          throw new IllegalArgumentException("路口引道与主路端点间须保留至少2格直路，请扩大 "+(cross==0?"A/B":"C/D")+" 范围");
      }
      var plan=JunctionPlanner.plan(spec);
      for(int i=0;i<plan.pieces().size();i++)pieces.add(new Leg(options.preset==Preset.SPUI?"单点中心路口":"菱形端部路口",-1,-1,plan.pieces().get(i).mesh(),new JunctionPlanner.Ref(spec,i)));
      double a=RoadQueries.horizontal(full,arms.get(0).endpoint().position()).sample().distance(),b=RoadQueries.horizontal(full,arms.get(1).endpoint().position()).sample().distance();
      cuts.add(new double[]{Math.min(a,b),Math.max(a,b)});
    }
    cuts.sort(Comparator.comparingDouble(c->c[0]));roads.remove(cross);double start=0;int index=0;
    for(var cut:cuts){if(cut[0]<start-1e-6)throw new IllegalArgumentException("菱形两个路口的引道重叠，请增大半径或扩大范围");if(cut[0]>start+1e-6)roads.add(new Leg(mainLeg.name()+" · "+index++,mainLeg.from(),mainLeg.to(),serviceSlice(full,start,cut[0])));start=cut[1];}
    if(start<full.length()-1e-6)roads.add(new Leg(mainLeg.name()+" · "+index,mainLeg.from(),mainLeg.to(),serviceSlice(full,start,full.length())));
    roads.addAll(pieces);
  }
  private static Mesh serviceSlice(Mesh mesh,double start,double end){
    var samples=new ArrayList<Sample>();samples.add(RoadStructures.sample(mesh,start));
    for(var s:mesh.samples())if(s.distance()>start+1e-7&&s.distance()<end-1e-7)samples.add(s);
    samples.add(RoadStructures.sample(mesh,end));return RoadRibbon.mesh(samples,mesh.settings());
  }

  private static void appendCurve(
      List<V> points, List<Double> widths, List<V> curve, double width) {
    for (int i = 1; i < curve.size(); i++) {
      if((i&63)==0)RoadPlanningBudget.check();
      points.add(curve.get(i));
      widths.add(width);
    }
  }

  private Mesh serviceRamp(
      List<V> points,
      List<Double> widths,
      int hold,
      double startY,
      double endY,
      double flat,
      boolean reverse,
      String name) {
    double[] distance = new double[points.size()];
    for (int i = 1; i < points.size(); i++) {
      if((i&63)==0)RoadPlanningBudget.check();
      distance[i] = distance[i - 1] + points.get(i).sub(points.get(i - 1)).horizontalLength();
    }
    double begin = distance[hold], end = distance[distance.length - 1] - flat;
    if(options.preset==Preset.DIAMOND||options.preset==Preset.SPUI){
      var probe=new ArrayList<Sample>();
      for(int i=0;i<points.size();i++){
        V p=points.get(i),d=points.get(Math.min(i+1,points.size()-1)).sub(points.get(Math.max(0,i-1)));
        probe.add(new Sample(new V(p.x(),startY,p.z()),d.horizontalUnit().left(),0,widths.get(i)/2));
      }
      // The whole ribbon, including the inside edge of the first curved triangle,
      // must leave its own host before a service ramp starts changing height.
      Mesh horizontal=RoadRibbon.mesh(probe,rampSettings());
      for(var contact:RoadClearance.detailedContacts(horizontal,roads.get(1-options.junctionAxis()).mesh()))
        if(contact.contact().from()<distance[hold]+transitionLength())
          begin=Math.max(begin,distance[InterchangeGradeProfile.ceil(distance,contact.contact().to())]);
    }
    checkClimb(name, "主路过渡与平交路口之间的升降区", endY - startY, end - begin);
    List<Sample> samples = new ArrayList<>();
    for (int i = 0; i < points.size(); i++) {
      if((i&63)==0)RoadPlanningBudget.check();
      V p = points.get(i),
          dir = points.get(Math.min(i + 1, points.size() - 1)).sub(points.get(Math.max(0, i - 1)));
      double y = startY + (endY - startY) * grade((distance[i] - begin) / (end - begin));
      samples.add(
          new Sample(new V(p.x(), y, p.z()), dir.horizontalUnit().left(), 0, widths.get(i) / 2));
    }
    if (reverse) {
      Collections.reverse(samples);
      // The saved horizontal frame stores X/Z only; keep its implicit Y as +0.
      samples.replaceAll(q -> new Sample(q.center(), new V(-q.left().x(), 0, -q.left().z()), 0, q.halfWidth()));
    }
    Mesh mesh = RoadRibbon.mesh(samples, rampSettings());
    RoadGrades.validate(mesh);
    RoadRibbon.checkSelfIntersections(mesh, options.clearance);
    return mesh;
  }

  /** Two uninterrupted mainlines, a separate intermediate ring, eight one-way slip roads. */
  private Plan roundabout() {
    double width = options.width();
    double radius =
        Math.max(
            100,
            Math.max(options.radius * 2, Math.max(main[0].width(), main[1].width()) + width + 48));
    double ringY = (heights[0] + heights[1]) / 2;
    List<Sample> samples = new ArrayList<>();
    int count = (int) Math.ceil(2 * Math.PI * radius / .4);
    for (int i = 0; i <= count; i++) {
      if((i&63)==0)RoadPlanningBudget.check();
      double angle = -traffic * 2 * Math.PI * i / count;
      V p = new V(radius * Math.cos(angle), ringY, radius * Math.sin(angle));
      V d = direction(new V(traffic * Math.sin(angle), 0, -traffic * Math.cos(angle)));
      samples.add(new Sample(world(p), d.left(), 0, width / 2));
    }
    Mesh ring = RoadRibbon.mesh(samples, rampSettings());
    roads.add(
        new Leg(
            "独立中层环道",
            -1,
            -1,
            new Mesh(
                ring.samples(),
                ring.settings(),
                ring.min(),
                ring.max(),
                ring.length(),
                true,
                null)));
    for (int arm = 0; arm < 4; arm++)
      for (boolean exit : new boolean[] {true, false}) {
        int axis = arm % 2, sign = exit ? 1 : -1;
        V inward = ARMS[arm].mul(-1),
            side = inward.left().mul(traffic * sign),
            forward = direction(inward);
        double source = lengths[arm] - 8, lead = transitionLength();
        if (source - lead < radius + 90)
          throw new IllegalArgumentException("独立环道和八条匝道需要更大范围，请将各端点外移");
        double outer = detachedOffset(axis);
        V a = world(ARMS[arm].mul(source).add(side.mul(attachment(axis)))),
            b = world(ARMS[arm].mul(source - lead).add(side.mul(outer)));
        double angle = .62;
        V radial = ARMS[arm].mul(Math.cos(angle)).add(side.mul(Math.sin(angle)));
        V target = world(new V(radial.x() * radius, ringY, radial.z() * radius));
        V tangent = direction(new V(traffic * radial.z() * sign, 0, -traffic * radial.x() * sign));
        List<V> points = new ArrayList<>();
        List<Double> widths = new ArrayList<>();
        appendLead(axis, points, widths, a, b, forward, lead, portWidth(axis), width, false);
        int hold = points.size() - 1;
        V approach = world(ARMS[arm].mul(radius + 60).add(side.mul(outer)));
        appendCurve(
            points,
            widths,
            curve(
                List.of(
                    new Knot(b, leadTangent(a, b, forward, lead, axis)), new Knot(approach, forward), new Knot(target, tangent))),
            width);
        Mesh mesh =
            serviceRamp(points, widths, hold, heights[axis], ringY, 22, !exit, "环道匝道 " + arm);
        roads.add(
            new Leg("环道 " + arm + (exit ? " 汇出" : " 汇入"), exit ? arm : -1, exit ? -1 : arm, mesh));
      }
    validateService();
    return finish(8);
  }

  /** Flat junctions are intentional. Every overlap during a climb must still meet clearance. */
  private void validateService() {
    if(options.preset!=Preset.DIAMOND&&options.preset!=Preset.SPUI){validateLegacyService();return;}
    for(int i=1;i<roads.size();i++)for(int j=0;j<i;j++){
      RoadPlanningBudget.check();
      for(var detail:RoadClearance.detailedContacts(roads.get(i).mesh(),roads.get(j).mesh())){
        // Micrometre-scale interpolation at a curved mouth is still its shared plane.
        if(Math.max(Math.abs(detail.minimumDifference()),Math.abs(detail.maximumDifference()))<=1e-5)continue;
        exactClearance(roads.get(i).name(),roads.get(j).name(),detail.contact());
      }
    }
  }

  private void validateLegacyService() {
    for (int i = 2; i < roads.size(); i++) {
      RoadPlanningBudget.check();
      Mesh a = roads.get(i).mesh();
      for (int j = 0; j < i; j++) {
        Mesh b = roads.get(j).mesh();
        for (int k = 0; k < a.samples().size(); k += 3) {
          if((k&63)==0)RoadPlanningBudget.check();
          Sample p = a.samples().get(k);
          var q = overlap(b, p);
          if (q == null) continue;
          double dy = p.center().y() - q.sample().center().y();
          if (Math.abs(dy) < .12) continue;
          double need =
              options.clearance + (dy >= 0 ? a.settings().thickness() : b.settings().thickness());
          if (Math.abs(dy) < need - .05)
            throw new IllegalArgumentException(
                roads.get(i).name + "与" + roads.get(j).name + "净高不足，请扩大范围");
        }
      }
    }
  }

  private Plan finish(int movements) {
    for (Leg road : roads) RoadGrades.validate(road.mesh());
    for (Leg road : roads)
      for (Sample sample : road.mesh.samples()) {
        V p = local(sample.center());
        double pad = Math.max(main[0].width(), main[1].width());
        if (p.x() < -lengths[0] - pad
            || p.x() > lengths[2] + pad
            || p.z() < -lengths[1] - pad
            || p.z() > lengths[3] + pad) throw new IllegalArgumentException("该预设超出选定范围，请扩大范围");
      }
    double min =
        roads.stream()
            .filter(l -> l.mesh.settings().style().ramp())
            .mapToDouble(l -> RoadRibbon.minRadius(l.mesh))
            .min()
            .orElse(0);
    return new Plan(
        List.copyOf(roads),
        movements,
        min,
        roads.stream().mapToDouble(l -> l.mesh.max().y()).max().orElse(base),
        center);
  }

  private Route route(int from, int to, boolean right, boolean leaf) {
    V di = ARMS[from].mul(-1), dj = ARMS[to];
    // A growing auxiliary lane holds its inner edge on the mainline edge. Its center never
    // makes a second lateral excursion before the turn, for any preset or traffic side.
    int ia = from % 2, ib = to % 2;
    double width = options.width(),
        lead = transitionLength(),
        oa = leaf && collectorLoops() ? auxiliaryOffset(ia) : detachedOffset(ia),
        ob = leaf && collectorLoops() ? auxiliaryOffset(ib) : detachedOffset(ib);
    if (leaf && options.preset.three) lead = Math.max(lead, Math.max(oa, ob) + loopRadius + 24);
    double
        sa =
            Math.min(
                lengths[from] - 8, Math.min(lengths[0], Math.min(lengths[1], lengths[2])) * .94),
        sb = Math.min(lengths[to] - 8, sa);
    if (!options.preset.three) {
      double shortest = Arrays.stream(lengths).min().orElseThrow();
      sa = Math.min(sa, shortest * .94);
      sb = Math.min(sb, shortest * .94);
    }
    V centerA, centerB;
    if (leaf) {
      double station = Math.max(oa, ob) + loopRadius;
      centerA = di.mul(station - lead);
      centerB = dj.mul(-(station - lead));
    } else {
      centerA = ARMS[from].mul(sa);
      centerB = ARMS[to].mul(sb);
    }
    V fwa = direction(di), fwb = direction(dj), wa = world(centerA), wb = world(centerB);
    double atA = attachment(ia), atB = attachment(ib);
    V start = wa.add(fwa.left().mul((options.leftTraffic ? -1 : 1) * atA)),
        end = wb.add(fwb.left().mul((options.leftTraffic ? -1 : 1) * atB));
    V aa = wa.add(fwa.mul(lead)).add(fwa.left().mul((options.leftTraffic ? -1 : 1) * oa));
    V bb = wb.sub(fwb.mul(lead)).add(fwb.left().mul((options.leftTraffic ? -1 : 1) * ob));
    if (leaf && collectorLoops()) {
      V[] ports = leafPorts(from, to);
      aa = ports[0];
      bb = ports[1];
    }
    V loopA = aa, loopB = bb;
    boolean terminalA = options.preset.three && from == 1;
    boolean terminalB = options.preset.three && to == 1;
    boolean collector = leaf && collectorLoops();
    if (terminalA)
      aa =
          world(ARMS[1].mul(terminalStation()))
              .add(fwa.left().mul((options.leftTraffic ? -1 : 1) * (width / 2 + .5)));
    if (terminalB)
      bb =
          world(ARMS[1].mul(terminalStation()))
              .add(fwb.left().mul((options.leftTraffic ? -1 : 1) * (width / 2 + .5)));
    List<V> path = new ArrayList<>();
    List<Double> widths = new ArrayList<>();
    double pwA = portWidth(ia), pwB = portWidth(ib);
    if (terminalA || collector) {
      path.add(aa);
      widths.add(width);
    } else appendLead(ia, path, widths, start, aa, fwa, lead, pwA, width, false, !leaf);
    int leadEnd = path.size() - 1;
    // Match the nonzero peel slope at the lead/curve seam. A smoothstep returning to
    // zero here made the highway ramp straighten and bend a second time.
    double slopeA = leaf || terminalA || collector ? 0 : peelSlope(ia, lead);
    double slopeB = leaf || terminalB || collector ? 0 : peelSlope(ib, lead);
    int handed = options.leftTraffic ? -1 : 1;
    V tangentA = fwa.add(fwa.left().mul(handed * slopeA)).horizontalUnit();
    V tangentB = fwb.sub(fwb.left().mul(handed * slopeB)).horizontalUnit();
    List<V> middle;
    if (leaf) {
      RoadLoop loop = new RoadLoop(loopA, loopB, tangentA, tangentB, loopRadius, !options.leftTraffic);
      int n = (int) Math.ceil(loop.length() / .45);
      middle = new ArrayList<>();
      if (terminalA) middle.addAll(curve(List.of(new Knot(aa, fwa), new Knot(loopA, fwa))));
      for (int k = terminalA ? 1 : 0; k <= n; k++) {
        if((k&63)==0)RoadPlanningBudget.check();
        middle.add(loop.point(k / (double) n));
      }
    } else if (right) {
      // Broad continuous inward bend. Existing radius, ribbon and clearance checks still
      // reject layouts that cut into a leaf; fitting enlarges the footprint when necessary.
      middle = curve(List.of(new Knot(aa, tangentA), new Knot(bb, tangentB)), directCurve);
    } else if (options.preset == Preset.STACK) {
      // A stack flyover has one continuous turn. Returning to the unpeeled
      // highway heading at intermediate knots introduced two reverse bends.
      middle = curve(List.of(new Knot(aa, tangentA), new Knot(bb, tangentB)), directCurve);
    } else if (options.preset == Preset.TURBINE || options.preset == Preset.Y) {
      double radius =
          Math.max(options.radius * 2, Math.max(main[0].width(), main[1].width()) * 2 + 18);
      V enter = world(dj.mul(-radius)), leave = world(di.mul(radius));
      middle = new ArrayList<>(curve(List.of(new Knot(aa, tangentA), new Knot(enter, fwa))));
      int n = (int) Math.ceil(Math.PI * radius / .8);
      for (int i = 1; i <= n; i++) {
        if((i&63)==0)RoadPlanningBudget.check();
        double angle = Math.PI / 2 * i / n;
        middle.add(world(dj.mul(-radius * Math.cos(angle)).add(di.mul(radius * Math.sin(angle)))));
      }
      var tail = curve(List.of(new Knot(leave, fwb), new Knot(bb, tangentB)));
      middle.addAll(tail.subList(1, tail.size()));
    } else {
      List<Knot> knots = new ArrayList<>();
      knots.add(new Knot(aa, tangentA));
      double room = Math.min(sa, sb) - lead;
      if (!right && options.preset != Preset.TURBINE && options.preset != Preset.Y) {
        double reach =
            options.preset == Preset.STACK
                ? Math.max(
                    0, room - cornerScale*Math.max(options.radius, Math.max(oa, ob) * 2.5 + width * 1.5 + 2))
                : Math.max(20, room * .42);
        if(reach>1){knots.add(new Knot(aa.add(fwa.mul(reach)), fwa));
        knots.add(new Knot(bb.sub(fwb.mul(reach)), fwb));}
      }
      knots.add(new Knot(bb, tangentB));
      boolean compactCorner = right;
      middle = curve(knots, compactCorner ? .18 : .4);
    }
    for (int i = 1; i < middle.size(); i++) {
      if((i&63)==0)RoadPlanningBudget.check();
      path.add(middle.get(i));
      widths.add(width);
    }
    int tailStart = path.size() - 1;
    if (!terminalB && !collector) appendLead(ib, path, widths, bb, end, fwb, lead, width, pwB, true, !leaf);
    if (path.size() > RoadLimits.MAX_SAMPLES) throw new IllegalArgumentException("立交匝道过长，请缩小范围或半径");
    return new Route(
        (char) ('A' + (from == 1 ? 2 : from == 2 ? 1 : from))
            + "→"
            + (char) ('A' + (to == 1 ? 2 : to == 2 ? 1 : to))
            + (leaf
                ? " 环绕"
                : right
                    ? (options.leftTraffic ? " 左转" : " 右转")
                    : (options.leftTraffic ? " 右转" : " 左转")),
        from,
        to,
        path,
        widths,
        leadEnd,
        tailStart,
        right ? 0 : leaf ? 1 : 2);
  }

  private double padding(int axis) {
    if (!main[axis].options().routing().fitEdges()) return 0;
    V d = axis == 0 ? u : v, p = nodes[axis == 0 ? 0 : 2].position();
    double coordinate;
    if (Math.abs(d.x()) > .99999) coordinate = p.z();
    else if (Math.abs(d.z()) > .99999) coordinate = p.x();
    else return 0;
    double half = main[axis].width() / 2;
    return Math.abs(coordinate - Math.floor(coordinate) - .5) < 1e-7
        ? Math.ceil(coordinate + half - 1e-7) - coordinate - half
        : 0;
  }

  private double detachedOffset(int axis) {
    // A detached parallel ramp needs a real gap before it can change height.
    // The old 0.2-block overlap kept its entire lead locked to the host plane,
    // including a later oblique crossing of the opposing carriageway.
    return main[axis].width() / 2 + padding(axis) + options.width() / 2 + .5;
  }

  /** The existing shoulder becomes the innermost ramp lane along the entire parallel lead. */
  private double auxiliaryOffset(int axis) {
    var layout = RoadProfile.layout(main[axis], main[axis].width() + 2 * padding(axis));
    if (layout.catalog().type() == RoadProfile.Type.HIGHWAY)
      return Math.abs(layout.outer(layout.outside())) + (options.width() - 1) / 2;
    return main[axis].width() / 2 + padding(axis) + options.width() / 2 - .2;
  }

  private double attachment(int axis) {
    if (RoadProfile.catalog(main[axis].style()).type() == RoadProfile.Type.HIGHWAY) {
      var layout = RoadProfile.layout(main[axis], main[axis].width() + 2 * padding(axis));
      return Math.abs(layout.outer(layout.outside())) + layout.shoulderWidth() / 2;
    }
    return main[axis].width() / 2 + padding(axis) - .2 + portWidth(axis) / 2;
  }

  private double portWidth(int axis) {
    if (RoadProfile.catalog(main[axis].style()).type() == RoadProfile.Type.HIGHWAY)
      return RoadProfile.layout(main[axis], main[axis].width() + 2 * padding(axis)).shoulderWidth();
    return .5;
  }

  private V leadTangent(V a, V b, V forward, double lead, int axis) {
    V side = b.sub(a).sub(forward.mul(lead)).horizontalUnit();
    return forward.add(side.mul(peelSlope(axis, lead))).horizontalUnit();
  }

  private double peelSlope(int axis, double lead) {
    if (servicePreset()) return 0;
    return 2 * (detachedOffset(axis) - auxiliaryOffset(axis)) / (.35 * lead);
  }

  private void appendLead(
      int axis, List<V> out,
      List<Double> widths,
      V a,
      V b,
      V forward,
      double lead,
      double wa,
      double wb,
      boolean skip) {
    appendLead(axis, out, widths, a, b, forward, lead, wa, wb, skip, false);
  }

  private void appendLead(int axis, List<V> out, List<Double> widths, V a, V b,
      V forward, double lead, double wa, double wb, boolean skip, boolean continuous) {
    V side = b.sub(a).sub(forward.mul(lead));
    int n = (int) Math.ceil(lead / .4);
    for (int i = skip ? 1 : 0; i <= n; i++) {
      if((i&63)==0)RoadPlanningBudget.check();
      double t = i / (double) n;
      boolean merging = wa > wb;
      double q = merging ? 1 - t : t;
      double grow = Settings.smooth(Math.min(1, q / .32));
      double shift = grow;
      {
        double total = side.horizontalLength();
        double occupied = auxiliaryOffset(axis) - attachment(axis);
        // Ordinary roads also need a welded full-width acceleration/deceleration
        // lane. Growing straight to detachedOffset left a half-block trench and
        // two walls along the entire ordinary-road merge.
        double peel = Math.max(0, Math.min(1, (q - .65) / .35));
        double leave = continuous ? peel * peel : Settings.smooth(peel);
        shift = total < 1e-8 ? grow
            : (occupied * grow + Math.max(0, total - occupied) * leave) / total;
      }
      if (merging) {
        grow = 1 - grow;
        shift = 1 - shift;
      }
      out.add(a.add(forward.mul(lead * t)).add(side.mul(shift)));
      widths.add(wa + (wb - wa) * grow);
    }
  }

  private static List<V> curve(List<Knot> knots) {
    return curve(knots, .4);
  }

  private static List<V> curve(List<Knot> knots, double strength) {
    List<V> out = new ArrayList<>();
    for (int j = 1; j < knots.size(); j++) {
      RoadPlanningBudget.check();
      Knot a = knots.get(j - 1), b = knots.get(j);
      double chord = a.p.sub(b.p).horizontalLength(), handle = chord * strength;
      V c = a.p.add(a.direction.mul(handle)), d = b.p.sub(b.direction.mul(handle));
      int n = (int) Math.ceil(chord * 1.65 / .4);
      for (int i = j == 1 ? 0 : 1; i <= n; i++) {
        if((i&63)==0)RoadPlanningBudget.check();
        double t = i / (double) n, u = 1 - t;
        out.add(
            a.p
                .mul(u * u * u)
                .add(c.mul(3 * u * u * t))
                .add(d.mul(3 * u * t * t))
                .add(b.p.mul(t * t * t)));
      }
    }
    return out;
  }

  private void extendThroat(Route r) {
    int originalA = r.leadEnd, originalB = r.tailStart;
    // Stay flat THROUGH the first separated cross-section, so the last touching
    // triangle cannot start climbing into the host. Use the full ribbon footprint.
    for (int i = originalA; i < r.points.size() / 2; i++) {
      if((i&63)==0)RoadPlanningBudget.check();
      r.leadEnd = Math.max(r.leadEnd, i);
      if (overlap(roads.get(r.from % 2).mesh, routeSample(r, i)) == null) break;
    }
    for (int i = originalB; i > r.points.size() / 2; i--) {
      if((i&63)==0)RoadPlanningBudget.check();
      r.tailStart = Math.min(r.tailStart, i);
      if (overlap(roads.get(r.to % 2).mesh, routeSample(r, i)) == null) break;
    }
    if (collectorLoops() && r.priority == 1) {
      // A short level landing keeps loop elevation continuous with the shared lane.
      // It is part of the loop itself; it does not extend the collector past the mouth.
      double landing = options.width() * 2 + 4, distance = 0;
      for (int i = 1; i < r.points.size() / 2; i++) {
        distance += r.points.get(i).distance(r.points.get(i - 1));
        if (distance > landing) break;
        r.leadEnd = Math.max(r.leadEnd, i);
      }
      distance = 0;
      for (int i = r.points.size() - 2; i > r.points.size() / 2; i--) {
        distance += r.points.get(i).distance(r.points.get(i + 1));
        if (distance > landing) break;
        r.tailStart = Math.min(r.tailStart, i);
      }
    }
    // A loop joins its continuous collector tangentially. Stay flat until the full ribbon has
    // separated, then use the remaining arc for the climb (and symmetrically on arrival).
    if (collectorLoops()) {
      for (int i = originalA; i < r.points.size() / 2; i++) {
        if((i&63)==0)RoadPlanningBudget.check();
        Sample p = routeSample(r, i);
        boolean touches = false;
        for (int k = 2; k < roads.size(); k++)
          if (roads.get(k).from == r.from && overlap(roads.get(k).mesh, p) != null) touches = true;
        r.leadEnd = Math.max(r.leadEnd, i);
        if (!touches) break;
      }
      for (int i = originalB; i > r.points.size() / 2; i--) {
        if((i&63)==0)RoadPlanningBudget.check();
        Sample p = routeSample(r, i);
        boolean touches = false;
        for (int k = 2; k < roads.size(); k++)
          if (roads.get(k).to == r.to && overlap(roads.get(k).mesh, p) != null) touches = true;
        r.tailStart = Math.min(r.tailStart, i);
        if (!touches) break;
      }
    }
    for (Route other : routes) {
      RoadPlanningBudget.check();
      if (other == r) continue;
      if (r.from == other.from && r.points.get(0).distance(other.points.get(0)) < .1) {
        int limit = Math.min(originalB, r.points.size() / 2);
        for (int i = originalA; i < limit; i++) {
          r.leadEnd = Math.max(r.leadEnd, i);
          if (!near(other, r.points.get(i), r.widths.get(i) / 2 + options.lanes * 2 + 3)) break;
        }
      }
      if (r.to == other.to
          && r.points.get(r.points.size() - 1).distance(other.points.get(other.points.size() - 1))
              < .1) {
        int limit = Math.max(originalA, r.points.size() / 2);
        for (int i = originalB; i > limit; i--) {
          r.tailStart = Math.min(r.tailStart, i);
          if (!near(other, r.points.get(i), r.widths.get(i) / 2 + options.lanes * 2 + 3)) break;
        }
      }
    }
  }

  private V routeTangent(Route r,int i){
    // The circle's endpoint chord is not its tangent. Exact collector frames
    // prevent a narrow triangular hole at any of the four loop mouths.
    if(collectorLoops()&&r.priority==1){
      if(i==0)return direction(ARMS[r.from].mul(-1));
      if(i==r.points.size()-1)return direction(ARMS[r.to]);
    }
    return r.points.get(Math.min(r.points.size()-1,i+1)).sub(r.points.get(Math.max(0,i-1))).horizontalUnit();
  }
  private Sample routeSample(Route r, int i) {
    V before = r.points.get(Math.max(0, i - 1)),
        after = r.points.get(Math.min(r.points.size() - 1, i + 1));
    return new Sample(
        r.points.get(i), routeTangent(r,i).left(), 0, r.widths.get(i) / 2);
  }

  private static boolean near(Route r, V p, double radius) {
    for (int i = 0; i < r.points.size(); i += 3) {
      if((i&63)==0)RoadPlanningBudget.check();
      if (r.points.get(i).sub(p).horizontalLength() < radius) return true;
    }
    return false;
  }

  private Mesh horizontal(Route r) {
    List<Sample> points = new ArrayList<>();
    for (int i = 0; i < r.points.size(); i++) {
      if((i&63)==0)RoadPlanningBudget.check();
      V p = r.points.get(i);
      V d =
          i == 0
              ? r.points.get(1).sub(p)
              : i == r.points.size() - 1
                  ? p.sub(r.points.get(i - 1))
                  : r.points.get(i + 1).sub(r.points.get(i - 1));
      points.add(
          new Sample(new V(p.x(), base, p.z()), routeTangent(r,i).left(), 0, r.widths.get(i) / 2));
    }
    return RoadRibbon.mesh(points, rampSettings());
  }

  private double nominalHeight(Route r,double[] d,double at){
    double h0=hostHeight(r.from,r.points.get(r.leadEnd)),h1=hostHeight(r.to,r.points.get(r.tailStart));
    if(at<=d[r.leadEnd])return h0;if(at>=d[r.tailStart])return h1;
    if(r.plateauStart<0)return h0+(h1-h0)*grade((at-d[r.leadEnd])/(d[r.tailStart]-d[r.leadEnd]));
    if(at<d[r.plateauStart])return h0+(r.layer-h0)*grade((at-d[r.leadEnd])/(d[r.plateauStart]-d[r.leadEnd]));
    if(at>d[r.plateauEnd])return r.layer+(h1-r.layer)*grade((at-d[r.plateauEnd])/(d[r.tailStart]-d[r.plateauEnd]));
    return r.layer;
  }

  private double[] reachable(Route r,double[] d,double from,double to){
    double h0=hostHeight(r.from,r.points.get(r.leadEnd)),h1=hostHeight(r.to,r.points.get(r.tailStart));
    double lo=Double.NEGATIVE_INFINITY,hi=Double.POSITIVE_INFINITY;
    for(double at:new double[]{from,to}){double a=Math.max(0,at-d[r.leadEnd]),b=Math.max(0,d[r.tailStart]-at);
      lo=Math.max(lo,Math.max(h0-a*RoadGrades.MAX_RAMP_GRADE,h1-b*RoadGrades.MAX_RAMP_GRADE));
      hi=Math.min(hi,Math.min(h0+a*RoadGrades.MAX_RAMP_GRADE,h1+b*RoadGrades.MAX_RAMP_GRADE));}
    return new double[]{lo,hi};
  }
  /** Necessary height reachability before the more expensive ramp/ramp crossings.
   * Rejecting here cannot create a valid plan: the same host bounds and local
   * grade edges are a subset of the complete solver's constraints. */
  static void checkLocalGradeBounds(double[] lower,double[] upper,int start,double[] distance){
    double lo=lower[start],hi=upper[start];
    for(int i=1;i<distance.length;i++){
      if((i&255)==0)RoadPlanningBudget.check();
      // Match the full graph's 1e-8 edge relaxation tolerance conservatively.
      double reach=(RoadGrades.MAX_RAMP_GRADE-1e-6)*(distance[i]-distance[i-1])+1e-8;
      lo=Math.max(lower[start+i],lo-reach);hi=Math.min(upper[start+i],hi+reach);
      if(lo>hi+1e-7)throw new IllegalArgumentException("实际交叉处的净高与 15% 纵坡无法同时满足，请扩大范围");
    }
  }
  private List<Mesh> elevateCrossings(List<Mesh> flat) {
    int count=routes.size(),total=0;int[] offset=new int[count];double[][] distance=new double[count][];
    for(int r=0;r<count;r++){
      offset[r]=total;var points=routes.get(r).points;total+=points.size();distance[r]=new double[points.size()];
      for(int i=1;i<points.size();i++)distance[r][i]=distance[r][i-1]+points.get(i).sub(points.get(i-1)).horizontalLength();
    }
    double[] preferred=new double[total],lower=new double[total],upper=new double[total];
    Arrays.fill(lower,Double.NEGATIVE_INFINITY);Arrays.fill(upper,Double.POSITIVE_INFINITY);
    var edges=new ArrayList<InterchangeGradeProfile.Edge>();
    for(int ri=0;ri<count;ri++){
      Route r=routes.get(ri);int n=r.points.size(),start=offset[ri];double[] d=distance[ri];
      if(r.tailStart<=r.leadEnd)throw new IllegalArgumentException(r.name+" 没有独立升降区，请扩大范围");
      double h0=hostHeight(r.from,r.points.get(r.leadEnd)),h1=hostHeight(r.to,r.points.get(r.tailStart));
      for(int i=0;i<n;i++){
        preferred[start+i]=h0+(h1-h0)*grade((d[i]-d[r.leadEnd])/(d[r.tailStart]-d[r.leadEnd]));
        if(i<=r.leadEnd)lower[start+i]=upper[start+i]=h0;
        if(i>=r.tailStart)lower[start+i]=upper[start+i]=h1;
        if(i>0){double reach=(RoadGrades.MAX_RAMP_GRADE-1e-6)*(d[i]-d[i-1]);
          edges.add(new InterchangeGradeProfile.Edge(start+i-1,start+i,-reach));
          edges.add(new InterchangeGradeProfile.Edge(start+i,start+i-1,-reach));}
      }
      for(int k=0;k<=roads.size()+ri;k++){
        if(k==roads.size())checkLocalGradeBounds(lower,upper,start,d);
        Leg host=k<roads.size()?roads.get(k):null;int oi=k-roads.size();
        Route other=host==null?routes.get(oi):null;Mesh against=host==null?flat.get(oi):host.mesh();
        for(var detail:oi==ri?RoadClearance.detailedSelfContacts(flat.get(ri),options.width()*2):RoadClearance.detailedContacts(flat.get(ri),against)){
          var c=detail.contact();
          // A skew trumpet loop can pass over its own approach. Its two remote
          // stations need the same pavement clearance as two different ramps.
          if(oi==ri&&detail.otherFrom()<=c.to()+options.width()*2)continue;
          var mouth=clearanceMouth(r.from,r.to,flat.get(ri),r.leadEnd,r.tailStart,c.from(),c.to());
          var otherMouth=other==null?null:clearanceMouth(other.from,other.to,against,other.leadEnd,other.tailStart,detail.otherFrom(),detail.otherTo());
          var footprint=new RoadClearance.DetailedContact(c,detail.otherFrom(),detail.otherTo(),0,0);
          if(sharedMouthOnCarriageway(flat.get(ri),mouth,against,otherMouth,host,footprint))continue;
          double ours=nominalHeight(r,d,(c.from()+c.to())/2);
          double theirs=host==null?nominalHeight(other,distance[oi],(detail.otherFrom()+detail.otherTo())/2):against.first().center().y();
          if(oi==ri&&Math.abs(ours-theirs)<1e-6){
            double ds=(c.from()+c.to())/2,dt=(detail.otherFrom()+detail.otherTo())/2;
            ours=Math.min(ds-d[r.leadEnd],d[r.tailStart]-ds);
            theirs=Math.min(dt-d[r.leadEnd],d[r.tailStart]-dt);
          }
          boolean above=ours>=theirs;
          double need=Math.max(options.clearance,c.required())+.08;
          int first=InterchangeGradeProfile.floor(d,c.from()),last=InterchangeGradeProfile.ceil(d,c.to());
          if(options.preset==Preset.HYBRID&&other!=null&&other!=r){
            var ourReach=reachable(r,d,c.from(),c.to());var theirReach=reachable(other,distance[oi],detail.otherFrom(),detail.otherTo());
            double aboveDepth=flat.get(ri).settings().thickness()+RoadInfrastructure.girderDepth(flat.get(ri));
            double belowDepth=against.settings().thickness()+RoadInfrastructure.girderDepth(against);
            boolean canAbove=ourReach[1]>=theirReach[0]+need+aboveDepth-1e-7;
            boolean canBelow=theirReach[1]>=ourReach[0]+need+belowDepth-1e-7;
            if(above&&!canAbove&&canBelow)above=false;else if(!above&&!canBelow&&canAbove)above=true;
          }
          if(host!=null){
            double bound=above?base+c.raiseBound()+need-c.required()-.10:base-c.lowerBound()-need+c.required()+.10;
            for(int i=first;i<=last;i++)if(above)lower[start+i]=Math.max(lower[start+i],bound);else upper[start+i]=Math.min(upper[start+i],bound);
          }else{
            int a=InterchangeGradeProfile.floor(distance[oi],detail.otherFrom()),b=InterchangeGradeProfile.ceil(distance[oi],detail.otherTo());
            double depth=above?flat.get(ri).settings().thickness()+RoadInfrastructure.girderDepth(flat.get(ri)):against.settings().thickness()+RoadInfrastructure.girderDepth(against);
            for(int i=first;i<=last;i++)for(int j=a;j<=b;j++)edges.add(new InterchangeGradeProfile.Edge(above?offset[oi]+j:start+i,above?start+i:offset[oi]+j,need+depth));
          }
        }
      }
    }
    double[] y=InterchangeGradeProfile.solve(preferred,lower,upper,edges,offset,distance);var result=new ArrayList<Mesh>();
    for(int ri=0;ri<count;ri++){
      var r=routes.get(ri);var samples=new ArrayList<Sample>();
      for(int i=0;i<r.points.size();i++){V p=r.points.get(i);samples.add(new Sample(new V(p.x(),y[offset[ri]+i],p.z()),routeTangent(r,i).left(),0,r.widths.get(i)/2));}
      Mesh mesh=RoadRibbon.mesh(samples,rampSettings());RoadGrades.validate(mesh);result.add(mesh);
    }
    return result;
  }

  private Mesh elevate(Route r) {
    RoadPlanningBudget.check();
    double[] distance = new double[r.points.size()];
    for (int i = 1; i < distance.length; i++) {
      if((i&63)==0)RoadPlanningBudget.check();
      distance[i] = distance[i - 1] + r.points.get(i).sub(r.points.get(i - 1)).horizontalLength();
    }
    double d0 = distance[r.leadEnd],
        d1 = distance[r.tailStart],
        h0 = hostHeight(r.from, r.points.get(r.leadEnd)),
        h1 = hostHeight(r.to, r.points.get(r.tailStart));
    if (r.plateauStart >= 0) {
      checkClimb(r.name, "汇出后至首个交叉平台", r.layer - h0, distance[r.plateauStart] - d0);
      checkClimb(r.name, "末个交叉平台至汇入", h1 - r.layer, d1 - distance[r.plateauEnd]);
    } else checkClimb(r.name, "汇出与汇入之间的升降区", h1 - h0, d1 - d0);
    List<Sample> samples = new ArrayList<>();
    for (int i = 0; i < r.points.size(); i++) {
      if((i&63)==0)RoadPlanningBudget.check();
      V p = r.points.get(i);
      double y;
      if (i <= r.leadEnd) y = hostHeight(r.from, p);
      else if (i >= r.tailStart) y = hostHeight(r.to, p);
      else {
        double t = (distance[i] - d0) / (d1 - d0);
        if (r.plateauStart >= 0) {
          double begin = distance[r.plateauStart], end = distance[r.plateauEnd];
          if (distance[i] < begin)
            y = h0 + (r.layer - h0) * grade((distance[i] - d0) / (begin - d0));
          else if (distance[i] > end)
            y = r.layer + (h1 - r.layer) * grade((distance[i] - end) / (d1 - end));
          else y = r.layer;
        } else y = h0 + (h1 - h0) * grade(t);
      }
      V delta =
          i == 0
              ? r.points.get(1).sub(p)
              : i == r.points.size() - 1
                  ? p.sub(r.points.get(i - 1))
                  : r.points.get(i + 1).sub(r.points.get(i - 1));
      samples.add(
          new Sample(
              new V(p.x(), y, p.z()), routeTangent(r,i).left(), 0, r.widths.get(i) / 2));
    }
    Style style = options.lanes == 1 ? Style.R1 : Style.R2;
    Settings s =
        new Settings(Mode.CURVE, style, options.width(), 1, .4, 90)
            .rampTurn(RampTurn.AUTO)
            .options(RoadProfile.Options.DEFAULT.traffic(options.leftTraffic));
    try {
      Mesh mesh = RoadRibbon.mesh(samples, s);
      RoadGrades.validate(mesh);
      return mesh;
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          r.name
              + "："
              + e.getMessage()
              + (Boolean.getBoolean("sr.debug")
                  ? " holds="
                      + r.leadEnd
                      + ","
                      + r.tailStart
                      + " plate="
                      + r.plateauStart
                      + ","
                      + r.plateauEnd
                      + " d="
                      + d0
                      + ","
                      + d1
                      + " hp="
                      + (r.plateauStart < 0
                          ? "none"
                          : distance[r.plateauStart] + "," + distance[r.plateauEnd])
                      + " layer="
                      + r.layer
                      + " endpoints="
                      + h0
                      + ","
                      + h1
                  : ""));
    }
  }

  private static void checkClimb(String name, String section, double height, double available) {
    double need = Math.abs(height) * 1.25 / RoadGrades.MAX_RAMP_GRADE;
    if (available + 1e-6 < need)
      throw new IllegalArgumentException(
          String.format(
              Locale.ROOT,
              "%s：%s可用 %.1f 格，升降 %.1f 格按 15%% 纵坡至少需 %.1f 格；这是该段约束，非端点到交点距离",
              name,
              section,
              available,
              Math.abs(height),
              need));
  }

  private static double grade(double t) {
    t = Math.max(0, Math.min(1, t));
    // Parabolic vertical curves at both ends with a constant-grade middle.
    // Unlike a whole-span smoothstep this uses the available climb length evenly.
    double ease = .20, scale = 1 / (1 - ease);
    if (t < ease) return scale * t * t / (2 * ease);
    if (t > 1 - ease) return 1 - scale * (1 - t) * (1 - t) / (2 * ease);
    return scale * (t - ease / 2);
  }

  private double hostHeight(int arm, V point) {
    V local = local(point);
    return height(arm % 2, arm % 2 == 0 ? local.x() : local.z());
  }

  private record Collision(int sample, double height, String reason) {}

  record ClearanceMouth(int arm, boolean entering) {}

  static ClearanceMouth clearanceMouth(int fromArm,int toArm,Mesh mesh,int leadEnd,int tailStart,
                                     double from,double to){
    if(from>=mesh.first().distance()-1e-7&&to<=mesh.samples().get(leadEnd).distance()+1e-7)
      return new ClearanceMouth(fromArm,true);
    if(from>=mesh.samples().get(tailStart).distance()-1e-7&&to<=mesh.last().distance()+1e-7)
      return new ClearanceMouth(toArm,false);
    return null;
  }

  /** The complete contact must belong to the same declared, coplanar interface.
   * A near-zero gap at a single corner does not authorize a crossing elsewhere. */
  static boolean sharedMouth(Mesh a,ClearanceMouth ours,Mesh b,ClearanceMouth other,
                             Leg host,RoadClearance.DetailedContact detail){
    if(ours==null||!detail.coplanar())return false;
    var contact=detail.contact();
    double dot=RoadStructures.sample(a,(contact.from()+contact.to())/2).left()
        .dot(RoadStructures.sample(b,(detail.otherFrom()+detail.otherTo())/2).left());
    if(host!=null){
      if(host.from()<0||host.from()%2!=ours.arm()%2)return false;
      // Collectors are directed carriageways, whereas a primary host is two-way.
      if(host.mesh().settings().style().ramp())
        return (ours.entering()?host.from():host.to())==ours.arm()&&dot>.3;
      return Math.abs(dot)>.3;
    }
    return other!=null&&ours.arm()==other.arm()&&ours.entering()==other.entering()&&dot>.3;
  }

  private boolean sharedMouthOnCarriageway(Mesh a,ClearanceMouth ours,Mesh b,ClearanceMouth other,Leg host,RoadClearance.DetailedContact detail){
    if(!sharedMouth(a,ours,b,other,host,detail))return false;
    if(host==null||host.mesh().settings().style().ramp())return true;
    var contact=detail.contact();var at=RoadQueries.horizontal(b,contact.ours()).sample();
    var sample=RoadStructures.sample(a,(contact.from()+contact.to())/2);
    if(options.preset.three&&Math.abs(sample.left().dot(at.left()))<.85)return false;
    // These endpoint projections are identical for every triangle in one mouth.
    // Keep the exact predicate, but compute each pair only once per plan.
    var sides=mouthSides.computeIfAbsent(a,k->new IdentityHashMap<>()).computeIfAbsent(b,k->{
      var first=RoadQueries.horizontal(b,a.first().center()).sample();var last=RoadQueries.horizontal(b,a.last().center()).sample();
      return new double[]{Math.signum(a.first().center().sub(first.center()).dot(first.left())),
          Math.signum(a.last().center().sub(last.center()).dot(last.left()))};});
    double side=sides[ours.entering()?0:1];
    return side!=0&&side*contact.ours().sub(at.center()).dot(at.left())>=-1e-7;
  }

  static boolean sharedHosts(Leg a,Leg b,RoadClearance.DetailedContact detail){
    if(a.from()<0||b.from()<0||a.from()%2!=b.from()%2||!detail.coplanar())return false;
    boolean aCollector=a.mesh().settings().style().ramp(),bCollector=b.mesh().settings().style().ramp();
    if(!aCollector&&!bCollector)return false;
    double dot=RoadStructures.sample(a.mesh(),(detail.contact().from()+detail.contact().to())/2).left()
        .dot(RoadStructures.sample(b.mesh(),(detail.otherFrom()+detail.otherTo())/2).left());
    if(aCollector&&bCollector)return a.from()==b.from()&&a.to()==b.to()&&dot>.3;
    return Math.abs(dot)>.3;
  }

  private void validateExactClearance(List<Mesh> ramps){
    // Main roads and continuous collectors may only share their own host plane.
    for(int i=0;i<roads.size();i++)for(int j=0;j<i;j++){
      RoadPlanningBudget.check();
      Leg a=roads.get(i),b=roads.get(j);
      for(var detail:RoadClearance.detailedContacts(a.mesh(),b.mesh())){
        if(sharedHosts(a,b,detail))continue;
        exactClearance(a.name(),b.name(),detail.contact());
      }
    }
    for(int i=0;i<routes.size();i++){
      RoadPlanningBudget.check();
      Route route=routes.get(i);Mesh mesh=ramps.get(i);
      for(int k=0;k<=roads.size()+i;k++){
        Leg host=k<roads.size()?roads.get(k):null;
        Route other=host==null?routes.get(k-roads.size()):null;
        Mesh against=host==null?ramps.get(k-roads.size()):host.mesh();
        for(var detail:k==roads.size()+i?RoadClearance.detailedSelfContacts(mesh,options.width()*2):RoadClearance.detailedContacts(mesh,against)){
          var c=detail.contact();
          if(k==roads.size()+i&&detail.otherFrom()<=c.to()+options.width()*2)continue;
          if(c.usableClearance()+1e-7>=Math.max(options.clearance,c.required()))continue;
          var mouth=clearanceMouth(route.from,route.to,mesh,route.leadEnd,route.tailStart,c.from(),c.to());
          var otherMouth=other==null?null:clearanceMouth(other.from,other.to,against,other.leadEnd,
              other.tailStart,detail.otherFrom(),detail.otherTo());
          if(sharedMouthOnCarriageway(mesh,mouth,against,otherMouth,host,detail))continue;
          exactClearance(route.name,host==null?other.name:host.name(),c);
        }
      }
    }
  }

  private void exactClearance(String ours,String other,RoadClearance.Contact contact){
    double required=Math.max(options.clearance,contact.required());
    if(contact.usableClearance()+1e-7>=required)return;
    throw new IllegalArgumentException(String.format(Locale.ROOT,
        "%s 与%s精确路面净空不足：X=%.2f Z=%.2f，%s / 要求 %.2f 格；请扩大范围或调整预设",
        ours,other,contact.ours().x(),contact.ours().z(),RoadClearance.clearanceLabel(contact.usableClearance()),required));
  }

  private Collision conflict(
      Mesh mesh, List<Mesh> mains, List<Mesh> previous, Route route, int index) {
    int mainCount = mains.size();
    List<Mesh> all = new ArrayList<>(mains);
    all.addAll(previous);
    for (int k = 0; k < all.size(); k++) {
      RoadPlanningBudget.check();
      Mesh other = all.get(k);
      for (int i = 0; i < mesh.samples().size(); i += 3) {
        if((i&63)==0)RoadPlanningBudget.check();
        Sample p = mesh.samples().get(i);
        var q = overlap(other, p);
        if (q == null) continue;
        double dy = p.center().y() - q.sample().center().y();
        if (Math.abs(dy) < .2
            && shared(route, i, p, k < mainCount ? null : routes.get(k - mainCount), k, other, q))
          continue;
        double need =
            options.clearance
                + (dy >= 0 ? mesh.settings().thickness() : other.settings().thickness());
        if (Math.abs(dy) < need - .05)
          return new Collision(
              i,
              q.sample().center().y(),
              "与" + (k < mainCount ? roads.get(k).name : routes.get(k - mainCount).name) + "净高不足");
      }
    }
    return null;
  }

  private RoadQueries.Projection overlap(Mesh other, Sample p) {
    if (p.center().x() + p.halfWidth() < other.min().x()
        || p.center().x() - p.halfWidth() > other.max().x()
        || p.center().z() + p.halfWidth() < other.min().z()
        || p.center().z() - p.halfWidth() > other.max().z()) return null;
    var q = RoadQueries.horizontal(other, p.center());
    double along = p.center().sub(q.sample().center()).dot(q.tangent().horizontalUnit());
    double extension = p.halfWidth() * Math.abs(p.left().dot(q.tangent().horizontalUnit()));
    if (q.sample().distance() < 1e-6 && along < -extension
        || q.sample().distance() > other.length() - 1e-6 && along > extension) return null;
    double serviceInset=conformance==null&&servicePreset()?.3:0;
    return q.horizontalDistance() < p.halfWidth() + q.sample().halfWidth() - serviceInset ? q : null;
  }

  private boolean shared(
      Route r, int i, Sample p, Route other, int k, Mesh mesh, RoadQueries.Projection q) {
    double dot = p.left().dot(q.sample().left());
    int host = i <= r.leadEnd + 4 ? r.from % 2 : i >= r.tailStart - 4 ? r.to % 2 : -1;
    if (host < 0) return false;
    if (other == null) return roads.get(k).from % 2 == host && Math.abs(dot) > .3;
    int otherHost =
        q.sample().distance() <= mesh.samples().get(other.leadEnd).distance() + 2
            ? other.from % 2
            : q.sample().distance() >= mesh.samples().get(other.tailStart).distance() - 2
                ? other.to % 2
                : -1;
    return host == otherHost && dot > .3;
  }

  private static double cross(V a, V b) {
    return a.x() * b.z() - a.z() * b.x();
  }

  private InterchangePlanner() {
    throw new AssertionError();
  }
}
