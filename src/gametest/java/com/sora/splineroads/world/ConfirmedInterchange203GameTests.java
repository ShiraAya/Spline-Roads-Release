package com.sora.splineroads.world;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;import com.sora.splineroads.core.InterchangePlanner.*;
import com.mojang.authlib.GameProfile;import java.util.*;import net.minecraft.core.BlockPos;import net.minecraft.gametest.framework.*;import net.minecraft.nbt.*;import net.minecraft.server.level.*;import net.minecraft.world.level.GameType;import net.minecraftforge.gametest.*;import net.minecraftforge.common.util.FakePlayerFactory;
@GameTestHolder("splineroads_203_confirmed") @PrefixGameTestTemplate(false)
public final class ConfirmedInterchange203GameTests {
 static BlockPos marker(ServerLevel level,ServerPlayer player,Node n){var p=BlockPos.containing(n.position().x(),n.position().y(),n.position().z());level.getChunkAt(p);level.setBlock(p,SplineRoads.NODE.get().defaultBlockState(),2);var entity=(NodeEntity)level.getBlockEntity(p);entity.owner=player.getUUID();entity.heightExplicit=true;entity.apply(n);return p;}
 static CompoundTag command(CompoundTag source,ListTag target){var out=source.copy();out.put("SourceNodes",source.getList("Nodes",Tag.TAG_COMPOUND).copy());out.putInt("SourceAxesHash",HostAxes.signature(source));out.put("FittedNodes",target.copy());return out;}
 static void rejected(GameTestHelper h,Runnable action,String name){boolean rejected=false;try{action.run();}catch(IllegalArgumentException expected){rejected=true;System.out.println("SR203 CONFIRMED EXPECTED "+name+": "+expected.getMessage());}h.assertTrue(rejected,"accepted "+name);}
 @GameTest(template="empty",templateNamespace="splineroads_203_confirmed",timeoutTicks=36000)
 public static void fixedPreviewBuildUpdateAndOwnership(GameTestHelper h){
  var level=h.getLevel();var data=RoadData.get(level);var player=FakePlayerFactory.get(level,new GameProfile(new UUID(203,203),"SR203Fit"));player.setGameMode(GameType.CREATIVE);
  var s=new Settings(Mode.STRAIGHT,Style.H4_RAIL,28,1,.4,90);s=s.options(s.options().extras(false,false,false).cycleFinish(RoadProfile.Options.CycleFinish.NONE).route(RoadProfile.Routing.DEFAULT.fit(false)).infrastructure(s.options().infrastructure().gantry(RoadInfrastructure.Gantry.OFF)));
  // Reserve a distinct region: ContinuousEnds201GameTests owns (28100.5,28100.5).
  // All GameTests share one dimension and absolute-coordinate fixtures survive
  // their test. Reserve this remote square away from ContinuousEnds201 (28100.5).
  double x=703100.5,z=703100.5;
  for(var existing:data.index.roads.values()){
    var min=existing.mesh.min();var max=existing.mesh.max();
    h.assertTrue(max.x()<x-1500||min.x()>x+1500||max.z()<z-1500||min.z()>z+1500,
        "confirmed-fit fixture area overlaps existing road "+existing.record.id()+" bounds="+min+" .. "+max);
  }
  Node[] ns={new Node(new V(x-400,-59,z),-90,0),new Node(new V(x+400,-59,z),-90,0),new Node(new V(x,-38,z-400),0,0),new Node(new V(x,-38,z+400),0,0)};
  long[] points=new long[4];for(int i=0;i<4;i++)points[i]=marker(level,player,ns[i]).asLong();
  data.connect(level,player,BlockPos.of(points[0]),BlockPos.of(points[1]),s,null);data.connect(level,player,BlockPos.of(points[2]),BlockPos.of(points[3]),s.structure(Structure.BRIDGE),null);
  var source=Interchanges.payload(level,player,points,null);source.put("Options",Interchanges.write(new Options(Preset.HYBRID,false,1,96,20,5,2,0,true,false,8)));
  var automatic=Interchanges.plan(source);
  // A slower preview may stop at the valid authored range; revalidation must use
  // that confirmed range rather than a faster server's different tighter result.
  var request=command(source,source.getList("Nodes",Tag.TAG_COMPOUND));var checked=Interchanges.confirmedPlan(source,request);
  h.assertTrue(checked.anchors().equals(Arrays.asList(ns)),"fixed candidate lost authored endpoints");
  h.assertTrue(!automatic.anchors().equals(checked.anchors()),"fixture did not exercise different valid fit results");
  var bad=request.copy();bad.getList("FittedNodes",Tag.TAG_COMPOUND).getCompound(0).putDouble("Z",z+2);rejected(h,()->Interchanges.build(level,player,bad),"off-axis submitted point");
  var other=FakePlayerFactory.get(level,new GameProfile(new UUID(203,204),"SR203Other"));other.setGameMode(GameType.CREATIVE);rejected(h,()->Interchanges.build(level,other,request),"foreign ownership");
  var before=new HashSet<>(data.interchanges.keySet());Interchanges.build(level,player,request);var added=new HashSet<>(data.interchanges.keySet());added.removeAll(before);h.assertTrue(added.size()==1,"missing built interchange");UUID group=added.iterator().next();var saved=data.interchanges.get(group);h.assertTrue(saved.getList("Nodes",Tag.TAG_COMPOUND).equals(request.getList("FittedNodes",Tag.TAG_COMPOUND)),"build changed confirmed endpoints");
  var edit=Interchanges.payload(level,player,saved.getLongArray("Points"),group);var preview=Interchanges.plan(edit);var targets=new ListTag();preview.anchors().forEach(n->targets.add(RoadRecord.writeNode(n)));var editRequest=command(edit,targets);
  Interchanges.plan(edit);Interchanges.build(level,player,editRequest);h.assertTrue(data.interchanges.get(group).getList("Nodes",Tag.TAG_COMPOUND).equals(targets),"overall update changed confirmed endpoints");
  var loaded=RoadData.load(data.save(new CompoundTag()));level.getDataStorage().set("splineroads",loaded);h.assertTrue(loaded.interchanges.containsKey(group),"reload lost group");Interchanges.remove(level,player,group);h.assertTrue(!loaded.interchanges.containsKey(group),"delete retained group");h.assertTrue(RoadWorkChunks.heldCount(level)==0,"chunk tickets leaked");
  System.out.println("SR203 CONFIRMED WORLD PASS different-valid-fit/build/overall-update/reload/delete/off-axis/owner");h.succeed();
 }
}
