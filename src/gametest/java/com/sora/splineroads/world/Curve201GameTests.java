package com.sora.splineroads.world;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;
import java.nio.file.*;import java.util.*;
import net.minecraft.core.BlockPos;import net.minecraft.gametest.framework.*;import net.minecraft.nbt.*;import net.minecraft.server.level.ServerLevel;import net.minecraft.world.level.levelgen.Heightmap;import net.minecraftforge.gametest.*;
@GameTestHolder("splineroads_201") @PrefixGameTestTemplate(false)
public final class Curve201GameTests {
 static BlockPos marker(ServerLevel level,Node node){var p=BlockPos.containing(node.position().x(),node.position().y(),node.position().z());level.getChunkAt(p);level.setBlock(p,SplineRoads.NODE.get().defaultBlockState(),3);var e=(NodeEntity)level.getBlockEntity(p);e.owner=new UUID(0,0);e.heightExplicit=true;e.headingLocked=true;e.gradeLocked=true;e.apply(node);return p;}
 static RoadRecord current(ServerLevel l,UUID id){return RoadData.get(l).index.roads.get(id).record;}
 static void same(Mesh a,Mesh b){for(var p:a.samples())if(p.center().distance(RoadSlicing.at(b,p.distance()).center())>1e-6)throw new AssertionError("saved curve drift at "+p.distance());}
 @GameTest(template="empty",templateNamespace="splineroads_201",timeoutTicks=16000)
 public static void actualCurveSplitEditDiskAndEdit(GameTestHelper h)throws Exception{
  var l=h.getLevel();int n=0;
  for(boolean through:new boolean[]{false,true}){int x=201000+n++*1000,z=0;l.getChunkAt(new BlockPos(x,0,z));double y=l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)+.125;
   var a=marker(l,new Node(new V(x+.5,y,z+.5),0,0));var b=marker(l,new Node(new V(x+.5,y,z+300.5),0,0));
   var s=RoadLanes.configure(new Settings(Mode.CURVE,Style.O2_YELLOW,9,1,.35,90).structure(Structure.GROUND),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(3,3),4);
   s=s.options(s.options().extras(false,false,false).outerRail(RoadProfile.OuterRail.OFF).route(s.options().routing().fit(false).controls(List.of(new RoadProfile.Control(UUID.randomUUID(),new V(x+45.5,y,z+100.5)),new RoadProfile.Control(UUID.randomUUID(),new V(x+45.5,y,z+200.5)))).through(through)));
   var whole=RoadData.get(l).connect(l,null,a,b,s,null);var p=RoadSplitter.plan(l,null,whole,whole.mesh().length()*.3,RoadSlicing.Connection.CONTINUOUS);RoadSplitter.commit(l,null,p);
   var r=current(l,whole.id());var before=r.rawMesh();var f=r.fragment();var legacy=r.settings(r.settings().options(r.settings().options().route(s.options().routing()))).fragment(new RoadSlicing.Fragment(f.source(),f.origin(),f.total(),f.connection(),f.applied(),f.geometry(),f.supports()));
   var oldTag=legacy.save();oldTag.getCompound("Slice").putInt("Version",1);oldTag.getCompound("Slice").remove("CurveAnchors");legacy=RoadRecord.load(oldTag);RoadData.get(l).index.put(new RoadIndex.Built(legacy));
   h.assertTrue(legacy.fragment().curveAnchors().isEmpty(),"legacy record changed before editing");
   var edit=legacy.localCurveForEditing();same(before,edit.rawMesh());RoadData.get(l).connect(l,null,edit.a(),edit.b(),edit.settings(),edit.id(),false);r=current(l,edit.id());same(before,r.rawMesh());
   var cs=new ArrayList<>(r.settings().options().routing().controls());var c=cs.get(0);cs.set(0,new RoadProfile.Control(c.id(),c.position().add(new V(.1,0,0))));
   var changed=r.settings().options(r.settings().options().route(r.settings().options().routing().controls(cs)).outerRail(RoadProfile.OuterRail.ON));RoadData.get(l).connect(l,null,r.a(),r.b(),changed,r.id(),false);r=current(l,r.id());h.assertTrue(!r.fragment().curveAnchors().isEmpty(),"actual edit discarded local anchors");var edited=r.rawMesh();
   var snapshot=RoadData.get(l).save(new CompoundTag());for(int version:new int[]{70,71,72,73}){var accepted=snapshot.copy();accepted.putInt("Version",version);h.assertTrue(RoadData.load(accepted).index.roads.containsKey(r.id()),"compatible actual saved NBT rejected: "+version);}for(int version:new int[]{69,74}){var denied=snapshot.copy();denied.putInt("Version",version);boolean rejected=false;try{RoadData.load(denied);}catch(IllegalStateException expected){rejected=true;}h.assertTrue(rejected,"incompatible actual saved NBT accepted: "+version);}
   var disk=Files.createTempFile("sr201-curves-", ".nbt");RoadData restored;
   try{NbtIo.writeCompressed(RoadData.get(l).save(new CompoundTag()),disk.toFile());restored=RoadData.load(NbtIo.readCompressed(disk.toFile()));}finally{Files.deleteIfExists(disk);}
   l.getDataStorage().set("splineroads",restored);r=current(l,r.id());same(edited,r.rawMesh());
   RoadData.get(l).connect(l,null,r.a(),r.b(),r.settings().options(r.settings().options().outerRail(RoadProfile.OuterRail.OFF)),r.id(),false);r=current(l,r.id());same(edited,r.rawMesh());
   h.assertTrue(l.getBlockEntity(p.pair().pos()) instanceof NodeEntity,"shared split node lost");
   System.out.println("SR201 WORLD CURVE PASS through="+through+" actual build/split/legacy edit/control+rail edit/disk NBT/install/re-edit");
  }h.assertTrue(RoadWorkChunks.heldCount(l)==0,"curve lifecycle leaked chunk tickets");h.succeed();
 }
}
