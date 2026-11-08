package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadProfile.*;
import java.util.*;

/** A shared endpoint cross-section, independent of construction order and pavement sampling. */
public final class RoadTransitions {
  private record SectionLayouts(Layout right,Layout left){}
  // A transition evaluates the same immutable end section at hundreds of
  // samples. Construct its options/catalog once, with both traffic directions.
  // Layout values do not retain Section keys or any world/mesh.
  private static final WeakIdentityCache<Section,SectionLayouts> SECTION_LAYOUTS=
      new WeakIdentityCache<>(512,1024,v->2);

  public record Section(
      Style style,
      double width,
      boolean cycle,
      boolean cycleRail,
      boolean curb,
      OuterRail outerRail,RoadSidewalks.Config sidewalk,boolean cycleAsphalt,Port port,RoadStreetscape.Config streetscape,RoadLanes.Counts lanes) {
    public Section(Style style,double width,boolean cycle,boolean cycleRail,boolean curb,OuterRail rail,RoadSidewalks.Config walk,boolean asphalt,Port port,RoadStreetscape.Config streetscape){this(style,width,cycle,cycleRail,curb,rail,walk,asphalt,port,streetscape,RoadLanes.Counts.AUTO);}
    public Section(Style style,double width,boolean cycle,boolean cycleRail,boolean curb,OuterRail rail,RoadSidewalks.Config walk,boolean asphalt,Port port){this(style,width,cycle,cycleRail,curb,rail,walk,asphalt,port,RoadStreetscape.Config.DEFAULT.separator(cycleRail?RoadStreetscape.Separator.RAIL:RoadStreetscape.Separator.LINE));}
    public Section(Style style,double width,boolean cycle,boolean cycleRail,boolean curb,OuterRail rail,RoadSidewalks.Config walk,boolean asphalt){this(style,width,cycle,cycleRail,curb,rail,walk,asphalt,null);}
    public Section(Style style,double width,boolean cycle,boolean cycleRail,boolean curb,OuterRail rail,RoadSidewalks.Config walk){this(style,width,cycle,cycleRail,curb,rail,walk,false);}
    public Section(Style style,double width,boolean cycle,boolean cycleRail,boolean curb,OuterRail outerRail){this(style,width,cycle,cycleRail,curb,outerRail,null);}
    public Section {
      if(lanes==null)lanes=RoadLanes.Counts.AUTO;
      if (style == null || outerRail == null || !Double.isFinite(width) || width < 2 || width > RoadLimits.maximumWidth(style))
        throw new IllegalArgumentException("接缝断面无效");
    }

    public static Section of(Settings s) {
      var o = s.options();
      return new Section(s.style(), s.width(), o.cycle(), o.cycleRail(), o.curb(), o.outerRail(),o.sidewalk(),o.cycleAsphalt(),o.ends().port(),o.streetscape(),o.lanes());
    }

    public Settings settings(boolean left) {
      return new Settings(Mode.STRAIGHT, style, width, 1, .4, 90)
          .options(
              Options.DEFAULT.traffic(left).extras(cycle, cycleRail, curb).cycleFinish(!cycle?Options.CycleFinish.NONE:cycleAsphalt?Options.CycleFinish.ASPHALT:Options.CycleFinish.GREEN).outerRail(outerRail).sidewalk(sidewalk==null?RoadSidewalks.Config.DEFAULT:sidewalk).ends(Ends.NONE.port(port)).streetscape(streetscape).lanes(lanes));
    }
    public Section port(Port value){return new Section(style,width,cycle,cycleRail,curb,outerRail,sidewalk,cycleAsphalt,value,streetscape,lanes);}
    public Layout layout(boolean left){
      var layouts=SECTION_LAYOUTS.get(this,s->new SectionLayouts(s.makeLayout(false),s.makeLayout(true)));
      return left?layouts.left():layouts.right();
    }
    private Layout makeLayout(boolean left){
      var base=RoadProfile.layout(settings(left),width);
      if(port==null)return base;
      return new Layout(base.catalog(),(port.motorMax()-port.motorMin()-port.median())/base.catalog().lanes(),port.median(),port.motorMin(),port.motorMax(),base.cycleWidth(),base.curbWidth(),base.shoulderWidth(),base.outside(),port.dividers(),port.medianCenter());
    }
  }

