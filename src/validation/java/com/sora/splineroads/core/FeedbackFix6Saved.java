package com.sora.splineroads.core;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import com.sora.splineroads.world.*;

/** Read the native-client result independently of the planner and its caches. */
public final class FeedbackFix6Saved {
 public static void main(String[] args)throws Exception{
  var root=NbtIo.readCompressed(Path.of(args[0]).toFile()).getCompound("data");var records=new HashMap<UUID,RoadRecord>();
  for(var value:root.getList("Roads",Tag.TAG_COMPOUND)){var r=RoadRecord.load((CompoundTag)value);records.put(r.id(),r);}
  int groups=0,ramps=0;
  for(var value:root.getList("Interchanges",Tag.TAG_COMPOUND)){
   var t=(CompoundTag)value;var preset=t.getCompound("Options").getString("Preset");if(!Set.of("STACK","TURBINE","TRUMPET","SPUI").contains(preset))continue;
   groups++;var legs=new ArrayList<InterchangePlanner.Leg>();
   for(var item:t.getList("SignTopology",Tag.TAG_COMPOUND)){var tag=(CompoundTag)item;var r=records.get(tag.getUUID("Road"));FeedbackFix6Core.check(r!=null,"missing saved leg");var mesh=r.mesh();RoadGrades.validate(mesh);
    legs.add(new InterchangePlanner.Leg(r.id().toString(),tag.getInt("From"),tag.getInt("To"),mesh));
    if(mesh.settings().style().ramp()){ramps++;var m=FeedbackFix6Core.metrics(mesh);FeedbackFix6Core.check(m[0]<.150001&&m[1]<.01&&m[2]<.02,"saved grade discontinuity "+r.id());}
   }
   if(preset.equals("STACK")){
    double radius=legs.stream().filter(l->l.mesh().settings().style().ramp()).mapToDouble(l->RoadRibbon.minRadius(l.mesh())).min().orElseThrow();
    FeedbackFix6Core.stackTopology(new InterchangePlanner.Plan(legs,8,radius,0,new RoadGeometry.V(0,0,0)));
    for(var leg:legs)if(leg.from()>=0&&leg.to()<0)System.out.println("FIX6_SAVED_FORK "+leg.from()+" "+leg.mesh().last().center());
   }
  }
  FeedbackFix6Core.check(groups==4&&ramps>32,"missing saved interchange corpus");System.out.println("FIX6_SAVED_PASS groups="+groups+" rampLegs="+ramps);
 }
}
