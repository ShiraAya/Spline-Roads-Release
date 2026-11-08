package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Exact subpaths and clipped boundary triangles; no refit and no hidden parent road. */
public final class RoadSlicing {
  public enum Connection { CONTINUOUS, JUNCTION }
  public record Patch(double from,double to,List<LaneDeck.Strip> strips,boolean clipped){
    public Patch(double from,double to,List<LaneDeck.Strip> strips){this(from,to,strips,false);}
    public Patch {strips=List.copyOf(strips);if(!RoadGeometry.finite(from,to)||from<0||to<=from||strips.size()>(clipped?4096:16))throw new IllegalArgumentException("分割切面无效");}
  }
  public record Geometry(List<Patch> patches,boolean clipped){public Geometry(List<Patch> patches){this(patches,false);}public Geometry{patches=List.copyOf(patches);if(patches.size()>(clipped?8192:4))throw new IllegalArgumentException("分割切面数量无效");}}
  public record Fragment(UUID source,double origin,double total,Connection connection,RoadAttachments.Data applied,Geometry geometry,List<RoadStructures.Part> supports){
    public Fragment(UUID source,double origin,double total,Connection connection,RoadAttachments.Data applied,Geometry geometry){this(source,origin,total,connection,applied,geometry,List.of());}
    public Fragment {supports=List.copyOf(supports);if(supports.size()>50000)throw new IllegalArgumentException("分割桥墩数量过多");Objects.requireNonNull(source);Objects.requireNonNull(connection);Objects.requireNonNull(applied);Objects.requireNonNull(geometry);if(!RoadGeometry.finite(origin,total)||origin<0||total<=0||origin>=total)throw new IllegalArgumentException("分割路径范围无效");}
    public Fragment geometry(Geometry value){return new Fragment(source,origin,total,connection,applied,value,supports);}
  }
  public record Slice(Mesh mesh,Geometry geometry){}
  public static Mesh attach(Mesh mesh,Geometry geometry){return new Mesh(mesh.samples(),mesh.settings(),mesh.min(),mesh.max(),mesh.length(),mesh.closed(),mesh.controlPoint(),mesh.controls(),mesh.reference(),geometry);}
  public static Mesh mesh(List<Sample> points,Settings settings){
    var validated=RoadRibbon.mesh(points,settings);double previous=-1;
    for(var point:points){if(!Double.isFinite(point.distance())||point.distance()<=previous)throw new IllegalArgumentException("分割路径里程未递增");previous=point.distance();}
    return new Mesh(List.copyOf(points),settings,validated.min(),validated.max(),points.get(points.size()-1).distance(),false);
  }
  /** Interpolate the actual half-edge vector, not a unit normal with the old width. */
  public static Sample at(Mesh mesh,double station){
    var points=mesh.samples();int hi=1;while(hi<points.size()-1&&points.get(hi).distance()<station)hi++;
    var a=points.get(hi-1);var b=points.get(hi);double t=Math.max(0,Math.min(1,(station-a.distance())/(b.distance()-a.distance())));
    V edge=a.left().mul(a.halfWidth()*(1-t)).add(b.left().mul(b.halfWidth()*t));
    return new Sample(a.center().mul(1-t).add(b.center().mul(t)),edge.horizontalUnit(),station,edge.horizontalLength());
  }
  public static Slice slice(Mesh source,double from,double to,Settings settings){
    if(source.closed()||from<0||to>source.length()+1e-7||to-from<1e-7)throw new IllegalArgumentException("道路分割范围无效");
    var first=at(source,from);var last=at(source,to);var points=new ArrayList<Sample>();points.add(local(first,from));
    for(var p:source.samples())if(p.distance()>from+1e-8&&p.distance()<to-1e-8)points.add(local(p,from));points.add(local(last,from));
    var patches=new ArrayList<Patch>();var original=source.samples();
    for(int i=1;i<original.size();i++){
      var a=original.get(i-1);var b=original.get(i);double lo=Math.max(from,a.distance()),hi=Math.min(to,b.distance());if(hi-lo<1e-8)continue;
      boolean partial=lo>a.distance()+1e-8||hi<b.distance()-1e-8;
      boolean existing=source.slicing()!=null&&source.slicing().patches().stream().anyMatch(p->Math.abs(p.from()-a.distance())<1e-7&&Math.abs(p.to()-b.distance())<1e-7);
      if(!partial&&!existing)continue;
      var strips=new ArrayList<LaneDeck.Strip>();
      for(var strip:LaneDeck.strips(source,a,b)){
        for(var triangle:List.of(List.of(strip.al(),strip.ar(),strip.br()),List.of(strip.al(),strip.br(),strip.bl()))){
          List<V> polygon=triangle;
          if(lo>a.distance()+1e-8)polygon=clip(polygon,first,true);
          if(hi<b.distance()-1e-8)polygon=clip(polygon,last,false);
          for(int j=1;j<polygon.size()-1;j++)addTriangle(strips,List.of(polygon.get(0),polygon.get(j),polygon.get(j+1)),strip);
        }
      }
      patches.add(new Patch(lo-from,hi-from,strips));
    }
    var geometry=new Geometry(patches);return new Slice(attach(mesh(points,settings),geometry),geometry);
  }
  private static Sample local(Sample p,double origin){return new Sample(p.center(),p.left(),p.distance()-origin,p.halfWidth());}
  private static List<V> clip(List<V> polygon,Sample cut,boolean after){
    if(polygon.isEmpty())return polygon;V forward=cut.left().left().mul(after?-1:1);var out=new ArrayList<V>();
    V a=polygon.get(polygon.size()-1);double da=a.sub(cut.center()).dot(forward);
    for(V b:polygon){double db=b.sub(cut.center()).dot(forward);if((da>=0)!=(db>=0))out.add(a.add(b.sub(a).mul(da/(da-db))));if(db>=-1e-10)out.add(b);a=b;da=db;}
    return out;
  }
  private static void addTriangle(List<LaneDeck.Strip> out,List<V> triangle,LaneDeck.Strip original){
    var a=triangle.get(0);var b=triangle.get(1);var c=triangle.get(2);
    if(Math.abs((b.x()-a.x())*(c.z()-a.z())-(b.z()-a.z())*(c.x()-a.x()))<1e-10)return;
    for(int i=0;i<3;i++){
      V p=triangle.get(i),q=triangle.get((i+1)%3);
      if(original.lowWall()&&on(p,original.ar(),original.br())&&on(q,original.ar(),original.br())||original.highWall()&&on(p,original.bl(),original.al())&&on(q,original.bl(),original.al())){
        V zero=triangle.get((i+2)%3);out.add(new LaneDeck.Strip(zero,p,q,q,true,false));return;
      }
    }
    out.add(new LaneDeck.Strip(a,b,c,c,false,false));
  }
  private static boolean on(V p,V a,V b){V d=b.sub(a);double length=d.dot(d);if(length<1e-16)return false;double t=p.sub(a).dot(d)/length;return t>=-1e-7&&t<=1+1e-7&&p.distance(a.add(d.mul(t)))<1e-7;}
  public static List<LaneDeck.Strip> patch(Mesh mesh,Sample a,Sample b){
    if(mesh.slicing()==null)return null;
    for(var p:mesh.slicing().patches())if(Math.abs(p.from()-a.distance())<1e-7&&Math.abs(p.to()-b.distance())<1e-7)return p.strips();return null;
  }
  /** Apply only edits made after the saved path already incorporated its controls. */
  public static Mesh deform(Mesh base,Settings settings,Fragment fragment){
    var current=settings.options().attachments();if(current.points().equals(fragment.applied().points()))return base;
    var before=new HashMap<UUID,RoadAttachments.Point>();for(var p:fragment.applied().points())before.put(p.id(),p);
    var after=new HashMap<UUID,RoadAttachments.Point>();for(var p:current.points())after.put(p.id(),p);
    var ids=new LinkedHashSet<>(before.keySet());ids.addAll(after.keySet());var controls=new ArrayList<RoadAttachments.Point>();
    for(UUID id:ids){var a=before.get(id);var b=after.get(id);var point=b==null?a:b;if((a==null||!a.controlled())&&(b==null||!b.controlled()))continue;
      V anchor=at(base,RoadAttachments.station(base,point.anchor())).center();
      V old=a!=null&&a.controlled()?a.position():anchor,target=b!=null&&b.controlled()?b.position():a==null?anchor:a.anchor();
      controls.add(new RoadAttachments.Point(id,anchor.add(target.sub(old)),anchor,true,point.origin()));
    }
    var temp=settings.options(settings.options().attachments(new RoadAttachments.Data(controls,current.spans())));var result=RoadAttachments.deform(base,temp);
    return new Mesh(result.samples(),settings,result.min(),result.max(),result.length(),false);
  }
  private RoadSlicing(){}
}