  /** Exact cross-section at a trimmed street / junction seam, in the approach frame. */
  public record Port(double motorMin,double motorMax,double median,List<Double> dividers,
      double curbLeft,double curbRight,double medianCenter) {
    public Port(double motorMin,double motorMax,double median,List<Double> dividers,double curbLeft,double curbRight){this(motorMin,motorMax,median,dividers,curbLeft,curbRight,0);}
    public Port {dividers=List.copyOf(dividers);if(!RoadGeometry.finite(motorMin,motorMax,median,curbLeft,curbRight,medianCenter)
        ||motorMin>=motorMax||Math.abs(motorMin)>64||Math.abs(motorMax)>64||median>10||median<0||curbLeft<0||curbRight<0||curbLeft>8||curbRight>8||dividers.size()>24
        ||dividers.stream().anyMatch(d->!Double.isFinite(d)||Math.abs(d)>64))throw new IllegalArgumentException("路口接缝断面无效");}
  }
  public record Ends(Section start, Section end, boolean persistent,double trimmedStart,double trimmedEnd,Port port,double paintPhase,double startHold,double endHold) {
    public Ends(Section start,Section end,boolean persistent,double trimmedStart,double trimmedEnd,Port port,double paintPhase){this(start,end,persistent,trimmedStart,trimmedEnd,port,paintPhase,0,0);}
    public Ends hold(double a,double b){return new Ends(start,end,persistent,trimmedStart,trimmedEnd,port,paintPhase,a,b);}
    public Ends(Section start,Section end,boolean persistent,double trimmedStart,double trimmedEnd,Port port){this(start,end,persistent,trimmedStart,trimmedEnd,port,0);}
    public Ends(Section start,Section end,boolean persistent){this(start,end,persistent,0,0,null);}
    public Ends {if(!RoadGeometry.finite(trimmedStart,trimmedEnd,paintPhase,startHold,endHold)||startHold<0||endHold<0||startHold>8192||endHold>8192||trimmedStart<0||trimmedEnd<0||trimmedStart>8192||trimmedEnd>8192)throw new IllegalArgumentException("道路裁剪范围无效");}
    public Ends trim(double a,double b){return new Ends(start,end,persistent,trimmedStart+a,trimmedEnd+b,port,paintPhase,startHold,endHold);}
    public Ends port(Port p){return new Ends(start,end,persistent,trimmedStart,trimmedEnd,p,paintPhase,startHold,endHold);}
    public Ends paintPhase(double phase){return new Ends(start,end,persistent,trimmedStart,trimmedEnd,port,phase,startHold,endHold);}
    public Ends(Section start, Section end) {
      this(start, end, false);
    }

    public static final Ends NONE = new Ends(null, null, false);
  }

  public static boolean compatible(Settings a, Settings b) {
    if (a.style().ramp() || b.style().ramp()) return true;
    var x = RoadProfile.catalog(a);
    var y = RoadProfile.catalog(b);
    return (x.type() == y.type() || x.type() == Type.ORDINARY && y.type() == Type.HIGHWAY
        || x.type() == Type.HIGHWAY && y.type() == Type.ORDINARY)
        && x.twoWay() == y.twoWay()
        && Math.abs(RoadLanes.counts(a).forward()-RoadLanes.counts(b).forward())<=1
        && Math.abs(RoadLanes.counts(a).reverse()-RoadLanes.counts(b).reverse())<=1;
  }

  public static void requireCompatible(Settings a, Settings b) {
    if (!compatible(a, b))
      throw new IllegalArgumentException("接点两侧断面不匹配："+description(a)+" ↔ "+description(b)+"；需单双向一致，且每方向车道数最多相差 1");
  }
  private static String description(Settings s){return RoadLanes.counts(s).label();}

