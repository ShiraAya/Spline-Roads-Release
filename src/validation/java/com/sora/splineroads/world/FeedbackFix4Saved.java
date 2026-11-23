package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.core.RoadStructures.*;
import java.util.*;
import java.nio.file.*;
import net.minecraft.nbt.*;

/** Geometry invariants after actual commits and an on-disk reload. */
public final class FeedbackFix4Saved {
 static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
 public static void main(String[] args)throws Exception{
  var root=NbtIo.readCompressed(Path.of(args[0]).toFile()).getCompound("data");var all=new LinkedHashMap<UUID,RoadIndex.Built>();
  for(var tag:root.getList("Roads",Tag.TAG_COMPOUND)){var r=RoadRecord.load((CompoundTag)tag);all.put(r.id(),RoadIndex.Built.loading(r));}
  var host=all.get(UUID.fromString("680cdaf5-5dfb-4521-ab08-6319d0ba7bd8"));var ramp=all.get(UUID.fromString("f39e9d5a-d3a1-4d56-83cc-c4bc4175c688"));
  var pads=host.record.structures().stream().filter(LaneClosureLandscape::paved).toList();check(!pads.isEmpty(),"missing asphalt");
  long blocks=pads.stream().filter(p->RoadClearance.structureInvades(p,ramp.mesh,4.25)).count();check(blocks==0,"asphalt obstructs ramp "+blocks);
  var walls=all.values().stream().filter(r->RoadIndex.overlapXZ(host.mesh,r.mesh,12)).flatMap(r->r.record.structures().stream()).filter(p->p.material()==Material.TUNNEL).toList();
  var tactile=host.record.structures().stream().filter(p->p.material()==Material.TACTILE).toList();check(!tactile.isEmpty()&&!walls.isEmpty(),"tactile/wall fixture empty");
  var clipped=TactileSurface.outsideWalls(tactile,walls);check(clipped.equals(tactile),"saved tactile intersects retaining wall");
  int posts=0;for(var id:List.of("2e9ac01f-fc51-35b9-9dbd-2c0c890aea6d","8630159b-b3c0-3db6-ba5b-ab6866989a1e")){
   var rail=all.get(UUID.fromString(id));var neighbors=all.values().stream().filter(r->r!=rail&&RoadIndex.overlapXZ(rail.mesh,r.mesh,3)).map(r->r.mesh).toList();
   check(RoadRailSlabClipping.clip(rail.record.structures(),neighbors).equals(rail.record.structures()),"saved rail has uncapped/clipped ends");
   posts+=rail.record.structures().stream().filter(p->RoadRailSlabClipping.guardrail(p)&&p.material()==Material.DARK_STEEL).count();
  }check(posts>0,"rail fixture empty");
  System.out.println("FIX4_SAVED_PASS roads="+all.size()+" asphalt="+pads.size()+" rampObstructions="+blocks+" tactile="+tactile.size()+" walls="+walls.size()+" railPosts="+posts);
 }
}
