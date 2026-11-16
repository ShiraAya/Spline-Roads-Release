package com.sora.splineroads.world;
import java.nio.file.*;import java.util.*;import net.minecraft.nbt.*;import com.sora.splineroads.core.*;
/** Captured valid non-monotone AUTO route: appearance edits still validate obstacles. */
public final class RampRailEdit211Validation {
 public static void main(String[] args)throws Exception{
  System.setProperty("inspection.world",args[0]);var data=Followup211Inspection.load();var state=NbtIo.readCompressed(Path.of(args[1]).toFile());
  var all=LaneTopology.records(data);var r=all.get(state.getCompound("Roads").getUUID("tunnel-entry"));
  if(LaneRamps.monotone(r.mesh())||LaneRamps.verticalKink(r.mesh()))throw new AssertionError("Captured profile no longer reproduced");
  for(var rail:List.of(r.settings().options().outerRail(),RoadProfile.OuterRail.ON)){
   var request=RoadRecord.readSettings(RoadRecord.writeSettings(r.settings().options(r.settings().options().outerRail(rail))));
   var next=LaneRamps.reconfigure(r,request,all);
   if(!next.id().equals(r.id())||!next.rawMesh().samples().equals(r.rawMesh().samples())||!LaneTopology.metadata(next).link().equals(LaneTopology.metadata(r).link())||next.settings().options().outerRail()!=rail)throw new AssertionError("Appearance edit changed alignment or lost request");
   LaneRamps.validate(next.mesh(),LaneCrossSections.staged(all,r.id(),LaneTopology.metadata(next).link(),next.mesh()),r.id(),LaneTopology.metadata(next).link());
  }
  var settings=r.settings().options(r.settings().options().lanePoints(LanePoints.Data.EMPTY));
  var mesh=RoadRibbon.mesh(r.mesh().samples(),settings);
  var obstacle=new RoadRecord(UUID.randomUUID(),r.owner(),r.a(),r.b(),r.start(),r.end(),settings,false,4).alignment(null,mesh);
  var blocked=new LinkedHashMap<>(all);blocked.put(obstacle.id(),obstacle);
  boolean rejected=false;try{LaneRamps.reconfigure(r,r.settings().options(r.settings().options().outerRail(RoadProfile.OuterRail.ON)),blocked);}catch(IllegalArgumentException expected){rejected=true;}
  if(!rejected)throw new AssertionError("New obstacle bypassed by retained route");
  System.out.println("RAMP_RAIL_EDIT211 PASS unchanged_save=true rail_edit=true exact_samples="+r.mesh().samples().size()+" new_obstacle_rejected=true");
 }
}