  /**
   * Both sides independently choose the same complete section, even after rebuilding a neighbor.
   */
  public static Section common(Settings a, Settings b) {
    requireCompatible(a, b);
    Section x = Section.of(a), y = Section.of(b);
    Comparator<Section> order =
        Comparator.comparingInt((Section s) -> RoadProfile.catalog(s.settings(false)).lanes())
            .thenComparingDouble(Section::width)
            .thenComparingInt(s -> RoadLanes.counts(s.settings(false)).forward())
            .thenComparingInt(s -> RoadLanes.counts(s.settings(false)).reverse())
            .thenComparing(s -> s.style().name())
            .thenComparing(Section::cycle)
            .thenComparing(Section::cycleAsphalt)
            .thenComparing(Section::curb)
            .thenComparing(Section::cycleRail)
            .thenComparing(s -> s.outerRail().name())
            .thenComparing(s -> s.port()==null?"":s.port().toString())
            .thenComparing(s -> s.sidewalk()==null?"":s.sidewalk().toString())
            .thenComparing(s -> s.streetscape().toString())
            .thenComparing(s -> s.lanes().toString());
    Section chosen=order.compare(x,y)>=0?x:y;
    var wx=a.options().sidewalk();var wy=b.options().sidewalk();
    Comparator<RoadSidewalks.Config> walkOrder=Comparator.comparing((RoadSidewalks.Config w)->w.enabled())
        .thenComparingInt(RoadSidewalks.Config::width).thenComparing(w->w.side().name())
        .thenComparing(w->w.material()).thenComparing(w->w.smooth())
        .thenComparing(w->w.tactile());
    var walk=(walkOrder.compare(wx,wy)>=0?wx:wy).tactile(wx.enabled()&&wy.enabled()&&wx.tactile()&&wy.tactile());
    var shared=new Section(chosen.style(),chosen.width(),chosen.cycle(),chosen.cycleRail(),chosen.curb(),chosen.outerRail(),walk,chosen.cycleAsphalt(),chosen.port(),chosen.streetscape(),chosen.lanes());
    // Both sides use the same physical rail gap and lane axes at a tunnel mouth.
    // An exact port keeps the transition continuous when green and rail widths differ.
    return a.structure()==Structure.TUNNEL||b.structure()==Structure.TUNNEL?TunnelMedian.seam(shared):shared;
  }

  public static Settings join(Settings s, Settings a, Settings b) {
    if (s.style().ramp() || !RoadProfile.modern(s.style())) return s;
    Section start = a == null || a.style().ramp() ? null : common(s, a);
    Section end = b == null || b.style().ramp() ? null : common(s, b);
    return ends(s, start, end);
  }

  public static Settings ends(Settings s, Section a, Section b) {
    if (s.options().ends().persistent()) {
      if (a == null) a = s.options().ends().start();
      if (b == null) b = s.options().ends().end();
    }
    return s.taper(a == null ? s.width() : a.width(), b == null ? s.width() : b.width())
        .options(s.options().ends(new Ends(a,b,s.options().ends().persistent(),s.options().ends().trimmedStart(),s.options().ends().trimmedEnd(),s.options().ends().port(),s.options().ends().paintPhase(),s.options().ends().startHold(),s.options().ends().endHold())));
  }

  public static double span(Settings s, double length) {
    double delta =
        Math.max(Math.abs(s.startWidth() - s.width()), Math.abs(s.endWidth() - s.width()));
    var e = s.options().ends();
    boolean both =
        e.start() != null
            && !e.start().equals(Section.of(s))
            && e.end() != null
            && !e.end().equals(Section.of(s));
    return Math.min(length * (both ? .45 : .9), Math.max(36, delta * 8));
  }

  private static double weight(double d, double span) {
    return 1 - Settings.smooth(Math.max(0, Math.min(1, d / Math.max(.001, span))));
  }

  /** Keep a shared lane mouth exact until the rising deck clears adjacent traffic. */
  public static double endWeight(Settings s,double d,double length,boolean start,double requestedSpan){
    var e=s.options().ends();if(e.startHold()==0&&e.endHold()==0)return weight(d,requestedSpan);double hold=start?e.startHold():e.endHold();
    double available=Math.max(.001,length-e.startHold()-e.endHold());
    return weight(d-hold,Math.min(requestedSpan,available*.9));
  }
  private static double endWeight(Settings s,double d,double length,boolean start){return endWeight(s,d,length,start,span(s,length));}

