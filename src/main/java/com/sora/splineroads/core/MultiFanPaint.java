package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadJunction.*;
import java.util.*;

/** Five/six-arm fans share lane space as a group, never as overlapping two-way forks. */
public final class MultiFanPaint {
  public static boolean applies(Mesh m) {return m.settings().style().ramp()&&m.settings().options().routing().fanArms()>=5;}
  private record Branch(Mesh mesh,boolean first,V origin,V axis,double reach) {
    Sample root(){return first?mesh.first():mesh.last();}
    Sample at(double station){
      if(station<=0)return root();
      var samples=mesh.samples();int size=samples.size();
      double max=Math.min(mesh.length(),reach*1.5+16);
      int lo=0,hi=size-1;
      // Locate the local feeder once by distance, then bisect axial station directly on
      // existing samples. Avoid nested interpolation/search and transient Sample objects.
      while(hi-lo>1){int mid=(lo+hi)/2;Sample p=samples.get(first?mid:size-1-mid);
        double d=first?p.distance():mesh.length()-p.distance();if(d<max)lo=mid;else hi=mid;}
      lo=0;
      while(hi-lo>1){int mid=(lo+hi)/2;Sample p=samples.get(first?mid:size-1-mid);
        if(p.center().sub(origin).dot(axis)<station)lo=mid;else hi=mid;}
      Sample a=samples.get(first?lo:size-1-lo),b=samples.get(first?hi:size-1-hi);
      double x=a.center().sub(origin).dot(axis),y=b.center().sub(origin).dot(axis);
      double t=Math.max(0,Math.min(1,(station-x)/Math.max(1e-9,y-x)));
      return new Sample(a.center().add(b.center().sub(a.center()).mul(t)),a.left().add(b.left().sub(a.left()).mul(t)).horizontalUnit(),
          a.distance()+(b.distance()-a.distance())*t,a.halfWidth()+(b.halfWidth()-a.halfWidth())*t);
    }
  }
  private static Branch branch(Mesh m,boolean first){
    Sample root=first?m.first():m.last();var r=m.settings().options().routing();
    return new Branch(m,first,root.center(),root.left().left().mul(first?-1:1),first?r.fanStart():r.fanEnd());
  }
  private static List<Branch> group(Mesh m,List<Mesh> neighbors,boolean first){
    Branch own=branch(m,first);var out=new ArrayList<Branch>();out.add(own);
    for(Mesh peer:neighbors){
      if(peer==m||!applies(peer)||peer.settings().options().routing().fanArms()!=m.settings().options().routing().fanArms())continue;
      Branch b=branch(peer,first);V delta=b.origin.sub(own.origin);
      if(Math.abs(delta.y())<.05&&Math.abs(delta.dot(own.axis))<.15
          &&delta.horizontalLength()<16&&b.axis.dot(own.axis)>.99999)out.add(b);
    }
    V side=own.axis.left();
    out.sort(Comparator.comparingDouble((Branch b)->b.at(b.reach).center().dot(side))
        .thenComparingDouble(b->b.mesh.first().center().x()).thenComparingDouble(b->b.mesh.last().center().z()));
    return out;
  }
  public static List<Zone> zones(Mesh m){
    var a=branch(m,true);var b=branch(m,false);
    return List.of(new Zone(0,a.at(a.reach).distance()),new Zone(b.at(b.reach).distance(),m.length()));
  }
  private static void line(List<Paint> out,V a,V b,double width){
    V side=b.sub(a).horizontalUnit().left().mul(width/2);if(a.distance(b)<1e-5)return;
    out.add(new Paint(List.of(a.add(side),a.sub(side),b.sub(side),b.add(side)),0xEDEEE2));
  }
  private record Slice(List<Sample> points,double[] lateral,double[] half,V side) {}
  private static Slice slice(List<Branch> group,double d){
    var samples=group.stream().map(b->b.at(d)).toList();V side=group.get(0).axis.left();
    double[] lateral=new double[samples.size()],half=new double[samples.size()];
    for(int i=0;i<samples.size();i++){var p=samples.get(i);lateral[i]=p.center().dot(side);half[i]=p.halfWidth()/Math.max(.8,Math.abs(p.left().dot(side)));}
    return new Slice(samples,lateral,half,side);
  }
  private static V point(Slice s,int i,double lateral){var p=s.points.get(i);return p.center().add(s.side.mul(lateral-s.lateral[i]));}
  private static double low(Slice s,int i){return i==0?s.lateral[i]-s.half[i]:Math.max(s.lateral[i]-s.half[i],(s.lateral[i-1]+s.lateral[i])/2);}
  private static double high(Slice s,int i){return i==s.points.size()-1?s.lateral[i]+s.half[i]:Math.min(s.lateral[i]+s.half[i],(s.lateral[i]+s.lateral[i+1])/2);}
  public static List<Paint> markings(Mesh m,List<Mesh> neighbors){
    var out=new ArrayList<Paint>();
    for(boolean first:new boolean[]{true,false}){
      var g=group(m,neighbors,first);if(g.get(0).mesh!=m)continue;
      double reach=g.stream().mapToDouble(Branch::reach).min().orElse(0);
      // One direction cue per shared mouth, not one stacked arrow per destination.
      Slice entry=slice(g,16);double low=low(entry,0),high=high(entry,g.size()-1);
      int lanes=high-low>=6.4?2:1;var arrows=new ArrayList<RoadSurface.Face>();
      for(int lane=0;lane<lanes;lane++)JunctionPaint.arrow(arrows,point(entry,0,low+(lane+.5)*(high-low)/lanes),
          g.get(0).axis.mul(first?1:-1),JunctionSpec.STRAIGHT,.7);
      if(!m.settings().options().hideArrows())for(var arrow:arrows)out.add(new Paint(arrow.points(),arrow.color()));
      for(double d=0;d<reach;d+=1.5){
        double end=Math.min(reach,d+1.5);Slice a=slice(g,d),b=slice(g,end);
        boolean dash=((int)(d/3)&1)==0;
        // A branch gets its own second lane only once the actual free corridor fits it.
        if(dash&&RoadProfile.catalog(m.settings().style()).lanes()==2)for(int i=0;i<g.size();i++){
          if(high(a,i)-low(a,i)<6.6||high(b,i)-low(b,i)<6.6)continue;
          line(out,point(a,i,(low(a,i)+high(a,i))/2),point(b,i,(low(b,i)+high(b,i))/2),.15);
        }
        for(int i=0;i+1<g.size();i++){
          double sep=a.lateral[i+1]-a.lateral[i],sepB=b.lateral[i+1]-b.lateral[i];
          double overlap=a.half[i]+a.half[i+1]-sep,overlapB=b.half[i]+b.half[i+1]-sepB;
          double mid=(a.lateral[i]+a.lateral[i+1])/2,midB=(b.lateral[i]+b.lateral[i+1])/2;
          if(overlap<0||overlapB<0)continue;
          // Keep one shared divider at a common two-lane mouth, then add only useful lanes.
          boolean central=i==(g.size()-1)/2&&RoadProfile.catalog(m.settings().style()).lanes()==2;
          if(dash&&overlap>1.6&&overlapB>1.6&&(central||sep>=3.2&&sepB>=3.2))
            line(out,point(a,i,mid),point(b,i,midB),.15);
          // A narrow physical gore lives between neighboring branches, never across live lanes.
          if(overlap<=1.6&&overlapB<=1.6){
            double wa=Math.min(.65,Math.max(.02,(1.6-overlap)*.4)),wb=Math.min(.65,Math.max(.02,(1.6-overlapB)*.4));
            for(int side:new int[]{-1,1})line(out,point(a,i,mid+side*wa),point(b,i,midB+side*wb),.15);
            if(((int)(d/1.5))%3==0){V tip=point(b,i,midB);line(out,point(a,i,mid-wa),tip,.18);line(out,tip,point(a,i,mid+wa),.18);}
          }
        }
      }
    }
    return List.copyOf(out);
  }
  private MultiFanPaint(){}
}
