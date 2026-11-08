package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** A single approach definition owns the stop, solid approach lines and far-side signal. */
public final class RoadSignals {
  public static final long PHASE_TICKS = 20 * 20;
  public static final double SOLID_APPROACH = 24;

  public record Approach(V inner, V outer, V post, V head, V forward, boolean ramp,
      double station, int direction, double crossingLength) {}

  public static boolean mainGreen(long gameTime) {
    return Math.floorMod(Math.floorDiv(gameTime, PHASE_TICKS), 2) == 0;
  }

  public static boolean signal(Part p) {
    return p.material() == Material.SIGNAL_MAIN || p.material() == Material.SIGNAL_RAMP || RoadJunctionSignalModel.supports(p);
  }

  public static List<Approach> approaches(Mesh road, List<Mesh> neighbors) {
    if (road.closed() || !RoadProfile.modern(road.settings().style())) return List.of();
    List<Approach> out = new ArrayList<>();
    boolean ramp = road.settings().style().ramp();
    for (var pad : neighbors) {
      if (pad.settings().style() != Style.UNMARKED) continue;
      if (ramp) {
        // Standard junction approaches own their stop line. Their outer end
        // meets the ramp end exactly; legacy crossing pads have no such seam.
        if(road.last().center().distance(pad.first().center())<.05
            &&road.last().left().dot(pad.first().left())>.95)continue;
        if (!RoadQueries.contains(pad, road.last().center(), .1, .1)) continue;
        for (double d = road.length() - .25; d > 1; d -= .25) {
          Sample at = RoadStructures.sample(road, d);
          if (!RoadQueries.contains(pad, at.center(), .1, .1)) {
            add(out, road, pad, RoadStructures.sample(road, d - .6), 1, true, neighbors);
            break;
          }
        }
      } else if (RoadProfile.catalog(road.settings().style()).twoWay()) {
        double entry = -1, exit = -1;
        // Use fixed world-space steps: render simplification must not move the stop lines.
        for (double d = 0; d <= road.length(); d += .25) {
          Sample at = RoadStructures.sample(road, d);
          if (!RoadQueries.contains(pad, at.center(), .1, .1)) continue;
          if (entry < 0) entry = d;
          exit = d;
        }
        if (entry > 1 && exit < road.length() - 1) {
          add(out, road, pad, RoadStructures.sample(road, entry - .85), 1, false, neighbors);
          add(out, road, pad, RoadStructures.sample(road, exit + .85), -1, false, neighbors);
        }
      }
    }
    return List.copyOf(out);
  }

  private static void add(List<Approach> out, Mesh road, Mesh pad, Sample at,
      int direction, boolean ramp, List<Mesh> neighbors) {
    var l = RoadProfile.layout(road, at);
    int side = l.outside() * direction;
    double inner = ramp ? l.outer(-side) + side * .1 : l.medianCenter() + side * (l.median() / 2 + .1);
    double outer = l.outer(side) - side * .1;
    V forward = at.left().left().mul(-direction);
    V middle = at.at((inner + outer) / 2, 0);
    double far = 0;
    for (double d = .5; d <= 160; d += .5) {
      if (RoadQueries.contains(pad, middle.add(forward.mul(d)), .2, .1)) far = d;
      else if (far > 0) break;
    }
    if (far == 0) return;
    // Keep the pole and rear housing mount on one far-side cross section.
    // Moving only the pole around the pad produced diagonal arms across the junction.
    V post = at.at(side * (at.halfWidth() - .28), 0).add(forward.mul(far + .92));
    V head = middle.add(forward.mul(far + .5)).add(new V(0, 7.35, 0));
    var approach = new Approach(at.at(inner, 0), at.at(outer, 0), post, head,
        forward, ramp, at.distance(), direction, far + 2);
    if (out.stream().noneMatch(a -> a.inner().distance(approach.inner()) < 2)) out.add(approach);
  }