  public static double width(Settings s, double d, double length) {
    return s.width()
        + (s.startWidth() - s.width()) * endWeight(s,d,length,true)
        + (s.endWidth() - s.width()) * endWeight(s,length-d,length,false);
  }

  private record Blend(Layout base, Layout target, double weight) {}

  private static Blend blend(Mesh mesh, Sample p) {
    Settings s = mesh.settings();
    Layout base = RoadProfile.layout(s, p.halfWidth() * 2);
    var ends = s.options().ends();
    double station=p.distance()+ends.trimmedStart(),length=mesh.length()+ends.trimmedStart()+ends.trimmedEnd();
    Section baseSection = Section.of(s);
    double startWeight =
        ends.start() == null || ends.start().equals(baseSection)
            ? 0
            : endWeight(s,station,length,true);
    double endWeight =
        ends.end() == null || ends.end().equals(baseSection)
            ? 0
            : endWeight(s,length-station,length,false);
    boolean start = startWeight >= endWeight;
    Section target = start ? ends.start() : ends.end();
    double w = Math.max(startWeight, endWeight);
    return new Blend(
        base,
        target == null
            ? base
            : target.layout(s.options().leftTraffic()),
        w);
  }

  /** An exact green mouth owns its lane axes until the authored section taper releases it. */
  public static double greenPortWeight(Mesh mesh,Sample sample){
    var e=mesh.settings().options().ends();double station=sample.distance()+e.trimmedStart(),length=mesh.length()+e.trimmedStart()+e.trimmedEnd();double weight=0;
    if(e.start()!=null&&e.start().port()!=null&&e.start().layout(mesh.settings().options().leftTraffic()).catalog().median()==Median.GREEN)weight=endWeight(mesh.settings(),station,length,true);
    if(e.end()!=null&&e.end().port()!=null&&e.end().layout(mesh.settings().options().leftTraffic()).catalog().median()==Median.GREEN)weight=Math.max(weight,endWeight(mesh.settings(),length-station,length,false));
    return weight;
  }
  /** Rail center spacing follows the same endpoint blend as the lane cross-section. */
  public static double railSeparation(Mesh mesh, Sample p, double band) {return railMeasure(mesh,p,band,false);}
  public static double railWidth(Mesh mesh, Sample p) {return railMeasure(mesh,p,0,true);}
  private static double railMeasure(Mesh mesh, Sample p, double band, boolean median) {
    Settings s=mesh.settings();var ends=s.options().ends();var own=Section.of(s);
    double station=p.distance()+ends.trimmedStart(),length=mesh.length()+ends.trimmedStart()+ends.trimmedEnd();
    double a=ends.start()==null||ends.start().equals(own)?0:endWeight(s,station,length,true);
    double b=ends.end()==null||ends.end().equals(own)?0:endWeight(s,length-station,length,false);
    var target=a>=b?ends.start():ends.end();double gap=s.options().streetscape().plantings().railGap();
    double base=railValue(gap,band,median);
    if(target==null)return base;
    double other=target.streetscape().plantings().railGap();
    return lerp(base,railValue(other,band,median),Math.max(a,b));
  }

  private static double railValue(double gap,double band,boolean median){return gap==0?(median?1:0):gap+(median?1.5:band);}

