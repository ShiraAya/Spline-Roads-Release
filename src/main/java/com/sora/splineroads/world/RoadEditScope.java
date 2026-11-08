package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.Part;
import java.util.*;

/** Conservative physical impact of an indirectly derived endpoint rail change.
 * Authored edits and every other kind of change retain whole-road validation. */
final class RoadEditScope {
  static RoadBoundsIndex.Bounds bounds(Mesh mesh){return new RoadBoundsIndex.Bounds(mesh.min().x(),mesh.min().z(),mesh.max().x(),mesh.max().z());}
  static RoadBoundsIndex.Bounds bounds(List<Part> parts){
    double minX=Double.POSITIVE_INFINITY,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxZ=maxX;
    for(var part:parts){double pad=part.halfExtent();minX=Math.min(minX,Math.min(part.a().x(),part.b().x())-pad);minZ=Math.min(minZ,Math.min(part.a().z(),part.b().z())-pad);maxX=Math.max(maxX,Math.max(part.a().x(),part.b().x())+pad);maxZ=Math.max(maxZ,Math.max(part.a().z(),part.b().z())+pad);}
    return new RoadBoundsIndex.Bounds(minX,minZ,maxX,maxZ);
  }
  static boolean unchanged(RoadIndex.Built before,RoadIndex.Built after){
    return before!=null&&before.record.structures(List.of()).equals(after.record.structures(List.of()))&&before.mesh.equals(after.mesh);
  }
  private static RoadTransitions.Section withoutRail(RoadTransitions.Section s){
    return s==null||s.outerRail()==RoadProfile.OuterRail.OFF?s:new RoadTransitions.Section(s.style(),s.width(),s.cycle(),s.cycleRail(),s.curb(),RoadProfile.OuterRail.OFF,s.sidewalk(),s.cycleAsphalt(),s.port(),s.streetscape(),s.lanes());
  }
  private static Settings withoutEndRails(Settings s){
    var e=s.options().ends();return s.options(s.options().ends(new RoadTransitions.Ends(withoutRail(e.start()),withoutRail(e.end()),e.persistent(),e.trimmedStart(),e.trimmedEnd(),e.port(),e.paintPhase(),e.startHold(),e.endHold())));
  }
  private static boolean sameRibbon(Mesh a,Mesh b){
    if(a==b)return true;if(a==null||b==null)return false;
    return a.samples().equals(b.samples())&&a.min().equals(b.min())&&a.max().equals(b.max())&&a.length()==b.length()&&a.closed()==b.closed()
        &&Objects.equals(a.controlPoint(),b.controlPoint())&&a.controls().equals(b.controls())&&Objects.equals(a.slicing(),b.slicing())
        &&withoutEndRails(a.settings()).equals(withoutEndRails(b.settings()))&&sameRibbon(a.reference(),b.reference());
  }
  private static RoadBoundsIndex.Bounds range(Mesh mesh,double from,double to){
    double minX=Double.POSITIVE_INFINITY,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxZ=maxX;
    var samples=new ArrayList<Sample>();samples.add(RoadStructures.sample(mesh,from));samples.add(RoadStructures.sample(mesh,to));
    for(var sample:mesh.samples())if(sample.distance()>from&&sample.distance()<to)samples.add(sample);
    double verge=mesh.settings().options().sidewalk().enabled()?mesh.settings().options().sidewalk().width():0;
    for(var sample:samples){double pad=sample.halfWidth()+verge+2;minX=Math.min(minX,sample.center().x()-pad);minZ=Math.min(minZ,sample.center().z()-pad);maxX=Math.max(maxX,sample.center().x()+pad);maxZ=Math.max(maxZ,sample.center().z()+pad);}
    return new RoadBoundsIndex.Bounds(minX,minZ,maxX,maxZ);
  }
  /** null means that no local proof exists. An empty list proves no change.
   * Only the two derived Section.outerRail fields may differ: curve, sampling,
   * caps, own options, lane topology, furniture phase and all metadata are exact. */
  static List<RoadBoundsIndex.Bounds> endpointRails(RoadIndex.Built before,RoadIndex.Built after){
    if(before==null||after==null)return null;if(unchanged(before,after))return List.of();
    if(!withoutEndRails(before.record.settings()).equals(withoutEndRails(after.record.settings()))
        ||!before.record.structures(List.of()).equals(after.record.settings(before.record.settings()).structures(List.of()))
        ||!sameRibbon(before.mesh,after.mesh))return null;
    var a=before.mesh.settings().options().ends();var b=after.mesh.settings().options().ends();
    double length=before.mesh.length()+a.trimmedStart()+a.trimmedEnd();
    double oldSpan=RoadTransitions.span(before.mesh.settings(),length),newSpan=RoadTransitions.span(after.mesh.settings(),length),span=Math.max(oldSpan,newSpan);
    // Changing one end can change the shared taper span at the other end too.
    boolean first=!Objects.equals(a.start(),b.start())||oldSpan!=newSpan&&(a.start()!=null||b.start()!=null);
    boolean last=!Objects.equals(a.end(),b.end())||oldSpan!=newSpan&&(a.end()!=null||b.end()!=null);
    var result=new ArrayList<RoadBoundsIndex.Bounds>();
    if(first){double end=Math.min(before.mesh.length(),Math.max(0,span-a.trimmedStart()));result.add(range(before.mesh,0,end));result.add(range(after.mesh,0,end));}
    if(last){double start=Math.max(0,Math.min(before.mesh.length(),before.mesh.length()+a.trimmedEnd()-span));result.add(range(before.mesh,start,before.mesh.length()));result.add(range(after.mesh,start,after.mesh.length()));}
    return List.copyOf(result);
  }
  static boolean affects(List<RoadBoundsIndex.Bounds> local,RoadIndex.Built dependency){
    if(local.isEmpty())return false;
    var deck=bounds(dependency.mesh);var fixtures=dependency.record.structures().isEmpty()?null:bounds(dependency.record.structures());
    return local.stream().anyMatch(area->area.overlaps(deck,82)||fixtures!=null&&area.overlaps(fixtures,82));
  }
  private RoadEditScope(){}
}