  public static boolean solid(List<Approach> approaches, double station, double lateral) {
    for (var a : approaches) {
      double back = (a.station() - station) * a.direction();
      V left = a.forward().left().mul(a.direction());
      double lo = a.inner().sub(a.outer()).dot(left);
      // Sign of the controlled carriageway; one-way ramps control all dividers.
      if (back >= .2 && back <= SOLID_APPROACH
          && (a.ramp() || Math.signum(lateral) == -Math.signum(lo))) return true;
    }
    return false;
  }

  /** Clear every incoming lane up to the far side, including the full stop-line width. */
  public static List<List<V>> paintCuts(List<Approach> approaches) {
    List<List<V>> out = new ArrayList<>();
    for (var a : approaches) {
      V side = a.outer().sub(a.inner()).horizontalUnit();
      V inner = a.inner().sub(side.mul(.2)).sub(a.forward().mul(.25));
      V outer = a.outer().add(side.mul(.2)).sub(a.forward().mul(.25));
      V reach = a.forward().mul(a.crossingLength());
      out.add(List.of(inner, outer, outer.add(reach), inner.add(reach)));
    }
    return out;
  }

  public static boolean furnitureClear(V p, List<Approach> approaches) {
    for (var a : approaches) {
      double ahead = p.sub(a.inner()).dot(a.forward());
      double side = Math.abs(p.sub(a.inner()).dot(a.forward().left()));
      double end = a.crossingLength() - 1.5;
      for (var opposite : approaches) {
        if (opposite == a || opposite.ramp() || a.ramp()
            || opposite.forward().dot(a.forward()) > -.99) continue;
        double stop = opposite.inner().sub(a.inner()).dot(a.forward());
        if (stop > 0 && stop < a.crossingLength() + 2) end = Math.min(end, stop);
      }
      // The paired stop planes, rather than the signal-post margin, bound the median.
      if (Math.abs(p.y() - a.inner().y()) < .5 && ahead >= -.20
          && ahead <= end + .20 && side < a.inner().distance(a.outer()) + 2) return true;
      if (p.sub(a.post()).horizontalLength() < 4) return true;
    }
    return false;
  }

  /** The authored far-side pose, also used by geometry-only previews. */
  public static List<Part> structures(List<Approach> approaches) {
    var out=new ArrayList<Part>();
    for(var a:approaches)out.addAll(assembly(a,a.post(),a.head(),false));
    return List.copyOf(out);
  }

