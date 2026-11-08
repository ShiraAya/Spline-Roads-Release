package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;
import java.util.function.DoubleUnaryOperator;
/** Cuts a tunnel mouth at its actual paved outline, retaining the lining above it.
 * Fragments use ordinary persisted framed prisms, so collision and rendering agree. */
public final class RoadShellOpening {
  private record Patch(List<V> floor,double first,double second,double third){
    double height(V p){if(first==second&&first==third)return first;var a=floor.get(0);var b=floor.get(1).sub(a);var c=floor.get(2).sub(a);var q=p.sub(a);double det=b.x()*c.z()-b.z()*c.x();
      double u=(q.x()*c.z()-q.z()*c.x())/det,v=(b.x()*q.z()-b.z()*q.x())/det;return first+(second-first)*u+(third-first)*v;}
  }
  private final Map<Long,List<Patch>> grid=new HashMap<>();
  /** Subtract only solids that actually exist. An expanded road envelope is not
   * the footprint of a portal collar, wall, canopy or arched roof. */
  public static RoadShellOpening solids(List<Part> parts){
    var result=new RoadShellOpening();
    for(var part:parts){var base=part.base();var top=part.top();
      if(planar(base)&&planar(top)&&Math.abs(JunctionPaint.area(base.subList(0,3)))>1e-14)result.add(new Patch(base,top.get(0).y()-base.get(0).y(),top.get(1).y()-base.get(1).y(),top.get(2).y()-base.get(2).y()));
      else for(int i=1;i+1<base.size();i++)result.add(new Patch(List.of(base.get(0),base.get(i),base.get(i+1)),top.get(0).y()-base.get(0).y(),top.get(i).y()-base.get(i).y(),top.get(i+1).y()-base.get(i+1).y()));
    }
    return result;
  }
  public RoadShellOpening(Mesh mesh,double from,double to,DoubleUnaryOperator height){this(mesh,from,to,height,1);}
  public RoadShellOpening(Mesh mesh,double from,double to,DoubleUnaryOperator height,double step){
    this(mesh,from,to,height,step,.08);
  }
  public RoadShellOpening(Mesh mesh,double from,double to,DoubleUnaryOperator height,double step,double edgePadding){
    if(step<=0||!Double.isFinite(step))throw new IllegalArgumentException("Invalid shell sample step");
    from=Math.max(mesh.first().distance(),from);to=Math.min(mesh.last().distance(),to);
    if(to<=from)return;
    // Use the actual ribbon breakpoints. Re-sampling a curved outline at one
    // metre left thin wedges of the original shell between clipping chords.
    var stations=new TreeSet<Double>();stations.add(from);stations.add(to);
    for(var sample:mesh.samples())if(sample.distance()>from&&sample.distance()<to)stations.add(sample.distance());
    for(double d=from+step;d<to-1e-7;d+=step)stations.add(d);
    var nodes=new ArrayList<Sample>();var roofs=new ArrayList<Double>();
    for(double d:stations){nodes.add(RoadStructures.sample(mesh,d));roofs.add(height.applyAsDouble(d));}
    double depth=mesh.settings().thickness()+.02;
    for(int i=0;i<nodes.size()-1;){
      int end=i+1;
      // Exact coalescing makes a long straight host a few apertures rather than
      // thousands of sequential polygon cuts. It preserves every curved corner.
      for(int candidate=end+1;candidate<nodes.size()&&nodes.get(candidate).distance()-nodes.get(i).distance()<=64;candidate++){
        var a=nodes.get(i);var b=nodes.get(candidate);boolean linear=true;
        for(int k=i+1;k<candidate&&linear;k++){
          var m=nodes.get(k);double t=(m.distance()-a.distance())/(b.distance()-a.distance());
          if(Math.abs(roofs.get(k)-(roofs.get(i)*(1-t)+roofs.get(candidate)*t))>1e-8)linear=false;
          for(int side:new int[]{-1,1})if(a.at(side*(a.halfWidth()+.08),depth).mul(1-t).add(b.at(side*(b.halfWidth()+.08),depth).mul(t)).distance(m.at(side*(m.halfWidth()+.08),depth))>1e-8)linear=false;
        }
        if(!linear)break;end=candidate;
      }
      var a=nodes.get(i);var b=nodes.get(end);double ha=roofs.get(i),hb=roofs.get(end);
      V al=a.at(a.halfWidth()+edgePadding,depth),ar=a.at(-a.halfWidth()-edgePadding,depth),bl=b.at(b.halfWidth()+edgePadding,depth),br=b.at(-b.halfWidth()-edgePadding,depth);
      var quad=List.of(al,ar,br,bl);
      if(planar(quad))add(new Patch(quad,ha+depth,ha+depth,hb+depth));
      else {add(new Patch(List.of(al,ar,br),ha+depth,ha+depth,hb+depth));add(new Patch(List.of(al,br,bl),ha+depth,hb+depth,hb+depth));}i=end;
    }
  }
  private void add(Patch p){if(Math.abs(JunctionPaint.area(p.floor()))<1e-12)return;
    int x0=tile(p.floor().stream().mapToDouble(V::x).min().orElseThrow()),x1=tile(p.floor().stream().mapToDouble(V::x).max().orElseThrow());
    int z0=tile(p.floor().stream().mapToDouble(V::z).min().orElseThrow()),z1=tile(p.floor().stream().mapToDouble(V::z).max().orElseThrow());
    for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++)grid.computeIfAbsent(key(x,z),k->new ArrayList<>()).add(p);
  }
  public List<Part> cut(Part part){
    var base=part.base();var near=new LinkedHashSet<Patch>();
    int x0=tile(base.stream().mapToDouble(V::x).min().orElseThrow()),x1=tile(base.stream().mapToDouble(V::x).max().orElseThrow());
    int z0=tile(base.stream().mapToDouble(V::z).min().orElseThrow()),z1=tile(base.stream().mapToDouble(V::z).max().orElseThrow());
    for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++)near.addAll(grid.getOrDefault(key(x,z),List.of()));
    var initial=new ArrayList<Slice>();var top=part.top();
    if(planar(base)&&planar(top))initial.add(new Slice(base,Plane.of(base),Plane.of(top)));
    else for(int i=1;i+1<base.size();i++)if(valid(List.of(base.get(0),base.get(i),base.get(i+1))))initial.add(new Slice(List.of(base.get(0),base.get(i),base.get(i+1)),Plane.of(List.of(base.get(0),base.get(i),base.get(i+1))),Plane.of(List.of(top.get(0),top.get(i),top.get(i+1)))));
    List<Slice> pieces=initial;
    for(var patch:near){var next=new ArrayList<Slice>();for(var piece:pieces)clip(piece,patch,next);pieces=next;if(pieces.isEmpty())break;}
    if(pieces.equals(initial))return List.of(part);
    var result=new ArrayList<Part>();for(var piece:pieces)emit(result,piece,part);
    return List.copyOf(result);
  }
  /** Intersection with this union, retaining exact cut planes and closed ends. */
  public List<Part> retain(Part part){
    var outside=cut(part);
    if(outside.isEmpty())return List.of(part);
    if(outside.size()==1&&outside.get(0).equals(part))return List.of();
    return solids(outside).cut(part);
  }
  private record Plane(V origin,double x,double z){
    double at(V p){return origin.y()+x*(p.x()-origin.x())+z*(p.z()-origin.z());}
    static Plane of(List<V> points){V a=points.get(0);for(int i=1;i+1<points.size();i++){
      V b=points.get(i).sub(a),c=points.get(i+1).sub(a);double det=b.x()*c.z()-b.z()*c.x();if(Math.abs(det)<1e-14)continue;
      return new Plane(a,(b.y()*c.z()-c.y()*b.z())/det,(b.x()*c.y()-c.x()*b.y())/det);
    }throw new IllegalArgumentException("Degenerate shell plane");}
  }
  private static boolean planar(List<V> p){if(!valid(p))return false;var plane=Plane.of(p);return p.stream().allMatch(v->Math.abs(v.y()-plane.at(v))<1e-8);}
  private record Slice(List<V> base,Plane bottom,Plane top){}
  // Match aperture insertion's area tolerance. An almost-zero clipped strip
  // can pass 1e-24 yet have no triangle large enough to define Plane.of (1e-14).
  // Such roundoff fragments must not abort otherwise valid bridge/cycle edits.
  private static boolean valid(List<V> p){return p.size()>=3&&Math.abs(JunctionPaint.area(p))>1e-12;}
  private static void slice(List<Slice> out,List<V> polygon,Plane bottom,Plane top){
    polygon=half(polygon,p->top.at(p)-bottom.at(p));
    if(!valid(polygon)||polygon.stream().mapToDouble(p->top.at(p)-bottom.at(p)).max().orElse(0)<=1e-7)return;
    out.add(new Slice(polygon,bottom,top));
  }
  private static List<V> half(List<V> p,java.util.function.ToDoubleFunction<V> value){
    if(p.isEmpty())return p;var out=new ArrayList<V>();V a=p.get(p.size()-1);double va=value.applyAsDouble(a);
    for(V b:p){double vb=value.applyAsDouble(b);if((va>=0)!=(vb>=0))out.add(a.add(b.sub(a).mul(va/(va-vb))));if(vb>=0)out.add(b);a=b;va=vb;}return out;
  }
  private static void clip(Slice part,Patch patch,List<Slice> out){
    var hit=RoadSurface.intersectVolume(part.base(),patch.floor());if(!valid(hit)){out.add(part);return;}
    var floor=Plane.of(patch.floor());var roof=Plane.of(patch.floor().stream().map(v->v.add(new V(0,patch.height(v),0))).toList());
    if(hit.stream().allMatch(v->part.bottom().at(v)>=roof.at(v)-1e-7)||hit.stream().allMatch(v->part.top().at(v)<=floor.at(v)+1e-7)){out.add(part);return;}
    for(var polygon:RoadSurface.subtract(part.base(),patch.floor()))slice(out,polygon,part.bottom(),part.top());
    // Split at crossing planes instead of stretching a constant-height prism.
    slice(out,half(hit,p->part.bottom().at(p)-roof.at(p)),part.bottom(),part.top());
    slice(out,half(hit,p->roof.at(p)-part.bottom().at(p)),roof,part.top());
    slice(out,half(hit,p->floor.at(p)-part.top().at(p)),part.bottom(),part.top());
    slice(out,half(hit,p->part.top().at(p)-floor.at(p)),part.bottom(),floor);
  }
  private static void emit(List<Part> out,Slice slice,Part source){
    var polygon=new ArrayList<V>();for(var v:slice.base())if(polygon.isEmpty()||v.sub(polygon.get(polygon.size()-1)).horizontalLength()>1e-8)polygon.add(v);
    if(polygon.size()>1&&polygon.get(0).sub(polygon.get(polygon.size()-1)).horizontalLength()<1e-8)polygon.remove(polygon.size()-1);
    for(int i=0;i<polygon.size()&&polygon.size()>3;){int n=polygon.size();V a=polygon.get((i+n-1)%n),b=polygon.get(i),c=polygon.get((i+1)%n);
      if(2*Math.abs(JunctionPaint.area(List.of(a,b,c)))<=a.sub(c).horizontalLength()*1e-8){polygon.remove(i);i=0;}else i++;}
    if(!valid(polygon))return;
    if(polygon.size()==4){emitPart(out,polygon,slice,source);return;}
    for(int i=1;i+1<polygon.size();i++)emitPart(out,List.of(polygon.get(0),polygon.get(i),polygon.get(i+1),polygon.get(i+1)),slice,source);
  }
  private static void emitPart(List<Part> out,List<V> polygon,Slice slice,Part source){
    double longest=0;for(int i=0;i<polygon.size();i++)longest=Math.max(longest,polygon.get(i).sub(polygon.get((i+1)%polygon.size())).horizontalLength());
    if(2*Math.abs(JunctionPaint.area(polygon))<=longest*1e-7)return;
    var p=polygon.stream().map(v->new V(v.x(),slice.bottom().at(v),v.z())).toList();double[] h=new double[4];double height=0,min=Double.POSITIVE_INFINITY;
    for(int i=0;i<4;i++){h[i]=Math.max(0,slice.top().at(p.get(i))-p.get(i).y());height=Math.max(height,h[i]);min=Math.min(min,h[i]);}
    if(height<=1e-7)return;V a=p.get(0).add(p.get(1)).mul(.5),b=p.get(2).add(p.get(3)).mul(.5);
    out.add(new Part(a,b,Math.max(.001,Math.min(8,p.get(0).sub(p.get(1)).horizontalLength())),height,false,source.material(),p.get(0).sub(a),p.get(3).sub(b),source.model(),height-min<1e-8?null:new Heights(h[0],h[1],h[2],h[3])));
  }
  private static int tile(double v){return (int)Math.floor(v/8);}
  private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
  private RoadShellOpening(){}
}
