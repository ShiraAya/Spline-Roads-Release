package com.sora.splineroads.core;

import java.util.ArrayList;
import java.util.List;

/** Deterministic geometry shared by preview, server collision and world rendering. */
public final class RoadGeometry {
  public record V(double x, double y, double z) {
    public V add(V v) {
      return new V(x + v.x, y + v.y, z + v.z);
    }

    public V sub(V v) {
      return new V(x - v.x, y - v.y, z - v.z);
    }

    public V mul(double n) {
      return new V(x * n, y * n, z * n);
    }

    public double horizontalLength() {
      return Math.hypot(x, z);
    }

    public double distance(V v) {
      V d = sub(v);
      return Math.sqrt(d.x * d.x + d.y * d.y + d.z * d.z);
    }

    public V horizontalUnit() {
      double d = horizontalLength();
      if (d < 1e-8) throw new IllegalArgumentException("道路出现零长度或折返切线");
      return new V(x / d, 0, z / d);
    }

    public V left() {
      return new V(-z, 0, x);
    }

    public double dot(V v) {
      return x * v.x + y * v.y + z * v.z;
    }
  }

  public record Node(V position, double yaw, double grade) {
    public Node {
      // NBT canonicalizes signed zero; keep in-memory records stable across saves.
      yaw = yaw == 0 ? 0 : yaw;
      grade = grade == 0 ? 0 : grade;
    }

    public V direction() {
      double r = Math.toRadians(yaw);
      return new V(-Math.sin(r), 0, Math.cos(r));
    }
  }

  public enum Mode {
    STRAIGHT,
    CURVE,
    ARC,
    RING,
    AUTO
  }

  public enum Style {
    TWO_LANE,
    FOUR_LANE,
    ONE_WAY,
    UNMARKED,
    RAMP_ONE,
    RAMP_TWO,
    O2_YELLOW,
    O4_YELLOW,
    O6_YELLOW,
    O2_DASHED,
    O2_RAIL,
    O4_RAIL,
    O6_RAIL,
    O2_GREEN,
    O4_GREEN,
    O6_GREEN,
    O1_ONE,
    O2_ONE,
    O3_ONE,
    H4_RAIL,
    H6_RAIL,
    H4_GREEN,
    H6_GREEN,
    H4_YELLOW,
    H6_YELLOW,
    H2_ONE,
    H3_ONE,
    R1,
    R2,
    R1_SHOULDER,
    R2_SHOULDER, O8_YELLOW, O8_RAIL, O8_GREEN, O4_ONE, H8_RAIL, H8_GREEN, H4_ONE, H1_ONE, C1_RAMP, C1_HIGHWAY_RAMP, TOLL, RAILBED;

    public boolean connectorRamp() { return this==C1_RAMP||this==C1_HIGHWAY_RAMP; }

    public boolean ramp() {
      return this == RAMP_ONE
          || this == RAMP_TWO
          || RoadProfile.catalog(this).type() == RoadProfile.Type.RAMP;
    }

    public double defaultWidth() {
      if (RoadProfile.modern(this))
        return RoadProfile.width(
            this, RoadProfile.Options.DEFAULT, RoadProfile.catalog(this).laneWidth());
      return this == FOUR_LANE ? 17 : this == RAMP_ONE ? 5 : 9;
    }
  }

  public enum Structure {
    AUTO,
    GROUND,
    BRIDGE,
    TUNNEL
  }

  public enum RampTurn {
    LEGACY,
    AUTO,
    LEFT,
    RIGHT
  }

