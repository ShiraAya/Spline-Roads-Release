package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Material bands of a road. Temporary interior cuts are real gaps, not a thinner
 * bounding ribbon or a hidden stripe. The same strips feed rendering, collision,
 * clearance and ray selection. DETACH keeps its original outer-ribbon rules. */
public final class LaneDeck {
  public record Span(double low,double high,boolean lowWall,boolean highWall){}
  public record Strip(V al,V ar,V bl,V br,boolean lowWall,boolean highWall){}
  /** Visible and physical ground edge; the nominal ribbon still defines full-width lanes.
   * Retain rail footings, verges and fixed auxiliary mouths. Blend at structural/road ends. */
  public static double edge(Mesh mesh,Sample sample,int side){
    // The authored ribbon is the pavement contract used by auxiliary approaches.
    // Paint masks must never shrink that ribbon: doing so left an unpaved seam
    // while the joining ramp still met the original shoulder.
    return side*sample.halfWidth();
  }
  public static boolean hasOpenings(Mesh mesh){return !mesh.settings().options().streetscape().crossings().isEmpty()||mesh.settings().options().lanePoints().cuts().stream().anyMatch(LaneSections.Cut::temporary);}
  private static final WeakIdentityCache<Mesh,List<Integer>> SLOT_ORDER=new WeakIdentityCache<>(256,8192,List::size);
  private static List<Integer> slots(Mesh mesh){return SLOT_ORDER.get(mesh,LaneDeck::slotOrder);}
  private static List<Integer> slotOrder(Mesh mesh){
    var raw=LaneSections.reference(mesh);double d=(raw.first().distance()+raw.last().distance())/2;
    var out=new ArrayList<Integer>();for(var cut:mesh.settings().options().lanePoints().cuts())if(cut.temporary()&&!out.contains(cut.lane()))out.add(cut.lane());
    var at=RoadStructures.sample(raw,d);out.sort(Comparator.comparingDouble(slot->LanePoints.lane(raw,d,slot).position().sub(at.center()).dot(at.left())));return List.copyOf(out);
  }
  private static List<Span> holes(Mesh mesh,Sample sample){
    // Terminal faces use the interior cross-section. A rectangular cut is open
    // at its exact mathematical endpoint, but that must not restore a paper-thin
    // wall across a lane which remains closed right up to the road end.
    double probe=sample.distance();
    if(Math.abs(probe-mesh.first().distance())<1e-7)probe+=1e-5;
    if(Math.abs(probe-mesh.last().distance())<1e-7)probe-=1e-5;
    return holes(mesh,sample,probe);
  }
  private static List<Span> holes(Mesh mesh,Sample sample,double interval){
    return holes(mesh,sample,interval,true);
  }
  private static List<Span> holes(Mesh mesh,Sample sample,double interval,boolean footings){
    var raw=LaneSections.reference(mesh);var holes=new ArrayList<Span>();
    for(int slot:slots(mesh)){
      double removed=0;for(var cut:mesh.settings().options().lanePoints().cuts())if(cut.temporary()&&cut.lane()==slot)removed=Math.max(removed,cut.removed(cut.rectangular()||cut.arrival()&&Math.abs(sample.distance()-cut.end())<1e-7?interval:sample.distance()));
      var lane=LanePoints.lane(raw,sample.distance(),slot);
      double center=lane.position().sub(sample.center()).dot(sample.left()),half=lane.width()*removed*(LaneAdditions.isAdded(raw,slot)?LaneAdditions.find(raw,slot).fraction(sample.distance()):1)/2;
      double lo=Math.max(-sample.halfWidth(),Math.min(sample.halfWidth(),center-half));
      double hi=Math.max(lo,Math.min(sample.halfWidth(),center+half));
      boolean rectangular=mesh.settings().options().lanePoints().cuts().stream().anyMatch(c->c.lane()==slot&&c.rectangular()&&c.removed(interval)>.999);
      if(rectangular&&half>1e-7){
        boolean lowOuter=true,highOuter=true;
        for(int other:LaneAdditions.slots(raw,sample.distance()))if(other!=slot){
          if(LaneAdditions.isAdded(raw,other)&&LaneAdditions.find(raw,other).fraction(sample.distance())<1e-7||LaneAdditions.permanentRemoval(raw,sample.distance(),other)>.999999)continue;
          double x=LanePoints.lane(raw,sample.distance(),other).position().sub(sample.center()).dot(sample.left());
          if(x<center-1e-6)lowOuter=false;if(x>center+1e-6)highOuter=false;
        }
        // Remove only the redundant shoulder. A separate cycle lane still needs
        // its deck and outer protection when the adjacent motor lane closes.
        var layout=RoadProfile.layout(raw,sample);
        // Seat the rail and its widest 0.62-block footing inside the closed slot.
        // Its inward inset must not consume any of the neighboring driving lane.
        boolean access=mesh.settings().options().lanePoints().access().stream().anyMatch(r->r.cuts().stream().anyMatch(c->c.lane()==slot&&c.removed(interval)>.999));
        double shoulder=footings&&!access?Math.min(2*RoadRailJoin.INSET,lane.width()*.25):0;
        // An added outer lane begins beyond the OLD motor edge. While that lane
        // is closed, retain the existing shoulder all the way to its mouth. A
        // generic .68 m footing narrowed a one-metre verge by .32 m, then abruptly
        // restored it at the arrival. Original/internal reservations still use
        // their own ledges, never steal a neighboring motor lane.
        if(footings&&LaneAdditions.isAdded(raw,slot)){
          var original=RoadStructures.sample(raw,sample.distance());var originalLayout=RoadProfile.layout(raw,original);
          boolean rail=mesh.settings().structure()==Structure.BRIDGE||RoadStreetscape.raised(mesh,sample,center<0?-1:1)
              ||mesh.settings().options().outerRail()==RoadProfile.OuterRail.ON||RoadProfile.highway(mesh.settings().style());
          shoulder=rail?shoulder:0;
          if(highOuter)shoulder=Math.max(shoulder,edge(raw,original,1)-originalLayout.motorMax());
          if(lowOuter)shoulder=Math.max(shoulder,originalLayout.motorMin()-edge(raw,original,-1));
        }
        boolean openLow=lowOuter&&(layout.cycleWidth()<.01||!layout.catalog().twoWay()&&layout.outside()>0);
        boolean openHigh=highOuter&&(layout.cycleWidth()<.01||!layout.catalog().twoWay()&&layout.outside()<0);
        if(openLow)lo=-sample.halfWidth();
        if(openHigh)hi=sample.halfWidth();
        if(!openLow)lo=Math.min(hi,lo+shoulder);
        if(!openHigh)hi=Math.max(lo,hi-shoulder);
      }
      holes.add(new Span(lo,hi,false,false));
    }
    // Access gaps use the authored host axis. A one-sided auxiliary lane
    // shifts the physical ribbon centre; transform its median hole just as
    // the selected lane holes above, without moving the saved reservation.
    double gapShift=RoadStructures.sample(raw,sample.distance()).center().sub(sample.center()).dot(sample.left());
    for(var access:mesh.settings().options().lanePoints().access())for(var gap:access.gaps())
      holes.add(gap.at(interval)?new Span(gap.low()+gapShift,gap.high()+gapShift,false,false):new Span(gap.low()+gapShift,gap.low()+gapShift,false,false));
    for(int side:new int[]{-1,1})if(RoadsideBypass.crossing(mesh,interval,side)){
      double inner=RoadsideBypass.inner(mesh,sample,side),outer=RoadsideBypass.outer(mesh,sample,side);
      holes.add(new Span(Math.min(side*inner,side*outer),Math.max(side*inner,side*outer),false,false));
    }
    holes.sort(Comparator.comparingDouble(Span::low));
    return holes;
  }
  /** A physically reserved outer slot is not an intact outer road boundary. */
  public static boolean outerOpening(Mesh mesh,double station,int side){
    var sample=RoadStructures.sample(mesh,station);
    return !present(mesh,sample,edge(mesh,sample,side)-side*.025,0);
  }
  public static List<Span> spans(Mesh mesh,Sample sample){
    double probe=sample.distance();
    if(Math.abs(probe-mesh.first().distance())<1e-7)probe+=1e-5;
    if(Math.abs(probe-mesh.last().distance())<1e-7)probe-=1e-5;
    return spans(mesh,sample,probe);
  }
  private static List<Span> spans(Mesh mesh,Sample sample,double interval){
    return spans(mesh,sample,interval,true);
  }
  private static List<Span> spans(Mesh mesh,Sample sample,double interval,boolean footings){
    double left=edge(mesh,sample,-1),right=edge(mesh,sample,1);
    if(!hasOpenings(mesh))return List.of(new Span(left,right,true,true));
    var out=new ArrayList<Span>();double low=left;boolean wall=true;
    for(var hole:holes(mesh,sample,interval,footings)){
      double high=Math.min(right,Math.max(low,hole.low()));boolean open=hole.high()-hole.low()>1e-7;
      out.add(new Span(low,high,wall,open));low=Math.min(right,Math.max(low,hole.high()));wall=open;
    }
    out.add(new Span(low,right,wall,true));return out;
  }
  public static List<Strip> strips(Mesh mesh,Sample a,Sample b){
    return strips(mesh,a,b,true);
  }
  /** Closed-slot rail footings remain physical material, not vehicle lanes.
   * Preserve the exact closure interval: restored downstream lanes stay protected. */
  public static List<Strip> drivingStrips(Mesh mesh,Sample a,Sample b){return strips(mesh,a,b,false);}
  private static List<Strip> strips(Mesh mesh,Sample a,Sample b,boolean footings){
    var patch=RoadSlicing.patch(mesh,a,b);if(patch!=null)return patch;
    double probe=(a.distance()+b.distance())/2;
    var aa=spans(mesh,a,probe,footings);var bb=spans(mesh,b,probe,footings);var result=new ArrayList<Strip>();
    for(int i=0;i<aa.size();i++){
      var x=aa.get(i);var y=bb.get(i);if(Math.max(x.high()-x.low(),y.high()-y.low())<1e-8)continue;
      // A dormant cut has zero width on both ends. Its two pavement bands
      // are a single continuous strip, including when a saved street is sliced.
      double lowA=x.low(),lowB=y.low();
      boolean lowWall=x.lowWall()||y.lowWall();
      if(!result.isEmpty()&&!lowWall){
        var previous=result.get(result.size()-1);
        if(!previous.highWall()&&previous.al().distance(a.at(lowA,0))<1e-8&&previous.bl().distance(b.at(lowB,0))<1e-8){
          result.set(result.size()-1,new Strip(a.at(x.high(),0),previous.ar(),b.at(y.high(),0),previous.br(),previous.lowWall(),x.highWall()||y.highWall()));
          continue;
        }
      }
      result.add(new Strip(a.at(x.high(),0),a.at(lowA,0),b.at(y.high(),0),b.at(lowB,0),lowWall,x.highWall()||y.highWall()));
    }return result;
  }
  public static List<List<V>> holeQuads(Mesh mesh,Sample a,Sample b){
    double probe=(a.distance()+b.distance())/2;var aa=holes(mesh,a,probe);var bb=holes(mesh,b,probe);var out=new ArrayList<List<V>>();
    for(int i=0;i<aa.size();i++){var x=aa.get(i);var y=bb.get(i);
      if(Math.max(x.high()-x.low(),y.high()-y.low())>1e-8)out.add(List.of(a.at(x.high(),0),a.at(x.low(),0),b.at(y.low(),0),b.at(y.high(),0)));
    }return out;
  }
  public record Cap(V a,V b){}
  /** Cut endpoints are one-sided cross sections. Close the transverse slab faces,
   * but do not add a wall where two adjacent closed intervals continue each other. */
  public static List<Cap> caps(Mesh mesh){
    var stations=new TreeSet<Double>();
    for(var cut:mesh.settings().options().lanePoints().cuts())if(cut.rectangular()){
      stations.add(cut.begin());stations.add(cut.end());
    }
    var result=new ArrayList<Cap>();
    for(double d:stations){
      if(d<=mesh.first().distance()+1e-7||d>=mesh.last().distance()-1e-7)continue;
      var sample=RoadStructures.sample(mesh,d);
      var before=holes(mesh,sample,d-1e-5);var after=holes(mesh,sample,d+1e-5);
      var edges=new TreeSet<Double>();for(var h:before){edges.add(h.low());edges.add(h.high());}for(var h:after){edges.add(h.low());edges.add(h.high());}
      var boundaries=new ArrayList<>(edges);Double from=null;boolean opening=false;
      for(int i=1;i<boundaries.size();i++){
        double lo=boundaries.get(i-1),hi=boundaries.get(i),middle=(lo+hi)/2;
        boolean was=before.stream().anyMatch(h->middle>h.low()&&middle<h.high());
        boolean now=after.stream().anyMatch(h->middle>h.low()&&middle<h.high());
        if(from!=null&&(was==now||now!=opening)){V low=sample.at(from,0),high=sample.at(lo,0);result.add(new Cap(opening?low:high,opening?high:low));from=null;}
        if(was!=now&&from==null){from=lo;opening=now;}
      }
      if(from!=null){V low=sample.at(from,0),high=sample.at(boundaries.get(boundaries.size()-1),0);result.add(new Cap(opening?low:high,opening?high:low));}
    }
    return List.copyOf(result);
  }
  public static boolean present(Mesh mesh,Sample sample,double lateral,double margin){
    if(lateral<edge(mesh,sample,-1)-margin-1e-8||lateral>edge(mesh,sample,1)+margin+1e-8)return false;
    if(!hasOpenings(mesh))return true;
    for(var hole:holes(mesh,sample))if(hole.high()-hole.low()>1e-7&&lateral>hole.low()+Math.max(0,margin)+1e-8&&lateral<hole.high()-Math.max(0,margin)-1e-8)return false;
    return true;
  }
  /** Raster batches retain authored longitudinal stations and original slot axes.
   * RoadRibbon.split rebases stations and would reopen/close the wrong part of a long road. */
  public static List<Mesh> rasterPieces(Mesh mesh,double length){
    // Clipped two-station polygons already carry bounds; triangle tips are not road cross-sections.
    if(mesh.slicing()!=null&&mesh.samples().size()==2)return List.of(mesh);
    if(RoadProfile.catalog(mesh.settings()).type()!=RoadProfile.Type.ORDINARY&&mesh.slicing()==null&&!hasOpenings(mesh)&&mesh.reference()==null&&mesh.settings().options().lanePoints().additions().isEmpty())return mesh.length()<=256?List.of(mesh):RoadRibbon.split(mesh,length);
    var out=new ArrayList<Mesh>();var points=mesh.samples();int start=0;
    for(int i=1;i<points.size();i++)if(points.get(i).distance()-points.get(start).distance()>=length||i==points.size()-1){
      var subset=List.copyOf(points.subList(start,i+1));
      var bounds=RoadRibbon.mesh(subset,mesh.settings());
      out.add(new Mesh(subset,mesh.settings(),bounds.min(),bounds.max(),mesh.length(),false,null,mesh.controls(),LaneSections.reference(mesh),mesh.slicing()));start=i;
    }return out;
  }
  /** Motor bands and the median, not shoulders which form part of the joining mouth. */
  public static Mesh motorOnly(Mesh host){
    var samples=new ArrayList<Sample>();
    for(var sample:host.samples()){
      var l=RoadProfile.layout(host,sample);samples.add(new Sample(sample.at(l.motorCenter(),0),sample.left(),sample.distance(),(l.motorMax()-l.motorMin())/2));
    }
    return new Mesh(List.copyOf(samples),host.settings(),host.min(),host.max(),host.length(),host.closed(),host.controlPoint(),host.controls(),LaneSections.reference(host));
  }
  public static Mesh excludingSlot(Mesh host,int slot){
    var cuts=new ArrayList<>(host.settings().options().lanePoints().cuts());
    // A clearance mask removes this selected motor slot entirely. Its physical
    // closure ledge must not be reintroduced into the protected neighboring lanes.
    cuts.removeIf(c->c.temporary()&&c.lane()==slot);
    cuts.add(new LaneSections.Cut(new UUID(0,slot+1),slot,1,-1024,host.length()+1024,2,null,true));
    var settings=host.settings().options(host.settings().options().lanePoints(host.settings().options().lanePoints().cuts(cuts)));
    return new Mesh(host.samples(),settings,host.min(),host.max(),host.length(),host.closed(),host.controlPoint(),host.controls(),LaneSections.reference(host));
  }
  private LaneDeck(){}
}
