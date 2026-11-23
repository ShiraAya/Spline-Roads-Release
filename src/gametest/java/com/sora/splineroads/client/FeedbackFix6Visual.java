package com.sora.splineroads.client;
import java.nio.file.*;
import java.util.*;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.world.*;
import com.sora.splineroads.net.RoadNetwork;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Real interchange button -> pending receipt -> play while working -> published layout. */
@Mod.EventBusSubscriber(modid="splineroads",value=Dist.CLIENT)
public final class FeedbackFix6Visual {
 static final Path OUT=Path.of(Boolean.getBoolean("road.feedbackFix6Shots")?"visual-fix6-final":"visual-fix6");static UUID id;
 static boolean started,done,submitted,closed;static int ticks,stage,ready,activeTicks,shot;
 static long deadline,begin,last,maxGap;static volatile int responses;static volatile long maxAck;
 static volatile Throwable error;static volatile boolean busy,completed;static RoadIndex.Built before;
 static final String[] SHOTS={"03-stack-top","04-stack-overview","05-ramp-fork","06-stack-side"};
 static final double[][] CAMERAS={{4000,320,4000,4000,56,4001},{4240,230,3700,4000,65,4000},{3710,140,3970,3682,75,4044},{4150,95,3800,4000,65,4000}};
 static void check(boolean ok,String s){if(!ok)throw new AssertionError(s);}
 static void log(String s)throws Exception{System.out.println("FIX6_CLIENT "+s);Files.writeString(OUT.resolve("results.txt"),s+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
 static void screenshot(Minecraft mc,String name)throws Exception{try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(OUT.resolve(name+".png"));}log("SCREENSHOT "+name);}
 @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
  if(!Boolean.getBoolean("road.feedbackFix6Client")||done||event.phase!=TickEvent.Phase.END)return;
  var mc=Minecraft.getInstance();ticks++;long now=System.nanoTime();
  try{
   if(!started){if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;started=true;Files.createDirectories(OUT);deadline=now+900_000_000_000L;mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(24);mc.options.framerateLimit().set(60);mc.options.enableVsync().set(false);mc.options.cloudStatus().set(CloudStatus.OFF);org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(),1280,720);if(Boolean.getBoolean("road.feedbackFix6Shots")){stage=6;mc.options.hideGui=true;}mc.createWorldOpenFlows().loadLevel(mc.screen,"fix6-regression");return;}
   if(now>deadline)throw new AssertionError("timeout stage="+stage);if(error!=null)throw new AssertionError("server",error);
   if(mc.level==null||mc.player==null||mc.getOverlay()!=null)return;mc.mouseHandler.releaseMouse();mc.player.setDeltaMovement(0,0,0);
   if(submitted&&!completed){if(last!=0)maxGap=Math.max(maxGap,now-last);last=now;activeTicks++;if(ticks%5==0){long sent=now;mc.getSingleplayerServer().execute(()->{try{responses++;maxAck=Math.max(maxAck,System.nanoTime()-sent);var level=mc.getSingleplayerServer().overworld();if(RoadBuildJobs.busy(level))busy=true;else if(busy){var data=RoadData.get(level);check(data.index.roads.values().stream().filter(b->id.equals(b.record.assembly())).count()==18,"wrong final stack topology");check(data.index.roads.get(before.record.id())!=before,"no publication");completed=true;}}catch(Throwable failure){error=failure;}});}}
   if(ticks%200==0)log("stage="+stage+" ticks="+activeTicks+" responses="+responses+" error="+ClientRoads.error);
   if(ticks<ready)return;
   if(stage==0){mc.setScreen(null);FeedbackFix3Visual.camera(mc,3650,100,3940,4000,65,4000);mc.getSingleplayerServer().execute(()->{try{var server=mc.getSingleplayerServer();var p=server.getPlayerList().getPlayer(mc.player.getUUID());server.getPlayerList().op(p.getGameProfile());p.setGameMode(GameType.CREATIVE);p.getAbilities().flying=true;p.onUpdateAbilities();p.setItemInHand(InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(SplineRoads.INTERCHANGE.get()));var level=p.serverLevel();level.setDayTime(6000);level.setWeatherParameters(12000,0,false,false);var data=RoadData.get(level);id=UUID.fromString("0dc19f23-71f0-483d-a991-5c5f7aced2fd");before=data.index.roads.values().stream().filter(b->id.equals(b.record.assembly())).findFirst().orElseThrow();}catch(Throwable failure){error=failure;}});stage=1;ready=ticks+180;return;}
   if(stage==1){mc.getSingleplayerServer().execute(()->{try{var p=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());var data=RoadData.get(p.serverLevel());var saved=Interchanges.descriptor(data,id);RoadNetwork.open(p,Interchanges.payload(p.serverLevel(),p,saved.getLongArray("Points"),id));}catch(Throwable failure){error=failure;}});stage=2;return;}
   if(stage==2){if(!(mc.screen instanceof InterchangeScreen screen))return;var button=screen.children().stream().filter(w->w instanceof Button b&&b.getMessage().getString().equals("整体更新")&&b.active).map(w->(Button)w).findFirst();if(button.isEmpty())return;screenshot(mc,"01-interchange-update");begin=now;last=now;submitted=true;button.get().onPress();stage=3;return;}
   if(stage==3){if(mc.screen!=null){check(now-begin<15_000_000_000L,"editor did not close");return;}closed=true;log("PENDING_CLOSED_EDITOR elapsedMs="+(now-begin)/1e6);FeedbackFix3Visual.camera(mc,3710,110,3910,4000,65,4000);stage=4;ready=ticks+150;return;}
   if(stage==4){check(busy&&responses>20,"server stopped responding during build");screenshot(mc,"02-playing-during-interchange-build");mc.getConnection().sendCommand("time query daytime");stage=5;return;}
   if(stage==5){if(!completed)return;check(closed&&activeTicks>100&&ClientRoads.error.isBlank(),"failed background result");log("NETWORK_PASS clientTicks="+activeTicks+" serverResponses="+responses+" maxClientGapMs="+maxGap/1e6+" maxServerAckMs="+maxAck/1e6+" elapsedMs="+(now-begin)/1e6);stage=6;mc.options.hideGui=true;}
   if(stage==6){if(shot==0&&Boolean.getBoolean("road.feedbackFix6Shots"))mc.getSingleplayerServer().execute(()->{var p=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());p.setGameMode(GameType.CREATIVE);p.getAbilities().flying=true;p.onUpdateAbilities();});if(shot>0)screenshot(mc,SHOTS[shot-1]);if(shot==CAMERAS.length){log("PASS");done=true;mc.stop();return;}var c=CAMERAS[shot++];mc.setScreen(null);FeedbackFix3Visual.camera(mc,c[0],c[1],c[2],c[3],c[4],c[5]);ready=ticks+260;}
  }catch(Throwable failure){done=true;failure.printStackTrace();try{log("FAIL "+failure);}catch(Exception ignored){}mc.stop();}
 }
}