  public record Settings(
      Mode mode,
      Style style,
      double width,
      double thickness,
      double tension,
      double arcDegrees,
      double startWidth,
      double endWidth,
      Structure structure,
      int taperVersion,
      RampTurn rampTurn,
      RoadProfile.Options options) {
    public Settings {
      if(options!=null&&!options.routing().controls().isEmpty())mode=Mode.CURVE;
      if(options!=null&&structure==Structure.TUNNEL)options=options.streetscape(options.streetscape().parking(false).separator(RoadStreetscape.Separator.RAIL));
      if(options!=null&&options.lanes().explicit()&&(style.ramp()||!RoadProfile.modern(style)))options=options.lanes(RoadLanes.Counts.AUTO);
      if(options!=null&&!RoadStreetscape.walkLampAllowed(options,structure,style)&&options.streetscape().walkLamps())options=options.streetscape(options.streetscape().walkLamps(false));
      if (options != null && options.sidewalk().enabled()
          && (structure==Structure.TUNNEL || style.ramp() || RoadProfile.catalog(style).type() == RoadProfile.Type.HIGHWAY))
        options = options.sidewalk(options.sidewalk().enabled(false));
    }

    public Settings(
        Mode mode,
        Style style,
        double width,
        double thickness,
        double tension,
        double arcDegrees,
        double startWidth,
        double endWidth,
        Structure structure,
        int taperVersion,
        RampTurn rampTurn) {
      this(
          mode,
          style,
          width,
          thickness,
          tension,
          arcDegrees,
          startWidth,
          endWidth,
          structure,
          taperVersion,
          rampTurn,
          RoadProfile.Options.DEFAULT);
    }

    public Settings options(RoadProfile.Options value) {
      return new Settings(
          mode,
          style,
          width,
          thickness,
          tension,
          arcDegrees,
          startWidth,
          endWidth,
          structure,
          taperVersion,
          rampTurn,
          value);
    }

    public Settings taperVersion(int value) {
      return new Settings(
          mode,
          style,
          width,
          thickness,
          tension,
          arcDegrees,
          startWidth,
          endWidth,
          structure,
          value,
          rampTurn,
          options);
    }

    public boolean separatedPort(boolean start) {
      return newRamp() && taperVersion >= 3
          && (start ? options.ports().sourceOutset() : options.ports().targetOutset()) > 0;
    }

    private boolean shoulderPort(boolean start) {
      return taperVersion >= 4 && separatedPort(start) && (start ? startWidth : endWidth) >= 1
          && (start ? options.ports().sourceJoin() : options.ports().targetJoin())
              == RoadProfile.JoinMode.ADDED;
    }

    private double portBlend(double t, double chord, boolean start) {
      double q = separatedPort(start) ? leadProgress(t, chord, start)
          : (start ? t / startEdge(chord) : (1 - t) / endEdge(chord));
      if (!shoulderPort(start)) return smooth(q);
      double port = start ? startWidth : endWidth;
      double outset = start ? options.ports().sourceOutset() : options.ports().targetOutset();
      double full = (width - port) / 2 + outset, occupied = (width - port) / 2 - .5;
      double grow = smooth(Math.min(1, q / .32));
      double peel = smooth(Math.max(0, Math.min(1, (q - .65) / .35)));
      return (occupied * grow + (full - occupied) * peel) / full;
    }

    public double leadProgress(double t, double chord, boolean start) {
      double time = (start ? t : 1 - t) * (chord + startLead() + endLead()),
          lead = start ? startLead() : endLead();
      double hold = (start ? startWidth : endWidth) < 1 ? 0 : 6;
      return Math.max(0, Math.min(1, (time - hold) / (lead - hold)));
    }

    public boolean newRamp() {
      return laneRamp() && RoadProfile.modern(style);
    }

    public double startLead() {

      if (taperVersion >= 4 && (separatedPort(true) || newRamp() && startWidth < 1))
        return Math.max(options.routing().transition(), width * 5) + (startWidth < 1 ? 0 : 6);
      return separatedPort(true)
          ? (startWidth < 1 ? 18 : 24)
          : newRamp() && startWidth < 1 ? 18 : 6;
    }

    public double endLead() {

      if (taperVersion >= 4 && (separatedPort(false) || newRamp() && endWidth < 1))
        return Math.max(options.routing().transition(), width * 5) + (endWidth < 1 ? 0 : 6);
      return separatedPort(false) ? (endWidth < 1 ? 18 : 24) : newRamp() && endWidth < 1 ? 18 : 6;
    }

    public double startEdge(double chord) {
      return startLead() / (chord + startLead() + endLead());
    }

    public double endEdge(double chord) {
      return endLead() / (chord + startLead() + endLead());
    }

    public double liftParameter(double chord) {
      double start = laneRamp() ? startEdge(chord) : 0, end = laneRamp() ? endEdge(chord) : 0;
      return start + (1 - start - end) * options.liftPosition();
    }

    public double liftAt(double t, double chord) {
      double start = laneRamp() ? startEdge(chord) : 0, end = laneRamp() ? endEdge(chord) : 0;
      double u = Math.max(0, Math.min(1, (t - start) / (1 - start - end))),
          p = options.liftPosition();
      return options.liftHeight()*smooth(u<=p?u/p:(1-u)/(1-p));
    }

    public static double smooth(double u) {
      return u * u * u * (10 + u * (-15 + 6 * u));
    }

    public Settings(
        Mode mode,
        Style style,
        double width,
        double thickness,
        double tension,
        double arcDegrees,
        double startWidth,
        double endWidth,
        Structure structure,
        int taperVersion) {
      this(
          mode,
          style,
          width,
          thickness,
          tension,
          arcDegrees,
          startWidth,
          endWidth,
          structure,
          taperVersion,
          RampTurn.LEGACY);
    }

    public boolean laneRamp() {
      return style.ramp() && !style.connectorRamp() && rampTurn != RampTurn.LEGACY;
    }

    public Settings rampTurn(RampTurn turn) {
      return new Settings(
          mode,
          style,
          width,
          thickness,
          tension,
          arcDegrees,
          startWidth,
          endWidth,
          structure,
          taperVersion,
          turn,
          options);
    }

    public Settings(
        Mode mode,
        Style style,
        double width,
        double thickness,
        double tension,
        double arcDegrees,
        double startWidth,
        double endWidth,
        Structure structure) {
      this(
          mode,
          style,
          width,
          thickness,
          tension,
          arcDegrees,
          startWidth,
          endWidth,
          structure,
          RoadProfile.modern(style) ? 3 : 2);
    }

    public Settings(
        Mode mode,
        Style style,
        double width,
        double thickness,
        double tension,
        double arcDegrees,
        double startWidth,
        double endWidth) {
      this(
          mode, style, width, thickness, tension, arcDegrees, startWidth, endWidth, Structure.AUTO);
    }

    public Settings(
        Mode mode, Style style, double width, double thickness, double tension, double arcDegrees) {
      this(mode, style, width, thickness, tension, arcDegrees, width, width);
    }

    public Settings taper(double start, double end) {
      return new Settings(
          mode,
          style,
          width,
          thickness,
          tension,
          arcDegrees,
          start,
          end,
          structure,
          taperVersion,
          rampTurn,
          options);
    }

    public Settings structure(Structure value) {
      return new Settings(
          mode,
          style,
          width,
          thickness,
          tension,
          arcDegrees,
          startWidth,
          endWidth,
          value,
          taperVersion,
          rampTurn,
          options);
    }

    public double widthAt(double t, double chord) {
      if (!options.ends().equals(RoadTransitions.Ends.NONE))
        return RoadTransitions.width(this, t * chord, chord);
      if (laneRamp()) {
        if (newRamp()) {
          double ea = startEdge(chord), eb = endEdge(chord);
          double ta =
              separatedPort(true)
                  ? leadProgress(t, chord, true)
                  : startWidth < 1 ? Math.min(1, t / ea) : Math.max(0, Math.min(1, (t - ea) / .25));
          double tb =
              separatedPort(false)
                  ? leadProgress(t, chord, false)
                  : endWidth < 1
                      ? Math.min(1, (1 - t) / eb)
                      : Math.max(0, Math.min(1, (1 - t - eb) / .25));


          if (shoulderPort(true)) ta = Math.min(1, ta / .32);
          if (shoulderPort(false)) tb = Math.min(1, tb / .32);
          return width
              + (startWidth - width) * (1 - smooth(ta))
              + (endWidth - width) * (1 - smooth(tb));
        }
        double edge = 6 / (chord + 12), span = Math.min(.45, edge + .25);
        double a = Math.max(0, Math.min(1, (t - edge) / (span - edge))),
            b = Math.max(0, Math.min(1, (1 - t - edge) / (span - edge)));
        return width + (startWidth - width) * (1 - ease(a)) + (endWidth - width) * (1 - ease(b));
      }
      if (style.ramp() && taperVersion >= 2) {
        // Hold entry width until paths have begun to separate; avoid a neck before a Y fork.
        double span =
            Math.min(
                .65,
                Math.max(
                    .45,
                    6
                        * Math.max(Math.abs(startWidth - width), Math.abs(endWidth - width))
                        / chord));
        double hold = span * .25;
        double a = Math.max(0, Math.min(1, (t - hold) / (span - hold))),
            b = Math.max(0, Math.min(1, (1 - t - hold) / (span - hold)));
        return width + (startWidth - width) * (1 - ease(a)) + (endWidth - width) * (1 - ease(b));
      }
      double span =
          Math.min(
              .4,
              Math.max(
                  .12,
                  2 * Math.max(Math.abs(startWidth - width), Math.abs(endWidth - width)) / chord));
      return width
          + (startWidth - width) * (1 - ease(Math.min(1, t / span)))
          + (endWidth - width) * (1 - ease(Math.min(1, (1 - t) / span)));
    }

    private static double ease(double t) {
      return t * t * (3 - 2 * t);
    }

    public static Settings defaults() {
      return new Settings(Mode.AUTO, Style.O2_YELLOW, 9, 1, .35, 90);
    }

    public void validate() {
      if (mode == null
          || style == null
          || structure == null
          || rampTurn == null
          || options == null
          || taperVersion < 1
          || taperVersion > 6
          || !finite(width, thickness, tension, arcDegrees, startWidth, endWidth))
        throw new IllegalArgumentException("参数必须为有限数值");
      if (startWidth < (newRamp() ? .5 : 2)
          || startWidth > RoadLimits.maximumWidth(style)
          || endWidth < (newRamp() ? .5 : 2)
          || endWidth > RoadLimits.maximumWidth(style)
          || width < 2
          || width > RoadLimits.maximumWidth(style)
          || thickness < .125
          || thickness > 2
          || tension < .05
          || tension > 1.5) throw new IllegalArgumentException("宽度 2–"+(int)RoadLimits.maximumWidth(style)+"，厚度 0.125–2，曲线强度 0.05–1.5");
      if (RoadProfile.modern(style) && RoadProfile.layout(this, width).laneWidth() < 2)
        throw new IllegalArgumentException("断面过窄，每根机动车道至少 2 格");
      if (RoadVertical.automatic(this) && (style.ramp() || mode==Mode.RING || options.liftHeight()!=0))
        throw new IllegalArgumentException("自动下潜 / 抬升用于普通道路或高速；请关闭中间临时抬升后使用");
      if (mode == Mode.ARC && (Math.abs(arcDegrees) < 5 || Math.abs(arcDegrees) > 300))
        throw new IllegalArgumentException("圆弧角度绝对值需在 5–300°");
    }
  }

