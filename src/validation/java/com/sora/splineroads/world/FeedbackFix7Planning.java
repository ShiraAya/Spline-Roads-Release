package com.sora.splineroads.world;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
public final class FeedbackFix7Planning {
 public static void main(String[] args)throws Exception {
  var root=NbtIo.readCompressed(Path.of(args[0]).toFile()).getCompound("data");
  var roads=new HashMap<UUID,RoadRecord>();for(var t:root.getList("Roads",Tag.TAG_COMPOUND)){var r=RoadRecord.load((CompoundTag)t);roads.put(r.id(),r);}
  var a=roads.get(UUID.fromString("e2589901-56f4-4ba3-99d2-9a67b8a1379a"));
  var b=roads.get(UUID.fromString("3358edf8-f03b-4c96-b307-9417c5ab9e3b"));
  int failed=0;
  for(int permutation=0;permutation<8;permutation++)for(var preset:List.of(InterchangePlanner.Preset.STACK,InterchangePlanner.Preset.TURBINE)) {
   if(Boolean.getBoolean("sr.testAbove")&&preset==InterchangePlanner.Preset.STACK)continue;
   var aa=(permutation&4)==0?a:b;var bb=(permutation&4)==0?b:a;
   var ns=new Node[]{(permutation&1)==0?aa.start():aa.end(),(permutation&1)==0?aa.end():aa.start(),(permutation&2)==0?bb.start():bb.end(),(permutation&2)==0?bb.end():bb.start()};
   var options=new InterchangePlanner.Options(preset,Boolean.getBoolean("sr.testLeft"),1,96,20,5,1,5,true).above(Boolean.getBoolean("sr.testAbove"));
   long start=System.nanoTime();System.out.println("FIX7_BEGIN "+preset+" permutation="+permutation);
   try(var budget=RoadPlanningBudget.open("fix7 baseline",45)) {
    var payload=new CompoundTag();var nodes=new ListTag();for(var n:ns)nodes.add(RoadRecord.writeNode(n));
    payload.put("Nodes",nodes);payload.putLongArray("Points",new long[]{((permutation&1)==0?aa.a():aa.b()).asLong(),((permutation&1)==0?aa.b():aa.a()).asLong(),((permutation&2)==0?bb.a():bb.b()).asLong(),((permutation&2)==0?bb.b():bb.a()).asLong()});
    payload.put("Main1",RoadRecord.writeSettings(RoadProfile.editable(aa.settings())));payload.put("Main2",RoadRecord.writeSettings(RoadProfile.editable(bb.settings())));
    payload.put("Options",Interchanges.write(options));var sources=new ListTag();for(int i=0;i<2;i++){var source=new CompoundTag();source.putInt("Axis",i);source.put("Road",(i==0?aa:bb).header());sources.add(source);}
    payload.put("ContinuousSources",sources);payload.put("ContinuousLimits",nodes.copy());
    var plan=Interchanges.plan(payload);
    System.out.println("FIX7_PLAN_PASS "+preset+" ms="+(System.nanoTime()-start)/1e6+" legs="+plan.legs().size()+" radius="+plan.minRadius()+" anchors="+plan.anchors());
   } catch(Exception e){failed++;System.out.println("FIX7_PLAN_FAIL "+preset+" ms="+(System.nanoTime()-start)/1e6+" "+e);e.printStackTrace();}
  }
  if(failed>0)throw new AssertionError(failed+" planning cases failed");
 }
}
