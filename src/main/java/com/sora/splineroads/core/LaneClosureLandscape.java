package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** Q2-16/17: replace a closed ground lane with actual planting solids, never a
 * drivable painted island. Elevated slots remain the rectangular LaneDeck void.
 * Classification uses ORIGINAL terrain at the selected lane, not absolute world Y
 * or a majority vote over the unrelated half of a wide road. */
public final class LaneClosureLandscape {
  public static boolean raised(Mesh mesh,int slot,double station,Ground ground){
    if(mesh.settings().structure()==Structure.BRIDGE)return true;
    if(mesh.settings().structure()==Structure.GROUND)return false;
    if(mesh.settings().structure()==Structure.TUNNEL)return true; // no tunnel vegetation
    var raw=LaneSections.reference(mesh);var center=LanePoints.lane(raw,station,slot);
    double threshold=RoadStructures.GROUND_CONTACT;int unsupported=0;
    for(double along:new double[]{-.65,0,.65}){
      var at=RoadStructures.sample(raw,Math.max(raw.first().distance(),Math.min(raw.last().distance(),station+along)));
      var lane=LanePoints.lane(raw,at.distance(),slot);
      for(double across:new double[]{-.35,0,.35}){
        V p=lane.position().add(at.left().mul(across*center.width()));
        double top=ground.top(p.x(),p.z(),p.y());
        if(!Double.isFinite(top)||p.y()-top>threshold)unsupported++;
      }
    }
    // A one-block construction spine or a missing center block cannot classify the
    // whole lane by itself. Unknown/water columns fail closed to a non-floating gap.
    return unsupported>=5;
  }
  public static final double MIN_GREEN_LENGTH=12;
  public static boolean paved(Part part){return RoadsideBypass.paved(part)||part.material()==Material.CONCRETE&&(part.model().equals("sr:closed_lane_pavement")||part.model().equals("sr:tunnel_apron"));}
  public static List<Part> plan(Mesh mesh,Ground ground){
    if(mesh.settings().structure()==Structure.TUNNEL)return List.of();
    var raw=LaneSections.reference(mesh);var result=new ArrayList<Part>();
    var intervals=closedIntervals(mesh,ground);
    var eligible=new ArrayList<Interval>();
    for(var cut:intervals){
      var lane=List.of(cut.lane());double start=Double.NaN;
      for(double a=cut.begin();a<cut.end()-1e-7;a+=1){
        double b=Math.min(a+1,cut.end());
        boolean clear=!raised(mesh,cut.lane(),(a+b)/2,ground)&&bed(raw,lane,a,b,1,1).stream().noneMatch(ground::blocked);
        if(clear&&Double.isNaN(start))start=a;
        if((!clear||b>=cut.end())&&Double.isFinite(start)){
          double end=clear?b:a;
          if(end-start>=MIN_GREEN_LENGTH)eligible.add(new Interval(cut.lane(),start,end));
          start=Double.NaN;
        }
      }
    }
    var planted=new ArrayList<Interval>();
    for(var band:bands(raw,eligible))plant(raw,band,ground,result,planted);
    for(var band:bands(raw,intervals)){
      for(double a=band.from();a<band.to()-1e-7;a+=1){
        double b=Math.min(a+1,band.to()),station=(a+b)/2;
        var onGround=band.slots().stream().filter(slot->!raised(mesh,slot,station,ground)).toList();
        for(var slots:adjacent(raw,onGround,station)){
          boolean green=planted.stream().anyMatch(i->slots.contains(i.lane())&&station>=i.begin()&&station<i.end());
          var first=cross(raw,slots,a);var last=cross(raw,slots,b);
          double depth=Math.max(.5,raw.settings().thickness()+.125);
          var pads=new ArrayList<Part>();
          RoadStructures.plantingLayer(pads,first.center().add(new V(0,-depth,0)),last.center().add(new V(0,-depth,0)),
              first.left(),last.left(),first.width(),last.width(),depth+(green?.02:0),Material.CONCRETE,green?"":"sr:closed_lane_pavement");
          for(var pad:pads)addUnblocked(result,pad,ground,0);
        }
      }
    }
    return List.copyOf(result);
  }
  private record Band(List<Integer> slots,double from,double to){}
  private record Cross(V center,V left,double width){}
  private static Cross cross(Mesh raw,List<Integer> slots,double station){
    var at=RoadStructures.sample(raw,station);
    double lo=Double.POSITIVE_INFINITY,hi=Double.NEGATIVE_INFINITY;
    for(int slot:slots){
      var lane=LanePoints.lane(raw,station,slot);
      double x=lane.position().sub(at.center()).dot(at.left());
      lo=Math.min(lo,x-lane.width()/2);hi=Math.max(hi,x+lane.width()/2);
    }
    return new Cross(at.at((lo+hi)/2,0),at.left(),hi-lo);
  }
  private static List<List<Integer>> adjacent(Mesh raw,List<Integer> slots,double d){
    var at=RoadStructures.sample(raw,d);var layout=RoadProfile.layout(raw,at);
    var sorted=new ArrayList<>(slots);
    sorted.sort(Comparator.comparingDouble(slot->LanePoints.lane(raw,d,slot).position().sub(at.center()).dot(at.left())));
    var groups=new ArrayList<List<Integer>>();List<Integer> group=null;double previous=Double.NEGATIVE_INFINITY;
    for(int slot:sorted){
      var lane=LanePoints.lane(raw,d,slot);double x=lane.position().sub(at.center()).dot(at.left()),low=x-lane.width()/2;
      boolean median=Math.abs(previous-layout.medianEdge(-1))<.05&&Math.abs(low-layout.medianEdge(1))<.05;
      if(group==null||low>previous+.05&&!median){group=new ArrayList<>();groups.add(group);}
      group.add(slot);previous=x+lane.width()/2;
    }
    return groups;
  }
  private static List<Band> bands(Mesh raw,List<Interval> intervals){
    var edges=new TreeSet<Double>();for(var i:intervals){edges.add(i.begin());edges.add(i.end());}
    var ordered=new ArrayList<>(edges);var result=new ArrayList<Band>();
    var lastIndex=new HashMap<List<Integer>,Integer>();
    for(int k=1;k<ordered.size();k++){
      double a=ordered.get(k-1),b=ordered.get(k),mid=(a+b)/2;
      var active=intervals.stream().filter(i->mid>=i.begin()&&mid<i.end()).map(Interval::lane).distinct().toList();
      for(var slots:adjacent(raw,active,mid)){
        Integer n=lastIndex.get(slots);var last=n==null?null:result.get(n);
        if(last!=null&&last.to()==a)result.set(n,new Band(slots,last.from(),b));
        else {lastIndex.put(slots,result.size());result.add(new Band(slots,a,b));}
      }
    }
    return result;
  }
  private static void plant(Mesh raw,Band band,Ground ground,List<Part> out,List<Interval> planted){
    double from=band.from(),to=band.to();
    if(to-from<=.4)return;
    var parts=new ArrayList<Part>();
    for(double a=from+.2;a<to-.2-1e-7;a+=1){
      double b=Math.min(to-.2,a+1);
      parts.addAll(bed(raw,band.slots(),a,b,taper(a,from,to),taper(b,from,to)));
    }
    for(double d:new double[]{from+.1,to-.1}){
      var section=cross(raw,band.slots(),d);double depth=Math.max(.5,raw.settings().thickness()+.125);
      var center=section.center().add(new V(0,-depth,0));var edge=section.left().mul(Math.max(.08,(section.width()-.12)*taper(d<from+1?from+.2:to-.2,from,to)/2));
      parts.add(new Part(center.sub(edge),center.add(edge),.2,depth+.35,false,Material.CONCRETE));
    }
    if(parts.stream().noneMatch(ground::blocked)){
      out.addAll(parts);for(int slot:band.slots())planted.add(new Interval(slot,from,to));
    }else if(band.slots().size()>1){
      for(int slot:band.slots())plant(raw,new Band(List.of(slot),from,to),ground,out,planted);
    }
  }
  private static double taper(double d,double from,double to){return .2+.8*Settings.smooth(Math.min(1,Math.min(d-from,to-d)/2));}
  private static List<Part> bed(Mesh raw,List<Integer> slots,double a,double b,double wa,double wb){
    var first=cross(raw,slots,a);var last=cross(raw,slots,b);
    return RoadStructures.planting(first.center(),last.center(),first.left(),last.left(),
        (first.width()-.12)*wa,(last.width()-.12)*wb,1,Math.max(.5,raw.settings().thickness()+.125));
  }
  private static boolean connectorAddition(Mesh mesh,LaneSections.Cut cut){
    return cut.lane()>=8&&mesh.settings().options().lanePoints().additions().stream().anyMatch(a->a.slot()==cut.lane()&&a.connection().equals(cut.connection()));
  }
  private record Interval(int lane,double begin,double end){}
  private static List<Interval> closedIntervals(Mesh mesh,Ground ground){
    var raw=LaneSections.reference(mesh);var intervals=new ArrayList<Interval>();
    for(var cut:mesh.settings().options().lanePoints().cuts())if(cut.temporary()&&cut.rectangular()&&!cut.underpass()&&!connectorAddition(mesh,cut)&&ground.closedLanePlanting(cut.connection())){
      double a=Math.max(raw.first().distance(),Math.min(cut.begin(),cut.end()));
      double b=Math.min(raw.last().distance(),Math.max(cut.begin(),cut.end()));
      if(b>a+1e-7)intervals.add(new Interval(cut.lane(),a,b));
    }
    intervals.sort(Comparator.comparingInt(Interval::lane).thenComparingDouble(Interval::begin));
    var merged=new ArrayList<Interval>();
    for(var next:intervals){
      if(!merged.isEmpty()){
        var last=merged.get(merged.size()-1);
        if(last.lane()==next.lane()&&next.begin()<=last.end()+1e-7){
          merged.set(merged.size()-1,new Interval(last.lane(),last.begin(),Math.max(last.end(),next.end())));continue;
        }
      }
      merged.add(next);
    }
    return merged;
  }
  /** Clip only the low unplanted foundation. Vegetation is accepted as a complete
   * run above; subdividing its individual layers would expose cut soil faces. */
  private static void addUnblocked(List<Part> out,Part part,Ground ground,int depth){
    if(!ground.blocked(part)){out.add(part);return;}
    if(depth>=4||part.a().sub(part.b()).horizontalLength()<.125)return;
    // Unframed end kerbs use Part.base()'s implicit lateral frame. Do not dereference null,
    // and preserve this same prism when subdivision makes the frames explicit.
    V side=part.pier()||part.b().sub(part.a()).horizontalLength()<1e-8?new V(part.width()/2,0,0):part.b().sub(part.a()).horizontalUnit().left().mul(part.width()/2);
    V first=part.frameA()==null?side:part.frameA(),last=part.frameB()==null?side:part.frameB();
    V mid=part.a().add(part.b()).mul(.5),frame=first.add(last).mul(.5);
    addUnblocked(out,new Part(part.a(),mid,part.width(),part.height(),part.pier(),part.material(),first,frame,part.model()),ground,depth+1);
    addUnblocked(out,new Part(mid,part.b(),part.width(),part.height(),part.pier(),part.material(),frame,last,part.model()),ground,depth+1);
  }
  private LaneClosureLandscape(){}
}
