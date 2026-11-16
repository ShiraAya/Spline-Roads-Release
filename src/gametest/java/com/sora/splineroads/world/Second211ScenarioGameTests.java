package com.sora.splineroads.world;
import java.util.*;import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;
import net.minecraft.gametest.framework.*;
@net.minecraftforge.gametest.GameTestHolder("splineroads_211_scenarios")
@net.minecraftforge.gametest.PrefixGameTestTemplate(false)
public final class Second211ScenarioGameTests {
 public static void rebuildSpui(net.minecraft.server.level.ServerLevel l){var d=RoadData.get(l);var id=UUID.fromString("c3ba2c13-3ca2-3977-9e62-80fbd51280c3");var saved=d.interchanges.get(id);Interchanges.build(l,null,Interchanges.payload(l,null,saved.getLongArray("Points"),id));}

 @GameTest(template="empty",templateNamespace="splineroads_211_extension",timeoutTicks=16000)
 public static void lowerRoadExtension(GameTestHelper h){
  var l=h.getLevel();var d=RoadData.get(l);var host=d.index.roads.get(UUID.fromString("b82e1f44-eebe-3d18-947b-892dd6d94985")).record;
  var direction=host.end().position().sub(host.start().position()).horizontalUnit();var end=new net.minecraft.core.BlockPos(1206,56,1217);l.getChunkAt(end);if(!(l.getBlockEntity(end) instanceof NodeEntity))throw new AssertionError("expected reported extension end "+end);
  var road=d.connect(l,null,host.b(),end,RoadProfile.editable(host.settings()),null);
  System.out.println("SECOND211 LOWER EXTENSION PASS "+road.id());h.succeed();
 }
 @GameTest(template="empty",templateNamespace="splineroads_211_scenarios",timeoutTicks=16000)
 public static void compactSpui(GameTestHelper h){
  var l=h.getLevel();rebuildSpui(l);var d=RoadData.get(l);var id=UUID.fromString("c3ba2c13-3ca2-3977-9e62-80fbd51280c3");
  var ramps=d.index.roads.values().stream().filter(r->id.equals(r.record.assembly())&&r.mesh.settings().style().ramp()).toList();h.assertTrue(ramps.size()==4,"four compact ramps");
  for(var r:ramps){double grade=0;var m=r.mesh;for(int i=1;i<m.samples().size();i++){var v=m.samples().get(i).center().sub(m.samples().get(i-1).center());grade=Math.max(grade,Math.abs(v.y())/v.horizontalLength());}h.assertTrue(grade>=.10&&grade<=.15,"compact ramp grade "+grade);System.out.println("SECOND211 SPUI WORLD length="+m.length()+" grade="+grade);}
  h.succeed();
 }
}
