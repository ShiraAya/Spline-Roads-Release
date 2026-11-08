package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** Original twelve-sided impact drum, with a recessed lid and reflective bands. */
public final class RoadCrashCushion {
  public static final String MODEL="sr:crash_cushion";
  public static List<RoadSurface.Face> faces(Part part){
    var faces=new ArrayList<RoadSurface.Face>();V center=part.a();double r=part.width()/2;
    double[] heights={0,.08,.32,.43,.69,.80,1};
    for(int band=0;band+1<heights.length;band++)for(int i=0;i<12;i++){
      double a=2*Math.PI*i/12,b=2*Math.PI*(i+1)/12;
      double lo=heights[band]*part.height(),hi=heights[band+1]*part.height();
      double radius=band==0?r:r*.94;int color=band==0?0x33383A:band==2||band==4?0xF3F0D0:0xE9AF12;
      faces.add(new RoadSurface.Face(List.of(center.add(new V(radius*Math.cos(b),lo,radius*Math.sin(b))),center.add(new V(radius*Math.cos(a),lo,radius*Math.sin(a))),center.add(new V(radius*Math.cos(a),hi,radius*Math.sin(a))),center.add(new V(radius*Math.cos(b),hi,radius*Math.sin(b)))),color));
    }
    for(int i=0;i<12;i++){
      double a=2*Math.PI*i/12,b=2*Math.PI*(i+1)/12,y=part.height();
      double foot=.08*part.height();
      faces.add(new RoadSurface.Face(List.of(
          center.add(new V(r*Math.cos(a),foot,r*Math.sin(a))),
          center.add(new V(r*.94*Math.cos(a),foot,r*.94*Math.sin(a))),
          center.add(new V(r*.94*Math.cos(b),foot,r*.94*Math.sin(b))),
          center.add(new V(r*Math.cos(b),foot,r*Math.sin(b)))),0x33383A));
      faces.add(new RoadSurface.Face(List.of(center.add(new V(0,y-.025,0)),center.add(new V(r*.94*Math.cos(b),y,r*.94*Math.sin(b))),center.add(new V(r*.94*Math.cos(a),y,r*.94*Math.sin(a)))),0xBC8C13));
    }
    return List.copyOf(faces);
  }
  public static List<Part> plan(Mesh ramp,List<Mesh> neighbors,List<Part> parts){
    if(!ramp.settings().style().ramp()||ramp.settings().structure()==Structure.TUNNEL)return List.of();
    var decks=new ArrayList<Mesh>(neighbors);decks.add(ramp);var motors=decks.stream().map(LaneDeck::motorOnly).toList();
    // The sloping deck is the drum's foundation, not an above-deck obstacle.
    var occupied=new RoadSolidOverlap.Index(parts.stream().filter(p->p.top().stream()
        .anyMatch(v->v.y()>RoadQueries.horizontal(ramp,v).sample().center().y()+.03)).toList());
    var out=new ArrayList<Part>();
    for(var host:neighbors){
      if(host.settings().style().ramp()&&!RoadJunction.sharedRampFork(ramp,host))continue;
      var departure=RoadQueries.horizontal(host,ramp.first().center());
      if(departure.horizontalDistance()>departure.sample().halfWidth()+ramp.first().halfWidth()+.1
          ||Math.abs(departure.sample().center().y()-ramp.first().center().y())>.3
          ||Math.abs(departure.sample().left().dot(ramp.first().left()))<.9)continue;
      int flow=departure.sample().left().dot(ramp.first().left())>=0?1:-1;
      var support=ForkSignPlacement.find(ramp,host,parts,flow,false);if(support==null)continue;
      Part base=null;double closest=Double.POSITIVE_INFINITY;
      for(var part:parts)if(ForkSignPlacement.isBase(part)){
        V delta=part.b().sub(part.a());double len=delta.horizontalLength();if(len<.1)continue;
        double t=Math.max(0,Math.min(1,support.foot().sub(part.a()).dot(new V(delta.x(),0,delta.z()))/(len*len)));
        double distance=part.a().add(delta.mul(t)).sub(support.foot()).horizontalLength();
        if(distance<=part.width()/2+.1&&distance<closest){base=part;closest=distance;}
      }
      if(base==null)continue;
      V forward=RoadQueries.horizontal(ramp,support.foot()).tangent().horizontalUnit();
      V along=base.b().sub(base.a()).horizontalUnit();if(along.dot(forward)<0)along=along.mul(-1);
      V nose=base.a().dot(along)<base.b().dot(along)?base.a():base.b();
      // Seat the drum immediately ahead of this physical separator. Never hunt
      // far upstream for a vacant shoulder, or unrelated rails gain stray drums.
      placement: for(double gap=.27;gap<=.57;gap+=.05)for(double sideways:new double[]{0,.08,.16,.24}){
        V point=nose.sub(along.mul(gap));var q=RoadQueries.horizontal(ramp,point);var at=q.sample();
        // A drum is slightly wider than the urban barrier. Move only toward
        // the paved shoulder until its whole footprint clears the driving lane.
        point=point.add(at.left().mul(Math.signum(q.lateral())*sideways));
        V foot=new V(point.x(),at.center().y(),point.z());
        if(RoadStreetscape.underground(ramp,at.distance()))continue;
        var barrel=new Part(foot,foot,.46,.8,true,Material.CONCRETE,null,null,MODEL);
        if(occupied.intersects(barrel))continue;
        if(motors.stream().anyMatch(m->RoadClearance.structureInvades(barrel,m,4.25)))continue;
        boolean supported=barrel.base().stream().allMatch(p->decks.stream().anyMatch(m->RoadQueries.contains(m,p,.01,.08)));
        if(!supported)continue;
        if(out.stream().noneMatch(p->p.a().distance(barrel.a())<2))out.add(barrel);
        break placement;
      }
    }
    return List.copyOf(out);
  }
  private RoadCrashCushion(){}
}
