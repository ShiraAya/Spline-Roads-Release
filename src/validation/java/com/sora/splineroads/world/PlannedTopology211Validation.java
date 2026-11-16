package com.sora.splineroads.world;
import java.nio.file.*;import java.util.*;import net.minecraft.nbt.*;import com.sora.splineroads.core.*;
/** Replays a captured, failed native late-planning pass without any world writes. */
public final class PlannedTopology211Validation {
 public static void main(String[] args)throws Exception{
  System.setProperty("inspection.world",args[0]);var data=Followup211Inspection.load();var tag=NbtIo.readCompressed(Path.of(args[1]).toFile());
  var before=new LinkedHashMap<UUID,RoadRecord>();for(var t:tag.getList("Roads",Tag.TAG_COMPOUND)){var r=RoadRecord.load((CompoundTag)t);before.put(r.id(),r);}
  var ids=new LinkedHashSet<UUID>();for(var id:tag.getString("Changed").split(","))ids.add(UUID.fromString(id));
  var built=new ArrayList<RoadIndex.Built>();for(var id:ids)built.add(new RoadIndex.Built(before.get(id)));
  var initial=before.values().stream().map(RoadIndex.Built::new).toList();
  if(!LaneCrossSections.needsRestoreRefresh(initial,ids))throw new AssertionError("Captured native failure no longer reproduces");
  var removed=new HashSet<>(ids);if(args.length>2&&args[2].equals("original"))LaneTopology.reconcile(data,built,removed);else LaneTopology.reconcilePlanned(data,built,removed);
  var after=new LinkedHashMap<>(before);removed.forEach(after::remove);for(var b:built){var previous=before.get(b.record.id());var r=b.record;if(previous!=null&&r.rawMesh().samples().equals(previous.rawMesh().samples()))r=r.structures(previous.structures());after.put(r.id(),r);}
  var finalPlanning=after.values().stream().map(RoadIndex.Built::new).toList();
  boolean unstable=LaneCrossSections.needsRestoreRefresh(finalPlanning,built.stream().map(b->b.record.id()).toList());
  if(args.length>2&&args[2].equals("original")){if(!unstable)throw new AssertionError("Original negative control unexpectedly converged");System.out.println("NEGATIVE CONTROL confirmed: original discards structure-derived closure");return;}
  if(unstable)throw new AssertionError("Prepared route did not stabilize");
  if(LaneTopology.needsRefresh(data,finalPlanning,built.stream().map(b->b.record.id()).toList()))throw new AssertionError("Ports not stable");
  int unchanged=0,ramps=0;for(var r:before.values()){var next=after.get(r.id());if(next==null)throw new AssertionError("Lost road");if(!ids.contains(r.id())&&r.save().equals(next.save()))unchanged++;if(LaneTopology.metadata(r).link()!=null){if(!r.rawMesh().samples().equals(next.rawMesh().samples()))throw new AssertionError("Rerouted prepared ramp");LaneRamps.validate(next.mesh(),after,next.id(),LaneTopology.metadata(next).link());ramps++;}}
  System.out.println("PASS roads="+after.size()+" stable_ramps="+ramps+" untouched_records="+unchanged+" initially_changed="+ids.size());
 }
}
