package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;

/** Intact shafts and capitals: uneven terrain never slices a pier into thin strips. */
public final class RoadSupports {
  public static final double MAX_PORTAL_SPAN=48, MAX_PORTAL_OUTREACH=12;
  private static double foundation(V center,double top,double width,Ground ground){
    return ground.foundation(center,top,width);
  }
  public static boolean shaft(List<Part> out,V center,double top,double width,Ground ground){
    double floor=foundation(center,top,width+.4,ground);
    if(!Double.isFinite(floor))return false;
    double height=top-floor;
    if(height<.4)return true;
    if(height>RoadStructures.MAX_DROP)return false;
    post(out,center,floor,width,height);
    // A low plinth and a stepped capital give the shaft a deliberate silhouette.
    post(out,center,floor,width+.4,Math.min(.35,height));
    if(height>2)for(int i=0;i<3;i++)post(out,center,top-.6+i*.2,width+.25*(i+1),.2);
    return true;
  }
  private static void post(List<Part> out,V c,double y,double width,double height){
    V p=new V(c.x(),y,c.z());out.add(new Part(p,p,width,height,true,Material.CONCRETE));
  }
  /** A lower road excavates its original terrain. In a permitted median the
   * actual ribbon is the bearing, including the low corner of a graded deck.
   * No vehicle lane, tunnel, open slot or junction may become a foundation. */
  public static double medianFoundation(V center,double top,double width,List<Mesh> roads){
    V position=new V(center.x(),top,center.z());
    var foot=new Part(position,position,width,.35,true,Material.CONCRETE);
    double highest=Double.NEGATIVE_INFINITY;
    for(var road:roads){
      if(road.settings().structure()==Structure.TUNNEL||!RoadStructures.fitsMedian(foot,road,roads))continue;
      double floor=Double.POSITIVE_INFINITY,ceiling=Double.NEGATIVE_INFINITY;boolean present=true;
      for(double dx:new double[]{-.5,0,.5})for(double dz:new double[]{-.5,0,.5}){
        var q=RoadQueries.horizontal(road,center.add(new V(dx*width,0,dz*width)));var s=q.sample();
        if(!LaneDeck.present(road,s,q.lateral(),0)||road.settings().options().streetscape().tunnels().stream().anyMatch(span->s.distance()>=span.from()&&s.distance()<=span.to())){present=false;break;}
        floor=Math.min(floor,s.center().y());ceiling=Math.max(ceiling,s.center().y());
      }
      if(present&&ceiling<top-.4&&top-floor<=RoadStructures.MAX_DROP)highest=Math.max(highest,floor);
    }
    return Double.isFinite(highest)?highest:Double.NaN;
  }
  public static List<Part> standard(Sample s,double thickness,Ground ground){
    double underside=s.center().y()-thickness,cap=.8,reach=Math.min(s.halfWidth(),Math.max(1.4,s.halfWidth()*.78));
    var out=new ArrayList<Part>();
    double floor=foundation(s.center(),underside,1.5,ground);
    if(Double.isFinite(floor)&&underside-floor<2)return List.of();
    // Keep the narrow median-compatible shaft. The cap spreads its load under the deck.
    if(!shaft(out,s.center(),underside-cap,1.5,ground)||out.isEmpty())return List.of();
    out.add(new Part(s.at(-reach,thickness+cap),s.at(reach,thickness+cap),2.1,cap,false,Material.CONCRETE));
    return List.copyOf(out);
  }
  /** Each surviving strip carries its own support; a cap may not bridge a cut slot.
   * Intersect the footprint across the cap's longitudinal width as well. */
  public static List<Sample> bearing(Mesh mesh,double station,double halfSpan){
    var at=RoadStructures.sample(mesh,station);var bands=new ArrayList<LaneDeck.Span>(LaneDeck.spans(mesh,at));
    var probes=new TreeSet<Double>();probes.add(Math.max(0,station-halfSpan));probes.add(Math.min(mesh.length(),station+halfSpan));
    for(var c:mesh.settings().options().lanePoints().cuts())for(double edge:new double[]{c.begin(),c.end()})
      if(edge>station-halfSpan&&edge<station+halfSpan){probes.add(Math.max(0,edge-1e-5));probes.add(Math.min(mesh.length(),edge+1e-5));}
    for(double d:probes){
      var s=RoadStructures.sample(mesh,d);var next=new ArrayList<LaneDeck.Span>();
      for(var x:bands)for(var y:LaneDeck.spans(mesh,s)){
        double lo=Math.max(x.low(),s.at(y.low(),0).sub(at.center()).dot(at.left()));
        double hi=Math.min(x.high(),s.at(y.high(),0).sub(at.center()).dot(at.left()));
        if(hi-lo>=2.4)next.add(new LaneDeck.Span(lo,hi,true,true));
      }bands=next;
    }
    return bands.stream().filter(b->b.high()-b.low()>=2.4).map(b->new Sample(at.at((b.low()+b.high())/2,0),at.left(),station,(b.high()-b.low())/2)).toList();
  }
  public static List<Part> clearStandard(Mesh mesh,double station,Ground ground){
    if(!LaneDeck.hasOpenings(mesh))return clearStandard(RoadStructures.sample(mesh,station),mesh.settings().thickness(),ground);
    var out=new ArrayList<Part>();
    for(var sample:bearing(mesh,station,1.125))out.addAll(clearStandard(sample,mesh.settings().thickness(),ground));
    return List.copyOf(out);
  }
  public static List<Part> ramp(Sample s,double thickness,Ground ground){
    var out=new ArrayList<Part>();double top=s.center().y()-thickness;
    double floor=foundation(s.center(),top,1.5,ground);
    if(!Double.isFinite(floor)||top-floor<2||top-floor>RoadStructures.MAX_DROP)return List.of();
    V foot=new V(s.center().x(),floor,s.center().z());
    var shaft=new Part(foot,foot,1.5,top-floor,true,Material.CONCRETE);
    if(ground.blocked(shaft))return List.of();out.add(shaft);return List.copyOf(out);
  }
  public static List<Part> ramp(Mesh mesh,double station,Ground ground){
    return ramp(mesh,station,0,ground);
  }
  /** A bounded lateral single shaft; the entire footprint must remain below an intact deck strip. */
  public static List<Part> ramp(Mesh mesh,double station,double lateral,Ground ground){
    var axis=RoadStructures.sample(mesh,station);
    double radius=.75*(Math.abs(axis.left().x())+Math.abs(axis.left().z()))+.04;
    boolean bearing=false;
    for(var band:bearing(mesh,station,1.12)){
      double center=band.center().sub(axis.center()).dot(axis.left());
      if(lateral-radius>=center-band.halfWidth()&&lateral+radius<=center+band.halfWidth()){bearing=true;break;}
    }
    if(!bearing)return List.of();
    var s=new Sample(axis.at(lateral,0),axis.left(),station,axis.halfWidth());double thickness=mesh.settings().thickness();
    var before=RoadStructures.sample(mesh,Math.max(0,station-.75));
    var after=RoadStructures.sample(mesh,Math.min(mesh.length(),station+.75));
    V delta=after.center().sub(before.center());double grade=delta.y()/Math.max(1e-6,delta.horizontalLength());
    V tangent=delta.horizontalUnit();double gx=grade*tangent.x(),gz=grade*tangent.z();
    double top=s.center().y()-thickness-.006;
    double floor=foundation(s.center(),top,1.5,ground);
    if(!Double.isFinite(floor)||top-floor<2||top-floor>RoadStructures.MAX_DROP)return List.of();
    // Bury the sloped bottom entirely below the foundation; all four top corners
    // follow the deck underside instead of ending at the lowest sampled height.
    double height=top-floor+.75*(Math.abs(gx)+Math.abs(gz))+.02;
    V a=new V(s.center().x(),top-height-.75*gz,s.center().z());
    V b=new V(s.center().x(),top-height+.75*gz,s.center().z());
    V frame=new V(.75,.75*gx,0);
    var shaft=new Part(a,b,1.5,height,true,Material.CONCRETE).frames(frame,frame);
    return ground.blocked(shaft)?List.of():List.of(shaft);
  }
  public static List<Part> portal(Sample s,double thickness,double depth,double offset,double width,Ground ground){
    double top=s.center().y()-thickness-depth;
    var out=new ArrayList<Part>();double[] feet=new double[2];int i=0;
    for(int side:new int[]{-1,1}){
      boolean found=false;
      for(double reach=offset;reach<=Math.min(offset+MAX_PORTAL_OUTREACH,MAX_PORTAL_SPAN-width-.75-offset);reach+=.5){
        var shaft=new ArrayList<Part>();
        if(!shaft(shaft,s.at(side*reach,0),top,width,ground)||shaft.isEmpty())continue;
        if(shaft.stream().anyMatch(ground::blocked))continue;
        out.addAll(shaft);feet[i++]=side*reach;found=true;break;
      }
      if(!found)return List.of();
    }
    if(feet[1]-feet[0]+width+.75>MAX_PORTAL_SPAN+1e-7)return List.of();
    var beam=new Part(s.at(feet[0]-(width+.75)/2,thickness+depth),s.at(feet[1]+(width+.75)/2,thickness+depth),width+.75,depth,false,Material.CONCRETE);
    if(ground.blocked(beam))return List.of();out.add(beam);return List.copyOf(out);
  }
  public static List<Part> clearStandard(Sample s,double thickness,Ground ground){
    var simple=standard(s,thickness,ground);
    if(!simple.isEmpty()&&simple.stream().noneMatch(ground::blocked))return simple;
    // Keep the cap under the intact deck while placing its shaft on an offset
    // lower median. The upper axis is not a foundation requirement.
    double limit=Math.max(0,s.halfWidth()-1.35);
    for(double reach=.5;reach<=limit+1e-7;reach+=.5)for(int side:new int[]{-1,1}){
      double underside=s.center().y()-thickness,cap=.8;var out=new ArrayList<Part>();
      V axis=s.at(side*reach,0);double floor=foundation(axis,underside,1.5,ground);
      if(!Double.isFinite(floor)||underside-floor<2)continue;
      if(!shaft(out,axis,underside-cap,1.5,ground)||out.isEmpty())continue;
      double spread=Math.min(s.halfWidth(),Math.max(1.4,s.halfWidth()*.78));
      double low=Math.min(-spread,side*reach-1.1),high=Math.max(spread,side*reach+1.1);
      if(low< -s.halfWidth()||high>s.halfWidth())continue;
      out.add(new Part(s.at(low,thickness+cap),s.at(high,thickness+cap),2.1,cap,false,Material.CONCRETE));
      if(out.stream().noneMatch(ground::blocked))return List.copyOf(out);
    }
    // The caller first tries the neighbouring standard stations, then may use
    // a local portal fallback on AUTO / ordinary viaduct only.
    return List.of();
  }
  private RoadSupports(){}
}