  public record Sample(V center, V left, double distance, double halfWidth) {
    public V at(double offset, double down) {
      return center.add(left.mul(offset)).add(new V(0, -down, 0));
    }
  }

  public record Mesh(
      List<Sample> samples,
      Settings settings,
      V min,
      V max,
      double length,
      boolean closed,
      V controlPoint, List<V> controls, Mesh reference, RoadSlicing.Geometry slicing) {
    public Mesh(List<Sample> samples,Settings settings,V min,V max,double length,boolean closed,V point,List<V> controls,Mesh reference){this(samples,settings,min,max,length,closed,point,controls,reference,null);}
    public Mesh(List<Sample> samples,Settings settings,V min,V max,double length,boolean closed,V point,List<V> controls){this(samples,settings,min,max,length,closed,point,controls,null);}
    public Mesh(List<Sample> samples,Settings settings,V min,V max,double length,boolean closed,V point){this(samples,settings,min,max,length,closed,point,point==null?List.of():List.of(point));}
    public Mesh(
        List<Sample> samples, Settings settings, V min, V max, double length, boolean closed) {
      this(samples, settings, min, max, length, closed, null);
    }

    public Sample first() {
      return samples.get(0);
    }

    public Sample last() {
      return samples.get(samples.size() - 1);
    }
  }

