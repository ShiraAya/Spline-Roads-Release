package com.sora.splineroads.world;
import com.sora.splineroads.SplineRoads;import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;import java.io.*;import java.lang.reflect.*;import net.minecraft.nbt.*;import net.minecraft.core.*;import net.minecraft.gametest.framework.*;import net.minecraft.server.level.*;import net.minecraft.world.level.ChunkPos;import net.minecraft.world.level.block.Blocks;import net.minecraftforge.gametest.*;
/** Isolated real NodeEntity and final transaction validation, using read-only saved geometry. */
@GameTestHolder("splineroads_202") @PrefixGameTestTemplate(false)
public class RetainedNode202GameTests {
 private static void validate(RoadData data,ServerLevel level,BlockPos p,List<RoadIndex.Built> changed,List<RoadIndex.Built> finals,boolean walkway)throws Exception{
  long key=p.asLong(),chunk=new ChunkPos(p).toLong();var body=finals.stream().filter(r->!r.boxes(p).isEmpty()).toList();var air=finals.stream().anyMatch(r->r.clearanceAt(p));
  var type=Class.forName("com.sora.splineroads.world.RoadData$PreparedEdit");var ctor=type.getDeclaredConstructors()[0];ctor.setAccessible(true);
  var edit=ctor.newInstance(changed,Set.of(),false,Set.of(key),Set.of(),Set.of(),walkway?Map.of(key,Blocks.STONE.defaultBlockState()):Map.of(),Set.of(),body.isEmpty()?Map.of():Map.of(key,body),air?Set.of(key):Set.of(),Set.of(),Map.of(chunk,finals),Set.of(),finals,List.of(),RoadLimits.MAX_EDIT_CELLS,Set.of(chunk));
  var method=RoadData.class.getDeclaredMethod("validateAndCommit",ServerLevel.class,ServerPlayer.class,type,boolean.class);method.setAccessible(true);
  try{method.invoke(data,level,null,edit,true);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception x)throw x;throw e;}
 }
 private static void reject(GameTestHelper h,RoadData data,BlockPos p,List<RoadIndex.Built> changed,List<RoadIndex.Built> finals,boolean walk,String label)throws Exception{
  boolean rejected=false;try{validate(data,h.getLevel(),p,changed,finals,walk);}catch(IllegalArgumentException e){rejected=e.getMessage().contains("道路范围内有其他端点");}h.assertTrue(rejected,"new node conflict accepted: "+label);
 }
 @GameTest(template="empty",templateNamespace="splineroads_202",timeoutTicks=2000)
 public static void unchangedRemoteEndpointTransaction(GameTestHelper h)throws Exception{
  String path=System.getenv("SR202_NODE_SOURCE");h.assertTrue(path!=null,"Set SR202_NODE_SOURCE to a read-only splineroads.dat snapshot");var tag=NbtIo.readCompressed(new File(path)).getCompound("data");var records=new HashMap<UUID,RoadRecord>();for(Tag t:tag.getList("Roads",Tag.TAG_COMPOUND)){var r=RoadRecord.load((CompoundTag)t);records.put(r.id(),r);}
  var lower=records.get(UUID.fromString("3c13e036-ed13-4120-973b-f6e15c43f240"));var upper=records.get(UUID.fromString("23f0d1c1-dbe9-4b43-b442-a23ae699c665"));h.assertTrue(lower!=null&&upper!=null,"source snapshot is missing F07 roads");
  var p=Curve201GameTests.marker(h.getLevel(),upper.start());var original=h.getLevel().getBlockEntity(p).saveWithFullMetadata();var data=new RoadData();var before=new RoadIndex.Built(lower);var top=new RoadIndex.Built(upper);data.index.put(before);data.index.put(top);var next=new RoadIndex.Built(RoadRecord.load(lower.save()));var finals=List.of(next,top);h.assertTrue(before.boxes(p).isEmpty()&&before.clearanceAt(p),"old air-only contact fixture invalid");
  if("1".equals(System.getenv("SR202_NODE_EXPECT_LEGACY"))){reject(h,data,p,List.of(next),finals,false,"baseline unchanged contact");System.out.println("SR202 NODE RED reproduced actual saved endpoint "+p);h.succeed();return;}
  validate(data,h.getLevel(),p,List.of(next),finals,false);
  var newId=new RoadRecord(UUID.randomUUID(),lower.owner(),lower.a(),lower.b(),lower.start(),lower.end(),lower.settings(),lower.automatic(),lower.clearance(),lower.structures(),lower.endCaps(),lower.buildVersion(),lower.assembly(),lower.alignment(),lower.furniturePhase(),lower.junction(),lower.toll(),lower.fragment());var fresh=new RoadIndex.Built(newId);reject(h,data,p,List.of(fresh),List.of(fresh,top),false,"new road ID");reject(h,data,p,List.of(next),List.of(next),false,"removed endpoint owner");reject(h,data,p,List.of(next),finals,true,"paving occupancy");
  var at=new V(p.getX()+.5,p.getY(),p.getZ()+.5);var parts=new ArrayList<>(lower.structures());parts.add(new RoadStructures.Part(at,at,.5,.5,true,RoadStructures.Material.CONCRETE));var added=new RoadIndex.Built(lower.structures(parts));h.assertTrue(!added.boxes(p).isEmpty(),"added body fixture empty");reject(h,data,p,List.of(added),List.of(added,top),false,"new structure");
  data.index.remove(upper.id());reject(h,data,p,List.of(next),finals,false,"new marker owner");data.index.put(top);var small=new RoadIndex.Built(lower.structures(List.of()));h.assertTrue(!small.clearanceAt(p),"reduced old air fixture invalid");data.index.put(small);reject(h,data,p,List.of(next),finals,false,"new clearance");
  h.assertTrue(h.getLevel().getBlockState(p).is(SplineRoads.NODE.get())&&h.getLevel().getBlockEntity(p).saveWithFullMetadata().equals(original),"validation changed real endpoint block/entity");
  System.out.println("SR202 NODE GREEN actual final world validation accepted unchanged contact; 6 identity/body/air/paving negative controls rejected; NodeEntity exact");h.succeed();
 }
}
