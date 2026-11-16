package com.sora.splineroads.world;
import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;import java.nio.file.*;import net.minecraft.core.BlockPos;import net.minecraft.gametest.framework.*;import net.minecraft.nbt.*;import net.minecraft.world.level.levelgen.Heightmap;import net.minecraftforge.gametest.*;
@GameTestHolder("splineroads_201") @PrefixGameTestTemplate(false)
public final class AccessBridge201GameTests {
 static Settings road(){var s=RoadLanes.configure(new Settings(Mode.STRAIGHT,Style.O2_RAIL,9,1,.35,90),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(3,3),4);return s.options(s.options().extras(false,false,false).route(s.options().routing().fit(false)));}
 @GameTest(template="empty",templateNamespace="splineroads_201",timeoutTicks=24000)
 public static void actualBridgeBothBuildOrders(GameTestHelper h){var level=h.getLevel();int fixture=0;
  for(var kind:List.of(RoadInfrastructure.Bridge.ARCH,RoadInfrastructure.Bridge.CABLE,RoadInfrastructure.Bridge.SUSPENSION))for(boolean bridgeFirst:new boolean[]{false,true}){
   int x=210000+fixture++*1000,z=0;level.getChunkAt(new BlockPos(x,0,z));double y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)+18;
   var a=Curve201GameTests.marker(level,new Node(new V(x+.5,y,z+.5),0,0));var b=Curve201GameTests.marker(level,new Node(new V(x+.5,y,z+100.5),0,0));var c=Curve201GameTests.marker(level,new Node(new V(x+.5,y,z+300.5),0,0));
   var standard=road().structure(Structure.BRIDGE);standard=standard.options(standard.options().sidewalk(RoadSidewalks.Config.DEFAULT.enabled(true)));
   var bridge=standard.options(standard.options().infrastructure(standard.options().infrastructure().bridge(kind)));
   var data=RoadData.get(level);RoadRecord old,next;
   if(bridgeFirst){next=data.connect(level,null,b,c,bridge,null);old=data.connect(level,null,a,b,standard,null);}
   else{old=data.connect(level,null,a,b,standard,null);h.assertTrue(old.structures().stream().anyMatch(p->p.material().name().startsWith("WALK_")),"fixture missing saved sidewalk");next=data.connect(level,null,b,c,bridge,null);}
   h.assertTrue(data.index.roads.containsKey(old.id())&&data.index.roads.containsKey(next.id()),"joined road missing");
   h.assertTrue(!data.index.roads.get(next.id()).record.structures().isEmpty(),"custom bridge has no actual structures");
   System.out.println("SR201 WORLD BRIDGE PASS kind="+kind+" bridgeFirst="+bridgeFirst);
  }h.assertTrue(RoadWorkChunks.heldCount(level)==0,"bridge tickets leaked");h.succeed();
 }
 @GameTest(template="empty",templateNamespace="splineroads_201",timeoutTicks=24000)
 public static void actualSingleAuxiliaryCreateDiskReloadDelete(GameTestHelper h)throws Exception{var level=h.getLevel();
  for(int direction:new int[]{1,-1}){int x=218000+(direction==1?0:1000),z=0;level.getChunkAt(new BlockPos(x,0,z));double y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)+.125;
   var data=RoadData.get(level);var origin=new V(x+.5,y,z+.5);var a=Curve201GameTests.marker(level,new Node(origin,0,0));var b=Curve201GameTests.marker(level,new Node(origin.add(new V(0,0,400)),0,0));
   var host=data.connect(level,null,a,b,road(),null);var marker=ElevatedPointTool.create(level,null,data.index.roads.get(host.id()),origin.add(new V(0,0,120)));
   var end=Curve201GameTests.marker(level,new Node(origin.add(new V(0,12,240)),0,0));var far=Curve201GameTests.marker(level,new Node(origin.add(new V(0,12,345)),0,0));
   var settings=RoadLanes.configure(road().structure(Structure.BRIDGE),RoadProfile.Type.ORDINARY,new RoadLanes.Counts(direction==1?3:2,direction==1?2:3),4);var upper=data.connect(level,null,end,far,settings,null);
   var draft=ElevatedAccessRoads.payload(level,null,host.id(),marker.id(),end,true,null);var snapshot=data.save(new CompoundTag());var preview=ElevatedAccessRoads.preview(level,null,draft);h.assertTrue(snapshot.equals(data.save(new CompoundTag())),"preview mutated saved state");ElevatedAccessRoads.build(level,null,preview);
   var md=LaneTopology.metadata(data.index.roads.get(host.id()).record);h.assertTrue(md.additions().size()==1,"expected precisely one auxiliary direction");h.assertTrue(md.additions().get(0).sign()==direction,"wrong auxiliary side");var id=md.access().get(0).connection();
   var disk=Files.createTempFile("sr201-single-aux-", ".nbt");RoadData restored;try{NbtIo.writeCompressed(data.save(new CompoundTag()),disk.toFile());restored=RoadData.load(NbtIo.readCompressed(disk.toFile()));}finally{Files.deleteIfExists(disk);}
   level.getDataStorage().set("splineroads",restored);h.assertTrue(LaneTopology.metadata(restored.index.roads.get(host.id()).record).access().equals(md.access()),"disk reload changed reservation");RoadClearance.check(restored.index.roads.get(id).mesh,restored.index.roads.get(host.id()).mesh,null);
   if(direction==1){
     restored.remove(level,null,upper.id());
     h.assertTrue(!restored.index.roads.containsKey(upper.id())&&restored.index.roads.containsKey(id),"deleting the continuation removed the independent connector");
   }
   restored.remove(level,null,id);h.assertTrue(LaneTopology.metadata(restored.index.roads.get(host.id()).record).access().isEmpty()&&LaneTopology.metadata(restored.index.roads.get(host.id()).record).additions().isEmpty(),"delete left reserved lanes");h.assertTrue(restored.index.roads.containsKey(upper.id())==(direction!=1),"connector removal changed the upper road state");
   System.out.println("SR201 WORLD SINGLE AUX PASS direction="+direction+" preview/build/disk reload/delete");
  }h.assertTrue(RoadWorkChunks.heldCount(level)==0,"access tickets leaked");h.succeed();
 }
 @GameTest(template="empty",templateNamespace="splineroads_201",timeoutTicks=12000)
 public static void mildGradeChainRegression(GameTestHelper h){int cases=0;
  for(boolean reversed:new boolean[]{false,true})for(double grade:new double[]{0,.0001,.001,-.001})for(int length:new int[]{32,64,128}){
   var s=road();var data=new RoadData();var host=chainRoad(128,128+length,grade,s);for(var r:List.of(chainRoad(reversed?128:0,reversed?0:128,grade,s),host,chainRoad(reversed?512:128+length,reversed?128+length:512,grade,s)))data.index.put(new RoadIndex.Built(r));
   var p=new V(0,3+grade*(128+length/2d),128+length/2d);var marker=new RoadAttachments.Point(UUID.randomUUID(),p,p,false,p,true);var upper=RoadLanes.configure(s,RoadProfile.Type.ORDINARY,new RoadLanes.Counts(2,2),4);
   var result=ElevatedAccessChain.plan(data,host,marker,UUID.randomUUID(),new Node(new V(0,15+grade*(128+length/2d+120),128+length/2d+120),0,grade),upper,true);
   h.assertTrue(!result.reservations().isEmpty(),"missing chain reservation");cases++;
   for(var entry:result.reservations().entrySet())h.assertTrue(data.index.roads.containsKey(entry.getKey()),"reservation references an absent road");
   System.out.println("SR201 CHAIN PASS grade="+grade+" length="+length+" reversed="+reversed+" hosts="+result.reservations().size());
  }
  for(boolean brokenHeight:new boolean[]{false,true}){double grade=.001;var s=road();var data=new RoadData();var host=chainRoad(128,160,grade,s);var next=chainRoad(160,512,grade,s);var start=next.start();var end=next.end();
   if(brokenHeight)start=new Node(start.position().add(new V(0,.01,0)),start.yaw(),start.grade());else start=new Node(start.position(),start.yaw(),-.02);
   next=new RoadRecord(next.id(),next.owner(),next.a(),next.b(),start,end,s,false,4,List.of(),0,0);for(var r:List.of(chainRoad(0,128,grade,s),host,next))data.index.put(new RoadIndex.Built(r));var p=new V(0,3+grade*144,144);var marker=new RoadAttachments.Point(UUID.randomUUID(),p,p,false,p,true);var upper=RoadLanes.configure(s,RoadProfile.Type.ORDINARY,new RoadLanes.Counts(2,2),4);boolean rejected=false;
   try{ElevatedAccessChain.plan(data,host,marker,UUID.randomUUID(),new Node(new V(0,15+grade*264,264),0,grade),upper,true);}catch(IllegalArgumentException expected){rejected=true;}
   h.assertTrue(rejected,"accepted discontinuous chain: height="+brokenHeight);
  }h.assertTrue(cases==24,"missing grade cases");h.succeed();
 }
 @GameTest(template="empty",templateNamespace="splineroads_201",timeoutTicks=24000)
 public static void actualGradedChainBuildReloadDelete(GameTestHelper h)throws Exception{var level=h.getLevel();int fixture=0;
  for(double grade:new double[]{.0001,-.001}){int x=221000+fixture++*1000;level.getChunkAt(new BlockPos(x,0,0));double y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,0)+.25;var origin=new V(x+.5,y,.5);var data=RoadData.get(level);var nodes=new ArrayList<BlockPos>();
   for(int z:new int[]{0,100,200,400})nodes.add(Curve201GameTests.marker(level,new Node(origin.add(new V(0,grade*z,z)),0,grade)));
   var s=road();var before=data.connect(level,null,nodes.get(0),nodes.get(1),s,null);var host=data.connect(level,null,nodes.get(1),nodes.get(2),s,null);var after=data.connect(level,null,nodes.get(2),nodes.get(3),s,null);
   var marker=ElevatedPointTool.create(level,null,data.index.roads.get(host.id()),origin.add(new V(0,grade*120,120)));var end=Curve201GameTests.marker(level,new Node(origin.add(new V(0,12+grade*240,240)),0,grade));var far=Curve201GameTests.marker(level,new Node(origin.add(new V(0,12+grade*345,345)),0,grade));data.connect(level,null,end,far,s.structure(Structure.BRIDGE),null);
   var draft=ElevatedAccessRoads.payload(level,null,host.id(),marker.id(),end,true,null);ElevatedAccessRoads.build(level,null,ElevatedAccessRoads.preview(level,null,draft));var id=LaneTopology.metadata(data.index.roads.get(host.id()).record).access().get(0).connection();
   h.assertTrue(data.interchanges.get(id).getList("Hosts",Tag.TAG_INT_ARRAY).size()==3,"grade chain did not borrow both adjacent roads");var disk=Files.createTempFile("sr201-grade-chain-", ".nbt");RoadData restored;try{NbtIo.writeCompressed(data.save(new CompoundTag()),disk.toFile());restored=RoadData.load(NbtIo.readCompressed(disk.toFile()));}finally{Files.deleteIfExists(disk);}level.getDataStorage().set("splineroads",restored);
   h.assertTrue(restored.interchanges.get(id).getList("Hosts",Tag.TAG_INT_ARRAY).size()==3,"disk reload lost borrowed hosts");restored.remove(level,null,id);for(var r:List.of(before,host,after))h.assertTrue(LaneTopology.metadata(restored.index.roads.get(r.id()).record).access().isEmpty()&&LaneTopology.metadata(restored.index.roads.get(r.id()).record).additions().isEmpty(),"deletion left graded lane reservation");System.out.println("SR201 WORLD GRADE PASS grade="+grade+" build/disk reload/delete");
  }h.assertTrue(RoadWorkChunks.heldCount(level)==0,"graded access tickets leaked");h.succeed();
 }
 static RoadRecord chainRoad(double a,double b,double grade,Settings s){var av=new V(0,3+grade*a,a);var bv=new V(0,3+grade*b,b);double yaw=b>a?0:180,g=b>a?grade:-grade;return new RoadRecord(UUID.randomUUID(),new UUID(0,0),BlockPos.containing(av.x(),av.y(),av.z()),BlockPos.containing(bv.x(),bv.y(),bv.z()),new Node(av,yaw,g),new Node(bv,yaw,g),s,false,4,List.of(),0,0);}
}
