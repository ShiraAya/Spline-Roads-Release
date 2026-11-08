package com.sora.splineroads.core;
import java.util.*;
import com.sora.splineroads.core.RoadRaster.Box;
/** Surface of a union of disjoint terrain boxes; remove internal faces and merge coplanar tiles. */
public final class TerrainEdgeFaces {
  public record Face(int axis,boolean positive,Box box){}
  private record Plane(int axis,boolean positive,double at){}
  private record Rect(double a,double b,double c,double d){}
  public static List<Face> surface(List<Box> boxes){
    var planes=new LinkedHashMap<Plane,List<Rect>>();
    for(var box:boxes)for(int axis=0;axis<3;axis++)for(boolean positive:new boolean[]{false,true}){
      double at=bound(box,axis,positive);var visible=new ArrayList<Rect>();visible.add(rect(box,axis));
      for(var other:boxes)if(other!=box&&Math.abs(bound(other,axis,!positive)-at)<1e-9){
        var next=new ArrayList<Rect>();for(var r:visible)subtract(next,r,rect(other,axis));visible=next;if(visible.isEmpty())break;
      }
      planes.computeIfAbsent(new Plane(axis,positive,at),k->new ArrayList<>()).addAll(visible);
    }
    var out=new ArrayList<Face>();for(var entry:planes.entrySet()){
      var rects=entry.getValue();boolean again=true;while(again){again=false;
        outer:for(int i=0;i<rects.size();i++)for(int j=i+1;j<rects.size();j++){
          var a=rects.get(i);var b=rects.get(j);Rect merged=null;
          if(eq(a.a,b.a)&&eq(a.c,b.c)&&(eq(a.d,b.b)||eq(b.d,a.b)))merged=new Rect(a.a,Math.min(a.b,b.b),a.c,Math.max(a.d,b.d));
          else if(eq(a.b,b.b)&&eq(a.d,b.d)&&(eq(a.c,b.a)||eq(b.c,a.a)))merged=new Rect(Math.min(a.a,b.a),a.b,Math.max(a.c,b.c),a.d);
          if(merged!=null){rects.set(i,merged);rects.remove(j);again=true;break outer;}
        }
      }
      var p=entry.getKey();for(var r:rects)out.add(new Face(p.axis,p.positive,switch(p.axis){case 0->new Box(p.at,r.a,r.b,p.at,r.c,r.d);case 1->new Box(r.a,p.at,r.b,r.c,p.at,r.d);default->new Box(r.a,r.b,p.at,r.c,r.d,p.at);}));
    }return List.copyOf(out);
  }
  private static boolean eq(double a,double b){return Math.abs(a-b)<1e-9;}
  private static double bound(Box b,int a,boolean p){return switch(a){case 0->p?b.x1():b.x0();case 1->p?b.y1():b.y0();default->p?b.z1():b.z0();};}
  private static Rect rect(Box b,int a){return switch(a){case 0->new Rect(b.y0(),b.z0(),b.y1(),b.z1());case 1->new Rect(b.x0(),b.z0(),b.x1(),b.z1());default->new Rect(b.x0(),b.y0(),b.x1(),b.y1());};}
  private static void subtract(List<Rect> out,Rect r,Rect cut){
    double a=Math.max(r.a,cut.a),b=Math.max(r.b,cut.b),c=Math.min(r.c,cut.c),d=Math.min(r.d,cut.d);
    if(a>=c-1e-9||b>=d-1e-9){out.add(r);return;}
    if(r.a<a-1e-9)out.add(new Rect(r.a,r.b,a,r.d));if(c<r.c-1e-9)out.add(new Rect(c,r.b,r.c,r.d));
    if(r.b<b-1e-9)out.add(new Rect(a,r.b,c,b));if(d<r.d-1e-9)out.add(new Rect(a,d,c,r.d));
  }
  private TerrainEdgeFaces(){}
}
