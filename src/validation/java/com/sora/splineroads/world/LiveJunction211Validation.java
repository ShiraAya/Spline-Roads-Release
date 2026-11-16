package com.sora.splineroads.world;
import java.nio.file.*;import java.util.*;import net.minecraft.nbt.*;import com.sora.splineroads.core.*;
public final class LiveJunction211Validation {
 public static void main(String[] args)throws Exception{
  var root=NbtIo.readCompressed(Path.of(args[0]).toFile()).getCompound("data");var specs=new LinkedHashMap<JunctionSpec,Integer>();
  for(var v:root.getList("Roads",10)){var j=((CompoundTag)v).getCompound("Junction");if(j.contains("Version")){var s=JunctionCodec.read(j);if(JunctionSlips.enabled(s))specs.put(s,0);}}
  if(specs.isEmpty())throw new AssertionError("no right slip fixtures");var f=JunctionPlanner.class.getDeclaredField("CACHE");f.setAccessible(true);var cache=f.get(null);var cf=cache.getClass().getDeclaredField("values");cf.setAccessible(true);var count=cache.getClass().getDeclaredField("bytes");count.setAccessible(true);var bytes=JunctionPlanner.class.getDeclaredMethod("planBytes",JunctionPlanner.Plan.class);bytes.setAccessible(true);
  var held=new ArrayList<JunctionPlanner.Ref>();var resolved=new ArrayList<JunctionPlanner.Piece>();long start=System.nanoTime();for(var spec:specs.keySet()){var ref=new JunctionPlanner.Ref(spec,0);held.add(ref);resolved.add(ref.get());System.out.println("plan_bytes="+bytes.invoke(null,JunctionPlanner.plan(spec))+" pieces="+JunctionPlanner.plan(spec).pieces().size());}double cold=(System.nanoTime()-start)/1e6;
  synchronized(cache){((Map<?,?>)cf.get(cache)).clear();count.setLong(cache,0);}
  System.gc();start=System.nanoTime();int identical=0;for(int i=0;i<held.size();i++){var ref=new JunctionPlanner.Ref(held.get(i).spec(),0,held.get(i).geometryVersion());if(ref.get()==resolved.get(i))identical++;if(!ref.get().equals(resolved.get(i)))throw new AssertionError("geometry changed");}
  double reload=(System.nanoTime()-start)/1e6;boolean original=args.length>1&&args[1].equals("original");if(original?identical==held.size():identical!=held.size())throw new AssertionError("unexpected plan retention "+identical);
  System.out.println("LIVE_JUNCTION211 "+(original?"NEGATIVE_CONTROL":"PASS")+" junctions="+held.size()+" exact_geometry=true reused="+identical+" cold_ms="+cold+" after_strong_cache_eviction_ms="+reload);
 }
}