  public static boolean finite(double... values) {
    for (double d : values) if (!Double.isFinite(d)) return false;
    return true;
  }

  /** Close a free road end to the containing block boundary; shared junctions keep exact ports. */
  public static Mesh endCaps(Mesh mesh, int mask) {
    return endCaps(mesh, mask, 0, 0);
  }

  public static Mesh endCaps(Mesh mesh, int mask, double startGrade, double endGrade) {
    if (mask == 0 || mesh.closed()) return mesh;
    List<Sample> points = new ArrayList<>(mesh.samples());
    for (boolean first : new boolean[] {true, false}) {
      if ((mask & (first ? 1 : 2)) == 0) continue;
      Sample p = first ? mesh.first() : mesh.last();
      V forward = p.left().left().mul(first ? 1 : -1);
      boolean xAxis = Math.abs(forward.x()) > .999999, zAxis = Math.abs(forward.z()) > .999999;
      if (!xAxis && !zAxis) continue;
      double value = xAxis ? p.center().x() : p.center().z(),
          sign = xAxis ? forward.x() : forward.z();
      double edge = sign > 0 ? Math.ceil(value - 1e-7) : Math.floor(value + 1e-7);
      double amount = Math.abs(edge - value);
      if (amount < 1e-6 || amount > 1) continue;
      double grade = first ? -startGrade : endGrade;
      V center = p.center().add(forward.mul(amount)).add(new V(0, grade * amount, 0));
      Sample cap = new Sample(center, p.left(), 0, p.halfWidth());
      if (first) points.add(0, cap);
      else points.add(cap);
    }
    List<Sample> out = new ArrayList<>();
    V min = mesh.min(), max = mesh.max(), prior = null;
    double distance = 0;
    for (Sample p : points) {
      if (prior != null) distance += p.center().distance(prior);
      out.add(new Sample(p.center(), p.left(), distance, p.halfWidth()));
      prior = p.center();
      for (int side : new int[] {-1, 1}) {
        V edge = p.at(side * p.halfWidth(), 0);
        min =
            new V(
                Math.min(min.x(), edge.x()),
                Math.min(min.y(), edge.y() - mesh.settings().thickness()),
                Math.min(min.z(), edge.z()));
        max =
            new V(
                Math.max(max.x(), edge.x()),
                Math.max(max.y(), edge.y()),
                Math.max(max.z(), edge.z()));
      }
    }
    return new Mesh(
        List.copyOf(out), mesh.settings(), min, max, distance, false, mesh.controlPoint(),mesh.controls());
  }

  private static void validate(Node n) {
    if (!finite(n.position.x, n.position.y, n.position.z, n.yaw, n.grade) || Math.abs(n.grade) > .5)
      throw new IllegalArgumentException("端点数值无效，坡度范围为 -50%–50%");
    if (Math.abs(n.position.x) > 29999000 || Math.abs(n.position.z) > 29999000)
      throw new IllegalArgumentException("超出世界边界");
  }

