package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** Uses the built inner barrier, so the sign cannot precede the real separator. */
public final class ForkSignPlacement {
  /** A saved connection identifies one physical mouth, including its driving frame. */
  public record Mouth(UUID connection,LanePoints.Departure departure,int lane,int flow,double station){}
  private record Candidate(SmartSigns.Support support,double station,boolean base,double x,double z,Part foundation){}
  public static SmartSigns.Support findConnected(Mesh ramp,Mesh host,List<Part> parts,Mouth mouth,boolean paired){
    if(ramp.settings().structure()==Structure.TUNNEL)return null;
    if((mouth.departure()==LanePoints.Departure.DETACH||mouth.departure()==LanePoints.Departure.TEMPORARY)
        &&host.settings().options().lanePoints().cuts().stream().noneMatch(c->c.connection().equals(mouth.connection())&&!c.arrival()&&c.lane()==mouth.lane()&&c.sign()==mouth.flow()&&Math.abs(c.begin()-mouth.station())<.01))return null;
    var candidates=new ArrayList<Candidate>();
    for(var part:parts){
      boolean base=isBase(part),beam=isBeam(part);if(!base&&!beam)continue;
      V delta=part.b().sub(part.a());double length=delta.horizontalLength();if(length<.4)continue;
      for(V start:List.of(part.a(),part.b())){
        var q=RoadQueries.horizontal(ramp,start);var at=q.sample();var near=RoadQueries.horizontal(host,start);var h=near.sample();
        // A looping ramp can pass the same X/Z on two levels. Its projected
        // driving frame does not make an upper railing a seat for the lower exit.
        if(!atDeckHeight(start,at,h))continue;
        V forward=at.left().left().mul(-1),hostForward=h.left().left().mul(-mouth.flow());
        if(at.distance()>256||mouth.flow()*(h.distance()-mouth.station())<-.05
            ||at.distance()<1e-5&&start.sub(at.center()).dot(forward)<-.05)continue;
        if(Math.abs(at.center().y()-h.center().y())>3.5||forward.dot(hostForward)<.25)continue;
        V toward=h.center().sub(at.center());double gap=toward.horizontalLength();
        // A common outside rail cannot lie between both actual centre lines.
        if(gap<.15||start.sub(at.center()).dot(new V(toward.x(),0,toward.z()))<=.01
            ||start.sub(h.center()).dot(new V(-toward.x(),0,-toward.z()))<=.01)continue;
        int inner=at.left().dot(toward)>=0?1:-1;
        if(Math.abs(q.lateral()*inner-at.halfWidth())>.85||Math.abs(delta.horizontalUnit().dot(forward))<.65)continue;
        V along=delta.horizontalUnit().mul(delta.dot(forward)>=0?1:-1);
        V nose=part.a().dot(along)<part.b().dot(along)?part.a():part.b();
        double advance=Math.min(1.25,length*.65);V on=nose.add(along.mul(advance));
        double fraction=on.sub(part.a()).horizontalLength()/length;
        double top=beam?RoadQueries.horizontal(ramp,on).sample().center().y():part.a().y()+(part.b().y()-part.a().y())*fraction+part.height();
        V foot=new V(on.x(),top,on.z()).add(at.left().mul(inner*Math.min(.07,part.width()*.2)));
        var finalRamp=RoadQueries.horizontal(ramp,foot);var finalHost=RoadQueries.horizontal(host,foot);
        if(mouth.flow()*(finalHost.sample().distance()-mouth.station())<-.05||finalRamp.sample().distance()+.05<at.distance())continue;
        var support=new SmartSigns.Support(foot,h.left().mul(mouth.flow()),h.left().mul(mouth.flow()).left(),paired);
        candidates.add(new Candidate(support,at.distance(),base,nose.x(),nose.z(),part));
      }
    }
    candidates.sort(Comparator.comparingDouble(Candidate::station).thenComparing(c->!c.base()).thenComparingDouble(Candidate::x).thenComparingDouble(Candidate::z).thenComparingDouble(c->c.support().foot().x()).thenComparingDouble(c->c.support().foot().y()).thenComparingDouble(c->c.support().foot().z()).thenComparingDouble(c->c.support().right().x()).thenComparingDouble(c->c.support().right().z()));
    var rampMotor=LaneDeck.motorOnly(ramp);var hostMotor=LaneDeck.motorOnly(host);
    for(var c:candidates){var foot=c.support().foot();var post=new Part(foot,foot,.24,4.6,true,Material.STEEL);
      // A built separator already excludes driving through its footprint. At a
      // detached mouth the nominal ramp band still overlaps that concrete nose;
      // do not push a wholly seated post downstream to the end of that overlap.
      if(c.base()&&seated(post,c.foundation())
          ||!RoadClearance.structureInvades(post,rampMotor,4.25)&&!RoadClearance.structureInvades(post,hostMotor,4.25)){
        var support=plantedEdgeSeat(seat(c.support(),parts),ramp,host,parts);V at=support.foot().add(support.front().mul(-.08));
        var mast=new Part(at,at,.30,7.87,true,Material.STEEL);
        if(parts.stream().noneMatch(p->!mountingContact(mast,p,support)&&RoadSolidOverlap.intersects(mast,p)))return support;
      }}
    return null;
  }
  /** A connected inner lane can meet a planted separator. Put its mast beside
   * the planting, on the existing paved shoulder, instead of above a rail tube. */
  private static SmartSigns.Support plantedEdgeSeat(SmartSigns.Support support,Mesh ramp,Mesh host,List<Part> parts){
    var origin=support.foot().add(support.front().mul(-.08));var original=new Part(origin,origin,.30,7.87,true,Material.STEEL);
    if(parts.stream().noneMatch(p->(p.material()==Material.GREEN||p.material()==Material.SOIL)&&RoadSolidOverlap.intersects(original,p)))return support;
    var rampMotor=LaneDeck.motorOnly(ramp);var hostMotor=LaneDeck.motorOnly(host);
    for(double offset=.15;offset<=1.51;offset+=.15)for(int side:new int[]{-1,1}){
      V foot=support.foot().add(support.right().mul(side*offset));
      if(!RoadQueries.contains(ramp,foot,.05,.2)&&!RoadQueries.contains(host,foot,.05,.2))continue;
      double y=foot.y();var probe=new Part(foot,foot,.30,.6,true,Material.STEEL);
      for(var p:parts)if(p.material()==Material.CONCRETE&&seated(probe,p)){
        double top=p.top().stream().mapToDouble(V::y).max().orElse(y);if(top>=y&&top<=foot.y()+.4)y=Math.max(y,top);
      }
      foot=new V(foot.x(),y,foot.z());var candidate=new SmartSigns.Support(foot,support.right(),support.front(),support.paired(),support.cantileverSide());
      V at=foot.add(support.front().mul(-.08));var mast=new Part(at,at,.30,7.87,true,Material.STEEL);
      if(RoadClearance.structureInvades(mast,rampMotor,4.25)||RoadClearance.structureInvades(mast,hostMotor,4.25))continue;
      if(parts.stream().noneMatch(p->!mountingContact(mast,p,candidate)&&RoadSolidOverlap.intersects(mast,p)))return candidate;
    }
    return support;
  }
  private static boolean seated(Part post,Part foundation){
    var polygon=foundation.base();
    for(var p:post.base()){
      boolean positive=false,negative=false;
      for(int i=0;i<polygon.size();i++){
        V a=polygon.get(i),b=polygon.get((i+1)%polygon.size());
        double cross=(b.x()-a.x())*(p.z()-a.z())-(b.z()-a.z())*(p.x()-a.x());
        positive|=cross>1e-8;negative|=cross< -1e-8;
      }
      if(positive&&negative)return false;
    }
    return true;
  }
  private static boolean atDeckHeight(V point,Sample ramp,Sample host){
    // Bases start at deck level; the highest supported rail tube starts at 1.05 m.
    return Math.min(Math.abs(point.y()-ramp.center().y()),Math.abs(point.y()-host.center().y()))<=1.3;
  }
  static boolean isBase(Part p){return p.material()==Material.TUNNEL&&(p.model().equals("sr:tunnel_nose")||p.model().startsWith("sr:auto_cut:"))||p.material()==Material.CONCRETE&&!p.pier()&&p.width()>=.08
      &&(p.width()<=.62+1e-5&&Math.abs(p.height()-.8)<1e-5||p.width()<=.42+1e-5&&Math.abs(p.height()-.45)<1e-5||p.width()<=.7+1e-5&&Math.abs(p.height()-.5)<1e-5);}
  private static boolean isBeam(Part p){return p.material()==Material.STEEL&&!p.pier()&&(Math.abs(p.width()-.3)<1e-5&&Math.abs(p.height()-.18)<1e-5||Math.abs(p.width()-.1)<1e-5&&Math.abs(p.height()-.1)<1e-5||Math.abs(p.width()-.12)<1e-5&&Math.abs(p.height()-.12)<1e-5);}
  public static SmartSigns.Support find(Mesh ramp,Mesh host,List<Part> parts,int flow,boolean paired){
    return find(ramp,host,parts,flow,paired,false);
  }
  /** Two diverging child roads can overlap at their start. Their shared outer
   * rail is not a fork nose: the support must lie between the two centre lines. */
  public static SmartSigns.Support findBetween(Mesh ramp,Mesh sibling,List<Part> parts,int flow,boolean paired){
    return find(ramp,sibling,parts,flow,paired,true);
  }
  private static SmartSigns.Support find(Mesh ramp,Mesh host,List<Part> parts,int flow,boolean paired,boolean between){
    if(ramp.settings().structure()==Structure.TUNNEL)return null;
    V best=null;double first=Double.POSITIVE_INFINITY;V heading=null;boolean bestBase=false;
    for(var p:parts){
      boolean base=isBase(p),beam=isBeam(p);
      if(!base&&!beam)continue;
      V delta=p.b().sub(p.a());double length=delta.horizontalLength();if(length<.4)continue;
      for(V start:List.of(p.a(),p.b())){
        var q=RoadQueries.horizontal(ramp,start);var at=q.sample();
        // Only a full-width ramp can own an inner barrier. The actually built
        // rail is authoritative: vertical separation can expose it while
        // pavement edges still touch in plan (roundabout and SPUI feeders).
        if(at.halfWidth()<ramp.settings().width()/2-.15||at.distance()>256||at.distance()>first)continue;
        if(!onPath(ramp,start,.05)||!onPath(host,start,.05))continue;
        var near=RoadQueries.horizontal(host,start);var h=near.sample();
        if(!atDeckHeight(start,at,h))continue;
        if(between&&(at.left().dot(h.left().mul(flow))<.5||q.lateral()*near.lateral()*flow>=-.05))continue;
        if(Math.abs(at.center().y()-h.center().y())>3.5)continue;
        if(beam&&bestBase&&Math.abs(at.distance()-first)<1e-5)continue;
        int inner=at.left().dot(h.center().sub(at.center()))>=0?1:-1;
        if(Math.abs(q.lateral()*inner-at.halfWidth())>.85)continue;
        V forward=at.left().left().mul(-1);
        // A clamped endpoint is not a projection onto the ramp. On a closed ring
        // its upstream outside rail otherwise appears to be the earliest nose.
        if(at.distance()<1e-5&&start.sub(at.center()).dot(forward)<-.05)continue;
        {
          V toward=h.center().sub(at.center());
          if(toward.horizontalLength()<.15||start.sub(at.center()).dot(toward)<=.01
              ||start.sub(h.center()).dot(toward.mul(-1))<=.01)continue;
        }
        if(Math.abs(delta.horizontalUnit().dot(forward))<.65)continue;
        // The first actual barrier segment supplies the support. Move behind
        // its nose, then seat the flange on its concrete cap, on the gore side.
        V along=delta.horizontalUnit().mul(delta.dot(forward)>=0?1:-1);
        V nose=p.a().dot(along)<p.b().dot(along)?p.a():p.b();
        double advance=Math.min(1.25,length*.65);
        V foot=nose.add(along.mul(advance)).add(at.left().mul(inner*(beam?.18:.07)));
        double fraction=Math.max(0,Math.min(1,foot.sub(p.a()).dot(delta)/(length*length)));
        var top=p.top();double firstTop=(top.get(0).y()+top.get(1).y())/2,lastTop=(top.get(2).y()+top.get(3).y())/2;
        foot=new V(foot.x(),beam?RoadQueries.horizontal(ramp,foot).sample().center().y():firstTop+(lastTop-firstTop)*fraction,foot.z());
        // Keep the support above the owning barrier footprint, never an
        // interpolated midpoint over the void between two bridge decks.
        V right=h.left().mul(flow);best=foot;heading=right;first=at.distance();bestBase=base;
      }
    }
    return best==null?null:seat(new SmartSigns.Support(best,heading,heading.left(),paired),parts);
  }
  /** A mast bolted to the nose may meet its small rail tubes/post at the foot.
   * This never exempts a wall, concrete shell, overhead beam or the sign board. */
  public static boolean mountingContact(Part mast,Part rail,SmartSigns.Support support){
    if(support==null||!mast.pier()||mast.material()!=Material.STEEL||Math.abs(mast.width()-.30)>1e-6
        ||mast.a().distance(support.foot().add(support.front().mul(-.08)))>1e-6)return false;
    boolean railing=isBeam(rail)||rail.material()==Material.DARK_STEEL&&!rail.pier()
        &&Math.abs(rail.width()-.16)<1e-6&&rail.height()<=1.22;
    if(!railing)return false;
    double low=rail.base().stream().mapToDouble(V::y).min().orElseThrow(),high=rail.top().stream().mapToDouble(V::y).max().orElseThrow();
    return low>=support.foot().y()-.10&&high<=support.foot().y()+1.3;
  }
  /** The host's narrow rail cap can overlap the ramp's lower nose base. Seat
   * the flange on the finished concrete cap instead of starting inside it. */
  private static SmartSigns.Support seat(SmartSigns.Support support,List<Part> parts){
    V foot=support.foot(),best=foot;double top=foot.y(),shift=Double.POSITIVE_INFINITY;
    for(var p:parts)if(isBase(p)||!p.pier()&&p.material()==Material.CONCRETE
        &&Math.abs(p.width()-.36)<1e-5&&Math.abs(p.height()-.35)<1e-5){
      V d=p.b().sub(p.a());double length=d.horizontalLength();if(length<.31)continue;
      V center=foot.add(support.front().mul(-.08));
      double t=Math.max(.16/length,Math.min(1-.16/length,center.sub(p.a()).dot(new V(d.x(),0,d.z()))/(length*length)));
      double y=p.a().y()+t*d.y()+p.height();
      if(y<foot.y()-1e-5||y>foot.y()+1.2)continue;
      V on=p.a().add(d.mul(t));V candidate=new V(on.x(),y,on.z()).sub(support.front().mul(-.08));
      double distance=candidate.sub(foot).horizontalLength();if(distance>.26||distance>shift+1e-7)continue;
      var flange=new Part(candidate.add(support.front().mul(-.08)),candidate.add(support.front().mul(-.08)),.30,.4,true,Material.STEEL);
      if(!seated(flange,p))continue;
      if(distance<shift-1e-7||y>top){best=candidate;top=y;shift=distance;}
    }
    // A level flange rests on the highest cap point under its full footprint,
    // not the cap height at its centre. Adjacent short/sloped cap pieces count
    // too; otherwise tiny corner penetrations suppress an otherwise valid sign.
    V center=best.add(support.front().mul(-.08));double seatedY=best.y();
    for(var p:parts)if(isBase(p)||!p.pier()&&p.material()==Material.CONCRETE
        &&Math.abs(p.width()-.36)<1e-5&&Math.abs(p.height()-.35)<1e-5){
      var cap=p.top();cap=clipCap(cap,0,center.x()-.15,true);cap=clipCap(cap,0,center.x()+.15,false);
      cap=clipCap(cap,2,center.z()-.15,true);cap=clipCap(cap,2,center.z()+.15,false);
      double high=cap.stream().mapToDouble(V::y).max().orElse(Double.NEGATIVE_INFINITY);
      // Only correct seating on this cap, never lift a pole over an obstacle.
      if(high>=best.y()&&high<=best.y()+.10)seatedY=Math.max(seatedY,high);
    }
    if(seatedY>best.y())best=new V(best.x(),seatedY,best.z());
    return best==foot?support:new SmartSigns.Support(best,support.right(),support.front(),support.paired(),support.cantileverSide());
  }
  private static List<V> clipCap(List<V> polygon,int axis,double edge,boolean lower){
    var out=new ArrayList<V>();if(polygon.isEmpty())return out;
    V a=polygon.get(polygon.size()-1);double da=(axis==0?a.x():a.z())-edge;if(!lower)da=-da;
    for(V b:polygon){double db=(axis==0?b.x():b.z())-edge;if(!lower)db=-db;
      if((da>=0)!=(db>=0))out.add(a.add(b.sub(a).mul(da/(da-db))));
      if(db>=0)out.add(b);a=b;da=db;
    }return out;
  }
  /** Full-width ramp inner pavement has actually left the host pavement.
   * Parallel tapers must not claim their outer rail as a fork nose. */
  public static double separator(Mesh ramp,Mesh host){
    for(var at:ramp.samples()){
      if(at.halfWidth()<ramp.settings().width()/2-.15)continue;
      var q=RoadQueries.horizontal(host,at.center());var h=q.sample();
      if(Math.abs(at.center().y()-h.center().y())>3.5)continue;
      int inner=at.left().dot(h.center().sub(at.center()))>=0?1:-1;
      V edge=at.at(inner*at.halfWidth(),0);var near=RoadQueries.horizontal(host,edge);
      if(Math.abs(near.lateral())>=near.sample().halfWidth()+.06)return at.distance();
    }
    return Double.POSITIVE_INFINITY;
  }
  /** Backup seats on the exit-side approach rail, before the departure taper.
   * Only actual saved rail segments may support a pole; never invent a footing
   * over the ground/bridge void or wrap around a closed road. Nearest first. */
  public static List<SmartSigns.Support> approach(Mesh host,List<Part> parts,double station,int flow,int side,boolean paired){
    if(host.closed()||host.settings().structure()==Structure.TUNNEL||Math.abs(side)!=1)return List.of();
    var candidates=new ArrayList<Candidate>();int edge=side*flow;
    for(var part:parts){
      boolean paved=part.material().name().startsWith("WALK_")&&!part.pier()&&part.height()<=.5;
      boolean base=isBase(part)||part.material()==Material.CONCRETE&&!part.pier()
          &&Math.abs(part.width()-.36)<1e-5&&Math.abs(part.height()-.35)<1e-5;
      if(!base&&!isBeam(part)&&!paved)continue;
      V delta=part.b().sub(part.a());double length=delta.horizontalLength();if(length<.35)continue;
      // Leave room for the flange at both ends of an actual built segment.
      for(double along=.16;along<=length-.16+1e-6;along+=Math.max(.5,length/64)){
        double fraction=along/length;V on=part.a().add(delta.mul(fraction));
        if(paved){
          var projection=RoadQueries.horizontal(host,on);var sample=projection.sample();
          // Existing pavement outside the bicycle band is also a real footing.
          // Keep the pole on the road-side edge of the sidewalk, with room for
          // its flange, instead of requiring a rail which an underpass removed.
          double desired=edge*(sample.halfWidth()+.4),shift=desired-projection.lateral();
          shift=Math.max(-part.width()/2+.2,Math.min(part.width()/2-.2,shift));on=on.add(sample.left().mul(shift));
        }
        var q=RoadQueries.horizontal(host,on);var at=q.sample();double before=(station-at.distance())*flow;
        if(!atDeckHeight(on,at,at))continue;
        if(before<2||before>64||at.distance()<.5||at.distance()>host.length()-.5
            ||Math.abs(q.lateral()*edge-at.halfWidth())>(paved?1.25:.85)
            ||Math.abs(delta.horizontalUnit().dot(at.left().left()))<.9)continue;
        var top=part.top();double a=(top.get(0).y()+top.get(1).y())/2,b=(top.get(2).y()+top.get(3).y())/2;
        V foot=new V(on.x(),base||paved?a+(b-a)*fraction:at.center().y(),on.z()),right=at.left().mul(flow);
        if(paved&&!seated(new Part(foot,foot,.34,.1,true,Material.STEEL),part))continue;
        candidates.add(new Candidate(seat(new SmartSigns.Support(foot,right,right.left(),paired,-side),parts),before,base,on.x(),on.z(),part));
      }
    }
    candidates.sort(Comparator.comparingDouble(Candidate::station).thenComparing(c->!c.base())
        .thenComparingDouble(Candidate::x).thenComparingDouble(Candidate::z));
    var result=new ArrayList<>(candidates.stream().map(Candidate::support).distinct().toList());
    // A ground road may have neither rails nor a sidewalk. Its paved shoulder
    // is a real bearing too. Require the entire flange on intact pavement and
    // outside every live motor lane; the caller still checks all other roads,
    // cycle traffic, structures and the complete sign envelope.
    var motor=LaneDeck.motorOnly(host);
    for(double before=2;before<=64;before+=1){
      double d=station-flow*before;if(d<.5||d>host.length()-.5)continue;
      var at=RoadStructures.sample(host,d);V right=at.left().mul(flow),front=right.left();
      V center=at.at(edge*(at.halfWidth()-.26),0);var flange=new Part(center,center,.34,.1,true,Material.STEEL);
      boolean supported=true;double y=center.y();
      for(var corner:flange.base()){
        var q=RoadQueries.horizontal(host,corner);var surface=new V(corner.x(),q.sample().center().y(),corner.z());
        if(!RoadQueries.contains(host,surface,-.01,.01)||Math.abs(surface.y()-center.y())>.05){supported=false;break;}
        y=Math.max(y,surface.y());
      }
      if(!supported)continue;
      center=new V(center.x(),y,center.z());var mast=new Part(center,center,.30,7.87,true,Material.STEEL);
      if(RoadClearance.structureInvades(mast,motor,4.5))continue;
      result.add(new SmartSigns.Support(center.add(front.mul(.08)),right,front,paired,-side));
    }
    return List.copyOf(result);
  }
  /** Lateral distance from a clamped projection says nothing about its
   * longitudinal position. An endpoint rail is never an exit beyond the host. */
  public static boolean onPath(Mesh mesh,V point,double tolerance){
    if(mesh.closed())return true;var q=RoadQueries.horizontal(mesh,point);var s=q.sample();
    double along=point.sub(s.center()).dot(s.left().left().mul(-1));
    return !(s.distance()<1e-5&&along< -tolerance||s.distance()>mesh.length()-1e-5&&along>tolerance);
  }
  private ForkSignPlacement(){}
}
