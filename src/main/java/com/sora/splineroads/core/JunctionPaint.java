package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadSurface.*;
import java.util.*;

/** Geometry-only marking primitives, also used by the GUI's exact plan preview. */
public final class JunctionPaint {
  public static final int WHITE=0xEDEEE2, YELLOW=0xFAC136;
  public static void line(List<Face> out,V a,V b,double width,int color) {
    if(a.sub(b).horizontalLength()<1e-7)return;
    V n=b.sub(a).horizontalUnit().left().mul(width/2);
    out.add(new Face(List.of(a.add(n),a.sub(n),b.sub(n),b.add(n)),color));
  }
  public static void path(List<Face> out,List<V> points,double width,boolean dashed,int color) {
    path(out,points,width,dashed,color,0);
  }
  public static void path(List<Face> out,List<V> points,double width,boolean dashed,int color,double phaseOffset) {
    double distance=(phaseOffset%6+6)%6;
    for(int i=1;i<points.size();i++) {
      V a=points.get(i-1),b=points.get(i);double length=b.sub(a).horizontalLength();
      for(double d=0;d<length-1e-7;) {
        double phase=(distance+d)%6,span=Math.min(length-d,(phase<3?3:6)-phase);
        if(!dashed||phase<3) line(out,a.add(b.sub(a).mul(d/length)),a.add(b.sub(a).mul((d+span)/length)),width,color);
        d+=Math.max(span,1e-6);
      }
      distance+=length;
    }
  }
  public static void ribbon(List<Face> out,List<V> points,double width,int color) {
    var normals=new ArrayList<V>();
    for(int i=0;i<points.size();i++) {
      V before=points.get(Math.max(1,i)).sub(points.get(Math.max(0,i-1))).horizontalUnit();
      V after=points.get(Math.min(points.size()-1,i+1)).sub(points.get(Math.min(points.size()-2,i))).horizontalUnit();
      if(i==0)before=after;if(i==points.size()-1)after=before;
      V normal=before.left().add(after.left()).horizontalUnit();
      normals.add(normal.mul(width/2/Math.max(.25,normal.dot(after.left()))));
    }
    for(int i=1;i<points.size();i++)out.add(new Face(List.of(points.get(i-1).add(normals.get(i-1)),points.get(i-1).sub(normals.get(i-1)),points.get(i).sub(normals.get(i)),points.get(i).add(normals.get(i))),color));
  }
  private static void head(List<Face> out,V tip,V direction,double length,double halfWidth) {
    V f=direction.horizontalUnit(),side=f.left();
    out.add(new Face(List.of(tip,tip.sub(f.mul(length)).add(side.mul(halfWidth)),
        tip.sub(f.mul(length)).sub(side.mul(halfWidth))),WHITE));
  }
  public static void arrow(List<Face> out,V at,V forward,int mask,double scale) {
    V f=forward.horizontalUnit().mul(scale),right=forward.horizontalUnit().left().mul(scale);
    if(mask==0){line(out,at.sub(f),at.add(f),.16,WHITE);line(out,at.sub(right.mul(.65)),at.add(right.mul(.65)),.2,WHITE);return;}
    boolean singleTurn=mask==JunctionSpec.LEFT||mask==JunctionSpec.RIGHT;
    if(!singleTurn)line(out,at.sub(f.mul(2.1)),at.add(f.mul(.25)),.22*scale,WHITE);
    if((mask&JunctionSpec.STRAIGHT)!=0){line(out,at,at.add(f.mul(1.85)),.22*scale,WHITE);head(out,at.add(f.mul(2.65)),forward,.95*scale,.40*scale);}
    for(int direction:new int[]{-1,1}) {
      int flag=direction<0?JunctionSpec.LEFT:JunctionSpec.RIGHT;
      if((mask&flag)==0)continue;
      V start=at.sub(f.mul(.25)),bend=at.add(f.mul(.9)),end=bend.add(right.mul(direction*.75));
      var curve=new ArrayList<V>();if(singleTurn)curve.add(at.sub(f.mul(2.1)));curve.add(start);
      for(int k=1;k<=8;k++){double t=k/8.,u=1-t;V p=start.mul(u*u).add(bend.mul(2*u*t)).add(end.mul(t*t));curve.add(p);}
      V tip=bend.add(right.mul(direction*1.55));
      curve.add(tip.sub(right.mul(direction*.4)));ribbon(out,curve,.22*scale,WHITE);
      head(out,tip,right.mul(direction),.65*scale,.32*scale);
    }
    if((mask&JunctionSpec.UTURN)!=0){
      V a=at.add(f.mul(.8)),b=a.sub(right.mul(.85)),c=b.sub(f.mul(1.1));
      line(out,at,a,.18*scale,WHITE);line(out,a,b,.18*scale,WHITE);line(out,b,c,.18*scale,WHITE);head(out,c,forward.mul(-1),.65*scale,.32*scale);
    }
  }
  public static double area(List<V> p) { double sum=0;if(p.isEmpty())return 0;V origin=p.get(0); for(int i=0;i<p.size();i++){V a=p.get(i).sub(origin),b=p.get((i+1)%p.size()).sub(origin);sum+=a.x()*b.z()-a.z()*b.x();}return sum/2; }
  public static boolean inside(List<V> polygon,V p) {
    boolean yes=false;
    for(int i=0,j=polygon.size()-1;i<polygon.size();j=i++) {
      V a=polygon.get(i),b=polygon.get(j);
      if((a.z()>p.z())!=(b.z()>p.z())&&p.x()<(b.x()-a.x())*(p.z()-a.z())/(b.z()-a.z())+a.x())yes=!yes;
    }return yes;
  }
  public static List<List<V>> triangulate(List<V> input) {
    var p=new ArrayList<V>();
    for(var v:input)if(p.isEmpty()||v.sub(p.get(p.size()-1)).horizontalLength()>1e-7)p.add(v);
    if(p.size()>1&&p.get(0).sub(p.get(p.size()-1)).horizontalLength()<1e-7)p.remove(p.size()-1);
    boolean changed=true;while(changed&&p.size()>3){changed=false;for(int i=0;i<p.size();i++){V a=p.get((i+p.size()-1)%p.size()),b=p.get(i),c=p.get((i+1)%p.size());if(Math.abs(cross(a,b,c))<1e-8&&b.sub(a).dot(b.sub(c))<=1e-8){p.remove(i);changed=true;break;}}}
    for(int i=0;i<p.size();i++)for(int j=i+2;j<p.size();j++){
      if(i==0&&j==p.size()-1)continue;V a=p.get(i),b=p.get((i+1)%p.size()),c=p.get(j),d=p.get((j+1)%p.size());
      double ab1=cross(a,b,c),ab2=cross(a,b,d),cd1=cross(c,d,a),cd2=cross(c,d,b);
      if(ab1*ab2<-1e-12&&cd1*cd2<-1e-12)throw new IllegalArgumentException("路口外缘自交，请拉开接入口或减小转角半径");
    }
    if(area(p)<0)Collections.reverse(p);
    var triangles=new ArrayList<List<V>>();int safety=p.size()*p.size();
    while(p.size()>3&&safety-->0){boolean found=false;
      for(int i=0;i<p.size();i++){
        V a=p.get((i+p.size()-1)%p.size()),b=p.get(i),c=p.get((i+1)%p.size());
        if(cross(a,b,c)<=1e-8)continue;boolean occupied=false;
        for(V v:p)if(v.sub(a).horizontalLength()>1e-7&&v.sub(b).horizontalLength()>1e-7&&v.sub(c).horizontalLength()>1e-7&&cross(a,b,v)>=-1e-8&&cross(b,c,v)>=-1e-8&&cross(c,a,v)>=-1e-8){occupied=true;break;}
        if(occupied)continue;triangles.add(List.of(a,b,c));p.remove(i);found=true;break;
      }if(!found)throw new IllegalArgumentException("路口外缘自交，请拉开接入口或减小转角半径");
    }
    if(p.size()==3&&Math.abs(area(p))>1e-8)triangles.add(List.copyOf(p));
    return List.copyOf(triangles);
  }
  public static List<List<V>> triangulateLegacy(List<V> input) {
    var p=new ArrayList<>(input);if(legacyArea(p)<0)Collections.reverse(p);
    var triangles=new ArrayList<List<V>>();int safety=p.size()*p.size();
    while(p.size()>3&&safety-->0){boolean found=false;
      for(int i=0;i<p.size();i++){
        V a=p.get((i+p.size()-1)%p.size()),b=p.get(i),c=p.get((i+1)%p.size());
        if(cross(a,b,c)<=1e-8)continue;boolean occupied=false;
        for(V v:p)if(v!=a&&v!=b&&v!=c&&cross(a,b,v)>=-1e-8&&cross(b,c,v)>=-1e-8&&cross(c,a,v)>=-1e-8){occupied=true;break;}
        if(occupied)continue;triangles.add(List.of(a,b,c));p.remove(i);found=true;break;
      }if(!found)throw new IllegalArgumentException("路口外缘自交，请拉开接入口或减小转角半径");
    }
    if(p.size()==3&&Math.abs(legacyArea(p))>1e-8)triangles.add(List.copyOf(p));
    return List.copyOf(triangles);
  }
  private static double legacyArea(List<V> p){double sum=0;for(int i=0;i<p.size();i++){V a=p.get(i),b=p.get((i+1)%p.size());sum+=a.x()*b.z()-a.z()*b.x();}return sum/2;}
  private static double cross(V a,V b,V c){return (b.x()-a.x())*(c.z()-a.z())-(b.z()-a.z())*(c.x()-a.x());}
  private JunctionPaint(){}
}
