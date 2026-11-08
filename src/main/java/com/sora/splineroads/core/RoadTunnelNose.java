package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;
import java.util.function.DoubleUnaryOperator;
/** One solid divider nose between linked tunnel ribbons, with a blunt front cap. */
public final class RoadTunnelNose {
  public record Join(List<Part> volume,List<Part> solid,List<Part> pavement){}
  private record Edge(V host,V ramp,V hostNormal,V rampNormal,double gap,double floor,double top,double envelope){
    Edge mix(Edge b,double t){return new Edge(host.mul(1-t).add(b.host.mul(t)),ramp.mul(1-t).add(b.ramp.mul(t)),hostNormal.mul(1-t).add(b.hostNormal.mul(t)).horizontalUnit(),rampNormal.mul(1-t).add(b.rampNormal.mul(t)).horizontalUnit(),gap*(1-t)+b.gap*t,floor*(1-t)+b.floor*t,top*(1-t)+b.top*t,envelope*(1-t)+b.envelope*t);}
  }
  public static Join build(Mesh host,Mesh ramp,double from,double to,DoubleUnaryOperator hostCeiling,DoubleUnaryOperator rampCeiling){
    var volume=new ArrayList<Part>();var solid=new ArrayList<Part>();var pavement=new ArrayList<Part>();var stations=new TreeSet<Double>();
    from=Math.max(0,from);to=Math.min(ramp.length(),to);if(to<=from)return new Join(List.of(),List.of(),List.of());
    stations.add(from);stations.add(to);for(double d=from;d<to;d+=.5)stations.add(d);
    for(var at:ramp.samples())if(at.distance()>from&&at.distance()<to)stations.add(at.distance());
    Edge previous=null;
    for(double d:stations){
      var at=RoadStructures.sample(ramp,d);var query=RoadQueries.horizontal(host,at.center());var main=query.sample();
      int side=query.lateral()<0?-1:1;
      double inner=Math.signum(main.center().sub(at.center()).dot(at.left()));
      V a=main.at(side*(main.halfWidth()+.08),0),b=at.at(inner*(at.halfWidth()+.08),0);
      double gap=b.sub(a).dot(main.left().mul(side));
      double hc=hostCeiling.applyAsDouble(main.distance()),rc=rampCeiling.applyAsDouble(d);
      Edge current=null;
      if(hc>=1&&rc>=1&&Math.abs(at.left().dot(main.left()))>.45){
        double floor=Math.min(a.y()-host.settings().thickness(),b.y()-ramp.settings().thickness());
        double top=Math.min(a.y()+hc,b.y()+rc);
        if(top-floor>1)current=new Edge(a,b,main.left().mul(side),at.left().mul(inner),gap,floor,top,Math.max(a.y()+hc,b.y()+rc));
      }
      if(previous!=null&&current!=null){// Replace only the height actually filled by the shared nose. Reciprocal travel apertures remove the rest.
      add(volume,previous,current,-.28,2*RoadAutoTunnels.LINING,.15);add(solid,previous,current,.35,2*RoadAutoTunnels.LINING,0);apron(pavement,previous,current);}
      previous=current;
    }
    return new Join(List.copyOf(volume),List.copyOf(solid),List.copyOf(pavement));
  }
  private static void add(List<Part> out,Edge a,Edge b,double minimum,double maximum,double padding){
    double lo=0,hi=1,change=b.gap()-a.gap();
    if(Math.abs(change)<1e-10){if(a.gap()<minimum||a.gap()>maximum)return;}
    else {double first=(minimum-a.gap())/change,last=(maximum-a.gap())/change;lo=Math.max(0,Math.min(first,last));hi=Math.min(1,Math.max(first,last));if(hi<=lo+1e-8)return;}
    var x=a.mix(b,lo);var y=a.mix(b,hi);
    // The clipping envelope includes the .13 m skin between each existing
    // wall inner face (edge-.05) and the travel aperture (edge+.08). Leaving
    // that skin outside the mask produced TWO paper tips ahead of the blunt cap.
    x=new Edge(x.host().sub(x.hostNormal().mul(padding)),x.ramp().sub(x.rampNormal().mul(padding)),x.hostNormal(),x.rampNormal(),x.gap(),x.floor(),x.top(),x.envelope());
    y=new Edge(y.host().sub(y.hostNormal().mul(padding)),y.ramp().sub(y.rampNormal().mul(padding)),y.hostNormal(),y.rampNormal(),y.gap(),y.floor(),y.top(),y.envelope());
    V h0=new V(x.host().x(),x.floor(),x.host().z()),r0=new V(x.ramp().x(),x.floor(),x.ramp().z()),h1=new V(y.host().x(),y.floor(),y.host().z()),r1=new V(y.ramp().x(),y.floor(),y.ramp().z());
    if(Math.abs(JunctionPaint.area(List.of(h0,r0,r1,h1)))<1e-9)return;
    V c0=h0.add(r0).mul(.5),c1=h1.add(r1).mul(.5);double ha=x.top()-x.floor(),hb=y.top()-y.floor();
    out.add(new Part(c0,c1,Math.max(h0.distance(r0),h1.distance(r1)),Math.max(ha,hb),false,Material.TUNNEL,h0.sub(c0),h1.sub(c1),"sr:tunnel_nose",new Heights(ha,ha,hb,hb)));
  }
  private static void apron(List<Part> out,Edge a,Edge b){
    double lo=0,hi=1,change=b.gap()-a.gap();
    if(Math.abs(change)<1e-10){if(a.gap()<0||a.gap()>2*RoadAutoTunnels.LINING)return;}
    else {double x=-a.gap()/change,y=(2*RoadAutoTunnels.LINING-a.gap())/change;lo=Math.max(0,Math.min(x,y));hi=Math.min(1,Math.max(x,y));if(hi<=lo+1e-8)return;}
    // Asphalt is a horizontal seam, never a curtain between roads at different
    // elevations. Clip the join where its transverse rise exceeds a quarter block.
    double rise=a.host().y()-a.ramp().y(),slope=b.host().y()-b.ramp().y()-rise;
    if(Math.abs(slope)<1e-10){if(Math.abs(rise)>.25)return;}
    else {double u=(-.25-rise)/slope,v=(.25-rise)/slope;lo=Math.max(lo,Math.min(u,v));hi=Math.min(hi,Math.max(u,v));if(hi<=lo+1e-8)return;}
    Edge x=a.mix(b,lo),y=a.mix(b,hi);
    V h0=x.host().sub(x.hostNormal().mul(.15)),r0=x.ramp().sub(x.rampNormal().mul(.15));
    V h1=y.host().sub(y.hostNormal().mul(.15)),r1=y.ramp().sub(y.rampNormal().mul(.15));
    if(Math.abs(JunctionPaint.area(List.of(h0,r0,r1,h1)))<1e-9)return;
    V c0=h0.add(r0).mul(.5),c1=h1.add(r1).mul(.5);
    out.add(new Part(c0.add(new V(0,-.25,0)),c1.add(new V(0,-.25,0)),Math.max(h0.distance(r0),h1.distance(r1)),.25,false,Material.CONCRETE,h0.sub(c0),h1.sub(c1),"sr:tunnel_apron"));
  }
  public static List<Part> apply(List<Part> parts,Join join){
    if(join.volume().isEmpty())return parts;
    var mask=RoadShellOpening.solids(join.volume());var out=new ArrayList<Part>();
    for(var p:parts)if(p.material()==Material.TUNNEL)out.addAll(mask.cut(p));else out.add(p);
    out.addAll(join.solid());out.addAll(join.pavement());return List.copyOf(out);
  }
  private RoadTunnelNose(){}
}