  public static Mesh build(Node a, Node b, Settings s) {
    if(s.options().attachments().points().isEmpty())return buildBase(a,b,s);
    Settings plain=s.options(s.options().attachments(RoadAttachments.Data.EMPTY));
    return RoadAttachments.deform(buildBase(a,b,plain),s);
  }
  private static Mesh buildBase(Node a, Node b, Settings s) {
    if (s.mode() == Mode.AUTO)
      return RoadPlanner.plan(RoadPlanner.Hint.free(a), RoadPlanner.Hint.free(b), s).mesh();
    validate(a);
    validate(b);
    s.validate();
    V delta = b.position.sub(a.position);
    double chord = delta.horizontalLength();
    if (chord < 2 || chord > RoadLimits.MAX_ENDPOINT_DISTANCE)
      throw new IllegalArgumentException("端点水平距离需为 2–" + RoadLimits.MAX_ENDPOINT_DISTANCE + " 格");
    Path path = new Path(a, b, s);
    // Oversample first to choose a near-constant distance step. Analytical tangents avoid seams.
    double estimate = 0;
    V last = path.point(0);
    for (int i = 1; i <= 128; i++) {
      V p = path.point(i / 128.0);
      estimate += p.distance(last);
      last = p;
    }
    if (estimate > RoadLimits.MAX_PATH_LENGTH)
      throw new IllegalArgumentException(
          "单段道路曲线总长不能超过 " + RoadLimits.MAX_PATH_LENGTH + " 格，请增加中间端点");
    int count = Math.max(8, (int) Math.ceil(estimate / .4));
    if (s.mode == Mode.RING && count % 2 != 0) count++;
    List<Sample> samples = new ArrayList<>();
    double distance = 0;
    V min = new V(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY),
        max = new V(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE);
    Sample prev = null;
    java.util.SortedSet<Double> parameters = new java.util.TreeSet<>();
    for (int i = 0; i <= count; i++) parameters.add(i / (double) count);
    if(path.through!=null)path.through.knots(parameters);
    if (s.laneRamp()) {
      parameters.add(s.startEdge(chord));
      parameters.add(1 - s.endEdge(chord));
    }
    if (s.options().liftHeight() != 0) parameters.add(s.liftParameter(chord));

    if (RoadVertical.automatic(s)){parameters.add(RoadVertical.rising(s)?.30:.45);parameters.add(.5);parameters.add(RoadVertical.rising(s)?.70:.55);}
    // Endpoint padding is invariant along the road. Previously all four endpoint/tangent
    // evaluations and width layouts were repeated at every sample.
    boolean fitGrid=!s.style().ramp()&&RoadProfile.modern(s.style())&&s.options().routing().fitEdges()&&s.mode()!=Mode.RING;
    double firstPadding=fitGrid&&s.options().ends().start()==null?gridPadding(path.point(0),path.tangent(0),s.widthAt(0,chord)/2):0;
    double endPadding=fitGrid&&s.options().ends().end()==null?gridPadding(path.point(1),path.tangent(1),s.widthAt(1,chord)/2):0;
    for (double t : parameters) {
      V p = path.point(t);
      V tangent = path.tangent(t);
      V left = tangent.horizontalUnit().left();
      double half = s.widthAt(t, chord) / 2;
      if(fitGrid) {
        double blend=Settings.smooth(t);
        half+=firstPadding*(1-blend)+endPadding*blend;
      }
      if (prev != null) {
        if (prev.left().dot(left) < 0) throw new IllegalArgumentException("道路出现急剧折返，请拉远端点或切换智能选线");
        V d = p.sub(prev.center);
        double h = d.horizontalLength();
        if (h < 1e-7 || Math.abs(d.y) / h > .505)
          throw new IllegalArgumentException("道路坡度超过 50%，请拉长道路或调整端点坡度");
        if (Math.abs(d.y)/h>RoadVertical.limit(s)+.0001)
          throw new IllegalArgumentException(String.format(java.util.Locale.ROOT,"桥隧坡度超过 %.0f%%；请降低高差、延长道路或开启端点调整",RoadVertical.limit(s)*100));
        if (RoadTunnel.dipping(s) && p.y()<RoadTunnel.minimum(a,b,s)-1e-6)
          throw new IllegalArgumentException("端点坡度使道路越过最低点；请减小端点坡度");
        if (RoadVertical.rising(s) && p.y()>RoadVertical.maximum(a,b,s)+1e-6)
          throw new IllegalArgumentException("端点坡度使桥面越过设定最高点；请减小端点坡度");
        // Offset curves must remain forward-facing: reject a folded inner edge.
        for (int side : new int[] {-1, 1}) {
          V edge = p.add(left.mul(side * half)).sub(prev.at(side * prev.halfWidth(), 0));
          if (edge.dot(d) <= 0) throw new IllegalArgumentException("弯道半径过小，道路内侧重叠；请减小宽度或放大弯道");
        }
        distance += p.distance(prev.center);
      }
      Sample sample = new Sample(p, left, distance, half);
      samples.add(sample);
      prev = sample;
      for (int side : new int[] {-1, 1}) {
        V edge = sample.at(side * half, 0);
        min =
            new V(
                Math.min(min.x, edge.x),
                Math.min(min.y, edge.y - s.thickness),
                Math.min(min.z, edge.z));
        max = new V(Math.max(max.x, edge.x), Math.max(max.y, edge.y), Math.max(max.z, edge.z));
      }
    }
    count = samples.size() - 1;
    // Broad-phase spatial bins avoid quadratic self-crossing checks on long city roads.
    // STRAIGHT is provably monotone in XZ (Path.horizontal=a+delta*t). It cannot cross
    // itself. Grade, width-fold and endpoint validation above remain fully active; curved,
    // ring and automatic curved candidates still run the unchanged exact test below.
    if(s.mode()!=Mode.STRAIGHT) {
    java.util.Map<Long, java.util.List<Integer>> bins = new java.util.HashMap<>();
    for (int j = 0; j < count; j++) {
      V r = samples.get(j).center, u = samples.get(j + 1).center;
      int x0 = (int) Math.floor(Math.min(r.x, u.x) / 8),
          x1 = (int) Math.floor(Math.max(r.x, u.x) / 8),
          z0 = (int) Math.floor(Math.min(r.z, u.z) / 8),
          z1 = (int) Math.floor(Math.max(r.z, u.z) / 8);
      java.util.Set<Integer> candidates = new java.util.HashSet<>();
      for (int x = x0; x <= x1; x++)
        for (int z = z0; z <= z1; z++)
          candidates.addAll(
              bins.getOrDefault(((long) x << 32) ^ (z & 0xffffffffL), java.util.List.of()));
      for (int i : candidates) {
        if (j < i + 2 || (s.mode == Mode.RING && i == 0 && j == count - 1)) continue;
        V p = samples.get(i).center, q = samples.get(i + 1).center;
        double cross = cross(q.sub(p), u.sub(r));
        if (Math.abs(cross) < 1e-9) continue;
        double t = cross(r.sub(p), u.sub(r)) / cross, v = cross(r.sub(p), q.sub(p)) / cross;
        if (t > 1e-6
            && t < 1 - 1e-6
            && v > 1e-6
            && v < 1 - 1e-6
            && Math.abs(p.y + (q.y - p.y) * t - r.y - (u.y - r.y) * v) < s.thickness + 2)
          throw new IllegalArgumentException("道路在同一高度自相交，请拆分为独立路段");
      }
      for (int x = x0; x <= x1; x++)
        for (int z = z0; z <= z1; z++)
          bins.computeIfAbsent(
                  ((long) x << 32) ^ (z & 0xffffffffL), k -> new java.util.ArrayList<>())
              .add(j);
    }
    }
    if (!s.options().ends().equals(RoadTransitions.Ends.NONE)) {
      List<Sample> tapered = new ArrayList<>();
      for (Sample p : samples)
        tapered.add(
            new Sample(
                p.center(),
                p.left(),
                p.distance(),
                RoadTransitions.width(s, p.distance(), distance) / 2));
      var ribbon = RoadRibbon.mesh(tapered, s);
      return new Mesh(
          ribbon.samples(),
          s,
          ribbon.min(),
          ribbon.max(),
          ribbon.length(),
          s.mode == Mode.RING,
          path.point(s.liftParameter(chord)),heightControls(path,s,chord));
    }
    return new Mesh(
        List.copyOf(samples),
        s,
        min,
        max,
        distance,
        s.mode == Mode.RING,
        path.point(s.liftParameter(chord)),heightControls(path,s,chord));
  }

