package com.sora.splineroads.core;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import com.sora.splineroads.world.*;

/** Checks persisted meshes, independently of a new planner run. */
public final class FeedbackFix5Saved {
 public static void main(String[] args)throws Exception{
  var root=NbtIo.readCompressed(Path.of(args[0]).toFile()).getCompound("data");var groups=new HashMap<UUID,String>();
  for(var value:root.getList("Interchanges",Tag.TAG_COMPOUND)){var t=(CompoundTag)value;String preset=t.getCompound("Options").getString("Preset");if(preset.equals("STACK")||preset.equals("TURBINE"))groups.put(t.getUUID("Id"),preset);}
  FeedbackFix5Core.check(groups.size()==2,"missing persisted interchange fixture");var counts=new HashMap<UUID,Integer>();double maxJump=0,maxGrade=0;
  for(var value:root.getList("Roads",Tag.TAG_COMPOUND)){var r=RoadRecord.load((CompoundTag)value);if(!groups.containsKey(r.assembly())||!r.settings().style().ramp())continue;
   var m=FeedbackFix5Core.metrics(r.mesh());maxJump=Math.max(maxJump,m[1]);maxGrade=Math.max(maxGrade,m[0]);counts.merge(r.assembly(),1,Integer::sum);
   FeedbackFix5Core.check(m[0]<.150001&&m[1]<.01&&m[2]<.02,"persisted grade discontinuity "+r.id());
  }
  for(var id:groups.keySet())FeedbackFix5Core.check(counts.getOrDefault(id,0)==16,"missing saved ramp legs "+id);
  System.out.println("FIX5_SAVED_PASS groups="+groups.size()+" rampLegs="+counts.values().stream().mapToInt(Integer::intValue).sum()+" maxGrade="+maxGrade+" maxJump="+maxJump);
 }
}
