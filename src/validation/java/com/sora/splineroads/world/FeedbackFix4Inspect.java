package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
import java.nio.file.*;
import net.minecraft.nbt.*;
public final class FeedbackFix4Inspect {
 static int bytes(CompoundTag tag){try{var out=new java.io.ByteArrayOutputStream();NbtIo.write(tag,new java.io.DataOutputStream(out));return out.size();}catch(Exception e){throw new RuntimeException(e);}}
 public static void main(String[] args)throws Exception{
  var root=NbtIo.readCompressed(Path.of(args[0]).toFile()).getCompound("data");var roads=new ArrayList<RoadIndex.Built>();
  for(var tag:root.getList("Roads",Tag.TAG_COMPOUND))roads.add(RoadIndex.Built.loading(RoadRecord.load((CompoundTag)tag)));
  for(var p:List.of(new V(-8,56,1452),new V(-10,56,1421),new V(1230,56,954),new V(1247,56,959),new V(388,65,69))){
   System.out.println("AT "+p);roads.stream().sorted(Comparator.comparingDouble(r->RoadQueries.horizontal(r.mesh,p).horizontalDistance())).limit(3).forEach(b->{var r=b.record;var q=RoadQueries.horizontal(b.mesh,p);System.out.println("ROAD "+r.id()+" assembly="+r.assembly()+" style="+r.settings().style()+" structure="+r.settings().structure()+" dist="+q.horizontalDistance()+" station="+q.sample().distance()+" y="+q.sample().center().y()+" minY="+b.mesh.min().y()+" maxY="+b.mesh.max().y()+" link="+LaneTopology.metadata(r).link());var parts=r.structures().stream().filter(x->x.a().add(x.b()).mul(.5).sub(p).horizontalLength()<20).toList();System.out.println("PARTS "+parts.stream().collect(java.util.stream.Collectors.groupingBy(x->x.material()+"/"+x.model(),java.util.stream.Collectors.counting())));});
  }
  for(var b:roads){var settings=RoadRecord.writeSettings(b.record.settings());int size=bytes(settings);if(size>16000){System.out.println("LARGE_SETTINGS "+b.record.id()+" bytes="+size);for(var k:settings.getAllKeys()){var t=new CompoundTag();t.put(k,settings.get(k));if(bytes(t)>2000)System.out.println(" KEY "+k+"="+bytes(t));}}}
  for(var key:List.of("Interchanges","LogicalStreets")){var list=root.getList(key,Tag.TAG_COMPOUND);System.out.println(key+" count="+list.size());if(key.equals("Interchanges"))for(var value:list){var t=(CompoundTag)value;System.out.println("INTERCHANGE "+t.getUUID("Id")+" keys="+t.getAllKeys());for(var k:t.getAllKeys()){var v=t.get(k);System.out.println(" META "+k+" "+(v.toString().length()<1500?v:"length="+v.toString().length()));}for(var source:ContinuousRoads.sources(t))System.out.println(" SOURCE "+source.road().id());}}
 }
}
