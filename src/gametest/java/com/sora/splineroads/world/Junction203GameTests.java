package com.sora.splineroads.world;
import com.sora.splineroads.SplineRoads;import com.sora.splineroads.core.*;import java.util.*;import java.io.*;import net.minecraft.core.BlockPos;import net.minecraft.nbt.*;import net.minecraft.gametest.framework.*;import net.minecraftforge.gametest.*;
/** Exact saved metadata replayed into a separate flat world; no original block is changed. */
@GameTestHolder("splineroads_203_junction") @PrefixGameTestTemplate(false)
public class Junction203GameTests {
 static boolean sameConnector(CompoundTag before,CompoundTag after){
  var a=RoadRecord.load(before).mesh();var b=RoadRecord.load(after).mesh();var x=before.copy();var y=after.copy();
  x.getCompound("Settings").getCompound("AttachedRoadPoints").remove("Spans");y.getCompound("Settings").getCompound("AttachedRoadPoints").remove("Spans");if(!x.equals(y))return false;
  var cuts=new TreeSet<Double>();cuts.add(0.0);cuts.add(a.length());for(var mesh:List.of(a,b))for(var span:mesh.settings().options().attachments().spans()){cuts.add(Math.max(0,Math.min(a.length(),RoadAttachments.station(mesh,span.start()))));cuts.add(Math.max(0,Math.min(a.length(),RoadAttachments.station(mesh,span.end()))));}
  var boundaries=new ArrayList<>(cuts);for(int i=1;i<boundaries.size();i++)cuts.add((boundaries.get(i-1)+boundaries.get(i))/2);
  for(double at:cuts)if(!RoadAttachments.paint(a,at).equals(RoadAttachments.paint(b,at)))return false;return true;
 }
 @GameTest(template="empty",templateNamespace="splineroads_203_junction",timeoutTicks=24000)
 public static void savedTJunctionCanRebuildEachArm(GameTestHelper h)throws Exception{
  var level=h.getLevel();String source=System.getenv("SR202_NODE_SOURCE"),nodes=System.getenv("SR203_NODE_TAGS");h.assertTrue(source!=null&&nodes!=null,"Set read-only source and node snapshots");
  var data=RoadData.load(NbtIo.readCompressed(new File(source)).getCompound("data"));level.getDataStorage().set("splineroads",data);
  for(Tag raw:NbtIo.readCompressed(new File(nodes)).getList("Nodes",Tag.TAG_COMPOUND)){var tag=(CompoundTag)raw;var p=new BlockPos(tag.getInt("x"),tag.getInt("y"),tag.getInt("z"));level.getChunkAt(p);level.setBlock(p,SplineRoads.NODE.get().defaultBlockState(),3);((NodeEntity)level.getBlockEntity(p)).load(tag);}
  var center=new BlockPos(-233,-60,291);var config=AutoJunctions.center(data,center);h.assertTrue(config!=null,"latest saved T center missing");
  var graph=AutoJunctions.context(data,Set.of(center));var roads=new ArrayList<RoadRecord>();for(Tag raw:graph.getList("Streets",Tag.TAG_COMPOUND))roads.add(RoadRecord.load((CompoundTag)raw));roads.sort(Comparator.comparing(RoadRecord::id));h.assertTrue(roads.size()==3,"actual T should have 3 logical arms");
  int pick=Integer.parseInt(System.getenv().getOrDefault("SR203_EDIT_INDEX","0"));h.assertTrue(pick>=0&&pick<=3,"edit index 0=center 1..3=arms");
  var connectors=new HashMap<UUID,CompoundTag>();var elevated=new HashMap<UUID,CompoundTag>();data.interchanges.forEach((id,t)->{if(t.getBoolean("ElevatedAccess")){elevated.put(id,t.copy());connectors.put(id,data.index.roads.get(id).record.save());}});h.assertTrue(elevated.size()==1,"expected one actual elevated access");
  var snapshot=data.save(new CompoundTag());
  if(pick==0)AutoJunctions.edit(level,null,AutoJunctions.payload(level,null,config.getUUID("Id")));
  else {var r=roads.get(pick-1);data.connect(level,null,r.a(),r.b(),RoadProfile.editable(r.settings()),r.id());}
  for(var id:elevated.keySet()){h.assertTrue(data.interchanges.get(id).equals(elevated.get(id)),"T edit changed elevated access ownership receipt");var after=data.index.roads.get(id).record.save();var before=connectors.get(id);if(!after.equals(before)){NbtIo.writeCompressed(before,new File("t-connector-before.nbt"));NbtIo.writeCompressed(after,new File("t-connector-after.nbt"));for(var key:before.getAllKeys())if(!Objects.equals(before.get(key),after.get(key)))System.out.println("SR203 CONNECTOR CHANGED key="+key+" before="+(String.valueOf(before.get(key)).length()<1000?before.get(key):"long")+" after="+(String.valueOf(after.get(key)).length()<1000?after.get(key):"long"));}h.assertTrue(sameConnector(before,after),"T edit changed remote connector geometry, settings or effective paint");}
  for(var r:roads)h.assertTrue(data.index.roads.containsKey(r.id()),"T edit lost source street");h.assertTrue(data.junctions.containsKey(config.getUUID("Id")),"T center lost");h.assertTrue(RoadWorkChunks.heldCount(level)==0,"T edit leaked chunk tickets");
  System.out.println("SR203 T WORLD PASS index="+pick+" roads="+data.index.roads.size()+" connector geometry, effective paint and ownership exact");h.succeed();
 }
}


