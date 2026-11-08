package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Attached road points are neither real endpoints nor lane/ramp ports. World coordinates are authoritative. */
public final class RoadAttachments {
  public record Point(UUID id,V position,V anchor,boolean controlled,V origin,boolean elevated) {
    public Point(UUID id,V position,V anchor,boolean controlled,V origin){this(id,position,anchor,controlled,origin,false);}
    public Point(UUID id,V position,V anchor,boolean controlled){this(id,position,anchor,controlled,anchor);}
    public Point { Objects.requireNonNull(id); finite(position);finite(anchor);finite(origin); }
    public Point at(V value){return new Point(id,value,anchor,true,origin,elevated);}
  }
  public record Paint(List<RoadLaneLines.Edit> lines,boolean hideArrows) {
    public Paint {lines=List.copyOf(lines);if(lines.size()>32)throw new IllegalArgumentException("标线记录过多");}
  }
  public record Span(V start,V end,Paint paint) {
    public Span {finite(start);finite(end);Objects.requireNonNull(paint);}
  }
  public record Data(List<Point> points,List<Span> spans) {
    public static final Data EMPTY=new Data(List.of(),List.of());
    public Data {points=List.copyOf(points);spans=List.copyOf(spans);
      if(spans.size()>512||points.stream().map(Point::id).distinct().count()!=points.size())throw new IllegalArgumentException("附属点重复或标线区间过多");}
    public Data points(List<Point> value){return new Data(value,spans);}
  }
  public record Range(double start,double end) {}
  private static void finite(V p){if(p==null||!RoadGeometry.finite(p.x(),p.y(),p.z()))throw new IllegalArgumentException("附属点坐标无效");}
  private static final ThreadLocal<IdentityHashMap<Mesh,Map<V,Double>>> STATIONS=ThreadLocal.withInitial(IdentityHashMap::new);
  public static double station(Mesh m,V p){var meshes=STATIONS.get();if(meshes.size()>32)meshes.clear();return meshes.computeIfAbsent(m,k->new HashMap<>()).computeIfAbsent(p,k->RoadQueries.horizontal(m,p).sample().distance());}
  public static List<Point> sorted(Mesh m){return m.settings().options().attachments().points().stream().filter(p->!p.elevated()).sorted(Comparator.comparingDouble(p->station(m,p.position()))).toList();}
  public static Range range(Mesh m,double d){double lo=0,hi=m.length();for(var p:sorted(m)){double at=station(m,p.position());if(at<=d+1e-6)lo=Math.max(lo,at);else {hi=at;break;}}return new Range(lo,hi);}
  public static Paint paint(Mesh m,double d){
    var o=m.settings().options();Paint result=new Paint(o.laneLines(),o.hideArrows());
    for(var span:o.attachments().spans()){double a=station(m,span.start()),b=station(m,span.end());if(d>=Math.min(a,b)-1e-7&&d<Math.max(a,b)-1e-7)result=a<=b?span.paint():reverse(m,span.paint());}
    return result;
  }
  private static Paint reverse(Mesh mesh,Paint paint){
    int dividers=(int)RoadLaneLines.lines(mesh).stream().filter(l->l.key().startsWith("divider:")).count();var lines=new ArrayList<RoadLaneLines.Edit>();
    for(var e:paint.lines()){String[] parts=e.key().split(":");int n=Integer.parseInt(parts[1]);String key=parts[0]+":"+(parts[0].equals("divider")?dividers-1-n:-n);var pattern=switch(e.pattern()){
      case LEFT_SOLID_RIGHT_DASHED->RoadLaneLines.Pattern.LEFT_DASHED_RIGHT_SOLID;case LEFT_DASHED_RIGHT_SOLID->RoadLaneLines.Pattern.LEFT_SOLID_RIGHT_DASHED;
      case YELLOW_LEFT_SOLID_RIGHT_DASHED->RoadLaneLines.Pattern.YELLOW_LEFT_DASHED_RIGHT_SOLID;case YELLOW_LEFT_DASHED_RIGHT_SOLID->RoadLaneLines.Pattern.YELLOW_LEFT_SOLID_RIGHT_DASHED;default->e.pattern();};lines.add(new RoadLaneLines.Edit(key,pattern,e.width()));}
    return new Paint(lines,paint.hideArrows());
  }
  public static RoadLaneLines.Edit line(Mesh m,double d,String key){return paint(m,d).lines().stream().filter(e->e.key().equals(key)).findFirst().orElse(new RoadLaneLines.Edit(key,RoadLaneLines.Pattern.DEFAULT,.12));}
  public static Data edit(Mesh m,Range range,Paint value){
    var spans=new ArrayList<Span>();
    for(var span:m.settings().options().attachments().spans()){
      double a=station(m,span.start()),b=station(m,span.end());double lo=Math.min(a,b),hi=Math.max(a,b);
      if(hi<=range.start()+1e-7||lo>=range.end()-1e-7){spans.add(span);continue;}
      Paint previous=a<=b?span.paint():reverse(m,span.paint());
      if(lo<range.start()-1e-7)spans.add(new Span(at(m,lo),at(m,range.start()),previous));
      if(hi>range.end()+1e-7)spans.add(new Span(at(m,range.end()),at(m,hi),previous));
    }
    spans.add(new Span(at(m,range.start()),at(m,range.end()),value));
    return new Data(m.settings().options().attachments().points(),spans);
  }
  public static V at(Mesh m,double d){return RoadStructures.sample(m,d).center();}
  /** Exact marker boundaries are included in paint tessellation, including sub-sample edits. */
  public static List<Sample> splitPaint(Mesh m,List<Sample> source){
    TreeSet<Double> cuts=new TreeSet<>();for(var s:source)cuts.add(s.distance());
    for(var p:m.settings().options().attachments().points())cuts.add(station(m,p.position()));
    for(var s:m.settings().options().attachments().spans()){cuts.add(station(m,s.start()));cuts.add(station(m,s.end()));}
    return cuts.stream().map(d->RoadStructures.sample(m,d)).toList();
  }
  /** Interpolating cubic displacement, zero end slopes; all authored points survive each edit. */
  public static Mesh deform(Mesh base,Settings settings){
    var data=settings.options().attachments();
    if(data.points().stream().noneMatch(Point::controlled))return new Mesh(base.samples(),settings,base.min(),base.max(),base.length(),base.closed(),base.controlPoint(),base.controls());
    TreeMap<Double,V> offsets=new TreeMap<>();offsets.put(0.0,new V(0,0,0));offsets.put(base.length(),new V(0,0,0));
    for(var p:data.points()){
      if(!p.controlled())continue;
      double d=station(base,p.anchor());V center=at(base,d);V delta=p.position().sub(center);
      if(settings.mode()!=Mode.CURVE)delta=new V(0,delta.y(),0);
      if((d<1e-5||d>base.length()-1e-5)&&delta.distance(new V(0,0,0))>1e-5)throw new IllegalArgumentException("与真实端点重合的附属点不能单独改变端点位置");
      if(offsets.containsKey(d)&&offsets.get(d).distance(delta)>1e-5)throw new IllegalArgumentException("同一纵向位置的附属点控制目标不一致，请错开控制点位置");
      offsets.put(d,delta);
    }
    TreeSet<Double> stations=new TreeSet<>(offsets.keySet());for(var s:base.samples())stations.add(s.distance());
    var knots=new ArrayList<>(offsets.keySet());var positions=new ArrayList<V>();var widths=new ArrayList<Double>();
    for(double d:stations){int i=0;while(i+1<knots.size()-1&&d>knots.get(i+1))i++;double a=knots.get(i),b=knots.get(i+1),t=(d-a)/(b-a);double u=t*t*(3-2*t);
      positions.add(at(base,d).add(offsets.get(a).mul(1-u).add(offsets.get(b).mul(u))));widths.add(RoadStructures.sample(base,d).halfWidth());}
    var samples=new ArrayList<Sample>();for(int i=0;i<positions.size();i++){
      V tangent=positions.get(Math.min(i+1,positions.size()-1)).sub(positions.get(Math.max(0,i-1))).horizontalUnit();
      samples.add(new Sample(positions.get(i),tangent.left(),0,widths.get(i)));}
    Mesh result=RoadRibbon.mesh(samples,settings);RoadRibbon.checkSelfIntersections(result,4);return result;
  }
  private RoadAttachments(){}
}