  private static List<V> heightControls(Path path,Settings s,double chord){
    return List.of(path.point(s.liftParameter(chord)));
  }
  public static Mesh controls(Mesh original,Mesh changed){
    var points=original.controls().stream().map(p->{var q=RoadQueries.horizontal(changed,p);return new V(p.x(),q.sample().center().y(),p.z());}).toList();
    return new Mesh(changed.samples(),changed.settings(),changed.min(),changed.max(),changed.length(),changed.closed(),points.isEmpty()?null:points.get(0),points);
  }
  private static double gridPadding(V p, V tangent, double half) {
    V side = tangent.horizontalUnit().left();
    boolean ns = Math.abs(side.x()) > .999999, ew = Math.abs(side.z()) > .999999;
    double coordinate = ns ? p.x() : p.z();
    return (ns || ew) && Math.abs(coordinate - Math.floor(coordinate) - .5) < 1e-7
        ? Math.ceil(coordinate + half - 1e-7) - coordinate - half
        : 0;
  }

  private static double cross(V a, V b) {
    return a.x * b.z - a.z * b.x;
  }

  private static double elevation(double t, double a, double b, double ma, double mb) {
    if (a == b && ma == 0 && mb == 0) return a;
    double t2 = t * t, t3 = t2 * t;
    return (2 * t3 - 3 * t2 + 1) * a
        + (t3 - 2 * t2 + t) * ma
        + (-2 * t3 + 3 * t2) * b
        + (t3 - t2) * mb;
  }

