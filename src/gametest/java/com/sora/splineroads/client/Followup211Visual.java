package com.sora.splineroads.client;

import com.sora.splineroads.SplineRoads;
import com.sora.splineroads.world.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Native regression fixture; loaded only by the opt-in development client. */
@Mod.EventBusSubscriber(modid=SplineRoads.ID,value=Dist.CLIENT)
public final class Followup211Visual {
 static final Path OUT=Path.of("followup211");static boolean started,done;static int ticks,stage,ready;static long deadline;static CompletableFuture<?> pending;
 static Map<?,?> cachedRoads;
 static Object field(Object value,String name)throws Exception{var f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
 static void manual(Minecraft mc,String action){var t=new net.minecraft.nbt.CompoundTag();t.putString("Action",action);t.putUUID("PlannerSession",ManualPlannerClient.map.session);t.putInt("Tool",2);t.putInt("Arms",4);t.putDouble("X",-120);t.putDouble("Y",80);t.putDouble("Z",559);pending=mc.getSingleplayerServer().submit(()->ManualPlanner.action(Feedback211Visual.player(mc),t));}
 static void log(String s)throws Exception{System.out.println("SR211 FOLLOWUP "+s);Files.writeString(OUT.resolve("results.txt"),s+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
 static void next(int n){stage=n;ready=ticks+100;}
 static void shot(Minecraft mc,String name)throws Exception{try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(OUT.resolve(name+".png"));}log("SCREENSHOT "+name);}
 @SubscribeEvent public static void fog(net.minecraftforge.client.event.ViewportEvent.RenderFog e){if("followup".equals(System.getProperty("road.feedback211Resume"))){e.setNearPlaneDistance(10000);e.setFarPlaneDistance(11000);e.setCanceled(true);}}
 @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e){
  if(!"followup".equals(System.getProperty("road.feedback211Resume"))||done||e.phase!=TickEvent.Phase.END)return;var mc=Minecraft.getInstance();ticks++;
  try{
   if(!started){if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;started=true;Files.createDirectories(OUT);deadline=System.nanoTime()+1800_000_000_000L;
    mc.options.pauseOnLostFocus=false;mc.options.cloudStatus().set(CloudStatus.OFF);mc.options.renderDistance().set(16);mc.options.framerateLimit().set(60);mc.options.enableVsync().set(false);mc.options.guiScale().set(2);mc.resizeDisplay();mc.createWorldOpenFlows().loadLevel(mc.screen,"sr211-followup");log("OPEN CURRENT WORLD COPY");return;}
   if(System.nanoTime()>deadline)throw new AssertionError("timeout stage="+stage);
   if(mc.level==null||mc.player==null||mc.getOverlay()!=null||ticks<ready)return;
   mc.mouseHandler.releaseMouse();mc.player.setDeltaMovement(0,0,0);mc.player.getAbilities().flying=true;mc.player.setNoGravity(true);
   if(stage==0&&Files.exists(OUT.resolve("curve-choice-only"))){pending=mc.getSingleplayerServer().submit(()->Followup211Scene.curveChoice(Feedback211Visual.player(mc).serverLevel()));next(19);return;}
   if(stage==0&&Files.exists(OUT.resolve("endpoint-only"))){mc.setScreen(null);pending=mc.getSingleplayerServer().submit(()->Followup211Scene.endpoint(Feedback211Visual.player(mc)));next(12);return;}
   if(stage==0&&Files.exists(OUT.resolve("update-only"))){mc.setScreen(null);pending=mc.getSingleplayerServer().submit(()->Followup211Scene.rebuild(Feedback211Visual.player(mc).serverLevel(),true,"A"));next(4);return;}
   if(stage==0){mc.setScreen(null);mc.options.hideGui=true;Feedback211Visual.camera(mc,246,111,1185,260,66,1190);next(1);return;}
   if(stage==1){pending=mc.getSingleplayerServer().submit(()->Followup211Scene.preflight(Feedback211Visual.player(mc)));next(2);return;}
   if(stage==2){if(!pending.isDone())return;log(pending.join().toString());next(3);return;}
   if(stage==3){if(!(mc.screen instanceof InterchangeScreen))throw new AssertionError("SPUI host use did not open native interchange editor: "+mc.screen);var payload=Feedback211Visual.captured(mc);if(!payload.getUUID("Id").equals(UUID.fromString("c3ba2c13-3ca2-3977-9e62-80fbd51280c3")))throw new AssertionError("wrong SPUI selected");mc.options.hideGui=false;shot(mc,"01-spui-host-selected");log("SPUI NATIVE SELECTION PASS");mc.setScreen(null);pending=mc.getSingleplayerServer().submit(()->Followup211Scene.rebuild(Feedback211Visual.player(mc).serverLevel(),true,"A"));next(4);return;}
   if(stage==4||stage==6||stage==8||stage==10){if(!pending.isDone())return;mc.options.hideGui=false;mc.setScreen(new InterchangeScreen((net.minecraft.nbt.CompoundTag)pending.join()));next(stage+1);return;}
   if(stage==5||stage==7||stage==9||stage==11){
    String name=switch(stage){case 5->"single-A";case 7->"single-B";case 9->"double-A";default->"double-B";};
    String wanted=(stage==5||stage==9)?"主路定向左转":"主路左转回环";
    if(mc.screen.children().stream().noneMatch(c->c instanceof net.minecraft.client.gui.components.Button b&&b.getMessage().getString().startsWith(wanted)))throw new AssertionError("style label missing: "+wanted);
    if(mc.screen.children().stream().anyMatch(c->c instanceof net.minecraft.client.gui.components.Button b&&(b.getMessage().getString().startsWith("a ·")||b.getMessage().getString().startsWith("b ·"))))throw new AssertionError("letter prefix remains");
    shot(mc,"02-"+name);log("EXISTING "+name+" BUILD AND UI PASS");mc.setScreen(null);
    if(stage==11){pending=mc.getSingleplayerServer().submit(()->Followup211Scene.endpoint(Feedback211Visual.player(mc)));next(12);return;}
    boolean single=stage==5;String style=stage==7?"A":"B";pending=mc.getSingleplayerServer().submit(()->Followup211Scene.rebuild(Feedback211Visual.player(mc).serverLevel(),single,style));next(stage+1);return;
   }
   if(stage==12){if(!pending.isDone())return;pending.join();if(!(mc.screen instanceof RoadScreen))throw new AssertionError("reported endpoint editor failed: "+mc.screen);shot(mc,"03-endpoint-editor");log("REPORTED ENDPOINT EDITOR PASS");mc.setScreen(null);net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.getKey(org.lwjgl.glfw.GLFW.GLFW_KEY_P,0));next(13);return;}
   if(stage==13){if(!(mc.screen instanceof ManualPlannerScreen map)||map.loading())return;cachedRoads=new HashMap<>((Map<?,?>)field(map,"mapRoads"));manual(mc,"manualTool");next(14);return;}
   if(stage==14){if(!pending.isDone())return;pending.join();manual(mc,"manualClick");next(15);return;}
   if(stage==15||stage==16||stage==17){if(!pending.isDone())return;pending.join();var map=ManualPlannerClient.map;boolean present=((Map<?,?>)field(map,"mapNodes")).containsKey(net.minecraft.core.BlockPos.containing(-120,80,559).asLong());if(present!=(stage!=16))throw new AssertionError("point undo/redo state differs stage="+stage);var state=(net.minecraft.nbt.CompoundTag)field(map,"state");if(map.loading()||state.hasUUID("MapSnapshot"))throw new AssertionError("point edit requested full roads");var roads=(Map<?,?>)field(map,"mapRoads");if(!roads.equals(cachedRoads))throw new AssertionError("point edit replaced cached roads");log("POINT "+stage+" DELTA PASS cachedRoads="+roads.size());shot(mc,"04-point-"+stage);manual(mc,stage==16?"manualRedo":"manualUndo");next(stage+1);return;}
   if(stage==19){if(!pending.isDone())return;mc.setScreen(new InterchangeScreen((net.minecraft.nbt.CompoundTag)pending.join()));next(20);return;}
   if(stage==20){var o=(com.sora.splineroads.core.InterchangePlanner.Options)field(mc.screen,"options");if(o.preset()!=com.sora.splineroads.core.InterchangePlanner.Preset.DIAMOND)throw new AssertionError("unsupported curve changed selected preset");shot(mc,"05-curved-choice-kept");log("CURVED UNSUPPORTED UI CHOICE RETAINED PASS");done=true;mc.stop();return;}
   if(stage==18){if(!pending.isDone())return;pending.join();log("FOLLOWUP NATIVE ALL PASS");done=true;mc.stop();}
  }catch(Throwable f){done=true;f.printStackTrace();try{log("FAIL stage="+stage+" "+f);Files.writeString(OUT.resolve("failure.txt"),f.toString());}catch(Exception ignored){}mc.stop();}
 }
}
