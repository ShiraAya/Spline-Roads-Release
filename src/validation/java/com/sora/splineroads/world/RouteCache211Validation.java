package com.sora.splineroads.world;
import java.util.*;import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;

/** Run in separate JVMs against baseline/candidate classes; compare every verdict. */
public final class RouteCache211Validation {
 public static void main(String[] args)throws Exception{
  System.setProperty("inspection.world",args[0]);var data=Followup211Inspection.load();var all=LaneTopology.records(data);int cases=0;long began=System.nanoTime();
  for(var road:all.values().stream().filter(r->LaneTopology.metadata(r).link()!=null).sorted(Comparator.comparing(RoadRecord::id)).toList()){
   var mesh=road.mesh();for(int dy:new int[]{0,-2}){
    var candidate=dy==0?mesh:RoadRibbon.mesh(mesh.samples().stream().map(s->new Sample(s.center().add(new V(0,dy,0)),s.left(),s.distance(),s.halfWidth())).toList(),mesh.settings());
    String result="PASS";try{LaneRamps.validate(candidate,all,road.id(),LaneTopology.metadata(road).link());}catch(IllegalArgumentException failure){result=failure.getClass().getName()+":"+failure.getMessage();}
    System.out.println("RESULT "+road.id()+" dy="+dy+" "+result);cases++;
   }
  }
  if(cases<8)throw new AssertionError("insufficient ramp fixtures "+cases);
  System.out.println("CASES "+cases);System.out.println("ELAPSED_MS "+(System.nanoTime()-began)/1e6);
 }
}