  public static Layout layout(Mesh mesh, Sample p) {
    if (mesh.settings().options().ends().equals(Ends.NONE))
      return RoadProfile.layout(mesh.settings(), p.halfWidth() * 2);
    var b = blend(mesh, p);
    var a = b.base();
    var z = b.target();
    double w = b.weight();
    int lanes = a.catalog().lanes();
    for (Section end :
        new Section[] {
          mesh.settings().options().ends().start(), mesh.settings().options().ends().end()
        }) if (end != null) lanes = Math.max(lanes, end.layout(false).catalog().lanes());
    List<Double> dividers = new ArrayList<>();
    if (a.catalog().twoWay()) {
      for (int sign : new int[] {-1, 1})
        for (int n = 1; n < dividerLanes(mesh, a, sign); n++)
          dividers.add(lerp(divider(a, n, sign), divider(z, n, sign), w));
    } else {
      for (int n = 1; n < lanes; n++)
        dividers.add(
            lerp(
                Math.min(a.motorMax(), a.motorMin() + n * a.laneWidth()),
                Math.min(z.motorMax(), z.motorMin() + n * z.laneWidth()),
                w));
    }
    return new Layout(
        w >= .5 ? z.catalog() : a.catalog(),
        lerp(a.laneWidth(), z.laneWidth(), w),
        lerp(a.median(), z.median(), w),
        lerp(a.motorMin(), z.motorMin(), w),
        lerp(a.motorMax(), z.motorMax(), w),
        lerp(a.cycleWidth(), z.cycleWidth(), w),
        lerp(a.curbWidth(), z.curbWidth(), w),
        lerp(a.shoulderWidth(), z.shoulderWidth(), w),
        a.outside(),
        List.copyOf(dividers),lerp(a.medianCenter(),z.medianCenter(),w));
  }

  public static boolean greenCycle(Mesh mesh,Sample p){
    Settings s=mesh.settings();var e=s.options().ends();double station=p.distance()+e.trimmedStart(),length=mesh.length()+e.trimmedStart()+e.trimmedEnd();
    boolean green=s.options().cycle()&&!s.options().cycleAsphalt();
    double a=e.start()==null?0:endWeight(s,station,length,true),b=e.end()==null?0:endWeight(s,length-station,length,false);
    var target=a>=b?e.start():e.end();return target!=null&&target.cycle()&&(!s.options().cycle()||Math.max(a,b)>=.5)?!target.cycleAsphalt():green;
  }
  public static double green(Mesh mesh, Sample p) {
    var b = blend(mesh, p);
    return lerp(
        b.base().catalog().median() == Median.GREEN ? 1 : 0,
        b.target().catalog().median() == Median.GREEN ? 1 : 0,
        b.weight())*(1-RoadStreetscape.tunnelBlend(mesh,p));
  }

  /** Painted closure of the surplus lanes before a terminating three-way split. */
  public static double dropBoundary(Mesh mesh, Sample p) {
    var e = mesh.settings().options().ends();
    if (!e.persistent() || e.end() == null) return Double.NaN;
    int oldLanes = RoadProfile.catalog(mesh.settings()).lanes();
    int newLanes = RoadProfile.catalog(e.end().settings(false)).lanes();
    if (newLanes >= oldLanes) return Double.NaN;
    double length=mesh.length()+e.trimmedStart()+e.trimmedEnd();
    double t = 1 - (mesh.length()+e.trimmedEnd()-p.distance()) / span(mesh.settings(),length);
    if (t <= 0) return Double.NaN;
    var layout = layout(mesh, p);
    double outer = Math.abs(layout.outer(1)) - .2;
    double first = Math.min(outer, layout.median() / 2 + newLanes / 2.0 * layout.laneWidth());
    return outer - Math.max(0, outer - first) * Settings.smooth(Math.min(1, t * 4));
  }

  /** Paint identity must remain stable even where both endpoint weights are zero. */
  private static int dividerLanes(Mesh mesh,Layout base,int side){
    int count=base.lanesOnSide(side);
    for(Section end:new Section[]{mesh.settings().options().ends().start(),mesh.settings().options().ends().end()})
      if(end!=null)count=Math.max(count,end.layout(mesh.settings().options().leftTraffic()).lanesOnSide(side));
    return count;
  }

  private static double divider(Layout l, int lane,int side) {
    if(l.transitionDividers()!=null){
      var offsets=l.dividers().stream().map(d->(d-l.medianCenter())*side).filter(d->d>1e-7).sorted().toList();
      if(lane<=offsets.size())return l.medianCenter()+side*offsets.get(lane-1);
    }
    return l.medianCenter()+side*Math.min(Math.abs(l.outer(side)-l.medianCenter()),l.median()/2+lane*l.laneWidth());
  }

  private static double lerp(double a, double b, double t) {
    return a + (b - a) * t;
  }

  private RoadTransitions() {}
}
