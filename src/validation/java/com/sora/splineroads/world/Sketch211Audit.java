package com.sora.splineroads.world;
import java.nio.file.*;import java.util.*;import net.minecraft.nbt.*;
/** Read-only receipt and full geometry export for the built Redstone Ready fixture. */
public final class Sketch211Audit {
 public static void main(String[] a)throws Exception{
  System.setProperty("inspection.world",a[0]);var data=Followup211Inspection.load();var state=NbtIo.readCompressed(Path.of(a[1]).toFile());
  if(state.getInt("Next")!=70)throw new AssertionError("Incomplete fixture "+state.getInt("Next"));
  var roads=state.getCompound("Roads");int ramps=0;
  for(String name:List.of("tunnel-entry","tunnel-exit","viaduct-east-entry","viaduct-west-entry")){
   var r=data.index.roads.get(roads.getUUID(name));if(r==null||LaneTopology.metadata(r.record).link()==null)throw new AssertionError("Missing ramp "+name);ramps++;
  }
  for(String name:List.of("parallel-spui","turbine-above","trumpet-a"))if(!data.interchanges.containsKey(state.getCompound("Designs").getCompound(name).getUUID("Id")))throw new AssertionError("Missing interchange "+name);
  var out=new StringBuilder("road\tjunction\tx\ty\tz\thalf_width\n");
  for(var b:data.index.roads.values())for(var s:b.mesh.samples())out.append(b.record.id()).append('\t').append(b.record.junction()!=null).append('\t').append(s.center().x()).append('\t').append(s.center().y()).append('\t').append(s.center().z()).append('\t').append(s.halfWidth()).append('\n');
  Files.writeString(Path.of(a[2]),out);
  System.out.println("SKETCH211 PASS completed_steps=70 roads="+data.index.roads.size()+" logical_streets="+data.streets.size()+" junctions="+data.junctions.size()+" managed_assemblies="+data.interchanges.size()+" requested_auto_interchanges=3 requested_ramps="+ramps+" exported_vertices="+out.toString().lines().count());
  for(var t:data.interchanges.values())System.out.println("INTERCHANGE "+t.getUUID("Id")+" "+t.getCompound("Options"));
 }
}
