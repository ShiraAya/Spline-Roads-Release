package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import java.util.function.DoubleFunction;

/** Detached rail coordinates. MTR parameters and SR 3D stations are never interchangeable. */
public final class RailbedPath {
  public static final double MAX_LENGTH=2048, ERROR=.003;
  public static final int MAX_SEGMENTS=64, MAX_POINTS=8192;
  public record Node(int x,int y,int z) implements Comparable<Node> {
    public Node{if(Math.abs((long)x)>29999984||Math.abs((long)z)>29999984||Math.abs((long)y)>2048)throw new IllegalArgumentException("铁路节点坐标无效");}
    public int compareTo(Node b){int c=Integer.compare(x,b.x);if(c==0)c=Integer.compare(y,b.y);return c==0?Integer.compare(z,b.z):c;}
    public V center(){return new V(x+.5,y,z+.5);}
  }
  public record Point(double parameter,V position){
    public Point{if(position==null||!RoadGeometry.finite(parameter,position.x(),position.y(),position.z())||parameter<0||Math.abs(position.x())>29999984||Math.abs(position.z())>29999984||Math.abs(position.y())>2048)throw new IllegalArgumentException("铁路采样坐标无效");}
  }
  public record Segment(String id,Node from,Node to,boolean reverse,double length,List<String> styles,List<Point> points){
    public Segment{styles=List.copyOf(styles);points=List.copyOf(points);if(points.size()<2||points.size()>MAX_POINTS)throw new IllegalArgumentException("铁路采样数量超限");
      if(id==null||id.isBlank()||id.length()>128||from==null||to==null||from.equals(to)||!Double.isFinite(length)||length<=.01||length>MAX_LENGTH||styles.size()>64||styles.stream().anyMatch(s->s.length()>256))throw new IllegalArgumentException("铁路轨段信息无效");
      double last=-1;for(var p:points){if(p.parameter()<=last||p.parameter()>length+1e-6)throw new IllegalArgumentException("铁路采样参数无效");last=p.parameter();}
    }
  }
  public record Path(List<Segment> segments){
    public Path{segments=List.copyOf(segments);if(segments.isEmpty()||segments.size()>MAX_SEGMENTS||segments.stream().mapToInt(s->s.points().size()).sum()>MAX_POINTS||segments.stream().mapToDouble(s->s.points().get(s.points().size()-1).parameter()-s.points().get(0).parameter()).sum()>MAX_LENGTH+.001)throw new IllegalArgumentException("铁路区间超过预算，请分段选择");
      var ids=new HashSet<String>();for(int i=0;i<segments.size();i++){var s=segments.get(i);if(!ids.add(s.id()))throw new IllegalArgumentException("铁路区间重复经过同一轨段");if(i>0){var before=segments.get(i-1);if(!before.to().equals(s.from())||before.points().get(before.points().size()-1).position().distance(s.points().get(0).position())>.001)throw new IllegalArgumentException("铁路轨迹不连续");}}
    }
    public double length(){return segments.stream().mapToDouble(s->s.points().get(s.points().size()-1).parameter()-s.points().get(0).parameter()).sum();}
    public List<V> positions(){var out=new ArrayList<V>();for(var s:segments)for(var p:s.points()){if(out.isEmpty()||out.get(out.size()-1).distance(p.position())>1e-8)out.add(p.position());}return List.copyOf(out);}
    public String fingerprint(){
      // Include the full immutable sampled geometry and model installation basis,
      // excluding speed/signals, which do not change the support surface.
      try{var digest=java.security.MessageDigest.getInstance("SHA-256");for(var s:segments){
        digest.update((s.id()+":"+s.reverse()+":"+s.styles()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var buffer=java.nio.ByteBuffer.allocate(32);for(var p:s.points()){buffer.clear();buffer.putDouble(p.parameter()).putDouble(p.position().x()).putDouble(p.position().y()).putDouble(p.position().z());digest.update(buffer.array());}
      }return java.util.HexFormat.of().formatHex(digest.digest());}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
  }
  /** One-sided second-order end derivative; a first chord points inside an arc. */
  public static V tangent(List<V> points,int index){
    if(points.size()<3||index>0&&index+1<points.size())return points.get(Math.min(points.size()-1,index+1)).sub(points.get(Math.max(0,index-1))).horizontalUnit();
    int direction=index==0?1:-1;var a=points.get(index);var b=points.get(index+direction);var c=points.get(index+2*direction);
    double h1=b.sub(a).horizontalLength(),h2=c.sub(b).horizontalLength();
    return b.sub(a).mul((2*h1+h2)/(h1*(h1+h2))).sub(c.sub(b).mul(h1/(h2*(h1+h2)))).mul(direction).horizontalUnit();
  }
  /** Quarter-point checks catch an S curve whose midpoint lies on its chord.
   * One call advances one interval, allowing the simulator to share its tick. */
  public static final class Sampler {
    private record Span(double a,V p,double b,V q,int depth){}
    private final DoubleFunction<V> curve;private final Deque<Span> pending=new ArrayDeque<>();
    private final List<Point> points=new ArrayList<>();
    public Sampler(double length,DoubleFunction<V> curve){
      if(!Double.isFinite(length)||length<=.01||length>MAX_LENGTH)throw new IllegalArgumentException("铁路长度无效或超过 2048 格，请分段选择");
      this.curve=curve;V a=read(0),b=read(length);points.add(new Point(0,a));pending.push(new Span(0,a,length,b,0));
    }
    private V read(double s){V p=curve.apply(s);if(!RoadGeometry.finite(p.x(),p.y(),p.z())||Math.abs(p.x())>29999984||Math.abs(p.z())>29999984||Math.abs(p.y())>2048)throw new IllegalArgumentException("MTR 轨道坐标超出有效范围");return p;}
    public boolean done(){return pending.isEmpty();}
    public void step(){
      var span=pending.pop();double length=span.b-span.a;boolean split=length>2;V middle=null;
      for(double f:new double[]{.25,.5,.75}){V p=read(span.a+length*f);if(f==.5)middle=p;
        if(p.distance(span.p.mul(1-f).add(span.q.mul(f)))>ERROR)split=true;}
      if(split){if(span.depth>=22||points.size()+pending.size()+2>MAX_POINTS)throw new IllegalArgumentException("铁路曲线细分超过预算，请缩短区间");double mid=(span.a+span.b)/2;
        pending.push(new Span(mid,middle,span.b,span.q,span.depth+1));pending.push(new Span(span.a,span.p,mid,middle,span.depth+1));
      }else points.add(new Point(span.b,span.q));
    }
    public List<Point> result(){if(!done())throw new IllegalStateException("轨道读取尚未完成");return List.copyOf(points);}
  }
  private RailbedPath(){}
}
