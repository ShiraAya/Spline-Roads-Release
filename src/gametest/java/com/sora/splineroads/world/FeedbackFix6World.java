package com.sora.splineroads.world;

import java.util.*;
import java.util.concurrent.*;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Explicit isolated-world regression for every saved auto-interchange family. */
@Mod.EventBusSubscriber(modid="splineroads")
public final class FeedbackFix6World {
 static int ticks,stage,startedTicks,slot;static boolean done;static long revision,begin;
 static CompletableFuture<String> future;static CompoundTag command;static Map<UUID,CompoundTag> before;
 static List<UUID> existing;static UUID stack;
 static void check(boolean ok,String s){if(!ok)throw new AssertionError(s);}
 static void submit(net.minecraft.server.level.ServerLevel level){
  var data=RoadData.get(level);revision=data.index.contentRevision();startedTicks=ticks;begin=System.nanoTime();
  future=RoadBuildJobs.submit(level,null,ignored->{check(Thread.currentThread().getName().equals("SR-world-planner"),"wrong planner thread");return Interchanges.build(level,null,command);});
  System.out.println("FIX6_SUBMITTED stage="+stage+" submitMs="+(System.nanoTime()-begin)/1e6);
 }
 @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event){
  if(!Boolean.getBoolean("road.feedbackFix6")||done||event.phase!=TickEvent.Phase.END||++ticks<60)return;
  var server=event.getServer();var level=server.overworld();var data=RoadData.get(level);
  try{
   if(stage==0){
    existing=data.interchanges.values().stream().filter(t->Set.of("TURBINE","TRUMPET","SPUI").contains(t.getCompound("Options").getString("Preset"))).map(t->t.getUUID("Id")).toList();
    check(existing.size()==3,"missing original fixtures");before=new HashMap<>();data.interchanges.forEach((id,t)->before.put(id,t.copy()));
    var points=List.of(new BlockPos(3500,75,4000),new BlockPos(4500,75,4000),new BlockPos(4000,56,3500),new BlockPos(4000,56,4500));
    for(var pos:points){level.setBlock(pos,SplineRoads.NODE.get().defaultBlockState(),18);var node=(NodeEntity)level.getBlockEntity(pos);node.owner=new UUID(0,0);node.heightExplicit=true;node.offsetY=.125;node.setChanged();}
    var existingStack=data.interchanges.values().stream().filter(t->t.getCompound("Options").getString("Preset").equals("STACK")).findFirst();
    command=Interchanges.payload(level,null,points.stream().mapToLong(BlockPos::asLong).toArray(),existingStack.map(t->t.getUUID("Id")).orElse(null));
    command.put("Options",Interchanges.write(new InterchangePlanner.Options(InterchangePlanner.Preset.STACK,false,1,64,32,5,1)));
    submit(level);future.cancel(false);stage=1;return;
   }
   if(stage==1){if(RoadBuildJobs.busy(level))return;check(data.index.contentRevision()==revision&&data.interchanges.equals(before),"cancel mutated world data");System.out.println("FIX6_CANCEL_PASS");stage=2;submit(level);return;}
   if(!future.isDone()){check(data.index.contentRevision()==revision,"partial publication during planning");if(ticks-startedTicks>18000)throw new AssertionError("timeout");return;}
   System.out.println("FIX6_ASYNC_PASS stage="+stage+" ticks="+(ticks-startedTicks)+" ms="+(System.nanoTime()-begin)/1e6+" result="+future.join());
   check(ticks-startedTicks>5,"server did not tick during build");
   if(stage==2){stack=command.hasUUID("Id")?command.getUUID("Id"):data.interchanges.keySet().stream().filter(id->!before.containsKey(id)).findFirst().orElseThrow();check(data.index.roads.values().stream().filter(b->stack.equals(b.record.assembly())).count()==18,"stack roads missing");stage=3;}
   if(slot<existing.size()){
    UUID id=existing.get(slot++);var saved=data.interchanges.get(id);command=Interchanges.payload(level,null,saved.getLongArray("Points"),id);submit(level);return;
   }
   int ramps=0;for(var b:data.index.roads.values())if(stack.equals(b.record.assembly())){RoadGrades.validate(b.mesh);if(b.mesh.settings().style().ramp())ramps++;}
   check(ramps==16,"stack ramps incomplete");System.out.println("FIX6_WORLD_PASS cases=5 stack="+stack+" totalRoads="+data.index.roads.size());done=true;server.halt(false);
  }catch(Throwable failure){done=true;System.out.println("FIX6_WORLD_FAIL stage="+stage);failure.printStackTrace();server.halt(false);}
 }
}
