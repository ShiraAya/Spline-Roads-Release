package com.sora.splineroads.client;
import java.nio.file.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Automated travel in a disposable copy, with screenshots and queue receipts. */
@Mod.EventBusSubscriber(modid="splineroads",value=Dist.CLIENT)
public final class Feedback212Visual {
 static final Path OUT=Path.of("visual212");
 static boolean started,done;static int ticks,stage,ready;static long deadline;
 static void log(String s)throws Exception{System.out.println("FIX1_CLIENT "+s);Files.writeString(OUT.resolve("results.txt"),s+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
 static void camera(Minecraft mc,double x,double y,double z,double tx,double ty,double tz){
  float yaw=(float)Math.toDegrees(Math.atan2(x-tx,tz-z)),pitch=(float)Math.toDegrees(Math.atan2(y-ty,Math.hypot(x-tx,z-tz)));
  mc.getSingleplayerServer().execute(()->{var p=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);p.teleportTo(p.serverLevel(),x,y,z,yaw,pitch);});
  mc.player.setPos(x,y,z);mc.player.setYRot(yaw);mc.player.yRotO=yaw;mc.player.setXRot(pitch);mc.player.xRotO=pitch;
 }
 static void shot(Minecraft mc,String name)throws Exception{try(var i=Screenshot.takeScreenshot(mc.getMainRenderTarget())){i.writeToFile(OUT.resolve(name+".png"));}log("SCREENSHOT "+name);}
 @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e){
  if(!Boolean.getBoolean("road.feedback212Client")||done||e.phase!=TickEvent.Phase.END)return;var mc=Minecraft.getInstance();ticks++;
  try{
   if(!started){if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;started=true;Files.createDirectories(OUT);deadline=System.nanoTime()+900_000_000_000L;mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(8);mc.options.framerateLimit().set(60);mc.options.enableVsync().set(false);mc.options.hideGui=true;mc.options.cloudStatus().set(CloudStatus.OFF);mc.createWorldOpenFlows().loadLevel(mc.screen,"world");return;}
   if(System.nanoTime()>deadline)throw new AssertionError("timeout stage="+stage);
   if(mc.level==null||mc.player==null||mc.getOverlay()!=null)return;
   mc.mouseHandler.releaseMouse();mc.player.setDeltaMovement(0,0,0);
   if(ticks%100==0)log("stage="+stage+" "+RoadTerrainModels.status()+" "+RoadRenderer.uploadMetrics()+" "+ClientRoads.cacheMetrics()+" error="+ClientRoads.error);
   if(ticks<ready)return;
   if(stage==0){mc.setScreen(null);com.sora.splineroads.config.RoadClientConfig.SURFACE_BACKEND.set(com.sora.splineroads.config.RoadClientConfig.SurfaceBackend.AUTO);
    if(Boolean.getBoolean("road.feedback212CutVisual")){camera(mc,1008,40,790,1010,41,800);stage=12;ready=ticks+500;return;}
    camera(mc,2628,120,-161,2700,56,-95);stage=1;ready=ticks+500;return;}
   if(stage==1){shot(mc,"01-initial");camera(mc,-2000,110,3000,-1950,56,3000);stage=2;ready=ticks+350;return;}
   if(stage==2){camera(mc,2628,120,-161,2700,56,-95);stage=3;ready=ticks+100;return;}
   if(stage==3){shot(mc,"02-return-5s");stage=4;ready=ticks+300;return;}
   if(stage==4){shot(mc,"03-return-20s");com.sora.splineroads.config.RoadClientConfig.SURFACE_BACKEND.set(com.sora.splineroads.config.RoadClientConfig.SurfaceBackend.TERRAIN);stage=5;ready=ticks+500;return;}
   if(stage==5){shot(mc,"04-terrain");
    com.sora.splineroads.config.RoadClientConfig.COLD_ROAD_CACHE_MIB.set(0);
    com.sora.splineroads.config.RoadClientConfig.COLD_MESH_CACHE_MIB.set(0);
    com.sora.splineroads.config.RoadClientConfig.UNLOADED_VBO_CACHE_MIB.set(0);
    com.sora.splineroads.config.RoadClientConfig.TERRAIN_CACHE_MIB.set(0);
    camera(mc,-2000,110,3000,-1950,56,3000);stage=6;ready=ticks+350;return;}
   if(stage==6){camera(mc,2628,120,-161,2700,56,-95);stage=7;ready=ticks+400;return;}
   if(stage==7){shot(mc,"05-terrain-return");
    if(!ClientRoads.INDEX.roads.containsKey(java.util.UUID.fromString("f04c26eb-af8c-4121-ab96-8654e73e2021")))throw new AssertionError("host source missing after eviction/return");
    if(!ClientRoads.error.isBlank()||RoadTerrainModels.stats().queued()!=0)throw new AssertionError("return queues not drained: "+ClientRoads.error+" "+RoadTerrainModels.status());
    log("EVICTION_RETURN_PASS");camera(mc,2540,76,-42,2548,56,-62);stage=8;ready=ticks+400;return;}
   if(stage==8){shot(mc,"06-junction-port");camera(mc,380,80,90,388,65,69);stage=9;ready=ticks+400;return;}
   if(stage==9){shot(mc,"07-bridge");camera(mc,-6,95,1412,0,56,1470);stage=10;ready=ticks+400;return;}
   if(stage==10){shot(mc,"08-interchange");camera(mc,1058,100,1079,1150,56,1085);stage=11;ready=ticks+400;return;}
   if(stage==11){shot(mc,"09-sidewalks");camera(mc,1008,40,790,1010,41,800);stage=12;ready=ticks+400;return;}
   if(stage==12){shot(mc,"10-tunnel");camera(mc,1314,61,596,1310,56,610);stage=13;ready=ticks+400;return;}
   if(stage==13){shot(mc,"11-open-cut");camera(mc,1528,72,669,1510,56,690);stage=14;ready=ticks+400;return;}
   if(stage==14){shot(mc,"12-buried-walks");log(Boolean.getBoolean("road.feedback212CutVisual")?"DONE cut visual":"DONE eviction return PASS");done=true;mc.stop();}
  }catch(Throwable failure){done=true;failure.printStackTrace();try{log("FAIL "+failure);}catch(Exception ignored){}mc.stop();}
 }
}