  /** Fit the actual base locally, keeping the pole and housing in one cross section.
   * Bearings are existing pavement, edge slabs or the original concrete rail foot;
   * this planner never adds paving or searches elsewhere around the junction. */
  public static List<Part> structures(List<Approach> approaches,List<Mesh> roads,Ground terrain){
    if(approaches.isEmpty())return List.of();
    var local=roads.stream().filter(m->approaches.stream().anyMatch(a->{
      var q=RoadQueries.horizontal(m,a.post());
      return q.horizontalDistance()<q.sample().halfWidth()+4&&Math.abs(q.sample().center().y()-a.post().y())<1;
    })).toList();
    var fixtures=new ArrayList<Part>();
    for(var mesh:local){
      // Keep coincident boundaries in this read-only obstacle set. The actual
      // planner chooses one owner; either owner has the same physical boundary.
      var join=new RoadRailJoin(mesh,roads.stream().filter(m->m!=mesh).map(m->new RoadRailJoin.Neighbor(m,false)).toList());
      var ground=new Ground(){
        public double top(double x,double z,double y){return terrain.top(x,z,y);}
        public boolean blocked(Part p){return terrain.blocked(p);}
        public boolean joined(V p){return false;}
        public boolean unionRails(){return true;}
        public boolean railBlocked(Part p,V a,V b){return terrain.railBlocked(p,a,b);}
        public List<RoadRailJoin.Span> railSpans(V a,V b,V out,double inset){return join.exposed(a,b,out,inset);}
        public V railJoint(V p,V d,boolean h,boolean raised){return join.joint(p,d,h,raised);}
        public boolean railPost(V p,boolean h,boolean raised){return join.ownsPost(p,h,raised);}
      };
      for(var part:RoadStructures.roadsideSupports(mesh,ground))if(approaches.stream().anyMatch(a->near(part,a.post(),4)))fixtures.add(part);
    }
    var motors=roads.stream().filter(m->m.settings().style()!=Style.UNMARKED).map(LaneDeck::motorOnly).toList();
    var out=new ArrayList<Part>();
    for(var a:approaches){
      V across=a.forward().left();if(a.post().sub(a.head()).dot(across)<0)across=across.mul(-1);
      List<Part> chosen=null;
      // A sub-metre move, when necessary, moves the whole assembly together.
      for(double advance:new double[]{0,-.25,.25,-.5,.5,-.75,.75}){
        V shift=a.forward().mul(advance),wanted=a.post().add(shift),head=a.head().add(shift);
        for(int step=0;step<=78&&chosen==null;step++){
          double lateral=step==0?0:(step%2==1?1:-1)*((step+1)/2)*.025;
          V p=wanted.add(across.mul(lateral));if(p.sub(a.post()).horizontalLength()>=1)continue;
          var parts=assembly(a,p,head,false);
          if(seated(parts.get(0),local,fixtures,terrain)&&clear(parts,roads,motors,fixtures,null))chosen=parts;
          if(chosen==null){
            // A normal ground road sits 1/8 m above the original terrain. A
            // roadside footing may start on that real ground while the housing
            // keeps its road-relative height. Do not lower into any road slab.
            double bottom=terrain.top(p.x(),p.z(),p.y()),drop=p.y()-bottom;
            if(Double.isFinite(bottom)&&drop>.025&&drop<=.34){
              var grounded=assembly(a,new V(p.x(),bottom,p.z()),head,false);
              if(seated(grounded.get(0),local,fixtures,terrain)&&clear(grounded,roads,motors,fixtures,null))chosen=grounded;
            }
          }
        }
        if(chosen!=null)break;
      }
      if(chosen==null){
        // At a tapered ramp end the outer fascia can be too narrow. Reuse the
        // existing concrete parapet bearing with a compact bolt plate. The mast
        // then joins the two steel rails, as a boundary post, outside the lanes.
        for(var bearing:fixtures){
          if(bearing.pier()||bearing.material()!=Material.CONCRETE||bearing.width()<.35||bearing.width()>.7)continue;
          V delta=bearing.b().sub(bearing.a());double denominator=delta.dot(a.forward());
          if(Math.abs(denominator)<1e-8)continue;
          double t=a.post().sub(bearing.a()).dot(a.forward())/denominator;if(t<0||t>1)continue;
          V center=bearing.a().add(delta.mul(t)).add(new V(0,bearing.height(),0));
          if(center.y()<a.post().y()+.3||center.y()>a.post().y()+1.21||center.sub(a.post()).horizontalLength()>=1)continue;
          for(double offset:new double[]{0,.02,-.02,.04,-.04}){
            V p=center.add(across.mul(offset));if(p.sub(a.post()).horizontalLength()>=1)continue;
            var parts=assembly(a,p,a.head(),true);
            if(parts.get(0).base().stream().allMatch(v->fixtures.stream().anyMatch(support->onTop(support,v)))&&clear(parts,roads,motors,fixtures,bearing)){chosen=parts;break;}
          }
          if(chosen!=null)break;
        }
      }
      if(chosen==null)throw new IllegalArgumentException(String.format(Locale.ROOT,"路口远侧信号灯缺少可用承托（%.0f, %.0f, %.0f）",a.post().x(),a.post().y(),a.post().z()));
      out.addAll(chosen);
    }
    return List.copyOf(out);
  }

