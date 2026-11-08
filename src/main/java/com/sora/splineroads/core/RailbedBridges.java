package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;
/** Apply the selected railway bridge to continuous AUTO elevated portions. */
public final class RailbedBridges {
  public static List<RoadStreetscape.Span> runs(Mesh mesh){
    if(mesh.settings().style()!=Style.RAILBED||mesh.settings().structure()!=Structure.AUTO||mesh.settings().options().infrastructure().bridge()==RoadInfrastructure.Bridge.STANDARD)return List.of();
    var sides=mesh.settings().options().streetscape().sides();var out=new ArrayList<RoadStreetscape.Span>();
    for(var a:sides.negativeRaised())for(var b:sides.positiveRaised()){double from=Math.max(a.from(),b.from()),to=Math.min(a.to(),b.to());if(to-from>=8)out.add(new RoadStreetscape.Span(from,to));}
    return List.copyOf(out);
  }
  public static boolean customAt(Mesh mesh,double station){return runs(mesh).stream().anyMatch(s->station>=s.from()&&station<=s.to());}
  public static List<Part> plan(Mesh mesh,Ground ground){
    if(ground.sharedDeck())return List.of();
    var out=new ArrayList<Part>();for(var span:runs(mesh)){
      var samples=new ArrayList<Sample>();samples.add(RoadStructures.sample(mesh,span.from()));mesh.samples().stream().filter(s->s.distance()>span.from()&&s.distance()<span.to()).forEach(samples::add);samples.add(RoadStructures.sample(mesh,span.to()));
      var segment=RoadRibbon.mesh(samples,mesh.settings().structure(Structure.BRIDGE));out.addAll(RoadInfrastructure.plan(segment,ground));
    }return List.copyOf(out);
  }
  private RailbedBridges(){}
}