  private static final class Path {
    final Node a, b;
    final Settings s;
    final V delta, center, radius;
    final double length, angle, chord;
    final RoadLoop loop;
    final List<V> bezier;
    final RoadThroughCurve through;

    Path(Node a, Node b, Settings s) {
      this.a = a;
      this.b = b;
      this.s = s;
      delta = b.position.sub(a.position);
      chord = delta.horizontalLength();
      angle = s.mode == Mode.RING ? 2 * Math.PI : Math.toRadians(s.arcDegrees);
      V middle = a.position.add(b.position).mul(.5);
      center =
          s.mode == Mode.ARC
              ? middle.add(delta.horizontalUnit().left().mul(chord / (2 * Math.tan(angle / 2))))
              : middle;
      radius = a.position.sub(center);
      length =
          s.mode == Mode.ARC || s.mode == Mode.RING
              ? Math.abs(angle) * radius.horizontalLength()
              : chord;
      var route = s.options().routing();
      through=route.through()&&!route.controls().isEmpty()?new RoadThroughCurve(a,b,s):null;
      if(through!=null&&(s.options().liftHeight()!=0||RoadVertical.automatic(s)))throw new IllegalArgumentException("经过点已指定高程，请关闭中间抬升及自动桥隧纵坡");
      if(route.controls().isEmpty()||through!=null)bezier=List.of();
      else {
        var points=new ArrayList<V>();points.add(a.position());
        double ha=Math.min(chord*s.tension(),a.position().distance(route.controls().get(0).position())*.5);
        double hb=Math.min(chord*s.tension(),b.position().distance(route.controls().get(route.controls().size()-1).position())*.5);
        points.add(a.position().add(a.direction().mul(ha)).add(new V(0,a.grade()*ha,0)));
        for(var c:route.controls()){if(c.position().distance(a.position())>RoadLimits.MAX_ENDPOINT_DISTANCE||c.position().distance(b.position())>RoadLimits.MAX_ENDPOINT_DISTANCE)throw new IllegalArgumentException("控制点距离端点不能超过2048格");points.add(c.position());}
        points.add(b.position().sub(b.direction().mul(hb)).add(new V(0,-b.grade()*hb,0)));points.add(b.position());bezier=List.copyOf(points);
      }
      if (s.laneRamp()
          && (route.kind() == RoadProfile.RouteKind.LOOP_LEFT
              || route.kind() == RoadProfile.RouteKind.LOOP_RIGHT)) {
        if (route.radius() <= s.width() / 2 + 1)
          throw new IllegalArgumentException("环绕半径需大于路宽的一半加 1 格");
        V aa = a.position.add(a.direction().mul(s.startLead())).add(portShift(true));
        V bb = b.position.sub(b.direction().mul(s.endLead())).add(portShift(false));
        loop =
            new RoadLoop(
                aa,
                bb,
                a.direction(),
                b.direction(),
                route.radius(),
                route.kind() == RoadProfile.RouteKind.LOOP_RIGHT);
      } else loop = null;

    }

    // The plan curve and vertical profile share arc length. There is no separate
    // straight climbing leg: a turn can rise or descend over its entire length.

    private V portShift(boolean first) {
      double side =
          s.taperVersion() >= 3
              ? RoadProfile.trafficSign(s.options().leftTraffic())
              : s.options().leftTraffic() ? 1 : -1;
      double width = first ? s.startWidth() : s.endWidth();
      double outset =
          first ? s.options().ports().sourceOutset() : s.options().ports().targetOutset();
      return (first ? a : b)
          .direction()
          .left()
          .mul(
              s.separatedPort(first)
                  ? side * ((s.width() - width) / 2 + outset)
                  : s.newRamp() && width < 1 ? side * (s.width() - width) / 2 : 0);
    }

    private V rampPoint(double t) {

      double span = chord + s.startLead() + s.endLead(),
          edge = s.startEdge(chord),
          endEdge = s.endEdge(chord);
      V da = new V(a.direction().x(), a.grade(), a.direction().z()),
          db = new V(b.direction().x(), b.grade(), b.direction().z());
      V shiftA = portShift(true), shiftB = portShift(false);
      if (t <= edge)
        return a.position
            .add(da.mul(t * span))
            .add(
                shiftA.mul(
                    s.portBlend(t, chord, true)));
      if (t >= 1 - endEdge)
        return b.position
            .sub(db.mul((1 - t) * span))
            .add(
                shiftB.mul(
                    s.portBlend(t, chord, false)));
      V aa = a.position.add(da.mul(s.startLead())).add(shiftA),
          bb = b.position.sub(db.mul(s.endLead())).add(shiftB);
      double v = (t - edge) / (1 - edge - endEdge),
          u = 1 - v,
          handle = aa.sub(bb).horizontalLength() * s.tension;
      V c1 = aa.add(a.direction().mul(handle)), c2 = bb.sub(b.direction().mul(handle));
      V h =
          aa.mul(u * u * u)
              .add(c1.mul(3 * u * u * v))
              .add(c2.mul(3 * u * v * v))
              .add(bb.mul(v * v * v));
      double derivative = handle * 3;
      if (loop != null) {
        h = loop.point(v);
        derivative = loop.length();
      }
      var route = s.options().routing();
      double control = s.options().liftPosition();
      double bump = Settings.smooth(v <= control ? v / control : (1 - v) / (1 - control));
      h = h.add(new V(route.offsetX() * bump, 0, route.offsetZ() * bump));
      return new V(h.x, elevation(v, aa.y, bb.y, a.grade * derivative, b.grade * derivative), h.z);
    }

