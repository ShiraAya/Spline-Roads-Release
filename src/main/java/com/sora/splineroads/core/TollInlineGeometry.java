package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.TollPlaza.*;
import com.sora.splineroads.core.RoadStructures.Part;
import java.util.*;

/** Curved pavement and rigid equipment have separate transforms. Saved v5 retains its straight core. */
public final class TollInlineGeometry {
  public static Spec local(Spec s){
    if(s.path().isEmpty())return s;
    double length=s.path().get(s.path().size()-1).distance();
    return new Spec(new Node(new V(0,0,0),0,0),new Node(new V(0,0,length),0,0),s.config(),s.portA(),s.portB());
  }
  private static Mesh axis(Spec s){return RoadSlicing.mesh(s.path(),TollPlaza.port(s,true));}
  private static final class Alignment {
    final Mesh source;final boolean rigid;final double middle,from,to,length;final Sample center;final V forward;
    Alignment(Spec s){source=axis(s);rigid=s.rigidCore()&&!s.curvedDeck();length=source.length();middle=length/2;from=middle-TollPlanner.CORE/2;to=middle+TollPlanner.CORE/2;center=RoadSlicing.at(source,middle);forward=center.left().left().mul(-1);}
    V position(double d){
      var original=RoadSlicing.at(source,d).center();if(!rigid)return original;
      V p=center.center().add(forward.mul(d-middle));
      if(d<from)p=curve(source.first().center(),center.center().sub(forward.mul(TollPlanner.CORE/2)),source.first().left().left().mul(-1),forward,d/from);
      if(d>to)p=curve(center.center().add(forward.mul(TollPlanner.CORE/2)),source.last().center(),forward,source.last().left().left().mul(-1),(d-to)/(length-to));
      // Fit the vertical transition directly. Blending the old elevation into
      // a level core concentrates the entire height change near its boundary.
      double y=center.center().y();
      if(d<from){double u=d/from,g=RoadSlicing.at(source,Math.min(.1,length)).center().sub(source.first().center()).y()/Math.min(.1,length);y=source.first().center().y()+(y-source.first().center().y())*u*u*(3-2*u)+g*from*u*(u-1)*(u-1);}
      if(d>to){double run=length-to,u=(d-to)/run,g=source.last().center().sub(RoadSlicing.at(source,length-Math.min(.1,length)).center()).y()/Math.min(.1,length);y+=(source.last().center().y()-y)*u*u*(3-2*u)+g*run*u*u*(u-1);}
      return new V(p.x(),y,p.z());
    }
    private static V curve(V a,V b,V start,V end,double t){double h=a.sub(b).horizontalLength()/3,u=1-t;return a.mul(u*u*u).add(a.add(start.mul(h)).mul(3*u*u*t)).add(b.sub(end.mul(h)).mul(3*u*t*t)).add(b.mul(t*t*t));}
    Sample sample(double d){
      d=Math.max(0,Math.min(length,d));var old=RoadSlicing.at(source,d);
      if(!rigid||d<1e-8||d>length-1e-8)return old;
      V left=d>=from&&d<=to?center.left():position(Math.min(length,d+.01)).sub(position(Math.max(0,d-.01))).horizontalUnit().left();
      return new Sample(position(d),left,d,old.halfWidth());
    }
  }
  private static double equipmentLift(Mesh source){double middle=source.length()/2,top=RoadSlicing.at(source,middle).center().y();for(double d=middle-6.5;d<=middle+6.5;d+=.25)top=Math.max(top,RoadSlicing.at(source,d).center().y());return top-RoadSlicing.at(source,middle).center().y();}
  public static V equipmentAt(Spec s,double station,double lateral,double up){
    if(!s.curvedDeck()||s.path().isEmpty())return at(s,station,lateral,up);
    var source=axis(s);double middle=source.length()/2;var center=RoadSlicing.at(source,middle);
    return center.at(lateral,-up-equipmentLift(source)).add(center.left().left().mul(middle-station));
  }
  public static V at(Spec s,double station,double lateral,double up){
    var sample=new Alignment(s).sample(station);
    return sample.at(lateral,-up);
  }
  public static V direction(Spec s,boolean first){return s.path().isEmpty()?s.b().position().sub(s.a().position()).horizontalUnit():(first?s.path().get(0):s.path().get(s.path().size()-1)).left().left().mul(-1);}
  public static TollPlanner.Plan plan(Spec s){
    var base=TollPlanner.centered(local(s));var alignment=new Alignment(s);var source=alignment.source;
    var samples=new ArrayList<Sample>();
    for(var sample:base.mesh().samples()){
      var at=alignment.sample(sample.distance());samples.add(new Sample(at.center(),at.left(),sample.distance(),sample.halfWidth()));
    }
    var bs=base.mesh().settings();
    var mesh=RoadRibbon.mesh(samples,new Settings(bs.mode(),bs.style(),bs.width(),source.settings().thickness(),bs.tension(),bs.arcDegrees()).structure(bs.structure()).options(bs.options()));
    for(int i=1;i<samples.size();i++){
      var a=samples.get(i-1);var b=samples.get(i);var delta=b.center().sub(a.center());
      if(Math.abs(delta.y())/Math.max(1e-9,delta.horizontalLength())>TollPlanner.MAX_GRADE+1e-6)throw new IllegalArgumentException("收费站断面纵坡最多12%");
      for(int side:new int[]{-1,1})if(b.at(side*b.halfWidth(),0).sub(a.at(side*a.halfWidth(),0)).dot(delta)<=0)throw new IllegalArgumentException("收费站扩宽后弯道内侧折叠，请减少通道或选择更平缓路段");
    }
    if(s.rigidCore())RoadRibbon.checkSelfIntersections(mesh,4);
    java.util.function.UnaryOperator<V> warp=p->{var at=alignment.sample(p.z());return at.at(-p.x(),-p.y());};
    double middle=source.length()/2;var center=RoadSlicing.at(source,middle);double lift=s.curvedDeck()?equipmentLift(source):0;
    java.util.function.UnaryOperator<V> equipment=p->center.at(-p.x(),-p.y()-lift).add(center.left().left().mul(middle-p.z()));
    var parts=new ArrayList<Part>();for(var p:base.structures()){
      var transform=s.curvedDeck()&&!p.model().equals("sr:toll_outer")&&!p.model().equals("sr:toll_median")?equipment:warp;
      var q=p.base().stream().map(transform).toList();var a=q.get(0).add(q.get(1)).mul(.5);var b=q.get(2).add(q.get(3)).mul(.5);
      double depth=0;
      if(s.curvedDeck()&&p.model().equals("sr:toll_island"))for(double d=p.a().z();d<=p.b().z()+1e-7;d+=.25)depth=Math.max(depth,center.center().y()+lift-RoadSlicing.at(source,d).center().y()+.02);
      parts.add(new Part(a.sub(new V(0,depth,0)),b.sub(new V(0,depth,0)),p.width(),p.height()+depth,p.pier(),p.material(),q.get(0).sub(q.get(1)).mul(.5),q.get(3).sub(q.get(2)).mul(.5),p.model(),p.heights()));
    }
    var paint=base.paint().stream().map(f->new RoadSurface.Face(f.points().stream().map(warp).toList(),f.color(),f.emissive(),f.texture(),f.uv())).toList();
    var units=base.units().stream().map(u->new TollPlanner.Unit(u.direction(),u.index(),u.etc(),(s.curvedDeck()?equipment:warp).apply(u.center()),u.clearWidth(),u.clearHeight())).toList();
    return new TollPlanner.Plan(s,base.lengths(),mesh,List.copyOf(parts),paint,units,base.coreStart(),base.coreEnd());
  }
  private TollInlineGeometry(){}
}
