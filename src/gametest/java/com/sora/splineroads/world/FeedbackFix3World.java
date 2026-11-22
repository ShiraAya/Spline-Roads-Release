package com.sora.splineroads.world;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sora.splineroads.core.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid="splineroads")
public final class FeedbackFix3World {
 static int ticks,completedTick;static boolean started,done;static long last,maxGap,begin,worldTime;static ScheduledExecutorService probes;static Runnable verify;
 static final AtomicLong acknowledgements=new AtomicLong(),maxAck=new AtomicLong();
 @SubscribeEvent public static void tick(TickEvent.ServerTickEvent e){
  if(!Boolean.getBoolean("road.feedbackFix3")||Boolean.getBoolean("road.feedbackFix3Safety")||done||e.phase!=TickEvent.Phase.END)return;
  long now=System.nanoTime();if(started&&last!=0)maxGap=Math.max(maxGap,now-last);last=now;ticks++;
  var server=e.getServer();var level=server.overworld();
  if(started){if(verify!=null&&ticks>=completedTick+20){verify.run();return;}if(ticks%100==0)System.out.println("FIX3_HEARTBEAT ticks="+ticks+" acknowledgements="+acknowledgements.get()+" maxGapMs="+maxGap/1e6);if(now-begin>600_000_000_000L){done=true;System.out.println("FIX3_FAIL timeout");server.halt(false);}return;}
  if(ticks==1)RoadData.get(level);
  if(ticks<100)return;started=true;begin=now;last=now;worldTime=level.getGameTime();
  var data=RoadData.get(level);var id=UUID.fromString("ada9bac3-ed47-4c4a-9abe-45b06cf3bbf0");var r=data.editableRoad(id);
  var links=new HashMap<UUID,LanePoints.Link>();for(var b:data.index.roads.values())if(LaneTopology.metadata(b.record).link()!=null)links.put(b.record.id(),LaneTopology.metadata(b.record).link());
  probes=Executors.newSingleThreadScheduledExecutor(task->{var thread=new Thread(task,"fix3-heartbeat");thread.setDaemon(true);return thread;});
  probes.scheduleAtFixedRate(()->{long sent=System.nanoTime();server.execute(()->{acknowledgements.incrementAndGet();maxAck.accumulateAndGet(System.nanoTime()-sent,Math::max);});},0,250,TimeUnit.MILLISECONDS);
  var distant=level.getChunkSource().getChunkFuture(320,320,net.minecraft.world.level.chunk.ChunkStatus.FULL,true);
  System.out.println("FIX3_BEGIN road="+id+" roads="+data.index.roads.size());
  try{RoadBuildJobs.connect(level,null,r.a(),r.b(),r.settings(),id,false,false,false,List.of()).whenComplete((saved,failure)->{
    completedTick=ticks;verify=()->{
    try{
      if(failure!=null)throw new RuntimeException(failure);
      for(var entry:links.entrySet()){var kept=data.index.roads.get(entry.getKey());if(kept==null||!Objects.equals(LaneTopology.metadata(kept.record).link().from(),entry.getValue().from())||!Objects.equals(LaneTopology.metadata(kept.record).link().to(),entry.getValue().to()))throw new AssertionError("connection lost "+entry.getKey());}
      FeedbackFix2World.exposedPosts(data,"after");if(FeedbackFix2World.railDeckConflicts(data,"after")!=0)throw new AssertionError("rail slab regression");
      if(acknowledgements.get()<20||level.getGameTime()-worldTime<50||!distant.isDone())throw new AssertionError("server/chunk progress missing");
      System.out.println("FIX3_RESPONSIVENESS elapsedMs="+(System.nanoTime()-begin)/1e6+" ticks="+(level.getGameTime()-worldTime)+" acknowledgements="+acknowledgements.get()+" maxAckMs="+maxAck.get()/1e6+" maxGapMs="+maxGap/1e6+" distantChunk="+distant.isDone());
      if(maxGap>3_000_000_000L||maxAck.get()>3_000_000_000L)throw new AssertionError("save still causes a long server pause");
      System.out.println("FIX3_PASS");
    }catch(Throwable error){System.out.println("FIX3_FAIL");error.printStackTrace();}
    finally{done=true;probes.shutdownNow();server.halt(false);}
    };
  });}catch(Throwable error){done=true;probes.shutdownNow();System.out.println("FIX3_FAIL");error.printStackTrace();server.halt(false);}
 }
}