    V horizontal(double t) {
      if (s.laneRamp()) return rampPoint(t);
      return switch (s.mode) {
        case AUTO -> throw new IllegalStateException("AUTO must be resolved before sampling");
        case STRAIGHT -> a.position.add(delta.mul(t));
        case CURVE -> {
          double u = 1 - t;
          V c1 = a.position.add(a.direction().mul(chord * s.tension));
          V c2 = b.position.sub(b.direction().mul(chord * s.tension));
          yield a.position
              .mul(u * u * u)
              .add(c1.mul(3 * u * u * t))
              .add(c2.mul(3 * u * t * t))
              .add(b.position.mul(t * t * t));
        }
        case ARC, RING -> {
          double c = Math.cos(angle * t), sn = Math.sin(angle * t);
          yield new V(
              center.x + radius.x * c - radius.z * sn, 0, center.z + radius.x * sn + radius.z * c);
        }
      };
    }

    private static V evaluate(List<V> points,double t){var work=new ArrayList<>(points);for(int n=work.size()-1;n>0;n--)for(int i=0;i<n;i++)work.set(i,work.get(i).mul(1-t).add(work.get(i+1).mul(t)));return work.get(0);}
    V tangent(double t) {
      if(through!=null)return through.tangent(t);
      if(!bezier.isEmpty()){var derivative=new ArrayList<V>();for(int i=1;i<bezier.size();i++)derivative.add(bezier.get(i).sub(bezier.get(i-1)).mul(bezier.size()-1));return evaluate(derivative,t);}
      if (s.laneRamp()) {
        // Cross sections in an added-lane taper stay perpendicular to the host road.
        if ((s.newRamp()) && (s.startWidth() < 1 || s.separatedPort(true)) && t <= s.startEdge(chord))
          return a.direction();
        if ((s.newRamp())
            && (s.endWidth() < 1 || s.separatedPort(false))
            && t >= 1 - s.endEdge(chord)) return b.direction();
        if (t < 1e-7) return a.direction();
        if (t > 1 - 1e-7) return b.direction();
        return rampPoint(Math.min(1, t + 1e-6)).sub(rampPoint(Math.max(0, t - 1e-6)));
      }
      return switch (s.mode) {
        case AUTO -> throw new IllegalStateException("AUTO must be resolved before sampling");
        case STRAIGHT -> delta;
        case ARC, RING -> {
          V r = horizontal(t).sub(center);
          yield new V(-r.z * angle, 0, r.x * angle);
        }
        case CURVE -> {
          V c1 = a.position.add(a.direction().mul(chord * s.tension)),
              c2 = b.position.sub(b.direction().mul(chord * s.tension));
          double u = 1 - t;
          yield c1.sub(a.position)
              .mul(3 * u * u)
              .add(c2.sub(c1).mul(6 * u * t))
              .add(b.position.sub(c2).mul(3 * t * t));
        }
      };
    }

    V point(double t) {
      if(through!=null)return through.point(t);
      if(!bezier.isEmpty())return evaluate(bezier,t).add(new V(0,s.liftAt(t,chord),0));
      if (s.laneRamp()) return rampPoint(t).add(new V(0, s.liftAt(t, chord), 0));
      V h = horizontal(t);
      double y;
      if (s.mode == Mode.RING) {
        y =
            t <= .5
                ? elevation(
                    t * 2, a.position.y, b.position.y, a.grade * length / 2, b.grade * length / 2)
                : elevation(
                    t * 2 - 1,
                    b.position.y,
                    a.position.y,
                    b.grade * length / 2,
                    a.grade * length / 2);
      } else {
        double scaleA = s.mode == Mode.CURVE ? tangent(0).horizontalLength() : length;
        double scaleB = s.mode == Mode.CURVE ? tangent(1).horizontalLength() : length;
        y = RoadVertical.automatic(s)?RoadVertical.height(t,a,b,s,scaleA,scaleB)
            :elevation(t, a.position.y, b.position.y, a.grade * scaleA, b.grade * scaleB);
      }
      return new V(h.x, y + s.liftAt(t, chord), h.z);
    }
  }

  private RoadGeometry() {}
}
