package com.sora.splineroads.world;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import com.sora.splineroads.core.*;

public final class FeedbackFix6MemoryChecks {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception{
  var root=NbtIo.readCompressed(Path.of(args[0]).toFile()).getCompound("data");int count=0;
  for(var value:root.getList("Roads",Tag.TAG_COMPOUND)){
   var road=RoadRecord.load((CompoundTag)value);var tag=road.save();
   check(tag.equals(RoadRecord.load(tag.copy()).save()),"roundtrip "+road.id());count++;
  }
  var spec=root.getList("Roads",Tag.TAG_COMPOUND).stream().map(v->((CompoundTag)v).getCompound("Junction")).filter(t->t.contains("OneSide")).findFirst().orElseThrow().copy();
  var a=JunctionCodec.read(spec);var b=JunctionCodec.read(spec.copy());check(a.oneSide()==b.oneSide(),"unchanged snapshots not shared");
  var changed=spec.copy();var source=changed.getCompound("OneSide");source.putDouble("From",source.getDouble("From")+.25);
  var c=JunctionCodec.read(changed);check(c.oneSide()!=a.oneSide()&&c.oneSide().from()!=a.oneSide().from(),"changed source reused");
  var host=RoadRecord.load(source.getCompound("Road"));var parts=new ArrayList<>(host.structures());var part=parts.get(0);parts.set(0,new RoadStructures.Part(part.a(),part.b(),part.width()+.01,part.height(),part.pier(),part.material(),part.frameA(),part.frameB(),part.model(),part.heights()));
  source.put("Road",host.structures(parts).save());var d=JunctionCodec.read(changed);check(d.oneSide()!=c.oneSide()&&d.oneSide().structures().get(0).width()!=c.oneSide().structures().get(0).width(),"changed structures reused");
  var lru=new RoadIndex.LongLru<String>(2);check(lru.size()==0&&lru.get(0L)==null,"empty cache");lru.put(-1L,"a");lru.put(0L,"b");lru.get(-1L);lru.put(1L,"c");check(lru.containsKey(-1L)&&!lru.containsKey(0L)&&lru.size()==2,"eviction order");lru.clear();lru.put(2L,null);check(lru.containsKey(2L)&&lru.size()==1,"null values");lru.computeIfAbsent(2L,k->"d");check(lru.get(2L).equals("d"),"compute after null");lru.clear();check(lru.entrySet().isEmpty(),"clear");
  check(count>1000,"missing road corpus");System.out.println("FIX6_MEMORY_CHECKS_PASS roadRoundtrips="+count+" cacheChecks=8");
 }
}
