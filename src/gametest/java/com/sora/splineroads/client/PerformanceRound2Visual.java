package com.sora.splineroads.client;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.world.Performance211Scene;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;
import jdk.jfr.Recording;
import java.time.Duration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Separate, explicitly diagnostic replay. The frozen fresh-build fixture is unchanged. */
@Mod.EventBusSubscriber(modid=SplineRoads.ID,value=Dist.CLIENT)
public final class PerformanceRound2Visual {
  static final Path OUT=Path.of("round2");
  static boolean started,done;static int ready,age;static CompletableFuture<?> pending;
  @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
    if(!"performance-round2".equals(System.getProperty("road.feedback211Resume"))||done||event.phase!=TickEvent.Phase.END)return;
    var mc=Minecraft.getInstance();age++;
    try{
      if(!started){
        if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;
        started=true;mc.options.pauseOnLostFocus=false;mc.options.enableVsync().set(false);
        mc.options.renderDistance().set(12);mc.options.framerateLimit().set(260);
        mc.createWorldOpenFlows().loadLevel(mc.screen,Files.readString(OUT.resolve("world.txt")).trim());return;
      }
      if(age>18000)throw new AssertionError("Diagnostic replay timeout");
      if(mc.level==null||mc.player==null||mc.getOverlay()!=null)return;
      mc.setScreen(null);mc.mouseHandler.releaseMouse();
      mc.player.getAbilities().flying=true;mc.player.setNoGravity(true);mc.player.setDeltaMovement(0,0,0);
      if(++ready<200)return;
      if(pending==null){pending=mc.getSingleplayerServer().submit(()->{
        try{
          var scene=new Performance211Scene(mc.getSingleplayerServer().overworld());
          scene.restore(net.minecraft.nbt.NbtIo.readCompressed(OUT.resolve("state.nbt").toFile()));
          for(String token:Files.readString(OUT.resolve("steps.txt")).trim().split(",")){
            int step=Integer.parseInt(token.trim());var operation=scene.steps().get(step);
            try(var recording=new Recording()){
              recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(4));
              recording.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(4));
              recording.enable("jdk.ObjectAllocationSample").withStackTrace().with("throttle","200/s");
              recording.enable("jdk.GCPhasePause").withThreshold(Duration.ZERO);
              recording.setMaxSize(48L<<20);recording.start();
              long start=System.nanoTime();System.out.println("ROUND2 START "+step+" "+operation.name());
              try{operation.action().run();}
              finally{recording.stop();recording.dump(OUT.resolve("step-"+step+".jfr"));}
              String line="ROUND2 OP "+step+" "+operation.name()+" ms="+(System.nanoTime()-start)/1e6;
              System.out.println(line);Files.writeString(OUT.resolve("results.txt"),line+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            }
          }
          Files.writeString(OUT.resolve("done.txt"),"Diagnostic replay complete\n");
        }catch(Exception error){throw new RuntimeException(error);}
      });return;}
      if(!pending.isDone())return;pending.join();done=true;mc.stop();
    }catch(Throwable error){error.printStackTrace();done=true;mc.stop();}
  }
}
