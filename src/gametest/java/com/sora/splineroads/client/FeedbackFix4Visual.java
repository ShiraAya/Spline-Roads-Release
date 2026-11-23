package com.sora.splineroads.client;
import java.nio.file.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid="splineroads",value=Dist.CLIENT)
public final class FeedbackFix4Visual {
 static final Path OUT=Path.of("visual-fix4");static boolean started,done;static int ticks,stage,ready;static long deadline;
 static final String[] SHOTS={"03-ground-rail","04-terminal","05-ramp-profile","06-sidewalk-wall","07-tactile","08-asphalt"};
 static final double[][] CAMERAS={{-8,56.5,1452,-20,56.8,1452},{-10,56.6,1421,-14,56.7,1422},{1297,69,501,1333,55.5,525},{1229,56,954,1225,54,958},{1231,56,952,1221,56,942},{1247,77,959,1232,54,956}};
 static void log(String s)throws Exception{System.out.println("FIX4_CLIENT "+s);Files.writeString(OUT.resolve("results.txt"),s+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
 @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e){
  if(!Boolean.getBoolean("road.feedbackFix4Client")||done||e.phase!=TickEvent.Phase.END)return;var mc=Minecraft.getInstance();ticks++;
  try{
   if(!started){if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;started=true;Files.createDirectories(OUT);deadline=System.nanoTime()+900_000_000_000L;mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(8);mc.options.framerateLimit().set(60);mc.options.enableVsync().set(false);mc.options.hideGui=true;mc.options.cloudStatus().set(CloudStatus.OFF);mc.createWorldOpenFlows().loadLevel(mc.screen,"world");return;}
   if(System.nanoTime()>deadline)throw new AssertionError("timeout stage="+stage);
   if(mc.level==null||mc.player==null||mc.getOverlay()!=null)return;
   mc.mouseHandler.releaseMouse();mc.player.setDeltaMovement(0,0,0);if(ticks<ready)return;
   if(stage>0){try(var shot=Screenshot.takeScreenshot(mc.getMainRenderTarget())){shot.writeToFile(OUT.resolve(SHOTS[stage-1]+".png"));}log("SCREENSHOT "+SHOTS[stage-1]+" "+RoadTerrainModels.status());if(!ClientRoads.error.isBlank())throw new AssertionError(ClientRoads.error);}
   if(stage==CAMERAS.length){log("PASS");done=true;mc.stop();return;}
   if(stage==0){org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(),1280,720);mc.getSingleplayerServer().execute(()->{var level=mc.getSingleplayerServer().overworld();level.setDayTime(6000);level.setWeatherParameters(12000,0,false,false);});}
   mc.setScreen(null);var c=CAMERAS[stage++];Feedback212Visual.camera(mc,c[0],c[1],c[2],c[3],c[4],c[5]);ready=ticks+300;
  }catch(Throwable failure){done=true;failure.printStackTrace();try{log("FAIL "+failure);}catch(Exception ignored){}mc.stop();}
 }
}