  private static boolean near(Part part,V point,double margin){
    var base=part.base();return point.x()>=base.stream().mapToDouble(V::x).min().orElseThrow()-margin
        &&point.x()<=base.stream().mapToDouble(V::x).max().orElseThrow()+margin
        &&point.z()>=base.stream().mapToDouble(V::z).min().orElseThrow()-margin
        &&point.z()<=base.stream().mapToDouble(V::z).max().orElseThrow()+margin;
  }
  private static boolean seated(Part base,List<Mesh> roads,List<Part> fixtures,Ground terrain){
    var probes=new ArrayList<>(base.base());probes.add(base.a().add(base.b()).mul(.5));
    for(int i=0;i<4;i++)probes.add(base.base().get(i).add(base.base().get((i+1)%4)).mul(.5));
    for(var p:probes)if(roads.stream().noneMatch(m->RoadQueries.contains(m,p,-.005,.06))
        &&fixtures.stream().noneMatch(s->onTop(s,p))){double y=terrain.top(p.x(),p.z(),p.y());if(!Double.isFinite(y)||Math.abs(y-p.y())>.025)return false;}
    return true;
  }
  private static boolean onTop(Part part,V p){
    if(part.material()!=Material.CONCRETE||part.pier()||!near(part,p,.001))return false;
    var top=part.top();
    for(int i=1;i<top.size()-1;i++){
      V a=top.get(0),b=top.get(i),c=top.get(i+1),u=b.sub(a),v=c.sub(a),d=p.sub(a);
      double det=u.x()*v.z()-u.z()*v.x();if(Math.abs(det)<1e-10)continue;
      double s=(d.x()*v.z()-d.z()*v.x())/det,t=(u.x()*d.z()-u.z()*d.x())/det;
      if(s>=-1e-7&&t>=-1e-7&&s+t<=1+1e-7&&Math.abs(p.y()-a.y()-s*u.y()-t*v.y())<=.03)return true;
    }
    return false;
  }
  private static boolean clear(List<Part> parts,List<Mesh> roads,List<Mesh> motors,List<Part> fixtures,Part bearing){
    for(var p:parts){
      for(var m:motors)if(RoadClearance.structureInvades(p,m,4.25))return false;
      for(var m:roads)if(RoadClearance.structureInvades(p,m,0))return false;
      for(var obstacle:fixtures)if(RoadSolidOverlap.intersects(p,obstacle)){
        boolean railJoint=bearing!=null&&p.material()==Material.CB_POST
            &&(obstacle.material()==Material.STEEL||obstacle.material()==Material.DARK_STEEL)
            &&Math.max(obstacle.a().y(),obstacle.b().y())+obstacle.height()<=bearing.a().y()+1.25
            &&near(bearing,obstacle.a().add(obstacle.b()).mul(.5),.4);
        if(!railJoint)return false;
      }
    }
    return true;
  }
  private static List<Part> assembly(Approach a,V p,V head,boolean railSeat){
    var out=new ArrayList<Part>();V f=a.forward();double base=railSeat?.36:.46;
    double beamBottom=head.y()+.20;
    out.add(new Part(p.sub(f.mul(base/2)),p.add(f.mul(base/2)),base,railSeat?.14:.34,false,Material.CB_BASE,null,null,railSeat?"sr:signal_bolt_plate":"sr:signal_base"));
    double shaft=railSeat?.36:.38;
    out.add(new Part(p.sub(f.mul(shaft/2)),p.add(f.mul(shaft/2)),shaft,beamBottom-p.y()+.25,false,Material.CB_POST));
    V top=new V(p.x(),beamBottom,p.z()),mount=head.add(f.mul(.42));
    V armEnd=new V(mount.x(),beamBottom,mount.z());armEnd=armEnd.add(armEnd.sub(top).horizontalUnit());
    out.add(new Part(top,armEnd,.25,.25,false,Material.CB_ARM));
    V centered=head.add(new V(0,-.19690625,0));
    out.add(new Part(centered.add(f.mul(.5)),centered.sub(f.mul(.5)),1.9,.85,false,
        a.ramp()?Material.SIGNAL_RAMP:Material.SIGNAL_MAIN,null,null,"sr:centered_horizontal"));
    return List.copyOf(out);
  }

  public static List<RoadSurface.Face> lights(List<Part> heads, boolean mainGreen) {
    List<RoadSurface.Face> out = new ArrayList<>();
    for (var p : heads) {
      if (!signal(p)) continue;
      boolean green = p.material() == Material.SIGNAL_MAIN ? mainGreen : !mainGreen;
      out.addAll(RoadSignalModel.faces(p, true, green));
    }
    return List.copyOf(out);
  }

  private RoadSignals() {}
}
