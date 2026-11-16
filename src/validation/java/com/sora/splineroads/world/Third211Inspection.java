package com.sora.splineroads.world;
import java.nio.file.*;import java.util.*;import net.minecraft.nbt.*;import net.minecraft.network.FriendlyByteBuf;import io.netty.buffer.Unpooled;import com.sora.splineroads.core.*;import com.sora.splineroads.core.RoadGeometry.*;
public final class Third211Inspection {
 public static RoadData load()throws Exception{System.setProperty("inspection.world","work/feedback-211-nov15-third/reported-roads.dat");return Followup211Inspection.load();}
 static int bytes(CompoundTag t){var b=new FriendlyByteBuf(Unpooled.buffer());try{b.writeNbt(t);return b.readableBytes();}finally{b.release();}}
 static void sizes(String label,CompoundTag t){System.out.println(label+" TOTAL="+bytes(t));for(String k:t.getAllKeys()){var one=new CompoundTag();one.put(k,t.get(k));System.out.println(label+" "+k+"="+bytes(one));}}
 public static void main(String[]args)throws Exception{
  var d=load();System.out.println("ROADS "+d.index.roads.size());
  for(var saved:d.interchanges.values())if(ParallelSpui.active(saved)){
   var t=saved.copy();ParallelSpui.capture(d,t,null);System.out.println("SPUI "+t.getUUID("Id")+" options="+t.getCompound("Options"));
   var command=new CompoundTag();for(String k:List.of("Id","Points","Options","Main1","Main2","Main3"))if(t.contains(k))command.put(k,t.get(k).copy());command.putString("Action","interchange_update");command.putInt("SourceAxesHash",HostAxes.signature(t));command.put("SourceNodes",t.getList("Nodes",Tag.TAG_COMPOUND).copy());
   sizes("COMMAND",command);sizes("MAIN1",t.getCompound("Main1"));sizes("MAIN2",t.getCompound("Main2"));
   for(String k:List.of("Main1","Main2"))for(String child:t.getCompound(k).getAllKeys())if(t.getCompound(k).get(child) instanceof CompoundTag nested&&bytes(nested)>2000)sizes(k+"."+child,nested);
   var plan=Interchanges.plan(t);var csv=new StringBuilder("leg,station,x,y,z,width\n");for(var leg:plan.legs())if(leg.mesh().settings().style().ramp()){var m=leg.mesh();System.out.println("RAMP "+leg.name()+" length="+m.length()+" first="+m.first().center()+" last="+m.last().center());for(var s:m.samples())csv.append(leg.name()).append(',').append(s.distance()).append(',').append(s.center().x()).append(',').append(s.center().y()).append(',').append(s.center().z()).append(',').append(s.halfWidth()*2).append('\n');}Files.writeString(Path.of("work/feedback-211-nov15-third/spui-before-"+t.getUUID("Id")+".csv"),csv);
  }
  var near=new V(547,56,1347);d.index.roads.values().stream().sorted(Comparator.comparingDouble(r->RoadQueries.horizontal(r.mesh,near).horizontalDistance())).limit(8).forEach(r->{System.out.println("NEAR "+r.record.id()+" distance="+RoadQueries.horizontal(r.mesh,near).horizontalDistance()+" start="+r.record.start().position()+" end="+r.record.end().position()+" fragment="+r.record.fragment()+" assembly="+r.record.assembly());System.out.println("SETTINGS "+RoadRecord.writeSettings(r.record.settings()));});
 }
}
