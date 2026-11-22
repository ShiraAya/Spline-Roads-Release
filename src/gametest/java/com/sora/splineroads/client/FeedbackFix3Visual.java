package com.sora.splineroads.client;

import java.nio.file.*;
import java.util.*;
import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.world.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Exercises the actual save button, pending packet, player movement and receipt. */
@Mod.EventBusSubscriber(modid="splineroads",value=Dist.CLIENT)
public final class FeedbackFix3Visual {
 static final UUID ID=UUID.fromString("ada9bac3-ed47-4c4a-9abe-45b06cf3bbf0");static final Path OUT=Path.of("visual-fix3");
 static boolean started,done,submitted,closed;static int ticks,stage,ready,activeTicks;static long deadline,begin,last,maxGap,serverStart;static RoadIndex.Built before;
 static volatile boolean sawBusy,completed;static volatile long responses,maxResponse;static volatile Throwable error;
 static void log(String s)throws Exception{System.out.println("FIX3_CLIENT "+s);Files.writeString(OUT.resolve("results.txt"),s+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
 static void shot(Minecraft mc,String name)throws Exception{try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(OUT.resolve(name+".png"));}log("SCREENSHOT "+name);}
 static void camera(Minecraft mc,double x,double y,double z,double tx,double ty,double tz){float yaw=(float)Math.toDegrees(Math.atan2(x-tx,tz-z)),pitch=(float)Math.toDegrees(Math.atan2(y-ty,Math.hypot(x-tx,z-tz)));mc.getSingleplayerServer().execute(()->{var player=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());player.teleportTo(player.serverLevel(),x,y,z,yaw,pitch);});mc.player.setPos(x,y,z);mc.player.setYRot(yaw);mc.player.yRotO=yaw;mc.player.setXRot(pitch);mc.player.xRotO=pitch;}
 @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
  if(!Boolean.getBoolean("road.feedbackFix3Client")||done||event.phase!=TickEvent.Phase.END)return;var mc=Minecraft.getInstance();ticks++;long now=System.nanoTime();
  try{
   if(!started){if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;started=true;Files.createDirectories(OUT);deadline=now+900_000_000_000L;mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(6);mc.options.framerateLimit().set(60);mc.options.enableVsync().set(false);mc.options.cloudStatus().set(CloudStatus.OFF);mc.createWorldOpenFlows().loadLevel(mc.screen,"world");return;}
   if(now>deadline)throw new AssertionError("timeout stage="+stage);if(error!=null)throw new AssertionError("server action failed",error);
   if(mc.level==null||mc.player==null||mc.getOverlay()!=null)return;mc.mouseHandler.releaseMouse();mc.player.setDeltaMovement(0,0,0);
   if(submitted){if(last!=0)maxGap=Math.max(maxGap,now-last);last=now;activeTicks++;if(ticks%5==0){long sent=now;mc.getSingleplayerServer().execute(()->{try{responses++;maxResponse=Math.max(maxResponse,System.nanoTime()-sent);var level=mc.getSingleplayerServer().overworld();if(RoadBuildJobs.busy(level))sawBusy=true;else if(sawBusy){if(RoadData.get(level).index.roads.get(ID)==before)throw new AssertionError("network save did not publish road");completed=true;}}catch(Throwable failure){error=failure;}});}}
   if(ticks%100==0)log("stage="+stage+" busySeen="+sawBusy+" responses="+responses+" error="+ClientRoads.error);
   if(ticks<ready)return;
   if(stage==0){mc.setScreen(null);camera(mc,1207,65,1001,1200,56,998);mc.getSingleplayerServer().execute(()->{try{var server=mc.getSingleplayerServer();var player=server.getPlayerList().getPlayer(mc.player.getUUID());server.getPlayerList().op(player.getGameProfile());player.setGameMode(GameType.CREATIVE);player.getAbilities().flying=true;player.onUpdateAbilities();player.setItemInHand(InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(SplineRoads.CONNECTOR.get()));before=RoadData.get(player.serverLevel()).index.roads.get(ID);serverStart=player.serverLevel().getGameTime();}catch(Throwable failure){error=failure;}});stage=1;ready=ticks+250;return;}
   if(stage==1){mc.getSingleplayerServer().execute(()->{try{var player=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());RoadTool.openRoad(player,RoadData.get(player.serverLevel()).editableRoad(ID),false);}catch(Throwable failure){error=failure;}});stage=2;ready=ticks+100;return;}
   if(stage==2){if(!(mc.screen instanceof RoadScreen))return;var save=mc.screen.children().stream().filter(w->w instanceof Button b&&b.getMessage().getString().equals("建造 / 保存")&&b.active).map(w->(Button)w).findFirst();if(save.isEmpty())return;shot(mc,"01-save-editor");begin=now;last=now;submitted=true;save.get().onPress();stage=3;return;}
   if(stage==3){if(mc.screen==null){closed=true;log("PENDING_CLOSED_EDITOR elapsedMs="+(now-begin)/1e6);camera(mc,5000,90,5000,5010,60,5000);stage=4;ready=ticks+150;return;}if(now-begin>15_000_000_000L)throw new AssertionError("pending response did not close editor");return;}
   if(stage==4){if(!sawBusy||responses<20||mc.player.getX()<4900)throw new AssertionError("movement/server acknowledgements unavailable during build");shot(mc,"02-playing-during-save");mc.getConnection().sendCommand("time query daytime");camera(mc,1207,65,1001,1200,56,998);stage=5;return;}
   if(stage==5){if(!completed)return;ready=ticks+300;stage=6;return;}
   if(stage==6){shot(mc,"03-completed-save");if(!closed||activeTicks<100||responses<20||!ClientRoads.error.isBlank())throw new AssertionError("save interaction failed");log("PASS elapsedMs="+(now-begin)/1e6+" clientTicks="+activeTicks+" responses="+responses+" maxClientGapMs="+maxGap/1e6+" maxServerAckMs="+maxResponse/1e6);done=true;mc.stop();}
  }catch(Throwable failure){done=true;failure.printStackTrace();try{log("FAIL "+failure);}catch(Exception ignored){}mc.stop();}
 }
}
