package com.sora.splineroads.world;
import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;import java.nio.file.*;import net.minecraft.core.BlockPos;import net.minecraft.gametest.framework.*;import net.minecraft.nbt.*;import net.minecraft.world.level.levelgen.Heightmap;import net.minecraftforge.gametest.*;
@GameTestHolder("splineroads_202_planning") @PrefixGameTestTemplate(false)
public final class Planning202GameTests {
 static Settings settings(){var s=RoadLanes.configure(new Settings(Mode.CURVE,Style.O6_GREEN,28,1,.35,90),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(3,3),4);return s.options(s.options().extras(false,false,false).route(RoadProfile.Routing.DEFAULT.fit(false)));}
 static Settings upperSettings(){var s=RoadLanes.configure(new Settings(Mode.STRAIGHT,Style.O6_RAIL,22,1,.35,90),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(2,3),4);return s.options(s.options().extras(false,false,false).route(RoadProfile.Routing.DEFAULT.fit(false)));}
 static RoadRecord road(Node a,Node b,Settings s){return new RoadRecord(UUID.randomUUID(),new UUID(0,0),BlockPos.containing(a.position().x(),a.position().y(),a.position().z()),BlockPos.containing(b.position().x(),b.position().y(),b.position().z()),a,b,s,false,4,List.of(),0,0);}
 @GameTest(template="empty",templateNamespace="splineroads_202_planning",timeoutTicks=18000)
 public static void curvedAccessChain(GameTestHelper h){
  var s=settings();var a=new Node(new V(-532.5755968228827,3,285.23595249456577),91.21674945664473,0);var b=new Node(new V(-758.2551553197677,3,286.4207904877285),89.69919476468566,0);
  var host=road(a,b,s);var neighbor=road(new Node(a.position(),-88.7832505433553,0),new Node(new V(-266.63492317702094,3,290.8843933542457),-88.7832505433553,0),new Settings(Mode.STRAIGHT,s.style(),s.width(),1,.35,90).options(s.options()));
  var sample=RoadStructures.sample(host.rawMesh(),98.35);var point=new RoadAttachments.Point(UUID.randomUUID(),sample.center(),sample.center(),false,sample.center(),true);var raised=road(new Node(new V(-399.5,12,288.5),90,0),new Node(new V(-499.5,12,288.5),90,0),upperSettings());var port=raised.caps(0).mesh().last();var end=new Node(port.center(),RoadPlanner.yaw(port.left().left()),0);var upper=RoadEndpointSections.section(raised.caps(0).mesh(),false,true);h.assertTrue(RoadLanes.counts(upper).equals(new RoadLanes.Counts(3,2)),"upper port was not mirrored to 3+2");
  var data=new RoadData();data.index.put(new RoadIndex.Built(host));data.index.put(new RoadIndex.Built(neighbor));var before=data.save(new CompoundTag());
  var result=ElevatedAccessChain.plan(data,host,point,UUID.randomUUID(),end,upper,true);h.assertTrue(result.reservations().size()==2,"curved chain not borrowed");h.assertTrue(before.equals(data.save(new CompoundTag())),"planning moved source geometry");
  h.assertTrue(host.rawMesh().samples().stream().anyMatch(q->Math.abs(q.center().sub(a.position()).dot(b.position().sub(a.position()).horizontalUnit().left()))>.1),"fixture is not curved");
  int rejected=0;
  for(int kind=0;kind<4;kind++){var first=neighbor.start();var last=neighbor.end();var ns=neighbor.settings();if(kind==0)first=new Node(first.position().add(new V(0,.01,0)),first.yaw(),0);if(kind==1)first=new Node(first.position(),first.yaw(),.02);if(kind==2)last=new Node(last.position().add(new V(0,0,20)),last.yaw(),0);if(kind==3)ns=RoadLanes.configure(ns,RoadProfile.Type.ORDINARY,new RoadLanes.Counts(2,2),4);var bad=new RoadRecord(neighbor.id(),neighbor.owner(),neighbor.a(),neighbor.b(),first,last,ns,false,4,List.of(),0,0);data.index.put(new RoadIndex.Built(bad));boolean failed=false;try{ElevatedAccessChain.plan(data,host,point,UUID.randomUUID(),end,upper,true);}catch(IllegalArgumentException expected){failed=true;}h.assertTrue(failed,"accepted discontinuous/incorrect section kind="+kind);rejected++;}
  h.assertTrue(rejected==4,"missing negative controls");System.out.println("SR202 CURVED ACCESS PASS 2 retained roads, exact source geometry, 4 discontinuity controls");h.succeed();
 }
 @GameTest(template="empty",templateNamespace="splineroads_202_planning",timeoutTicks=24000)
 public static void actualCurvedAccessBuildReloadDelete(GameTestHelper h)throws Exception{
  var l=h.getLevel();int ox=235000; l.getChunkAt(new BlockPos(ox,0,0));double y=l.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,ox,0)+.125;V shift=new V(ox+532.5755968228827,y+60,-285.23595249456577);
  var an=new Node(new V(-532.5755968228827,-60,285.23595249456577).add(shift),91.21674945664473,0);
  var bn=new Node(new V(-758.2551553197677,-60,286.4207904877285).add(shift),89.69919476468566,0);
  var cn=new Node(new V(-266.63492317702094,-60,290.8843933542457).add(shift),-88.7832505433553,0);
  var a=Curve201GameTests.marker(l,an);var b=Curve201GameTests.marker(l,bn);var c=Curve201GameTests.marker(l,cn);var s=settings();var straight=new Settings(Mode.STRAIGHT,s.style(),s.width(),1,.35,90).options(s.options());var data=RoadData.get(l);
  var host=data.connect(l,null,a,b,s,null);var shared=(NodeEntity)l.getBlockEntity(a);shared.headingLocked=false;shared.plannerHeading=true;shared.setChanged();var neighbor=data.connect(l,null,a,c,straight,null);var raw=data.index.roads.get(host.id()).record.rawMesh();var beforeNeighbor=data.index.roads.get(neighbor.id()).record.rawMesh();
  h.assertTrue(raw.samples().stream().anyMatch(q->Math.abs(q.center().sub(raw.first().center()).dot(raw.last().center().sub(raw.first().center()).horizontalUnit().left()))>.1),"built host lost curve");var sample=RoadStructures.sample(raw,98.35);var marker=ElevatedPointTool.create(l,null,data.index.roads.get(host.id()),sample.center());
  var end=Curve201GameTests.marker(l,new Node(new V(-499.5,-51,288.5).add(shift),90,0));var far=Curve201GameTests.marker(l,new Node(new V(-399.5,-51,288.5).add(shift),90,0));
  var upper=upperSettings();var raised=data.connect(l,null,far,end,upper,null);
  var draft=ElevatedAccessRoads.payload(l,null,host.id(),marker.id(),end,true,null);h.assertTrue(RoadLanes.counts(RoadRecord.readSettings(draft.getCompound("Settings"))).equals(new RoadLanes.Counts(3,2)),"preview did not inherit actual mirrored upper section");var preview=ElevatedAccessRoads.preview(l,null,draft);ElevatedAccessRoads.build(l,null,preview);
  var id=LaneTopology.metadata(data.index.roads.get(host.id()).record).access().get(0).connection();h.assertTrue(data.interchanges.get(id).getList("Hosts",Tag.TAG_INT_ARRAY).size()==2,"curved build did not reserve both original road IDs");
  Curve201GameTests.same(raw,data.index.roads.get(host.id()).record.rawMesh());Curve201GameTests.same(beforeNeighbor,data.index.roads.get(neighbor.id()).record.rawMesh());
  var disk=Files.createTempFile("sr202-curved-access-", ".nbt");RoadData restored;try{NbtIo.writeCompressed(data.save(new CompoundTag()),disk.toFile());restored=RoadData.load(NbtIo.readCompressed(disk.toFile()));}finally{Files.deleteIfExists(disk);}l.getDataStorage().set("splineroads",restored);
  h.assertTrue(restored.interchanges.get(id).getList("Hosts",Tag.TAG_INT_ARRAY).size()==2,"reload lost curve source IDs");restored.remove(l,null,id);
  for(var r:List.of(host,neighbor)){var lanes=LaneTopology.metadata(restored.index.roads.get(r.id()).record);h.assertTrue(lanes.access().isEmpty()&&lanes.additions().isEmpty(),"delete left auxiliary reservations");}
  h.assertTrue(restored.index.roads.containsKey(raised.id()),"delete removed existing upper road");h.assertTrue(RoadWorkChunks.heldCount(l)==0,"curved access chunk tickets leaked");System.out.println("SR202 CURVED ACCESS WORLD PASS preview/build/unaltered curved centers/disk reload/delete");h.succeed();
 }

}
