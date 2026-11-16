package com.sora.splineroads.world;
import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;import net.minecraft.core.BlockPos;import java.util.*;
/** Reservation coordinates may extend across a host seam, but only a new cut through occupied pavement is forbidden. */
public class AccessRange203Validation {
 static int checks;static void ok(boolean v,String message){checks++;if(!v)throw new AssertionError(message);}
 static RoadRecord road(boolean beyondStart){var s=new Settings(Mode.STRAIGHT,Style.O6_GREEN,28,1,.35,90);var id=UUID.randomUUID();double from=beyondStart?-100:250,to=beyondStart?30:400;
  var cut=new LaneSections.Cut(id,0,1,from,to,24,null,true,false,true,false);var r=new ElevatedAccess.Reservation(id,UUID.randomUUID(),List.of(cut),List.of(),List.of(new ElevatedAccess.Gap(from,to,-1.5,1.5)));
  s=s.options(s.options().lanePoints(LanePoints.Data.EMPTY.cuts(List.of(cut)).access(List.of(r))));return new RoadRecord(UUID.randomUUID(),UUID.randomUUID(),new BlockPos(0,5,0),new BlockPos(0,5,300),new Node(new V(.5,5,.5),0,0),new Node(new V(.5,5,300.5),0,0),s,false,4);}
 static RoadRecord slice(RoadRecord r,double from,double to){return RoadSplitGeometry.junctionRange(r,from,to,r.id(),r.a(),r.b(),RoadSplitGeometry.node(r.mesh(),from),RoadSplitGeometry.node(r.mesh(),to),RoadSlicing.Connection.CONTINUOUS);}
 static void reject(RoadRecord r,double from,double to){boolean thrown=false;try{slice(r,from,to);}catch(IllegalArgumentException e){thrown=e.getMessage().contains("高架出入口");}ok(thrown,"actual access cut was accepted "+from+".."+to);}
 public static void main(String[] args){for(boolean start:new boolean[]{true,false}){var r=road(start);var original=r.settings().options().lanePoints().access().get(0);var full=slice(r,0,300);ok(full.settings().options().lanePoints().access().equals(List.of(original)),"full host lost extended equation");
   double from=start?0:40,to=start?200:300;var tail=slice(r,from,to);ok(tail.settings().options().lanePoints().access().equals(List.of(ElevatedAccess.shifted(original,-from))),"distant trim changed reservations");
   reject(r,start?5:0,start?300:290);reject(r,start?0:0,start?20:270);
   var clear=slice(r,start?60:0,start?300:200);ok(clear.settings().options().lanePoints().access().isEmpty(),"disjoint piece retained reservation");
  }System.out.println("AccessRange203Validation PASS checks="+checks);}
}
