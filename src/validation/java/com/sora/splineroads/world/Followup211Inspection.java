package com.sora.splineroads.world;
import java.nio.file.*;import java.util.*;import net.minecraft.nbt.*;import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;
public final class Followup211Inspection {
 public static RoadData load()throws Exception{
  var root=NbtIo.readCompressed(Path.of(System.getProperty("inspection.world","work/feedback-211-nov15-followup/fps-roads.dat")).toFile()).getCompound("data");var d=new RoadData();
  for(var t:root.getList("Roads",Tag.TAG_COMPOUND))d.index.put(new RoadIndex.Built(RoadRecord.load((CompoundTag)t)));
  for(var t:root.getList("Interchanges",Tag.TAG_COMPOUND)){var g=(CompoundTag)t;d.interchanges.put(g.getUUID("Id"),g);}
  for(var t:root.getList("Junctions",Tag.TAG_COMPOUND)){var g=(CompoundTag)t;d.junctions.put(g.getUUID("Id"),g);}
  for(var t:root.getList(root.contains("LogicalStreets")?"LogicalStreets":"Streets",Tag.TAG_COMPOUND)){var r=RoadRecord.load((CompoundTag)t);d.streets.put(r.id(),r);}
  return d;
 }
 public static void main(String[] args)throws Exception{
  var d=load();System.out.println("roads="+d.index.roads.size()+" groups="+d.interchanges.size());
  for(var t:d.interchanges.values()){
   System.out.println("GROUP "+t.getUUID("Id")+" options="+t.getCompound("Options")+" nodes="+t.getList("Nodes",Tag.TAG_COMPOUND)+" keys="+t.getAllKeys());
   if(ParallelSpui.active(t))System.out.println("SPUI "+t.getCompound("ParallelJunction"));
  }
  var near=new V(-20,56,559);
  for(var r:d.index.roads.values())if(r.record.start().position().sub(near).horizontalLength()<12||r.record.end().position().sub(near).horizontalLength()<12)System.out.println("ENDPOINT "+r.record.id()+" a="+r.record.a()+" b="+r.record.b()+" fragment="+(r.record.fragment()!=null)+" header="+r.record.header().sizeInBytes());
  var command=new CompoundTag();AutoJunctions.enrich(command,d,new net.minecraft.core.BlockPos(-16,56,558),new net.minecraft.core.BlockPos(235,56,527),null);
  for(String key:command.getAllKeys()){var one=new CompoundTag();one.put(key,command.get(key));System.out.println("PAYLOAD "+key+" nbt="+one.sizeInBytes()+" gzip="+gzip(one));}
  try{var fragments=com.sora.splineroads.net.RoadWire.split(command);System.out.println("WIRE parts="+fragments.size());}catch(Exception e){System.out.println("WIRE failure="+e.getMessage());}
  var group=d.interchanges.get(UUID.fromString("54526339-ef86-49ab-9dc3-afc53de4272e"));
  var plan=Interchanges.plan(group);var csv=new StringBuilder("leg,x,y,z,width\n");
  for(var leg:plan.legs())for(var s:leg.mesh().samples())csv.append("\"").append(leg.name().replace("\"","\"\"")).append("\"").append(',').append(s.center().x()).append(',').append(s.center().y()).append(',').append(s.center().z()).append(',').append(s.halfWidth()*2).append('\n');
  Files.writeString(Path.of("work/feedback-211-nov15-followup/double-after.csv"),csv);System.out.println("DOUBLE legs="+plan.legs().size());
 }
 static int gzip(CompoundTag t)throws Exception{var out=new java.io.ByteArrayOutputStream();NbtIo.writeCompressed(t,out);return out.size();}
}
