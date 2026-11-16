package com.sora.splineroads.client;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.core.*;
import com.sora.splineroads.world.*;
import com.sora.splineroads.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Isolated native inspection of the second screenshot report; never packaged. */
@Mod.EventBusSubscriber(modid=SplineRoads.ID,value=Dist.CLIENT)
public final class Second211Visual {
 static final Path OUT=Path.of("second211");static boolean started,done;static int ticks,stage,ready;static long deadline;static CompletableFuture<?> pending;
 static void log(String s)throws Exception{System.out.println("SR211 SECOND "+s);Files.writeString(OUT.resolve("results.txt"),s+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
 static void next(int n){stage=n;ready=ticks+160;}
 static void shot(Minecraft mc,String name)throws Exception{try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(OUT.resolve(name+".png"));}log("SCREENSHOT "+name);}
 @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e){
  if(!"second".equals(System.getProperty("road.feedback211Resume"))||done||e.phase!=TickEvent.Phase.END)return;var mc=Minecraft.getInstance();ticks++;
  try{
   if(!started){if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;started=true;Files.createDirectories(OUT);deadline=System.nanoTime()+1800_000_000_000L;
    mc.options.pauseOnLostFocus=false;mc.options.cloudStatus().set(CloudStatus.OFF);mc.options.renderDistance().set(14);mc.options.framerateLimit().set(60);mc.options.enableVsync().set(false);mc.options.guiScale().set(2);mc.resizeDisplay();mc.createWorldOpenFlows().loadLevel(mc.screen,"sr211-second");log("OPEN ORIGINAL WORLD COPY");return;}
   if(System.nanoTime()>deadline)throw new AssertionError("timeout stage="+stage);
   if(mc.level==null||mc.player==null||mc.getOverlay()!=null||ticks<ready)return;
   mc.mouseHandler.releaseMouse();mc.player.setDeltaMovement(0,0,0);mc.player.getAbilities().flying=true;mc.player.setNoGravity(true);
   if(stage==0){mc.setScreen(null);mc.options.hideGui=false;mc.getSingleplayerServer().execute(()->Feedback211Visual.player(mc).setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(SplineRoads.CONNECTOR.get())));Feedback211Visual.camera(mc,-1601,66,1109,-1601,56,1123);next(1);ready=ticks+400;return;}
   if(stage==1){if(!ClientRoads.INDEX.internalInterchangeNode(new net.minecraft.core.BlockPos(-1601,56,1123)))throw new AssertionError("client seam not hidden");shot(mc,"01-internal-marker-hidden");mc.options.hideGui=true;Feedback211Visual.camera(mc,-9,68,722,-9,63,755);next(2);return;}
   if(stage==2){shot(mc,"02-loop-underpass-dashes");Feedback211Visual.camera(mc,999,76,1208,940,66,1212);next(3);return;}
   if(stage==3){shot(mc,"03-outer-transition-dashes");if(Files.exists(OUT.resolve("paint-only"))){log("PAINT NATIVE RECHECK COMPLETE");done=true;mc.stop();return;}Feedback211Visual.camera(mc,867,79,1165,880,61,1212);next(4);return;}
   if(stage==4){shot(mc,"04-spui-before");pending=mc.getSingleplayerServer().submit(()->{var l=Feedback211Visual.player(mc).serverLevel();Second211ScenarioGameTests.rebuildSpui(l);});next(5);return;}
   if(stage==5){if(!pending.isDone())return;pending.join();log("SPUI NATIVE SAVE PASS");next(6);ready=ticks+400;return;}
   if(stage==6){shot(mc,"05-spui-compact-after");mc.options.hideGui=false;pending=mc.getSingleplayerServer().submit(()->{var p=Feedback211Visual.player(mc);RoadTool.openRoad(p,RoadData.get(p.serverLevel()).index.roads.get(UUID.fromString("b82e1f44-eebe-3d18-947b-892dd6d94985")).record,false);});next(7);return;}
   if(stage==7){if(!pending.isDone())return;pending.join();if(!(mc.screen instanceof RoadScreen))throw new AssertionError("road editor not opened");
    // Replay the actual refusal diagnostic through the production split result
    // transport, with a real editor receipt; no synthetic world geometry.
    var f=ClientRoads.class.getDeclaredField("editRequests");f.setAccessible(true);var requests=(RoadEditRequests)f.get(null);var command=new CompoundTag();command.putString("Action","secondDiagnosticReplay");var request=requests.submit(mc.screen,command.getString("Action"),"");RoadActionContext.attach(command,request);
    pending=mc.getSingleplayerServer().submit(()->{try{var method=RoadNetwork.class.getDeclaredMethod("result",net.minecraft.server.level.ServerPlayer.class,boolean.class,String.class,CompoundTag.class);method.setAccessible(true);method.invoke(null,Feedback211Visual.player(mc),false,"新道路与已有匝道设施冲突；冲突道路 c3802cbe-8ab8-3d57-920f-1defe718edee，坐标 911.24 55.97 1227.36",command);}catch(Exception failure){throw new RuntimeException(failure);}});next(8);return;}
   if(stage==8){if(!pending.isDone())return;pending.join();if(!ClientRoads.conflictPreview||ClientRoads.nodePreviews.isEmpty())throw new AssertionError("split diagnostic did not display conflict geometry");shot(mc,"06-conflict-editor");mc.screen.onClose();next(9);return;}
   if(stage==9){shot(mc,"07-conflict-world");log("SECOND NATIVE PASS marker, dashes, compact save, diagnostic transport replay");done=true;mc.stop();}
  }catch(Throwable f){done=true;f.printStackTrace();try{log("FAIL stage="+stage+" "+f);Files.writeString(OUT.resolve("failure.txt"),f.toString());}catch(Exception ignored){}mc.stop();}
 }
}
