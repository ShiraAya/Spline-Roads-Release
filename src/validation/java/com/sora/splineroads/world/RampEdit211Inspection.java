package com.sora.splineroads.world;
import java.nio.file.*;import java.util.*;import net.minecraft.nbt.*;import com.sora.splineroads.core.*;
/** Read-only reproduction of a saved connector edit and its obstacle identity. */
public final class RampEdit211Inspection {
 public static void main(String[] args)throws Exception{
  System.setProperty("inspection.world",args[0]);var data=Followup211Inspection.load();var state=NbtIo.readCompressed(Path.of(args[1]).toFile());
  var all=LaneTopology.records(data);var id=state.getCompound("Roads").getUUID("tunnel-entry");var r=all.get(id);
  System.out.println("RAMP "+id+" samples="+r.mesh().samples().size()+" link="+LaneTopology.metadata(r).link());
  var from=all.get(LaneTopology.metadata(r).link().from().road());System.out.println("HOST "+from.id()+" cuts="+LaneTopology.metadata(from).cuts());
  if(args.length>2){var collision=all.get(UUID.fromString(args[2]));System.out.println("COLLISION "+(collision==null?null:collision.id())+" host="+(collision!=null&&collision.id().equals(from.id())));}
  var method=LaneRamps.class.getDeclaredMethod("reconfigure",RoadData.class,RoadRecord.class,RoadGeometry.Settings.class,Map.class);method.setAccessible(true);
  System.out.println("ENDS start="+(r.settings().options().ends().start()!=null)+" end="+(r.settings().options().ends().end()!=null));
  for(String check:List.of("verticalKink","monotone")){var m=LaneRamps.class.getDeclaredMethod(check,RoadGeometry.Mesh.class);m.setAccessible(true);System.out.println(check+"="+m.invoke(null,r.mesh()));}
  try{var staged=LaneCrossSections.staged(all,id,LaneTopology.metadata(r).link(),r.mesh());LaneRamps.validate(r.mesh(),staged,id,LaneTopology.metadata(r).link());System.out.println("SAVED_GEOMETRY PASS");}catch(Exception e){System.out.println("SAVED_GEOMETRY FAIL "+e);}
  for(var rail:List.of(r.settings().options().outerRail(),RoadProfile.OuterRail.ON)){
   long began=System.nanoTime();try{var next=(RoadRecord)method.invoke(null,data,r,r.settings().options(r.settings().options().outerRail(rail)),all);System.out.println("EDIT "+rail+" PASS samples="+next.mesh().samples().size()+" ms="+(System.nanoTime()-began)/1e6);}
   catch(Exception e){System.out.println("EDIT "+rail+" FAIL ms="+(System.nanoTime()-began)/1e6+" "+(e.getCause()==null?e:e.getCause()));}
  }
 }
}
