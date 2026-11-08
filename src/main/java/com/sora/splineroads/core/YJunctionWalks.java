package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** The pedestrian island between the two one-way branches of a Y. */
public final class YJunctionWalks {
  public record Pocket(double outCut,double inCut,List<Part> fill,List<Part> tactile){}
  private static boolean inner(RoadSidewalks.Config c,int side){return c.enabled()&&c.smooth()&&(c.side()==RoadSidewalks.Side.BOTH||c.side()==(side>0?RoadSidewalks.Side.RIGHT:RoadSidewalks.Side.LEFT));}
  public static Pocket plan(Mesh stem,Mesh out,Mesh in){
    int side=-RoadProfile.trafficSign(stem.settings().options().leftTraffic());
    var a=out.settings().options().sidewalk();var b=in.settings().options().sidewalk();
    if(!inner(a,side)||!inner(b,side)||RoadProfile.catalog(out.settings().style()).type()!=RoadProfile.Type.ORDINARY)return null;
    double max=Math.min(out.length(),in.length())*.7,cut=Math.min(24,max);
    for(;cut<max;cut+=.5){V p=edge(out,cut,side),q=edge(in,in.length()-cut,side);if(p.sub(q).horizontalLength()>=Math.max(8,a.width()+b.width()+1))break;}
    if(cut>=max)return null;
    var fill=new ArrayList<Part>();int count=(int)Math.ceil(cut/.5);
    for(int i=1;i<=count;i++){
      double d0=cut*(i-1)/count,d1=cut*i/count;
      V p=edge(out,d0,side),q=edge(in,in.length()-d0,side),r=edge(out,d1,side),s=edge(in,in.length()-d1,side);
      V c0=p.add(q).mul(.5),c1=r.add(s).mul(.5);
      slab(fill,p,c0,r,c1,a);slab(fill,c0,q,c1,s,b);
    }
    var tactile=new ArrayList<Part>();
    if(a.tactile()&&b.tactile()&&a.width()>=2&&b.width()>=2){
      Sample sa=RoadStructures.sample(out,cut),sb=RoadStructures.sample(in,in.length()-cut);
      V p=sa.at(side*(sa.halfWidth()+RoadStreetscape.tactileOffset(a.width())),0),q=sb.at(side*(sb.halfWidth()+RoadStreetscape.tactileOffset(b.width())),0);
      V toward=sa.left().left(),away=sb.left().left();
      double handle=Math.min(cut*.65,p.sub(q).horizontalLength()*.65);
      V c=p.add(toward.mul(handle)),d=q.sub(away.mul(handle));
      var route=new ArrayList<V>();int n=Math.max(32,(int)Math.ceil(p.distance(q)*4));
      for(int i=0;i<=n;i++){double t=i/(double)n,u=1-t;route.add(p.mul(u*u*u).add(c.mul(3*u*u*t)).add(d.mul(3*u*t*t)).add(q.mul(t*t*t)));}
      for(int i=1;i<route.size();i++){
        V pa=route.get(i-1),pb=route.get(i);V na=(i==1?toward:route.get(i).sub(route.get(i-2)).horizontalUnit()).left(),nb=(i==route.size()-1?away:route.get(i+1).sub(route.get(i-1)).horizontalUnit()).left();
        tactile.add(new Part(pa.add(new V(0,.2,0)),pb.add(new V(0,.2,0)),.6,.015,false,Material.TACTILE).frames(na.mul(.3),nb.mul(.3)));
        for(double ridge:new double[]{-.18,0,.18})tactile.add(new Part(pa.add(na.mul(ridge)).add(new V(0,.215,0)),pb.add(nb.mul(ridge)).add(new V(0,.215,0)),.06,.02,false,Material.TACTILE).frames(na.mul(.03),nb.mul(.03)));
      }
    }
    var clip=new SidewalkJoins(List.of(stem,out,in),List.of());
    return new Pocket(cut,cut,clip.clip(fill),new SidewalkJoins(List.of(stem,out,in),List.of()).clip(tactile));
  }
  private static V edge(Mesh m,double d,int side){Sample s=RoadStructures.sample(m,d);return s.at(side*s.halfWidth(),0);}
  private static void slab(List<Part> result,V a,V b,V c,V d,RoadSidewalks.Config config){
    V first=a.add(b).mul(.5).add(new V(0,-.3,0)),last=c.add(d).mul(.5).add(new V(0,-.3,0));
    if(first.sub(last).horizontalLength()<1e-7)return;
    double width=Math.max(.001,Math.min(8,Math.max(a.distance(b),c.distance(d))));
    result.add(new Part(first,last,width,.5,false,RoadSidewalks.Finish.of(config.material()).material()).frames(a.sub(b).mul(.5),c.sub(d).mul(.5)));
  }
  public static List<Part> apply(Mesh own,Mesh stem,Mesh out,Mesh in,List<Part> original){
    Pocket pocket=plan(stem,out,in);if(pocket==null||own==stem)return original;
    boolean outbound=own==out;int side=-RoadProfile.trafficSign(stem.settings().options().leftTraffic());
    var result=new ArrayList<Part>();
    for(Part p:original){
      if(p.material()!=Material.TACTILE){result.add(p);continue;}
      V mid=p.a().add(p.b()).mul(.5);var query=RoadQueries.horizontal(own,mid);
      if(query.lateral()*side<=0){result.add(p);continue;}
      double a=distance(own,p.a(),outbound),b=distance(own,p.b(),outbound),cut=outbound?pocket.outCut:pocket.inCut;
      if(Math.max(a,b)<=cut+1e-7)continue;
      if(Math.min(a,b)>=cut-1e-7){result.add(p);continue;}
      double t=(cut-a)/(b-a);V at=p.a().add(p.b().sub(p.a()).mul(t)),fa=p.frameA(),fb=p.frameB();V frame=fa==null||fb==null?null:fa.mul(1-t).add(fb.mul(t));
      result.add(a<cut?new Part(at,p.b(),p.width(),p.height(),false,p.material(),frame,fb):new Part(p.a(),at,p.width(),p.height(),false,p.material(),fa,frame));
    }
    if(outbound){result.addAll(pocket.fill);result.addAll(pocket.tactile);}
    return result;
  }
  private static double distance(Mesh m,V p,boolean outbound){double d=RoadQueries.horizontal(m,p).sample().distance();return outbound?d:m.length()-d;}
  private YJunctionWalks(){}
}
