package com.sora.splineroads.client;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.world.*;
import java.nio.file.*;import java.util.*;import java.util.concurrent.*;
import net.minecraft.client.*;import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.flat.*;
import net.minecraftforge.api.distmarker.Dist;import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid=SplineRoads.ID,value=Dist.CLIENT)
public final class Performance211Visual {
  static final Path OUT=Path.of("performance211");
  static boolean started,done,built;static int step=-1,ticks,ready;static long deadline,totalStart,opStart,worldStart;
  static CompletableFuture<?> pending;static Performance211Scene scene;
  static void log(String s)throws Exception{System.out.println("PERF211 "+s);Files.writeString(OUT.resolve("results.txt"),s+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
  static String fixtureHash()throws Exception{var digest=java.security.MessageDigest.getInstance("SHA-256");for(var c:List.of(Performance211Scene.class,Performance211Visual.class,Performance211Review.class))try(var stream=c.getResourceAsStream("/"+c.getName().replace('.','/')+".class")){digest.update(Objects.requireNonNull(stream).readAllBytes());}return HexFormat.of().formatHex(digest.digest());}
  static WorldDimensions dimensions(net.minecraft.core.RegistryAccess r){var flat=r.registryOrThrow(Registries.FLAT_LEVEL_GENERATOR_PRESET).getOrThrow(FlatLevelGeneratorPresets.REDSTONE_READY).settings();return r.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions().replaceOverworldGenerator(r,new FlatLevelSource(flat));}
  @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e){
    if(!"performance".equals(System.getProperty("road.feedback211Resume"))||done||e.phase!=TickEvent.Phase.END)return;
    var mc=Minecraft.getInstance();ticks++;
    try{
      if(!started){if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;started=true;Files.createDirectories(OUT);worldStart=System.nanoTime();log("WORLD_START_UTC "+java.time.Instant.now());log("FIXTURE_SHA256 "+fixtureHash());deadline=System.nanoTime()+7200_000_000_000L;mc.options.pauseOnLostFocus=false;mc.options.enableVsync().set(false);mc.options.framerateLimit().set(260);mc.options.renderDistance().set(12);mc.options.cloudStatus().set(CloudStatus.OFF);if(Files.exists(OUT.resolve("resume-world.txt"))){mc.createWorldOpenFlows().loadLevel(mc.screen,Files.readString(OUT.resolve("resume-world.txt")).trim());return;}mc.createWorldOpenFlows().createFreshLevel("sr211-sketch-"+System.currentTimeMillis(),new LevelSettings("SR211 Redstone sketch baseline",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(2111116,false,false),Performance211Visual::dimensions);return;}
      if(System.nanoTime()>deadline)throw new AssertionError("timeout step="+step);
      if(mc.level==null||mc.player==null||mc.getOverlay()!=null||ticks<ready)return;
      mc.setScreen(null);mc.mouseHandler.releaseMouse();mc.player.getAbilities().flying=true;mc.player.setNoGravity(true);mc.player.setDeltaMovement(0,0,0);
      if(built){if(Performance211Review.tick(mc,scene.ground())){log("WORLD_BUILD_AND_REVIEW_MS "+(System.nanoTime()-worldStart)/1e6);log("REVIEW_COMPLETE");done=true;mc.stop();}return;}
      if(scene==null){if(pending==null){pending=mc.getSingleplayerServer().submit(()->{var l=mc.getSingleplayerServer().overworld();l.setDayTime(6000);l.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,mc.getSingleplayerServer());return new Performance211Scene(l);});return;}if(!pending.isDone())return;scene=(Performance211Scene)pending.join();pending=null;totalStart=System.nanoTime();if(Files.exists(OUT.resolve("resume-world.txt")))step=scene.restore(net.minecraft.nbt.NbtIo.readCompressed(OUT.resolve("state.nbt").toFile()))-1;log("WORLD preset=redstone_ready ground="+scene.ground()+" resumed="+(step>=0));}
      if(pending!=null){if(!pending.isDone())return;pending.join();net.minecraft.nbt.NbtIo.writeCompressed(scene.checkpoint(step+1),OUT.resolve("state.nbt").toFile());log(String.format(Locale.ROOT,"OP %02d %s roundtrip_ms=%.3f",step,scene.steps().get(step).name(),(System.nanoTime()-opStart)/1e6));pending=null;ready=ticks+10;return;}
      step++;if(step>=scene.steps().size()){log("BUILD_TOTAL_MS "+(System.nanoTime()-totalStart)/1e6);log("WORLD_AND_BUILD_TOTAL_MS "+(System.nanoTime()-worldStart)/1e6);log("BUILD_END_UTC "+java.time.Instant.now());built=true;return;}
      log("START "+step+" "+scene.steps().get(step).name());opStart=System.nanoTime();pending=mc.getSingleplayerServer().submit(()->{long t=System.nanoTime();scene.steps().get(step).action().run();try{log("SERVER_EXEC_MS "+step+" "+(System.nanoTime()-t)/1e6);}catch(Exception ex){throw new RuntimeException(ex);}});
    }catch(Throwable fail){done=true;fail.printStackTrace();try{log("FAIL step="+step+" "+fail);}catch(Exception ignored){}mc.stop();}
  }
}
